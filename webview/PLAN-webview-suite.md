# Plan — grow `webview` from two IDE plugins into one product: a localhost page host, an LSP sidecar, and a ZED host

Status: **Phases 0, 1, 2 delivered (2026-09-25) plus Phase 5's navigation half as a spike, and Phase 3 with
both its headless half and the LSP buffer path (gate (e) observed on Zed); the JetBrains/VS Code buffer paths,
the page-side client, the extension's `process:exec` half and Phase 6 are not started.** Phase 0's results and
two corrections to this plan are in [`PHASE0-ZED-FINDINGS.md`](PHASE0-ZED-FINDINGS.md); the hosts and their
contracts are in [`doc/webview-host-api.md`](doc/webview-host-api.md) and
[`doc/webview-edit-api.md`](doc/webview-edit-api.md).
Scope: the whole `webview/` product (today: `webview-jetbrains`, `webview-vscode`, `doc/`, `examples/`) plus the
sidecar material that is being folded into it (`jwa-sidecar`, and the JWA/JSWA IDE clients).
Written against: JDK 25 (`C:\Program Files\Java\jdk-25`; the shell's default `java` is 1.8, `JAVA_HOME` is 21 —
every Maven/Gradle command below must pin 25), Maven via **mvnd 1.0.0-m4**, IntelliJ Platform 2026.2.3 / Gradle
9.2.1 for the JetBrains host, Node 24 + `bun` for the JS hosts, and **Zed 1.21.0**
(`C:\Users\hrg\AppData\Local\Programs\Zed\bin\Zed.exe`, `zed://` scheme registered in `HKCU\Software\Classes\zed`).

> **Implementation record — Phase 1, 2026-09-25.** What exists now, and what proves it:
>
> | Built | Evidence |
> | --- | --- |
> | `webview/core/webview-core` (Maven, JDK 25): `InjectedBridge`, `BridgeMessage`, `AllowedOrigins`, `RateLimiter`/`Clock`, `UrlNormalizer`, `PathResolver`, `EditorHost`/`NullHost`, `Navigator`, `NavigationOutcome`, `TextRange` | **97 tests green** in the Maven reactor, no IDE and no Gradle needed |
> | `HostHealth` — the `GET /health` document, and **the page/file server** (`PageServer`, moved out of the VS Code host) | `HostHealthTest`, `PageServerTest`; the route that answered `Access-Control-Allow-Origin: *` is now jailed and tested |
> | `webview/conformance/bridge-decisions.json` | the origin/CORS/rate-limit **and `/health` key** vectors; read by the Java suite **and** by the VS Code host (139 assertions) |
> | **Host parity gate**: every host must answer `/health` with the shared document, and refuse an unauthorized `/open` with `403` *before* it acts on the request | `HostHealthParityTest` — the two IDE hosts cannot be started from this module, so it asserts the two properties that make the runtime behaviour true and says so |
> | `webview-jetbrains` consumes core from the local Maven repository; its five duplicated classes and four test classes were deleted | **25 tests green** under Gradle (was 52; the difference is the 27 that moved to core and are asserted there) |
> | `jwa-sidecar` moved to `webview/jwa-sidecar`, depends on core, `/jump` is authorized, loopback-only and rate limited, `/health` uses the shared document | **7 new tests**, the first the sidecar has ever had, over a real socket |
> | `webview-vscode`'s policy extracted to `BridgePolicy.ts` (`decideRoute`, `healthDocument`), tested without VS Code | `npm run test:unit` → 139 assertions green |
>
> Deliberately deferred from this phase, with the reason: the sidecar's `/jump` route was **hardened rather than
> folded into `/api/v1`** (that surface does not exist until Phase 2, and breaking the documented route with no
> replacement would just move the problem); `reveal` is **not** advertised by the JetBrains host because it needs
> a platform API that has not been verified against the pinned 2026.2.3 build, and a capability that throws is
> worse than one that is absent; `webview-jetbrains`' `NavigatorService` still opens editors itself rather than
> delegating to core's `Navigator` — the two entry points already share one rate limit there, and Phase 2 owns
> the reconnect; and the JetBrains host does **not** yet answer the `/file/` route (`PageServer` is written and
> tested in core, and Phase 2's `webviewd` is its first real caller, so adding a route to a shipped plugin is
> left to a phase that also changes what that plugin is). So the plugin's behaviour is unchanged apart from the
> two new status codes documented in `doc/webview-link-api.md` § 3.
>
> **Correction made in the relocation commit, 2026-09-25.** The risk table's claim that "the POM `relativePath`
> is the only path that changes" when `jwa-sidecar` moves was **wrong** — four more references pointed at the old
> root path and were fixed in the same commit: `intellij-jwa/build.gradle.kts` (`from("../jwa-sidecar/…")`),
> `intellij-jwa`'s `JwaLspServerDescriptor` development-mode probe, `vscode-jwa/extension.js`'s development-mode
> fallback, and `MigrationCompletenessTest`'s module list (which skips a module whose directory is missing, so it
> would have silently stopped sweeping the sidecar's sources instead of failing). Three of the four are *dev-mode*
> paths, which is why no gate caught them: they are only reached from a checkout, never from a bundle.
>
> **Answers taken 2026-09-25, after Phase 1:** §10 Q1 = **(c) then (a)** — a proposal is shown as a diff and is
> written to disk only when the reader accepts it; (b) also applies when the host declares `edit`. And `/jump`
> becomes a **deprecated alias** of `POST /api/v1/open` in Phase 2 rather than disappearing, so the JWA/JSWA
> clients keep working until the alias is withdrawn.

---

## 1. The goal in one paragraph

Today a page can ask an IDE to *show* a file. The page's only host is an editor plugin, and the only verb is
`openFile`. The goal is to make the page host **independent of the editor**: one process that serves pages on
localhost and exposes the whole verb set over HTTP, so the *same* page has *every* capability whether it is
rendered inside a JetBrains tool window, a VS Code webview view, a ZED side-by-side window (or an ordinary
browser), or with no editor running at all. On top of the read/navigate verbs, the page gains the ability to
**change files** — propose, diff, apply, and undo edits — with the same semantics everywhere. ZED, which has no
embeddable webview, is the first editor that is *only* reachable this way.

## 2. Where the code is today

| Piece | Location | What it does | Reusable for this plan |
| --- | --- | --- | --- |
| Frozen page contract | `webview/doc/webview-link-api.md` | `window.openFile(path, line, column)`, `data-open`/`data-line`, `GET /open`, `GET /health`, auth rules, §7 host checklist | **Yes — the base to extend, not replace** |
| JetBrains host | `webview/webview-jetbrains` (Java, JCEF) | Injects `window.openFile` after each main-frame load; HTTP fallback on port 18881; `NavigatorService` resolves the path, rate-limits, opens the editor | Path resolution + rate-limit logic ports directly |
| VS Code host | `webview/webview-vscode` (TS) | Same contract, port 18882, **plus** `/file/<path>` (a local static file server) and `postMessage` bridging inside an iframe | `/file/` server is the seed of the sidecar's page server |
| JWA sidecar | `jwa-sidecar` (Java, LSP4J, shaded jar) | LSP server on stdio **and** an HTTP jump service on **port 7979**, `GET /jump?uri=&line=&column=` → custom `mytool/jump` notification **and** standard `window/showDocument` | **Yes — this is the LSP sidecar, already half-built** |
| Page examples + harness | `webview/examples/` | Two page shapes, a CDP smoke test (49 assertions, 4 pages), offline microlighter highlighting | The parity gate for "headless is not lacking" |
| JWA/JSWA IDE clients | `vscode-jwa`, `vscode-jswa`, `intellij-jwa`, `intellij-jswa` | Thin clients that launch a sidecar for the Java/JS agent products | Host adapters of the same sidecar |

**The duplication that motivates the rewrite.** The auth model, CORS emission, allow-list semantics, and the
"20 navigations / 20 s" rate limit exist **three times** — `HttpBridgeService.java`, `HttpBridge.ts`, and
`SidecarApp.java` — and they disagree: the JetBrains bridge denies every caller until configured, while the
sidecar's `/jump` sends `Access-Control-Allow-Origin: *` and accepts anyone. There is no write verb anywhere, and
no host that works without an editor.

## 3. Decisions

| # | Decision | Choice | Why | Status |
| --- | --- | --- | --- | --- |
| D1 | Where the page is served from | **A standalone sidecar is the canonical host.** IDE plugins become thin adapters that either proxy to it or keep injecting into their own webview | The page must work with no editor; only a separate process can exist in all cases. Avoids a *third* copy of bridge/auth code in ZED | proposed |
| D2 | How the webview gets a host per editor | JetBrains/VS Code: **unchanged injection** (keep working exactly as today). ZED: **no embedded webview exists**, so the standalone window is the webview | Do not regress two working hosts to gain a third. Zed extensions cannot create UI panels (see §5) | proposed |
| D3 | ZED integration order | **Tier 1 LSP channel (`window/showDocument` + `workspace/applyEdit`) → Tier 2 `zed` CLI adapter as the fallback when no LSP client is connected → Tier 3 Zed extension (`process:exec` / language-server registration) for distribution → Tier 4 ACP/MCP as a separate surface** | Tier 1's *verbs* are confirmed working on the installed 1.21.0 (§5.5, Phase 0) and need no ZED cooperation. **Corrected 2026-09-25:** the registration half of Tier 1 needs an **extension** — Zed refuses config-only custom server names (§5.4, A′) — so Tier 3 is a prerequisite for Tier 1, not merely distribution; and Tier 2 cannot place a caret on Windows (§5.3, B), so it can only open files | proposed, **corrected** |
| D4 | Is a ZED plugin that "opens a port" possible | **Yes, but not as a webview.** Zed extensions are Rust→`wasm32-wasip2`, cannot create panels/views, and their granted capabilities are only `process:exec`, `download_file`, `npm:install`. So: an extension may **launch our sidecar** (Tier 3), never host a page | Zed docs, [Developing Extensions](https://zed.dev/docs/extensions/developing-extensions), [Extension Capabilities](https://zed.dev/docs/extensions/capabilities) | **confirmed** |
| D5 | ACP as the integration | **Deferred to an exploration phase, and explicitly not the webview's transport.** Zed deprecated ACP *extensions* in favour of the [ACP Registry](https://agentclientprotocol.com/registry) as of Zed v1.5.0 (we run 1.21.0), and ACP's content model has no HTML (§5.6) | Zed docs, [Agent Server Extensions](https://zed.dev/docs/extensions/agent-servers); the window is served over HTTP, and ACP would only ever be an agent-tool face | **confirmed (deprecation + no HTML)** |
| D6 | "File changes from the webview" | A **digest-guarded edit API** (`/api/v1/applyEdit`) that writes to disk through one command layer, with `dryRun`, and never merges silently | Ambiguous in the request — see §10 Q1. This choice is the conservative default: the browser cannot clobber a file the reader did not see | proposed |
| D7 | Port model | Sidecar picks an **ephemeral port by default** and publishes it in a per-workspace descriptor file; a fixed port is opt-in | A fixed port collides between projects and must not be assumed; the current docs already warn "never assume one" | proposed |
| D8 | Security | **One** implementation, reused by every host: loopback bind, token in a `0600` file under `.jcodebuddy/webview/`, allow-list, rate limit, and **no `Access-Control-Allow-Origin: *`** on any state-changing route | The sidecar's `/jump` currently violates the contract's own §3 rules; a write API makes that a file-writing hole | proposed |
| D9 | Repo layout | Sidecar/LSP code moves **under `webview/`** as siblings of the two plugins | Maintainer instruction in this session; the sidecar and the webview are the same product | proposed |
| D10 | Headless parity | **The sidecar is the reference host.** A verb exists only when the sidecar implements it; each IDE host declares support per verb and the page feature-detects through `/health` | "Headless mode should not be lacking any features" becomes mechanically checkable instead of aspirational | proposed |

## 4. Target layout

```
webview/
  README.md                       (rewritten: one product, four hosts)
  PLAN-webview-suite.md           (this file)
  doc/
    webview-link-api.md           FROZEN read/navigate contract — unchanged
    webview-host-api.md           DELIVERED: the full verb set + /health capability document
    webview-edit-api.md           DELIVERED: the write contract (digest, dryRun, undo, events)
    webview-page-authoring.md     extended: the ladder gains "editor host" and "edit" rungs
  core/
    webview-core/                 DELIVERED Maven module (JDK 25)
      page server                 serves a project subtree + the generated pages (/file/, /page/) — delivered
      command layer               the ONE place a path becomes an open editor / a write (port of NavigatorService)
      api/v1 surface              /open /health /applyEdit /diff /watch /events (the frozen /open and /health
                                  are delivered by webviewd; the write half is Phase 3)
      security                    token, allow-list, CORS, rate limit, path jail + symlink rules — delivered
      hosts                       EditorHost SPI + NullHost (headless) — delivered; ZedCliHost is delivered in
                                  webviewd, VscodeCliHost still to come
    webviewd/                     DELIVERED Maven module (JDK 25): the standalone host — loopback HTTP, the
                                  frozen routes, /page/ with the injected bridge, the manifest, the descriptor,
                                  and the host adapters it can attach
    webview-lsp/                  NEW Maven module: stdio LSP shim over the same command layer
  jwa-sidecar/                    MOVED here (from repo root), retargeted onto webview-core
  webview-jetbrains/              unchanged behaviour, one-line switch to core's command layer
  webview-vscode/                 unchanged behaviour, one-line switch to core's command layer
  zed/
    webview-zed-dev-extension/    DELIVERED Rust wasm extension: registers the language-server name so
                                  lsp.webview-sidecar.binary is legal, and returns the command for Zed to spawn
    phase0/                       the Phase 0 apparatus (stub LSP server, DB probe, scratch targets)
    settings-snippet.json         Tier 1 configuration a user pastes into Zed — **only useful with the
                                  extension above**, which Phase 0 proved is required (A′)
  examples/                       unchanged; smoke test extended to the whole verb set (§7)
```

## 5. What ZED actually allows — the findings this plan rests on

Verified from Zed's own documentation on the machine's Zed **1.21.0**, and — where marked **measured** — by
Phase 0's experiments against the installed build ([`PHASE0-ZED-FINDINGS.md`](PHASE0-ZED-FINDINGS.md)):

1. **No embeddable webview, and no custom UI.** Extensions can provide languages, debuggers, themes, icon
   themes, snippets, and MCP servers — nothing that renders an arbitrary page
   ([Developing Extensions](https://zed.dev/docs/extensions/developing-extensions)). A page therefore has to be
   shown by a **separate window** (a browser) — which is exactly the "standalone window on localhost" the request
   asks for.
2. **An extension can run a process, and Zed can be asked to run it.** The `process:exec` capability grants
   `zed_extension_api::process::Command`, restricted per-command/per-args by
   `granted_extension_capabilities` ([Extension Capabilities](https://zed.dev/docs/extensions/capabilities)).
   Language-server, context-server and debugger extensions are the ones that require Rust code, and a manifest can
   register a **language server** — a long-lived child process Zed owns. That is the only honest reading of "a
   plugin that opens a port": **the extension starts our sidecar; the sidecar owns the port.** Item 4 below makes
   this mandatory rather than optional.
3. **Getting a file into ZED from outside works for opening, not for positions.** The CLI takes paths, `-e/--existing`,
   `-a/--add`, `-n/--new`, `-r/--reuse` and `--wait`, and it opens `zed://`, `file://` and `ssh://` URLs; on this
   machine `zed://` is registered as a protocol handler for `Zed.exe`
   ([CLI Reference](https://zed.dev/docs/reference/cli)). **Measured: the documented `path:line[:column]` form
   does not work on Windows in 1.21.0** — `windows_only_instance` logs `error parsing path argument … (os error
   123)` for `:42` and `:42:10`, absolute or relative, while a plain path parses. The `zed://` form parses without
   error but whether an *already running* window receives it is **unverified**. Navigation therefore has to be the
   LSP channel; the CLI/URL tier only opens files.
4. **A custom language server can be configured without publishing an extension** via `"lsp": { "<name>": {
   "binary": { "path": …, "arguments": […], "env": {…} } } }`, with `initialization_options` and `settings`
   ([Configuring Languages](https://zed.dev/docs/configuring-languages)). This is Tier 1.
   **CORRECTED 2026-09-25 by Phase 0 — this is false on 1.21.0.** The `lsp` key accepts only names an extension
   or a built-in adapter has registered; an arbitrary name is silently ignored
   ([zed#52653](https://github.com/zed-industries/zed/issues/52653), closed *not planned*: *"Zed currently only
   allows LSP names that an extension or built-in adapter has registered"*). **Measured:** a project settings file
   naming `jcb-phase0-md` for `Markdown` started nothing, while
   `lsp.rust-analyzer.binary.path` → our stub did start it. So Tier 1 requires a **registration extension** in
   front of it; the cheap shape is [zed-customlsp](https://github.com/zhcn000000/zed-customlsp)'s — an extension
   that registers the id and lets settings supply the binary. Phase 4's extension is a prerequisite, not a
   distribution nicety, and its `settings-snippet.json` is only useful alongside it.
5. **Zed's LSP client really does honour `window/showDocument` — with the selection — and
   `workspace/applyEdit`.** Read in Zed's source:
   * `crates/lsp/src/lsp.rs` advertises `window.show_document.support = true` and `workspace.applyEdit = true` in
     its initialize params ([file](https://github.com/zed-industries/zed/blob/main/crates/lsp/src/lsp.rs),
     `default_initialize_params`).
   * `crates/project/src/lsp_store.rs` registers handlers for **`lsp::request::ShowDocument`**, which builds a
     `LanguageServerShowDocumentRequest { uri, external, take_focus, selection, … }` and emits it to the UI, and
     for **`lsp::request::ApplyWorkspaceEdit`**, which routes to `on_lsp_workspace_edit`
     ([file](https://github.com/zed-industries/zed/blob/main/crates/project/src/lsp_store.rs), `setup_lsp_messages`).
   * For reference, the same method registers `WorkspaceConfiguration`, `WorkspaceFoldersRequest`,
     `RegisterCapability`, `UnregisterCapability`, `WorkDoneProgressCreate`, the refresh requests, and
     `ShowMessageRequest`.

   **Measured on the installed 1.21.0 (Phase 0), not just read on `main`:** the client advertises both
   capabilities in its own `initialize`; `window/showDocument` with a selection and `takeFocus` answered
   `{"success":true}` and the caret was observed at the requested position; `workspace/applyEdit` answered
   `{"applied":true}`, sent `textDocument/didChange` back to the server, and left the bytes on disk untouched
   while Zed's own database held the modified buffer — i.e. the edit landed in the buffer and its undo stack.
   **Consequence for this plan:** the sidecar's *existing* `JwaLanguageServer.jump` already sends exactly this
   `ShowDocument` request, so the **verbs** of Tier 1 need no CLI process; but per item 4 the *registration* does
   need an extension, so "no extension" was wrong. The write path (§6.2) has a native-host route confirmed:
   `workspace/applyEdit` lets Zed apply the change to its own buffer and undo stack. One compatibility requirement
   comes with it: Zed asks Rust-scoped servers for `experimental/runnables` and logs an error if the answer is not
   an array, so `webviewd --lsp` must answer it.
6. **ACP is a JSON-RPC 2.0 protocol whose UI vocabulary is markdown-and-tool-calls, not HTML.** The spec is at
   **v1** with a v2 draft ([ACP overview](https://agentclientprotocol.com/protocol/v1/overview),
   [content](https://agentclientprotocol.com/protocol/v1/content)); the flow is `initialize` → `session/new` or
   `session/load` → `session/prompt`, with `session/update` notifications, permission requests, file-system and
   terminal methods, and its `ContentBlock` is deliberately the **MCP** `ContentBlock` (text / image / audio /
   resource / resource_link). There is **no HTML, iframe or webview content type** — so ACP can never be how a
   rich page reaches the user; the localhost window does that. There are official **Java** and Kotlin libraries
   (relevant, since the sidecar is Java), and the listed clients include Zed and **JetBrains**. Note also that
   **ACP extensions are deprecated** in favour of the ACP Registry (Zed v1.5.0+,
   [Agent Server Extensions](https://zed.dev/docs/extensions/agent-servers)), which is a second reason it is an
   exploration rather than a foundation. **Consequence:** ACP is an optional *second* face for the sidecar — a way
   for an agent panel to call the same command layer as a tool — not a transport for the webview. This is why §D5
   defers it.

## 6. The API this plan adds

### 6.1 Host verbs (the whole set a page may call)

`window.openFile` stays exactly as frozen. The sidecar surface becomes the reference, and every host adapter
implements as many verbs as it can — the page never assumes:

| Verb | HTTP (`/api/v1`) | Capability key |
| --- | --- | --- |
| open a file at a line | `GET /open` (frozen, kept) | `open` |
| reveal in the project tree | `POST /reveal` | `reveal` |
| select a range / highlight | `POST /select` | `select` |
| **propose + apply an edit** | `POST /applyEdit` | `edit` |
| **show a diff of a proposal** | `POST /diff` | `diff` |
| **undo / redo the last applied edit** | `POST /undo`, `POST /redo` | `undo` |
| notify the host a page changed | `POST /refresh` | `refresh` |
| stream file-change events (SSE) | `GET /events` | `watch` |
| what this host can do | `GET /health` (extended, backward compatible) | — |

`/health` grows a `capabilities` array and a `bridgeVersion`, so a page decides its ladder from data, not from a
hard-coded port: `openFile` → editor host → localhost verbs → clipboard. The existing `window.__jcbWebViewBridge`
probe keeps working.

### 6.2 The write contract (D6)

```
POST /api/v1/applyEdit
  { "filePath": "src/main/java/A.java",
    "expectedDigest": "sha256:…",        // digest of the content the page rendered from
    "edits": [ { "startLine": 12, "startColumn": 1, "endLine": 12, "endColumn": 8,
                 "newText": "renamed" } ],
    "dryRun": true }                      // true → report the result, change nothing
→ 200 { "applied": false, "digest": "sha256:…", "unifiedDiff": "…" }
→ 409 { "reason": "stale", "digest": "sha256:…" }     // file changed since the page read it
→ 403 / 404 as in the frozen contract
```

Rules that make this safe rather than merely possible: every edit passes the same **path jail** as `/open`
(project root, resolved symlinks, no `..` escape); a **digest mismatch is a refusal, never a merge**; a write is
**atomic** (`tmp` + move) and preserves the file's existing line-ending style; the containing directory must not
be outside the workspace; writes are rate-limited by the same limiter as navigation; and every applied write is
recorded as a checkpoint so `/undo` can restore the previous bytes. When an editor host is present *and* declares
`edit`, the host applies the edit to its buffer (JetBrains: `WriteCommandAction`; VS Code: `WorkspaceEdit`) so the
user sees it in the editor's undo stack; otherwise the sidecar writes to disk. That asymmetry is stated in the
capability document, not hidden.

### 6.3 Where the LSP sidecar fits the same command layer

The sidecar's LSP face keeps `mytool/jump` and `window/showDocument` (`JwaLanguageServer.jump` already sends
both), and gains a server→client `workspace/applyEdit` path so an LSP-capable host can apply a change it asked
for. **On Zed both of these land**: §5.5 confirms the client handles `ShowDocument` (including `selection`) and
`ApplyWorkspaceEdit`, so one process can serve pages over HTTP *and* drive Zed's caret and buffers over LSP. The
LSP face is therefore the primary ZED host, and the CLI/URL adapter (Tier 2) is what runs when no language server
is attached — a browser window open with no project file touched yet, for example.

`/jump` is **not** kept as a second public HTTP surface: it is folded into `POST /api/v1/open` with the same auth
as everything else, which removes today's wildcard-CORS hole. **Decided 2026-09-25:** the fold happens in Phase 2,
and `/jump` survives that release as a **deprecated alias** of the new route — the JWA/JSWA clients are migrated
off it before it is withdrawn. The `jwa-sidecar.txt` addon-file mechanism and the
JWA builder code actions are preserved as they are — this plan changes the sidecar's *transport*, not its tools.
**Corrected 2026-09-25:** the addon-file mechanism has **no implementation to preserve** — nothing in the
repository's history ever read that file, and `modules.md` had recorded it as done. The builder code actions do
exist; see [the Phase 1 record](#phase-1--extract-the-shared-core-and-put-the-sidecar-with-the-product) and
[`jwa-sidecar/modules.md`](jwa-sidecar/modules.md).

## 7. Phases

Each phase ends in a gate that can fail. No phase silently changes the frozen contract.

### Phase 0 — Verify the ZED facts end to end (½ day, no production code)
Source reading says the LSP path works (§5.5); the point of this phase is to see it with the installed 1.21.0.
- Experiment A (the important one): register a **stub** LSP server via `"lsp": {"<name>": {"binary": {"path": …}}}`
  for a language the workspace already opens; have it send `window/showDocument` with a `selection` range and then
  `workspace/applyEdit`; record whether Zed moves the caret to the line and whether the edit appears in the
  buffer's undo stack.
- Experiment B: with Zed running, `Zed.exe <file>:<line>:<column>`, then with `-e` and `-a`; record which window
  receives the caret and whether a line number survives.
- Experiment C: invoke the registered `zed://` handler with a file path with and without a line fragment (the
  CLI form is documented; the URL form on **Windows** is not).
- Experiment D: install a dev extension whose `process:exec` spawns the host; does Zed start it, keep it alive, and
  stop it with the window?
- **Gate:** a findings note committed next to this plan, naming the Zed version and the observed behaviour for
  each experiment. A is expected to pass; if it does not, Tier 1 moves to the CLI and the extension becomes the
  auto-start path instead.

> **Delivered 2026-09-25** — [`PHASE0-ZED-FINDINGS.md`](PHASE0-ZED-FINDINGS.md), with the apparatus in
> [`zed/phase0/`](zed/phase0). A **passed** and the verbs are confirmed against the installed build; B **failed**
> on Windows; C is unverified; D was not run. Two amendments to the plan come out of it, both of which the phase
> was written to catch:
>
> 1. **A config-only custom language server is impossible in 1.21.0.** Zed accepts only LSP names that an
>    extension or a built-in adapter registered ([zed#52653](https://github.com/zed-industries/zed/issues/52653),
>    closed *not planned*), so `"lsp": {"<name>": {"binary": …}}` works **only as an override for a registered
>    name** — proven here by pointing `lsp.rust-analyzer.binary` at the stub. §5.4 and §D3 are corrected above
>    and below; Phase 4's extension is now a **prerequisite for the LSP tier**, not a distribution nicety, and
>    the `settings-snippet.json` deliverable is only useful alongside it.
> 2. **`zed <file>:<line>:<column>` does not work on Windows** — `zed::zed::windows_only_instance` logs
>    `error parsing path argument … (os error 123)` for the absolute, relative, line-only and line+column forms,
>    while a plain path parses. Tier 2 therefore cannot carry navigation on Windows; it degrades to opening a
>    file, and `zed://` (C) is the only candidate for more once someone tests it.
>
> Also recorded there because they will bite the sidecar: Zed's **worktree-trust gate** delays the language
> server start; Zed asks Rust-scoped servers for **`experimental/runnables`** and a `null` reply is a visible
> error; and a **BOM** in `.zed/settings.json` makes Zed discard the whole file.

### Phase 1 — Extract `webview-core`, unify the three copies of the security model (2–3 days)
- New Maven module `webview/core/webview-core` (JDK 25, no IntelliJ/VSCode dependency), containing: path jail,
  token + allow-list auth, CORS policy, rate limiter (the existing `RateLimiter`/`Clock` move here), the
  `EditorHost` SPI, the page/file server (from `HttpBridge.ts`'s `/file/`), and `NullHost`.
- Retarget `webview-jetbrains`' `NavigatorService`/`HttpBridgeService` and `webview-vscode`'s `HttpBridge` onto it;
  keep their ports (18881 / 18882) and every existing response body.
- Move `jwa-sidecar` under `webview/`; give it the same auth on `/jump` or delete `/jump` in favour of the API.
- **Gate:** the 52 JetBrains unit tests, the VS Code suite, and `webview/examples/smoke-test.mjs` all still pass;
  a new test asserts that the three hosts answer `/health` identically and that an unauthorized `/open` is `403`
  in all of them.

> **Delivered 2026-09-25**, with two amendments to the gate as written: the JetBrains suite is **25 tests**, not
> 52, because 27 of them moved into `webview-core` (52 total coverage is preserved, split 25 + 27 and 27 more new
> ones); and the VS Code end of the gate is `npm run test:unit` — a new dependency-free run over the shared
> vectors — rather than only the VS Code integration suite, because that suite needs a VS Code download and has
> one pre-existing failure unrelated to this work (documented in `webview-vscode/README.md`). See the
> implementation record at the top of this file.

### Phase 2 — The standalone host: any page, any browser, no editor (2–3 days)
- A `webviewd` entry point: `webviewd --project <dir> [--port 0|N] [--open]`, serving the project's generated
  pages plus a manifest at `/.well-known/webview.json` (port, token path, capabilities).
- Publish the port/token in `.jcodebuddy/webview/host.json` (gitignored) and refuse to start a second instance for
  the same project.
- The `EditorHost` adapters, each reporting its own capabilities:
  * `LspHost` — speaks through an attached language server; registered here but only *complete* in Phase 5, so it
    reports `open`/`edit` as unavailable until then;
  * `ZedCliHost` — `Zed.exe <abs>` — **corrected by Phase 0:** the `:line:column` spelling and the `zed://`
    fallback are out (the CLI refuses the positional form on Windows, `os error 123`, and the URL form's
    delivery is unverified), so this adapter opens the file and reports `lineNavigation: file-only`;
  * `NullHost` — headless: every editor verb reports unavailable, writes still work.
- **Gate:** the examples' pages open in Chrome; `/health` reports the host and its capabilities; a CI job with no
  editor asserts the same pages degrade to the clipboard rather than erroring. **Amended by Phase 0:** the
  original wording also asked that "clicking a field lands the caret in a running Zed through the CLI adapter",
  which is impossible on Windows — `zed <file>:<line>:<column>` fails to parse (`os error 123`, §5.3/B), so the
  CLI adapter can open a file and nothing more. The caret half therefore moves to the LSP tier (Phase 4's
  extension + Phase 5's `LspHost`), and this phase's Zed requirement is "the CLI adapter opens the file in the
  running Zed, and `/health` says that is all it can do". The clipboard rung stays the floor for both
  `openFile`-less browsers and caret-less hosts.

> **Delivered 2026-09-25** as [`core/webviewd`](core/webviewd) — a Maven module (JDK 25, no dependencies beyond
> webview-core and JUnit) that packages a shaded `webviewd.jar` with **27 tests of its own**, all green, plus
> core's 97.
>
> What it does: binds loopback on an ephemeral port by default and publishes it in
> `.jcodebuddy/webview/host.json` (with a generated token in a sibling file, never in the descriptor); serves
> the frozen `GET /open` and `GET /health` with the shared document; serves project files at `/file/` and the
> same files with the bridge injected at `/page/`; publishes the manifest at `/.well-known/webview.json`; uses
> core's origin allow-list, token, path jail and 20-per-20s limiter rather than a second copy of them.
>
> **Verified against the binary, not only in tests:** on an ephemeral port, `/health` answered
> `{"plugin":"hr.hrg.webview.webviewd","port":…,"allowedOrigins":2,"tokenRequired":true,"bridgeVersion":1,"capabilities":["open"]}`;
> `/open` answered `403` with no credentials, `404` headless (an honest failure, not a silent no-op), `403` for a
> path escaping the project, `429` for the 21st call, `405` for a `POST`; `/file/` served the examples' real
> `site.css` and `nav-client.js` with the right content types and refused a path outside the project; `/page/`
> served the real 271-line `entity-reference.html` **with `window.openFile` injected**; CORS was echoed to the
> host's own origin and absent for a foreign one; `OPTIONS` preflight answered `204`; a second instance exited
> `2` with the conflict message; and a restart after a *hard* kill started anyway, proving a stale descriptor
> does not block (`isLive()` asks the operating system, not the file).
>
> Two decisions worth naming, both recorded in [`doc/webview-host-api.md`](doc/webview-host-api.md):
>
> 1. **The host trusts its own origin by default** (plus anything `--allowed-origins` names). An IDE host denies
>    everyone until configured; a host that *serves* the pages must trust the pages it serves, or every example
>    would need a token pasted into it. Another page on another port is still refused.
> 2. **`host.lineNavigation` exists** because `open` alone would be a half-truth on Zed: the CLI adapter reports
>    `file-only`, so `/health` and the manifest say `open` while the manifest also says the line is not applied.
>    That is Phase 0's measurement turned into an API field rather than a footnote.
>
> Still open from this phase: **`LspHost`** (Phase 5, and only reachable through Phase 4's extension), the
> `POST /api/v1/...` surface and the write verbs (Phase 3), and `--open` launching a browser — implemented with
> `Desktop.browse` and a printed URL fallback, but not exercised here because this session has no browser to
> observe. The examples' pages were fetched over HTTP and their bridge injection checked, which is the half a
> headless run can prove.

### Phase 3 — The edit verbs, headless first (3–4 days)
- Implement §6.2 in `webview-core` (digest, atomic write, line-ending preservation, checkpoints, `/undo`,
  `/redo`, `/diff` as a unified diff), with `/events` (SSE) for file changes so a page can re-render.
- Wire `edit` into the JetBrains and VS Code hosts through their native apply-edit APIs; both keep the sidecar's
  disk path as the fallback when they are not running. For Zed there is no plugin to change: the sidecar sends
  `workspace/applyEdit` over the LSP channel (confirmed handled, §5.5) and Zed applies it to its buffer, so the
  change arrives in Zed's own undo stack without the sidecar touching the file on disk.
- Page-side: a small `webview-client.js` in `examples/with-assets/assets/` implementing the full ladder, and one
  example page that edits (a rename in a table) and shows the diff before applying.
- **Gate:** (a) a stale digest is refused with `409` and no bytes change; (b) an edit inside the project applies,
  `/undo` restores the exact previous bytes (byte-compare in the test); (c) an edit whose path escapes the project
  is refused in every host; (d) applying through the JetBrains host appears in the IDE's own undo stack; (e) an
  edit sent through the LSP channel appears in Zed's buffer and is undone with Zed's own undo.

> **Headless half delivered 2026-09-25** — the write contract in `webview-core` and its HTTP surface in
> `webviewd`, with [`doc/webview-edit-api.md`](doc/webview-edit-api.md) as the reference. **Gate items (a), (b),
> (c) and — later the same day — (e) are measured**; **(d)** is not, and the phase is not finished without it.
>
> What exists: `EditService` (the one place a path becomes a write) enforces the same path jail as navigation,
> refuses a stale digest with the current digest in the answer, writes atomically through a temp file and a move,
> preserves the file's line endings *and* whether it ended with a newline, checkpoints every applied write, and
> takes its rate limit from the same limiter as navigation. `POST /api/v1/{applyEdit,diff,undo,redo}` and
> `GET /api/v1/events` expose it; the four write routes require the **token** rather than the weaker
> origin-or-token rule that `/open` uses, per D8.
>
> **Verified against the running binary, not only in tests:** a proposal returned
> `200 {applied:false, digest:<the digest the file would have>, unifiedDiff:"@@ -1,2 +1,2 @@\n one\n-two\n+TWO"}`
> and left the file untouched; accepting it wrote the file with its terminator intact; replaying the same request
> with the old digest answered `409`; `/undo` restored the file to a **byte-identical** hash; `/redo` reapplied
> it; a missing token answered `403` and a `GET` on a write route `405`; and the event stream answered
> `200 text/event-stream` and delivered `event: change data: {"paths":["src/A.java"]}` after two keep-alives.
>
> **Three things the work turned up, each now fixed and pinned by a test:**
>
> 1. **`TextEdit.lines` consumed the line terminator without returning it**, so replacing line 2 of
>    `one\ntwo\nthree` produced `one\nTWOthree`. The replacement now carries a terminator when it lacks one.
> 2. **An undo could silently discard the reader's own edit.** The smoke test edited the file after our write and
>    `/undo` restored the older checkpoint, throwing that edit away — the one thing this contract refuses to do
>    anywhere else. An undo is now digest-guarded like any other write (`409 stale`, checkpoint kept), which is
>    enforced by `CheckpointStore` remembering the digest each checkpoint is safe against.
> 3. **Windows reports a child's change against the parent directory's entry too**, so the first frame named
>    `src` rather than `src/A.java`. Directories are now registered internally but never reported to a page.
>
> **Gate (d) delivered as code the same day, with its observation outstanding.** The work it needed turned out to
> be more than the plugin: the write *conversation* — `target`, the digest guard before an editor is asked, the
> statuses and bodies — was living in `webviewd`'s `EditApi`, and a second host would have meant a second set of
> rules. It is now `webview-core`'s **`WriteSurface`**, used by `webviewd` and by the JetBrains bridge, with 8
> core tests for the routing itself. The plugin gains `IdeDocumentEditor` + `WriteCommandEditor` (document text
> set inside `WriteCommandAction.runWriteCommandAction` on the EDT, never saved), `DocumentEdits` (the resulting
> text, a pure function over core's `EditableText`, so the buffer and disk halves cannot disagree about where an
> edit goes — **5 unit tests**), `NavigatorService` declaring `edit` and delegating, and the token-only
> `/api/v1/applyEdit|diff|undo|redo` routes on its bridge, sharing the project's rate limiter. The plugin's suite
> is 30 tests, all green under Gradle (`gradlew test`), and `webview-core` must be `install`ed to `mavenLocal`
> before that build, which its `build.gradle.kts` says in as many words. **Gate (d) was then observed on
> 2026-09-26** — JetBrains 2026.2.3 through `gradlew runIde`, reported by the maintainer: the host answered
> `"target": "buffer"`, the bytes on disk were unchanged when it answered, the replacement appeared in the editor,
> and the IDE's own undo restored the original text. The file reached disk minutes later because **IntelliJ saved
> it itself** on frame deactivation — the editor saving, which the contract allows and describes — and the
> observation tool now samples the file twice (`--watchSeconds`) so that distinction is evidence rather than
> inference. Steps and expected output for a repeat:
> [`doc/ide-observation-checklist.md`](doc/ide-observation-checklist.md).
>
> **Also still open in this phase:** VS Code's `WorkspaceEdit` path — that host declares no `edit`, so a page
> asking it for a buffer edit is refused rather than written to — and checkpoint persistence across restarts, which
> is documented rather than hidden.
>
> **VS Code's buffer path followed the same day**, so that note is history rather than a gap:
> `decideWriteRoute` / `decideEdit` / `parseEditRequest` / `mapEdits` in `BridgePolicy.ts` decide, the bridge makes
> the one `vscode.workspace.applyEdit` call, and `/health` advertises `edit`. Its surface is narrower by design —
> a disk write is the file owner's job (its atomic replace, its checkpoints in core's `EditService`), while this
> host has the editor's own undo — so `target: "disk"`, `/diff`, `/undo` and `/redo` are refused with
> `409 no-disk-write`, naming `webviewd`. `npm run test:unit` is **161 assertions**, all green, with no VS Code
> download. **And it is observed, 2026-09-26**, in an Extension Development Host: the edit landed in the editor's
> buffer, the file was not saved, and the editor's own undo/redo moved it back and forth. Two host-side fixes had
> to happen first, both of which are the kind of thing only a real run finds: `webviewExplorer.token` did not
> exist, so every write route refused everything with 403 while `/health` advertised `edit`; and the extension had
> no `activationEvents`, so it was never activated and port 18882 was closed with nothing to explain why. Both
> IDE hosts are therefore observed now; the JetBrains note above records its own run.
> observation and is not claimed here.
>
> **Gate (e) was delivered and OBSERVED the same day**, once the LSP write transport existed: the sidecar now
> sends `workspace/applyEdit` (`documentChanges`, zero-based ranges, `version: null`), `webviewd` routes an
> `applyEdit` to the buffer whenever the attached host declares `edit` (with `target: auto|buffer|disk` to make
> the destination explicit, and `409 no-buffer-edit` rather than a silent fall back when a page asks for a
> buffer no host can provide), and the digest guard still runs *before* any editor is asked. On Zed 1.21.0 the
> maintainer observed the requested line appear in `Sample.rs` as an **unsaved** change, and **one `Ctrl+Z`
> removed it and left the tab clean** — the editor's own undo stack, with the file on disk byte-identical
> throughout. The two-process hop needs the sidecar's token, like the navigation hop, which is another argument
> for question 4's one-process answer.

### Phase 4 — ZED tier 3: the extension, so Zed can address the host at all (2–3 days, promoted by Phase 0)
- `webview/zed/webview-zed-dev-extension/`: `extension.toml` + a minimal Rust crate that (i) **registers the
  language-server id** so `lsp.<id>.binary` in settings becomes legal (§5.4 — without this, Zed ignores the name
  entirely) and (ii) declares the language-server entry so Zed starts `webviewd --lsp` for the workspace, and/or
  (iii) uses `process:exec` to launch the standalone host, with `granted_extension_capabilities` documented in the
  README. The cheap registration-only shape is
  [zed-customlsp](https://github.com/zhcn000000/zed-customlsp): register the id, return the command, let settings
  supply the binary.
- Ship `settings-snippet.json` for users who have the extension but want to point it at their own build — **not**
  as a substitute for it: Phase 0 proved a snippet alone starts nothing.
- **Gate:** installed as a dev extension, opening the project starts the host without a terminal, the port is
  discoverable from Zed's side, and closing the window stops it. Documented as *experimental*, with the exact Zed
  version it was verified against.

> **Registration half delivered 2026-09-25**, and it is the half Phase 0 proved was missing:
> [`zed/webview-zed-dev-extension`](zed/webview-zed-dev-extension) — `extension.toml` registering
> `[language_servers.webview-sidecar]` for Java plus a `language_server_command` that resolves
> `JCB_WEBVIEW_SIDECAR` → `webviewd --lsp` → `JCB_WEBVIEW_JAVA`/`$JAVA_HOME/bin/java` `-jar
> webview/jwa-sidecar/target/jwa-sidecar.jar`. It uses **no** `process:exec` (Zed spawns the command; the
> extension never does) and it renders nothing (no Zed extension can). **Observed on 1.21.0, Windows, by the
> maintainer:** installed as a dev extension; opening a `.java` file made Zed log
> `starting language server process. binary path: "C:\Program Files\Java\jdk-25\bin\java.exe", args: ["-jar",
> …\webview\jwa-sidecar\target\jwa-sidecar.jar"]`; the sidecar stayed up (`java -jar …jwa-sidecar.jar` visible as
> a live process) and its jump service answered — **port 7979 listening** — which is Tier 1's registration
> problem actually solved, with no adapter hijacked and no settings-only trick. The `lsp.webview-sidecar.binary`
> override was needed for the JDK: with the default chain (PATH `java` 1.8 / `JAVA_HOME` 21) Zed logged
> `Failed to start language server "webview-sidecar"`, which is the loud, documented failure the README predicts,
> not a silent one. Also checked while it ran: no Zed-side deserialization errors were attributed to our server
> and jdtls kept running, so registering for Java adds a server rather than displacing one.
> **Still open from this gate:** `process:exec` for launching the standalone host when no file of the
> registered language is opened at all — the language-server route only starts the host once such a buffer
> exists, which is exactly the gap the CLI/URL tier was supposed to cover and Phase 0 showed it cannot cover
> positions. **Closed later the same day:** quitting Zed stopped the sidecar (its `java -jar …` process was gone
> and port 7979 refused connections), so "closing the window stops it" is measured rather than assumed — Zed owns
> the child process.

### Phase 5 — LSP sidecar parity, the ZED LSP host, + ACP exploration (2–3 days, partially spiked)
- Finish `LspHost` (the send side, started in Phase 2): `window/showDocument` for navigation and
  `workspace/applyEdit` for edits, with the reference host being the sidecar itself. Answer
  `experimental/runnables` with an array and tolerate Zed's worktree-trust delay (Phase 0).
- Confirm behaviour per host — Zed (verbs confirmed in Phase 0; registration needs Phase 4's extension), JetBrains
  via `LSP4IJ`, VS Code, Neovim — and record the support matrix in `webview-host-api.md`. Keep the JWA builder
  code actions working. **Corrected 2026-09-25:** there is no `jwa-sidecar.txt` addon-file mechanism to keep
  working (it was never implemented, see the Phase 1 record); if it is wanted, it is new work — a classloader
  over the listed GAVs and paths plus an SPI for what an addon contributes, per
  [`jwa-sidecar/modules.md`](jwa-sidecar/modules.md).
- **Gate (the request's headline case):** with the sidecar registered as Zed's language server — via Phase 4's
  extension, since Phase 0 proved settings alone cannot register it — and **no CLI invocation at all**, clicking a
  location link in the browser window beside Zed moves Zed's caret to the line; and an `applyEdit` from the page
  appears in Zed's buffer and is undone by Zed's own undo. (Phase 0 measured the first half and left the undo
  half unobserved; this gate must not treat it as already proven.)

> **The gate's first half was delivered the same day as a spike, and it is measured:** a page's
> `GET /open?filePath=…&line=7&column=5` on `webviewd` (`--host lsp`) reached Zed's caret on line 7, and a second
> request opened a different file at line 8 — with **no CLI process involved**. The chain is page → `webviewd` →
> the sidecar's `/jump` → LSP `window/showDocument` with a selection → Zed. `webviewd`'s manifest reported
> `"host": {"name": "lsp", "lineNavigation": "exact"}` and its `/health` reported `["open","select"]`, both read
> from the sidecar's own capability document.
>
> **The spike earned its keep by finding a bug that would have made the gate a lie.** `SidecarApp` parsed
> `/jump`'s `line` and `column` and passed them to `JwaLanguageServer.jump`, which **ignored them** and delegated
> to `Navigator.openUrl(uri)` — a helper that takes the line from a `#L42` fragment and otherwise defaults to 1.
> The observed symptom was exactly the confusing half-success this phase exists to catch: the file opened and the
> caret stayed at the top. It survived Phase 1's tests because those tests only asked the endpoint questions it
> answers with `403`/`NO_HOST`; it was caught by a diagnostic that speaks LSP *as Zed* and logs what the sidecar
> actually sends (kept as [`jwa-sidecar/tools/fake-zed-client.mjs`](jwa-sidecar/tools/fake-zed-client.mjs)), and
> it is now pinned by `SidecarJumpPositionTest`, which asserts both the `mytool/jump` notification and the
> zero-based `showDocument` selection. The fix also settled `openUrl`'s role: the explicit query position wins,
> the fragment stays the fallback for a caller that only had a link.
>
> **Two more things the spike measured, both now part of the design:**
>
> 1. **The sidecar's `/health` was lying**, advertising `jump` and `showDocument` before any client had
>    completed `initialize`, when `/jump` could only answer `NO_HOST`. It now reports the contract's verb keys
>    (`open`, `select`) only while a client is attached, and `webviewd`'s LSP adapter reads that document —
>    re-reading it after a short TTL, because Zed attaches and detaches without telling anyone. Without this,
>    `--host auto` would have preferred a sidecar that could not act.
> 2. **A two-process deployment needs a shared secret.** The sidecar denies unconfigured callers (Phase 1's
>    deliberate fix), so `webviewd` must be given the sidecar's token (`--sidecar-token`, defaulting to the
>    `jwa.sidecar.token` system property) and the sidecar must be spawned with it. That is the strongest argument
>    yet for question 4's one-jar/one-process answer: a single process serving pages and holding the LSP
>    connection needs no handshake at all. The version in the source tree today is two processes, and the hop
>    between them is a plain loopback HTTP call to `/jump`.
>
> **Still open from this gate:** the `applyEdit` half (Phase 3 owns the write contract; the LSP transport for it
> is Phase 5), the undo observation, and `process:exec` for starting a host when no buffer of the registered
> language is open. The spike also had to pass Zed's **worktree-trust gate** in a fresh data directory — a
> language server does not start until the worktree is trusted, which the isolated run had to allow explicitly
> (`session.trust_all_worktrees` in the instance's own config), and which no host may assume away.
- ACP: a timed spike (≤1 day) that registers `webviewd --acp` as a **custom agent** (`agent_servers:
  {"<id>": {"type": "custom", "command": …, "args": ["--acp"], "env": {}}}`), answers `initialize` / `session/new`
  / `session/prompt` and exposes one tool that calls the same command layer as `/api/v1` (using the official Java
  ACP library). Establishes whether the Agent Panel is a useful *additional* surface. **No commitment** — Zed
  deprecated the extension form of this, ACP cannot render the page (§5.6), and the webview does not need it.
  The spike ends in a written go/no-go.

### Phase 6 — Headless parity as a build gate (1–2 days)
- Extend `examples/smoke-test.mjs` (CDP, no dependencies) to drive **every** verb against a headless `webviewd`
  and assert the result; add the JetBrains/VS Code capability declarations to the same test so a host that
  declares a verb it does not implement fails the build.
- **Gate:** "headless lacks nothing" is a test result. The README's claim of parity links to the test that proves
  it.

## 8. Acceptance criteria (whole plan)

1. `webview/webview-core` exists, is the only implementation of auth/CORS/rate-limit/path-jail, and every host
   answers `/health` with a capability list.
2. A page served by `webviewd` from a plain browser performs: open at line, reveal, select, applyEdit, diff,
   undo, and receives file-change events — with no editor installed.
3. In JetBrains and VS Code the *existing* behaviour is unchanged (same ports, same responses, existing tests
   green).
4. In Zed 1.21.0 on Windows, clicking a location link in a browser window beside Zed moves Zed's caret to that
   line via the LSP channel (`window/showDocument`), and the CLI adapter produces the same result when no
   language server is attached — both observed, not inferred.
5. `applyEdit` refuses a stale digest, refuses a path outside the project, is atomic, and is undoable byte-for-byte.
6. The sidecar no longer answers any state-changing route with `Access-Control-Allow-Origin: *`.
7. `jwa-sidecar` lives under `webview/`, builds from the parent POM, and its JWA code actions still exist —
   the "Sync Builder" action in `JwaTextDocumentService`, which has no test yet.
   **Corrected 2026-09-25:** the criterion also asked that "addon-file loading still work"; there is no such
   loading, and there never was (no code in any language references `jwa-sidecar.txt`, in the whole history), so
   that half of the criterion was unachievable as written. The sidecar's `pom.xml` depends on `jwa-builder`
   directly, which is what the "reference implementation" in its README actually describes.
8. Every capability claim in the READMEs is covered by a test or a dated, version-named verification note.

## 9. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Zed's `ShowDocument`/`ApplyWorkspaceEdit` handlers differ in the released 1.21.0 from `main` | Tier 1 degrades to Tier 2 | Phase 0 observes them on the installed build before anything depends on them; the CLI/`zed://` adapter needs no LSP cooperation, only the documented CLI/URL surface |
| A language server only runs when a matching file type is open, so a page opened before any file is touched has no channel | Navigation silently unavailable | Capability-aware ladder: the CLI adapter is the fallback, and `/health` says which host is live |
| Zed extension capability policy changes or is restricted by the user | Auto-start stops working | Tiers 1 and 2 avoid extensions entirely; all three paths documented |
| A free port is discovered by another local process / CSRF from a random web page | File writes triggered by a hostile page | Token required on every state-changing route, loopback bind, `Origin`-less requests refused unless the token is presented, rate limit, and the digest guard on writes |
| The write API is the wrong interpretation of "trigger file changes" | Rework of Phase 3 | Phase 3 is deliberately after Phases 1–2, and §10 Q1 asks before it starts |
| Moving `jwa-sidecar` breaks the JWA/JSWA clients and CI | Broken builds | The move is its own commit with a reactor build + the JWA client smoke path; the POM `relativePath` is the only path that changes |
| Gradle needs `C:\Users\hrg\.gradle`, JDK 25 is not the default `java` | Host plugin builds fail confusingly | Every command in the phase notes pins `org.gradle.java.home` / `JAVA_HOME=…jdk-25`; already pinned in `gradle.properties` |

## 10. Open questions (answer before the phase that needs them)

1. **What does "trigger file changes" mean?** ANSWERED 2026-09-25 — **(c) then (a)**: the page asks for a
   proposal, the host answers with a unified diff (and, when it can, shows it in the editor's own diff view), and
   the write to disk happens only when the reader accepts it. (b) additionally applies when the host declares
   `edit`: the change goes into the editor's buffer and its undo stack rather than to disk. (d) — running the JWA
   builder from a page — is *not* this verb; it stays a separate tool face (Phase 5's ACP spike is where it
   belongs, if anywhere). Phase 3 may start, and it must implement the diff step as the default flow rather than
   as an option.
2. **Which hosts are in scope for the write verbs?** JetBrains + VS Code + headless, or headless only for now?
3. **Should the JetBrains and VS Code plugins become proxying adapters** (talk to `webviewd` for everything) or
   keep their in-process implementations and merely share the extracted core? This plan assumes shared core, which
   is the smaller change; proxying is a later option that would make the "one implementation" claim absolute.
4. **Is `webviewd` a separate binary** (shaded jar, like `jwa-sidecar` today) **or the same jar as the LSP
   sidecar** started with a flag (`--host`, `--lsp`, `--acp`)? This plan assumes one shaded jar, three modes.
   **Interim answer, 2026-09-25:** Phase 2 shipped it as its own module with its own shaded `webviewd.jar`,
   because the sidecar already exists as its own jar and neither entry point needed the other's dependencies.
   Folding them together is now a packaging decision rather than a design one, and nothing in `webviewd`
   prevents it (its `main` takes flags already). **The LSP spike then argued for folding:** with two processes,
   navigation from a page needs a shared secret configured at the sidecar's spawn *and* passed to `webviewd`
   (see the Phase 5 record), because the sidecar refuses unconfigured callers by design; one process holding the
   HTTP surface and the LSP connection would need no handshake, no `/jump` hop and no token in two places.
5. **Which of `vscode-jwa`/`vscode-jswa`/`intellij-jwa`/`intellij-jswa` also move under `webview/`?** They are
   sidecar clients and would use the same host adapters, but they belong to the JWA/JSWA products. This plan moves
   only `jwa-sidecar`.

## 11. Verification commands (for whoever implements this)

```bash
# core + sidecar (JDK 25; the shell default java is 8 and JAVA_HOME is 21)
JAVA_HOME="C:/Program Files/Java/jdk-25" mvnd -q -pl webview/core/webview-core,webview/jwa-sidecar -am verify
# the standalone host: its own 27 tests plus core's 97
JAVA_HOME="C:/Program Files/Java/jdk-25" mvnd -q -pl webview/core/webviewd -am verify
# run it: port 0 publishes an ephemeral port in .jcodebuddy/webview/host.json, and the token file sits beside it
"C:/Program Files/Java/jdk-25/bin/java.exe" -jar webview/core/webviewd/target/webviewd.jar \
    --project <dir> --port 0 --host auto
# then: GET /health, GET /.well-known/webview.json, GET /page/<percent-encoded absolute path>?token=<from the token file>
# pages, all four hosts' contracts, headless parity
node webview/check-links.mjs
node webview/examples/smoke-test.mjs
# JetBrains host (Gradle needs write access to C:\Users\hrg\.gradle)
cd webview/webview-jetbrains && ./gradlew test buildPlugin
# VS Code host
cd webview/webview-vscode && npm test
# ZED (Windows). Run these from a normal shell, not from an agent session: a second process cannot reach a
# running Zed's CLI endpoint there ("error connecting to cli: Access is denied"), and `--user-data-dir` gives an
# isolated instance whose logs are readable.
"C:/Users/hrg/AppData/Local/Programs/Zed/bin/Zed.exe" --version    # expect 1.21.0
"C:/Users/hrg/AppData/Local/Programs/Zed/bin/Zed.exe" <file>       # opens; NO position suffix works on Windows
# Phase 0 apparatus (see webview/zed/phase0/README.md)
node webview/zed/phase0/self-test-client.mjs                        # prove the stub, no editor needed
node webview/zed/phase0/probe-zed-db.mjs "Sample"                   # what Zed persisted for a buffer/caret
```

## 12. Suggested order of work, in one line

Phase 0 → Phase 1 → Phase 2 → *(answer Q1)* → Phase 3 → Phase 4 → Phase 5 → Phase 6.
Phases 1 and 2 deliver the request's two hard parts (a pluggable sidecar webview, and a side-by-side ZED window
that can drive the editor) without touching the frozen page contract or either working IDE host.
Phase 0 has since shown that **Phase 4's extension is what makes Zed able to address the sidecar at all** (§5.4,
A′), so it is no longer the last ZED phase but a precondition for Phase 5's gate; and that on Windows the CLI can
open a file but cannot place a caret (§5.3, B), so nothing may depend on Tier 2 for navigation.
