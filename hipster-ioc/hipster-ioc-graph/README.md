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
| `src/graph.test.js` | `bun run src/graph.test.js` — the model, the document, and the standalone properties                |

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

## The editor: what works, and the one thing that does not

The page's **navigation layer is `jsx6`** and it works: the shell, the sidebar, the per-context
buttons and the status line are jsx6 function components, and the status is a signal bound as a child,
so updating it rewrites one text node. `graph.test.js` covers the model and the document.

**The diagram layer does not render today, and it is a library-side limitation rather than a page
bug.** `nodditor`'s `NodeEditor` is a custom element whose constructor builds its own DOM, and the
custom-element contract forbids that: an element created by the parser or by `document.createElement`
must not set attributes or add children while it is being constructed. Measured in headless Chrome, on
this page, three ways:

| how the element is created                           | what the browser reports                                                                    |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `new NodeEditor(...)` (the JSX runtime's class path) | `TypeError: Failed to construct 'HTMLElement': Illegal constructor`                         |
| `<jsx6-nodditor …/>` in JSX, after `define`          | `NotSupportedError: Failed to execute 'createElement': The result must not have attributes` |
| `document.createElement('jsx6-nodditor')`            | the same `NotSupportedError`, raised from the class's own `createElement` call              |

The third line is the diagnostic one, and it is why this is not a page-side fix: creating the element
with **no** arguments still fails, inside `NodeEditor`'s own construction. nodditor's own demo avoids
it only because its `<jsx6-nodditor>` is **parsed from HTML before the class is registered**, so the
element is upgraded from an already-created element — an upgrade path a host that creates the editor
after loading cannot rely on, and one that would break the render-inspect-wire order its host guide
requires.

**This is reported rather than worked around**, which is DEC-027 § "Missing capabilities are reported"
and `AGENTS.md` § 2's jsx6 rule: the finding belongs in `nodditor` (a constructor that defers its DOM
build to `connectedCallback`, or documents the upgrade-only path), and the workaround a page could
reach for — a `document.createElement` before the package loads, or a fake element — would be exactly
the silent workaround both rules forbid. `src/main.js` still wires the graph through the documented API
(`typeMap`, `loadGraph`, then `loadLines`), so the page is complete against that API and starts
rendering as soon as the editor can be constructed.

**Status, stated plainly: the page is not finished.** Its navigation layer is real and verified; its
diagram does not paint. It is recorded as such in the plan's Progress table, and the jsx6 finding is to
be taken up in the library before this step is called done.
