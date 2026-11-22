# Preferred JavaScript stack

This is the direct, third-party JavaScript stack this checkout chooses and uses. It is a
**direct-dependency** list, not a dependency tree: transitive packages are not listed. Bun is the
runtime for scripts and builds; npm packages are only those declared by a `package.json` in a product
module.

The local jsx6 checkout and the vendored microlighter asset are included because they are real choices
even though neither is installed as a package. The downloaded VS Code test tree under
`webview/webview-vscode/.vscode-test/` and the `.kilo/` agent plugin are **not** product dependencies and
are not listed.

## Runtime and build

| Library             | Version / range                        | Where                                       | Why it is here |
| ------------------- | -------------------------------------- | ------------------------------------------- | -------------- |
| **Bun**             | repository toolchain (currently 1.4.x) | scripts, UI builds, checks                  | The runtime for JavaScript tooling and the bundler launcher. The repository rule is Bun JavaScript, not shell scripts. |
| **esbuild**         | `^0.25.0` (dev)                        | `jcodebuddy-agent/web`, `merge-java/review`, `project-automation/compiler-console`, `project-automation/entity-browser` | The single bundler for jsx6 pages. It handles the JSX transform, local `@jsx6/*` aliases and source maps. |
| **jsdiff** (`diff`) | `^9.0.0`                               | `jcodebuddy-agent/web` (runtime dependency) | Line diff for the agent dashboard and builder review cards. The agent now imports `diffLines` instead of carrying its own LCS implementation. |

## UI and graph

| Library          | Version / range                                                       | Where                                                                          | Why it is here |
| ---------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------ | -------------- |
| **jsx6**         | local checkout, updated on demand (`JCODEBUDDY_JSX6_DIR` or `.jsx6/`) | every interactive product page                                                 | The required UI library: signals, JSX/DOM contract and the page/runtime model. It is deliberately not an npm dependency; the checkout's own `AGENTS.md` is the usage authority. |
| **nodditor**     | part of the jsx6 checkout (`@jsx6/nodditor`)                          | `hipster-ioc/hipster-ioc-graph`                                                | The jsx6 graph/editor used for the IoC dependency graph. It is a jsx6 app, not a separate npm package. |
| **microlighter** | 2.2.0, vendored                                                       | `markdown-view/assets/microlighter.js`; used by `markdown-view/page-client.js` | Self-contained code syntax highlighting for Markdown report pages. It is copied upstream code, not an npm dependency. |

### Syntax highlighting: what exists and where

- **Chosen highlighter:** **microlighter 2.2.0** (MIT), vendored as
  [`markdown-view/assets/microlighter.js`](../markdown-view/assets/microlighter.js). It is used for
  **fenced code blocks in generated Markdown report pages** and for the **code lines in the agent's
  change-batch diffs**.
- **Grammars bundled:** JavaScript, JSON, Java, HTML, Markdown, CSS and YAML. Kotlin is deliberately
  absent; a Kotlin fence stays escaped/uncoloured.
- **Mechanism:** it builds ranges and registers them with the browser's **CSS Custom Highlight API**
  (`CSS.highlights` / `Highlight`), with capability guards. It does not wrap tokens in `<span>` elements.
  When the API is unavailable it reports the blocks it would have highlighted and the page gets **no
  colour**; there is no fallback highlighter.
- **No highlighter is chosen for the rest of the jsx6/HTML application UI.** The entity browser, compiler
  console and ChatApp client render code as plain text; their diffs are not highlighted. The agent's
  **change-batch** view is the exception because it uses the vendored microlighter on each diff line.
  There is **no Prism, Shiki, highlight.js, CodeMirror or Monaco** dependency in the product UI.
- **nodditor is not a highlighter.** It is the graph/editor for the IoC relations page.

## JCodeBuddy JavaScript tooling

| Library                  | Version / range | Where                     | Why it is here                                                                    |
| ------------------------ | --------------- | ------------------------- | --------------------------------------------------------------------------------- |
| **@hrg/inject-examples** | `^1.2.2`        | root tooling (`scripts/`) | The reference section/marker parser. Tools that check markers or sections use it instead of carrying a second grammar; it is the recorded exception to `scripts/`'s dependency-free rule. |
| **@hrg/md-fix-tables**   | `^1.2.0`        | root tooling (`scripts/`) | Normalizes Markdown tables in place; every changed line must remain a table line. |
| **wyhash**               | `link:wyhash`   | root tooling              | The local WyHash package used by JavaScript tooling for the same content-hash identity as the Java side. It is a linked sibling package, not a registry dependency. |

## VS Code extension

`webview/webview-vscode` is a direct consumer of the VS Code extension API and its test stack.

| Library                              | Version / range | Scope      | Why it is here                                                   |
| ------------------------------------ | --------------- | ---------- | ---------------------------------------------------------------- |
| **vscode-languageclient**            | `^8.1.0`        | dependency | LSP client used by the VS Code extension to talk to the sidecar. |
| **TypeScript**                       | `^5.1.3`        | dev        | Compiles the extension.                                          |
| **@types/vscode**                    | `^1.80.0`       | dev        | VS Code API types matching the extension engine.                 |
| **@types/node**                      | `16.x`          | dev        | Node API types for the extension host.                           |
| **@types/glob**                      | `^8.1.0`        | dev        | Types for the file matching used by tests/tooling.               |
| **@types/mocha**                     | `^10.0.1`       | dev        | Mocha types.                                                     |
| **@types/sinon**                     | `^21.0.0`       | dev        | Sinon types.                                                     |
| **@typescript-eslint/eslint-plugin** | `^5.59.8`       | dev        | TypeScript lint rules.                                           |
| **@typescript-eslint/parser**        | `^5.59.8`       | dev        | TypeScript parser for ESLint.                                    |
| **@vscode/test-electron**            | `^2.3.2`        | dev        | Downloads and runs the VS Code integration-test host.            |
| **eslint**                           | `^8.41.0`       | dev        | Lints the extension sources.                                     |
| **glob**                             | `^8.1.0`        | dev        | Test file discovery. This is a development dependency only; repository path matching uses gitignore/wildmatch, not glob. |
| **mocha**                            | `^10.2.0`       | dev        | The extension's test runner.                                     |
| **sinon**                            | `^21.0.1`       | dev        | Test doubles for extension tests.                                |

## What is deliberately excluded

- **Transitive packages** — not listed; the point of this page is the direct choices.
- **`webview/webview-vscode/.vscode-test/`** — a downloaded VS Code distribution with its own enormous
  dependency tree; it is not our direct dependency.
- **`.kilo/`** — the coding-agent plugin's own dependencies, not the product stack.
- **`proto/` driver projects** — consumers of JCodeBuddy, with their own repositories and dependency
  choices. They are not part of this preferred stack.
- **`node_modules/`** — installed output.

If a library is not in this table, the product should not be importing it directly. Adding one means
adding it to the relevant `package.json` and to this page.
