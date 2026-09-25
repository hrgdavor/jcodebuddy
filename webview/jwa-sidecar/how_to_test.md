# Testing JWA Builder via VS Code Actions

You can now test the **Record Builder Generator** directly in any VS Code-based editor using the newly created `vscode-jwa` extension.

## 1. Prerequisites
- **JDK 25** installed; the whole reactor now compiles with `--release 25` (this file used to say Java 21).
- **Node.js** installed (for the extension setup, already done).
- The `jwa-sidecar.jar` has been built at `webview/jwa-sidecar/target/jwa-sidecar.jar` — the module moved
  under `webview/` on 2026-09-25. Build it with:

  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
  mvn -pl webview/jwa-sidecar -am install -DskipTests
  ```

## 2. Launching the Extension
To test the extension:
1. Open the `vscode-jwa` folder in a new VS Code window.
2. Press `F5` (or go to **Run and Debug** -> **Launch Extension**). This will open a new window called **[Extension Development Host]**.
3. In that new window, open the `jcodebuddy` project folder.

## 3. Testing the "Sync Builder" Action
Once the project is open in the [Extension Development Host]:
1. Open a Java file with a `record`, for example
   `java-watch-agent/src/main/java/hr/hrg/watch2/agent/TestRecord.java`.
2. Click on the line with the record name: `public record TestRecord(...)`.
3. You should see a **Lightbulb icon** (Quick Fix) appear.
4. Click the lightbulb and select **Sync Builder**.
5. The builder inner class should be automatically generated or updated surgically within the file.

## 4. Testing the Remote Jump

The Sidecar runs a Jump HTTP service on `127.0.0.1:7979`. **It denies every caller until it is authorized** —
the same closed default as the JetBrains plugin's HTTP bridge, because this endpoint moves the user's editor.
Start it with a token and pass that token in the request:

```powershell
# Start the sidecar with a token (see README.md for launching it as a VS Code LSP server):
java "-Djwa.sidecar.token=local-dev" -cp "webview/jwa-sidecar/target/*" hr.hrg.watch2.sidecar.SidecarApp

# In another terminal:
Invoke-RestMethod -Uri ("http://127.0.0.1:7979/jump?token=local-dev" +
    "&uri=file:///D:/wrk/java/jcodebuddy/java-watch-agent/src/main/java/hr/hrg/watch2/agent/TestRecord.java&line=2")
```

The [Extension Development Host] window should focus `TestRecord.java` at line 2. `GET /health` answers without
a token and reports whether one is required. Without a token or an allowed origin, `/jump` answers `403`; a
request for a file that does not resolve answers `404`, and the twenty-first request inside twenty seconds
answers `429`.

## Summary of Integration
- **LSP Server**: `jwa-sidecar` in `webview/jwa-sidecar` (port 7979 for HTTP, stdio for LSP).
- **VS Code Client**: `vscode-jwa` extension (Handles command tunneling and jumps).
- **Core Engine**: `jwa-builder` (Shared logic), plus `webview-core` for the path jail and rate limit.
