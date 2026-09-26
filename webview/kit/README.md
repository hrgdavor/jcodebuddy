# webview kit — build HTML pages that drive your editor

This folder is the **copy-pasteable** part of `webview/`. Everything here is written for a project that
**consumes** a webview host — an IDE plugin, a standalone server, or another process that speaks the
same contract — and is not about how that host is built.

A page needs one function, two attributes and no build step. Put `data-open="src/main/java/A.java"` and
`data-line="14"` on an element, and a click opens that file in the editor with the caret on line 14. Ask
the host over HTTP and a change lands in the editor's buffer, unsaved, in the editor's own undo stack.
That is the whole feature.

> **The premise of every document here: the host is already there.** Making webview *available* in your
> project — installing the plugin, starting a host, authorising a token — is a one-time setup step
> done by a person, not by a page. If it is done, skip
> [`doc/host-in-this-project.md`](doc/host-in-this-project.md) and start at
> [`doc/page-authoring.md`](doc/page-authoring.md). If it is not done yet, read that file first; it is
> short, and it is written for a developer, not for a host author.

## What this kit gives you

A page reaches the editor through **two attributes and one function**, and the API surface is frozen:

```js
window.openFile(filePath, line, column);   // line and column are 1-based; defaults are 1
```

| Document | What it answers | Read it when |
| --- | --- | --- |
| [`doc/contract.md`](doc/contract.md) | the **frozen** API: `data-open` / `data-line` / `data-member` / `data-role`, `window.openFile`, `GET /open`, `GET /health`, the fallback ladder, what may change and what may not | you need the exact behaviour of a call, or you are writing anything that emits links |
| [`doc/page-authoring.md`](doc/page-authoring.md) | how to **build** a page: the two shapes, the skeleton, the link base, offline syntax highlighting, the verifier, troubleshooting | you are starting a page, or a generator was written and its output looks wrong in the IDE |
| [`doc/edit-api.md`](doc/edit-api.md) | how a page **changes** a file: propose, show the diff, apply, undo, redo, and the file-change event stream | a page is allowed to write, not only to navigate |
| [`doc/host-in-this-project.md`](doc/host-in-this-project.md) | how the host in *your* project is found and authorised: the port record, `GET /health`, tokens vs origins, the security rules your page lives under | clicks do nothing, or `403`/`404` shows up, or you are setting the environment up |

## What this kit ships

```
kit/
  README.md                      <- you are here
  doc/
    contract.md                  the FROZEN navigation contract, and the versioning rules
    page-authoring.md            how to build a page: shapes, skeleton, client, highlighting, verification
    edit-api.md                  the write contract: propose, apply, undo, redo, events
    host-in-this-project.md      prerequisites, discovery, authorisation, the security model
  examples/
    README.md                    what the two runnable examples are, and how to open them
    self-contained/index.html    EXAMPLE A — one file; travels alone (attachment, CI artifact, zip)
    with-assets/                 EXAMPLE B — one shell, one client, one file per page (what a generated site wants)
      index.html                   manual landing page
      pages/guide.html             manual deep-dive
      pages/entity-reference.html  a page written as if a generator owned it
      pages/edit-demo.html         an EDITING page: rename a row, see the diff, apply it, undo it
      assets/nav-client.js         the navigation client: the three-rung ladder
      assets/webview-client.js     the full client: capability discovery, navigation, edits, events
      assets/highlight-runner.js   calls microlighter and reports what it did
      assets/microlighter.js       vendor: microlighter 2.2.0 + 7 TextMate grammars (MIT)
      assets/site.css              shell styling
      assets/syntax-theme.css      the ::highlight() rules and the themes
    smoke-test.mjs               every page in a real Chromium: scripts parse, highlight runs, links resolve
    webview-client.test.mjs      the write verbs, driven against a live headless host
  scripts/
    check-pages.mjs              the verifier to keep: every link resolves before it is committed
    check-docs.mjs               every relative link in these documents resolves
```

**Assets are here, not somewhere else.** `nav-client.js`, `webview-client.js`, `highlight-runner.js`,
`microlighter.js` and `syntax-theme.css` are the kit's own files and are the ones the examples load. A
project that copies this folder gets working pages and the client that drives them; nothing in the kit
points back at a path outside it.

## Copying it into your project

Copy the folder, keep the layout, then change **two** values — the two that depend on your tree:

```console
# from your project root
cp -r path/to/jcodebuddy/webview/kit tools/webview-kit
```

1. **`data-link-base`**, in the examples and in your own pages, must state the distance from the page (or
   from `assets/`, when it sits on the `<script>` tag) to **your** project root. Count directories, not
   files, then let `scripts/check-pages.mjs --root <your root>` confirm it — it resolves every link exactly
   as a browser will. [`doc/page-authoring.md`](doc/page-authoring.md) § "The link base" has the arithmetic,
   and notes why the shipped examples say `../../../../..` rather than a rounder number.
2. **`data-bridge-port`**, or `0` to disable the HTTP fallback. Never assume a port: it is a deployment
   detail, and the port a host is really on is published in your project's
   `.jcodebuddy/webview/host.json`. [`doc/host-in-this-project.md`](doc/host-in-this-project.md) § 2.

Then replace the demo prose and tables in `examples/with-assets/pages/` with your own content, or point
your generator at that folder, and keep the four wiring tags:

```html
<link rel="stylesheet" href="assets/site.css">
<link rel="stylesheet" href="assets/syntax-theme.css">

<script id="nav-client" src="assets/nav-client.js"
        data-link-base="../../.." data-bridge-port="18881"></script>
<script id="microlighter" src="assets/microlighter.js"></script>
<script id="highlight-runner" src="assets/highlight-runner.js"></script>
```

**What is deliberately not here:** the host implementations, the conformance vectors, the port-claim rule,
and the observation records behind every "observed in that IDE" claim. Those are the host author's
documents and live beside the hosts; re-implementing a host means reading the *contract* in
[`doc/contract.md`](doc/contract.md) and speaking it, and nothing in this kit needs the rest.

## Verifying a page before you ship it

A page is a build output, so test it like one. Two checks matter, and both are cheap:

```console
# every data-open resolves to a file that exists, every claimed member is on the claimed line,
# every local href exists, and no absolute path or remote asset is baked into the artifact.
# --root is your project root: the directory every link base is measured from.
node scripts/check-pages.mjs --site examples/with-assets --root ../../..

# every relative link in these documents still resolves
node scripts/check-docs.mjs
```

In the kit as it ships, the project root is three levels above `examples/with-assets`, hence `--root
../../..`; in your project it is whatever you wrote into `data-link-base`, and after you change that value
this command is what tells you whether you counted the levels right. Point `--site` at the folder your
pages land in and the whole site is checked at once.

`smoke-test.mjs` is the third check, and the only one that needs a browser: it drives a real Chromium over
the DevTools Protocol with no dependencies and no download, checks that every script parses and that
highlighting actually registered ranges, and **skips the browser half** when no Chromium is installed
rather than passing quietly.

```console
node examples/smoke-test.mjs          # skips the browser half if Chromium is absent
bun examples/webview-client.test.mjs  # the write verbs, against a live headless host
```

Keep `check-pages.mjs` in your build. A generated page that ships links which look right and go nowhere is
the one failure this contract cannot detect at view time — the page cannot learn whether the IDE found the
file, by design — so it has to be caught before the artifact is written.

## Licence and provenance

`examples/with-assets/assets/microlighter.js` and the copy inlined in
`examples/self-contained/index.html` are [microlighter](https://github.com/davatron5000/microlighter)
2.2.0, MIT, © Dave Rupert, with TextMate grammars adapted from Microsoft VS Code (MIT). Four documented
changes adapt it to a page with no module loader; the list is in the vendor file's header and in
[`doc/page-authoring.md`](doc/page-authoring.md).
