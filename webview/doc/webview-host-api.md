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
| reveal in the project tree | `POST /reveal` | `reveal` | Phase 3 |
| select a range | `POST /select` | `select` | the sidecar's LSP host already reports it; no HTTP verb yet |
| propose + apply an edit | `POST /applyEdit` | `edit` | Phase 3 |
| show a diff of a proposal | `POST /diff` | `diff` | Phase 3 |
| undo / redo the last applied edit | `POST /undo`, `POST /redo` | `undo` | Phase 3 |
| notify the host a page changed | `POST /refresh` | `refresh` | Phase 3 |
| stream file-change events (SSE) | `GET /events` | `watch` | Phase 3 |

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
| `lsp` | `exact` | asking the JWA sidecar (which holds Zed's LSP connection) to send `window/showDocument` with a selection — the sidecar's `/jump` route, over loopback |
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

## 8. What is deliberately missing

- **The write verbs** (`applyEdit`, `diff`, `undo`, `redo`, `events`) are Phase 3; the digest-guarded semantics
  are specified in [the plan](../PLAN-webview-suite.md) § 6.2 and are not implemented here.
- **`POST /api/v1/...`** does not exist yet: the frozen `GET /open` is the navigation route and Phase 3 adds the
  write surface. The sidecar's `/jump` becomes a deprecated alias of the new open route when that surface lands
  (plan § 6.3) — until then `webviewd` calls `/jump`, and that hop is the only HTTP call between two of our own
  processes.
- **Registering the sidecar with Zed still needs the extension**, not a settings snippet: Phase 0 § A′ proved a
  config-only custom language server is impossible on 1.21.0. `webview/zed/webview-zed-dev-extension` supplies
  the registration; `lsp.webview-sidecar.binary` then points at the build.
- **Starting the host when no buffer of the registered language is open** is not covered: the language-server
  route starts the sidecar only once Zed opens a matching file, and the CLI/URL tier cannot place a caret on
  Windows. This is the gap `process:exec` in the extension (Phase 4's other half) is meant to close.
