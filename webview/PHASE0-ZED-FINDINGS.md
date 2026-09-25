# Phase 0 findings — what Zed 1.21.0 on Windows actually does

Written: 2026-09-25. Companion to [PLAN-webview-suite.md](PLAN-webview-suite.md) § 7 (Phase 0) and its gate.
Build under test: **Zed 1.21.0+stable.362.33c95853ed2b6956f339733c63a8220964ecbeb6** (`Zed.exe --version`),
Windows, workspace `D:\wrk\java\jcodebuddy`. Zed's own log and sqlite databases were read for evidence; the
stub server, its self-test and the DB probe are in [`zed/phase0/`](zed/phase0).

**Two of the plan's assumptions did not survive contact with the installed build**, and one of them changes the
tier order in §5.4/§D3:

| # | Experiment | Result | Consequence for the plan |
| --- | --- | --- | --- |
| A | `window/showDocument` (with selection) and `workspace/applyEdit` over LSP | **PASSED** — both executed; the edit landed in Zed's buffer and its undo stack, not on disk | §5.5's source reading holds for the installed build. Tier 1's *verbs* are real |
| A′ | Registering the sidecar as "a custom language server in configuration only" | **IMPOSSIBLE in 1.21.0** — Zed only accepts LSP names an extension or built-in adapter registered | §5.4 and Phase 4's `settings-snippet.json` are wrong as written; the extension is a **prerequisite**, not an auto-start nicety |
| B | `zed <file>:<line>:<column>` (the documented CLI form) on Windows | **FAILED** — `error parsing path argument … (os error 123)` | Phase 2's `ZedCliHost` cannot set a caret this way on Windows |
| C | `zed://file/<path>` URL handler | **PARTIAL** — handler present, URL parses; whether the file reaches a *running* window is unverified | Navigation fallback is probably the URL form, pending one check |
| D | Dev extension with `process:exec` | **NOT RUN** — `cargo`/`rustup` are installed; installing a dev extension is a UI action | Carried forward; Phase 4 owns it |

---

## A — the LSP verbs work, and an edit lands in Zed's buffer (verified)

**How it was observed.** Rather than the user's live Zed, an *isolated* instance was used
(`Zed.exe --user-data-dir target/zed-phase0 D:\wrk\java\jcodebuddy …`), with the project's `.zed/settings.json`
pointing a **registered** adapter at the stub: `lsp.rust-analyzer.binary.path` →
`C:\nvm4w\nodejs\node.exe webview/zed/phase0/stub-lsp.mjs`. Zed's log confirms it took the override:

```
[project::lsp_store] Worktree "D:\wrk\java\jcodebuddy" is trusted, starting language server rust-analyzer
[lsp] starting language server process. binary path: "C:\nvm4w\nodejs\node.exe", working directory: "D:\wrk\java\jcodebuddy",
      args: ["…\stub-lsp.mjs", "--log", …, "--file", "…\Sample.rs", "--line", "8", "--column", "5", "--edit-line", "1"]
```

**What Zed advertised** (from the stub's log of the real `initialize` request, i.e. the installed build — not
Zed's source on `main`):

```
CLIENT CAPABILITIES window.showDocument = {"support":true}
CLIENT CAPABILITIES workspace.applyEdit = true
CLIENT INFO {"clientInfo":{"name":"Zed","version":"1.21.0+stable.362.33c95853ed2b6956f339733c63a8220964ecbeb6"}}
```

**What happened when the stub exercised them** (`target/phase0-stub-rs.log`):

```
-> window/showDocument file:///…/scratch/Sample.rs at 8:5 (takeFocus=true)
<- window/showDocument response {"result":{"success":true}}
-> workspace/applyEdit inserting "// phase0-applyEdit 2026-09-25T14:58:30.267Z" at line 1 of file:///…/Sample.rs
<- textDocument/didChange          ← Zed told the server the buffer changed
<- workspace/applyEdit response {"result":{"applied":true}}
```

**Buffer, not disk — proven three ways.** (1) `applied: true`; (2) Zed sent `textDocument/didChange` back; (3)
Zed's own database (`target/zed-phase0/db/0-stable/db.sqlite`, table `editors.contents`) holds the modified
buffer with the marker as **line 1**, while `git`-visible bytes on disk still begin with the original first line.
So §6.2's asymmetry — "the host applies the edit to its buffer so the user sees it in the editor's undo stack;
otherwise the sidecar writes to disk" — is **observed behaviour**, not a guess.

**Caret and undo.** The persisted buffer proves the *edit*; the caret and the undo stack are visual claims the
plan asks to be *recorded*, so they are recorded from the maintainer's observation, not inferred:

> _Observation (maintainer, 2026-09-25): "there were more than one window, in one of them it was ok" — the caret
> and the marker were as asked in the window that ran the `rust-analyzer` override. The undo half was not tried,
> and one window (the one the `zed://` invocation started) showed only `Sample.java`, with no caret target._

**A real sidecar must answer `experimental/runnables`.** Zed asks any Rust-scoped adapter for it and expects an
array; the stub's generic `null` reply produced
`ERROR [lsp] failed to deserialize response from language server: invalid type: null, expected a sequence` and
`LSP Runnables via rust-analyzer failed`. A `webviewd --lsp` that answers `null` to unknown requests will be
visibly broken in Zed's UI, so this belongs in `webview-host-api.md` once that document exists.

## A′ — a config-only custom language server is not possible in Zed 1.21.0 (the headline correction)

§5.4 of the plan states that "a custom language server can be configured without publishing an extension" via
`"lsp": {"<name>": {"binary": {"path": …}}}`. On 1.21.0 that is false. Zed validates the names in `lsp` against
the adapters an extension or the built-in set has registered, and silently drops the rest.

Evidence, in increasing order of authority:

1. **Our own test.** `.zed/settings.json` registering `jcb-phase0-md` for `Markdown` (a built-in language) and
   `jcb-phase0-md.binary.path` → `node stub-lsp.mjs`, then `Sample.md` opened in the user's Zed: **the stub never
   started** (`target/phase0-stub-md.log` was never created; Zed's log contains no `[lsp]` line for it). That the
   file *was* opened is proven by Zed's DB (`editors` row + `recent_navigation_history` row for `Sample.md`), so
   this is a rejection, not a missing action.
2. **The same settings shape against a registered name works.** `lsp.rust-analyzer.binary.path` → `node
   stub-lsp.mjs` did start the stub (section A). So project settings, `lsp` blocks and `binary.path` are all read;
   only the *unregistered name* was ignored.
3. **Zed's maintainers say so.** [issue #52653](https://github.com/zed-industries/zed/issues/52653), reported by a
   user attempting exactly this shape for `taplo` ("`property taplo is not allowed`", server never starts), closed
   **not planned**: *"You will need to create an extension that provides the language server"* (maxdeviant) and
   *"Zed currently only allows LSP names that an extension or built-in adapter has registered"* (yeskunall). The
   reporter notes older Zed versions allowed it, and a commenter publishes the working workaround —
   [zed-customlsp](https://github.com/zhcn000000/zed-customlsp): a *registration-only* extension whose
   `extension.toml` declares `[language_servers.<id>]` and whose `language_server_command` returns an empty
   command, after which `lsp.<id>.binary` in settings supplies the real binary.

**What this means for the plan.**

- **Tier 1 as written does not exist.** There is no "settings snippet and nothing else" path to make `webviewd
  --lsp` a Zed language server. Phase 4's `settings-snippet.json` deliverable is unreachable without the
  extension, so the extension moves from "Tier 3, distribution" to "the one thing that makes Tier 1 possible".
  The cheap form is `zed-customlsp`'s: an extension that registers the id and returns the command, with the
  binary path left to settings.
- **Phase 5's gate** ("with the sidecar registered as Zed's language server and no CLI invocation at all") is
  reachable, but only *after* that extension exists — so Phase 5 cannot be the phase that discovers it.
- **Hijacking a built-in adapter is not a product route.** It works (section A proves it for `rust-analyzer`), but
  the only registered adapter for a Java project is the Java extension's `jdtls`, and replacing it would take the
  project's real Java language server away. Fine for an experiment, wrong as a design.
- **The trust gate is real.** The isolated instance logged `Worktree "…" is not trusted` → `Waiting for worktree
  "…" to be trusted, before starting language server rust-analyzer` → `is trusted, starting…`. A host must not
  assume the language server starts the moment a project opens; `/health`'s capability list has to tolerate
  `LspHost` being absent, which is what §6.1 already does.
- **A BOM kills project settings.** While the settings file briefly had a UTF-8 BOM (PowerShell's
  `Set-Content -Encoding UTF8` writes one), Zed logged
  `ERROR [project::project_settings] Failed to set local settings in ".zed/settings.json": expected value at line 1
  column 1` and ignored the whole file. Any snippet this project ships must be BOM-free.

## B — the documented CLI position syntax fails on Windows (verified)

```
zed <abs>\Sample.java          → parses, no position
zed <abs>\Sample.java:8        → ERROR [zed::zed::windows_only_instance] error parsing path argument: … (os error 123)
zed <abs>\Sample.java:8:5      → same
zed <rel>\Sample.java:8:5      → same
```

`os error 123` is `ERROR_INVALID_NAME`, and the error is logged by Zed itself, so the argument arrived intact and
Zed's Windows path parser rejected it. The [CLI reference](https://zed.dev/docs/reference/cli) documents
`zed myfile.txt:42:10` without a platform caveat.

**Consequence.** Phase 2's `ZedCliHost` must not build its navigation on the positional form: on Windows it can
open a file (`zed <abs>`, `-e`, `-a`) but cannot place a caret. Navigation must go through the LSP tier, and the
CLI/URL tier degrades to "open the file". The plan's risk table row ("Tier 1 degrades to Tier 2") therefore
degrades further on Windows: **Tier 2 cannot carry navigation at all**, only opening.

## C — the `zed://` handler is registered and parses; delivery is unverified (partial)

`HKCU\Software\Classes\zed\shell\open\command` = `"C:\Users\hrg\AppData\Local\Programs\Zed\Zed.exe" "%1"`.
Issuing `zed://file/D:/…/Sample.java` and `zed://file/…/Sample.java:8:5` through the shell produced **no path-parse
error** (unlike B) and did launch Zed; one instance afterwards held `Sample.java` open in its database. What could
not be established from the agent's session is whether the URL is delivered to an *already running* window: the
CLI's hand-off path fails there with `ERROR [crates/zed/src/main.rs:998] error connecting to cli: Access is
denied. (os error -2147024891)`, so a second instance is started instead. The plan's own claim that the URL form
works on Windows (§5.3) is therefore **unconfirmed**, and the position fragment's meaning (does Zed read
`:8:5` in a URL?) is untested.

## D — dev extension: written, installed and observed the same day

`cargo` and `rustup` are installed (`C:\Users\hrg\.cargo\bin`), and the `wasm32-wasip2` target is the one Zed
compiles extensions for. The extension was written and installed the same afternoon — because A′ put it on the
critical path — and the result is in the follow-up section below. What is still open from this experiment is only
the "closing the window stops it" half.

## Follow-up, same day: the registration gap is closed (verified)

Phase 4's registration half was written the same afternoon, because A′ made it the critical path:
[`zed/webview-zed-dev-extension`](zed/webview-zed-dev-extension) registers the language-server name
`webview-sidecar` for Java. Observed on the same 1.21.0 build, with the maintainer installing it as a dev
extension and opening a `.java` file:

- Zed logged `starting language server process. binary path: "C:\Program Files\Java\jdk-25\bin\java.exe", …,
  args: ["-jar", "…\webview\jwa-sidecar\target\jwa-sidecar.jar"]` — i.e. **an extension-registered name is
  accepted**, which is the mirror image of A′'s rejection.
- The sidecar stayed up (`java -jar …jwa-sidecar.jar` alive as a process) and its HTTP face answered:
  **port 7979 listening**. So the sidecar is reachable from Zed with no CLI invocation and no adapter hijack.
- Adding `lsp.webview-sidecar.binary` (the override proven in §A) was needed for the JDK: with the extension's
  default chain — PATH `java` is 1.8 here, `JAVA_HOME` is 21, the jar needs 25 — Zed logged
  `Failed to start language server "webview-sidecar"` instead of starting something broken. Loud failure, as the
  README predicts.
- No Zed-side deserialization errors were attributed to our server while it ran, and jdtls started alongside it:
  registering for Java *adds* a server rather than displacing the project's real one.
- The extension needs no `process:exec` capability (it returns a command; Zed spawns it) and renders nothing.

Still unobserved: that closing the Zed window stops the sidecar — Zed owns the child process, so it should, but
the plan's gate asks for observation and this note does not claim it.

## Spike follow-up, same day: the caret path over LSP, and the bug it exposed

The extra session this note's sections A and B made possible: drive `webviewd` → the sidecar → Zed and watch
the caret. It works, and it was broken in a way no test in the repository noticed.

- **Measured, end to end:** `webviewd --host lsp` answered a page's
  `GET /open?filePath=…/Sample.rs&line=7&column=5` with `200`, and Zed's caret was observed **on line 7**; a
  second request opened `Sample.java` **at line 8**. No CLI process was involved: the path is page → `webviewd`
  → the sidecar's loopback `/jump` → LSP `window/showDocument` (with a selection) → Zed, which is §5.5's
  mechanism and §6.3's intended shape.
- **The bug it found.** `SidecarApp` parsed `/jump`'s `line`/`column` and passed them to
  `JwaLanguageServer.jump`, which dropped them and called `Navigator.openUrl(uri)` — a helper that reads the line
  from a `#L42` fragment and otherwise defaults to 1. A fake client speaking LSP as Zed recorded the evidence:

  ```
  -> HTTP GET /jump?uri=…Sample.rs&line=7&column=5
  <- NOTIFICATION mytool/jump          {"line":1,"column":1}
  <- REQUEST  window/showDocument id=1 selection={"start":{"line":0,"character":0},…}
  ```

  i.e. the file opened and the caret went to the top — the half-success the live observation showed. The
  diagnostic is kept as [`jwa-sidecar/tools/fake-zed-client.mjs`](jwa-sidecar/tools/fake-zed-client.mjs)
  (it answers the sidecar's `showDocument` request and logs every message), and the fix is pinned by
  `SidecarJumpPositionTest`, which asserts both the notification and the zero-based selection.
- **The sidecar's `/health` was over-promising.** It advertised `jump` and `showDocument` before any client had
  completed `initialize`, while `/jump` could only answer `NO_HOST`. It now reports the contract's keys
  (`open`, `select`) only while a client is attached — which is also what lets `webviewd`'s LSP adapter decide,
  honestly and without a socket of its own, whether navigation may be offered.
- **The trust gate blocks automation.** A *fresh* data directory saw
  `Worktree "…" is not trusted` → `Waiting for worktree … before starting language server rust-analyzer`, and the
  server did not start until the isolated instance allowed it (`session.trust_all_worktrees` in that instance's
  own config directory). Anything that assumes "open the project and the host is up" is wrong on first run.
- **Two processes cost a handshake.** Because the sidecar refuses unconfigured callers (deliberately), the run
  needed the sidecar spawned with `-Djwa.sidecar.token=…` *and* `webviewd` given `--sidecar-token`. One process
  holding both faces would need neither — see the plan's question 4.

Still unobserved in this whole note: that a `workspace/applyEdit` arriving from the sidecar is undone by Zed's
own `Ctrl+Z` (the buffer half is measured; the undo half is not), and the `zed://` URL form's delivery to a
running window.

## Reproducing any of this

```powershell
# 0. prove the stub itself (no editor involved): 5/5 checks
node webview/zed/phase0/self-test-client.mjs

# 1. A — an isolated Zed instance, project settings pointing a registered adapter at the stub
& "C:\Users\hrg\AppData\Local\Programs\Zed\bin\Zed.exe" --user-data-dir <repo>\target\zed-phase0 `
    <repo> <repo>\webview\zed\phase0\scratch\Sample.rs
#    then read target\phase0-stub-rs.log (wire) and target\zed-phase0\logs\Zed.log (Zed's side)

# 2. read what Zed persisted for that buffer and caret
node webview/zed/phase0/probe-zed-db.mjs "Sample"      # default instance
node target/phase0-selections2.mjs <repo>\target\zed-phase0 "Sample"
```

## Observation (the plan's gate asks for names, versions and observed behaviour)

- **Caret after `window/showDocument` (line 8, column 5 of `scratch/Sample.rs`): OBSERVED OK** — maintainer,
  2026-09-25, in the window driven by the `rust-analyzer` override ("there were more than one window, in one of
  them it was ok"). Several Zed windows were open at the time, so the note is a per-window statement, not a
  screen recording; the wire response (`{"success":true}`) and the persisted buffer corroborate it.
- **`// phase0-applyEdit …` present as an unsaved change at line 1: PRESENT, proven from Zed's own database**
  (`editors.contents`). **Removed by a single `Ctrl+Z`: NOT OBSERVED** — the maintainer did not try the undo, so
  the undo-stack half of Phase 3's gate (d)/(e) is still an assumption, not a measurement.
- **`zed://` delivery to a running window on Windows: UNVERIFIED by decision** (2026-09-25) — the handler exists
  and the URL parses, but neither the agent's session nor a maintainer run established whether an *already
  running* window receives the file, or what a `:8:5` fragment means in a URL. Treat §5.3's URL claim as
  unconfirmed and do not build Phase 2's fallback on it until it is tested.
