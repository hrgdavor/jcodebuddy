# WebView Explorer for VS Code

This extension is a port of the JetBrains `WebView Explorer` plugin. It provides a WebView in the sidebar with an address bar and an HTTP bridge to open files in the editor.

To build a page that uses it — a navigable project report, an entity reference, a rendered Markdown
document — see [`../doc/webview-page-authoring.md`](../doc/webview-page-authoring.md) and the runnable pages
in [`../examples/`](../examples/README.md). They speak this same API, so the only value that changes between
the two hosts is the bridge port (18882 here, 18881 in JetBrains).

## Features

- **Sidebar WebView**: A persistent browser-like view in the VS Code sidebar.
- **Address Bar**: Enter URLs to browse the web or local files.
- **HTTP Bridge**: A local HTTP server (default port 18882) that allows opening files via GET requests.
  - Endpoint: `http://localhost:18882/open?filePath=path/to/file&line=10&column=5`
  - It starts when the **window loads** (`activationEvents: ["onStartupFinished"]`), not when you happen to
    open the sidebar. Before that, the extension is not activated at all and the port is closed — which is how
    a bridge request could fail with nothing to explain it. If the port is already taken, the extension says so
    in a message rather than failing silently.
- **JS Bridge**: Injects `window.openFile(path, line, col)` into pages loaded in the WebView (if origins match).
- **Settings**: Configure the HTTP port, the allowed origins and the token for the write routes.

## Usage

1. Click on the **WebView Explorer** icon in the Activity Bar.
2. Enter a URL in the address bar.
3. To open files from your web content, either:
   - Call `window.openFile('src/main.ts', 10, 5)` from your JS.
   - Send a GET request to `http://localhost:18882/open?filePath=src/main.ts&line=10`.

## Configuration

- `webviewExplorer.port`: Port for the HTTP server (default: 18882).
- `webviewExplorer.allowedOrigins`: Comma-separated list of origins allowed to call the bridge (e.g., `http://localhost:3000`).

## The authorization rule, and where it lives

The decisions this host makes about *who may drive the editor* — the origin allow-list, CORS, and the rate
limit — are in [`src/BridgePolicy.ts`](src/BridgePolicy.ts), as pure functions with no VS Code dependency, and
they are asserted against the vectors every host shares in
[`../conformance/bridge-decisions.json`](../conformance/bridge-decisions.json).

```bash
npm run test:unit      # 113 assertions, no VS Code download, about a second
npm test               # the VS Code integration suite (downloads VS Code; see "Known failures" below)
```

Three behaviours changed when this host adopted the shared rule. The first two were bugs:

* **`/open` used to test the allow-list only when the request carried an `Origin` header.** A request with no
  `Origin` — a `file://` page, a hidden iframe, any non-browser client — skipped the check and was served. An
  absent `Origin` is now a denial, exactly as in `webview-core`'s `AllowedOrigins`.
* **`/file/` used to answer `Access-Control-Allow-Origin: *`**, which let any page in the browser read any file
  the extension could read. It now sends a grant only to an origin the allow-list names.
* The bridge now binds `127.0.0.1` explicitly (it previously listened on every interface), gained a
  `GET /health` that answers without credentials so a page can discover whether a bridge is there, and shares
  the 20-per-20-seconds rate limit that `webview-core` applies in every other host.

## Known failures

`npm test` has one **pre-existing** failing assertion, `URL conversion logic - file://` in
`src/test/suite/extension.test.ts`: it expects `loadUrl` to hand back a `vscode-webview://` URL for the
hard-coded path `C:/test.html`, but the `file://` branch of `WebViewProvider.loadUrl` only produces that scheme
for a path that exists, so on a machine without `C:\test.html` it falls through to the `https://` guess. It is
unrelated to the policy work above — `WebViewProvider.ts`, which the test exercises, is unchanged by it.

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
