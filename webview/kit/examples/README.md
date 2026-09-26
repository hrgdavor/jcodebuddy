# The two runnable examples — a project navigator in two shapes

Two complete, working "navigate my project" pages, for any host that implements the contract in
[`doc/contract.md`](../doc/contract.md). They do the same job and differ in one decision: **whether a page
is one file or a folder.**

```
examples/
  self-contained/
    index.html              <- EXAMPLE A: everything in one file
  with-assets/
    index.html              <- EXAMPLE B: the manual landing page
    pages/guide.html        <-   a manual deep-dive, same shell
    pages/entity-reference.html  <-   a page written as if a generator owned it
    pages/edit-demo.html    <-   an EDITING page: rename, see the diff, apply it, undo it
    assets/site.css         <-   shell styling
    assets/syntax-theme.css <-   microlighter's ::highlight() mechanism + themes
    assets/nav-client.js    <-   the navigation ladder
    assets/webview-client.js <-  the full client: discovery, navigation, edits, events
    assets/highlight-runner.js <- calls microlighter, reports status
    assets/microlighter.js  <-   vendor: microlighter 2.2.0 + 7 TextMate grammars
  smoke-test.mjs            <- drives every page in a real Chromium
  webview-client.test.mjs   <- drives the write verbs against a live headless host
```

Read **[`doc/page-authoring.md`](../doc/page-authoring.md)** for the instructions; this
file is about the two examples and how to check them.

---

## How to open them

| Where | How |
| --- | --- |
| Inside an IDE host | open the `.html` file **through the host**, not from the file manager — in the JetBrains plugin that is right-click &rarr; **Open in WebView Explorer**, or `Ctrl+Alt+Shift+W` |
| Any browser | just open the file — clicks fall back to the host's HTTP transport on `data-bridge-port`, then to the clipboard |

Opening a page through the host is what installs `window.openFile(path, line, column)`, so a click lands the
caret on the exact line. In an ordinary browser the same click goes to the loopback transport, which needs
to be switched on and authorised — see [`doc/contract.md`](../doc/contract.md) § 3 and
[`doc/host-in-this-project.md`](../doc/host-in-this-project.md) § 4. With neither, the click copies
`path:line` to your clipboard and says so; the page never renders a link that does nothing.

---

## Example A — `self-contained/index.html`

One file. No `<script src>`, no `<link rel="stylesheet">`, no CDN, no `node_modules`, no network at view
time: the shell CSS, the navigation client, the highlight runner, microlighter **and seven TextMate
grammars** are all inlined. Copy it anywhere, double-click it, and it still works.

It mixes the two kinds of content a navigator always ends up with — hand-written prose and
generated-looking tables — as sections of one document with one navigation shell.

**Pick this shape when the page has to travel:** an attachment, a CI artifact, a zip somebody unpacks.

## Example B — `with-assets/index.html` + `pages/` + `assets/`

The same navigator split into files, and the shape a project with a generator wants:

* **one shell** — `assets/site.css` and `assets/syntax-theme.css` style every page, so restyling is one
  edit, not N;
* **one client** — `assets/nav-client.js` is referenced, never copied, so a fix lands everywhere at once;
* **one page per owner** — `pages/entity-reference.html` is written as if a build step produced it and can
  be regenerated without touching the manual prose or the shell.

**Pick this shape when there is more than one page**, which a project navigator always has.

### The wiring, which is the whole point

```html
<link rel="stylesheet" href="assets/site.css">
<link rel="stylesheet" href="assets/syntax-theme.css">

<body data-syntax-theme="default">

<script id="nav-client" src="assets/nav-client.js"
        data-link-base="../../../../.." data-bridge-port="18881"></script>
<script id="microlighter" src="assets/microlighter.js"></script>
<script id="highlight-runner" src="assets/highlight-runner.js"></script>
```

`data-link-base` is resolved **against the script's own `src`**, not against the page. So it states the
distance from `assets/` to the project root — five levels as this repository happens to nest it, hence
`"../../../../.."` — and `index.html` and `pages/guide.html`, one folder apart, write the *identical* value.
A page can be moved, or generated at a different depth, without a link breaking. **The number is not the
point; the anchor is.** Count the levels in your own tree — `check-pages.mjs` will tell you if you counted
wrong.

Put the attribute on `<body>` instead and it becomes page-relative: the self-contained example writes
`"../../../.."` there, because *that page* sits four levels below the project root, not because `assets/`
does. Two bases, two mental models — pick whichever fits the shape you are building.

`data-bridge-port` is a deployment detail, not a constant: a page written for one host's conventional port
works there and silently does nothing in another. See
[`doc/host-in-this-project.md`](../doc/host-in-this-project.md) § 2 for where the running port is published,
and `GET /health` — which answers without credentials — for whether a host is listening at all.

---

## Syntax highlighting

Offline, dependency-free, and with no token markup in the DOM:
[**microlighter**](https://github.com/davatron5000/microlighter) 2.2.0 (MIT) by Dave Rupert. It reads
TextMate grammars, turns matching token ranges into `Range` objects, and registers them on
`CSS.highlights`; `::highlight(category)` styles them. Your code stays plain text, which matters when a
reader wants to copy a generated block out of the page.

**Five languages are demonstrated, and the seven grammars they need are bundled:** JavaScript, Java, JSON,
HTML (which pulls in CSS, JSON and JS for what is inside `<style>`/`<script>`) and Markdown (which pulls in
YAML for front matter). Miss a dependency grammar and that language highlights only partially, which is the
tell: a code block that is mostly one colour.

A block is a plain `<pre><code>` pair with **one text node** inside:

```html
<pre><code class="language-json">{ "answer": 42 }</code></pre>
```

The code must be escaped (`<`, `>` and `&` as `&lt;`, `&gt;`, `&amp;`) and never nested in elements, and
the host needs the CSS Custom Highlight API — every modern Chromium has it. Without it the page still reads
perfectly, because the code is plain text, and the status pill says so instead of failing silently.

Both example pages report what happened in a pill and publish it as an attribute for tests:

```js
document.body.dataset.syntaxReady   // "7/23" = 7 blocks, 23 token categories
```

The full story — the two vendoring shapes, the four adaptations in the vendored copy, the theme-is-only-CSS-
variables mechanism, adding a language and re-highlighting after the page changes — is in
[`doc/page-authoring.md`](../doc/page-authoring.md) § "Syntax highlighting, offline".

---

## Running the checks

```bash
node ../scripts/check-pages.mjs --site . --root ../../..   # every link, offline, no browser needed
node smoke-test.mjs                                       # the same pages in a real Chromium
bun webview-client.test.mjs                               # the write verbs, against a live headless host
```

`smoke-test.mjs` has no dependencies: it uses Node's own `fetch`/`WebSocket` to drive a real Chromium over
the DevTools Protocol, and **skips the browser half** when no Chromium is installed rather than passing
quietly. Five checks, in the same order as `check-pages.mjs`:

1. **every script parses** — inline blocks and every `.js` asset, with `node --check`; and every local
   asset a page references must exist;
2. **each page renders offline and highlights** — loaded over `file://`, microlighter must register
   ranges for the page's languages, and the navigation client must resolve a link to an absolute path;
3. **every `data-open` target resolves** — through the *same* link base the page computes, with
   `data-member` checked against the claimed line, because "resolve or leave alone" is what stops a
   generated page shipping links that look right and go nowhere;
4. **every ordinary page link resolves on disk** — the sidebar and breadcrumb navigation, so no page
   sends a reader to a file that is not there;
5. **nothing is loaded off-box** and no absolute project path is baked in.

A clickable link to a project's GitHub page is navigation, not an asset, so check 5 leaves a URL target
alone. The counts both tools print are evidence, not constants: they change whenever a page does.

---

## Copying this into your own project

1. Copy the whole kit, not only these examples — the documents in `../doc/` are the instructions, and
   `../scripts/check-pages.mjs` is the verifier to keep.
2. Delete the pages you do not want, or keep `self-contained/index.html` alone if one file is what you
   need.
3. Change `data-link-base` on the nav-client script to point at **your** project root. Nothing else in the
   assets knows where anything is, and nothing else needs to change when your tree moves.
4. Set `data-bridge-port` to your host's port, or `0` to disable the HTTP fallback.
5. Replace the sidebar and the demo sections with your own; keep the four wiring tags.
6. Point your generator at `pages/`. Have it emit `data-open` + `data-line` and verify every link before it
   writes the file.
7. Run both checks. If you generate pages, keep `check-pages.mjs` in your build.

---

## Licence and provenance

`assets/microlighter.js` is [microlighter](https://github.com/davatron5000/microlighter) 2.2.0, MIT,
© Dave Rupert, with its bundled TextMate grammars (adapted from Microsoft VS Code, MIT). It is inlined
with four documented changes: the inlined grammar registry replaces `await import()`, the module became an
IIFE on `window.microlighter`, capability guards were added for `CSS.highlights`/`Highlight` and for
unresolved TextMate includes, and `Highlight.add()` is fed ranges from a single text node. The file's
header comment repeats this list. Everything else — the scope flattening, capture handling, begin/end
recursion and the memoised `dgm` regex cache — is upstream's algorithm.
