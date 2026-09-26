import * as assert from 'assert';
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { WebViewProvider } from '../../WebViewProvider';
import { HttpBridge } from '../../HttpBridge';
import * as sinon from 'sinon';

/**
 * What the address bar does with what a user types.
 *
 * These tests asserted `asWebviewUri` conversions until 2026-09-26, and had been failing ever since the provider
 * started **proxying local files through the HTTP bridge** instead: a `file://` URL (or a bare absolute path) that
 * exists becomes `http://localhost:<port>/file/<encoded path>`, which is what makes the same page work with the
 * bridge's authorization and its injected client. One assertion was left behind asserting the old shape, so the
 * integration suite was red — with a single other test in it, nobody noticed. The assertion now says what the
 * provider actually promises, and the bridge each test constructs is stopped again so the suite does not leave
 * listeners behind on a port a developer's own VS Code may be using.
 */
suite('WebView Explorer Test Suite', () => {
    let context: vscode.ExtensionContext;
    let httpBridge: HttpBridge;
    let provider: WebViewProvider;
    let folder = '';

    /** The webview a view resolution produces, with the message channel recorded. */
    function fakeWebview(postMessage: sinon.SinonStub) {
        return {
            webview: {
                options: {},
                html: '',
                asWebviewUri: (uri: vscode.Uri) => uri,
                postMessage,
                onDidReceiveMessage: sinon.stub(),
                onDidChangeVisibility: sinon.stub(),
                cspSource: 'vscode-webview://test'
            },
            onDidDispose: sinon.stub(),
            onDidChangeVisibility: sinon.stub()
        } as unknown as vscode.WebviewView;
    }

    setup(() => {
        folder = fs.mkdtempSync(path.join(os.tmpdir(), 'webview-provider-'));
        context = {
            globalState: {
                get: sinon.stub(),
                update: sinon.stub()
            } as unknown as vscode.Memento,
            extensionUri: vscode.Uri.file(folder)
        } as unknown as vscode.ExtensionContext;
        httpBridge = new HttpBridge();
        provider = new WebViewProvider(context.extensionUri, context, httpBridge);
    });

    teardown(() => {
        // Each test constructs a bridge, which starts a listener; stopping it keeps the next one (and the
        // developer's own running extension) from fighting over the port.
        httpBridge.stopServer();
        fs.rmSync(folder, { recursive: true, force: true });
    });

    test('a file URL that exists is proxied through the bridge', () => {
        const file = path.join(folder, 'report.html');
        fs.writeFileSync(file, '<!doctype html><html><body>hi</body></html>', 'utf8');
        const postMessage = sinon.stub();
        provider.resolveWebviewView(fakeWebview(postMessage));

        provider.loadUrl(vscode.Uri.file(file).toString());

        const url = postMessage.getCall(0).args[0].url as string;
        assert.match(url, /^http:\/\/localhost:\d+\/file\//,
            'a local page is served by the bridge, so the bridge\'s authorization and injected client apply');
        assert.ok(url.includes(encodeURIComponent('report.html')),
            `the path is encoded per segment: ${url}`);
    });

    test('a bare absolute path is proxied too', () => {
        const file = path.join(folder, 'report.html');
        fs.writeFileSync(file, '<!doctype html><html><body>hi</body></html>', 'utf8');
        const postMessage = sinon.stub();
        provider.resolveWebviewView(fakeWebview(postMessage));

        provider.loadUrl(file);

        assert.match(postMessage.getCall(0).args[0].url as string, /^http:\/\/localhost:\d+\/file\//,
            'a path typed into the address bar is the same request as a file:// URL');
    });

    test('an http URL is passed through unchanged', () => {
        const postMessage = sinon.stub();
        provider.resolveWebviewView(fakeWebview(postMessage));

        provider.loadUrl('http://example.com');

        assert.strictEqual(postMessage.getCall(0).args[0].url, 'http://example.com',
            'a page the user names by URL is not something the bridge should rewrite');
    });
});
