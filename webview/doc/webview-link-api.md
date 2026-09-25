# WebView link API — open a file and a line from a web page

This is the contract between **a web page that wants to navigate the project** and **the IDE plugin that
performs the navigation**. It is deliberately small, and deliberately the same for every plugin, so a
document generator writes one call and works in JetBrains today and in VS Code, or in any other host, the
day that plugin exists.

It is not aspirational: **two hosts already implement it** — `webview/webview-jetbrains` ("WebView Explorer") and
`webview/webview-vscode`, which was written as a port and speaks the same API. The only difference a page can
observe is the port each one listens on, which is why a page must not hard-code one (see § 3.1).

Audience: anyone generating a clickable document (an HTML report, a rendered Markdown page, a review
dashboard, a coverage view), and anyone implementing a plugin that wants such documents to work inside
their IDE.

---

## 1. The contract in one screen

A page asks for navigation by **calling one global function**:

```js
window.openFile(filePath, line, column);
```

| Argument   | Type   | Meaning |
| ---------- | ------ | ------- |
| `filePath` | string | The file to open. Absolute (`C:/work/proj/src/main/java/A.java`, `/home/me/proj/…`) or relative to the project root (`src/main/java/A.java`). Forward slashes and backslashes are both accepted. |
| `line`     | number | **1-based** line to place the caret on. `1` when unknown. `0` or negative is clamped to the first line. |
| `column`   | number | **1-based** column. `1` is fine for every use case this contract has; no generator in this repository sets anything else. |

Return value: **none**. The call is fire-and-forget by design; the page cannot know whether the IDE found
the file. A host that wants to signal failure does it through its own console (`HttpBridgeService` answers
an HTTP caller `404`, and the JetBrains bridge logs), never by throwing into the page — a navigation
failure must not break the page's own script.

**Feature-detect before you call.** A page is loaded in three places — the IDE's own webview, an ordinary
browser, and possibly a CI artifact viewer — and only the first has the function:

```js
if (typeof window.openFile === 'function') {
  window.openFile(target, line, column);   // the IDE will navigate
} else {
  // degrade: HTTP fallback (§ 3), or copy the location (§ 4)
}
```

Calling an undefined `window.openFile` throws a `TypeError` and, in a click handler, silently kills
navigation for every later click in that page. Always guard.

### 1.1 What the page must put on its links

The contract is a pair of attributes plus one function. Any element — `<a>`, `<span>`, `<td>` — may carry
them; the host reads them from the element, so the page keeps full control of its markup:

| Attribute     | Required        | Meaning                                                                 |
| ------------- | --------------- | ----------------------------------------------------------------------- |
| `data-open`   | yes             | the path or URL to open, in the same spelling `window.openFile` accepts |
| `data-line`   | yes in practice | 1-based line; omit only when line 1 is genuinely meant                  |
| `data-member` | no              | the member name at that line (`firstName`, `PersonSummary.Record`) — for the tooltip and for a human verifying a link |
| `data-role`   | no              | what the location *is* (`accessor`, `annotation`, `enum-constant`, …) — display metadata, never a routing key |

There is no attribute for `column` in this repository's pages because nothing needs one. A page that does
need it passes the third argument to `window.openFile` directly; see § 6 for whether the attribute set
should grow.

---

## 2. How the JetBrains host implements it

`webview/webview-jetbrains` injects the function into every page its tool window loads, after each main-frame load
(`WebViewBridge`). The injected script is, in full:

```js
window.__jcbWebViewBridge = 1;                 // the bridge protocol version
window.openFile = function (path, line, column) {
  var msg = JSON.stringify({kind: 'openFile', filePath: path, line: line || 1, column: column || 1});
  /* JBCefJSQuery.inject(...) — the host's transport, invisible to the page */
};
```

So the page only ever sees the function. `BridgeMessage` parses the JSON — tolerant of a path containing
quotes or commas, strict about `kind` and a non-blank `filePath`, and **forward compatible**: a message
whose `kind` this version does not know is ignored, not an error, so a newer page can speak a newer
protocol to an older plugin without breaking it.

**`window.__jcbWebViewBridge` is the version you may feature-detect.** It is absent on a page with no
bridge, `1` today, and it is bumped only when the injected contract changes incompatibly.

Everything then funnels through `NavigatorService.open(filePath, line, column)`, which resolves the path
against the project, refuses paths outside it, applies a rate limit, and opens the editor on the line with
the caret at the column. Both transports (the injected function and the HTTP fallback in § 3) call that
one method, so their behaviour cannot drift apart.

---

## 3. The HTTP fallback

A page opened in an ordinary browser is not this plugin's webview and has no injected function. The same
plugin can also listen on a loopback HTTP endpoint, which any page — including a `file://` page — can call
with an image, a `fetch`, or a hidden `<iframe>` (an `iframe` is used by this repository's renderer because
it needs no CORS grant and leaves no visible navigation).

**The server is off until it is configured** (`webview.explorer.port`). A plugin must not open a listening
socket nobody asked for.

```
GET http://127.0.0.1:<port>/open?filePath=<path>&line=<n>&column=<n>
GET http://127.0.0.1:<port>/health
```

### 3.1 The port is per host, so discover it

| Host                | Default port | Setting                 |
| ------------------- | ------------ | ----------------------- |
| `webview/webview-jetbrains` | **18881**    | `webview.explorer.port` |
| `webview/webview-vscode`    | **18882**    | `webviewExplorer.port`  |

A generated page therefore carries a **configured** port (`data-bridge-port` in this repository's pages,
`--bridge-port` on the Markdown viewer's CLI), never a constant: a page that assumed 18881 would work in
JetBrains and silently do nothing in VS Code. Because the port is a deployment detail, use
`GET /health` when you need to know whether a bridge is actually there before wiring a page to it — it is
the only endpoint that answers without credentials.

### `GET /open`

| Parameter  | Required                        | Meaning                                                              |
| ---------- | ------------------------------- | -------------------------------------------------------------------- |
| `filePath` | yes                             | same spelling as `window.openFile`'s first argument                  |
| `line`     | no                              | 1-based; defaults to `1`                                             |
| `column`   | no                              | 1-based; defaults to `1`                                             |
| `token`    | only when a token is configured | the shared secret, as an alternative to the `X-WebView-Token` header |

Responses:

| Status | Body                                                                             | Meaning                                           |
| ------ | -------------------------------------------------------------------------------- | ------------------------------------------------- |
| `200`  | `Opening <filePath>:<line>:<column>`                                             | accepted and opened                               |
| `400`  | `Missing filePath parameter`                                                     | no usable path                                    |
| `403`  | `Forbidden: configure webview.explorer.allowedOrigins or webview.explorer.token` | the caller proved nothing                         |
| `404`  | `Could not open <filePath>`                                                      | path not resolvable, or the rate limit refused it |
| `405`  | `Method Not Allowed`                                                             | not a `GET`                                       |

### `GET /health`

Useful before wiring a page to a bridge the user may not have started — and the only endpoint that
answers without credentials.

```json
{ "plugin": "hr.hrg.jetbrains.webview", "port": 18881, "allowedOrigins": 1, "tokenRequired": false }
```

### Security, and what it means for a page author

The endpoint opens an arbitrary file in the IDE, so it is denied unless the caller proves it may:

* it binds to **loopback only** (`127.0.0.1`), never `0.0.0.0`;
* an **empty allow-list denies everyone** — the safe default is closed, not open;
* a caller may present **either** an allowed `Origin` **or** the configured token, because a `file://`
  page sends no usable `Origin` and would otherwise have no route that does not weaken the Origin check;
* CORS response headers are sent **only** to an allowed origin, so an unauthenticated caller gets no grant;
* a **rate limit** applies to the navigation itself, shared by both transports.

For a generator that ships with a project this matters in one practical way: a `file://` page cannot send
a useful `Origin`, so if the bridge requires authorization the user must either add the page's origin to
the allow-list or configure a token and pass it (`?token=…` or the `X-WebView-Token` header). The simplest
local setup for a developer is a token.

---

## 4. The universal fallback

When there is neither an injected function nor a reachable bridge, the only thing a page can honestly do is
**put the location where the user can use it**: write `path:line` to the clipboard and say so. That is what
`scripts/entity-html/render.js` does, and it is worth copying because it makes a page useful in a plain
browser, in a PDF export and in a CI artifact:

```
1. window.openFile(...) present   -> call it
2. else bridge port reachable     -> hidden iframe to /open?…
3. else                           -> copy "path:line" to the clipboard, show a toast
```

Never render a link that does nothing. A dead-looking link is worse than a visible path string.

---

## 5. Writing a page: the three steps

```js
// 1. put the location on the element
const a = document.createElement('a');
a.dataset.open = 'src/main/java/com/example/Person.java';
a.dataset.line = '14';
a.dataset.member = 'Person';       // optional
a.dataset.role = 'type';           // optional
a.textContent = 'Person';

// 2. one delegated click handler for the whole page
document.addEventListener('click', (event) => {
  const link = event.target.closest('[data-open]');
  if (!link) return;
  event.preventDefault();
  open(link.dataset.open, Number(link.dataset.line) || 1);
});

// 3. the fallback ladder of § 4
function open(relative, line) {
  const target = absoluteFromLinkBase(relative);           // see below
  if (typeof window.openFile === 'function') {
    window.openFile(target, line, 1);
    return;
  }
  if (BRIDGE_PORT > 0) {
    const frame = document.createElement('iframe');        // no CORS grant needed
    frame.style.display = 'none';
    frame.src = `http://127.0.0.1:${BRIDGE_PORT}/open?filePath=${encodeURIComponent(target)}`
      + `&line=${line}&column=1`;
    document.body.appendChild(frame);
    return;
  }
  navigator.clipboard?.writeText(`${target}:${line}`);
  say(`Location copied: ${target}:${line}`);
}
```

### 5.1 Relative or absolute?

The generated reports place themselves somewhere inside the module and link to module-relative paths, so
they must turn a relative path into the absolute one the IDE wants. They do it from **the page's own
location**, never from a baked-in absolute path — a report must stay valid when the tree is moved or
cloned, and an absolute path in a committed artifact is wrong on every machine but one:

```
linkBase = <body data-link-base="../../..">      // relative from the page to the module root
target   = new URL(linkBase + '/' + relativePath, document.baseURI).pathname  // -> /abs/path
```

`data-link-base` is this repository's convention, not part of the API: the host accepts an absolute path,
so a page may compute one however it likes. What matters is that the **committed artifact contains no
absolute path** (`scripts/entity-html/entity-html.test.js` asserts exactly that for the generated page).

---

## 6. Versioning, and what may change

* **`window.openFile` and the `/open` parameters are frozen.** `(filePath, line, column)` — 1-based line and
  column, paths in either slash style, relative or absolute — is the contract; a host that accepted
  anything else would break every existing page.
* **`kind: 'openFile'` in the JSON payload is the only actionable kind today**, but the parser ignores
  unknown kinds instead of failing, so the protocol can grow (`revealInProjectView`, `openUrl`) without
  breaking an older host.
* **`window.__jcbWebViewBridge`** is the capability probe and a version number in one. Use it when you
  need to know *which* bridge you are talking to; use `typeof window.openFile === 'function'` when you only
  need to know whether navigation is possible.
* **A future host (a browser extension, an LSP sidecar, another editor) has two ways to comply**, and both
  are acceptable: inject a `window.openFile` with the same signature, or implement the `/open` endpoint.
  The repository's pages work with either, unchanged, because they try the function first and the endpoint
  second — which is exactly how `webview/webview-vscode` works today.
* **What is deliberately not in the API:** no URL scheme of our own (`jcodebuddy://…`) because it needs
  registration per OS and per host; no `postMessage` protocol because it needs a cooperating parent
  window; and no `data-column` attribute until something actually needs a column.

---

## 7. Implementing a host — the checklist

A new plugin that wants the generated pages to work in its IDE must:

1. inject `window.openFile` into every main-frame load of its webview (not just the first navigation —
   a page can be reloaded), with the § 1 signature;
2. keep an **empty allow-list denying every caller** if it offers an HTTP fallback, bind to loopback only,
   and send CORS headers only to an allowed origin;
3. resolve a path **against the project** and refuse anything outside it;
4. accept a 1-based line and column and clamp rather than reject — a page cannot know the file's length;
5. rate-limit navigation, because the endpoint is reachable from any page in the user's browser;
6. answer `404` (HTTP) or log (injected) when the file does not resolve, so a broken link is diagnosable
   instead of silent;
7. ignore unknown message kinds, so an older plugin does not break on a newer page.

Existing implementations to read, in this order:

| Concern                                              | JetBrains                                               |
| ---------------------------------------------------- | ------------------------------------------------------- |
| the injected function and version probe              | `webview/webview-jetbrains/.../bridge/WebViewBridge.java`       |
| payload parsing, unknown-kind tolerance              | `webview/webview-jetbrains/.../bridge/BridgeMessage.java`       |
| path resolution, rate limit, editor opening          | `webview/webview-jetbrains/.../bridge/NavigatorService.java`    |
| the HTTP fallback, auth, CORS, `/health`             | `webview/webview-jetbrains/.../services/HttpBridgeService.java` |
| the same API in a second host                        | `webview/webview-vscode/README.md`                              |
| a page that uses all of it, with the fallback ladder | `scripts/entity-html/render.js`                         |
| a second, smaller page that uses it                  | `scripts/markdown-view/` (README and `render.js`)       |

---

## 8. A worked example, end to end

The repository's generated entity page and the Markdown viewer built beside it are both real users of this
contract. The Markdown viewer is the smaller one and is meant to be read as a template:

```bash
# render every Markdown file of the example module into clickable pages
bun run scripts/markdown-view/index.js --module hipster-entity-example
```

It resolves a link target in three ways, which between them cover what prose about code actually
contains:

| In the Markdown                                              | Becomes                                                                                          |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| `` `src/main/java/.../Person.java` `` or a link to that path | `data-open="src/main/java/…/Person.java"`                                                        |
| a path or identifier with `#L14` / `:14`                     | the same, with `data-line="14"`                                                                  |
| `` `PersonSummary` `` — a bare type name                     | resolved through `.jcodebuddy/index/classes.json` to the declaring file and its declaration line |

That last row is the reason the class index (DEC-029) is worth having: a document can name a **class**
and the tool finds the file and the line without a path being written in the prose at all.
