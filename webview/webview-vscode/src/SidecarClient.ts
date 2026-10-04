/**
 * The VS Code half of the JWA sidecar: launch it as a language server, and listen for its jump notification.
 *
 * <p>This is the capability the 3.0p audit found living only in the earlier `webview/vscode-jwa` attempt
 * (step 3.0q writes it here rather than copying that file, whose discovery branch threw — see
 * `SidecarPaths`). The rules live in `SidecarPaths.ts` and are unit-tested; what is here is the part that
 * needs the editor: the `LanguageClient`, the file watcher, the output channel, and the notification
 * handler.</p>
 *
 * <p><strong>{@code mytool/jump} is an explicit API label, not a derived name</strong> (DEC-022). The
 * sidecar declares it as a {@code @JsonNotification} (`webview/jwa-sidecar/.../JwaLanguageClient.java:12`)
 * and sends it when navigation should move the editor's cursor, so this client must listen for that exact
 * string and an IDE rename refactor must never touch it. The HTTP bridge is a different route to a similar
 * result — it is what a *page* uses; this is what the *language server* uses.</p>
 */

import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions } from 'vscode-languageclient/node';

import { discoverJavaExecutable, fileExists, resolveSidecarJar } from './SidecarPaths';

/** The notification the sidecar sends to ask the editor to reveal a position. */
export const JUMP_NOTIFICATION = 'mytool/jump';

/** The parameters `mytool/jump` carries (the sidecar's `JumpParams`: a uri and a 1-based line and column). */
interface JumpParams {
    uri: string;
    line: number;
    column: number;
}

/** The Java sidecar, started as a language server for this workspace. */
export class SidecarClient {
    private client: LanguageClient | undefined;
    private readonly output: vscode.OutputChannel;

    constructor() {
        this.output = vscode.window.createOutputChannel('JWA Sidecar');
    }

    /** Whether the language client is running. */
    isRunning(): boolean {
        return this.client !== undefined;
    }

    /**
     * Starts the client, or reports why it could not start.
     *
     * <p>A failure is reported through the editor and the output channel, naming every path that was looked
     * in, and {@code activate} is not allowed to fail because of it: the webview half of this extension must
     * keep working in a workspace where the sidecar was never built.</p>
     */
    async start(context: vscode.ExtensionContext): Promise<void> {
        const configuration = vscode.workspace.getConfiguration('webviewExplorer');
        const javaExecutable = discoverJavaExecutable({
            configuredJavaHome: configuration.get<string>('sidecar.javaHome'),
            envJavaHome: process.env.JAVA_HOME,
            exists: fileExists,
        });
        const jar = resolveSidecarJar({
            configuredJarPath: configuration.get<string>('sidecar.jarPath'),
            extensionPath: context.extensionPath,
            workspaceRoot: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath,
            exists: fileExists,
        });

        this.output.appendLine(`JWA Sidecar: java = ${javaExecutable}`);
        for (const candidate of jar.candidates) {
            this.output.appendLine(`JWA Sidecar: ${candidate}${candidate === jar.jarPath && jar.found ? '  <- using this one' : ''}`);
        }
        if (!jar.found) {
            const message = 'JWA Sidecar: no sidecar JAR found. Build it with '
                + '`bun scripts/mvn-jdk25.js -pl webview/jwa-sidecar -am package`, or set webviewExplorer.sidecar.jarPath. '
                + `Looked in: ${jar.candidates.join(', ')}`;
            this.output.appendLine(message);
            return;
        }

        const serverOptions: ServerOptions = {
            run: { command: javaExecutable, args: ['-cp', jar.jarPath, 'hr.hrg.watch2.sidecar.SidecarApp'] },
            debug: { command: javaExecutable, args: ['-cp', jar.jarPath, 'hr.hrg.watch2.sidecar.SidecarApp'] },
        };
        const clientOptions: LanguageClientOptions = {
            documentSelector: [{ scheme: 'file', language: 'java' }],
            synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher('**/*.java') },
            outputChannel: this.output,
        };

        this.client = new LanguageClient('jwaSidecar', 'JWA Sidecar', serverOptions, clientOptions);
        try {
            await this.client.start();
        } catch (error) {
            this.client = undefined;
            this.output.appendLine(`JWA Sidecar: failed to start: ${String(error)}`);
            void vscode.window.showErrorMessage(`JWA Sidecar failed to start: ${String(error)}`);
            return;
        }

        // The jump handler: the server asks, the editor reveals. Registered after start, because a
        // notification can only arrive once the connection is up.
        this.client.onNotification(JUMP_NOTIFICATION, (params: JumpParams) => {
            void vscode.window.showTextDocument(vscode.Uri.parse(params.uri), {
                selection: new vscode.Range(params.line - 1, params.column - 1, params.line - 1, params.column - 1),
            });
        });
        this.output.appendLine('JWA Sidecar: language client started.');
    }

    /** Stops the client, if it is running. */
    async stop(): Promise<void> {
        const client = this.client;
        this.client = undefined;
        if (client) {
            await client.stop();
        }
    }

    /** The output channel this client writes to, so `deactivate` can dispose it with the extension. */
    get outputChannel(): vscode.OutputChannel {
        return this.output;
    }
}
