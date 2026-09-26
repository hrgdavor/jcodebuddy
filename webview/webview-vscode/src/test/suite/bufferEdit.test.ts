import * as assert from 'assert';
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as http from 'http';
import { createHash } from 'crypto';

/**
 * The buffer path, in a real VS Code, against the extension's own activated bridge.
 *
 * This is the observation the maintainer made by hand on 2026-09-26 (a page's edit landed in the editor, the
 * buffer was unsaved, the editor's own undo took it back) turned into something that runs on every `npm test`.
 * It is deliberately not a unit test of `BridgePolicy`: those 161 assertions already cover the decisions. What
 * this file adds is the part only a running editor can answer — that `vscode.workspace.applyEdit` actually
 * changed the document, that the document is *dirty* rather than saved, that the bytes on disk did not move, and
 * that the editor's own undo is what puts the text back.
 *
 * It talks to the extension's real HTTP surface over `node:http` rather than calling internals, because the
 * contract a page uses is the HTTP one; a refactor that breaks the route while keeping the classes intact must
 * fail here.
 */

/** Away from 18882, so a developer's own running instance cannot be mistaken for this one. */
const PORT = 18899;
const TOKEN = 'buffer-edit-integration-test';
const EXTENSION_ID = 'davorhrg.vscode-webview-explorer';

interface Answer {
    status: number;
    body: unknown;
    raw: string;
}

/** A POST that returns the host's own status and parsed body, whatever they are (a refusal is not a crash). */
function post(route: string, payload: unknown, token: string | null = TOKEN): Promise<Answer> {
    const data = JSON.stringify(payload);
    return new Promise((resolve, reject) => {
        const request = http.request({
            host: '127.0.0.1',
            port: PORT,
            path: route,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(data),
                ...(token === null ? {} : { 'X-WebView-Token': token })
            }
        }, response => {
            let raw = '';
            response.on('data', chunk => { raw += chunk; });
            response.on('end', () => {
                let body: unknown = raw;
                try {
                    body = JSON.parse(raw);
                } catch {
                    // A refusal may be plain text ("Forbidden: ..."), which is itself the assertion in places.
                }
                resolve({ status: response.statusCode ?? 0, body, raw });
            });
        });
        request.on('error', reject);
        request.write(data);
        request.end();
    });
}

function get(route: string): Promise<Answer> {
    return new Promise((resolve, reject) => {
        const request = http.request({ host: '127.0.0.1', port: PORT, path: route, method: 'GET' },
            response => {
                let raw = '';
                response.on('data', chunk => { raw += chunk; });
                response.on('end', () => {
                    let body: unknown = raw;
                    try {
                        body = JSON.parse(raw);
                    } catch {
                        // /health is JSON; anything else here is worth seeing as text.
                    }
                    resolve({ status: response.statusCode ?? 0, body, raw });
                });
            });
        request.on('error', reject);
        request.end();
    });
}

async function waitForHealth(timeoutMillis = 20000): Promise<Answer> {
    const deadline = Date.now() + timeoutMillis;
    let lastError = 'no attempt made';
    while (Date.now() < deadline) {
        try {
            const answer = await get('/health');
            if (answer.status === 200) {
                return answer;
            }
            lastError = `HTTP ${answer.status}`;
        } catch (error) {
            lastError = (error as Error).message;
        }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error(`the bridge never answered /health on 127.0.0.1:${PORT} (${lastError})`);
}

const digestOf = (file: string): string =>
    'sha256:' + createHash('sha256').update(fs.readFileSync(file)).digest('hex');

suite('buffer edit over the HTTP write API', () => {
    let folder = '';
    let file = '';
    let document: vscode.TextDocument;
    const original = 'class Sample {\n    int x = 1;\n}\n';
    /** `int x = 1;` on line 2: one-based, columns 5..15, end exclusive — the shape the write contract uses. */
    const edit = { startLine: 2, startColumn: 5, endLine: 2, endColumn: 15, newText: 'int x = 42;' };
    const filePath = () => file.replace(/\\/g, '/');

    suiteSetup(async function () {
        this.timeout(60000);
        folder = fs.mkdtempSync(path.join(os.tmpdir(), 'webview-buffer-edit-'));
        file = path.join(folder, 'Sample.java');
        fs.writeFileSync(file, original, 'utf8');

        // Configure before activating: the extension restarts the bridge when the port or token changes, so this
        // is what moves it off the default 18882 and onto the token every write route requires.
        const configuration = vscode.workspace.getConfiguration('webviewExplorer');
        await configuration.update('port', PORT, vscode.ConfigurationTarget.Global);
        await configuration.update('token', TOKEN, vscode.ConfigurationTarget.Global);

        const extension = vscode.extensions.getExtension(EXTENSION_ID);
        assert.ok(extension, `${EXTENSION_ID} must be present: this host runs with --extensionDevelopmentPath`);
        await extension.activate();

        // The buffer path needs an open document, which is the one host-specific requirement of this host.
        document = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
        await vscode.window.showTextDocument(document);
        await waitForHealth();
    });

    suiteTeardown(async () => {
        const configuration = vscode.workspace.getConfiguration('webviewExplorer');
        await configuration.update('port', undefined, vscode.ConfigurationTarget.Global);
        await configuration.update('token', undefined, vscode.ConfigurationTarget.Global);
        fs.rmSync(folder, { recursive: true, force: true });
    });

    setup(async () => {
        // Each test starts from the file's original text, with the undo stack emptied by a revert.
        if (document.isDirty) {
            await vscode.commands.executeCommand('workbench.action.files.revert');
        }
        fs.writeFileSync(file, original, 'utf8');
    });

    test('the host declares edit and says a token is required', async () => {
        const health = await waitForHealth();
        const body = health.body as { capabilities?: string[]; tokenRequired?: boolean };
        assert.ok(body.capabilities?.includes('edit'),
            `a host that serves /api/v1/applyEdit must declare edit: ${JSON.stringify(body)}`);
        assert.strictEqual(body.tokenRequired, true,
            'and it must say that a token is required, because the write routes refuse everyone without one');
    });

    test('a write route without the token is refused', async () => {
        const answer = await post('/api/v1/applyEdit',
            { filePath: filePath(), expectedDigest: digestOf(file), edits: [edit], dryRun: false }, null);
        assert.strictEqual(answer.status, 403, `expected 403, got ${answer.status}: ${answer.raw}`);
        assert.ok(answer.raw.includes('token is required'), answer.raw);
        assert.strictEqual(document.getText(), original, 'and the refusal changed nothing');
    });

    test('an edit lands in the buffer, unsaved, with the disk untouched', async () => {
        const before = digestOf(file);
        const answer = await post('/api/v1/applyEdit',
            { filePath: filePath(), expectedDigest: before, edits: [edit], dryRun: false, target: 'auto' });
        const body = answer.body as { applied?: boolean; target?: string };

        assert.strictEqual(answer.status, 200, `the edit must be applied: ${answer.raw}`);
        assert.strictEqual(body.applied, true, answer.raw);
        assert.strictEqual(body.target, 'buffer',
            'this host owns no bytes, so the change belongs in the editor: ' + answer.raw);

        assert.ok(document.getText().includes('int x = 42;'),
            `the editor must show the change: ${JSON.stringify(document.getText())}`);
        assert.strictEqual(document.isDirty, true,
            'the buffer must be dirty: an edit that quietly saved itself would not be reviewable');
        assert.strictEqual(digestOf(file), before,
            'and the bytes on disk must not have moved before the reader saves');
    });

    test("the change is in the editor's own undo stack", async () => {
        const before = digestOf(file);
        await post('/api/v1/applyEdit',
            { filePath: filePath(), expectedDigest: before, edits: [edit], dryRun: false, target: 'auto' });
        assert.ok(document.getText().includes('int x = 42;'), 'precondition: the edit is in the buffer');

        await vscode.commands.executeCommand('undo');

        assert.ok(document.getText().includes('int x = 1;'),
            `one undo must restore the original text: ${JSON.stringify(document.getText())}`);
        assert.strictEqual(digestOf(file), before,
            'and the disk still holds the original bytes: the host never wrote it, and the undo is the editor\'s');
    });

    test('a stale digest is refused rather than merged', async () => {
        const answer = await post('/api/v1/applyEdit', {
            filePath: filePath(),
            expectedDigest: 'sha256:' + '0'.repeat(64),
            edits: [edit],
            dryRun: false,
            target: 'auto'
        });
        const body = answer.body as { reason?: string };
        assert.strictEqual(answer.status, 409, `expected 409, got ${answer.status}: ${answer.raw}`);
        assert.strictEqual(body.reason, 'stale', answer.raw);
        assert.strictEqual(document.getText(), original, 'and the editor was left alone');
    });

    test('the verbs this host does not own are refused by name', async () => {
        // A diff needs bytes, and an undo history belongs to whoever wrote them; this host has neither, and says
        // which host does instead of pretending.
        for (const route of ['/api/v1/diff', '/api/v1/undo', '/api/v1/redo']) {
            const answer = await post(route, { filePath: filePath() });
            assert.strictEqual(answer.status, 409, `${route} must be refused: ${answer.raw}`);
            const body = answer.body as { reason?: string; detail?: string };
            assert.strictEqual(body.reason, 'no-disk-write', `${route}: ${answer.raw}`);
            assert.ok(body.detail?.includes('webviewd'),
                `${route} must name the host that can do it: ${answer.raw}`);
        }
    });
});
