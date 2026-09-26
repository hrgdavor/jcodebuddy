# The edit API — proposing and applying changes from a page

This is the contract between **a page that wants to change a file** and **the webview host that performs
the change**. It is the write half of [`contract.md`](contract.md): that document is navigation, this one is
a change, and a page that only navigates needs none of it.

Audience: anyone building a page that is allowed to **write** — an editable table, a rename tool, a review
dashboard whose reader accepts a suggestion, a generator's own preview-and-apply step.

**This file is normative.** Nothing here is a suggestion, and the one thing to read before anything else is
the digest rule in § 1: a page that gets it wrong is the reason the whole API exists.

---

## 1. The flow in one screen

```
1. GET  /file/<path>                            the page reads the bytes it will edit
                                                (the answer carries X-WebView-Digest — § 3.5 of contract.md)
2. POST /api/v1/diff    {expectedDigest: <that digest>, edits: […]}
   → 200 {applied: false, digest: <digest the file WOULD have>, unifiedDiff: "…"}
3. the reader looks at the diff and accepts
4. POST /api/v1/applyEdit {expectedDigest: <the ORIGINAL digest>, edits: […], dryRun: false}
   → 200 {applied: true, digest: <the digest the file now has>, unifiedDiff: "…"}
5. POST /api/v1/undo    {filePath}
   → 200 {applied: true, digest: …, unifiedDiff: …}
```

Step 2 and step 4 carry the **same** `expectedDigest`: the digest of what the page read. Step 3 is not
decoration — the proposal writes nothing, and a page that skips it has removed the reader from their own
file.

**The proposal's response digest is the *future* one.** A page can render it and chain the next edit without
re-reading — but it must **not** send it back as `expectedDigest`, because by then the file may not have it.
`expectedDigest` is always the digest of the content the page actually read.

**An absent `dryRun` means *propose*.** `POST /api/v1/applyEdit` with no `dryRun` is a `/diff`: a page that
forgets the flag must not be the reason a file changed. The rule that follows is the one to build on: a page
has to ask twice.

---

## 2. The routes

| Route | Method | Auth | Body | Answers |
| --- | --- | --- | --- | --- |
| `/api/v1/applyEdit` | POST | **token** | `{filePath, expectedDigest, edits, dryRun?, target?}` | `200` applied, `200` `no-change`, `400` `invalid-edit`/`invalid-path`, `403` `outside-project`, `404` `not-found`, `405` non-POST, `409` `stale`/`no-buffer-edit`/`no-disk-write`, `429` `rate-limited`, `500` `not-readable` |
| `/api/v1/diff` | POST | **token** | the same body, `dryRun` forced true | as above, always `applied: false` |
| `/api/v1/undo` | POST | **token** | `{filePath}` | `200`, `400`, `403`, `404` `nothing-to-undo`, `409` `stale`/`no-disk-write`, `429` |
| `/api/v1/redo` | POST | **token** | `{filePath}` | `200`, `400`, `403`, `404` `nothing-to-redo`, `409` `stale`/`no-disk-write`, `429` |
| `/api/v1/events` | GET | **token** | — | `200` `text/event-stream` until the client goes away |

| Status | Reason | What it means, and what a page does |
| --- | --- | --- |
| `200` | — | applied, proposed, restored, or `no-change` — the body says which |
| `400` | `invalid-edit` | an overlap, an out-of-range position, no edits at all, or an `expectedDigest` that is not a digest. The page's bug; do not retry the same request |
| `400` | `invalid-path` | the name is not a usable path |
| `403` | — | the caller proved nothing (**token** alone, § 6) |
| `403` | `outside-project` | the path escaped the project root; nothing was written |
| `404` | `not-found` | nothing is there |
| `404` | `nothing-to-undo` / `nothing-to-redo` | this host has no history for that file, or the last action was not the one asked for |
| `405` | — | not a `POST` (or not a `GET`, for `/events`) |
| `409` | `stale` | the file changed since the page read it. **The body carries the current digest** — re-read and propose again (§ 4) |
| `409` | `no-buffer-edit` | `target: "buffer"` was asked for and the attached host cannot put a change in a buffer — never a silent write to disk |
| `409` | `no-disk-write` | the host owns no bytes of its own: it can edit an editor's buffer, and `/diff`, `/undo` and `/redo` are refused rather than faked |
| `429` | `rate-limited` | writes share the navigation limiter (§ 4) |
| `500` | `not-readable` | the file is there and could not be read (or could not be written); a host-side condition, not the page's |

**`target` decides who performs the change**, and it is the field that makes the two destinations explicit
rather than implicit:

| `target` | Behaviour |
| --- | --- |
| `auto` (the default) | the attached editor's **buffer** when the host declares `edit`, otherwise this host's own write to disk |
| `buffer` | the editor's buffer; `409 no-buffer-edit` when no attached host can do it — never a silent fall back to disk |
| `disk` | this host's own atomic write, even when an editor is attached |

A buffer apply answers `{"applied": true, "target": "buffer", "digest": …, "unifiedDiff": …, "detail": …}`,
and the `detail` says the file on disk is unchanged until the editor saves. **That asymmetry is the point of
the field**: a page that keeps editing has to know whether it is tracking the **buffer** or the **file**, and
the digest it gets back describes the buffer. The digest guard still runs *before* any editor is asked — the
edits are checked against the file the page actually read — and when the editor itself declines the change
under `auto`, the answer is this host's own disk write with `target` absent. That is the documented behaviour
rather than an error; `target: "buffer"` is how a page asks for a refusal instead.

---

## 3. What an edit is

```json
{ "startLine": 12, "startColumn": 1, "endLine": 12, "endColumn": 8, "newText": "renamed" }
```

* **One-based line and column, inclusive start, exclusive end** — so an insertion is `start == end`, and a
  deletion is the same range with `newText: ""`.
* Positions are resolved **against the same decoding the digest covers**. A page that rendered the file knows
  the line and the character position in it; it is not asked to compute UTF-8 byte offsets through a decoding
  it did not perform. That is why the field is a column and not a byte offset.
* An end position may be **one line past the last line at column 1**, which is how a whole-line replacement is
  spelled without knowing the line's length.
* Edits in one request **must not overlap**; they are applied together, **against the original text**, never
  against each other's output. An overlap, a position outside the file, or an empty edit list is
  `400 invalid-edit` with a `detail` naming the problem, and nothing is written.
* **The file's line endings and its trailing newline survive.** A CRLF file stays CRLF; a file that ended
  without a newline still does. Otherwise every edit would show up as a whole-file change in the reader's
  version control.
* **Use the whole-line helper rather than building the range by hand.** The newline after the replaced lines
  belongs to the range, so a replacement must carry a terminator or it silently joins two lines: the helper
  appends one when the replacement lacks it. A partial-line change is an ordinary edit; removing lines outright
  is the range with an empty `newText`.

---

## 4. The rules that make it safe

1. **The same path jail as navigation.** Project root, symlinks resolved, no `..` escape. An edit outside the
   project is `403 outside-project`, and nothing is written.
2. **A stale digest is a refusal, never a merge.** If the file no longer matches `expectedDigest`, the answer
   is `409 stale` **with the current digest**, so the page can re-read, re-render and propose again. This is
   the rule the whole API exists for: no page may apply an edit to content the reader has not seen.
3. **Writes are atomic.** A temporary file in the same directory, then a move; an interrupted write leaves the
   old file or the new one, never half of each. Where the filesystem refuses an atomic move it degrades to a
   plain replace, and that is the only case where the guarantee is weaker.
4. **Every applied write is checkpointed, bounded per file and journalled to disk.** The bound (20 states per
   file in this implementation) is what keeps a long-running host from leaking; the journal is one small file
   per state rather than one rewritten journal, because a rewrite is the single operation that can lose the
   whole history to an interruption. The history therefore **survives a page reload and a host restart** — the
   alternative was telling a reader "nothing to undo" about a change they can see. The journal lives in the
   host's own state directory, for example the project's `.jcodebuddy/webview/`, which is ignored by git; a
   caller with nowhere to write gets an in-memory history instead, and a journal that cannot be written is
   reported rather than allowed to break an edit the reader already accepted.
5. **An undo is digest-guarded too.** It is a write like any other, so it refuses (`409 stale`) when the file
   changed since this host wrote it: restoring the checkpoint would silently discard the reader's own edit in
   their editor. The checkpoint is kept, so the page can decide and try again. **Redo mirrors undo**, and is
   refused unless the last action was the undo it would reverse.
6. **Writes share the navigation rate limit** (20 per 20 s in this implementation), so a page that spins cannot
   spend a separate allowance from a page that spins on clicks.
7. **`no-change` is not a failure.** An edit that describes what is already there answers `200` with
   `applied: false` and `reason: no-change`, and writes nothing. A page that treats it as an error reports a
   failure to a reader whose file is exactly as they wanted it.

---

## 5. `GET /api/v1/events`

Server-sent events, so a page re-renders instead of polling:

```
: keep-alive

event: change
data: {"paths":["src/main/java/com/example/Person.java"]}

```

* Paths are **project-relative and forward-slashed** — the same spelling a page links with.
* **One frame per batch, not per file**: a save that touches a dozen files is one thing that happened, and the
  page re-renders once.
* **Generated directories are not watched** (`.git`, `.jcodebuddy`, `.gradle`, `node_modules`, `target`,
  `build`, `out`, `.idea`). A page cares that its sources changed, not that a build wrote a hundred class files.
* **Directories created after the stream opens are watched too**, or a page would stop seeing new files the
  moment the first one was added.
* Watching is capped (2000 directories in this implementation) and **the host reports when watching is
  partial** rather than pretending to see everything.
* **The stream ends when the client disconnects**, which the host notices on the next frame — at most one
  keep-alive (15 s) later. A page closes it when it is done; an abandoned stream is a live watcher per tab.
* A frame the page cannot parse must not kill the stream: ignore it and keep the subscription.

---

## 6. What a host must do — and what a page may assume

A host may implement **the write half, the buffer half, or neither**: owning a project's bytes and editing an
open buffer are different powers, and no host is obliged to have both. That is not a gap to route around — it
is what `GET /health`'s `capabilities` array is for ([`contract.md`](contract.md) § 3.2):

| Capability | The verbs it covers |
| --- | --- |
| `edit` | a change can land in an editor's **buffer**; without it, `target: "auto"` is this host's own write |
| `diff` | a proposal and its unified diff |
| `undo` | the checkpoint history, and therefore `/undo` and `/redo` |
| `watch` | the event stream of § 5 |

**A host that owns no bytes refuses the verbs that need bytes rather than pretending.** A buffer-only host
answers `/diff`, `/undo` and `/redo` with `409 no-disk-write` and a `detail` naming which host does own the
file; a host with no editor answers `target: "buffer"` with `409 no-buffer-edit`. Both are **honest refusals**,
and a page must handle them as such — never by finding another route to the same change. The corollary is the
one rule a page author has to internalise: **feature-detect, do not try.** Read `capabilities`, choose the verb
you actually have, and if the answer is a refusal, tell the reader which host can do it. Applying the change
some other way after a refusal is exactly the failure this contract is written to prevent.

Everything else is a host author's concern: how a buffer change is spelled at the editor's own API, where the
journal lives, which adapter places a caret, and whether the host is a plugin, a standalone process or
something else entirely.

**The token, not merely an allowed `Origin`.** Every state-changing route accepts the token **alone**
([`contract.md`](contract.md) § 3.4): the origin rule is shared by every page the reader has open, while only a
page this host served can hold the secret. A host with no token configured refuses writes outright rather than
falling back to the weaker rule, and a page carries the token the same way it does for navigation —
`X-WebView-Token`, or `?token=…` on the URL.

### 6.1 The shape a page writes

The kit's client names the verbs, and a page should use it rather than speaking the routes by hand:

```js
const client = await window.jcbClient.auto();      // discovers the host; see § 4 of contract.md

const file = await client.read('src/main/java/com/example/Person.java');
// file.digest is the host's own digest of what was read — never recompute it yourself

const edits = [{ startLine: 12, startColumn: 5, endLine: 12, endColumn: 9, newText: 'renamed' }];

const proposal = await client.proposeEdit(file.path, file.digest, edits);
show(proposal.unifiedDiff);                        // the reader's review step; nothing is written yet

const applied = await client.applyEdit(file.path, file.digest, edits);
// the SAME digest as the proposal: proposal.digest is the file's FUTURE digest, not what to send back
// applied.target === 'buffer' means the editor holds it, unsaved, in the editor's own undo stack
// the host may answer 409: 'stale' -> re-read and propose again; 'no-buffer-edit' /
// 'no-disk-write' -> this host cannot do that, and the reader has to be told so

if (applied.applied) {
  await client.undo(file.path);                    // back to the bytes of file.digest
}
```

The client is [`examples/with-assets/assets/webview-client.js`](../examples/with-assets/assets/webview-client.js);
a page that edits end to end is
[`examples/with-assets/pages/edit-demo.html`](../examples/with-assets/pages/edit-demo.html).

---

## 7. What may change

* **The five routes, the request fields, the edit object and the `target` values are frozen.** `startLine`,
  `startColumn`, `endLine`, `endColumn`, `newText`, one-based, inclusive start and exclusive end, is the
  contract; a host that accepted anything else would break every existing page.
* **The digest rule is frozen**: `expectedDigest` is the digest of what the page read, and the response digest
  describes the content the operation leaves behind.
* **The status codes and the set of `reason` strings are not frozen, and a page must not depend on the exact
  one it got.** Act on `applied` and on the flow — a refusal means "re-read", "this host cannot", or "do not
  retry this request" — because a new host adds a new refusal reason without asking. `200` and the `409`
  refusals of § 2 are the ones a page acts on.
* **New optional request fields and new reasons may be added**; an existing field will not change what it
  means, and an unknown field must be ignored rather than rejected.
* **New routes and new capabilities may appear.** A page ignores what it does not know and never assumes that
  a sibling route exists because this document mentions it.
* **What is deliberately not in the API:** no patch format of its own (the diff is for a human, not for the
  host), no byte-offset addressing (a page knows lines), no multi-file transaction — one request names one file,
  and a page that needs two changes proposes them one after the other.
