# JWA VS Code Extension

Interactive Record Builder and JWA Integration for Visual Studio Code.

## Features
- **Sync Builder**: Real-time record generation via Code Actions (Lightbulbs).
- **Remote Jump**: Navigation support for the JWA jump protocol.
- **LSP Integration**: Leverages the [jwa-sidecar](../jwa-sidecar) for high-performance Java source manipulation.

## Configuration

This extension follows standard Java discovery best practices. You can configure the Java runtime used to launch the JWA Sidecar via:

1. **Extension Setting**: Set `jwa.java.home` in your VS Code `settings.json` to the absolute path of your JDK home directory.
2. **Environment Variable**: If no setting is provided, it falls back to the `JAVA_HOME` environment variable.
3. **System Path**: As a final fallback, it will attempt to use `java` from your system `PATH`.

Example `settings.json`:
```json
{
    "jwa.java.home": "C:\\Program Files\\Java\\jdk-21"
}
```

## Development

1. Open this folder in VS Code.
2. Run `npm install`.
3. Press `F5` to start a new VS Code window with the extension loaded.
4. Open a Java project. The JWA Sidecar will start automatically when a `.java` file is opened.
