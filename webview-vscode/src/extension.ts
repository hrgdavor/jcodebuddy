import * as vscode from 'vscode';
import { WebViewProvider } from './WebViewProvider';
import { HttpBridge } from './HttpBridge';

export function activate(context: vscode.ExtensionContext) {
    console.log('WebView Explorer is now active!');

    const httpBridge = new HttpBridge();
    const webViewProvider = new WebViewProvider(context.extensionUri, context, httpBridge);

    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(WebViewProvider.viewType, webViewProvider)
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('webview-explorer.refresh', () => {
            webViewProvider.refresh();
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('webview-explorer.openInWebView', (uri: vscode.Uri) => {
            if (uri) {
                vscode.commands.executeCommand('webview-explorer-view.focus');
                webViewProvider.loadUrl(uri.fsPath);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('webview-explorer.openSettings', () => {
            vscode.commands.executeCommand('workbench.action.openSettings', 'WebView Explorer');
        })
    );
}

export function deactivate() {
    // No cleanup currently required
}
