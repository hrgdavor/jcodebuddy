# The module index

The module's **class index**, written by the `hipster-entity-generator` pass. Derived output,
like `metadata/`: ignored by default, and opt-in together with the metadata subtree (see
`../README.md`, "Track policy").

## `classes.json` — the class index

One row per **type** the module compiles, keyed by its **fully qualified name**: the file that
declares it, that file's content identity, and the type's kind and modifiers. This is the only
place in the whole metadata tree where a source path is written — every `file` value in a
`<Marker>.metadata.json` is an FQN that resolves here, which is what keeps a path from being
repeated once per location.

* `format` — the table's own version. A consumer that does not recognise it must refuse the
  table and say why, never guess.
* `module`, `sourceRoot` — diagnostics, so the file is self-describing when opened directly.
* `hash` — the content-identity contract: `algo` (the algorithm the checksums use),
  `normalize` (`lf`, i.e. CRLF is normalised to LF before hashing) and `of` (`content`). A
  table whose `hash` does not match the reading build must force a full pass, never a guess.
* `classes` — FQN to row, sorted by key.

A row carries:

* `path` — the declaring file, module-relative with forward slashes, never absolute and never
  `..`.
* `kind` — `class`, `interface`, `enum`, `record` or `annotation`.
* `modifiers` — the declaration's Java modifier keywords, **sorted** (`public`, `protected`,
  `private`, `abstract`, `static`, `final`, `sealed`, `non-sealed`, `strictfp`). Sorted so a
  reordered modifier list is not a diff.
* `enclosing`, `depth` — a member type's enclosing type and how deeply it is nested; `null`
  and `0` for a top-level type. A member type is its own row, so a document can reference
  `…PersonSummary.Record` and have it resolve.
* `line` — the declaration's start line (its name), 1-based.
* `generated` — `1` when the pass wrote the file (it carries a DEC-021 header), `0` otherwise.
* `checksum` — 16 hex characters: `Wyhash64` over the file's bytes with CRLF normalised to LF.
  The same algorithm and the same normalisation the watch agent's own tables use, so the two
  agree about what "the same content" means.
* `hashCalculatedAt` — the ISO-8601 UTC instant that checksum was **calculated**. It changes if
  and only if `checksum` changes (or the row is new), so it dates the content rather than the
  build: an unchanged tree produces a byte-identical table on every pass.
* `size` — the file's size in bytes, as hashed.

The file's last-modified time is **not** in this table: an `mtime` belongs to a working tree
and cannot survive a checkout, so writing it here would make a committed table differ from a
regenerated one on every machine. It lives in `mtimes.json` beside this file, which a watcher
may use as a cheap pre-filter and which nothing treats as a correctness input.

### Why the key is a fully qualified name and not an id

An id — a hash, a counter, a 4-byte value, a positional index — is a fact about the pass that
wrote it: it changes when a file moves, when a row is inserted, or when the id scheme changes,
and it means nothing to a tool that cannot run our writer. A fully qualified name is a fact
about the code, and it is the one reference a Java IDE's rename refactor updates everywhere it
appears — including in text files that are not Java. That is the trade this index makes, and
the cost is stated: **a package or type rename changes the key**, so a reference in a document
is stale until the next pass regenerates it. The pass reports that as a removed row plus an
added row, and the document it rewrites carries the new name.

## `mtimes.json` — the working-tree sidecar

FQN to the file's last-modified time in epoch milliseconds, written by the same pass as the
table. It is **not** part of the class index, and deliberately so: an `mtime` is a property of a
working tree rather than of the source, so a value committed to git would differ on every
checkout while the content it describes did not. A watcher may compare it against the
filesystem as a cheap pre-filter before hashing anything; nothing may treat it as a
correctness input, and a missing or unreadable sidecar simply costs a full hash.

## Reserved, not implemented
The directory is a *directory of tables* so these can be added without changing any consumer's
contract:

* `generator` — a header value naming the tooling revision and the flags that change output.
  Defined, not written until something consumes it: the same "options are part of the
  fingerprint" rule the `hash` header follows.
* the dependency edges (`artifacts[].inputs` in a document) — per emitted artifact, the file
  ids it was generated *from*. That turns the index into the graph an incremental pass needs
  instead of one it must re-derive by parsing.
* a pointer in a generated artifact's DEC-021 header naming the class index row it came from
  — the machine-readable form of DEC-019's navigability rule for generated Java.

None of those is written today. `metadata/watch/<toolSet>/metadata.db` remains the watch
agent's own cache and is not replaced by this directory.

This README is tracked and human-owned: the pass creates it when it is absent and never
overwrites it.
