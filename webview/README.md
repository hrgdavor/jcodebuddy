# webview — the project-navigation webviews

Everything about showing a clickable, IDE-driven page of a project: the two host plugins, the page-authoring
contract, and runnable examples of a project navigator.

```
webview/
  README.md                      <- this file: what the folder holds
  check-links.mjs                verifies every relative link in this folder
  doc/
    webview-link-api.md          the FROZEN contract: openFile, data-*, /open, /health, security
    webview-page-authoring.md    how to build a page: shapes, the client ladder, highlighting, tests
  webview-jetbrains/             JetBrains IntelliJ Platform plugin, JCEF tool window ("WebView Explorer")
  webview-vscode/                the VS Code port, same API, different port
  examples/
    README.md                    the two examples and what the smoke test checks
    check-docs.mjs               verifies the links in this folder's documents
    smoke-test.mjs               verifies every page: scripts parse, highlight runs, all links resolve
    self-contained/index.html    EXAMPLE A — one file, microlighter + 7 grammars inlined
    with-assets/                 EXAMPLE B — index.html + pages/ + assets/
      index.html                   manual landing page
      pages/guide.html             manual deep-dive
      pages/entity-reference.html  a page written as if a generator owned it
      assets/                      site.css, syntax-theme.css, nav-client.js, highlight-runner.js, microlighter.js
```

---

## The idea in one paragraph

An HTML page can navigate a project. Put `data-open="src/main/java/A.java"` and `data-line="14"` on any
element, and when that page is loaded in one of these plugins, a click opens the file in the editor with
the caret on line 14. That is the whole feature — no framework, no build step, no network. It is enough to
render an entity reference, a coverage table, a rendered Markdown document, or a review dashboard as a map
of the code, and it works the same in JetBrains and VS Code because the contract is one function and two
attributes.

## Which file to read

| You want to… | Read |
| --- | --- |
| use the plugin | [`webview-jetbrains/README.md`](webview-jetbrains/README.md) — `Ctrl+Alt+Shift+W`, right-click an `.html` file → **Open in WebView Explorer** |
| use it in VS Code | [`webview-vscode/README.md`](webview-vscode/README.md) |
| write a page that navigates code | [`doc/webview-page-authoring.md`](doc/webview-page-authoring.md) |
| get the exact host contract | [`doc/webview-link-api.md`](doc/webview-link-api.md) |
| copy a working page | [`examples/`](examples/README.md) — start with [`self-contained/index.html`](examples/self-contained/index.html) |
| see what a generator produces | [`examples/with-assets/pages/entity-reference.html`](examples/with-assets/pages/entity-reference.html) |
| verify your own pages | [`examples/smoke-test.mjs`](examples/smoke-test.mjs) — `node webview/examples/smoke-test.mjs` |

## Two page shapes, one contract

| | `examples/self-contained/` | `examples/with-assets/` |
| --- | --- | --- |
| Files | 1 | 4 assets + one file per page |
| Travels alone (attachment, CI artifact) | yes | needs the folder |
| Several pages share a shell and client | no | yes |
| A generator regenerates one page without touching the rest | no | yes |

Both are offline: no CDN, no bundler, no `node_modules` at view time. Both keep absolute paths out of the
artifact. Both highlight JavaScript, Java, JSON, HTML and Markdown with
[microlighter](https://github.com/davatron5000/microlighter) — inlined in the self-contained page, shared as
an asset in the other.

## Where the port numbers come from

The injected `window.openFile` needs no configuration, so a page inside either plugin's webview just works.
The loopback HTTP fallback — for when the same page is open in an ordinary browser — is per host and off
until configured:

| Host | Default port | Setting |
| --- | --- | --- |
| `webview-jetbrains` | 18881 | `webview.explorer.port` |
| `webview-vscode` | 18882 | `webviewExplorer.port` |

Never assume one: put it in `data-bridge-port`, and use `GET /health` to find out whether a bridge is
actually listening. Authorisation (an allowed `Origin`, or a token) is covered in
[`doc/webview-link-api.md`](doc/webview-link-api.md) § 3.

## Verifying

```bash
node webview/check-links.mjs             # every relative link in this folder resolves
node webview/examples/smoke-test.mjs     # 4 pages: scripts parse, highlight runs, every link resolves
```

`smoke-test.mjs` drives a real Chromium over `file://` via the DevTools Protocol — no dependencies, and it
skips the browser half if no Chromium is installed. Current result: **49 assertions, 47 location links and
46 page links verified, all green.** It also reports the highlighted block and token-category counts per
page, so a grammar that silently stops loading fails the run rather than looking fine.

`node scripts/check-repo-links.mjs` from the repository root additionally checks every relative link in
every Markdown file of the repository, which is how the links *out* of this folder (into `scripts/`,
`doc-hipster-entity/`, …) are covered.

## Licence and provenance

`examples/*/assets/microlighter.js` and its inlined copy are
[microlighter](https://github.com/davatron5000/microlighter) 2.2.0, MIT, © Dave Rupert, with TextMate
grammars adapted from Microsoft VS Code (MIT). Four documented changes adapt it to a page with no module
loader; the list is in the file header and in
[`doc/webview-page-authoring.md`](doc/webview-page-authoring.md) § 6.1.
