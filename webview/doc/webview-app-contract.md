# The webview app contract

Status: **defined 2026-11-18.** This is the stable URL and path contract between the webview host, a
right-click/open action, and an authored app served under `/app/`. It is deliberately separate from the
frozen page bridge contract in [`../kit/doc/contract.md`](../kit/doc/contract.md); that contract still
governs `window.openFile`, `data-*`, `/open` and `/file/`.

## 1. The open-file URL

When the host is asked to open a project file in the app, it opens:

```text
/app/?openFile=<project-relative-path>
```

`openFile` is one URI query value. The whole relative path is URL-encoded once, so a path containing
spaces, `+`, `#` or non-ASCII characters cannot change the query:

```text
/app/?openFile=src%2Fmain%2Fjava%2Fdemo%2FOrder.java
```

The app reads the value, decodes it exactly once, and resolves it against the project root. It then
fetches the same file through the source namespace:

```text
GET /source/src/main/java/demo/Order.java
```

The URL is a **request to the app**, not a promise that the app renders a code editor. An app that cannot
open the file must say so in its UI rather than fetch a guessed path or silently show a blank page.

When a project has a built app (`.jcodebuddy/app/index.html`), the right-click **Open in WebView Explorer**
action uses this URL for every project file. Without a built app it keeps the existing behavior: Markdown is
rendered by the host, HTML is served as a page, and a plain file falls back to its `file:` URL.

### Canonical path form

The query value, after one decode, is the **project-relative path**:

- forward slashes only;
- no leading slash;
- no `.` or `..` segment;
- no drive prefix, scheme or authority;
- relative to the served project root, not to `.jcodebuddy/app` and not to the current page.

A host that receives an absolute or escaping path must refuse to build the app URL. The app and the
`/source` route must refuse it again; neither side trusts the other's validation.

## 2. Namespaces

```text
/app/                    default authored app; derived and rebuildable
/app/<page>              one page of that app, opened directly (e.g. /app/incremental-build.html)
/app/?openFile=<path>    the app shell, asked to open a project file (a page under .jcodebuddy/app is
                        navigated to; any other file is handed to the IDE)
/source/<path>           project sources and files, read-only (webview static)
/lib/                    project-automation bundles/shared assets (webview static)
/reports/<id>/...        generated report pages and metadata files (webview static)
/api/v1/metadata/...     catalog/index/SSE (JS metadata backend)
/api/tools               live tool descriptors from .jcodebuddy/ipc/ (host proxy, DEC-053)
/api/tools/<tool>/call   one call forwarded to that tool's Unix socket (host proxy, DEC-053)
/api/tools/<tool>/events that tool's event stream as SSE (host proxy, DEC-053)
```

- `/app/` maps to `<project>/.jcodebuddy/app/` and is the default URL a webview should open.
- `/app/<page>` opens one page of that app directly. The app pages are derived output and are readable
  without a token (a typed URL or a link that forgets the token still loads). The IDE host injects the
  server's page token into `localStorage` (`jcodebuddy.token`) before the page's scripts run; a standalone
  first visit may carry `?token=`, which the page stores and removes from the address bar. The token is what
  authorizes `/api/tools/<tool>/call` and the event stream; the pages themselves are public.
- The app directory is ignored derived output. A build may replace it; nothing in it is a committed
  source of truth.
- `/source/`, `/lib/` and `/reports/` are static file namespaces served by the webview host and jailed to
  the project. They are not a metadata API.
- `/api/v1/metadata/` is served by the **JS project-automation backend**, not by the webview host. In
  custom-server mode the webview proxies this prefix to that backend; without one there is no live
  metadata transport and the page falls back to its static model.
- `/api/tools` is the **host's** live-tool proxy, not the JS backend's. In custom-server mode it stays on the
  host's own port (the proxy forwards only `/app` and `/api/v1/metadata/`). The standalone Bun metadata server
  also serves the same three tool routes for the pages it serves, using the token in
  `.jcodebuddy/webview/token`, so a page works from either server.
- `/api/tools` and `/api/tools/<tool>/call` are the **host's** proxy to the project's live tool endpoints
  ([DEC-053](../../doc-hipster-entity/architecture/decisions/DEC-053.md)): the control plane of the tools
  that run inside the project, not page content. The call route is token-only, because a call may rebuild or
  change what a tool watches; the list is origin-or-token. A page served from `/app/` reaches them on its own
  origin, and needs the host token for the call route.
- `/api/tools/<tool>/events` is the same proxy's **push** half: the tool streams JSON-RPC notifications on
  its socket and the host forwards them as SSE. It is token-only too, and because `EventSource` cannot set a
  header the token is in the query (`?token=`), which is the mechanism the Eclipse host already uses for a
  page URL.
- `/app/` and `/reports/` pages receive the injected editor bridge; `/source/` and `/lib/` are raw files.

## 3. Own server vs custom project-automation server

The webview integration has two distinct APIs:

1. **The IDE integration API** — `/open`, `/health`, `/api/v1/applyEdit`, `/api/v1/diff`,
   `/api/v1/undo`, `/api/v1/redo`, and the injected bridge. It always belongs to the webview host.
2. **The project-automation API** — `/app/` and `/api/v1/metadata/...` (the latter is the JS metadata
   transport). It belongs to the JS backend. `/source/`, `/lib/` and `/reports/` are static files the
   webview host can serve from the project without a backend.

The project chooses the backend in `.jcodebuddy/conf/webview.json`:

```json
{
  "port": 18882,
  "customServer": "http://127.0.0.1:9000"
}
```

- **No `customServer`** — the webview serves the static namespaces and the local app; there is no
  metadata transport, so a page falls back to its static model.
- **`customServer` present** — the webview host still binds its own loopback **editor API** port, opens
  `/app/?openFile=...` from that port, and proxies `/app/` **and `/api/v1/metadata/`** to the custom
  backend.

`/source/`, `/lib/` and `/reports/` are static, project-jailed namespaces the webview host serves itself;
they are not a metadata transport and do not require the custom server. `/api/v1/metadata/` is the JS
backend's API and is never implemented in the Java webview host.

| Namespace                                          | Owner when a webview is present |
| -------------------------------------------------- | ------------------------------- |
| `/app/`                                            | JS backend, proxied by host     |
| `/api/v1/metadata/`                                | JS backend, proxied by host     |
| `/source/`, `/lib/`, `/reports/`                   | webview host (static files)     |
| `/open`, `/health`, `/api/v1/applyEdit`, undo/redo | webview host (editor)           |

The custom server can be just an app build and its metadata transport. It never implements the editor
API, and it does not have to duplicate `/source`, `/lib` or `/reports` when a webview is there — though it
may serve them too for a standalone editor.

`customServer` is committed configuration; the port the host actually bound remains local state in
`.jcodebuddy/webview/host.json`.

## 4. App-side example

```js
const params = new URLSearchParams(location.search)
const openFile = params.get('openFile') // already decoded once by URLSearchParams

if (openFile) {
  const relative = openFile.replace(/\\/g, '/')
  if (relative.startsWith('/') || relative.split('/').includes('..')) {
    showError('invalid project path')
  } else {
    const url = '/source/' + relative.split('/').map(encodeURIComponent).join('/')
    const response = await fetch(url, { cache: 'no-store' })
    if (!response.ok) showError(`cannot read ${relative}: HTTP ${response.status}`)
    else showSource(relative, await response.text())
  }
}
```

`URLSearchParams` decodes the query value; do not call `decodeURIComponent` again. Encode each path
segment when building the `/source` URL so a filename with `#`/`?` stays a filename.

## 5. Editor actions

An app can use the existing host verbs (see [`webview-host-api.md`](webview-host-api.md)):

- **jump**: `window.openFile(path, line, column)` (injected into `/app/` pages) or `GET /open`.
- **edit**: `POST /api/v1/applyEdit` with the host's edit capability.
- **diff/undo/redo**: `POST /api/v1/diff`, `/api/v1/undo`, `/api/v1/redo`.
- **refactor**: no generic OpenRewrite recipe verb is implemented yet. A future verb must be added to this
  contract and the host API before an app may call it; until then an app reports the capability as
  unavailable.

An app must ask `/health` for capabilities rather than infer them from `ide`.

## 6. Future contract slots

The following query keys are **reserved**; they are not implemented and must not be treated as live:

| Key          | Intended meaning                                        | Status      |
| ------------ | ------------------------------------------------------- | ----------- |
| `openFile`   | project file to open                                    | **current** |
| `openReport` | a report id/page and optional anchor                    | reserved    |
| `openSymbol` | an FQN/member reference rather than a path              | reserved    |
| `line`       | caret line for a file reference                         | reserved    |
| `column`     | caret column for a file reference                       | reserved    |
| `recipe`     | an OpenRewrite recipe id to propose                     | reserved    |

Rules for adding one:

1. add it to this document and the host API's verb table;
2. define its encoding and its project-jail rule;
3. add a host route (or a documented capability), not only a client convention;
4. keep old keys working; an unknown key is ignored, never used to guess a path.

The path rule is shared by all future keys: project-relative, forward-slash, one decode, no escaping
segments. A future key that cannot satisfy that rule needs its own contract section here.

## 7. Tests

- `webview/core/webviewd` `MetadataApiTest`: `/app/`, `/reports/`, `/source/`, `/lib/` status and jail.
- `webview/core/webviewd` `ToolApiTest`: `/api/tools` and the call route over a real tool socket (DEC-053).
- `project-automation/metadata/metadata.test.js`: the same three tool routes on the standalone Bun server
  (list, token-gated call, SSE stream over a real Unix socket).
- `project-automation/metadata/metadata.test.js`: the same namespaces in the standalone host.
- `scripts/check-metadata-live-page.js`: real-Chrome live report checks.
- The app contract's URL/path rules are pure; add a test beside the host that constructs the URL when a
  concrete app build lands.
