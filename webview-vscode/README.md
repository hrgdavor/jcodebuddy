# WebView Explorer for VS Code

This extension is a port of the JetBrains `WebView Explorer` plugin. It provides a WebView in the sidebar with an address bar and an HTTP bridge to open files in the editor.

## Features

- **Sidebar WebView**: A persistent browser-like view in the VS Code sidebar.
- **Address Bar**: Enter URLs to browse the web or local files.
- **HTTP Bridge**: A local HTTP server (default port 18882) that allows opening files via GET requests.
  - Endpoint: `http://localhost:18882/open?filePath=path/to/file&line=10&column=5`
- **JS Bridge**: Injects `window.openFile(path, line, col)` into pages loaded in the WebView (if origins match).
- **Settings**: Configure the HTTP port and allowed origins for CORS protection.

## Usage

1. Click on the **WebView Explorer** icon in the Activity Bar.
2. Enter a URL in the address bar.
3. To open files from your web content, either:
   - Call `window.openFile('src/main.ts', 10, 5)` from your JS.
   - Send a GET request to `http://localhost:18882/open?filePath=src/main.ts&line=10`.

## Configuration

- `webviewExplorer.port`: Port for the HTTP server (default: 18882).
- `webviewExplorer.allowedOrigins`: Comma-separated list of origins allowed to call the bridge (e.g., `http://localhost:3000`).

## Project Setup (For Tool Authors)

To ensure your users have the correct settings for your tool, you can include a `.vscode/settings.json` file in your repository:

```json
{
    "webviewExplorer.port": 18882,
    "webviewExplorer.allowedOrigins": "http://localhost:3000"
}
```

## Integration Examples

### JavaScript / Browser
If your page is loaded within the WebView Explorer, you can call our bridge directly:
```javascript
window.openFile('src/utils/math.ts', 42, 10);
```

### CLI / Terminal / package.json
You can also trigger file opening from the terminal or from scripts in your `package.json`:
```json
{
  "name": "my-cool-project",
  "scripts": {
    "open-error": "curl \"http://localhost:18882/open?filePath=src/main.ts&line=15\""
  }
}
```

## Packaging & Installation

To create a `.vsix` file that you can share or install directly into VS Code:

1. **Install `vsce`** (the VS Code Extension Manager) globally:
   ```bash
   npm install -g @vscode/vsce
   ```

2. **Package the extension**:
   Run this command in the project root:
   ```bash
   vsce package
   ```
   *Note: If you haven't filled out all fields in `package.json` (like publisher), it might warn you or ask for confirmation.*

3. **Install the `.vsix`**:
   - Open VS Code.
   - Go to the **Extensions** view (`Ctrl+Shift+X`).
   - Click the `...` (Views and More Actions) menu in the top right.
   - Select **Install from VSIX...**.
   - Choose the `vscode-webview-explorer-0.0.1.vsix` file created in step 2.
