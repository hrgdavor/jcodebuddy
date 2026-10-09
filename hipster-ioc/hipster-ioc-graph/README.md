# `hipster-ioc-graph` — the dependency-graph page

A **`jsx6` application** that shows a project's hipster-ioc contexts as a graph, rendered by Bun from
the generator's `contexts.json`. It is plan step 3.8, and it is the page DEC-027's 2026-10-01
amendment was written for: *"navigation across a project's structure"* and *"relations as a picture"*
are the two things that make a page an application rather than a document.

It lives **here**, not under `scripts/`, because `scripts/` stays dependency-free for the vanilla
renderers (DEC-027's 2026-10-02 note). A `jsx6` page is built, so it gets a home that may declare a
dependency.

## What it does

```
bun scripts/ioc-gen.js                        # the generator writes .jcodebuddy/metadata/hipster-ioc/contexts.json
bun run hipster-ioc/hipster-ioc-graph/src/build.js
# → hipster-ioc/hipster-ioc-test/.jcodebuddy/metadata/hipster-ioc/graph.html
```

Open `graph.html` — **double-click it or `file://` it; no server, no host, nothing to start.** It is
one self-contained file: the script and both stylesheets are inlined, and the graph JSON is inlined
too, so nothing is fetched at view time (DEC-027's "no network" rule, and the maintainer's "a UI must
be usable standalone even without webview").

| file                | what it is                                                                                          |
| ------------------- | --------------------------------------------------------------------------------------------------- |
| `src/model.js`      | the projection of the generator's JSON: contexts, beans, and the lines between them. A dependency naming a context that is not in the file is **reported, never drawn** |
| `src/blocks.js`     | the graph blocks — plain DOM, because nodditor's contract is about *elements* (`ncid` + `ne-connect` + `ne-drag`), not components |
| `src/host.jsx`      | the jsx6 markup: the navigation sidebar and the page shell                                          |
| `src/main.js`       | the entry: read the graph, render the shell, drive the editor                                       |
| `src/page.css`      | this page's own look; nodditor's geometry comes from its shipped `nodditor.css`, inlined ahead of it |
| `src/build.js`      | the build: stage, bundle, inline, write one HTML file. `--check` fails when the built page is stale |
| `src/graph.test.js` | `bun run src/graph.test.js` — the model, the document, the standalone properties, and (when Chrome is present) that the page **renders** |

## The build, and why it stages inside the checkout

`@jsx6/*` and `@jsx6/nodditor` resolve through the jsx6 workspace, so the bundle entry has to be built
from **inside** the checkout — measured: the same import from this repository's tree fails with
`Could not resolve "@jsx6/nodditor"`. The build copies its sources into the checkout's `.tmp/`
(ignored by both repositories), bundles them there with the jsx6 automatic runtime, and inlines the
result. The checkout is `JCODEBUDDY_JSX6_DIR` or, by default, the `.jsx6/` that `AGENTS.md` § 2 names.

Two flags are load-bearing and both were learned by measuring:

- **`--jsx-runtime automatic --jsx-import-source=@jsx6`** — the stack's own rule 2. Without it the
  bundle resolves `react/jsx-dev-runtime` and fails.
- **`--format iife`** — `file://` refuses `<script type="module">` (*"Access to script … has been
  blocked by CORS policy"*), which would leave the page on its loading line for anyone who opens it by
  double-clicking. A classic inline script has no such rule.

## The editor: the `jsx6` fix it needed, and what it means for a host

The page renders — its sidebar, its contexts as nodditor blocks with their beans and connectors, the zoom
controls and its status line. Getting there required one fix **in the library**, which is where
`AGENTS.md` § 2 and DEC-027 say a capability gap belongs.

`JsxW` built its template in the constructor, and a custom element's constructor **must not set attributes
or add children** — the parser and `document.createElement` both refuse the upgrade when it does
(`The result must not have attributes` / `must not have children`). Measured in headless Chrome, three
shapes:

| how the element is created                           | what the browser reported                                                                   |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `new NodeEditor(...)` (the JSX runtime's class path) | `TypeError: Failed to construct 'HTMLElement': Illegal constructor`                         |
| `<jsx6-nodditor …/>` in JSX, after `define`          | `NotSupportedError: Failed to execute 'createElement': The result must not have attributes` |
| `document.createElement('jsx6-nodditor')`            | the same `NotSupportedError`, raised from inside the class's own `tpl`                      |

nodditor's own demo only avoided it because its element is **parsed from HTML before the class is
registered** — an upgrade path a host that loads the package cannot rely on, and one that would break the
render → inspect → wire order its host guide requires.

**The fix, in `.jsx6/libs/w/src/JsxW.js`.** The checkout is not versioned in this repository, so the
change is recorded here: the constructor now stores its arguments and builds only when the element is
**already connected** — true for the JSX runtime's `createElement(tag, options)` path, where building is
legal — and otherwise builds in `connectedCallback`. Verified against **jsx6's own gate** (`bun run test`
in the checkout: all checks passed) and against a real render of this page.

Two consequences for a host, both in `src/main.js`:

- the editor element is created **detached and attribute-free** and only then inserted, because
  `createElement` with `className` already set makes the browser raise the upgrade error and leave the
  element un-upgraded (`loadGraph` undefined). Setting the class after insertion is the legal order;
- the reference is held by the caller, which is what the render → inspect → wire order needs anyway.

**Status: finished.** `src/graph.test.js` covers the model, the document and the standalone properties,
and — when a Chrome is present — asserts that the built page actually renders its shell, its block and
its status line, so a page that regresses to a blank shell fails a test rather than being noticed by eye.
