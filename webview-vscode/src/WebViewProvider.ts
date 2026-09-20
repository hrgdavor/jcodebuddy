import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { HttpBridge } from './HttpBridge';

export class WebViewProvider implements vscode.WebviewViewProvider {
    public static readonly viewType = 'webview-explorer-view';
    private _view?: vscode.WebviewView;

    constructor(
        private readonly _extensionUri: vscode.Uri,
        private readonly _context: vscode.ExtensionContext,
        private readonly _httpBridge: HttpBridge
    ) { }

    public resolveWebviewView(
        webviewView: vscode.WebviewView,
    ) {
        this._view = webviewView;

        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [
                this._extensionUri
            ]
        };

        webviewView.webview.html = this._getHtmlForWebview();

        webviewView.webview.onDidReceiveMessage(data => {
            switch (data.type) {
                case 'openFile':
                    this._httpBridge.navigateToFile(data.filePath, data.line, data.column);
                    break;
                case 'saveUrl':
                    this._context.globalState.update('lastUrl', data.url);
                    break;
                case 'enterUrl':
                    this.loadUrl(data.url);
                    break;
            }
        });

        // Restore last URL
        const lastUrl = this._context.globalState.get<string>('lastUrl') || '';
        if (lastUrl) {
            this.loadUrl(lastUrl);
        }
    }

    public refresh() {
        if (this._view) {
            this._view.webview.postMessage({ type: 'refresh' });
        }
    }

    public loadUrl(url: string) {
        if (!this._view) {
            return;
        }

        let targetUrl = url;
        console.log(`[WebViewExplorer] Loading URL: ${url}`);

        if (url.startsWith('file://')) {
            const uri = vscode.Uri.parse(url);
            const filePath = uri.fsPath;
            if (!fs.existsSync(filePath)) {
                console.error(`[WebViewExplorer] File not found: ${filePath}`);
                vscode.window.showErrorMessage(`File not found: ${filePath}`);
                return;
            }

            const port = vscode.workspace.getConfiguration('webviewExplorer').get<number>('port') || 18882;
            // Normalize path separators to forward slashes and encode each segment to allow directory traversal in browser
            const normalizedPath = filePath.replace(/\\/g, '/');
            const encodedPath = normalizedPath.split('/').map(s => encodeURIComponent(s)).join('/');

            targetUrl = `http://localhost:${port}/file/${encodedPath}`;
            if (uri.query) targetUrl += `?${uri.query}`;
            if (uri.fragment) targetUrl += `#${uri.fragment}`;

            console.log(`[WebViewExplorer] Proxied URL (from file://): ${targetUrl}`);
        } else if (!url.startsWith('http://') && !url.startsWith('https://')) {
            // Check if it's a local file path
            try {
                const cleanPath = url.trim().replace(/^["']/, '').replace(/["']$/, '');
                if (path.isAbsolute(cleanPath) || cleanPath.includes('\\') || cleanPath.includes('/')) {
                    const uri = vscode.Uri.file(path.normalize(cleanPath));
                    if (fs.existsSync(uri.fsPath)) {
                        const port = vscode.workspace.getConfiguration('webviewExplorer').get<number>('port') || 18882;
                        // Normalize and encode segments
                        const normalizedPath = uri.fsPath.replace(/\\/g, '/');
                        const encodedPath = normalizedPath.split('/').map(s => encodeURIComponent(s)).join('/');

                        targetUrl = `http://localhost:${port}/file/${encodedPath}`;
                        console.log(`[WebViewExplorer] Proxied URL (from path): ${targetUrl}`);
                    } else {
                        targetUrl = 'https://' + url;
                    }
                } else {
                    targetUrl = 'https://' + url;
                }
            } catch (e) {
                targetUrl = 'https://' + url;
            }
        }

        this._view.webview.postMessage({
            type: 'loadUrl',
            url: targetUrl,
            originalUrl: url
        });
        this._context.globalState.update('lastUrl', url);
        this._view.show?.(true);
    }

    private _getHtmlForWebview() {
        return `<!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta http-equiv="Content-Security-Policy" content="default-src * 'unsafe-inline' 'unsafe-eval' data: blob:; frame-src *; img-src *; style-src * 'unsafe-inline'; script-src * 'unsafe-inline' 'unsafe-eval'; connect-src *;">
                <style>
                    body { font-family: var(--vscode-font-family); padding: 0; margin: 0; display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
                    .toolbar { display: flex; padding: 4px; background: var(--vscode-editor-background); border-bottom: 1px solid var(--vscode-panel-border); align-items: center; gap: 4px; }
                    .toolbar input { flex: 1; min-width: 0; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border); padding: 2px 4px; font-size: 12px; }
                    .toolbar button { background: none; border: none; color: var(--vscode-foreground); cursor: pointer; padding: 2px; display: flex; align-items: center; justify-content: center; }
                    .toolbar button:hover { background: var(--vscode-toolbar-hoverBackground); }
                    .toolbar button svg { width: 16px; height: 16px; fill: currentColor; }
                    iframe { flex: 1; border: none; width: 100%; height: 100%; background: white; }
                </style>
            </head>
            <body>
                <div class="toolbar">
                    <button id="back" title="Back"><svg viewBox="0 0 16 16"><path d="M10.78 13.28a.75.75 0 0 1-1.06 0L4.97 8.53a.75.75 0 0 1 0-1.06l4.75-4.75a.75.75 0 1 1 1.06 1.06L6.56 8l4.22 4.22a.75.75 0 0 1 0 1.06z"/></svg></button>
                    <button id="forward" title="Forward"><svg viewBox="0 0 16 16"><path d="M5.22 13.28a.75.75 0 0 0 1.06 0l4.75-4.75a.75.75 0 0 0 0-1.06L6.28 2.72a.75.75 0 1 0-1.06 1.06L9.44 8l-4.22 4.22a.75.75 0 0 0 0 1.06z"/></svg></button>
                    <button id="reload" title="Reload"><svg viewBox="0 0 16 16"><path d="M8 2a6 6 0 1 0 5.46 3.51.75.75 0 1 1 1.34-.67A7.5 7.5 0 1 1 2.5 5.56V4.25a.75.75 0 0 1 1.5 0v3.5a.75.75 0 0 1-.75.75h-3.5a.75.75 0 0 1 0-1.5h1.76A6 6 0 0 0 8 2z"/></svg></button>
                    <input type="text" id="address-bar" placeholder="Enter URL...">
                </div>
                <iframe id="browser" src="about:blank"></iframe>

                <script>
                    const vscode = acquireVsCodeApi();
                    const browser = document.getElementById('browser');
                    const addressBar = document.getElementById('address-bar');
                    const backBtn = document.getElementById('back');
                    const forwardBtn = document.getElementById('forward');
                    const reloadBtn = document.getElementById('reload');

                    addressBar.addEventListener('keydown', (e) => {
                        if (e.key === 'Enter') {
                            vscode.postMessage({ type: 'enterUrl', url: addressBar.value });
                        }
                    });

                    backBtn.addEventListener('click', () => {
                        try { window.history.back(); } catch(e) {}
                    });
                    forwardBtn.addEventListener('click', () => {
                        try { window.history.forward(); } catch(e) {}
                    });
                    reloadBtn.addEventListener('click', () => {
                        browser.src = browser.src;
                    });

                    window.addEventListener('message', event => {
                        const message = event.data;
                        console.log('WebView received message:', message);
                        switch (message.type) {
                            case 'loadUrl':
                                console.log('Loading URL into iframe:', message.url);
                                browser.src = message.url;
                                addressBar.value = message.originalUrl || message.url;
                                break;
                            case 'refresh':
                                console.log('Refreshing iframe');
                                browser.src = browser.src;
                                break;
                        }
                    });

                    browser.addEventListener('load', () => {
                        console.log('Iframe loaded event fired. Current src:', browser.src);
                        try {
                            console.log('Iframe contentWindow:', browser.contentWindow);
                            console.log('Iframe origin:', browser.contentWindow.origin);
                        } catch(e) {
                            console.log('Iframe content is cross-origin or denied access');
                        }
                    });

                    browser.addEventListener('error', (e) => {
                        console.error('Iframe error event fired:', e);
                    });

                    // Bridge for the content inside iframe
                    window.addEventListener('message', event => {
                        if (event.data && event.data.type === 'openFile') {
                            vscode.postMessage(event.data);
                        }
                    });

                    browser.onload = () => {
                        // Inject bridge into same-origin iframes
                        try {
                            browser.contentWindow.openFile = function(path, line, col) {
                                vscode.postMessage({ type: 'openFile', filePath: path, line: line, column: col });
                            };
                        } catch(e) {
                            // Cross-origin iframe, cannot inject directly
                        }
                    };
                </script>
            </body>
            </html>`;
    }
}
