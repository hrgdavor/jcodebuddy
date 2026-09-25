# The edit API — proposing and applying changes from a page

Status: **the headless half is implemented and tested** (2026-09-25, Phase 3): `webview-core`'s `EditService`
and `webviewd`'s `/api/v1/*` routes. The editor-buffer halves — JetBrains' `WriteCommandAction`, VS Code's
`WorkspaceEdit`, and Zed's `workspace/applyEdit` over LSP — are **specified here and not yet wired**, and this
document says so at each point rather than implying parity.

Decisions this implements: [the plan](../PLAN-webview-suite.md) § 6.2 (digest, `dryRun`, undo) and § 10 Q1,
answered 2026-09-25 as **(c) then (a)**: a page proposes, the reader sees a diff, and the bytes are written only
when the reader accepts. The same answer keeps (b) for a host that can apply into an editor's buffer instead.

## 1. The flow

```
1. GET  /file/<path>                       the page reads the bytes it will edit
2. POST /api/v1/diff    {expectedDigest: <that digest>, edits: […]}
   → 200 {applied: false, digest: <digest the file WOULD have>, unifiedDiff: "…"}
3. the reader looks at the diff and accepts
4. POST /api/v1/applyEdit {expectedDigest: <the ORIGINAL digest>, edits: […], dryRun: false}
   → 200 {applied: true, digest: <the digest the file now has>, unifiedDiff: "…"}
5. POST /api/v1/undo    {filePath}
   → 200 {applied: true, digest: …, unifiedDiff: …}
```

Step 2 and step 4 carry the **same** `expectedDigest`: the digest of what the page read. The proposal's
response digest is the *future* one, so a page can render the result and chain the next edit without re-reading
— but it must not send that digest back as `expectedDigest`, because by then the file may not have it.

## 2. Routes

| Route | Method | Auth | Body | Answers |
| --- | --- | --- | --- | --- |
| `/api/v1/applyEdit` | POST | **token** | `{filePath, expectedDigest, edits, dryRun?}` | `200` applied, `200` `no-change`, `400` `invalid-edit`/`invalid-path`, `403` `outside-project`, `404` `not-found`, `405` non-POST, `409` `stale`, `429` `rate-limited`, `500` `not-readable` |
| `/api/v1/diff` | POST | **token** | same, `dryRun` forced true | as above, always `applied: false` |
| `/api/v1/undo` | POST | **token** | `{filePath}` | `200`, `400`, `403`, `404` `nothing-to-undo`, `409` `stale`, `429` |
| `/api/v1/redo` | POST | **token** | `{filePath}` | `200`, `400`, `403`, `404` `nothing-to-redo`, `409` `stale`, `429` |
| `/api/v1/events` | GET | **token** | — | `200` `text/event-stream` until the client goes away |

**The token, not merely an allowed `Origin`.** `/open` accepts either, because it only moves a caret. A
state-changing route accepts only the token (plan D8): the origin rule is shared by every page the reader has
open, and a page that was served by this host can hold the secret. A host with no token configured refuses
writes outright rather than falling back to the weaker rule.

Absent `dryRun` means **propose**, on `/applyEdit` as well as `/diff`. A page that forgets the flag must not be
the reason a file changed.

## 3. What an edit is

```json
{ "startLine": 12, "startColumn": 1, "endLine": 12, "endColumn": 8, "newText": "renamed" }
```

- **One-based line and column**, inclusive start, **exclusive** end — so an insertion is `start == end`.
- Positions are resolved against the same decoding the digest covers. A page that rendered the file knows the
  line; it is not asked to compute UTF-8 byte offsets through a decoding it did not perform.
- An end position may be **one line past the last line at column 1**, which is how a whole-line replacement is
  expressed without knowing the line's length.
- Edits in one request **must not overlap**; they are applied together, against the original text. An overlap,
  an out-of-range position or an empty edit list is `400 invalid-edit` with a detail naming the problem, and
  nothing is written.
- A whole-line helper exists in the core API and appends a terminator when the replacement lacks one — because
  the newline after the replaced lines belongs to the range, and consuming it without giving it back silently
  joins two lines.

## 4. The rules that make it safe

1. **The same path jail as navigation.** Project root, symlinks resolved, no `..` escape. An edit outside the
   project is `403` and nothing is written.
2. **A stale digest is a refusal, never a merge.** If the file no longer matches `expectedDigest`, the answer is
   `409 stale` **with the current digest** so the page can re-read and propose again. This is the rule the whole
   API exists for: no page may apply an edit to content the reader has not seen.
3. **Writes are atomic.** A temporary file in the same directory, then a move. An interrupted write leaves the
   old file or the new one, never half of each. (Where the filesystem refuses an atomic move, it degrades to a
   plain replace, and that is the only case where the guarantee is weaker.)
4. **The file's line endings and its trailing newline survive.** A CRLF file stays CRLF; a file that ended
   without a newline still does. Otherwise every edit would show up as a whole-file change in the reader's
   version control.
5. **Every applied write is checkpointed**, bounded per file (20 states by default — an unbounded history in a
   long-running host is a leak).
6. **An undo is digest-guarded too.** It is a write like any other, so it refuses (`409 stale`) when the file
   changed since this host wrote it: restoring the checkpoint would silently discard the reader's own edit in
   their editor. The checkpoint is kept, so the page can decide and try again.
7. **Writes share the navigation rate limit** (20 per 20 s), so a page that spins cannot spend a separate
   allowance from a page that spins on clicks.
8. **`no-change` is not a failure.** An edit that describes what is already there answers `200` with
   `applied: false` and `reason: no-change`, and writes nothing.

## 5. `GET /api/v1/events`

Server-sent events so a page re-renders instead of polling:

```
: keep-alive

event: change
data: {"paths":["src/A.java","webview/PLAN-webview-suite.md"]}

```

- Paths are **project-relative and forward-slashed** — the same spelling a page links with.
- **One frame per batch, not per file**: a save that touches a dozen files is one thing that happened.
- **Generated directories are not watched** (`.git`, `.jcodebuddy`, `.gradle`, `node_modules`, `target`,
  `build`, `out`, `.idea`). A page cares that its sources changed, not that a build wrote a hundred class files.
- **Directories created after the stream opens are watched too**, or a page would stop seeing new files the
  moment the first one was added.
- Watching is capped (2000 directories) and the host logs when it is partial rather than pretending.
- The stream ends when the client disconnects, which the host notices on the next frame — at most one
  keep-alive (15 s) later.

## 6. What is not implemented yet

- **The editor-buffer path.** When a host declares `edit`, the plan is that the change goes into the editor's
  buffer and its own undo stack instead of to disk (JetBrains: `WriteCommandAction`; VS Code:
  `WorkspaceEdit`). Nothing in `webviewd` does that today, and the capability list it publishes therefore does
  not claim it.
- **Zed's `workspace/applyEdit`.** Phase 0 measured that Zed applies such an edit to its buffer and not to
  disk; the LSP side of the sidecar does not send it yet. The write contract's transports are HTTP-only for now.
- **The page-side client.** `webview-client.js` (the full ladder, including the diff-and-accept step) and an
  example page that edits are Phase 3's remaining half.
- **Checkpoint persistence.** The undo history lives in the running host; a restart forgets it, which is
  documented rather than hidden.
- **`/jump` is still a separate route.** Plan § 6.3 folds it into `POST /api/v1/open`; that has not happened,
  and `webviewd`'s LSP adapter still calls it.
