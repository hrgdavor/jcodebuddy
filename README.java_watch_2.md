# Java Watch 2 Ecosystem

A comprehensive suite of Java-based file monitoring and developer productivity tools. Designed for performance, reliability, and seamless IDE integration.

## Project Structure

- **[java-watch-core](java-watch-core)**: Core models and utilities. Includes `ChecksumDatabase` for change detection, `CodeEdit` for surgical updates, and `CodeEditApplier`.
- **[java-watch-agent](java-watch-agent)**: The "Agent" - an extensible file watcher that applies automated transformations (tools) to your source code in real-time.
- **[jwa-sidecar](jwa-sidecar)**: An **LSP (Language Server Protocol)** wrapper for JWA. Enables interactive surgical updates and a **Remote Jump** service to tunnel navigation from Web UIs back to your IDE.
- **[jwa-builder](jwa-builder)**: The core engine for Record Builder generation. IO-agnostic and shared between the Agent and the Sidecar.
- **[java-watch-scp](java-watch-scp)**: Specialized file watcher for automatic synchronization to remote servers via SCP/SSH.
- **[watch](watch)**: A lightweight, generic file monitoring and backup utility.

## Key Features

- **Surgical Code Updates**: Uses JavaParser and LexicalPreservingPrinter to modify code without destroying comments or formatting.
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
java -jar java-watch-agent/target/java-watch-agent.jar
```

## Running the Sidecar (LSP)

The Sidecar can be launched by an IDE or as a standalone process (LSP listening on stdin/stdout, Jump HTTP on port 7979).

```powershell
java -cp "jwa-sidecar/target/*" hr.hrg.watch2.sidecar.SidecarApp
```


## IDE Integration

### IntelliJ IDEA
1. Open the [intellij-jwa](intellij-jwa) module.
2. Build the plugin: `./gradlew buildPlugin`.
3. Install from disk from `build/distributions/`.

### VS Code
1. Open [vscode-jwa](vscode-jwa).
2. Follow the setup and configuration instructions in [vscode-jwa/README.md](vscode-jwa/README.md).
3. `npm install` and `F5`.

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0** with the **Commons Clause** condition.

### What this means:
*   **Developers & Businesses:** You are free to use, modify, and distribute this tool for personal projects or internal business operations.
*   **Share Alike:** Any derivatives or modifications you distribute must be shared back under these same terms.
*   **Commercial Restriction:** You may **not** include this software in a **paid tier** of a commercial product or service.
*   **Free Tiers:** Integration into the **free tier** is permitted, provided no fee is charged for this software's functionality.

For the full legal text, see the [LICENSE.md](LICENSE.md) file.
