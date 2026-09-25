# WebView page examples — a project navigator in two shapes

Two complete, working "navigate my project" pages for the **WebView Explorer** webview plugin
(`webview/webview-jetbrains`, and `webview/webview-vscode` which speaks the same API). They do the same job and differ in
one decision: **whether a page is one file or a folder.**

```
webview/examples/
  self-contained/
    index.html              <- EXAMPLE A: everything in one file
  with-assets/
    index.html              <- EXAMPLE B: the manual landing page
    pages/guide.html        <-   a manual deep-dive, same shell
    pages/entity-reference.html  <-   a GENERATED page
    assets/site.css         <-   shell styling
    assets/syntax-theme.css <-   microlighter's ::highlight() mechanism + themes
    assets/nav-client.js    <-   the navigation ladder
    assets/highlight-runner.js <- calls microlighter, reports status
    assets/microlighter.js  <-   vendor: microlighter 2.2.0 + 7 TextMate grammars
  smoke-test.mjs            <- verifies both examples
```

Read **[`webview/doc/webview-page-authoring.md`](../doc/webview-page-authoring.md)** for the instructions; this
file is about the two examples and how to check them.

---

## How to open them

| Where | How |
| --- | --- |
| IntelliJ / JetBrains | right-click the `.html` file in the Project view &rarr; **Open in WebView Explorer**, or `Ctrl+Alt+Shift+W` |
| VS Code | open it in the **WebView Explorer** sidebar |
| Any browser | just open the file — clicks fall back to the IDE's HTTP bridge, then to the clipboard |

In the IDE webview the plugin injects `window.openFile(path, line, column)`, so a click lands the caret
on the exact line. In a browser the same click goes to the loopback bridge on
`data-bridge-port`, which needs to be switched on and authorised — see
[`webview/doc/webview-link-api.md`](../doc/webview-link-api.md) § 3. With neither, the click copies
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
        data-link-base="../../../.." data-bridge-port="18881"></script>
<script id="microlighter" src="assets/microlighter.js"></script>
<script id="highlight-runner" src="assets/highlight-runner.js"></script>
```

`data-link-base` is resolved **against the script's own `src`**, not against the page. So it states the
distance from `assets/` to the repository root — four levels, hence `"../../../.."` — and `index.html` and
`pages/guide.html`, one folder apart, write the *identical* value. A page can be moved, or generated at a
different depth, without a link breaking.

Put the attribute on `<body>` instead and it becomes page-relative: the self-contained example writes
`"../../.."` there, because *that page* sits three levels below the repository root, not because
`assets/` does. Two bases, two mental models — pick whichever fits the shape you are building.

`data-bridge-port` is a deployment detail, not a constant: 18881 is `webview/webview-jetbrains`, 18882 is
`webview/webview-vscode`. `GET /health` on the port answers without credentials and tells you which bridge, if any,
is listening.

---

## Syntax highlighting

Offline, dependency-free, and with no token markup in the DOM:
[**microlighter**](https://github.com/davatron5000/microlighter) 2.2.0 (MIT) by Dave Rupert. It reads
TextMate grammars, turns matching token ranges into `Range` objects, and registers them on
`CSS.highlights`; `::highlight(category)` styles them. Your code stays plain text, which matters when a
reader wants to copy a generated block out of the page.

**Five languages are demonstrated, and the seven grammars they need are bundled:**

| Language | Class | Grammar(s) |
| --- | --- | --- |
| JavaScript | `language-javascript` | `source.js` |
| Java | `language-java` | `source.java` |
| JSON | `language-json` | `source.json` |
| HTML | `language-html` | `text.html.basic` &rarr; `source.css`, `source.json`, `source.js` (for what is inside `<style>`/`<script>`) |
| Markdown | `language-markdown` | `text.html.markdown` &rarr; `source.yaml` (for front matter) |

A block is a plain `<pre><code>` pair with **one text node** inside:

```html
<pre><code class="language-json">{ "answer": 42 }</code></pre>
```

Two constraints from microlighter's design apply: the code must be a single text node (escape `<`, `>`
and `&` as `&lt;`, `&gt;`, `&amp;` — never nest elements), and the host needs the CSS Custom Highlight
API, which every modern Chromium has (JetBrains JCEF and the VS Code webview included). Without it the
page still reads fine — code is plain text — and the status pill says so instead of failing silently.

Both pages report what happened in a pill, and publish it as an attribute for tests:

```js
document.body.dataset.syntaxReady   // "7/23" = 7 blocks, 23 token categories
```

---

## Running the smoke test

```bash
node webview/examples/smoke-test.mjs
```

No dependencies — it uses Node's own `fetch`/`WebSocket` to drive a real Chromium over the DevTools
Protocol, and skips the browser half if none is installed. Five checks:

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

Current output: **49 assertions across 4 pages — 47 location links verified against the tree, 46 page links
resolved on disk, 7 highlighted code blocks, 18 scripts and assets checked — all green.** A clickable link to
a project's GitHub page is navigation, not an asset, so check 5 leaves it alone.

---

## Copying this into your own project

1. Copy `with-assets/` and delete the pages you do not want — or copy `self-contained/index.html` if one
   file is what you need.
2. Change `data-link-base` on the nav-client script to point at **your** project root. Nothing else in the
   assets knows where anything is.
3. Set `data-bridge-port` to your host's port, or `0` to disable the HTTP fallback.
4. Replace the sidebar and the demo sections with your own; keep the four wiring tags.
5. Point your generator at `pages/`. Have it emit `data-open` + `data-line` and verify every link before it
   writes the file.
6. Run the smoke test. If you are generating pages, keep a version of check 3 in your build.

---

## Licence and provenance

`assets/microlighter.js` is [microlighter](https://github.com/davatron5000/microlighter) 2.2.0, MIT,
© Dave Rupert, with its bundled TextMate grammars (adapted from Microsoft VS Code, MIT). It is inlined
with four documented changes: the inlined grammar registry replaces `await import()`, the module became an
IIFE on `window.microlighter`, capability guards were added for `CSS.highlights`/`Highlight` and for
unresolved TextMate includes, and `Highlight.add()` is fed ranges from a single text node. The file's
header comment repeats this list. Everything else — the scope flattening, capture handling, begin/end
recursion and the memoised `dgm` regex cache — is upstream's algorithm.
