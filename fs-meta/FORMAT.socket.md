# format for unix socket communication

notes

- define binary handshake but in such way it can not happen first two bytes are start of JSON object `{"`
  - this way we easily recognize when it is JSONRPC 
- binary wire protocol is 8 bytes for message prefix and 
  - when sending changes adds 64 bytes from meta followed by file name without newline
  - those 64 bytes have file name length that makes it easy to know that entry being read is 64+filenameLength and next starts after it 
  - commands are encoded in the 8 bytes somehow
  - START_BATCH
  - END_BATCH
  - CHANGE_ENTRY
  - etc...

## Proposed wire format

The notes above are the origin of this design; what follows makes them precise enough to implement, and deliberately
reuses the format the engine already writes so that **nothing on the wire needs a second parser**.

### Framing

Every message is an 8-byte prefix followed by `length` payload bytes, all integers little-endian:

| Offset | Size | Field    | Meaning                                                |
| ------ | ---- | -------- | ------------------------------------------------------ |
| 0      | 2    | `kind`   | the message kind, a `u16` from the table below         |
| 2      | 2    | `flags`  | reserved, `0` for now; a reader rejects unknown bits   |
| 4      | 4    | `length` | payload bytes after the prefix, at most 1 MiB          |

`kind` values are all **below `0x7B`**, so the first byte of a binary message can never be `0x7B` (`{`) — which is
what makes the JSON-RPC side of the socket recognisable from its first byte, as the notes ask: a JSON object starts
`{"` = `0x7B 0x22`, and a binary message starts with a small kind byte followed by `0x00`.

### Messages

| `kind` | Name           | Payload                                                                      |
| ------ | -------------- | ---------------------------------------------------------------------------- |
| `0x01` | `HELLO`        | `u16 protocol`, `u16 entry_size = 64`, `u64 generation`, `u8 hash_algorithm` |
| `0x02` | `START_BATCH`  | `u64 number`, `u64 parent`, `u64 started_ns`                                 |
| `0x03` | `CHANGE_ENTRY` | 64-byte entry (§ 5) + name bytes, no LF                                      |
| `0x04` | `END_BATCH`    | `u64 number`, `u64 added`, `u64 changed`, `u64 deleted`, `u64 changes_hash`  |
| `0x05` | `ERROR`        | `u16 code`, then a UTF-8 message                                             |
| `0x06` | `SUBSCRIBE`    | `u64 from_batch` — the last batch the client has applied (`0` = nothing)     |
| `0x07` | `PING`         | empty                                                                        |
| `0x08` | `PONG`         | empty                                                                        |

**`CHANGE_ENTRY` is the trio's entry, unchanged.** Its `nameLength` (offset 40) makes the frame self-delimiting:
`length == 64 + nameLength`, and a client checks that equality rather than trusting `length` alone. The change kind
comes from the entry's change bits (§ 17.2), so there is no third representation of "added / changed / deleted" — the
wire, the batch folder and the in-memory plan all speak the same 64 bytes.

**`START_BATCH` / `END_BATCH` are the boundaries, and `END_BATCH` is the commit.** A client applies nothing until the
batch's `END_BATCH` arrives, exactly as a reader trusts `files.json` last (§ 9): the counts in `END_BATCH` must equal
the entries counted by change bit, or the batch is discarded with `batch-count-mismatch`. That is what makes a dropped
connection mid-batch harmless — the client keeps its `from_batch` and asks again.

### Ordering on the wire

`CHANGE_ENTRY` messages within a batch are sent in **`(nameHash, name)`** order (§ 4 and § 17.2a). A client therefore
does not buffer a batch to sort it: it can insert into its own hash-ordered state as messages arrive, and a batch that
arrives already sorted merges with the previous one in a single walk — the same operation the reconcile API performs
off-line.

### Replay, resume and the two refusals

1. The client sends `SUBSCRIBE {from_batch}`.
2. The server answers with `HELLO`, then for each generation `g` in `from_batch + 1 ..= current`: `START_BATCH{g, g-1}`,
   its `CHANGE_ENTRY` messages, `END_BATCH{g, …}`.
3. If `from_batch` is older than the oldest retained batch, the server answers `ERROR{batch-too-old}` and the client
   falls back to a full pass (read the trio, or ask for a snapshot).
4. A missing batch in the range is `ERROR{batch-gap}` — never silently skipped, because a skipped batch is a quietly
   wrong index.

Live updates are the same messages: when a pass publishes generation `g`, the server sends its `START_BATCH`,
`CHANGE_ENTRY` messages and `END_BATCH` to every subscribed client whose `from_batch` is `g - 1`. A client that was
asleep gets the replay path instead, which is why replay and live streaming are one protocol rather than two.

### What the wire deliberately does not carry

- **File contents.** A client that needs a file's bytes asks the host OS, not the engine; the engine hashes on request
  (§ 12) and reports the hash, not the data.
- **The trio's own bytes.** `SUBSCRIBE` is the incremental path; a client that wants the whole state for the first time
  reads `files.meta` / `files.txt` / `files.json` directly, or asks for a snapshot message that is not specified yet.
- **Ordering by clock.** `START_BATCH.started_ns` exists for diagnostics; ordering is the batch number, never a
  timestamp, because a clock can go backwards and a sequence cannot.

### Why two boundaries: the consumer can work during the batch

`START_BATCH` and `END_BATCH` are not only a framing device — they tell a consumer **which work it may begin now and
which must wait for the whole change set**, and that distinction is where the latency advantage comes from.

Work that is **independent per entry** can start the moment its `CHANGE_ENTRY` arrives, while the rest of the batch is
still being decoded and transferred:

- reading the file's bytes (or opening it for the host tool that will use it) and hashing/parsing them;
- building the consumer's own per-file rows, index entries or parse trees;
- scheduling per-file work on a thread pool, one task per change.

Work that **depends on the complete set** is postponed to `END_BATCH`, and the reason is that it cannot be decided
from a prefix:

- a removed directory removes its subtree, and the set of surviving names is only known at the end;
- renames and "same content, new name" detection need both sides of the window;
- aggregate structures — inverted indexes, dependency graphs, "is any file dirty" flags — need every change before
  they are consistent;
- the durable commit itself: one transaction, one `generation` watermark advance, one "generation g applied" event.

So a consumer that waits for the last byte pays `transfer + independent_work` serially; one that pipelines pays
`max(transfer, independent_work)` and hides the parsing under the network. On a batch of a few thousand entries over a
socket that is a real difference, and on a local socket it is the difference between "the watcher's latency" and "the
watcher's latency plus the indexer's".

The correctness rule is unchanged: **pipelined work is speculative until `END_BATCH`.** A consumer may do it, must not
acknowledge it, and must be able to discard it — if the connection drops or a count mismatches, it keeps its old
`from_batch` and replays from there. Because the independent work is a pure function of (entry, file bytes), replaying
it is safe, which is what makes the pipelining free rather than merely fast.

`START_BATCH` also fails fast: `number` and `parent` are in the first message, so a consumer whose `from_batch` does not
match `parent - 1` learns it immediately instead of after receiving a change set it cannot apply.
