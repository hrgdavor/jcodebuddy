# Java Watch 2 Ecosystem

A comprehensive suite of Java-based file monitoring and developer productivity tools. Designed for performance, reliability, and seamless IDE integration.

## Project Structure

- **[java-watch-core](watch/java-watch-core)**: Core models and utilities. Includes `ChecksumDatabase` for change detection, `CodeEdit` for surgical updates, and `CodeEditApplier`.
- **[java-watch-agent](jcodebuddy/jcodebuddy-agent)**: The "Agent" - an extensible file watcher that applies automated transformations (tools) to your source code in real-time.
- **[webview/jwa-sidecar](webview/jwa-sidecar)**: An **LSP (Language Server Protocol)** wrapper for JWA, and the LSP transport of the webview product. Enables interactive surgical updates, a **Remote Jump** service to tunnel navigation from Web UIs back to your IDE, and — since the sidecar moved in with the webview hosts — the same origin allow-list, rate limit and path jail they use.
- **[jwa-builder](jcodebuddy/jcodebuddy-builder)**: The core engine for Record Builder generation. IO-agnostic and shared between the Agent and the Sidecar.
- **[java-watch-scp](watch/java-watch-scp)**: Specialized file watcher for automatic synchronization to remote servers via SCP/SSH.
- **[watch](watch)**: A lightweight, generic file monitoring and backup utility.

## Key Features

- **Surgical Code Updates**: Reads a record's or class's shape from an OpenRewrite tree, then **splices generated text into the original source** — so comments, whitespace and the rest of the file are preserved byte-for-byte instead of being reformatted. See [`docs/RecordBuilderGenerator.md`](docs/RecordBuilderGenerator.md).
- **IO-Agnostic Tools**: Transformation logic is decoupled from file system operations, allowing it to run in CLI watchers or as interactive IDE plugins.
- **Real-time Change Detection**: Uses `ChecksumDatabase` to ensure actions only trigger on meaningful content changes.
- **IDE Connectivity**: The Sidecar provides an LSP bridge, enabling "Sync Builder" lightbulbs in VS Code, IntelliJ, and more.
- **Remote Jump Protocol**: A custom protocol (`mytool/jump`) and HTTP bridge that lets web dashboards navigate your IDE to specific files and lines.

## Building the Project

From the root directory (requires Java 21+):

```powershell
mvn clean install -DskipTests
```

## Running the Agent

The Agent uses a `.watch_agent.conf` file for configuration.

```powershell
java -jar jcodebuddy/jcodebuddy-agent/target/java-watch-agent.jar
```

## Running the Sidecar (LSP)

The Sidecar can be launched by an IDE or as a standalone process (LSP listening on stdin/stdout, Jump HTTP on port 7979).

```powershell
java -cp "webview/jwa-sidecar/target/*" hr.hrg.watch2.sidecar.SidecarApp
```

The Jump endpoint denies every caller until it is authorized, the same closed default as the JetBrains
plugin's HTTP bridge. Configure it with VM options — a token, or the origin a page is served from:

```powershell
java -Djwa.sidecar.token=<shared secret> -Djwa.sidecar.jumpPort=7979 -cp "webview/jwa-sidecar/target/*" hr.hrg.watch2.sidecar.SidecarApp
```

```text
GET http://127.0.0.1:7979/jump?uri=<file url>&line=<n>&column=<n>&token=<shared secret>
GET http://127.0.0.1:7979/health
```

`jwa.sidecar.allowedOrigins` accepts a comma-separated origin list as an alternative to the token. The
endpoint binds `127.0.0.1` only, and it is rate limited to 20 jumps per 20 seconds.


## IDE Integration

### IntelliJ IDEA
The earlier standalone `intellij-jwa` plugin is gone: step 3.0q merged its language-server registration into the
plugin this repository ships. Open `webview/webview-jetbrains`, build it (`./gradlew buildPlugin`) and install from
disk from `build/distributions/`.

### VS Code
The earlier standalone `vscode-jwa` extension is gone for the same reason: its language client and its
`mytool/jump` handler now live in `webview/webview-vscode`. Open that folder, run `npm install`, and press `F5`.

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0** with the **Commons Clause** condition.

### What this means:
*   **Developers & Businesses:** You are free to use, modify, and distribute this tool for personal projects or internal business operations.
*   **Share Alike:** Any derivatives or modifications you distribute must be shared back under these same terms.
*   **Commercial Restriction:** You may **not** include this software in a **paid tier** of a commercial product or service.
*   **Free Tiers:** Integration into the **free tier** is permitted, provided no fee is charged for this software's functionality.

For the full legal text, see the [LICENSE.md](LICENSE.md) file.
