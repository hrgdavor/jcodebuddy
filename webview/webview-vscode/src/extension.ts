import * as vscode from 'vscode';
import { WebViewProvider } from './WebViewProvider';
import { HttpBridge } from './HttpBridge';
import { SidecarClient } from './SidecarClient';

export async function activate(context: vscode.ExtensionContext) {
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

    // The JWA sidecar as a language server (step 3.0q merged this from the earlier `vscode-jwa` attempt).
    // A failure to start is reported and never thrown: the webview half must keep working in a workspace
    // where the sidecar was not built.
    const sidecar = new SidecarClient();
    context.subscriptions.push(sidecar.outputChannel);
    context.subscriptions.push({ dispose: () => void sidecar.stop() });
    await sidecar.start(context);
}

export function deactivate() {
    // The subscriptions above stop the sidecar and dispose its channel.
}
