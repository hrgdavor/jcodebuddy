import * as vscode from 'vscode';
import * as http from 'http';
import * as url from 'url';
import * as path from 'path';
import * as fs from 'fs';
import {
    BridgeConfig,
    RateLimiter,
    PRODUCTION_RATE_LIMIT,
    countAllowedOrigins,
    decideRoute,
    healthDocument
} from './BridgePolicy';

/**
 * The VS Code HTTP bridge: the fallback for a page opened in an ordinary browser, and the file server the
 * webview's iframe loads pages from.
 *
 * The authorization, CORS and rate-limit decisions live in `BridgePolicy`, which is tested without VS Code
 * against the shared vectors in `webview/conformance/bridge-decisions.json`. This class is the HTTP half:
 * routing, headers, and serving bytes.
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
                e.affectsConfiguration('webviewExplorer.allowedOrigins')
            ) {
                this.restartServer();
            }
        });
    }

    /** The configuration the policy functions read. */
    private config(): BridgeConfig {
        const configuration = vscode.workspace.getConfiguration('webviewExplorer');
        return {
            allowedOrigins: configuration.get<string>('allowedOrigins') || ''
        };
    }

    private startServer() {
        const config = vscode.workspace.getConfiguration('webviewExplorer');
        const port = config.get<number>('port') || 18882;

        this.server = http.createServer((req, res) => {
            this.handleRequest(req, res);
        });

        this.server.listen(port, '127.0.0.1', () => {
            console.log(`HTTP Bridge server listening on 127.0.0.1:${port}`);
        });

        this.server.on('error', (err) => {
            vscode.window.showErrorMessage(`Failed to start HTTP Bridge server: ${err.message}`);
        });
    }

    public stopServer() {
        if (this.server) {
            this.server.close();
            this.server = undefined;
        }
    }

    public restartServer() {
        this.stopServer();
        this.startServer();
    }

    private handleRequest(req: http.IncomingMessage, res: http.ServerResponse) {
        const parsedUrl = url.parse(req.url || '', true);
        const bridgeConfig = this.config();
        const origin = req.headers.origin;

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
            // The shared document shape, so a page reads the same keys from this host as from the Java ones.
            const document = healthDocument(
                this.boundPort(),
                countAllowedOrigins(bridgeConfig.allowedOrigins),
                false,
                ['open', 'serveFile']
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

    private boundPort(): number {
        const address = this.server?.address();
        return typeof address === 'object' && address ? address.port : 0;
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
