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
/app/?openFile=<path>    open a project file in the app
/source/<path>           project sources and files, read-only
/lib/                    project-automation bundles and shared assets
/reports/<id>/...        generated report pages and their metadata
/api/v1/metadata/...     catalog/index/SSE
```

- `/app/` maps to `<project>/.jcodebuddy/app/` and is the default URL a webview should open.
- The app directory is ignored derived output. A build may replace it; nothing in it is a committed
  source of truth.
- `/source/` is project-jailed. It is a file reader, not a directory listing.
- `/lib/` is project-jailed to the metadata lib directory.
- `/reports/` is project-jailed through the catalog.
- The host serves all four namespaces; `/app/` and `/reports/` pages receive the injected editor bridge,
  while `/source/` and `/lib/` are raw files.

## 3. Own server vs custom project-automation server

The webview integration has two distinct APIs:

1. **The IDE integration API** — `/open`, `/health`, `/api/v1/applyEdit`, `/api/v1/diff`,
   `/api/v1/undo`, `/api/v1/redo`, and the injected bridge. It always belongs to the webview host.
2. **The project-automation API** — `/app/`, `/source/`, `/lib/`, `/reports/`,
   `/api/v1/metadata/...`. It belongs to whichever server owns the site.

The project chooses the second in `.jcodebuddy/conf/webview.json`:

```json
{
  "port": 18882,
  "customServer": "http://127.0.0.1:9000"
}
```

- **No `customServer`** — the webview host serves its own page/data namespaces.
- **`customServer` present** — the webview host still binds its own loopback **editor API** port, opens
  `/app/?openFile=...` from that port, and proxies `/app/`, `/source/`, `/lib/`, `/reports/` and
  `/api/v1/metadata/` to the custom base. `/open`, `/health` and the edit/undo routes stay local.

That proxy is the point of the split: the app is same-origin with the IDE bridge, so `window.openFile`,
`fetch('/source/...')` and `POST /api/v1/applyEdit` all work while the page and data come from the
project-automation server. A custom server never implements the editor API and a host never reimplements
the project-automation server.

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
- `project-automation/metadata/metadata.test.js`: the same namespaces in the standalone host.
- `scripts/check-metadata-live-page.js`: real-Chrome live report checks.
- The app contract's URL/path rules are pure; add a test beside the host that constructs the URL when a
  concrete app build lands.
