const vscode = require('vscode');
const { LanguageClient, TransportKind } = require('vscode-languageclient/node');
const fs = require('fs');

/**
 * Discovery logic for Java home following best practices:
 * 1. Extension setting 'jwa.java.home'
 * 2. JAVA_HOME environment variable
 * 3. Default 'java' from PATH
 */
function findJavaExecutable() {
    const config = vscode.workspace.getConfiguration('jwa');
    const javaHomeSetting = config.get('java.home');
    const isWindows = process.platform === 'win32';
    const javaBin = isWindows ? 'java.exe' : 'java';

    // 1. Check Configuration
    if (javaHomeSetting) {
        const binPath = path.join(javaHomeSetting, 'bin', javaBin);
        if (fs.existsSync(binPath)) return binPath;
    }

    // 2. Check JAVA_HOME
    const envJavaHome = process.env.JAVA_HOME;
    if (envJavaHome) {
        const binPath = path.join(envJavaHome, 'bin', javaBin);
        if (fs.existsSync(binPath)) return binPath;
    }

    // 3. Fallback to 'java' on PATH
    return javaBin;
}

let client;

function activate(context) {
    const config = vscode.workspace.getConfiguration('jwa');

    // 1. Try custom path from config (Project or User)
    let jarPath = config.get('server.jarPath');

    // 2. Fallback to bundled location
    if (!jarPath || !fs.existsSync(jarPath)) {
        // Path to the fat jar bundled in the extension
        jarPath = path.join(context.extensionPath, 'sidecar', 'jwa-sidecar.jar');
    }

    // 3. Last fallback (development mode)
    if (!fs.existsSync(jarPath)) {
        jarPath = path.join(context.extensionPath, '..', 'jwa-sidecar', 'target', 'jwa-sidecar.jar');
    }

    const javaPath = findJavaExecutable();

    let serverOptions = {
        run: { command: javaPath, args: ['-jar', jarPath] },
        debug: { command: javaPath, args: ['-jar', jarPath] }
    };

    const outputChannel = vscode.window.createOutputChannel('JWA Sidecar');
    outputChannel.show();
    outputChannel.appendLine('JWA Sidecar: Extension activating...');
    console.log('JWA Sidecar: Extension activating...');

    // Client options
    let clientOptions = {
        documentSelector: [{ scheme: 'file', language: 'java' }],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.java')
        },
        outputChannel: outputChannel
    };

    outputChannel.appendLine(`JWA Sidecar: Jar path: ${jarPath}`);

    // Create the language client and start the client.
    client = new LanguageClient(
        'jwaSidecar',
        'JWA Sidecar',
        serverOptions,
        clientOptions
    );

    outputChannel.appendLine('JWA Sidecar: Starting language client...');

    // Start the client. This will also launch the server
    client.start().then(() => {
        outputChannel.appendLine('JWA Sidecar: Language client started successfully.');
        // Register custom notification handler for 'mytool/jump'
        client.onNotification('mytool/jump', (params) => {
            outputChannel.appendLine(`JWA Sidecar: Received jump request: ${JSON.stringify(params)}`);
            const uri = vscode.Uri.parse(params.uri);
            vscode.window.showTextDocument(uri, {
                selection: new vscode.Range(params.line - 1, params.column - 1, params.line - 1, params.column - 1)
            });
        });
    }).catch(err => {
        outputChannel.appendLine(`JWA Sidecar: Failed to start client: ${err}`);
        vscode.window.showErrorMessage(`JWA Sidecar failed to start: ${err}`);
    });

    // Note: 'jwa.syncBuilder' is handled by the server as a workspace/executeCommand,
    // but the lightbulb (CodeAction) will automatically send it back to the server.
}

function deactivate() {
    if (!client) {
        return undefined;
    }
    return client.stop();
}

module.exports = {
    activate,
    deactivate
};
