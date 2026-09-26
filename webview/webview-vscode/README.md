# WebView Explorer for VS Code

This extension is a port of the JetBrains `WebView Explorer` plugin. It provides a WebView in the sidebar with an address bar and an HTTP bridge to open files in the editor.

To build a page that uses it — a navigable project report, an entity reference, a rendered Markdown
document — see [`../kit/doc/page-authoring.md`](../kit/doc/page-authoring.md) and the runnable pages
in [`../kit/examples/`](../kit/examples/README.md). They speak this same API, so the only value that changes between
the two hosts is the bridge port (18882 here, 18881 in JetBrains).

## Features

- **Sidebar WebView**: A persistent browser-like view in the VS Code sidebar.
- **Address Bar**: Enter URLs to browse the web or local files.
- **HTTP Bridge**: A local HTTP server (default port 18882) that allows opening files via GET requests.
  - Endpoint: `http://localhost:18882/open?filePath=path/to/file&line=10&column=5`
  - It starts when the **window loads** (`activationEvents: ["onStartupFinished"]`), not when you happen to
    open the sidebar. Before that, the extension is not activated at all and the port is closed — which is how
    a bridge request could fail with nothing to explain it.
  - **18882 is a request, not the address.** The port actually bound is published in the workspace's
    `.jcodebuddy/webview/host.json` (`port`, plus `ide` and `project`), and `GET /health` answers the same
    three things. When the requested port is held by an unrelated application the bridge takes the next free
    port; when it is held by a host that already serves **this** project — another IDE on the same folder —
    the bridge opens nothing at all and says who is serving, rather than starting a second bridge for one
    project. See [`../doc/webview-host-api.md`](../doc/webview-host-api.md) § 4a.
- **JS Bridge**: Injects `window.openFile(path, line, col)` into pages loaded in the WebView (if origins match).
- **Settings**: Configure the HTTP port, the allowed origins and the token for the write routes.

## Usage

1. Click on the **WebView Explorer** icon in the Activity Bar.
2. Enter a URL in the address bar.
3. To open files from your web content, either:
   - Call `window.openFile('src/main.ts', 10, 5)` from your JS.
   - Send a GET request to `http://localhost:18882/open?filePath=src/main.ts&line=10`.

## Configuration

- `webviewExplorer.port`: The port to **ask for** (default: 18882). With no explicit setting for this window,
  the port this workspace is currently on (`.jcodebuddy/webview/host.json` — local, never in git) is used
  first, and failing that the project's committed default `.jcodebuddy/conf/webview.json`
  (`{ "port": 18882 }`, optional), so a fresh clone needs no IDE configuration. The bridge takes the next free
  port when something unrelated holds the one it asked for, and opens nothing when the port is held by a host
  already serving this workspace. The port in use is in `.jcodebuddy/webview/host.json`, and in `GET /health`.
- **The record outlives the bridge.** Stopping the bridge does not delete it, so the next start asks for the
  same port instead of taking a fresh one — which is what makes an ephemeral port usable in a bookmark.
  Delete that file to go back to the project's default.
- **A pinned port is never moved.** If that `host.json` says `"sticky": true`, the port it records is the only
  one acceptable: if something else holds it the bridge opens nothing and shows an error, rather than serving
  where you did not ask. Set it by editing the file, or once with `webviewd --sticky` from the project (the
  pin belongs to the project and the port, not to the process that set it).
- `webviewExplorer.allowedOrigins`: Comma-separated list of origins allowed to call the bridge (e.g., `http://localhost:3000`).
- `webviewExplorer.token`: Required by the state-changing routes (`/api/v1/applyEdit` and friends) — an allowed
  `Origin` is deliberately not enough for a route that changes something.

## The rules, and where they live

Two sets of decisions, both as pure functions with no VS Code dependency and both asserted against the tables
every host shares:

| What | Where | Asserted by |
| --- | --- | --- |
| who may drive the editor: the origin allow-list, CORS, the rate limit, the write-token rule | [`src/BridgePolicy.ts`](src/BridgePolicy.ts) | [`../conformance/bridge-decisions.json`](../conformance/bridge-decisions.json) |
| which port this host serves on: the claim table, the `/health` identity, the descriptor | [`src/BridgePolicy.ts`](src/BridgePolicy.ts) + [`src/HostRegistration.ts`](src/HostRegistration.ts) | `HostRegistration.test.js` (real sockets) and `BridgePolicy.test.js` |

```bash
npm run test:unit      # BridgePolicy + HostRegistration: no VS Code download, about a second
npm test               # the VS Code integration suite (downloads VS Code; see "Known failures" below)
```

Three behaviours changed when this host adopted the shared authorization rule. The first two were bugs:

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
