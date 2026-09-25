import * as assert from 'assert';
import * as vscode from 'vscode';
import { WebViewProvider } from '../../WebViewProvider';
import { HttpBridge } from '../../HttpBridge';
import * as sinon from 'sinon';

suite('WebView Explorer Test Suite', () => {
    vscode.window.showInformationMessage('Start all tests.');

    let context: vscode.ExtensionContext;
    let httpBridge: HttpBridge;
    let provider: WebViewProvider;

    setup(() => {
        context = {
            globalState: {
                get: sinon.stub(),
                update: sinon.stub()
            } as any,
            extensionUri: vscode.Uri.file('/fake/path')
        } as any;
        httpBridge = new HttpBridge();
        provider = new WebViewProvider(context.extensionUri, context, httpBridge);
    });

    test('URL conversion logic - file://', () => {
        const webviewView: any = {
            webview: {
                options: {},
                asWebviewUri: (uri: vscode.Uri) => uri.toString().replace('file://', 'vscode-webview://'),
                postMessage: sinon.stub(),
                onDidReceiveMessage: sinon.stub()
            }
        };

        provider.resolveWebviewView(webviewView);

        // Test file path conversion
        provider.loadUrl('C:/test.html');
        const call = webviewView.webview.postMessage.getCall(0);
        assert.ok(call.args[0].url.startsWith('vscode-webview://'));
    });

    test('URL conversion logic - http', () => {
        const webviewView: any = {
            webview: {
                options: {},
                asWebviewUri: (uri: vscode.Uri) => uri.toString(),
                postMessage: sinon.stub(),
                onDidReceiveMessage: sinon.stub()
            }
        };

        provider.resolveWebviewView(webviewView);
        provider.loadUrl('http://example.com');
        const call = webviewView.webview.postMessage.getCall(0);
        assert.strictEqual(call.args[0].url, 'http://example.com');
    });
});
