# The host API — what a page may call, and what each host promises

Status: **written 2026-09-25 with Phase 2** (`webviewd`), **extended the same day by the LSP navigation
spike**, which made `--host lsp` real and found the position bug that had been silently dropping `line` and
`column`. The read/navigate half is implemented and tested; the write half is specified here and implemented in
Phase 3. This document is the *full* contract the hosts converge on;
[`webview-link-api.md`](webview-link-api.md) remains the **frozen** subset every existing page relies on and
nothing here changes it.

- The frozen subset: `window.openFile(path, line, column)`, `data-open`/`data-line`, `GET /open`,
  `GET /health`, the auth rules, and the `/file/` route.
- What this document adds: the capability document, the manifest, the descriptor, the page route, and the
  verbs Phase 3 fills in.

## 1. Verbs

The reference host is `webviewd`. A verb exists only when the reference host implements it; an editor host
declares which verbs it implements right now, and a page decides its rung from that declaration rather than by
calling and watching something fail.

| Verb | HTTP | Capability key | Implemented in |
| --- | --- | --- | --- |
| open a file at a line | `GET /open` (frozen) | `open` | `webviewd` (LSP and CLI adapters) + both IDE hosts |
| serve a project file | `GET /file/<abs path>` (frozen) | `serveFile` | `webviewd`, `webview-vscode` |
| serve a page **with the bridge injected** | `GET /page/<abs path>` | `serveFile` | `webviewd` |
| **propose + apply an edit** | `POST /api/v1/applyEdit`, `/api/v1/diff` | `edit` | `webviewd`: the buffer when the host declares `edit`, otherwise its own atomic write |
| reveal in the project tree | `POST /reveal` | `reveal` | Phase 3 |
| select a range | `POST /select` | `select` | the LSP host reports it; no HTTP verb yet |
| show a diff of a proposal | included in `/api/v1/diff` and in every apply response | `diff` | `webviewd` |
| undo / redo the last applied edit | `POST /api/v1/undo`, `/api/v1/redo` | `undo` | `webviewd` (its own checkpoints; the editor's own undo is the reader's `Ctrl+Z`) |
| notify the host a page changed | `POST /refresh` | `refresh` | Phase 3 |
| stream file-change events (SSE) | `GET /api/v1/events` | `watch` | `webviewd` |

`window.openFile` stays exactly as frozen in every host. A page that only needs navigation needs none of the
rest.

## 2. `GET /health` — the capability document

Answers without credentials, which is the whole point: a page asks before it wires itself up.

```json
{ "plugin": "hr.hrg.webview.webviewd", "port": 51234, "allowedOrigins": 2, "tokenRequired": true,
  "bridgeVersion": 1, "capabilities": ["open", "serveFile"] }
```

| Key | Meaning |
| --- | --- |
| `plugin` | which host is answering — `hr.hrg.webview.webviewd`, `hr.hrg.jetbrains.webview`, `vscode-webview-explorer`, `hr.hrg.watch2.sidecar` |
| `port` | the port actually bound, which is how a page learns an ephemeral one |
| `allowedOrigins` | how many origins are trusted; **`0` means every caller is refused**, and that is the default, not an error |
| `tokenRequired` | whether a token is configured for the state-changing routes |
| `bridgeVersion` | which injected contract the host implements; the same number a page sees as `window.__jcbWebViewBridge` |
| `capabilities` | what the host can do **now**, sorted. Empty is the honest answer for a host with no editor attached |

One implementation produces this document (`webview-core`'s `HostHealth`), so two hosts with the same
abilities produce identical bytes — that is Phase 1's `HostHealthParityTest`.

`capabilities` describes the *editor* half. The file and page routes are served by the host process itself,
so `serveFile` appears whenever that route exists, editor or not.

## 3. `/.well-known/webview.json` — the manifest

New in Phase 2. `/health` answers "is a bridge there and what can it do"; the manifest answers "what is this
process, where is its token, and how precisely can it move a caret".

```json
{
  "plugin": "hr.hrg.webview.webviewd",
  "port": 51234,
  "project": "D:/wrk/java/jcodebuddy",
  "tokenPath": "D:/wrk/java/jcodebuddy/.jcodebuddy/webview/token",
  "tokenRequired": true,
  "bridgeVersion": 1,
  "capabilities": ["open"],
  "host": {
    "name": "zed-cli",
    "available": true,
    "lineNavigation": "file-only",
    "note": "the adapter opens the file but cannot place a caret: …"
  }
}
```

`host.lineNavigation` is the field that keeps a host honest about a verb it only half implements:

| Value | Meaning |
| --- | --- |
| `exact` | the caret lands on the requested line and column |
| `file-only` | the file opens; the line and column are **not** applied |
| `none` | no editor adapter is attached; navigation is refused |

`file-only` is not hypothetical: Phase 0 measured that Zed 1.21.0 on Windows refuses the documented
`zed <file>:<line>:<column>` form (`os error 123`), so the CLI adapter can open a file and nothing more
([`PHASE0-ZED-FINDINGS.md`](../PHASE0-ZED-FINDINGS.md) § B). The caret path on Zed is the LSP channel, not the
CLI — which is why the capability says `open` and this field says `file-only` rather than both pretending.

## 4. `.jcodebuddy/webview/` — the descriptor

```
.jcodebuddy/webview/host.json    { plugin, pid, port, project, tokenPath, capabilities, startedAt, host }
.jcodebuddy/webview/token        the secret itself, 0600 where the filesystem has POSIX permissions
```

- The port is ephemeral by default (`--port 0`); the descriptor is how a page or a script finds it, which is
  decision D7 in [the plan](../PLAN-webview-suite.md).
- **The descriptor never contains the token**, only the path to it, so it can be logged or printed.
- A second `webviewd` for the same project **refuses to start** (exit code `2`) while the recorded pid is
  alive. Liveness is asked of the operating system, not inferred from the file, because a killed process leaves
  the file behind; a stale descriptor is simply overwritten.
- On Windows there is no POSIX mode to set, so the token file inherits the user profile's ACLs. That is stated
  rather than worked around; the file is inside the user's own profile either way.
- `.jcodebuddy/webview/` is derived state and is ignored by git (see the repository's `.gitignore`).

## 5. Routes

| Route | Auth | Answers |
| --- | --- | --- |
| `GET /` | none | an index page: the project, the port, the adapter, the capabilities, links to the routes below |
| `GET /health` | none | the capability document (§ 2) |
| `GET /.well-known/webview.json` | none | the manifest (§ 3) |
| `GET /open?filePath=&line=&column=` | origin **or** token | the frozen statuses, unchanged: `200`, `400`, `403`, `404`, `405`, `429` |
| `GET /file/<percent-encoded absolute path>` | origin **or** token | the file, or `400`/`403`/`404`/`500` from core's page server |
| `GET /page/<percent-encoded absolute path>` | origin **or** token | the same file with the bridge appended when it is HTML |
| `OPTIONS` on an authorized route | origin | `204` with `Access-Control-Allow-Headers: X-WebView-Token`, so a page may send the token in a header |

`/page/` differs from `/file/` by exactly one thing: `window.openFile` and `window.__jcbWebViewBridge` are
installed. The transport the standalone host injects is an image beacon to its own `/open`
(`'/open?filePath=' + encodeURIComponent(path) + …`), which needs no CORS grant and leaves no visible
navigation; another host passes its own statement to `InjectedBridge.script(transport)`.

## 6. Security rules (implemented once, in `webview-core`)

1. **Loopback only** — `127.0.0.1`, never `0.0.0.0`.
2. **An empty allow-list denies everyone.** The safe default is closed.
3. A caller proves itself with **either** an allowed `Origin` **or** the configured token, because a
   `file://` page sends no usable `Origin`.
4. **CORS headers are echoed only to an allowed origin** (`Access-Control-Allow-Origin: <origin>` plus
   `Vary: Origin`) — never `*`, and never to an unauthenticated caller.
5. **A path jail** confines `/open` and `/file/`/`/page/` to the project, with symlinks resolved.
6. **A rate limit** of 20 navigations per 20 s applies to `/open`, shared by every transport of that host.
7. **The host trusts its own origin by default**, in addition to anything `--allowed-origins` names: the pages
   it serves are the pages it must drive. This is a deliberate, documented deviation from the IDE hosts, which
   trust nothing until configured, and it is narrower than it sounds — another page on another port still has
   to be allow-listed or hold the token.

## 7. Running the reference host

```
webviewd --project <dir> [--port 0|N] [--open] [--host auto|none|lsp|zed-cli]
         [--allowed-origins <origin,origin>] [--token <secret>]
         [--sidecar-port <7979>] [--sidecar-token <secret>] [--print-manifest]
```

| Exit code | Meaning |
| --- | --- |
| `0` | served until stopped; the descriptor is removed on the way out |
| `1` | I/O or bind failure |
| `2` | a usage error, or another live host already owns this project |

### The adapters, and the preference order

`auto` prefers **`lsp`**, because on Windows it is the only route that can place a caret; then the **Zed CLI**,
which can open a file but not position it; then **nothing**, which is a supported deployment rather than a
failure. `--host lsp` and `--host zed-cli` refuse to start rather than fall back silently, and the refusal
names what to check.

| Adapter | `lineNavigation` | Reaches the editor by |
| --- | --- | --- |
| `lsp` | `exact` | asking the JWA sidecar (which holds Zed's LSP connection) to send `window/showDocument` with a selection — the sidecar's `/jump` route, over loopback. It also carries `edit`: the sidecar sends `workspace/applyEdit`, so a change lands in the editor's buffer and its undo stack ([edit API](webview-edit-api.md) § 6) |
| `zed-cli` | `file-only` | `Zed.exe <absolute path>`; the documented `path:line:column` form is refused by Zed 1.21.0 on Windows ([PHASE0-ZED-FINDINGS.md](../PHASE0-ZED-FINDINGS.md) § B) |
| `null` | `none` | nothing: every editor verb is refused and a page should fall back to the clipboard |

The LSP adapter's honesty is not a constant: it reads the sidecar's `/health` (re-reading it after a short TTL,
because Zed attaches and detaches without telling anyone) and reports `open` **only while that document lists
it**. The sidecar in turn withholds its capabilities until an LSP client has completed `initialize`, so the
three-way chain — page, `webviewd`, sidecar — never advertises a navigation that can only answer `NO_HOST`.
That rule was corrected on 2026-09-25 in both directions: the sidecar used to announce `jump`/`showDocument`
unconditionally, and `webviewd` used to treat any adapter it could construct as `exact`.

Because the caret path is LSP, `webviewd` needs to authenticate to the sidecar when the sidecar was started
with a token: `--sidecar-token`, defaulting to the `jwa.sidecar.token` system property so one `-D` configures
both processes. The sidecar denies unconfigured callers by design (Phase 1), which is why a two-process
deployment needs a shared secret and a one-process deployment (plan question 4) would not.

`--print-manifest` prints what would be published without binding a socket, which is how the Phase 2 work
checked the adapter's honesty about lines on a machine with no editor running.

## 8. The support matrix, and what is missing

Two words are used precisely, and nothing here uses a third: **implemented** means code exists and its tests
pass; **observed** means someone ran it against that host on the build named and reported the result. A cell that
says "implemented" is not claimed to work in front of a human, and the dates are the ones in
[`webview-edit-api.md`](webview-edit-api.md) § 6 and
[`ide-observation-checklist.md`](ide-observation-checklist.md) § 2a.

| | `webviewd` (standalone) | `webview-jetbrains` | `webview-vscode` | `jwa-sidecar` (LSP) |
| --- | --- | --- | --- | --- |
| `open` (caret) | implemented; with `--host lsp` observed on Zed 1.21.0 | observed | observed | observed (Zed 1.21.0) |
| `open` precision | `exact` with LSP, `file-only` with the Zed CLI, `none` with no editor | `exact` | `exact` | `exact` |
| `select` (range) | implemented | implemented | implemented | implemented |
| `reveal` (project view) | n/a | **not implemented**, and deliberately not advertised | n/a | n/a |
| `serveFile` / `/page/` | implemented | not implemented | implemented | not implemented |
| served digest (`X-WebView-Digest`, `ETag`) | implemented | not implemented | not implemented | n/a |
| `edit` → the editor's buffer | implemented (delegates to whatever adapter says it can) | **observed** 2026-09-26 | **observed** 2026-09-26 | **observed** 2026-09-25 |
| `edit` → disk (digest-guarded, atomic, journalled undo) | implemented; driven end to end by `examples/webview-client.test.mjs` | offered, not observed | **refused by design** with `409 no-disk-write` | not implemented |
| `diff` (a proposal to show before writing) | implemented | implemented | **refused by design** (it owns no bytes) | not implemented |
| `undo` / `redo` (`/api/v1/...`) | implemented, journalled to disk | implemented | **refused by design** (the editor's own undo is the review step) | not implemented |
| `events` (SSE file changes) | implemented | not implemented | not implemented | n/a |
| Neovim | — | — | — | registered in principle; **never run** |

Three things are worth reading out of that table rather than inferring:

* **A host that owns no bytes refuses the verbs that need bytes.** VS Code's `diff`, `undo` and `redo` answer
  `409 no-disk-write` instead of pretending, which is why its `edit` still works: the change goes to the buffer,
  where the editor's own undo already exists.
* **`reveal` is absent from the JetBrains capability list on purpose** — advertising a verb that throws at
  runtime turns a page's fallback ladder into a broken link, which is the rule every capability list here obeys.
* **Zed is a client, not a host.** Its navigation and buffer-edit results are the sidecar's, and they depend on
  the extension registering it: a settings-only entry is ignored by 1.21.0 ([`PHASE0-ZED-FINDINGS.md`](../PHASE0-ZED-FINDINGS.md) § A′).

### What is missing, and what that costs

- **The Zed cold start is a one-step user action, not a missing feature.** The sidecar is Zed's language server
  for Java, so Zed spawns it when a Java buffer is opened — and until then there is no editor to attach to. The
  extension's `process:exec` capability was the plan's answer to that and has been **dropped (2026-09-26)**: a
  process the extension spawns is *not* Zed's language server, so the sidecar would answer `/health` with
  capabilities `[]` (`isEditorAttached()` is false) — a listening port that can move nothing, which is exactly
  what this document's honesty rules exist to prevent. Opening any `.java` file once (`zed path/to/Some.java`
  works: Phase 0 § B) starts the sidecar for the worktree, and it stays until Zed quits. Until then a page
  degrades honestly: the client's status line says "No host: clipboard only" and `webviewd`'s manifest reports
  `lineNavigation: none`.
- **The `jwa-sidecar.txt` addon-file mechanism**: documented in the sidecar's README, **never implemented** — no
  code in any language has ever read that file. The builder is a compile-time dependency instead; see
  [`../jwa-sidecar/modules.md`](../jwa-sidecar/modules.md).
- **A test for the JWA "Sync Builder" code action**: it exists in the sidecar and is offered to clients, and
  nothing in a test run asserts it. Recorded as a gap rather than presented as a feature.
- **Checkpoints are per host.** `webviewd` journals them under `.jcodebuddy/webview/checkpoints/`; the JetBrains
  plugin builds its own `EditService` per project. A page that switches host starts a new undo history, which is
  correct but surprising if unwritten — so it is written here.
- **`/jump` is still a separate route.** Plan § 6.3 folds it into `POST /api/v1/open`; until then `webviewd`'s LSP
  adapter calls `/jump`, and that hop is the only HTTP call between two of our own processes. Its `/applyEdit` is
  the same kind of temporary bridge.
