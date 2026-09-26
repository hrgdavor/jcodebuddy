# The navigation contract — frozen

This is the contract between **a web page that wants to navigate a project** and **the webview host that
performs the navigation**. It is deliberately small, and deliberately the same for every host, so a
document generator writes one call and works in JetBrains today, in VS Code today, in a standalone
a standalone server, and in any other host that implements this.

Audience: anyone generating a clickable document — an HTML report, a rendered Markdown page, a review
dashboard, a coverage view, a code-graph view.

**This file is normative for the page side and frozen.** § 6 says exactly what may change; a host that
accepted anything else would break every existing page, so nothing here is a suggestion.

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
| `column`   | number | **1-based** column. `1` is fine for every use case this contract has. |

Return value: **none**. The call is fire-and-forget by design; the page cannot know whether the editor
found the file. A host that wants to signal failure does it through its own console or an HTTP status to an
HTTP caller, never by throwing into the page — a navigation failure must not break the page's own script.

**Feature-detect before you call.** A page is loaded in three places — the host's own webview, an ordinary
browser, and possibly a CI artifact viewer — and only the first has the function:

```js
if (typeof window.openFile === 'function') {
  window.openFile(target, line, column);   // the editor will navigate
} else {
  // degrade: HTTP transport (§ 3), or copy the location (§ 4)
}
```

Calling an undefined `window.openFile` throws a `TypeError` and, in a click handler, silently kills
navigation for every later click in that page. Always guard.

### 1.1 What the page puts on its links

The contract is a pair of attributes plus one function. **Any** element — `<a>`, `<span>`, `<td>` — may
carry them; the host reads them from the element, so the page keeps full control of its markup:

| Attribute     | Required        | Meaning                                                                 |
| ------------- | --------------- | ----------------------------------------------------------------------- |
| `data-open`   | yes             | the path or URL to open, in the same spelling `window.openFile` accepts |
| `data-line`   | yes in practice | 1-based line; omit only when line 1 is genuinely meant                  |
| `data-member` | no              | the member name at that line (`firstName`, `PersonSummary.Record`) — for the tooltip and for a human verifying a link |
| `data-role`   | no              | what the location *is* (`accessor`, `annotation`, `enum-constant`, …) — display metadata, never a routing key |

There is no attribute for `column`, because nothing needs one: a page that genuinely does passes the third
argument to `window.openFile` directly.

Two rules follow from the table, and they are the two a generator gets wrong:

1. **`data-open` is a path, not a label.** What the reader sees (`Person`, `the accessor`) and what is
   opened are separate; the host never guesses one from the other.
2. **Never claim a line you cannot verify.** The line is where the reader lands; a link that opens the
   right file on the wrong line is worse than plain text, because it costs a click to discover. Verify
   before writing the page — see [`page-authoring.md`](page-authoring.md) § "Verifying before you ship".

### 1.2 The version probe

```js
window.__jcbWebViewBridge   // absent | 1
```

This is the **injected contract version**, and it is the one value that tells a page *which* bridge it is
talking to, where `typeof window.openFile === 'function'` only tells it whether navigation is possible at
all. It is absent on a page with no bridge, `1` today, and it is bumped only when the injected contract
changes incompatibly. `GET /health` reports the same number as `bridgeVersion` (§ 3.2).

---

## 2. What a host must do — the minimal contract

A page can only rely on what a host actually implements, so this is the acceptance list a page assumes —
and the list to check when a click does nothing. It is short on purpose; a host may implement more (§ 3)
and a page discovers that from `GET /health` rather than by trying.

1. **Inject `window.openFile` into every main-frame load** of its webview — not just the first navigation,
   because a page can be reloaded — with the § 1 signature, and set `window.__jcbWebViewBridge`.
2. **Own a notion of "the project."** `data-open` paths are relative to it, and an absolute path is
   accepted as-is. It must be the same directory the editor has open, or every relative link misses.
3. **Clamp rather than reject** a 1-based line and column: a page cannot know the file's length, and line
   `0` means "the first line", not "an error".
4. **Answer for a file that does not resolve**, so a broken link is diagnosable instead of silent: the
   injected path logs, and the HTTP transport answers `404` (§ 3).
5. **Ignore a message kind it does not know**, so a newer page can speak a newer protocol to an older host
   without breaking it.

---

## 3. The HTTP transport

A page opened in an ordinary browser is not the host's webview and has no injected function. The same host
may also listen on a **loopback** HTTP endpoint, which any page — including a `file://` page — can call
with an image, a `fetch`, or a hidden `<iframe>`. A hidden `iframe` is what the kit's client uses, because
it needs no CORS grant and leaves no visible navigation; `fetch` from a `file://` page is blocked by CORS
regardless of the server.

**A host must not open a listening socket nobody asked for**, so the server is off until it is configured.

```
GET http://127.0.0.1:<port>/open?filePath=<path>&line=<n>&column=<n>
GET http://127.0.0.1:<port>/health
```

### 3.1 The port is per host, so discover it

| Host | Conventional port | Where the running port is published |
| --- | --- | --- |
| JetBrains plugin (`WebView Explorer`) | `18881` (setting `webview.explorer.port`) | `<project>/.jcodebuddy/webview/host.json` |
| VS Code extension | `18882` (setting `webviewExplorer.port`) | `<project>/.jcodebuddy/webview/host.json` |
| A standalone host | ephemeral — it asks for `0` unless told otherwise | `<project>/.jcodebuddy/webview/host.json` |

**The listed port is a request, not an address.** A host takes the next free port when something unrelated
holds the one it asked for, so a generated page carries a **configured** port (the kit writes
`data-bridge-port`), never a constant: a page that assumed `18881` would work in JetBrains and silently do
nothing in VS Code.

Two ways to find the truth, and they answer different questions:

* **`<project>/.jcodebuddy/webview/host.json`** — which port *this checkout* is on, written by the host
  that bound it. This is local state, ignored by git, and it **outlives the host**: a stopped host's record
  is normal, so never treat the file as proof that something is listening.
* **`GET /health`** — whether a host is *really there*, and what it can do. The only endpoint that answers
  without credentials.

### 3.2 `GET /health`

Useful before wiring a page to a host the user may not have started, and how a page picks its rung from
data instead of by calling something and watching it fail.

```json
{ "plugin": "hr.hrg.jetbrains.webview", "port": 18881, "allowedOrigins": 1, "tokenRequired": false,
  "bridgeVersion": 1, "capabilities": ["open", "select"], "ide": "IntelliJ IDEA",
  "project": "D:/wrk/myproject" }
```

| Key | Meaning |
| --- | --- |
| `plugin` | which host is answering — lets a script tell one host's convention from another's |
| `port` | the port actually bound, which is how a page learns an ephemeral one |
| `allowedOrigins` | how many origins are trusted; **`0` means every caller is refused**, and that is the default rather than an error |
| `tokenRequired` | whether a token is configured |
| `bridgeVersion` | the same number a page sees as `window.__jcbWebViewBridge` (§ 1.2) |
| `capabilities` | what the host can do **now**, sorted. **An empty array is the honest answer for a host with no editor attached**, and it is how a page chooses its fallback instead of calling a verb and watching it fail |
| `ide` | a human name for the editor — `"IntelliJ IDEA"`, `"Visual Studio Code"`, `"unknown"`, or a standalone host's own name |
| `project` | the directory **this endpoint serves**, forward-slashed and absolute, or the empty string when the host does not know it yet |

`capabilities` may contain `open`, `select`, `reveal`, `serveFile`, `edit`, `diff`, `undo` and `watch`. The
ones this kit's pages use are `open` (§ 1) and, when a page edits, `edit` and `diff`
([`edit-api.md`](edit-api.md) § 2).

### 3.3 `GET /open`

| Parameter  | Required                        | Meaning                                                              |
| ---------- | ------------------------------- | -------------------------------------------------------------------- |
| `filePath` | yes                             | same spelling as `window.openFile`'s first argument                  |
| `line`     | no                              | 1-based; defaults to `1`                                             |
| `column`   | no                              | 1-based; defaults to `1`                                             |
| `token`    | only when a token is configured | the shared secret, as an alternative to the `X-WebView-Token` header |

| Status | Meaning                                            | What a page should do |
| ------ | -------------------------------------------------- | --------------------- |
| `200`  | accepted and opened                                | — |
| `400`  | no usable path                                     | the link is malformed; fix the generator |
| `403`  | the caller proved nothing, or the path resolved outside the project | authorise the caller (§ 3.4); if the path is the cause, the link escaped the project |
| `404`  | the path did not resolve                           | the link is stale; a broken link must be dropped, not shipped |
| `405`  | not a `GET`                                        | — |
| `429`  | the rate limit refused the request                 | the page is navigating too fast; do not wire `mouseover` to navigation |

**A page must not depend on which failure it was.** Treat "not 200" as "it did not open", which is the only
reading this contract ever promised. The set of statuses has grown — an escaping path answers `403` and the
rate limit `429`, both of which used to be a plain `404` — and may grow again; `200` and `403` (the caller
proved nothing) are the two a page could ever act on.

### 3.4 Security, and what it means for a page author

The endpoint opens an arbitrary file in the editor, so it is denied unless the caller proves it may:

* it binds to **loopback only** (`127.0.0.1`), never `0.0.0.0`;
* an **empty allow-list denies everyone** — the safe default is closed, not open;
* a caller may present **either** an allowed `Origin` **or** the configured token, because a `file://`
  page sends no usable `Origin` and would otherwise have no route that does not weaken the Origin check;
* CORS response headers are sent **only** to an allowed origin, so an unauthenticated caller gets no grant;
* a **rate limit** applies to the navigation itself, shared by every transport of that host (20 per 20 s in
  this implementation), so a page that spins cannot spend a separate allowance from a page that spins on
  clicks.

For a generator that ships with a project this matters in one practical way: **a `file://` page cannot send
a useful `Origin`**, so if the host requires authorization the user must either add the page's origin to
the allow-list or configure a token and pass it (`?token=…` or the `X-WebView-Token` header). The simplest
local setup for a developer is a token. [`host-in-this-project.md`](host-in-this-project.md) covers this
from the page author's side.

**Every state-changing route requires the token alone**, never merely an allowed `Origin`: the origin rule
is shared by every page the reader has open, while only a page this host served can hold the secret. See
[`edit-api.md`](edit-api.md) § 2.

### 3.5 Serving the page itself

A host that owns a file-serving route makes the page's own assets reachable, and it names the file's digest
so a page can propose an edit against exactly the bytes it read:

```
GET http://127.0.0.1:<port>/file/<percent-encoded absolute path>   → the file, with X-WebView-Digest (and an ETag)
GET http://127.0.0.1:<port>/page/<percent-encoded absolute path>   → the same file, with window.openFile injected
```

`/page/` differs from `/file/` by exactly one thing: the bridge is installed, so a page served over HTTP
inside an ordinary browser has the function of § 1 as well. Both are authorised by the same rules as
`/open` and refused the same way. A host that does not implement them omits `serveFile` from
`capabilities`, and a page falls back to the ladder's last rung (§ 4).

---

## 4. The fallback ladder

A page that only navigates needs three rungs, in this order, and the last one must always be reachable:

```
1. window.openFile(...) present  -> call it                      (the host's own webview)
2. a host answers /health        -> GET /open over HTTP          (any other host on its port)
3. nothing answers               -> copy "path:line" and say so  (browser, CI artifact, PDF export)
```

**Never render a link that does nothing.** A visible `path:line` the reader can paste into their editor
beats a dead anchor, and a status line that says which rung the page is on turns "the links stopped
working" into "no host here, clipboard only".

`data-bridge-port` (on the `<body>` or on the client's `<script>` tag, which is what the kit's client
reads) is how a page is configured for rung 2; `0` disables it. The kit's client implements exactly this
ladder and paints the current rung onto any element carrying `data-bridge-status` — see
[`page-authoring.md`](page-authoring.md).

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

// 3. the ladder of § 4
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

The host accepts an absolute path, so a committed artifact that contains project-relative links must turn
one into the other. It does it from **the page's own location**, never from a baked-in absolute path — a
report must stay valid when the tree is moved or cloned, and an absolute path in a committed artifact is
wrong on every machine but one:

```
linkBase = <body data-link-base="../../..">      // relative from the page to the project root
target   = new URL(linkBase + '/' + relativePath, document.baseURI).pathname  // -> /abs/path
```

`data-link-base` is a **convention, not part of the API** — the host never sees it. What matters is that
the committed artifact contains no absolute path.
[`page-authoring.md`](page-authoring.md) § "The link base" has the full spelling rules, including the
script-relative form that makes one value correct for pages at every depth.

---

## 6. Versioning, and what may change

* **`window.openFile` and the `/open` parameters are frozen.** `(filePath, line, column)` — 1-based line
  and column, paths in either slash style, relative or absolute — is the contract; a host that accepted
  anything else would break every existing page.
* **The `/open` status codes are not frozen, and a page must not depend on them.** `200` and `403` (the
  caller proved nothing) are the two a page could ever act on. The set grew when the path jail and the rate
  limiter became one shared implementation; a caller that treats "not 200" as "it did not open" is
  unaffected, which is the only reading the contract ever promised.
* **`data-open`, `data-line`, `data-member` and `data-role` keep their meanings.** New optional attributes
  may be added; an existing one will not change what it means.
* **`kind: 'openFile'` is the only actionable message kind today**, but the payload parser ignores unknown
  kinds instead of failing, so the protocol can grow (`revealInProjectView`, `openUrl`) without breaking an
  older host. A page must not depend on that growth having happened.
* **`window.__jcbWebViewBridge`** is the capability probe and a version number in one.
* **A new host has two ways to comply**, and both are acceptable: inject a `window.openFile` with the same
  signature, or implement the `/open` endpoint. Pages that try the function first and the endpoint second
  work with either, unchanged.
* **What is deliberately not in the API:** no URL scheme of our own (`myapp://…`), because it needs
  registration per OS and per host; no `postMessage` protocol, because it needs a cooperating parent
  window; and no `data-column` attribute until something actually needs a column.

---

## 7. Implementing a host — the checklist

A host that wants existing pages to work in it must:

1. inject `window.openFile` into every main-frame load of its webview (not just the first navigation — a
   page can be reloaded), with the § 1 signature, and set `window.__jcbWebViewBridge`;
2. keep an **empty allow-list denying every caller** if it offers the HTTP transport, bind to loopback only,
   and send CORS headers only to an allowed origin;
3. resolve a path **against the project** and refuse anything outside it;
4. accept a 1-based line and column and clamp rather than reject;
5. rate-limit navigation, because the endpoint is reachable from any page in the user's browser;
6. answer `404` (HTTP) or log (injected) when the file does not resolve, so a broken link is diagnosable
   instead of silent;
7. ignore unknown message kinds, so an older host does not break on a newer page;
8. answer `GET /health` with the document of § 3.2, including an honest `capabilities` list and the
   `project` it serves.

Anything beyond that — the rule that keeps one host per project, the manifest, the descriptor, the editor
adapters — is a host author's concern and is not part of what a page may rely on.
