# Building a page — the shapes, the skeleton, the checks

How to build an HTML page that **navigates a project**: a click on a type, a file path or a `path:line`
reference opens that file in the reader's editor with the caret on that line. This is the *how to build it*
companion to [`contract.md`](contract.md), the normative description of the call, the attributes, the HTTP
transport and the security model. Where the two could disagree the contract wins; this document never
restates its tables.

**Prerequisite: a host is already attached to your project.** A page does not install a plugin, start a host
or authorise anything — a person does that once. If it is not done yet, read
[`host-in-this-project.md`](host-in-this-project.md) first; it is short, and it is what turns "my clicks do
nothing" into a one-line answer.

The page side is deliberately boring: **one function, two attributes, no framework, no build step, no
network at view time.** Everything else here is about the three places a page gets it wrong — unguarded
calls, absolute paths, and dead links. Runnable examples live in
[`../examples/README.md`](../examples/README.md): the same navigator in both shapes, both spellings of the
link base, and the checks that verify every link.

---

## 1. What a navigator is

A project navigator is a page (or a few pages) whose *content is a map of the code*: a list of types, a file
inventory, a specification that names classes, a coverage or review report. The point is that a location in
the page is one click away from the location in the editor. A navigator ends up with two kinds of content,
and they have different owners:

| | **Manual pages** | **Generated pages** |
| --- | --- | --- |
| Written by | a human, in the editor | a build step |
| Changes when | someone edits prose | the model changes |
| Examples | an architecture overview, a how-to, a decision log | a type reference, a coverage table, a file inventory, a review dashboard |
| Must never happen | a generated block overwritten by a hand edit | a stale link that looks right and goes nowhere |

Both belong in **one navigator with one shell and one navigation client**, so the link contract, the fallback
ladder, the theme and the offline rules exist in exactly one place — [§ 3](#3-combining-manual-and-generated-content) is how to combine them. The host gives you the
injected function and nothing else: routing, the sidebar, the build step and syntax highlighting are the
page's job, which is why [§ 6](#6-the-page-a-working-skeleton) and [§ 8](#8-syntax-highlighting-offline) exist.

---

## 2. The contract is one function and two attributes

```js
window.openFile(filePath, line, column);   // line and column are 1-based; defaults are 1
```

`data-open` carries the location, `data-line` the 1-based line, and the optional `data-member` and
`data-role` carry display metadata; the complete table, the argument spellings, the return value, the HTTP
transport and the version probe are in [`contract.md`](contract.md) § 1 — read that once, and do not
paraphrase it into your own page:

```html
<span data-open="src/main/java/com/example/Person.java" data-line="14"
      data-member="Person" data-role="type">Person</span>
```

Two rules decide whether a generated page is usable:

1. **`data-open` is a path, not a label.** What the reader sees and what is opened are separate; the host
   never guesses one from the other.
2. **Resolve or leave alone.** Emit a link only when you can point at a file that exists; when you claim a
   line, the thing you claim must be on that line. Anything else stays plain text, because a page of
   plausible-looking links that go nowhere costs a reader a click each to discover.
   [§ 9](#8-verifying-before-you-ship) is how you enforce it.

### 2.1 Feature-detect, always

A page is opened in three places — the host's own webview, an ordinary browser, and a CI artifact viewer —
and only the first has the function.

```js
if (typeof window.openFile === 'function') {
  window.openFile(target, line, column);
} else {
  // degrade: the HTTP transport, or copy the location (§ 4)
}
```

Calling an undefined `window.openFile` throws a `TypeError`; inside a click handler that silently kills
navigation for **every later click in the page**. The symptom is "the links stopped working", not an error
message, which is why this is rule one. Use `window.__jcbWebViewBridge` only to know *which* bridge you are
talking to; use the `typeof` probe to know whether navigation is possible at all.

---

## 3. Combining manual and generated content

Three ways, in increasing order of separation:

1. **Generated block inside a manual page.** The generator owns a table or a card grid; the prose around it
   is hand-written. Cheapest, and usually best when the model is small and stable. Such a block must **not**
   carry its own copy of the client script — one delegated handler already covers elements that arrive after
   load.
2. **Generated page beside a manual page.** The generator owns one file outright and the manual pages link
   to it. Use it when the generated material is large, regenerated often, or for a different audience.
3. **One generated page per model entity**, with an index page linking them. Use it when a single page would
   be thousands of rows; the index carries the navigation, each detail page the source links.

Whatever you pick, the shell stays shared and the generator **owns whole files or nothing** — a generator
that patches a hand-written page is a generator that will eventually delete someone's paragraph. It must
also verify its own output: keep the link check of [§ 9](#8-verifying-before-you-ship) in the generator and
fail the run on the first unverifiable candidate, naming the file and the candidate. A dropped link that is
reported is a bug you fix once; a dropped link that is silent is a bug you ship.

---

## 4. The client: the ladder, and why it has these rungs

```
0. a host is reachable        -> ask what it can do: GET /health
1. window.openFile(...)       -> call it                       (the host's own webview)
2. a host answers             -> GET /open over HTTP           (any other host, on its port)
3. nothing answers            -> copy "path:line" and say so   (browser, CI artifact, PDF export)
```

Rung 0 is what makes rung 2 honest: `/health` reports a **capability list** and, in a standalone host's
manifest, how precisely the attached editor can reach a line (`exact`, `file-only`, `none`). A page that
reads it chooses its rung from data instead of from a hard-coded port, which is the difference between "the
host has no editor attached" and "the host is not there". The response document is in
[`contract.md`](contract.md) § 3.2. Rung 3 is not a failure path — **never render a link that does
nothing**, because a visible `path:line` the reader can paste into their editor beats a dead anchor.

A few facts that decide how you wire it:

* **The port is a deployment detail, never a constant.** Put it in configuration (`data-bridge-port`, or a
  CLI flag when your generator writes pages); a page that assumes one fixed port works in one host and
  silently does nothing in another. The port a host is really on is published in the served project's
  `.jcodebuddy/webview/host.json`, and `GET /health` answers without credentials and says whether a host is
  really listening, which editor it is, and which project it serves.
* **The HTTP transport is off until it is configured**, binds to loopback only, and denies every caller
  until one is authorised — by an allowed `Origin`, or by a token. The rules are in
  [`contract.md`](contract.md) § 3.4; [`host-in-this-project.md`](host-in-this-project.md) § 4 has the
  reader's side, including why a **`file://` page sends no usable `Origin`** and so needs the token.
* **An `iframe` needs no CORS grant** and leaves no visible navigation, which is why the fallback uses one
  rather than `fetch` — `fetch` from a `file://` page is blocked by CORS regardless of the server.
* **Navigation is rate limited**, shared by every transport of that host. Do not wire a `mouseover` to it.
* **`404` means the path did not resolve** (or the rate limit refused it), so a broken link is diagnosable
  and never silent. `403` means the caller proved nothing.

### 4.1 The edit rung: propose, show the diff, then write

A page that only navigates needs none of this. A page that *changes* a file uses the write contract in
[`edit-api.md`](edit-api.md), and the order is not a suggestion: **diff first, write when the reader
accepts**.

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

Rules that decide whether such a page works in more than one host:

* **Never compute the digest yourself.** A read answers with the host's own digest header; that is the digest
  it will compare against, and a second implementation of the same hash is a second chance to disagree.
* **`expectedDigest` is the digest of what the page read** — in the proposal and in the accepted write. The
  proposal's own `digest` describes the file it *would* produce: what to render next, not what to send back.
* **A stale digest is a refusal, never a merge.** Re-read, re-render, propose again. That is the whole safety
  argument: the browser cannot clobber a file the reader has not seen.
* **`target` says who writes.** `auto` (the default) uses the editor's buffer when the host declares `edit`,
  and otherwise writes to disk; `buffer` insists on the editor and is refused rather than silently written to
  disk; `disk` insists on the host's own atomic write.
* **Writes need the token**, not merely an allowed `Origin`: a state-changing route is reachable from every
  page the reader has open, and only a page this host served can hold the secret. The change event stream, by
  contrast, only lets a page re-render instead of polling.

Two clients ship with this kit, on purpose: [`nav-client.js`](../examples/with-assets/assets/nav-client.js)
is navigation only — the three-rung ladder, configured from the script tag by `data-link-base`,
`data-bridge-port` and `data-bridge-token` — while
[`webview-client.js`](../examples/with-assets/assets/webview-client.js) adds read, propose, apply, undo and
events, is capability-driven, and works with no DOM above the clipboard rung, which is why a Node test can
drive it. A page that edits, end to end, is
[`../examples/with-assets/pages/edit-demo.html`](../examples/with-assets/pages/edit-demo.html): rename a row,
see the unified diff, accept it, undo it. The write verbs are covered the same way a project should cover
them, in [`../examples/webview-client.test.mjs`](../examples/webview-client.test.mjs).

### 4.2 The link base: never an absolute path in an artifact

The host wants an absolute path; the committed artifact must contain none, because an absolute path is
wrong on every machine but one. The page therefore computes it from **its own location or its script's
location**:

```
target = new URL(linkBase + '/' + relativePath, <the page or the script>).pathname
```

Two spellings, and you must know which one you are using:

| Where `data-link-base` lives | Resolved against | What the value means |
| --- | --- | --- |
| on the **`<script src="…">`** tag (recommended for shape B) | the **script's own URL** | the distance from `assets/` to the project root — **identical on every page**, whatever its depth |
| on **`<body>`** (shape A) | the **page's own URL** | the distance from *this page* to the project root |

When both are present the script tag wins, which is what lets one client file serve a landing page and a
page three folders deep with no per-page bookkeeping. The same tag is written the same way everywhere:

```html
<script id="nav-client" src="assets/nav-client.js"   <!-- from assets/ up to the project root -->
        data-link-base="../../../../.." data-bridge-port="18881"></script>
<body data-link-base="../../../..">                 <!-- shape A: from THIS page to the root -->
```

**Count directories, not files.** A filename is not a level, and counting it is the single most common way
to break every link on a page at once. The kit's own tree makes the arithmetic concrete:

```
<root>/examples/with-assets/assets/nav-client.js
        ^        ^         ^      ^   ^
        |        |         |      |   +-- the file: not a level
        |        |         |      +------ the fifth level, if this copy sits inside a folder of its own
        1        2         3      4       -> "../../../../.."  (shape B, from assets/)

<root>/examples/self-contained/index.html
        ^        ^            ^
        |        |            +----------- the fourth level above the file, again if nested
        1        2            3          -> "../../../.."     (shape A, from the page)
```

Read the numbers as the shape of the arithmetic, not as constants: in this repository the kit sits at
`webview/kit/`, one folder deeper than the tree above assumes, so the two values that actually appear in
the shipped pages are `"../../../../.."` (shape B) and `"../../../.."` (shape A). The same `assets/` folder
produces one value and the same page produces another, and **both are correct** — they are measured from
different anchors (the script in `assets/`, and the page). Neither is a constant to copy: count the levels
in your own tree, then let the verifier in
[§ 9](#8-verifying-before-you-ship) resolve every link exactly as the browser will.

### 4.3 The client surface

[`../examples/with-assets/assets/nav-client.js`](../examples/with-assets/assets/nav-client.js) is a
complete, dependency-free implementation with a small surface, so pages and tests do not re-implement the
ladder:

| Member | Returns |
| --- | --- |
| `window.jcbNav.open(path, line)` | navigates programmatically |
| `window.jcbNav.mode()` | `'injected'` \| `'bridge'` \| `'clipboard'` |
| `window.jcbNav.absoluteTarget(path)` | the absolute target the page will ask for |
| `window.jcbNav.projectRoot()` | the absolute URL the link base resolved to |
| `window.jcbNav.links()` | every `[data-open]` element, resolved — for tests and debugging |
| `window.jcbNav.describe()` | the sentence the status pill shows |
| `window.jcbNav.onStatus(fn)` | called on every mode change; returns an unsubscribe |

It also paints the current mode onto any element marked for it — `<span class="pill" data-bridge-status>`,
or `data-bridge-status="short"` for the mode name alone — so a reader never has to guess why a click did
nothing visible.

---

## 5. Choose a shape

Two shapes, same contract; [`../examples/README.md`](../examples/README.md) holds both. This is the decision:

| | **A. One self-contained file** | **B. Page + `assets/` folder** |
| --- | --- | --- |
| Files | 1 | the assets + one file per page |
| Restyle everything | edit each file | edit `site.css` once |
| Several pages share a client | no sharing | yes — one `<script src>` per page |
| Travels as an attachment / CI artifact | perfect | needs the folder (or a zip) |
| Diff when the client changes | the whole page | just `nav-client.js` |
| A generator regenerates one page | no | yes, without touching the shell or prose |
| Reference | [`self-contained/index.html`](../examples/self-contained/index.html) | [`with-assets/index.html`](../examples/with-assets/index.html) |

**Choose A when a page must travel alone. Choose B when there is more than one page** — which a project
navigator always has — **or when anything is generated.**

"Self-contained" means *no network at view time*: no CDN, no bundler, no `node_modules`. A local
`<script src="assets/nav-client.js">` is not a network reference and is allowed in both shapes; what is
forbidden is an `https://` asset, because an embedded webview may open the page with no server and no
guaranteed network behind it. Shape B wires four tags per page — `site.css`, `syntax-theme.css`,
`nav-client.js` with its two attributes, and (when the page highlights code) `microlighter.js` before
`highlight-runner.js`.

---

## 6. The page: a working skeleton

The smallest page that navigates a project. Copy it, then change `data-link-base` for your tree and
`data-bridge-port` for your host.

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
     data-bridge-port: the host's loopback port, 0 to disable the HTTP fallback.
     data-bridge-token: only when the host requires a token. -->
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

  // A project-relative path -> the absolute path the host wants. Never a baked-in constant.
  function absoluteTarget(relative) {
    if (!relative) { return ''; }
    if (/^[A-Za-z]:[\\/]/.test(relative) || relative.charAt(0) === '/' || relative.indexOf('://') > 0) {
      return relative;                                    // already absolute or a URL
    }
    try {
      var base = LINK_BASE ? LINK_BASE.replace(/\/+$/, '') + '/' : '';
      var path = decodeURIComponent(new URL(base + relative, document.baseURI).pathname);
      return path.replace(/^\/([A-Za-z]:)/, '$1');        // strip the leading slash off /C:/...
    } catch (error) { return relative; }
  }

  function open(link) {
    var target = absoluteTarget(link.getAttribute('data-open'));
    var line = parseInt(link.getAttribute('data-line') || '1', 10);
    var member = link.getAttribute('data-member') || '';

    if (typeof window.openFile === 'function') {          // 1. the host's webview injected this
      window.openFile(target, line, 1);
      say('Opened ' + (member || target) + ' at line ' + line + '.');
      return;
    }
    if (BRIDGE_PORT > 0) {                                // 2. the loopback HTTP transport
      var frame = document.createElement('iframe');       // no CORS grant needed, no visible navigation
      frame.id = 'bridge-frame';
      frame.style.display = 'none';
      frame.setAttribute('aria-hidden', 'true');
      frame.src = 'http://127.0.0.1:' + BRIDGE_PORT + '/open?filePath=' + encodeURIComponent(target)
        + '&line=' + line + '&column=1' + (TOKEN ? '&token=' + encodeURIComponent(TOKEN) : '');
      document.body.appendChild(frame);
      say('Sent line ' + line + ' to the host on port ' + BRIDGE_PORT + '.');
      return;
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {   // 3. the honest fallback
      navigator.clipboard.writeText(target + ':' + line);
    }
    say('No host here. Location: ' + target + ':' + line);
  }

  // ONE delegated handler for the whole page: generated blocks need no wiring.
  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('[data-open]') : null;
    if (!link) { return; }
    event.preventDefault();
    open(link);
  });

  // Say which mode the page is in, so a click that does nothing is never a mystery.
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
2. **Feature-detect before calling.** See [§ 2.1](#21-feature-detect-always).
3. **Compute the absolute path from the link base**, not from a constant. See [§ 4.2](#42-the-link-base-never-an-absolute-path-in-an-artifact).
4. **Always end somewhere the reader can see** — a toast, a status line, a visible path.

### 6.1 Table rows, cards and other containers

`data-open` may go on **any** element; the host reads the attributes from the element, so the page keeps
full control of its markup. Prefer the leaf that names the thing (`<td>`, `<span>`, `<a>`) over the whole
row: a reader clicking whitespace expects nothing to happen.

Use a real `<a href="…">` when the target is *also* browsable on disk — the `href` gives middle-click,
hover-preview and "copy link address" in a browser, while the click handler intercepts the left click. Put
the *page-relative* path in `href` so it works with JavaScript disabled.

### 6.2 When a location has no file: anchors and in-page links

The rules above are about *locations*. Plain in-page navigation (`#section`, another page in the same
folder) needs none of it — use an ordinary `<a href>` and no `data-open`. Mixing the two is fine and is what
the examples do: a sidebar carries generated location links and ordinary page links side by side. A link to
the project's own web page is navigation too, and no verifier should demand that it exist on disk.

---

## 7. Syntax highlighting, offline

[**microlighter**](https://github.com/davatron5000/microlighter) (MIT, by Dave Rupert) is the highlighter
this kit uses: ~2 KiB, no runtime dependencies, TextMate grammars read with the browser's native `RegExp`,
and **never a token wrapped in a `<span>`** — it registers `Range` objects on `CSS.highlights` and lets
`::highlight(category)` style them. The DOM therefore stays plain text: greppable, diffable, copy-pasteable,
editable.

### 7.1 Two ways to get it into a page

| | **Inlined** (shape A) | **As an asset** (shape B) |
| --- | --- | --- |
| File | one `<script>` block in the page | `assets/microlighter.js` |
| Works from | anywhere, alone | the folder |
| Reference | [`../examples/self-contained/index.html`](../examples/self-contained/index.html) | [`../examples/with-assets/assets/microlighter.js`](../examples/with-assets/assets/microlighter.js) |

Upstream ships ES modules (`import { highlightAll } from 'microlighter'`) and loads each grammar with
`await import('./grammars/x.js')`. **A webview page may have no module loader and no bundler**, so both
examples use the same adaptation — four changes, listed in the vendor file's header:

1. the inlined grammar registry replaces the dynamic `import()`;
2. `import`/`export` became one IIFE on `window.microlighter`, and `highlightAll()` stays asynchronous past
   a satisfied registry;
3. capability guards for `CSS.highlights` / `Highlight` and for unresolved TextMate includes;
4. `Highlight.add()` is fed ranges from a single text node (which the scanner already guarantees).

Everything else — scope flattening, capture handling, begin/end rule recursion, the memoised `dgm` regex
cache — is upstream's algorithm, unchanged. **If you vendor it into your own project, repeat that list in
the file header**, so the next reader knows what to compare against upstream. The pinned version here is
`2.2.0`: to move up, take upstream's `highlight.js` and the grammars you need, re-apply the four changes,
re-run your checks, and diff the grammar files — a grammar change is a visual change.

### 7.2 Five languages, seven grammars

| Language | Class | Grammar | Pulls in |
| --- | --- | --- | --- |
| JavaScript | `language-javascript` | `source.js` | — |
| Java | `language-java` | `source.java` | — |
| JSON | `language-json` | `source.json` | — |
| HTML | `language-html` | `text.html.basic` | `source.css`, `source.json`, `source.js` — for what is inside `<style>`/`<script>` |
| Markdown | `language-markdown` | `text.html.markdown` | `source.yaml` — for front matter |

That dependency column is why seven grammars are bundled for five languages: the HTML grammar includes
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
  half-highlighted, which is the right failure but an easy one to miss. Language detection, in priority
  order: a `language-*` class on `<code>`, `data-language` on `<code>`, the same two on `<pre>`, then the
  deprecated `lang` on `<pre>`. Aliases (`js`, `ts`, `md`, `yml`, `sh`, …) work automatically; extra ones can
  be passed as `languageAliases`.
* **The host needs the CSS Custom Highlight API.** Every modern Chromium has it, and so do the embedded
  webviews a host ships. Without it the page still reads perfectly (code is plain text); say so rather than
  appearing broken.

### 7.3 The theme is only CSS variables

The mechanism and the theme are separate files, deliberately. **`syntax-theme.css`** holds the
`::highlight(category)` rules, which **do not change between themes** — they are the rendering half, and
copying them wrong is the usual reason "highlighting does nothing". A **theme** is a block of `--syntax-*`
custom properties, selected by an attribute:

```css
[data-syntax-theme] {
  --syntax-comment: #6f9fa2;  --syntax-keyword: #ff8a7a;  --syntax-string:  #8ee6a0;
  --syntax-constant: #f0c674; --syntax-function: #7fd6ff; --syntax-type:    #2ee6d0;
  /* … */
}
[data-syntax-theme="solarized"] { --syntax-comment: #586e75; /* … */ }
```

```html
<body data-syntax-theme="default">
```

Both live in [`../examples/with-assets/assets/syntax-theme.css`](../examples/with-assets/assets/syntax-theme.css),
with a second theme beside the first so the mechanism is visible. Upstream's `themes/*.css` files (github,
dracula, monokai, night-owl, tokyo-night, solarized-light, …) use `light-dark(light, dark)` pairs and follow
the OS scheme: prefer them verbatim if your page does, and otherwise keep the same rule list and retune the
custom properties, which is what the kit's example does for its dark shell. The categories a grammar can
produce are ~20 names:
`comment quote keyword storage at-rule doctype important section operator punctuation string regexp
attribute-value link raw numeric boolean constant symbol character-entity anchor entity function decorator
animation type support variable interpolation property key attribute-name tag selector inserted deleted`.
`window.microlighter.getCategory(scope)` flattens a TextMate scope to one of them; use that function when
you add rules, instead of guessing from the scope name.

### 7.4 Adding a language

1. Copy the grammar from upstream `src/grammars/<language>.js` and append it to the registry array.
2. Add any grammar it `include`s by scope (`source.css` → the `css` grammar) — check its `dependencies`
   field and any bare `source.*` includes in its rules.
3. Use `class="language-<name>"` in the page. Nothing else registers.

### 7.5 Re-highlighting after the page changes

microlighter's documented convention is a bubbling event:

```js
document.dispatchEvent(new Event('syntax-highlight'));   // after injecting or editing code
```

[`../examples/with-assets/assets/highlight-runner.js`](../examples/with-assets/assets/highlight-runner.js)
listens for it and reports the outcome into `[data-syntax-status]`, so a filter that swaps a section, or an
editable block, needs one line. Keep the vendor script *before* the runner: the runner calls it.

---

## 8. Verifying before you ship

A page is a build output, so test it like one. The kit ships both halves of that test, and the first one
needs no browser at all:

```console
# the five checks below except the highlighting one, over every page of a site
node scripts/check-pages.mjs --site examples/with-assets --root ../../..
# and every page in a real Chromium: scripts parse, highlighting runs, links resolve
node examples/smoke-test.mjs
```

`--root` is the project root the pages link into — the same directory `data-link-base` is measured from.
In the kit as it ships that is three levels above `examples/with-assets`, so `--root ../../..`; in your
project it is your root, and passing the wrong one is what makes every link look broken at once.

[`../scripts/check-pages.mjs`](../scripts/check-pages.mjs) is the one to **keep in your build**: it is a
plain script, it takes the two directories it needs as arguments, and it exits non-zero on the first
problem. [`../examples/smoke-test.mjs`](../examples/smoke-test.mjs) drives a real Chromium over the DevTools
Protocol with no dependencies, and it **skips the browser half** when no Chromium is installed rather than
passing quietly.

| Check | How | Why it is not optional |
| --- | --- | --- |
| **Every script parses** | `node --check` on each inline `<script>` and each `.js` asset; every referenced local asset exists | one typo in a fallback path is invisible until the host is missing |
| **The page opens offline and highlights** | load it in a real Chromium over `file://`; read back the block and category counts | proves the grammar registry and the `::highlight()` rules line up |
| **Every `data-open` resolves** | resolve each target through **the page's own link base** | this is "resolve or leave alone", enforced |
| **The claimed line exists and holds the member** | read the target file; the line must be inside it, `data-member` must appear **on that line**, and the line must not be a Markdown heading | a line number that is off by one looks exactly like a working link, and a link that lands on a section title is false the moment anything is inserted above the member |
| **Every ordinary page link resolves** | resolve each local `href` against the page | the sidebar and breadcrumbs are navigation too |
| **Nothing is loaded off-box** | no `https://` asset, no CSS `@import`, no absolute project path in the source | an embedded webview has no server and no guaranteed network |

The third and fourth checks are one rule seen from two sides, and the fourth is the one that catches a
generator's drift: point a link at the **member** it names, never at the section that contains it. A page
that wants to send a reader to a section links without `data-member` and says so in its label — that is
honest, and the checker leaves it alone. What it rejects is a `data-member` claim the line does not support,
because that is a link a reader cannot tell is wrong until they arrive.

Two details worth copying from that test:

* **Strip `<pre>` blocks before scanning for links**, and HTML comments with them: code samples contain
  escaped markup (`&lt;div class="x"&gt;`), a page may document itself in a comment, and a naive scan finds
  `data-open`-shaped text in either. Samples are demo content, never wiring.
* **Let the page publish its own state.** `highlight-runner.js` writes `data-syntax-ready` on `<body>` as
  `"<blocks>/<categories>"` (or `"unsupported"`, `"missing"`, `"error"` when it could not run), so a test
  asserts *highlighting ran* rather than merely that the HTML parsed. A page that reports its own state is
  far easier to test than one that has to be poked.

Keep check three — the link verifier — in your build, not only in a test somebody remembers to run: a page
that ships links which look right and go nowhere is the one failure the contract cannot detect at view time,
because the page cannot learn whether the editor found the file. `check-pages.mjs` is that check, and it is
the reason a generator can fail its run on the first unverifiable candidate instead of dropping it silently.

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Every click after the first does nothing | an unguarded `window.openFile` call threw a `TypeError` in the handler | feature-detect before calling ([§ 2.1](#21-feature-detect-always)) |
| A click does nothing, ever | the element has no `data-open`, or the handler is bound to the wrong root | put `data-open` on the element; delegate from `document` |
| The editor opens the wrong file | the link base is wrong, or the page used an absolute path that only exists on one machine | count the levels ([§ 4.2](#42-the-link-base-never-an-absolute-path-in-an-artifact)); never commit an absolute path |
| The editor opens the file, caret on line 1 | `data-line` missing, or the value is not a number | 1-based `data-line`; the client falls back to `1` |
| `403` from `/open` | the caller proved nothing — a `file://` page sends no usable `Origin` | configure a token and pass `?token=…`, or serve the page through the host ([`host-in-this-project.md`](host-in-this-project.md) § 4) |
| `404` from `/open` | the path did not resolve against the project, or the rate limit refused it | check the path; read the host's log |
| Works in one host, silently does nothing in another | the page assumed a fixed port | put the port in configuration; read it from `/health` |
| No highlighting, or it is invisible while the status pill says it ran | `CSS.highlights`/`Highlight` missing, the vendor script loaded *after* the runner, or the `::highlight()` rules are missing | load the vendor first, report the API status, and take the rule list from `syntax-theme.css` verbatim ([§ 7.3](#73-the-theme-is-only-css-variables)) |
| One language highlights only partially | a grammar dependency is missing (HTML → css/json/js, Markdown → yaml) | bundle the dependency grammar ([§ 7.2](#72-five-languages-seven-grammars)) |
| A code block is not highlighted while others are | it has an element child, or a whitespace sibling — microlighter needs **one text node** | escape `<`/`>`/`&`; no nested elements |
| The page is blank in the host but fine in a browser | a remote asset, or a module `<script type="module">` the webview never fetched | no CDN, no modules at view time ([§ 5](#5-choose-a-shape)) |

For a problem that looks like the environment rather than the page — a click that always reaches the
clipboard rung, a `403`, a page never loaded *through* the host —
[`host-in-this-project.md`](host-in-this-project.md) § 5 has the matching table. Turning on the embedded
browser's DevTools is worth the two minutes: the console, the DOM and `CSS.highlights` all become
observable.

---

## 10. Checklist

- [ ] `window.openFile` is **feature-detected** at every call site.
- [ ] One **delegated** click handler on `document` reads `[data-open]`.
- [ ] `data-open` targets are **project-relative**; the absolute path is computed from the link base.
- [ ] `data-link-base` is correct **for its own spelling** — script-relative or page-relative ([§ 4.2](#42-the-link-base-never-an-absolute-path-in-an-artifact)).
- [ ] `data-bridge-port` is configured (or `0`), never assumed; `GET /health` verified against the host.
- [ ] Every link has a 1-based `data-line`, and `data-member` only where the **member really is on that
      line** — never on a section heading.
- [ ] Everything unresolvable is **plain text**, not a link.
- [ ] The page ends every click somewhere visible: the editor, the HTTP transport, or a copied `path:line`.
- [ ] No CDN, no `node_modules`, no `https://` asset, no absolute path in the artifact.
- [ ] The mode and the highlight status are shown on the page (`data-bridge-status`, `data-syntax-status`),
      and published for a test (`data-syntax-ready`).
- [ ] Code blocks are `<pre><code class="language-x">` with **one text node**; `<`/`>`/`&` escaped.
- [ ] Both checks pass: `check-pages.mjs` over the site, and the smoke test in a real Chromium.
- [ ] If anything is generated: the generator owns whole files, verifies every link, and reports what it
      dropped.

---

## 11. Where to read more

| Document | What it answers |
| --- | --- |
| [`contract.md`](contract.md) | the frozen contract: `window.openFile`, the attributes, `/open`, `/health`, the ladder, the security model, what may change |
| [`edit-api.md`](edit-api.md) | how a page proposes, shows and applies a change, and undoes it |
| [`host-in-this-project.md`](host-in-this-project.md) | how the host in *your* project is found and authorised, and what to check when it is not |
| [`../examples/README.md`](../examples/README.md) | the two runnable examples, and how to check them |
| [`../scripts/check-pages.mjs`](../scripts/check-pages.mjs) | the five checks, as a script to keep in your build |
| [`../examples/smoke-test.mjs`](../examples/smoke-test.mjs) | the same pages in a real Chromium: highlighting included |
| [`../examples/webview-client.test.mjs`](../examples/webview-client.test.mjs) | the write verbs, driven against a live headless host |
| [`../examples/with-assets/pages/edit-demo.html`](../examples/with-assets/pages/edit-demo.html) | an editing page, end to end |
| [`../examples/with-assets/pages/entity-reference.html`](../examples/with-assets/pages/entity-reference.html) | a page written as if a generator owned it |
| [microlighter](https://github.com/davatron5000/microlighter) | the highlighter: grammars, themes, `<micro-lighter>`, editable code |

**Provenance.** `microlighter` is MIT, © Dave Rupert, with TextMate grammars adapted from Microsoft VS Code
(MIT). The vendored copy in this kit is version `2.2.0` with the four documented adaptations listed in
[§ 7.1](#71-two-ways-to-get-it-into-a-page) and repeated in the vendor file's header.
