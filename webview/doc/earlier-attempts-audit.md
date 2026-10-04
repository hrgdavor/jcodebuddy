# The five earlier sidecar attempts — the 3.0p audit

> **What this is.** Five directories under `webview/` are earlier takes on the sidecar functionality the current
> suite provides: `intellij-jwa`, `intellij-jswa`, `vscode-jwa`, `vscode-jswa` and `jswa-core`. DEC-039 moved them
> under `webview/`, three of them now say so at the top of their README, and **nothing had compared them**. This is
> that comparison: every capability gets a verdict, every directory gets a verdict, and step 3.0q acts on the
> result. **This step merges nothing and deletes nothing.**
>
> **How it was done, and what it did not do.** Read-only inspection of every tracked file: build files, entry
> points, plugin manifests, extension registration, and what each one launches or talks to. **No Gradle or npm
> build was run** — step 3.0p says "no build gate — this step reads" — so "would it build" is reported as what its
> build files reference and whether those references still resolve, never as a build result. Every claim below
> cites a path; nothing is asserted from memory.

**The five together, measured:** 50 tracked files — 219 lines of Java, 204 of JavaScript, 85 of TypeScript, and no
Kotlin sources (76 lines of `.kts` build scripts). **25 of those 50 files are committed Gradle `.gradle/` cache
state.** None of the five is in the root POM's reactor; they are two standalone Gradle IntelliJ Platform projects,
two VS Code npm extensions and one Bun/TypeScript package.

## The shape being audited against

The maintainer's answer of 2026-10-03 fixes the target: **all three editors are actually driven** (JetBrains, VS
Code, Eclipse), so "worth keeping" is not "pick the winner" — the shape is **one shared host with a thin client per
editor**, and what may be deleted is a client that duplicates another's job, or an attempt whose capability the
shared host now provides.

What exists today, and what each part is for (from [`webview/README.md`](../README.md)):

| Part             | Path                                    | What it provides                                           |
| ---------------- | --------------------------------------- | ---------------------------------------------------------- |
| the shared host  | `webview/core/webviewd`                 | the standalone page server: `/health`, `/open`, `/file/`, `/proxy`, `/api/v1/*`, the descriptor and manifest, adapters (lsp, zed-cli, null). Main class `hr.hrg.webview.webviewd.WebviewdMain` |
| the host library | `webview/core/webview-core`             | what the host and the IDE hosts are built from (`InjectedBridge`, `BridgeMessage`, `AllowedOrigins`, `RateLimiter`, `UrlNormalizer`) |
| the LSP sidecar  | `webview/jwa-sidecar`                   | the LSP transport and the JWA side. Its main class is `hr.hrg.watch2.sidecar.SidecarApp`; the shaded artifact's `finalName` is **`jwa-sidecar`** (`pom.xml:81`) |
| the consumer kit | `webview/kit`                           | the copy-pasteable half a page author takes                |
| JetBrains client | `webview/webview-jetbrains`             | IntelliJ Platform plugin (Gradle, `rootProject.name = "WebView Explorer"`), a JCEF tool window that injects `window.openFile`. Not a Maven module |
| VS Code client   | `webview/webview-vscode`                | the Node package `vscode-webview-explorer`: a webview sidebar with an address bar plus an HTTP bridge |
| Eclipse client   | `webview/eclipse/webview-eclipse`       | the SWT `Browser` workbench view (Maven `webview-eclipse`) |
| Zed client       | `webview/zed/webview-zed-dev-extension` | registers the sidecar as a language server, because Zed accepts only language servers an extension declares |

**The Zed row is the precedent that matters for two verdicts below**: registering the sidecar as an *LSP server* is
the established shape for a thin client, and it is already done once — for Zed.

**What the sidecar already owns**, which decides where a client half ends and the server half begins:
`mytool/jump` as a notification (`JwaLanguageClient.java:12`), a `/jump` HTTP route (`SidecarApp.java:56-57`), the
jump service itself (`SidecarApp.startJumpService`, exercised by `SidecarAppJumpServiceTest`), navigation delegated
to `webview-core`'s `Navigator` (`JwaLanguageServer.java:21`), `window/showDocument` (`JwaLanguageServer.java:181`)
and `executeCommandProvider` for `jwa.syncBuilder` (`JwaLanguageServer.java:228`).

## `webview/intellij-jwa` — has material to merge

**What it is.** Three real files and two build files, plus fifteen tracked Gradle cache files under `.gradle/`:

| File                                                                    | Size         | What it does                                                                    |
| ----------------------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------- |
| `src/main/java/hr/hrg/watch2/intellij/JwaLspServerDescriptor.java`      | 97 lines     | a `ProjectWideLspServerDescriptor` that launches the sidecar                    |
| `src/main/java/hr/hrg/watch2/intellij/JwaLspServerSupportProvider.java` | 15 lines     | registers that descriptor with the platform's LSP support                       |
| `src/main/resources/META-INF/plugin.xml`                                | —            | declares the provider as `platform.lsp.serverSupportProvider` (`plugin.xml:10`) |
| `build.gradle.kts`, `settings.gradle.kts`                               | 42 + 1 lines | the Kotlin-DSL Gradle build: `org.jetbrains.intellij.platform` 2.1.0, `intellijIdeaCommunity("2024.1")` |

There is **no README**, so the sources are the statement. The descriptor's `createCommandLine()`
(`JwaLspServerDescriptor.java:31-41`) finds a sidecar JAR, finds a Java executable, and returns
`java -cp <jar> hr.hrg.watch2.sidecar.SidecarApp` — and it throws a named
`ExecutionException("JWA Sidecar JAR not found. Please build it first or check plugin installation")` when the JAR
is missing, which is the failure a user is meant to read. Its JAR candidates are a `PropertyComponent`
(`hr.hrg.watch2.jarPath`), a bundled `sidecar/jwa-sidecar.jar`, and a development path
(`JwaLspServerDescriptor.java:68,75,84,91`); it claims only `.java` files (`:27`) and **defines no LSP method
itself** — the platform's LSP support does that, which is why `window/showDocument` from the sidecar is enough for
the editor side to work.

**Capability comparison.**

| Capability | Verdict                                                             | Counterpart, or where it belongs |
| ---------- | ------------------------------------------------------------------- | -------------------------------- |
| Register the sidecar as a language server in an IntelliJ-based IDE (the platform's `ProjectWideLspServerDescriptor` API, the support provider, the manifest entry) | **unique — worth keeping** | **no counterpart**: `webview/webview-jetbrains` is a JCEF tool window and registers no language server (grep for `LspServer`/`LspServerSupportProvider` in its sources finds nothing). Belongs in `webview/webview-jetbrains`, next to its existing view |
| Launch the sidecar from a JAR with a discovered Java home, and the three JAR candidates (property, bundled, development) | **unique — worth keeping, but only as the descriptor's own detail** | the discovery rules belong with whichever host launches the sidecar; `vscode-jwa`'s twin has the same logic in TypeScript. **The expected JAR name matches**: this looks for `jwa-sidecar.jar`, which is today's sidecar artifact's `finalName` (`webview/jwa-sidecar/pom.xml:81`) |
| The development JAR path (`build.gradle.kts:25` copies from `../webview/jwa-sidecar/target/jwa-sidecar.jar`) | **dead — and evidence for the delete verdict** | after DEC-039 moved this directory under `webview/`, that path resolves to `webview/webview/jwa-sidecar/...`, which does not exist. The copy task was written when the directory sat at the repository root and the move left it pointing a level too high |
| The `.gradle/` build cache (15 files, 65 032 bytes: locks, `fileHashes.bin`, `last-build.bin`, `gc.properties`) | **dead** | committed build state that no build reads from a fresh clone; **nobody's counterpart — it should simply not be tracked** |

**Not stale, which is worth saying**: the class it launches, `hr.hrg.watch2.sidecar.SidecarApp`, is exactly the
main class of today's `webview/jwa-sidecar` (`SidecarApp.java:53,94`). This attempt points at code that still
exists; it is superseded in *packaging*, not in *contract*.

**Directory verdict: has material to merge** (the LSP registration into `webview/webview-jetbrains`). Everything
else in the directory is either its build files or the tracked cache above.

## `webview/intellij-jswa` — delete

**What it is.** The same shape as `intellij-jwa`, one level down: two Java files
(`src/main/java/hr/hrg/jswa/intellij/JswaLspServerDescriptor.java`, 91 lines, and
`JswaLspServerSupportProvider.java`, 16), a `plugin.xml` (registering the provider at `plugin.xml:9`), two Gradle
files (32 + 1 lines) and **ten** tracked `.gradle/` cache files (60 972 bytes). No README. It launches
`bun run <script>` rather than a Java JAR, with the same three candidate shapes (`JswaLspServerDescriptor.java:43,50,60`)
and a development probe of `../webview/jswa-core/index.ts` (`:67`) that the move also left a level too high.

**Capability comparison.**

| Capability                                                        | Verdict  | Counterpart, or where it belongs |
| ----------------------------------------------------------------- | -------- | -------------------------------- |
| Register a **JavaScript/TypeScript** sidecar as a language server | **dead** | the sidecar it would register is `webview/jswa-core`, which is a 71-line scaffold (see below). There is no JS/TS sidecar in the suite, and the shared host's adapters are `lsp`, `zed-cli` and `null` — a *Java* LSP sidecar named `lsp`. So this registers a server that does not exist, for a capability the suite does not provide or need |
| The `.gradle/` build cache (10 files)                             | **dead** | as above                         |

**Directory verdict: delete.** Its one distinctive capability is "an LSP client for a JS/TS sidecar", and the thing
on the other end of it was never implemented. The *idea* is recorded here and in the plan's 3.0q record rather than
carried as code — if a JS/TS sidecar is ever wanted, this directory's history is the archive.

## `webview/vscode-jwa` — has material to merge

**What it is.** Four tracked files: `package.json`, `package-lock.json`, `extension.js` (**116 lines**) and a README
that already says it is an earlier attempt. `package.json` declares `main: ./extension.js`, activates on
`onLanguage:java`, contributes `commands` (including `jwa.syncBuilder`) and `configuration`, and depends on
`vscode-languageclient ^8.1.0`.

**What `extension.js` does.** `require('vscode-languageclient/node')` and a `LanguageClient`
(`extension.js:2`), a Java-home discovery chain documented in its own comment — extension setting `jwa.java.home`,
then `JAVA_HOME`, then `java` from `PATH` (`extension.js:6-15`) — three JAR candidates (setting `jwa.server.jarPath`,
bundled `sidecar/jwa-sidecar.jar`, and a development path, `:40,45,50`), a `documentSelector` for Java with a file
watcher, and — the part that is easy to miss — **a handler for the sidecar's `mytool/jump` notification**
(`:90-96`) that opens the document and moves the selection. A closing comment (`:102-103`) records that Sync
Builder is server-driven: the lightbulb comes back as a `workspace/executeCommand`, which the sidecar owns
(`JwaLanguageServer.java:228`).

**Capability comparison.**

| Capability                                                                                | Verdict                                        | Counterpart, or where it belongs                                         |
| ----------------------------------------------------------------------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------ |
| Register the sidecar as an LSP client for Java in VS Code (`LanguageClient`, activation on `onLanguage:java`, file watcher) | **unique — worth keeping** | **no counterpart**: today's `webview/webview-vscode` is a webview sidebar plus an HTTP bridge, and its sources contain no language client (its only `lsp` occurrence is an adapter *name* in a test fixture). Belongs in `webview/webview-vscode` |
| **The `mytool/jump` notification handler** — receive a jump from the sidecar and reveal the document at line/column | **unique — worth keeping** | **no counterpart in the VS Code host**: the *sidecar* sends `mytool/jump` (`JwaLanguageClient.java:12`) and today's extension does not listen for it; its `showTextDocument`/`revealRange` calls are in the HTTP bridge (`HttpBridge.ts:538,542`) for page-driven navigation, which is a different route. Belongs in `webview/webview-vscode`, and it is the client half of the sidecar's jump service |
| Java-home discovery (`jwa.java.home` → `JAVA_HOME` → `PATH`) and the three JAR candidates | **unique — worth keeping**                     | belongs with the client that launches the sidecar; the IntelliJ twin needs the same answer, and the JAR name it looks for matches today's artifact |
| Sync Builder code actions                                                                 | **duplicated**                                 | the current sidecar owns them: `executeCommandProvider` for `jwa.syncBuilder` (`JwaLanguageServer.java:228`), and this extension's own comment says the lightbulb is server-driven |
| The `commands` and `configuration` contributions in `package.json`                        | **duplicated**                                 | today's extension declares its own contributions; nothing here is unique |
| The development JAR fallback (`extension.js:50`)                                          | **dead — and evidence for the delete verdict** | it names `..','webview','jwa-sidecar',…`, which the 3.0o move left resolving to `webview/webview/jwa-sidecar/…` |
| `path.join` calls with no `require('path')` (`extension.js:19,26,45,50`)                  | **dead — a bug, not a capability**             | **do not copy this file verbatim**: `path` is never imported, so any run that enters the `jwa.java.home` or `JAVA_HOME` branch throws. The merge must write the discovery rule properly and test it |

**Directory verdict: has material to merge** (the language-client registration and its Java-home rule into
`webview/webview-vscode`). The Sync Builder and Remote Jump features are the *sidecar's* and are already current.

## `webview/vscode-jswa` — delete

**What it is.** Four tracked files: `package.json` (activating on `onLanguage:javascript` and
`onLanguage:typescript`, contributing `configuration`), `package-lock.json`, `extension.js` (**88 lines**) and the
"earlier attempt" README. It launches `bun run <script>` with a `documentSelector` for
javascript/typescript (`extension.js:55-64`), and a development fallback of `path.join('..','jswa-core','index.ts')`
(`:44`) — the one path among the five that is **still correct**, because both directories moved together.

**Capability comparison.**

| Capability                                                            | Verdict  | Counterpart, or where it belongs                                                        |
| --------------------------------------------------------------------- | -------- | --------------------------------------------------------------------------------------- |
| Register a JS/TS sidecar as an LSP client in VS Code                  | **dead** | same reason as `intellij-jswa`: the server it would talk to (`jswa-core`) is a scaffold |
| "Signals support: snippets and validation for custom signal patterns" | **dead** | the README's own wording; its `package.json` contributes only a `configuration` block, and the validation it names lives in `jswa-core` as a commented placeholder |

**Directory verdict: delete.**

## `webview/jswa-core` — delete

**What it is.** Seven tracked files, of which the substance is `index.ts` (**85 lines**), `INTEGRATION.md`
(63 lines), `package.json`, `tsconfig.json`, `bun.lock` and a `.gitignore`. Its README opens not with a description
but with **"ideas:"** and three links (js-codeformer, jsref, Fowler's codemods API).

**What `index.ts` does — a working sketch, not nothing.** It is an LSP server over stdio
(`createConnection(ProposedFeatures.all)`, `:19`) with `TextDocuments` beside it, declaring incremental sync, a
completion provider with `.` and `:` trigger characters, completion resolve, and `codeActionProvider: true`
(`:26-34`). The handlers are where it stops: `validateTextDocument` publishes an **empty** diagnostics array as a
placeholder (`:49-58`), while `onCompletion` returns one hard-coded `signal` snippet (`:60-71`) and
`onCompletionResolve` fills in its snippet text (`:73-79`). It touches no files, opens no socket and depends only
on the `vscode-languageserver` packages.

**Capability comparison.**

| Capability                                                             | Verdict                           | Counterpart, or where it belongs |
| ---------------------------------------------------------------------- | --------------------------------- | -------------------------------- |
| A JS/TS language-server sketch for "signals and boilerplate reduction" | **dead**                          | what runs is one hard-coded `signal` snippet and a completion-resolve that fills it in; validation publishes an empty diagnostics array as a placeholder. Nothing in the suite consumes it, and the idea it sketches is not one the shared host provides a home for |
| The declared capabilities a future attempt would want (`codeActionProvider`, incremental sync, `.`/`:` completion triggers) | **dead as code, live as an idea** | recorded here and in the plan's 3.0q record; a future JS/TS sidecar would start from the declaration set, not from this file |
| The design links in its README and the 63 lines of `INTEGRATION.md`    | **dead as code, live as an idea** | `INTEGRATION.md` documents wiring for VS Code, IntelliJ, Zed and Neovim, but hard-codes an `/absolute/path/to/java_watch2/...` checkout (`:36,63`) and tells a reader to `cd vscode-jswa` / `cd intellij-jswa` at the old repository root (`:14,21`) — instructions that no longer describe this tree. If a JS/TS sidecar is revisited, this is the reading list, and git history is the archive |

**Directory verdict: delete.** It is the other end of the two `*-jswa` clients, and it is the reason they are dead:
the attempt reached a sketch and stopped there.

## The cross-cutting finding: 25 tracked Gradle cache files

`intellij-jwa` tracks 15 files under `.gradle/` (65 032 bytes) and `intellij-jswa` tracks 10 (60 972 bytes) —
`checksums.lock`, `fileHashes.bin`, `last-build.bin`, `gc.properties`, `cache.properties`, and binaries from two
Gradle versions. None of it is build input; all of it is build *output* that a fresh clone regenerates. The two
directories' deletion in 3.0q removes it, which is the cheapest half of this audit: ~126 KB of machine state that
should never have been committed.

## What 3.0q acts on

**Merge (each as its own commit, with a test):**

1. **IntelliJ LSP registration → `webview/webview-jetbrains`**: the `ProjectWideLspServerDescriptor` +
   `LspServerSupportProvider` + the `plugin.xml` declaration, launching today's sidecar JAR (whose name already
   matches). Precedent: the Zed extension already registers the sidecar as a language server.
2. **VS Code language client → `webview/webview-vscode`**: the `LanguageClient` registration, activation on
   `onLanguage:java`, the file watcher on `**/*.java`, the `jwa.java.home` → `JAVA_HOME` → `PATH` discovery rule, and
   **the `mytool/jump` notification handler** — the client half of the sidecar's jump service, which nothing in the
   suite handles today. **Write it, do not copy it**: the old file uses `path.join` without importing `path`, so its
   discovery branch throws; a test for the discovery rule and the handler is part of the merge.

**Delete (three directories, naming what replaced them):**

3. `webview/intellij-jswa` — an LSP client for a JS/TS sidecar that was never implemented.
4. `webview/vscode-jswa` — the same, for VS Code.
5. `webview/jswa-core` — the 85-line sketch those two talked to.

**Delete with them, and say so**: the 25 tracked `.gradle/` cache files (the two IntelliJ directories carry them).

**After the deletions, two documents need the change too**: `doc/architecture/module-map.md` lists all five as
current modules (its webview table and its `jswa` glossary entry), and
`webview/jwa-sidecar/how_to_test.md:18` tells a reader to open the `vscode-jwa` folder. Both are references a
deletion would leave dangling, and 3.0q's own gate is a grep for exactly that.

## What this audit does not claim

- **It did not build anything**, so "still builds" is answered as "what its build files reference and whether that
  still exists" — and that is the only sense in which 3.0p can answer it. Running the two Gradle plugin builds
  would download an IntelliJ Platform distribution each; the step's own gate says this step reads.
- **It did not judge whether a JS/TS sidecar is desirable.** It records that the three `*-jswa*` pieces are a
  scaffold plus two clients for it, and that deleting them is not a judgement on the idea.
- **It did not compare the two IntelliJ attempts' Gradle builds** beyond their inputs. If 3.0q merges the LSP
  registration, the destination's existing build is what matters, not the old one's.
