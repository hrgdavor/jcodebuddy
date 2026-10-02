const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');
const path = require('path');
const fs = require('fs');

let client;

/**
 * Discovery logic for Bun executable following best practices:
 * 1. Extension setting 'jswa.bun.path'
 * 2. Default 'bun' or 'bun.exe' from PATH
 */
function findBunExecutable() {
    const config = vscode.workspace.getConfiguration('jswa');
    const bunPathSetting = config.get('bun.path');
    const isWindows = process.platform === 'win32';
    const bunBin = isWindows ? 'bun.exe' : 'bun';

    // 1. Check Configuration
    if (bunPathSetting) {
        if (fs.existsSync(bunPathSetting)) return bunPathSetting;
        // If it's a directory, check for bun inside
        const binInDir = path.join(bunPathSetting, bunBin);
        if (fs.existsSync(binInDir)) return binInDir;
    }

    // 2. Fallback to 'bun' on PATH
    return bunBin;
}

function activate(context) {
    const config = vscode.workspace.getConfiguration('jswa');

    // 1. Try custom path from config (Project or User)
    let serverScript = config.get('server.scriptPath');

    // 2. Fallback to bundled location
    if (!serverScript || !fs.existsSync(serverScript)) {
        serverScript = context.asAbsolutePath(path.join('sidecar', 'index.ts'));
    }

    // 3. Last fallback (development mode)
    if (!fs.existsSync(serverScript)) {
        serverScript = context.asAbsolutePath(path.join('..', 'jswa-core', 'index.ts'));
    }

    const bunPath = findBunExecutable();

    const outputChannel = vscode.window.createOutputChannel('JSWA Sidecar');
    outputChannel.show();
    outputChannel.appendLine('JSWA Sidecar: Extension activating...');

    // Server options: use Bun to run the TS file directly
    let serverOptions = {
        run: { command: bunPath, args: ['run', serverScript] },
        debug: { command: bunPath, args: ['run', '--inspect', serverScript] }
    };

    // Client options
    let clientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'javascript' },
            { scheme: 'file', language: 'typescript' }
        ],
        outputChannel: outputChannel
    };

    // Create and start the client
    client = new LanguageClient(
        'jswaSidecar',
        'JSWA Sidecar',
        serverOptions,
        clientOptions
    );

    client.start().then(() => {
        outputChannel.appendLine('JSWA Sidecar: Language client started successfully.');
    }).catch(err => {
        outputChannel.appendLine(`JSWA Sidecar: Failed to start client: ${err}`);
    });
}

function deactivate() {
    if (!client) return undefined;
    return client.stop();
}

module.exports = { activate, deactivate };
