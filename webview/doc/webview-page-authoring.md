# WebView pages — build a project navigator the IDE can drive

How to write an HTML page that **navigates a project**: click a type, a file path or a `path:line`
reference and the IDE opens that file with the caret on that line. Written for the two webview hosts in
this repository — [**WebView Explorer**](../webview-jetbrains/README.md) for JetBrains IDEs
(`Ctrl+Alt+Shift+W`) and the [**webview/webview-vscode**](../webview-vscode/README.md) port — and for any future
host that implements the same contract.

The page side is deliberately boring: **one function, two attributes, no framework, no build step, no
network at view time.** Everything else in this document is about the three places a page gets it wrong —
unguarded calls, absolute paths, and dead links.

> **The contract itself is frozen and documented separately.** Read
> [`webview/doc/webview-link-api.md`](webview-link-api.md) first; it is the normative description of
> `window.openFile`, `data-open`/`data-line`, the HTTP fallback, the response codes and the security
> model. This document is the *how to build it* companion: structure, templates, highlighting,
> verification, troubleshooting.
>
> **Runnable examples live in [`webview/examples/`](../examples/README.md)** — the same
> navigator in both shapes, with a smoke test that verifies every link.

---

## 1. What you are building

A project navigator is a page (or a few pages) whose *content is a map of the code*: a list of types, a
file inventory, a spec that names classes, a coverage or review report. Prose alone is fine; the point is
that a location in the page is a click away from the location in the editor.

A navigator almost always ends up with two kinds of content, and they have different owners:

| | **Manual pages** | **Generated pages** |
| --- | --- | --- |
| Written by | a human, in the editor | a build step |
| Changes when | someone edits prose | the model changes |
| Examples | an architecture overview, a how-to, a decision log | an entity reference, a coverage table, a file inventory, a review dashboard |
| Must never happen | a generated block overwritten by a hand edit | a stale link that looks right and goes nowhere |

Both belong in **one navigator with one shell and one navigation client**, so that the link contract, the
fallback ladder, the theme and the offline rules exist in exactly one place. How to combine them is § 3.

**What the host gives you, and what it does not.** The IDE webview injects one function after every
main-frame load. It does not give you routing, a sidebar, a build system or syntax highlighting — those are
the page's job, which is why § 5 and § 6 exist.

---

## 2. The contract in one screen

```js
window.openFile(filePath, line, column);   // line and column are 1-based; defaults are 1
```

| Argument | Meaning |
| --- | --- |
| `filePath` | absolute (`C:/work/proj/src/main/java/A.java`, `/home/me/proj/…`) or project-relative (`src/main/java/A.java`); forward and back slashes both work |
| `line` | 1-based line; `1` when unknown; `0` or negative is clamped, never rejected |
| `column` | 1-based column; `1` is right for every case in this repository |

Return value: **none**. The call is fire-and-forget by design — the page cannot learn whether the IDE
found the file, and must not break if it did not.

The page puts a location on an element and lets one delegated handler read it:

| Attribute | Required | Meaning |
| --- | --- | --- |
| `data-open` | yes | the path or URL, in the same spelling `window.openFile` accepts |
| `data-line` | in practice | 1-based line; omit only when line 1 is genuinely meant |
| `data-member` | no | the member at that line — tooltip text, and what a human (or a verifier) checks the line against |
| `data-role` | no | what the location *is* (`type`, `accessor`, `doc-section`); **display metadata, never a routing key** |

```html
<span class="file-link"
      data-open="webview/webview-jetbrains/README.md"
      data-line="63"
      data-member="window.openFile"
      data-role="doc-section">webview/webview-jetbrains/README.md:63</span>
```

When a page is loaded inside the IDE webview, the injected script is, in full:

```js
window.__jcbWebViewBridge = 1;                 // protocol version, and the capability probe
window.openFile = function (path, line, column) {
  var msg = JSON.stringify({ kind: 'openFile', filePath: path, line: line || 1, column: column || 1 });
  /* JBCefJSQuery.inject(...) — the host's transport, invisible to the page */
};
```

### 2.1 Feature-detect, always

A page is opened in three places — the IDE webview, an ordinary browser, a CI artifact viewer — and only
the first has the function.

```js
if (typeof window.openFile === 'function') {
  window.openFile(target, line, column);
} else {
  // degrade: HTTP fallback (§ 4), or copy the location
}
```

Calling an undefined `window.openFile` throws a `TypeError`; inside a click handler that silently kills
navigation for **every later click in the page**. The symptom is "the links stopped working", not an
error message, which is why this is rule one.

Use `window.__jcbWebViewBridge` only when you need to know *which* bridge you are talking to; use the
`typeof` probe when you only need to know whether navigation is possible.

### 2.2 The rule that a generator gets wrong

**Resolve or leave alone.** Emit a link only when you can point at a file that exists; when you claim a
line, the thing you claim must be on that line. Anything else stays plain text.

A page of plausible-looking links that go nowhere is worse than plain text, because it costs a reader a
click each to discover it. `scripts/markdown-view/render.js` is the reference for this: it resolves a path,
a `path:line` suffix, or a bare **type name** through the class index (`.jcodebuddy/index/classes.json`,
DEC-029) — and returns *nothing* for a word that merely looks path-shaped.

---

## 3. Choose a shape

Two shapes, same contract. The examples are both in
[`webview/examples/`](../examples/README.md); this section is the decision.

| | **A. One self-contained file** | **B. Page + `assets/` folder** |
| --- | --- | --- |
| Files | 1 | 4 assets + one file per page |
| Restyle everything | edit each file | edit `site.css` once |
| Several pages share a client | no sharing | yes — one `<script src>` per page |
| Travels as an attachment / CI artifact | perfect | needs the folder (or a zip) |
| Diff when the client changes | the whole page | just `nav-client.js` |
| A generator regenerates one page | no | yes, without touching the shell or prose |
| Reference | [`self-contained/index.html`](../examples/self-contained/index.html) | [`with-assets/`](../examples/with-assets/index.html) |

**Choose A when a page must travel alone. Choose B when there is more than one page** — which a project
navigator always has — **or when anything is generated.**

"Self-contained" means *no network at view time*: no CDN, no bundler, no `node_modules`. A local
`<script src="assets/nav-client.js">` is not a network reference and is allowed in both shapes; what is
forbidden is an `https://` asset, because the JCEF webview opens the page with no server behind it.

### 3.1 Combining manual and generated content

Three ways, in increasing order of separation:

1. **Generated block inside a manual page.** The generator owns a table or a card grid; the prose around it
   is hand-written. Cheapest, and usually best when the model is small and stable. A generated block must
   **not** carry its own copy of the client script — the delegated handler already covers the whole
   document, including elements that arrive after load.
2. **Generated page beside a manual page.** The generator owns one file outright and links to it. Use this
   when the generated material is large, or regenerated often, or belongs to a different audience.
3. **Generated page per model entity**, with an index page linking them. Use it when a single page would be
   thousands of rows; the index then carries the navigation and each detail page carries the source links.

Whatever you pick, the shell stays shared and the generator **owns whole files or nothing** — a generator
that patches a hand-written page is a generator that will eventually delete someone's paragraph.

---

## 4. The client: the ladder, and why it has these rungs

```
0. a port is known                -> ask what the host can do: GET /health, /.well-known/webview.json
1. window.openFile(...) present   -> call it                            (inside an IDE webview)
2. a host answers /health         -> GET /open over HTTP                (webviewd, or a plugin on its port)
3. nothing answers                -> copy "path:line" and say so in a toast
```

Rung 0 is what makes rung 2 honest: `/health` reports a **capability list** and, in the manifest, how precisely
the attached editor can reach a line (`exact`, `file-only`, `none`). A page that reads it decides its rung from
data instead of from a hard-coded port, which is the difference between "the host has no editor attached" and
"the host is not there".

Rung 2 is HTTP, because a browser has no injected function:

```
GET http://127.0.0.1:<port>/open?filePath=<path>&line=<n>&column=<n>
GET http://127.0.0.1:<port>/health
```

| Host | Default port | Setting |
| --- | --- | --- |
| `webview/core/webviewd` | ephemeral, published in `.jcodebuddy/webview/host.json` | `--port` |
| `webview/webview-jetbrains` | 18881 | `webview.explorer.port` |
| `webview/webview-vscode` | 18882 | `webviewExplorer.port` |

The default is what the host **asks for**, not what it necessarily gets: it takes the next free port when
something unrelated holds that one, and it opens no endpoint at all when the port is held by a host that
already serves the same project. The port that is really in use is in the project's
`.jcodebuddy/webview/host.json` (`port`, plus `ide` and `project`), and every host's `GET /health` says the
same three things.

A few facts that decide how you wire it:

* **The port is a deployment detail, never a constant.** Put it in `data-bridge-port` (or a CLI flag, the
  way `scripts/markdown-view` does); a page that assumes 18881 works in JetBrains and silently does nothing
  in VS Code. `GET /health` answers without credentials and tells you whether a bridge is really there,
  which editor it is, and which project it serves:
  ```json
  {"plugin":"hr.hrg.jetbrains.webview","port":18881,"allowedOrigins":1,"tokenRequired":false,
   "ide":"IntelliJ IDEA","project":"D:/wrk/java/jcodebuddy"}
  ```
* **The server is off until it is configured**, binds to loopback only, and **denies every caller until one
  is authorized** — by an allowed `Origin`, or by a token (`?token=…` or the `X-WebView-Token` header).
* **A `file://` page sends no usable `Origin`**, so if authorization is on, a **token** is the only route
  that works from a local page. That is the simplest local setup for a developer.
* **An `iframe` needs no CORS grant** and leaves no visible navigation, which is why the fallback uses one
  rather than `fetch` — `fetch` from a `file://` page is blocked by CORS regardless of the server.
* **Navigations are rate limited** (20 per 20 s, shared by both transports). Do not wire a
  `mouseover` to it.
* **`404` means the path did not resolve** (or the rate limit refused it), and the plugin logs it. A broken
  link is diagnosable, never silent. `403` means the caller proved nothing.

Full parameter, response-code and security detail: [`webview/doc/webview-link-api.md`](webview-link-api.md) § 3.

### 4.3 The edit rung: propose, show the diff, then write

A page that only navigates needs none of this. A page that *changes* a file uses the write contract
([`webview-edit-api.md`](webview-edit-api.md)), and the order is not a suggestion — the plan's question 1 was
answered as **diff first, write when the reader accepts**:

```js
const client = window.jcbClient.create({ port: port, token: token });
await client.ready();                                  // rung 0: what can this host do?

const file = await client.read('src/main/java/A.java');// the host's own digest comes back with the text
const proposal = await client.proposeEdit(file.path, file.digest, [
  { startLine: 12, startColumn: 5, endLine: 12, endColumn: 9, newText: 'renamed' }
]);
show(proposal.unifiedDiff);                            // nothing has been written yet

const applied = await client.applyEdit(file.path, file.digest, edits);   // target: 'auto' by default
// applied.target === 'buffer' means the editor holds it, unsaved, in its own undo stack
// applied.target === undefined means this host wrote the file on disk
await client.undo(file.path);                          // restores the exact previous bytes
```

Rules that decide whether the page works in more than one host:

* **Never compute the digest yourself.** `/file/` answers with `X-WebView-Digest` (and an `ETag`); that is the
  digest the host will compare against, and a second implementation of the same hash is a second chance to
  disagree. The client falls back to hashing the text only when a host does not send the header.
* **`expectedDigest` is the digest of what the page read**, in both the proposal and the accepted write. The
  proposal's own `digest` describes the file it *would* produce, which is what to render next — not what to send
  back.
* **A stale digest is a refusal (`409`), never a merge.** Re-read, re-render, propose again. That is the whole
  safety argument: the browser cannot clobber a file the reader has not seen.
* **`target` says who writes.** `auto` (the default) uses the editor's buffer when the host declares `edit` and
  otherwise writes to disk; `buffer` insists on the editor and is refused with `409 no-buffer-edit` when no host
  can do it — never silently written to disk; `disk` insists on this host's own atomic write.
* **Writes need the token**, not merely an allowed `Origin` (`/open` accepts either). A page served by
  `webviewd` can carry it in its URL: `…/page/<path>?token=<the token>`.
* **`GET /api/v1/events`** streams file changes so a page can re-render instead of polling. `webview-client.js`
  uses `EventSource` where it exists and reports `watching: false` where it does not, rather than pretending.

Two clients ship here, on purpose:

| File | For |
| --- | --- |
| [`examples/with-assets/assets/nav-client.js`](../examples/with-assets/assets/nav-client.js) | navigation only, the three-rung ladder, configured from the script tag — what the existing pages use |
| [`examples/with-assets/assets/webview-client.js`](../examples/with-assets/assets/webview-client.js) | the full ladder **plus** read/propose/apply/undo/events, capability-driven, and usable with no DOM above the clipboard rung (which is why a node test can drive it) |

A page that edits, end to end: [`examples/with-assets/pages/edit-demo.html`](../examples/with-assets/pages/edit-demo.html)
(rename a row, see the unified diff, accept it, undo it). Run it through a host:

```bash
webviewd --project webview/examples --port 18899 --token demo-token
# http://127.0.0.1:18899/page/<abs path>/webview/examples/with-assets/pages/edit-demo.html?token=demo-token
# and the client itself is exercised without a browser:
node webview/examples/webview-client.test.mjs      # 29 checks against a live headless host
```

### 4.1 The link base: never an absolute path in an artifact

The host wants an absolute path; the committed artifact must contain none, because an absolute path is
wrong on every machine but one. The page therefore computes it from **its own location**:

```
target = new URL(linkBase + '/' + relativePath, <the page or the script>).pathname
```

Two spellings, and you must know which one you are using:

| Where `data-link-base` lives | Resolved against | What the value means |
| --- | --- | --- |
| on the **`<script src="…">`** tag (recommended for shape B) | the **script's own URL** | the distance from `assets/` to the project root — **identical on every page**, whatever its depth |
| on **`<body>`** (shape A) | the **page's own URL** | the distance from *this page* to the project root |

```html
<!-- shape B: same string on index.html and on pages/deep/report.html -->
<script id="nav-client" src="assets/nav-client.js"
        data-link-base="../../../.."        <!-- from assets/ to the repository root -->
        data-bridge-port="18881"></script>

<body data-link-base="../../..">            <!-- shape A: from THIS page to the repository root -->
```

**Count directories, not files.** A filename is not a level, and counting it is the single most common way
to break every link on a page at once. The examples make the arithmetic concrete:

```
<root>/webview/examples/with-assets/assets/nav-client.js
       ^       ^        ^           ^   ^
       |       |        |           |   +-- the file: not a level
       1       2        3           4       -> "../../../.."  (shape B)

<root>/webview/examples/self-contained/index.html
       ^       ^        ^
       1       2        3                  -> "../../.."     (shape A)
```

Note that the same `webview/examples` folder produces `"../../.."` in one place and `"../../../.."` in the
other, and that **both are correct** — they are measured from different anchors (the page, and the script
in `assets/`). Neither is a constant to copy: count the levels in your own tree, then verify with the
checker in § 7, which resolves every link exactly as the browser will.

### 4.2 The client, as a file you can copy

[`webview/examples/with-assets/assets/nav-client.js`](../examples/with-assets/assets/nav-client.js)
is a complete, dependency-free implementation. It exposes a small surface so pages and tests do not
re-implement the ladder:

```js
window.jcbNav.open(path, line);      // navigate programmatically
window.jcbNav.mode();                // 'injected' | 'bridge' | 'clipboard'
window.jcbNav.links();               // every [data-open] element, resolved — for tests and debugging
```

It also paints the current mode onto any element marked for it, so a reader never has to guess why a click
did nothing visible:

```html
<span class="pill" data-bridge-status>IDE bridge: probing…</span>
```

**Never render a link that does nothing.** Rung 3 exists for exactly that reason: a visible
`path:line` the user can paste into their IDE beats a dead anchor.

---

## 5. The page: a working skeleton

The smallest page that navigates a project. Copy it, then change `data-link-base`.

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Project navigator</title>
<style>
  /* Only these two rules are load-bearing. Style the rest however you like. */
  [data-open] { cursor: pointer; color: #2ee6d0; border-bottom: 1px dashed currentColor; }
  #toast { position: fixed; right: 1rem; bottom: 1rem; padding: .5rem .7rem; background: #0f3134;
           color: #d8f2f0; border-radius: 8px; opacity: 0; transition: opacity .18s; }
  #toast.show { opacity: 1; }
</style>
</head>

<!-- data-link-base: the relative path from THIS page to the project root.
     data-bridge-port: 18881 JetBrains, 18882 VS Code, 0 to disable the fallback. -->
<body data-link-base="." data-bridge-port="18881">

<h1>Project navigator</h1>

<ul>
  <li><span data-open="src/main/java/com/example/Person.java" data-line="12"
            data-member="Person" data-role="type">Person</span></li>
  <li><span data-open="README.md" data-line="1">README.md</span></li>
</ul>

<!-- Say which mode the page is in, so a click that does nothing is never a mystery. -->
<p id="bridge-status">IDE bridge: probing…</p>

<script>
(function () {
  var LINK_BASE = document.body.getAttribute('data-link-base') || '';
  var BRIDGE_PORT = parseInt(document.body.getAttribute('data-bridge-port') || '0', 10);
  var TOKEN = document.body.getAttribute('data-bridge-token') || '';
  var toast = null;

  function say(text) {
    if (!toast) { toast = document.createElement('div'); toast.id = 'toast'; document.body.appendChild(toast); }
    toast.textContent = text;
    toast.classList.add('show');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { toast.classList.remove('show'); }, 2600);
  }

  // A page-relative path -> the absolute path the IDE wants. Never a baked-in constant.
  function absoluteTarget(relative) {
    if (!relative) { return ''; }
    if (/^[A-Za-z]:[\\/]/.test(relative) || relative.charAt(0) === '/' || relative.indexOf('://') > 0) {
      return relative;                                   // already absolute or a URL
    }
    try {
      var base = LINK_BASE ? LINK_BASE.replace(/\/+$/, '') + '/' : '';
      var path = decodeURIComponent(new URL(base + relative, document.baseURI).pathname);
      return path.replace(/^\/([A-Za-z]:)/, '$1');       // strip the leading slash off /C:/...
    } catch (error) { return relative; }
  }

  function open(link) {
    var target = absoluteTarget(link.getAttribute('data-open'));
    var line = parseInt(link.getAttribute('data-line') || '1', 10);
    var member = link.getAttribute('data-member') || '';

    if (typeof window.openFile === 'function') {          // 1. the IDE webview injected this
      window.openFile(target, line, 1);
      say('Opened ' + (member || target) + ' at line ' + line + '.');
      return;
    }
    if (BRIDGE_PORT > 0) {                                // 2. loopback HTTP bridge
      var url = 'http://127.0.0.1:' + BRIDGE_PORT + '/open?filePath=' + encodeURIComponent(target)
        + '&line=' + line + '&column=1' + (TOKEN ? '&token=' + encodeURIComponent(TOKEN) : '');
      var previous = document.getElementById('bridge-frame');
      if (previous) { previous.remove(); }
      var frame = document.createElement('iframe');
      frame.id = 'bridge-frame';
      frame.style.display = 'none';
      frame.src = url;
      document.body.appendChild(frame);
      say('Sent line ' + line + ' to the IDE bridge on port ' + BRIDGE_PORT + '.');
      return;
    }
    navigator.clipboard?.writeText(target + ':' + line);   // 3. the honest fallback
    say('No IDE bridge here. Location copied: ' + target + ':' + line);
  }

  // ONE delegated handler for the whole page: generated blocks need no wiring.
  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('[data-open]') : null;
    if (!link) { return; }
    event.preventDefault();
    open(link);
  });

  var status = document.getElementById('bridge-status');
  if (typeof window.openFile === 'function') { status.textContent = 'IDE bridge: ready'; }
  else { status.textContent = BRIDGE_PORT > 0 ? 'IDE bridge: absent (HTTP fallback)' : 'IDE bridge: absent'; }
})();
</script>
</body>
</html>
```

Four rules this skeleton follows, which any page you write should too:

1. **One delegated click handler**, on `document`, reading `[data-open]`. Never per-element handlers: a
   generated block that lands in the page later must work with no wiring.
2. **Feature-detect before calling.** See § 2.1.
3. **Compute the absolute path from the link base**, not from a constant. See § 4.1.
4. **Always end somewhere the user can see** — a toast, a status line, a visible path.

### 5.1 Table rows, cards and other containers

`data-open` may go on **any** element; the host reads the attributes from the element, so the page keeps
full control of its markup. Prefer the leaf that names the thing (`<td>`, `<span>`, `<a>`) over the whole
row: a reader clicking whitespace expects nothing to happen.

Use a real `<a href="…">` when the target is *also* browsable on disk — the `href` gives middle-click,
hover-preview and "copy link address" in a browser, while the click handler intercepts the left click. Put
the *page-relative* path in `href` so it works with JavaScript disabled.

### 5.2 When a location has no file: anchors and in-page links

Rules 3 and 4 in § 5 are about *locations*. Plain in-page navigation (`#section`, another page in the same
folder) needs none of it — use ordinary `<a href>` and no `data-open`. Mixing the two is fine and is what
the examples do: the sidebar has both generated location links and ordinary page links.

---

## 6. Syntax highlighting, offline — microlighter

[**microlighter**](https://github.com/davatron5000/microlighter) (MIT, by Dave Rupert) is the highlighter
used throughout this repository and in both examples. It is ~2 KiB, has no runtime dependencies, reads
TextMate grammars with the browser's native `RegExp`, and **never wraps a token in a `<span>`**: it
registers `Range` objects on `CSS.highlights` and lets `::highlight(category)` style them.

That last property is why it is the right choice here. The DOM stays plain text, so it stays greppable,
diffable, copy-pasteable, and editable.

### 6.1 Two ways to get it into a page

| | **Inlined** (shape A) | **As an asset** (shape B) |
| --- | --- | --- |
| File | one `<script>` block in the page | `assets/microlighter.js` |
| Works from | anywhere, alone | the folder |
| Reference | [`self-contained/index.html`](../examples/self-contained/index.html) | [`assets/microlighter.js`](../examples/with-assets/assets/microlighter.js) |

Upstream ships ES modules (`import { highlightAll } from 'microlighter'`) and loads each grammar with
`await import('./grammars/x.js')`. **A JCEF webview page has no module loader and no bundler**, so both
examples use the same adaptation: the grammars are an inlined registry, the module is an IIFE exposing
`window.microlighter`, and `highlightAll()` stays asynchronous past a satisfied registry. Four changes in
total, listed in the vendor file's header:

1. the inlined grammar registry replaces the dynamic `import()`;
2. `import`/`export` became one IIFE on `window.microlighter`;
3. capability guards for `CSS.highlights` / `Highlight` and for unresolved TextMate includes;
4. `Highlight.add()` is fed ranges from a single text node (which the scanner already guarantees).

Everything else — the scope flattening, capture handling, begin/end rule recursion, the memoised `dgm`
regex cache — is upstream's algorithm, unchanged. **If you vendor it into your own project, repeat that
list in the file header**, so the next reader knows what to compare against upstream when they upgrade.

> **Upgrading.** The pinned version in these examples is `2.2.0`. To move up: take `src/highlight.js` and
> the `src/grammars/*.js` you need, re-apply the four changes, then re-run your link/highlight smoke test —
> and diff the grammar files, because a grammar change is a visual change.

### 6.2 The five languages, and the seven grammars they need

| Language | Class | Grammar | Pulls in |
| --- | --- | --- | --- |
| JavaScript | `language-javascript` | `source.js` | — |
| Java | `language-java` | `source.java` | — |
| JSON | `language-json` | `source.json` | — |
| HTML | `language-html` | `text.html.basic` | `source.css`, `source.json`, `source.js` — for what is inside `<style>`/`<script>` |
| Markdown | `language-markdown` | `text.html.markdown` | `source.yaml` — for front matter |

The dependency column is why seven grammars are bundled for five languages: the HTML grammar includes
`source.css`, `source.json` and `source.js` by scope, and the Markdown grammar includes `source.yaml` for
front matter. Miss one and that language silently highlights only partially — the tell is a code block that
is mostly one colour.

A code block is a plain pair with **one text node** inside:

```html
<pre><code class="language-json">{ "answer": 42 }</code></pre>
```

```js
window.microlighter.highlightAll().then(function (blocks) {
  console.log(blocks.length + ' block(s), ' + CSS.highlights.size + ' token categories');
});
```

Two hard constraints, both from microlighter's design:

* **The whole block must be one text node.** Escape `<`, `>`, `&` as `&lt;`, `&gt;`, `&amp;` — never nest
  elements or wrap lines in their own `<span>`s. A block with an element child is skipped, not
  half-highlighted, which is the right failure but an easy one to miss.
* **The host needs the CSS Custom Highlight API.** Every modern Chromium has it — JetBrains JCEF and the VS
  Code webview both do. Without it the page still reads perfectly (code is plain text); say so rather than
  appearing broken.

Language detection, in priority order: a `language-*` class on `<code>`, `data-language` on `<code>`, the
same two on `<pre>`, then the deprecated `lang` on `<pre>`. Aliases (`js`, `ts`, `md`, `yml`, `sh`, …) work
automatically; extra ones can be passed as `languageAliases`.

### 6.3 The theme is only CSS variables

The mechanism and the theme are separate files, deliberately:

* **`syntax-theme.css`** holds the `::highlight(category)` rules. These **do not change between themes** —
  they are the rendering half, and copying them wrong is the usual reason "highlighting does nothing".
* A **theme** is a block of `--syntax-*` custom properties, selected by an attribute:

```css
[data-syntax-theme] {
  --syntax-comment: #6f9fa2;  --syntax-keyword: #ff8a7a;  --syntax-string:  #8ee6a0;
  --syntax-constant: #f0c674; --syntax-function: #7fd6ff; --syntax-type:    #2ee6d0;
  /* … */
}
[data-syntax-theme="solarized"] { --syntax-comment: #586e75; /* … */ }
```

```html
<body data-syntax-theme="github">
```

Upstream's `themes/*.css` files (github, dracula, monokai, night-owl, tokyo-night, solarized-light, …) use
`light-dark(light, dark)` pairs and follow the OS scheme. They are plain CSS; if your page follows the OS
scheme, prefer them verbatim. If your shell is dark, keep the same rule list and retune the custom
properties, which is what the examples do.

The categories a grammar can actually produce are ~20 names:
`comment quote keyword storage at-rule doctype important section operator punctuation string regexp
attribute-value link raw numeric boolean constant symbol character-entity anchor entity function decorator
animation type support variable interpolation property key attribute-name tag selector inserted deleted`.
`window.microlighter.getCategory(scope)` flattens a TextMate scope to one of them; use the same function
when you add rules, instead of guessing from the scope name.

### 6.4 Adding a language

1. Copy the grammar from upstream `src/grammars/<language>.js` and append it to the registry array.
2. Add any grammar it `include`s by scope (`source.css` → the `css` grammar) — check its `dependencies`
   field and any bare `source.*` includes in its rules.
3. Use `class="language-<name>"` in the page. Nothing else registers.

### 6.5 Re-highlighting after the page changes

microlighter's documented convention is a bubbling event:

```js
document.dispatchEvent(new Event('syntax-highlight'));   // after injecting or editing code
```

`highlight-runner.js` listens for it, so a filter that swaps a section, or an editable block, needs one
line. Keep the vendor script *before* the runner: the runner calls `window.microlighter`.

---

## 7. Verifying before you ship

A page is a build output, so test it like one. The repository's
[`webview/examples/smoke-test.mjs`](../examples/smoke-test.mjs) is a dependency-free
implementation (`node webview/examples/smoke-test.mjs`); copy its five checks into your build.

| Check | How | Why it is not optional |
| --- | --- | --- |
| **Every script parses** | `node --check` on each inline `<script>` and each `.js` asset; every referenced local asset exists | a single typo in a fallback path is invisible until the IDE is missing |
| **The page opens offline and highlights** | load it in a real Chromium over `file://`, read back the block/category counts | proves the grammar registry and the `::highlight()` rules actually line up |
| **Every `data-open` resolves** | resolve each target through **the page's own link base**; where `data-member` is claimed, assert the member is on the line | this is the "resolve or leave alone" rule, enforced |
| **Every ordinary page link resolves** | resolve each local `href` against the page | the sidebar and breadcrumbs are navigation too |
| **Nothing is loaded off-box** | no `https://` asset, no CSS `@import`, no absolute project path in the source | the JCEF webview has no server and no guaranteed network |

Two details worth copying from that test:

* **Strip `<pre>` blocks before scanning for links.** Code samples contain escaped markup
  (`&lt;div class="x"&gt;`), and a naive attribute scan finds `data-open`-shaped text inside a sample.
  Samples are demo content, never wiring.
* **Publish the result from the page itself.** `highlight-runner.js` sets
  `document.body.dataset.syntaxReady` to `"<blocks>/<categories>"`, so the test asserts *highlighting ran*
  rather than merely that the HTML parsed. A page that reports its own state is far easier to test than one
  that has to be poked.

For a generator, keep check 3 in the generator and fail the run on the first unverifiable candidate, in
DEC-022's diagnostic format. A dropped link that is reported is a bug you fix once; a dropped link that is
silent is a bug you ship.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Every click after the first does nothing | an unguarded `window.openFile` call threw a `TypeError` in the handler | feature-detect before calling (§ 2.1) |
| A click does nothing, ever | the element has no `data-open`, or the handler is bound to the wrong root | put `data-open` on the element; delegate from `document` |
| The IDE opens the wrong file | the link base is wrong, or the page used an absolute path that only exists on one machine | count the levels (§ 4.1); never commit an absolute path |
| The IDE opens the file, caret on line 1 | `data-line` missing, or the value is not a number | 1-based `data-line`; the client falls back to 1 |
| `403` from `/open` | the caller proved nothing — a `file://` page sends no usable `Origin` | configure a token and pass `?token=…` |
| `404` from `/open` | the path did not resolve against the project, or the rate limit refused it | check the path; read the plugin log |
| No highlighting at all | `CSS.highlights`/`Highlight` missing, or the vendor script loaded *after* the runner | load the vendor first; report the API status on the page |
| One language highlights only partially | a grammar dependency is missing (HTML → css/json/js, Markdown → yaml) | bundle the dependency grammar (§ 6.2) |
| A code block is not highlighted while others are | it has an element child, or a whitespace sibling — microlighter needs **one text node** | escape `<`/`>`/`&`; no nested elements |
| Highlighting is invisible but the pill says it ran | the `::highlight()` rules are missing, or a category has no colour | copy the rule list from `syntax-theme.css` verbatim (§ 6.3) |
| The page is blank in the IDE but fine in a browser | a remote asset, or a module `<script type="module">` the webview never fetched | no CDN, no modules at view time (§ 3) |

Turning on DevTools inside the IDE is worth the two minutes: open **Help → Find Action → Registry**, set
`ide.browser.jcef.debug.port` to `9222`, restart, then attach Chrome DevTools at
<http://localhost:9222>. Everything above becomes observable — the console, the DOM, and
`CSS.highlights` in the console.

---

## 9. Checklist

Before you call a page done:

- [ ] `window.openFile` is **feature-detected** at every call site.
- [ ] One **delegated** click handler on `document` reads `[data-open]`.
- [ ] `data-open` targets are **project-relative**; the absolute path is computed from the link base.
- [ ] `data-link-base` is correct **for its own spelling** — script-relative or page-relative (§ 4.1).
- [ ] `data-bridge-port` is configured (or `0`), never assumed; `GET /health` verified against the host.
- [ ] Every link has a 1-based `data-line`, and `data-member` where a member is claimed.
- [ ] Everything unresolvable is **plain text**, not a link.
- [ ] The page ends every click somewhere visible: IDE, bridge, or a copied `path:line`.
- [ ] No CDN, no `node_modules`, no `https://` asset, no absolute path in the artifact.
- [ ] The mode is shown on the page (`data-bridge-status`) and the highlight status too.
- [ ] Code blocks are `<pre><code class="language-x">` with **one text node**; `<`/`>`/`&` escaped.
- [ ] Both smoke-test halves pass: links resolve, and highlighting registered ranges.
- [ ] If anything is generated: the generator owns whole files, verifies every link, and reports what it
      dropped.

---

## 10. Where to read more

| Document | What it answers |
| --- | --- |
| [`webview/doc/webview-link-api.md`](webview-link-api.md) | the frozen contract: `openFile`, attributes, `/open`, `/health`, security, versioning |
| [`webview/examples/README.md`](../examples/README.md) | the two runnable examples and what the smoke test checks |
| [`webview/webview-jetbrains/README.md`](../webview-jetbrains/README.md) | the JetBrains host: tool window, HTTP bridge, VM options, JCEF debugging |
| [`webview/webview-vscode/README.md`](../webview-vscode/README.md) | the same API in VS Code, port 18882 |
| [`scripts/entity-html/README.md`](../../scripts/entity-html/README.md) | the repository's own generated navigator, end to end |
| [`scripts/markdown-view/README.md`](../../scripts/markdown-view/README.md) | the smallest complete generator: Markdown → clickable pages, link resolver included |
| [`doc-hipster-entity/architecture/decisions/DEC-027.md`](../../doc-hipster-entity/architecture/decisions/DEC-027.md) | why reports are self-contained, framework-free files with verified links |
| [`doc-hipster-entity/architecture/decisions/DEC-029.md`](../../doc-hipster-entity/architecture/decisions/DEC-029.md) | the class index: how a *type name* becomes a file and a line |
| [microlighter](https://github.com/davatron5000/microlighter) | the highlighter: grammars, themes, the `<micro-lighter>` element, editable code |

**Provenance.** `microlighter` is MIT, © Dave Rupert, with TextMate grammars adapted from Microsoft VS Code
(MIT). The vendored copy in these examples is version 2.2.0 with the four documented adaptations listed in
§ 6.1 and repeated in the file header.
