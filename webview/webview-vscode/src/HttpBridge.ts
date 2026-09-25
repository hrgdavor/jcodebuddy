import * as vscode from 'vscode';
import * as http from 'http';
import * as url from 'url';
import * as path from 'path';
import * as fs from 'fs';

export class HttpBridge {
    private server: http.Server | undefined;
    private requestTimestamps: number[] = [];
    private readonly RATE_LIMIT_COUNT = 20;
    private readonly RATE_LIMIT_WINDOW_MS = 20000;

    constructor() {
        this.startServer();

        // Listen for configuration changes
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('webviewExplorer.port')) {
                this.restartServer();
            }
        });
    }

    private startServer() {
        const config = vscode.workspace.getConfiguration('webviewExplorer');
        const port = config.get<number>('port') || 18882;

        this.server = http.createServer((req, res) => {
            this.handleRequest(req, res);
        });

        this.server.listen(port, () => {
            console.log(`HTTP Bridge server listening on port ${port}`);
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
        const reqPath = parsedUrl.pathname;

        // CORS headers
        const origin = req.headers.origin;
        if (origin && this.isOriginAllowed(origin)) {
            res.setHeader('Access-Control-Allow-Origin', origin);
            res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
            res.setHeader('Access-Control-Allow-Headers', '*');
        }

        if (req.method === 'OPTIONS') {
            res.writeHead(204);
            res.end();
            return;
        }

        if (reqPath === '/open' && req.method === 'GET') {
            if (origin && !this.isOriginAllowed(origin)) {
                res.writeHead(403);
                res.end('CORS Forbidden: Origin not allowed');
                return;
            }

            if (!this.checkRateLimit()) {
                res.writeHead(429);
                res.end('Too Many Requests: Rate limit exceeded');
                return;
            }

            const filePath = parsedUrl.query.filePath as string;
            const line = parseInt(parsedUrl.query.line as string) || 1;
            const column = parseInt(parsedUrl.query.column as string) || 1;

            if (filePath) {
                this.navigateToFile(filePath, line, column);
                res.writeHead(200);
                res.end(`Opening ${filePath}:${line}:${column}`);
            } else {
                res.writeHead(400);
                res.end('Missing filePath parameter');
            }
        } else if (reqPath?.startsWith('/file/') && req.method === 'GET') {
            // Allow all origins for the file server to support webview iframes
            res.setHeader('Access-Control-Allow-Origin', '*');

            // Extract file path from URL: /file/<encoded_path>
            // We use the raw URL to avoid issues with specialized characters passed in the path
            const match = req.url?.match(/^\/file\/(.+)$/);
            if (!match) {
                res.writeHead(400);
                res.end('Invalid file path');
                return;
            }

            let filePath = decodeURIComponent(match[1]);
            // Remove query parameters if any (though usually passed in path)
            if (filePath.includes('?')) {
                filePath = filePath.split('?')[0];
            }

            console.log(`[HttpBridge] Serving file: ${filePath}`);

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
                res.setHeader('Content-Length', stat.size);

                if (mimeType === 'text/html') {
                    // Inject bridge script for HTML files
                    let content = fs.readFileSync(filePath, 'utf8');
                    const bridgeScript = `
                        <script>
                            window.openFile = function(path, line, col) {
                                window.parent.postMessage({ type: 'openFile', filePath: path, line: line, column: col }, '*');
                            };
                        </script>
                     `;
                    content += bridgeScript;

                    res.setHeader('Content-Length', Buffer.byteLength(content));
                    res.end(content);
                } else {
                    const stream = fs.createReadStream(filePath);
                    stream.pipe(res);
                }
            } catch (err) {
                res.writeHead(500);
                res.end(`Error serving file: ${err}`);
            }
        } else {
            res.writeHead(404);
            res.end('Not Found');
        }
    }

    private isOriginAllowed(origin: string): boolean {
        const config = vscode.workspace.getConfiguration('webviewExplorer');
        const allowed = config.get<string>('allowedOrigins') || '';
        if (!allowed) return false;

        const origins = allowed.split(',').map(o => o.trim().toLowerCase());
        return origins.includes(origin.toLowerCase());
    }

    private checkRateLimit(): boolean {
        const now = Date.now();
        this.requestTimestamps = this.requestTimestamps.filter(t => now - t <= this.RATE_LIMIT_WINDOW_MS);

        if (this.requestTimestamps.length >= this.RATE_LIMIT_COUNT) {
            return false;
        }

        this.requestTimestamps.push(now);
        return true;
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
