# The module index

Addressing tables that a metadata document in this module references, written by the
`hipster-entity-generator` pass. Derived output, like `metadata/`: ignored by default, and
opt-in together with the metadata subtree (see `../README.md`, "Track policy").

## `files.json` — the addressing table

One row per file the pass indexed or wrote: a short **id** to the file's **module-relative
path**. This is the only place in the whole metadata tree where a source path is written —
every `file` value in a `<Marker>.metadata.json` is an id that resolves here, which is what
keeps a path from being repeated once per location.

* `format` — the table's own version. A consumer that does not recognise it must refuse the
  table and say why, never guess.
* `module`, `sourceRoot` — diagnostics, so the file is self-describing when opened directly.
* `files` — id to path, sorted by id. Paths are module-relative with forward slashes, never
  absolute and never `..`.

Ids are **derived from the path**, never from a counter: the file's simple name, qualified by
the shortest package suffix that disambiguates it among all the files this pass indexed
(`PersonSummary`, but `entity.Person` when another package holds a `Person` too). That makes
an id deterministic — a function of the path and the set of paths, with nothing depending on
visit order — identical in every document, and readable in a diff. The trade-off is
deliberate: adding a file whose simple name collides with an existing one may lengthen the
existing id, which a hash would avoid at the cost of readability.

## Reserved, not implemented

The directory is a *directory of tables* so these can be added without changing any
consumer's contract:

* `hashes.json` — content identity: id to a hash of the file's bytes (CRLF normalised to LF
  first, so the same content is valid on any checkout), plus a header naming the algorithm,
  the tooling revision and the flags that change output. Never a correctness input: a
  missing, unreadable or version-mismatched table must force a full pass. It is what would
  let a watcher or an incremental pass decide what actually changed without re-reading and
  re-parsing the tree.
* `artifacts[].inputs` in a document — the dependency edges: per emitted artifact, the file
  ids it was generated *from*. That turns the index into the graph an incremental pass needs
  instead of one it must re-derive by parsing.

Neither is written today. `metadata/watch/<toolSet>/metadata.db` remains the watch agent's
own cache and is not replaced by this directory.

This README is tracked and human-owned: the pass creates it when it is absent and never
overwrites it.
