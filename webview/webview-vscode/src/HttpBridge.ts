import * as vscode from 'vscode';
import * as http from 'http';
import * as url from 'url';
import * as path from 'path';
import * as fs from 'fs';
import * as crypto from 'crypto';
import {
    BridgeConfig,
    EditRequest,
    IDE_VSCODE,
    PLUGIN_VSCODE,
    RateLimiter,
    PRODUCTION_RATE_LIMIT,
    bufferEditBody,
    countAllowedOrigins,
    decideEdit,
    decideRoute,
    decideWriteRoute,
    healthDocument,
    mapEdits,
    normalizeProject,
    parseEditRequest,
    writeRefusalBody
} from './BridgePolicy';
import {
    boundPortOf,
    claimPort,
    closeQuietly,
    describeRequestedPort,
    descriptorFor,
    requestedPort,
    writePublished
} from './HostRegistration';

/**
 * The largest write body this host will read. An edit request is a range and some text; anything larger is not
 * one, and reading it unbounded would let a caller spend this process's memory.
 */
const MAX_WRITE_BODY = 1024 * 1024;

/**
 * The capabilities this host advertises, and the same list it publishes in its descriptor. `edit` is
 * advertised because the buffer path exists (`BridgePolicy.decideEdit` + `applyEdit` here); the rule this
 * codebase holds to is that a capability is advertised when it is implemented, and not before.
 */
const VSCODE_CAPABILITIES = ['edit', 'open', 'serveFile'];

/**
 * The VS Code HTTP bridge: the fallback for a page opened in an ordinary browser, and the file server the
 * webview's iframe loads pages from.
 *
 * The authorization, CORS and rate-limit decisions live in `BridgePolicy`, which is tested without VS Code
 * against the shared vectors in `webview/conformance/bridge-decisions.json`. This class is the HTTP half:
 * routing, headers, and serving bytes — plus the two things a host owes the project it serves, which are
 * decided in `HostRegistration`: the port it took, published in `.jcodebuddy/webview/host.json`, and the
 * refusal to take a second one when another editor is already serving this project.
 */
export class HttpBridge {
    private server: http.Server | undefined;
    private readonly rateLimiter = new RateLimiter(
        PRODUCTION_RATE_LIMIT.count,
        PRODUCTION_RATE_LIMIT.windowMillis
    );

    constructor() {
        this.startServer();

        // Listen for configuration changes
        vscode.workspace.onDidChangeConfiguration(e => {
            if (
                e.affectsConfiguration('webviewExplorer.port') ||
                e.affectsConfiguration('webviewExplorer.allowedOrigins') ||
                e.affectsConfiguration('webviewExplorer.token')
            ) {
                this.restartServer();
            }
        });
    }

    /** The configuration the policy functions read. */
    private config(): BridgeConfig {
        const configuration = vscode.workspace.getConfiguration('webviewExplorer');
        return {
            allowedOrigins: configuration.get<string>('allowedOrigins') || '',
            // The write routes require this (plan D8), so a host whose token cannot be configured cannot serve
            // them at all: without it every /api/v1/* request would answer 403 and the `edit` capability would
            // be advertised but unusable.
            token: configuration.get<string>('token') || ''
        };
    }

    /**
     * The project this bridge serves: the first workspace folder. Empty when no folder is open, in which case
     * there is no `.jcodebuddy/` to publish into and nothing to compare a second host against, so the
     * requested port is bound as it always was.
     */
    private projectRoot(): string {
        const folders = vscode.workspace.workspaceFolders;
        return folders && folders.length > 0 ? folders[0].uri.fsPath : '';
    }

    /**
     * The port to ask for, resolved by the shared rule (the same four sources as webview-core's
     * `HostConfig.requestedPort`): the setting the user made for this window, then the port this checkout is
     * currently on (`.jcodebuddy/webview/host.json` — local, never in git), then the project's committed
     * default (`.jcodebuddy/conf/webview.json` — optional, so a fresh clone needs no configuration), then the
     * conventional default.
     *
     * The setting is read through `inspect` rather than `get` because it declares a default: `get` cannot tell
     * "the user chose 18882" from "nobody chose anything and 18882 is the default", and only the first should
     * outrank what the project and the checkout say.
     */
    private requestedPort(): number {
        const configuration = vscode.workspace.getConfiguration('webviewExplorer');
        const inspected = configuration.inspect<number>('port');
        const explicit = inspected?.workspaceFolderValue ?? inspected?.workspaceValue ?? inspected?.globalValue;
        const project = this.projectRoot();
        if (project === '') {
            return typeof explicit === 'number' ? explicit : configuration.get<number>('port') || 18882;
        }
        const resolved = requestedPort(project, typeof explicit === 'number' ? explicit : null,
            configuration.get<number>('port') || 18882);
        if (resolved.problem !== '') {
            console.log(`HTTP Bridge: ${resolved.problem}`);
        }
        const note = describeRequestedPort(project, resolved);
        if (note !== '') {
            console.log(`HTTP Bridge: ${note}`);
        }
        return resolved.port;
    }

    private startServer() {
        const requestedPort = this.requestedPort();
        const project = this.projectRoot();

        const server = http.createServer((req, res) => {
            this.handleRequest(req, res);
        });
        this.server = server;

        if (project === '') {
            server.on('error', (err) => {
                vscode.window.showErrorMessage(`Failed to start HTTP Bridge server: ${err.message}`);
            });
            server.listen(requestedPort, '127.0.0.1', () => {
                console.log(`HTTP Bridge server listening on 127.0.0.1:${requestedPort}`);
            });
            return;
        }

        void this.claimAndListen(server, project, requestedPort);
    }

    /**
     * Takes a port for this project, or declines to serve at all.
     *
     * Three outcomes other than "listening on the port you configured" are normal, and only the third is an
     * error: the requested port was held by an unrelated application, so the next free one was taken (and
     * published); another editor is already serving **this** project, so this host opens nothing — a page that
     * finds two bridges for one project has no rule for choosing; or the project **pinned** its port
     * (`sticky` in its `host.json`) and it could not be had, which is reported rather than quietly worked
     * around.
     */
    private async claimAndListen(server: http.Server, project: string, requestedPort: number) {
        try {
            const decision = await claimPort(server, project, requestedPort);
            if (decision.action === 'fail') {
                await closeQuietly(server);
                this.server = undefined;
                console.log(`HTTP Bridge not started: ${decision.reason}`);
                vscode.window.showErrorMessage(`WebView Explorer: ${decision.reason}`);
                return;
            }
            if (decision.action === 'skip') {
                await closeQuietly(server);
                this.server = undefined;
                console.log(`HTTP Bridge not started: ${decision.reason}`);
                vscode.window.showWarningMessage(`WebView Explorer: ${decision.reason}`);
                return;
            }
            const port = boundPortOf(server) || decision.port;
            if (decision.tried > 1 || decision.sticky) {
                console.log(`HTTP Bridge: ${decision.reason}`);
            }
            // Registered only once the claim has settled: a bind failure during the claim is the claim's own
            // business (it steps over the port), and reporting it here would tell the user about a port the
            // bridge deliberately did not take.
            server.on('error', (err) => {
                vscode.window.showErrorMessage(`HTTP Bridge server error: ${err.message}`);
            });
            this.publish(project, port, decision.sticky);
            console.log(`HTTP Bridge server listening on 127.0.0.1:${port}`);
        } catch (error) {
            await closeQuietly(server);
            this.server = undefined;
            vscode.window.showErrorMessage(
                `Failed to start HTTP Bridge server: ${(error as Error).message}`);
        }
    }

    /** Publishes the port, so a page or a second host finds it without being told. */
    private publish(project: string, port: number, sticky: boolean) {
        try {
            writePublished(project, descriptorFor(project, PLUGIN_VSCODE, IDE_VSCODE, port,
                VSCODE_CAPABILITIES, {
                    name: 'vscode',
                    available: true,
                    lineNavigation: 'exact',
                    note: 'the editor opens the document and places the caret'
                }, '', sticky));
        } catch (error) {
            // A project that cannot be written to (read-only checkout, no permission) still serves pages: the
            // descriptor is a convenience for other tools, not a precondition for this one.
            console.log(`HTTP Bridge: could not publish the port in ${normalizeProject(project)}:`
                + ` ${(error as Error).message}`);
        }
    }

    public stopServer() {
        if (this.server) {
            this.server.close();
            this.server = undefined;
        }
        // The published record is left in place on purpose: it is the workspace's port, not this process's —
        // what the next start asks for, and where a `sticky` pin lives. "Is a bridge there?" is answered by
        // probing the port, so a record left by a stopped host misleads nobody.
    }

    public restartServer() {
        this.stopServer();
        this.startServer();
    }

    private handleRequest(req: http.IncomingMessage, res: http.ServerResponse) {
        const parsedUrl = url.parse(req.url || '', true);
        const bridgeConfig = this.config();
        const origin = req.headers.origin;

        // The write routes are decided first and separately: they require the token (an allowed Origin is not
        // enough for a state-changing route, plan D8) and they carry a JSON body, which the read routes do not.
        if (parsedUrl.pathname && parsedUrl.pathname.startsWith('/api/v1/')) {
            this.handleWrite(req, res, parsedUrl.pathname, bridgeConfig, origin);
            return;
        }

        // The rate limit is acquired here rather than inside the route, so the decision function can be
        // tested with "would this be refused" as an input instead of by exhausting a limiter.
        const rateLimited = !this.rateLimiter.tryAcquire();
        const route = decideRoute(req.method, parsedUrl.pathname, {
            filePath: parsedUrl.query.filePath,
            line: parsedUrl.query.line,
            column: parsedUrl.query.column
        }, bridgeConfig, origin, null, rateLimited);

        if (route.action === 'options') {
            if (route.corsGrant) {
                this.sendCorsHeaders(res, route.corsGrant);
                res.writeHead(204);
            } else {
                res.writeHead(403);
            }
            res.end();
            return;
        }

        if (route.action === 'health') {
            // The shared document shape, so a page reads the same keys from this host as from the Java ones —
            // including `ide` and `project`, which is how a second host for this project recognises this one
            // and declines to open a bridge of its own.
            const document = healthDocument(
                this.boundPort(),
                countAllowedOrigins(bridgeConfig.allowedOrigins),
                // Honest, and it was not: a token that is set must be reported as required, or a page cannot
                // tell whether presenting one is the way in.
                (bridgeConfig.token ?? '') !== '',
                VSCODE_CAPABILITIES,
                IDE_VSCODE,
                this.projectRoot()
            );
            res.setHeader('Content-Type', 'application/json');
            res.writeHead(200);
            res.end(JSON.stringify(document));
            return;
        }

        if (route.action === 'forbidden') {
            if (route.reason === 'rate') {
                res.writeHead(429);
                res.end(`Too Many Requests: ${PRODUCTION_RATE_LIMIT.count} per `
                    + `${PRODUCTION_RATE_LIMIT.windowMillis / 1000}s`);
            } else {
                // No CORS headers: an uninvited caller must not even be able to read the status.
                res.writeHead(403);
                res.end('Forbidden: configure webviewExplorer.allowedOrigins');
            }
            return;
        }

        if (route.action === 'methodNotAllowed') {
            res.writeHead(405);
            res.end('Method Not Allowed');
            return;
        }

        if (route.action === 'open') {
            if (route.corsGrant) {
                this.sendCorsHeaders(res, route.corsGrant);
            }
            // Authorization and the rate limit were settled by decideRoute; from here the request is
            // accepted, which is why the answer no longer claims success before trying.
            this.navigateToFile(route.filePath, route.line, route.column);
            res.writeHead(200);
            res.end(`Opening ${route.filePath}:${route.line}:${route.column}`);
            return;
        }

        if (route.action === 'serve') {
            if (route.corsGrant) {
                this.sendCorsHeaders(res, route.corsGrant);
            }
            this.serveFile(res, route.filePath);
            return;
        }

        res.writeHead(404);
        res.end('Not Found');
    }

    private sendCorsHeaders(res: http.ServerResponse, grant: string) {
        res.setHeader('Access-Control-Allow-Origin', grant);
        res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', '*');
    }

    /**
     * The port this bridge actually serves on, or 0 when it is not listening.
     *
     * Public because the webview's own URL rewriting needs it: the configured port is a *request*, and the
     * port that was free may be a different one, so a `/file/…` URL built from the setting would point at
     * whatever else holds that port.
     */
    public boundPort(): number {
        return this.server ? boundPortOf(this.server) : 0;
    }

    /**
     * The write verbs: `applyEdit` into the editor's buffer, and a refusal for the three that only make sense
     * for a host that owns the file.
     *
     * This is the thin half — read the body, hash the file, call the decision, and make the one VS Code call.
     * The reading, the digest comparison and the statuses are `BridgePolicy`, which the unit test asserts without
     * a VS Code runtime; the single platform call is `vscode.workspace.applyEdit`.
     */
    private handleWrite(req: http.IncomingMessage, res: http.ServerResponse, pathname: string,
                        bridgeConfig: BridgeConfig, origin: string | null | undefined) {
        const suppliedToken = (req.headers['x-webview-token'] as string | undefined) ?? undefined;
        const route = decideWriteRoute(req.method, pathname, bridgeConfig, origin, suppliedToken);
        const json = (status: number, body: string) => {
            res.setHeader('Content-Type', 'application/json');
            res.writeHead(status);
            res.end(body);
        };

        if (route.action === 'notFound') {
            res.writeHead(404);
            res.end('Not Found');
            return;
        }
        if (route.action === 'methodNotAllowed') {
            res.writeHead(405);
            res.end('Method Not Allowed');
            return;
        }
        if (route.action === 'forbidden') {
            res.writeHead(403);
            res.end('Forbidden: the token is required for state-changing routes');
            return;
        }
        if (route.corsGrant) {
            this.sendCorsHeaders(res, route.corsGrant);
        }

        if (route.verb !== 'applyEdit') {
            // diff, undo and redo are the file owner's verbs: a diff needs the bytes, and undo needs the
            // checkpoints a disk write records. This host has an editor whose own undo is the reader's review,
            // so it names the host that can do the rest instead of pretending.
            json(409, writeRefusalBody('no-disk-write',
                `'${route.verb}' needs a host that owns the file (its diff and its undo history): this host `
                + 'edits the editor\'s buffer, so ask a host that serves the project, such as webviewd'));
            return;
        }

        let body = '';
        req.on('data', (chunk) => {
            body += chunk;
            if (body.length > MAX_WRITE_BODY) {
                req.destroy();
            }
        });
        req.on('end', async () => {
            const parsed = parseEditRequest(body, true);
            if (!parsed.ok) {
                json(parsed.status, writeRefusalBody(parsed.reason, parsed.detail));
                return;
            }
            const digest = this.digestOf(parsed.request.filePath);
            const hasEditor = this.hasOpenDocument(parsed.request.filePath);
            const decision = decideEdit(parsed.request, digest, hasEditor);
            if (!decision.ok) {
                json(decision.status, writeRefusalBody(decision.reason, decision.detail, decision.digest));
                return;
            }
            if (!decision.apply) {
                // A proposal: the digest and the fact that nothing was written. This host does not produce a
                // unified diff (that is the file owner's job) — the editor's own undo is the review step here.
                json(200, JSON.stringify({
                    applied: false,
                    target: 'buffer',
                    digest,
                    detail: 'dryRun: nothing was written; this host applies into the editor buffer where the '
                        + 'editor\'s own undo is the review step'
                }));
                return;
            }
            const applied = await this.applyToBuffer(parsed.request);
            if (!applied) {
                json(409, writeRefusalBody('no-buffer-edit', 'VS Code refused the workspace edit'));
                return;
            }
            json(200, bufferEditBody(digest ?? '', true,
                "applied in the editor's buffer; the file on disk is unchanged until the editor saves, and the "
                + "reader can undo it with the editor's own undo"));
        });
    }

    /** `sha256:<hex>` over the file's bytes, or null when it cannot be read. */
    private digestOf(filePath: string): string | null {
        try {
            const bytes = fs.readFileSync(filePath);
            return `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;
        } catch {
            return null;
        }
    }

    /** Whether this file has an open text document, which is what a buffer edit needs. */
    private hasOpenDocument(filePath: string): boolean {
        return vscode.workspace.textDocuments.some((document) => {
            const candidate = document.uri.fsPath.replace(/\\/g, '/');
            return candidate.toLowerCase() === filePath.replace(/\\/g, '/').toLowerCase();
        });
    }

    /** The one platform call: a `WorkspaceEdit` full of ranges, which VS Code applies to its buffers. */
    private async applyToBuffer(request: EditRequest): Promise<boolean> {
        const document = vscode.workspace.textDocuments.find((candidate) => {
            const path = candidate.uri.fsPath.replace(/\\/g, '/');
            return path.toLowerCase() === request.filePath.replace(/\\/g, '/').toLowerCase();
        });
        if (!document) {
            return false;
        }
        const workspaceEdit = new vscode.WorkspaceEdit();
        for (const mapped of mapEdits(request.edits)) {
            const range = new vscode.Range(
                mapped.range.start.line, mapped.range.start.character,
                mapped.range.end.line, mapped.range.end.character);
            workspaceEdit.replace(document.uri, range, mapped.newText);
        }
        return vscode.workspace.applyEdit(workspaceEdit);
    }

    /** Serves one file that {@link decideRoute} has already authorized and decoded. */
    private serveFile(res: http.ServerResponse, filePath: string) {
        if (filePath.length === 0) {
            res.writeHead(400);
            res.end('Invalid file path');
            return;
        }

        if (!fs.existsSync(filePath)) {
            res.writeHead(404);
            res.end(`File not found: ${filePath}`);
            return;
        }

        try {
            const stat = fs.statSync(filePath);
            if (stat.isDirectory()) {
                res.writeHead(403);
                res.end('Directories not supported');
                return;
            }

            const mimeType = this.getMimeType(filePath);
            res.setHeader('Content-Type', mimeType);

            if (mimeType === 'text/html') {
                let content = fs.readFileSync(filePath, 'utf8');
                // The injected bridge, for pages served from this route into the webview's iframe. The
                // contract is the same one webview-core's InjectedBridge produces; this host cannot import
                // the Java module, and its copy is asserted against the same vectors as the Java one.
                const bridgeScript = `
                    <script>
                        window.__jcbWebViewBridge = 1;
                        window.openFile = function(path, line, col) {
                            window.parent.postMessage(
                                { type: 'openFile', filePath: path, line: line, column: col }, '*');
                        };
                    </script>
                `;
                content += bridgeScript;
                res.setHeader('Content-Length', Buffer.byteLength(content));
                res.end(content);
            } else {
                res.setHeader('Content-Length', stat.size);
                fs.createReadStream(filePath).pipe(res);
            }
        } catch (err) {
            res.writeHead(500);
            res.end(`Error serving file: ${err}`);
        }
    }

    public async navigateToFile(filePath: string, line: number, column: number) {
        let fullPath = filePath;
        if (!path.isAbsolute(filePath)) {
            const workspaceFolders = vscode.workspace.workspaceFolders;
            if (workspaceFolders) {
                fullPath = path.join(workspaceFolders[0].uri.fsPath, filePath);
            }
        }

        try {
            const uri = vscode.Uri.file(fullPath);
            const document = await vscode.workspace.openTextDocument(uri);
            const editor = await vscode.window.showTextDocument(document);

            const position = new vscode.Position(Math.max(0, line - 1), Math.max(0, column - 1));
            editor.selection = new vscode.Selection(position, position);
            editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open file: ${filePath}`);
        }
    }

    private getMimeType(filePath: string): string {
        const ext = path.extname(filePath).toLowerCase();
        const map: { [key: string]: string } = {
            '.html': 'text/html',
            '.htm': 'text/html',
            '.js': 'text/javascript',
            '.css': 'text/css',
            '.json': 'application/json',
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg',
            '.gif': 'image/gif',
            '.svg': 'image/svg+xml',
            '.txt': 'text/plain',
            '.xml': 'text/xml',
            '.pdf': 'application/pdf'
        };
        return map[ext] || 'application/octet-stream';
    }
}
