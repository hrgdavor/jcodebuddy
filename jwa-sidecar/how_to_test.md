# Testing JWA Builder via VS Code Actions

You can now test the **Record Builder Generator** directly in any VS Code-based editor using the newly created `vscode-jwa` extension.

## 1. Prerequisites
- **Java 21** installed and on your PATH.
- **Node.js** installed (for the extension setup, already done).
- The `jwa-sidecar.jar` has been built at: `d:\wrk\java_watch2\jwa-sidecar\target\jwa-sidecar.jar`.

## 2. Launching the Extension
To test the extension:
1. Open the folder `d:\wrk\java_watch2\vscode-jwa` in a new VS Code window.
2. Press `F5` (or go to **Run and Debug** -> **Launch Extension**). This will open a new window called **[Extension Development Host]**.
3. In that new window, open the `java_watch2` project folder.

## 3. Testing the "Sync Builder" Action
Once the project is open in the [Extension Development Host]:
1. Open a Java file with a `record`, for example: [d:\wrk\java_watch2\java-watch-agent\src\main\java\hr\hrg\watch2\agent\TestRecord.java](file:///d:/wrk/java_watch2/java-watch-agent/src/main/java/hr/hrg/watch2/agent/TestRecord.java).
2. Click on the line with the record name: `public record TestRecord(...)`.
3. You should see a **Lightbulb icon** (Quick Fix) appear. 
4. Click the lightbulb and select **Sync Builder**.
5. The builder inner class should be automatically generated or updated surgically within the file.

## 4. Testing the Remote Jump
The Sidecar is also running a Jump HTTP service on port `7979`. You can test it by opening a browser (or using `curl`) while the Extension Development Host is running:

```powershell
# In a terminal:
Invoke-RestMethod -Uri "http://localhost:7979/jump?uri=file:///d:/wrk/java_watch2/java-watch-agent/src/main/java/hr/hrg/watch2/agent/TestRecord.java&line=2"
```

The [Extension Development Host] window should automatically focus on the [TestRecord.java](file:///d:/wrk/java_watch2/java-watch-agent/src/main/java/hr/hrg/watch2/agent/TestRecord.java) file at line 2.

## Summary of Integration
- **LSP Server**: `jwa-sidecar` (Port 7979 for HTTP, Stdio for LSP).
- **VS Code Client**: `vscode-jwa` extension (Handles command tunneling and jumps).
- **Core Engine**: `jwa-builder` (Shared logic).
