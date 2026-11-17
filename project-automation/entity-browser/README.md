# `entity-browser` — a searchable record/entity page over the class index

A Bun-built HTML page that answers the question "what does this record contain, and what does it reach?".
It lists a project's records, shows one record's compact component structure, follows a component's type
to the type it names, and prints a small enum's values **on the component line** so a three-constant enum
does not cost a click.

It is a **`jsx6`** page, because it has search, selection, dependency drill-down and state beyond a
document (DEC-027's *interactive or advanced* class). It is a **served page directory**, not a
`file://` document: `index.html` loads `browser.js`, and `browser.js` fetches `model.json` from the same
origin. The JetBrains **WebView Explorer** serves that directory over loopback when you right-click the
HTML file; `serve.js` does the same for a plain browser.

## Where the tool lives, and where its output lives

- **The tool is committed under `project-automation/entity-browser/`.** It is dev-time code: nothing
  installs, deploys or ships it, and no application module depends on it. That is the `project-automation`
  boundary (root `AGENTS.md` § 1.1), and the same place a project keeps its generators.
- **The output is derived and lives in `.jcodebuddy/metadata/browser/`:** the page directory holds
  `index.html`, `browser.js`, `model.json`, `serve.js` and `README.txt`, and is ignored by DEC-026's
  metadata rule. `mvn clean` does not touch it, and it is never committed. `serve.js` is copied there so
  the output is runnable with Bun alone; the bundle already carries jsx6, so there is no `node_modules`
  to ship.
- **Customization is a build-time edit in this folder**, not a plugin registration. `build.config.json`
  is the project's setup (which modules/indexes the page covers, its link base, title, output and bridge
  port); the page source, model resolver and verification live beside it and can be changed for this
  project. As a part becomes reusable by other projects, it is promoted to a JCodeBuddy library (the
  direction § 1.1 describes); the project-automation folder keeps only the setup and the project-specific
  bits.

### What is reusable, and what is this project's

| part                                                                             | kind                  | where it goes when a second project needs it   |
| -------------------------------------------------------------------------------- | --------------------- | ---------------------------------------------- |
| `src/model.js` — class-index → page model, type resolution, enum inline decision | reusable              | a JCodeBuddy library; the tool then imports it |
| `src/verify.js` — source-line verification and DEC-022 diagnostics               | reusable              | same library                                   |
| `src/bridge.js` — the injected/HTTP/clipboard ladder and link-base resolution    | reusable              | same library (or `webview` kit)                |
| `src_build/serve.js` — a small static server with a path jail                    | reusable              | a library or a toolkit                         |
| `build.config.json` — modules, link base, output, title, port                    | project setup         | stays here                                     |
| `index.html` / `src/index.jsx` — layout, labels, drill-down presentation         | project customization | stays here                                     |

The split is a normal build change: the tool starts by owning the code, a second consumer is the signal
to promote the reusable part to a library, and the project-automation copy keeps its configuration and
presentation. There is no plugin registry to update and no runtime discovery step.

## The model is JSON, never Java

The build renders a `model.json` from the module's **class index** — `.jcodebuddy/index/classes.json`
(DEC-029) — and the `imports.json` sidecar beside it. This tool parses no Java:

| fact                                       | where it comes from                                                                          |
| ------------------------------------------ | -------------------------------------------------------------------------------------------- |
| types, kinds, modifiers, declaration lines | `classes.json` rows                                                                          |
| record components                          | rows' `members` with `kind: "record-component"`                                              |
| enum constants                             | rows' `members` with `kind: "enum-constant"`                                                 |
| `implements` / `extends`                   | rows' `relations`                                                                            |
| a component type's target                  | the index's types, the declaring file's imports, and the same-package / enclosing-type rules |
| source lines                               | the row's `line`, verified against the file before the page writes a link                    |

Record components are a member kind added to the index on 2026-11-16. A module indexed by an older
JCodeBuddy has rows without them; regenerate after updating (see "Stale rows" below).

## Build

```sh
# the modules in build.config.json, zero CLI configuration
bun project-automation/entity-browser/src_build/build.js

# a real project whose root holds .jcodebuddy/index/classes.json
bun project-automation/entity-browser/src_build/build.js --index proto/business-logic/.jcodebuddy/index/classes.json

# several modules in one page
bun project-automation/entity-browser/src_build/build.js \
  --index core/.jcodebuddy/index/classes.json \
  --index demo/.jcodebuddy/index/classes.json \
  --link-base . \
  --out .jcodebuddy/metadata/browser
```

The first run needs `bun install` in this directory (esbuild) and the jsx6 checkout at `<repo>/.jsx6`
(clone `https://github.com/hrgdavor/jsx6` there, or set `JCODEBUDDY_JSX6_DIR`).

The output defaults to `<link-base>/.jcodebuddy/metadata/browser/` and contains `index.html`,
`browser.js`, `model.json`, `serve.js` and `README.txt`. It is derived report output under DEC-026's
ignored metadata subtree, not a committed artifact and not Maven's `target/`.

### Serve it standalone

```sh
# the tool's source copy
bun project-automation/entity-browser/src_build/serve.js --dir hipster-entity/hipster-entity-example/.jcodebuddy/metadata/browser

# or from inside a built directory (the copied server defaults to its own directory)
cd hipster-entity/hipster-entity-example/.jcodebuddy/metadata/browser
bun serve.js
```

The command prints `http://127.0.0.1:<port>/`; open that. The page fetches `model.json` and loads the
bundle from the same origin, so no `file://` workaround is involved. `--port` and `--host` are optional;
the server is loopback-only by default and refuses paths outside the served directory.

### Flags

| flag                  | meaning                                                                                       | default                                                          |
| --------------------- | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `--module <dir>`      | read `<dir>/.jcodebuddy/index/classes.json` (repeatable)                                      | `build.config.json`'s `modules`                                  |
| `--index <file>`      | read one class index file (repeatable; can be mixed with `--module`)                          | `build.config.json`'s `indexes`                                  |
| `--link-base <dir>`   | directory that `data-open` paths are relative to — normally the project root the IDE has open | the index's module root, or the common ancestor of several roots |
| `--out <dir>`         | page directory for `index.html`, `browser.js`, `model.json` and `serve.js`                    | `<link-base>/.jcodebuddy/metadata/browser`                       |
| `--config <file>`     | project build configuration                                                                   | `build.config.json` beside this tool                             |
| `--title <text>`      | page title                                                                                    | `<module> — entity browser`                                      |
| `--bridge-port <n>`   | WebView Explorer HTTP bridge port for the browser fallback; `0` disables it                   | `18881`                                                          |
| `--soft`              | exit 0 even when a source link cannot be verified                                             | off                                                              |
| `--quiet`             | print only the output path and the summary                                                    | off                                                              |
| `--version`, `--help` | identity / usage, no build                                                                    | —                                                                |

An explicit `--module` or `--index` replaces the configured source set rather than adding to it; the
other defaults (link base, output, title, port) are still taken from the config unless a flag overrides
them.

Exit codes: `0` success, `1` an I/O error or an unverified source file (unless `--soft`), `2` a usage error.

## Open it in IntelliJ

1. Right-click the generated `index.html` in the Project view
   (`.jcodebuddy/metadata/browser/index.html`).
2. Choose **Open in WebView Explorer** (the action is on the Project view, editor and editor-tab popups;
   `Ctrl+Alt+Shift+W` toggles the tool window). The plugin serves the page over loopback HTTP — it does
   **not** open it as `file://`.
3. Click a record in the sidebar, then a component, a dependency chip or a `:line` link.

The plugin starts its own server on demand under a one-time
`http://127.0.0.1:<port>/page/<token>/…` URL. To use a host that is already running instead, set
**Settings → Tools → WebView Explorer → Existing page server** to its base, for example
`http://127.0.0.1:18884/page/` (what `webviewd --project <root>` serves); the page's path relative to the
project root is appended. That base is the path mapping; no server is started when it is set.

Source links use the frozen navigation contract (`webview/kit/doc/contract.md`):

1. `window.openFile(path, line, column)` — injected by the WebView Explorer;
2. the host's loopback HTTP bridge through a hidden iframe when the page is a plain browser; the page
   discovers the host's actual port from `/health` on its own origin;
3. clipboard (`path:line`) when there is no host — which is what `serve.js` alone gives you.

The written targets are relative to `--link-base`; at click time the page resolves that base against its
own location and hands the host an **absolute** path, so the IDE project may be the module or the repository
above it.

## What the page does

- **Records first.** The sidebar lists records. `all types` widens it to every indexed type and is how an
  enum is reached directly.
- **Compact structure.** A record shows its components in header order: name, type, annotations and the
  verified source line. A boolean/enum/primitive cell is never a drill-down; an enum of up to 12 constants
  is printed after its name, e.g. `status OrderStatus (NEW, PAID, SHIPPED)`. A larger enum says
  `N values →` and links to the enum.
- **Drill down.** A type name that resolves in the index is a link; clicking it opens that type, with a
  breadcrumb and `← Back`. `Depends on` lists the indexed types the declaration reaches, grouped by kind,
  and a separate row lists the external types (JDK and library types) the index cannot open.
- **Deep links.** `#<fully-qualified type>` selects that type on load, so another tool can link to one
  record or one enum.
- **Search** (`/` focuses, `Esc` clears, `Enter` opens the first result) matches names, packages,
  components, component types, local dependencies and enum values — so `PAID` finds a record whose status
  is the enum containing `PAID`.

## Verification and diagnostics

Before writing a link, the build checks that the file exists and that the recorded line contains the
member name. A stale line is dropped from the page and reported as `browser_line_stale` (a warning); a
missing file is `browser_source_missing` (an error, exit 1 unless `--soft`). An ambiguous simple name is
left unlinked and reported as `browser_ambiguous_type` rather than guessed. Diagnostics use DEC-022's
`kind, location, cause, current, canonical, action` format.

The page carries paths and lines, never file content.

## Stale rows after updating JCodeBuddy

The class index is incremental: an unchanged source file reuses its previous row. A row written before
this tool's `record-component` kind existed therefore keeps its empty `members` list even after a pass.
Force a full index rebuild by deleting the derived table and the entity pass's per-file cache, then
regenerating:

- `.jcodebuddy/index/classes.json`
- `.jcodebuddy/metadata/entity/cache/`

Both are derived output; nothing in either location is committed. Run the project's `scripts/gen.js`
after deleting them.

## Tests

```sh
cd project-automation/entity-browser
bun install          # once, for esbuild
bun test
```

`src/model.test.js` covers record extraction, type resolution (same package, nested type, explicit and
wildcard imports, ambiguity), the inline-enum threshold and search. `src/verify.test.js` covers verified,
stale and missing source lines. `src/bridge.test.js` covers the navigation URL. `src_build/build.test.js`
builds a fixture module and asserts the page directory (`index.html`, `browser.js`, `model.json`) and its
inline enum values; `src_build/serve.test.js` starts the server and checks the page, JSON, nested assets and
path-traversal refusal. The build tests skip when the jsx6 checkout or esbuild is absent.

## What it deliberately does not do

- It does not parse Java. A type the index and imports cannot resolve stays external text.
- It does not show method bodies or field initialisers; the class index points at the source instead.
- It does not index a project that has no `.jcodebuddy/index/classes.json`; run that project's JCodeBuddy
  entity pass first.
- It does not work as a `file://` document. The page fetches `model.json`, so it needs the plugin's server
  or `serve.js`; that is why the build emits a directory rather than one inlined HTML file.
- It reaches only its own origin at view time: no CDN, no external script, no network dependency. The
  source-navigation bridge is a click-time fallback.
