# Plan — the module class index: basic class metadata, keyed and referenced by FQDN

**Status: EXECUTED.** The plan below is kept as the specification it was; § 12 records every place the
implementation diverged from it and the measured numbers that replaced its projections, because a plan
that silently disagrees with the code is worse than no plan. The decision record is
[`doc-hipster-entity/architecture/decisions/DEC-029.md`](../doc-hipster-entity/architecture/decisions/DEC-029.md);
where this document and that one disagree, **DEC-029 is authoritative**.

**Status (as written):** Ready to execute. **Nothing in this document is implemented.** The repository is
green at the revision this file was added to — § 0.4 is the baseline you must confirm before you change
anything.

**Motivation.** The metadata records *where a field's locations are* (315 locations across 84 fields and
45 artifacts) but records almost nothing about *the classes those locations live in*, and the little it
records is entangled with one generator's needs:

* A document names a file by a **readable id** (`PersonSummaryBuilderTracking`, `entity.Person`) that
  resolves through `.jcodebuddy/index/files.json`. That id is **derived from the whole set of paths the
  pass happened to index**, so adding an unrelated file can lengthen an existing id and invalidate every
  document that used it. It is a *good* human id and a poor join key.
* Nothing in the tree knows a file's **content identity**. A generator that wants to skip work, a watcher
  that wants to know whether the bytes changed, and a report that wants to say "this file is stale" all
  have to re-read and re-hash the tree themselves — which is why `hashes.json` was *reserved* in
  `plan.metadata-locations.md` § 2.3.2 and never built.
* Nothing records a class's **kind** (class / interface / enum / record / annotation), its **modifiers**,
  its **size**, or when its content was last hashed — facts many generators want and that today are
  re-derived, per generator, by parsing.
* The consequence is that the metadata is shaped for **one** consumer (the entity-metadata generator and
  its HTML report) rather than being an index **many** generators can join against.

This plan builds that index: `.jcodebuddy/index/classes.json`, one row per **type**, keyed by its
**fully qualified name**, each row carrying the module-relative **path** of the file that declares it, a
**checksum** of that file's normalised content, the **timestamp when that checksum was calculated**, the
**file size**, and the type's **kind and modifiers**. References — in the metadata documents, in the
renderer, in any future generator — are **by FQDN, never by a surrogate id**.

**Why FQDN, and why no id.** An opaque 4-byte id (or any hash, counter or positional index) is a
statement about *this pass's* view of the tree: it changes when a file moves, when a row is inserted, when
the id's salt changes, and it means nothing to anything that cannot run our writer. A fully qualified name
is a statement about the code, and it is **the one identifier a Java IDE's rename refactor updates
everywhere it appears, including in text files** that are not Java. That is the property that matters
here: a project that renames `PersonDetails` expects every reference to follow, and a surrogate id would
be the one reference that silently does not. The cost is honest and accepted: an FQDN is longer than a
hash, the table is keyed by a string rather than by 8 hex characters, and a row is found by name rather
than by arithmetic. In exchange, every reference in the tree is refactor-safe, diff-readable, and
resolvable by a human with `grep`.

**What this plan is *not*.** An earlier draft of this file proposed re-expressing the location payload as
a **source map v3** — Base64 VLQ `mappings`, a sectioned index map, positional per-section `sources`.
That encoding is **dropped** (§ 3.1 says why, precisely). What survives from source maps is not the file
format but the **conventions**, which this plan adopts because they are the right conventions for a
shared index and they are what `files.json` half-implemented already — § 3.2 is that list, and it is a
first-class part of this plan, not an appendix.

**Relationship to the existing plan.** `plans__p1/plan.metadata-locations.md` (DEC-028) built
`files.json`, the readable id scheme, the artifact inventory and the location roles. **This plan
supersedes its addressing half** (the id scheme and the id→path table) and **keeps its location half**
(the artifact inventory, the eight location roles, the `views[].fields[].at` shape, the write ordering,
the one-statement-of-a-path rule). `plans__p2/plan.sourcemap-locations.md` is **withdrawn**: this file is
its replacement, and § 3.1 records why its format was rejected. It is left on disk for the reviewer to
delete deliberately — the deletion is a step of § 4.10, not something this plan did on its own.

---

## 0. Scope, baseline, ground rules

### 0.1 Already implemented and verified — do not rebuild any of this

| What | Where | State |
| --- | --- | --- |
| the central per-module index directory and its README rule | `ModuleFileIndex`, `.jcodebuddy/index/` | done, tested, green (DEC-028) |
| `views[].artifacts[]` — the inventory (id, name, kind, file, line, generated, own, header?) | `MetadataLocations`, `ArtifactMeta` | done, tested |
| `views[].fields[].at` — artifact id → role → line, the eight-role vocabulary | `MetadataLocations`, `ViewFieldMeta` | done, tested |
| interface-side locations (accessor name-token line + `@FieldSource` line) | `parseProperty`, `SourceLocation` | done, tested |
| `kindOf(TypeDeclaration)` — `enum`/`record`/`annotation`/`interface`/`class` from the AST | `MetadataLocations:465` | done, reused verbatim by § 4.3 |
| the shared, language-level-configured parser | `SourceReader.parser()` (`SourceReader:56`) | done, tested |
| the write order artifacts → index → documents | `generateInternal` | done, tested |
| the renderer finds the index by the two-route rule (module root, then the document's pointer); the scan only **verifies** a link; `html_index_missing` | `scripts/entity-html/` | done, tested |
| legacy documents (`sourcePath`/`markerSourcePath`, no artifacts/fields, no index pointer) still render | `fromJson`, `links.js` | done, tested |
| guards | `MetadataSourcePathTest`, `MetadataFileIdsTest`, `MetadataLocationsTest`, `IndexLayoutTest`, `GeneratorGuardTest`, `HtmlRenderBoundaryTest`, `ExampleRegenerationTest` | green |
| decisions in force | DEC-026 (+ amendment), DEC-027 (+ amendments), DEC-028, `AGENTS.md` § 1 | read all of them |
| an existing 64-bit content hash and CRLF→LF normaliser in the repository | `hr.hrg.wyhash.Wyhash64`, `ChecksumDatabase:114-152`, `MetadataCache:86` | done — § 4.2 decides how the tooling gets it |

Also already true, and easy to break: **no report carries source content** (DEC-027 § 2), and no `.java`
exists anywhere under a `.jcodebuddy/` tree (`GeneratorGuardTest`).

### 0.2 What this plan adds

1. **A class index** at `.jcodebuddy/index/classes.json` — one row per type the module compiles,
   **keyed by FQDN**, carrying `path`, `checksum`, `hashCalculatedAt`, `size`, `mtime`, `generated` and
   the type's `kind`, `modifiers`, `fqn` of the enclosing type, declaration `line` and nesting `depth`.
2. **FQDN references everywhere** — a metadata document names a type by its fully qualified name, and a
   document carries a `classIndex` pointer to the table. No ids, no positional indices, no hash keys.
3. **A checksum column** — `Wyhash64` over the content with CRLF normalised to LF, the same algorithm and
   the same normalisation the watch agent already uses, plus `hashCalculatedAt` (the timestamp that
   checksum was computed) and `size`/`mtime` as the cheap pre-filter. This is the reserved `hashes.json`
   from `plan.metadata-locations.md` § 2.3.2, folded into the class row instead of living in a second
   table.
4. **A generator-facing reader** — `ClassIndex` in the tooling: `row(fqn)`, `byPath(path)`,
   `changedSince(previous)`, so a generator asks the index instead of walking and parsing the tree.
5. **Adoption of the five source-map conventions** that `files.json` only half-honoured, stated as
   invariants with a test each (§ 3.2, § 5).

### 0.3 Non-goals

* **Any surrogate id — 4-byte, hashed, counter or positional.** Not in the index, not in a document, not
  in the renderer, not "for internal use". § 2.2 states the decision and its one cost.
* **The source-map v3 file format.** No `mappings`, no Base64 VLQ, no sectioned index map, no
  `sources`/`names` arrays, no `locations.map`, no hand-written decoder in either language. § 3.1.
* **A generated-Java pointer to the index.** A generated artifact naming the index row it came from (the
  `//# sourceMappingURL` idea) changes emitted Java, so it needs the DEC-021 header and DEC-022 naming
  contracts revisited and a full regeneration. § 10.
* **Source content.** Never, in any field (§ 7.9). The index records a hash, a size and a timestamp —
  never text.
* **A change to what the location metadata records.** The 315 locations, the eight roles, the artifact
  inventory and the rendered page are unchanged. This plan changes the *spelling of a file reference*,
  not the set of locations. The page must differ only in its run-record timestamp (§ 7.15).
* **A repository-wide or cross-module index.** One index per module. A foreign class is either absent or
  carried with its module name (§ 2.6) — never a merged global table.
* **A filesystem watcher.** The index makes incremental generation *possible*; it does not implement it
  (§ 4.5 is the reader API and one consumer, not a watch loop).
* **A binary/arena encoding.** The table is JSON — diffable, greppable, reviewable. `metadata-arena`
  stays the place for off-heap binary formats.

### 0.4 Baseline — confirm before you start

Run these four, and compare. If they do not match, stop and report; the tree is not at the revision
this plan assumes.

```
scripts\mvn-jdk25.cmd                    → BUILD SUCCESS, 7/7 modules
                                           api 4 · core 88 · tooling 324 · jackson 26 · hipster-entity-test 31  (473)
cd scripts ; bun test entity-html        → 24 pass, 0 fail
scripts\gen.cmd                          → "Link check: 305 links verified, 0 candidate(s) rejected, 0 stale"
                                           "Divergences: 0 (0 metadata note(s))"
git status --porcelain -- hipster-entity-example/src
                                         → empty (the pass is byte-identical on the committed sources)
```

Measured sizes at this revision (verified, and your baseline for the § 4.10 report):

| File | Bytes |
| --- | --- |
| `Auditable.metadata.json` | 6 834 |
| `PaymentMethod.metadata.json` | 23 573 |
| `Person.metadata.json` | 20 586 |
| **three documents together** | **50 993** |
| `.jcodebuddy/index/files.json` | 4 301 |
| `.jcodebuddy/index/README.md` | 2 699 |
| `index.html` (rendered) | 307 376 |

Measured content: **84 `at` lines** (8 714 bytes of `at` text), **315 location segments**, **45
artifacts**, **37 index rows**, **112 file references** in the documents. **The row count is not
measured — measure it in § 4.9 and record the real number.** `hipster-entity-example/src/main/java`
holds **37 files with a type declaration**; counting the member types as well
(`PersonSummary.Record`, `PersonSummary.Write`, `Person.Record`,
`PersonSummaryBuilderTracking.TrackingStrict`, and whatever else a full walk finds) gives the row count,
which this plan estimates in the low forties. Do not copy an estimate from this document into the report:
`grep` the declarations, or count the rows the pass writes.

> **Note on the working tree.** This plan follows DEC-027's renderer and DEC-028's central index, which
> are committed as `4d5065b metadata improvmeents`. Do not judge the tree by a clean `git status`:
> judge it by the four gate outputs above and by two files existing,
> `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ModuleFileIndex.java` and
> `hipster-entity-example/.jcodebuddy/index/files.json` (4 301 bytes). If the numbers or those files are
> absent, stop and report.

### 0.5 Ground rules for whoever executes this

1. **Every gate run is `clean`** (`scripts\mvn-jdk25.cmd` does `clean test`). A non-clean green is not
   evidence.
2. **A generator change regenerates the example in the same change.** `ExampleRegenerationTest`
   regenerates a copy of the committed tree and asserts byte-identity. If it goes red, regenerate and
   commit the output — never "fix the test". A green gate with a changed `hipster-entity-example/src`
   in `git status` means the example was not regenerated. (This plan must *not* change emitted Java, so
   that directory must stay empty.)
3. **A claim is worthless without the test that asserts it.** Every item in § 4 names its test.
4. **Keep commits separable**: (a) the index + hashing + reader, (b) document wiring + writer, (c)
   renderer, (d) docs.
5. **`.cmd` files are pure ASCII with CRLF.** If you touch one (you should not need to), edit it with
   an explicit ASCII/CRLF write, not an editor that normalises line endings.
6. **The renderer's inline script lives inside a JavaScript template literal** in `render.js`. No
   backticks, no `${…}`, no backslashes may appear in the emitted `<script>` (this plan adds no decoder,
   so it should not matter — but do not paste a template literal into it).
7. **Determinism.** No hash-order iteration, no `Files.walk` order leaking into output. Two passes over
   an unchanged tree must produce byte-identical `classes.json` **including** `hashCalculatedAt`, because
   an unchanged row keeps the timestamp it was first hashed at (§ 4.5). `entity-html.test.js` asserts
   page determinism and `ExampleRegenerationTest` asserts it for the Java.
8. **No `.java` under `.jcodebuddy/`, and no report directory that holds anything but JSON.**
   `GeneratorGuardTest` asserts both. The index carries no file content, so the first holds trivially;
   the second is why the tracked `README.md` lives in `index/` and not in `metadata/`.
9. **`.jcodebuddy/index/README.md` is tracked and human-owned** — the generator creates it when it is
   absent and *never* overwrites it. Changing its text is therefore a **manual** step (§ 4.7), not a
   side effect of a pass.
10. **No new module dependency without saying so.** `hipster-entity-tooling` depends on
    `hipster-entity-api`, `hipster-entity-core`, JavaParser and Jackson only (`DependencyBoundaryTest`,
    DEC-W003). § 4.2 is about exactly this and must be executed as written, including the golden-vector
    test.

---

## 1. What the index is for

Four consumers, and what each asks the index. If a design choice does not serve one of these, it does
not belong in the table.

| Consumer | Question | Answer it needs from one row |
| --- | --- | --- |
| The entity-metadata generator | "which file declares this type, and at which line" | `path` + `line` |
| Any other generator (mapper, adapter, validator, report) | "does this type exist, what is it, has it changed since I generated" | `kind`, `modifiers`, `checksum`, `hashCalculatedAt` |
| A watcher / incremental pass | "what changed without reading the tree" | `checksum` + `size` + `mtime`, compared against the previous table |
| A human reading a diff, or an IDE refactoring | "what is this, and does my rename reach it" | the **FQDN key** and the whole row, in plain text |

The previous draft served the fourth consumer and the *first half* of the first, at the cost of making
rows 1–3 harder and of introducing a key no IDE could maintain. This plan inverts that: the table is the
widest-audience artifact, it is keyed by the name every other tool already uses, and the one consumer with
a specialist need (where is a field's accessor) keeps its `at` maps, keyed by the type's FQDN.

---

## 2. Decisions

### 2.1 One row per type, keyed by FQDN

`classes.json` holds a JSON object whose **keys are fully qualified type names** and whose values are
rows:

```jsonc
{
  "format": 1,
  "module": "hipster-entity-example",
  "sourceRoot": "src/main/java",
  "hash": { "algo": "wyhash64", "normalize": "lf", "of": "content" },
  "classes": {
    "hr.hrg.hipster.entityexample.person.entity.PersonSummary": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
      "size": 1837, "mtime": 1757000000000, "generated": 0,
      "checksum": "3f2c8d91a4b7e601", "hashCalculatedAt": "2026-05-14T09:12:33Z",
      "kind": "interface", "modifiers": ["abstract", "public"],
      "enclosing": null, "line": 14, "depth": 0
    },
    "hr.hrg.hipster.entityexample.person.entity.PersonSummary.Record": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
      "size": 1837, "mtime": 1757000000000, "generated": 0,
      "checksum": "3f2c8d91a4b7e601", "hashCalculatedAt": "2026-05-14T09:12:33Z",
      "kind": "record", "modifiers": ["public"],
      "enclosing": "hr.hrg.hipster.entityexample.person.entity.PersonSummary", "line": 26, "depth": 1
    }
  }
}
```

* **The key is the FQN of the type**, package-qualified, nested types joined with `.`
  (`…PersonSummary.Record`) — the canonical form an IDE, `grep` and a Java consumer all understand.
  Member types are **their own rows**, so every reference in the tree can be an FQDN; the file-level
  facts (`path`, `size`, `mtime`, `checksum`, `hashCalculatedAt`, `generated`) are therefore repeated
  for each type declared in the same file. That duplication is **accepted and deliberate**: there are 2
  member types against 37 top-level ones in the example, so it costs two `path` strings and two hashes
  to make every key a type key. (If a future module is nested-heavy and the bytes matter, the fix is a
  `files` legend the rows point into — § 10 — not a second key scheme.)
* `enclosing` is the FQN of the enclosing type, or `null` for a top-level type; `depth` is 0 for
  top-level and 1+ for member types. `path` is always the file that declares the type.
* `kind` is exactly `MetadataLocations.kindOf`'s vocabulary (`class`/`interface`/`enum`/`record`/
  `annotation`) — one implementation, reused (§ 4.3).
* `modifiers` is the **sorted** list of Java modifier keywords for the type declaration, restricted to
  `public`, `protected`, `private`, `abstract`, `static`, `final`, `sealed`, `non-sealed`, `strictfp`.
  Sorted, not source order, so a reordered modifier list is not a diff. Annotation modifiers are not
  members of this list.
* `line` is the type declaration's start line, 1-based, from JavaParser's position — the same fact and
  the same source as `ArtifactMeta.line`.
* `generated` is `1` when the pass wrote the file (the DEC-021 header rule and `artifacts[].generated`
  already establish this), `0` otherwise. It is the one field whose value a later pass improves: the
  first pass to see a freshly written artifact knows it wrote it.

### 2.2 No id — the decision, and its one cost

**There is no id anywhere in this design.** Not a 4-byte hex value, not a hash, not a counter, not a
positional index, not a "local id for compactness" in the documents.

The reasoning, recorded so it is not re-litigated:

1. **An id is a fact about our pass, and an FQN is a fact about the code.** Every id scheme pays for its
   brevity with a mapping that only the writer can maintain — a hash is unreadable, a counter is
   unstable, a positional index is a function of the document, and a path-derived value breaks on a move.
   The FQN needs no mapping: it is the name the compiler, the IDE, the debugger and the stack trace use.
2. **Refactor safety is a hard requirement, not a nicety.** This repository's generators write committed
   Java that must stay navigable under a stock IDE (`AGENTS.md` § 1, DEC-019, DEC-022). A surrogate id in
   a text file is the one reference an IDE rename refactor **cannot** update — the FQN is the one it can,
   in any file type. Choosing a shorter key here would hand the project a class of silent stale
   references that its own decisions exist to prevent.
3. **A 4-byte id does not survive the content it names.** 2³² values over a module's types, with a hash
   function whose collisions must be detected and reported, buys nothing that the FQN does not already
   give — and the requirement it was proposed for (join key across generators) is met better by a name
   that is also the join key across *tools*.
4. **The brevity it buys is small.** Measured at this revision, the documents' 112 file references cost
   ≈1 906 bytes as readable ids. As FQDNs they grow (≈30 characters each, ≈3 360 bytes); as 8-hex ids
   they shrank to ≈900. **This plan accepts the growth.** The documents are derived, ignored output; a
   few hundred bytes of repeated FQDN is not worth a private key space. § 2.7 states the accounting
   plainly and forbids selling this plan as a size optimisation.

The one cost, stated honestly: **an FQDN is not stable under a package or type rename.** A row's key
changes, and a document that referenced the old name holds a stale reference until the same pass
regenerates it. That is the *correct* behaviour for a generated document — the pass sees the rename (the
IDE updated the Java, and the pass reads the Java), emits the new name, and the old name is reported as a
removed row plus an added row by `changedSince` (§ 4.4). The alternative (a stable surrogate key) would
hide the rename from every consumer and leave the tree with a reference that no IDE can fix. Record this
reasoning in the README (§ 6.1) and in DEC-029.

### 2.3 The checksum: `Wyhash64` over LF-normalised content

```
bytes  = Files.readAllBytes(file)
bytes  = normaliseCrLfToLf(bytes)                 // identical to ChecksumDatabase:136
checksum = String.format("%016x", Wyhash64.hash(0, bytes))
```

* **The algorithm is the repository's existing one.** `ChecksumDatabase.calculateChecksum` (the watch
  agent's table) already hashes text files with `Wyhash64.hash(0, normalised)` and formats `%016x`;
  `MetadataCache.calculateChecksum` does the same. Using the same algorithm and the same normalisation
  means the index and the watch tables agree about what "the same content" means, which is the entire
  reason DEC-028 reserved a single hash table rather than letting each component pick one.
* **LF normalisation is not optional.** A CRLF checkout and an LF checkout of identical content must
  produce the same checksum, or the table is useless the moment two developers share it (the reserved
  design in `plan.metadata-locations.md` § 2.3.2 says so, and it is already implemented at
  `ChecksumDatabase:136` — reuse that code's behaviour, do not re-invent it).
* **`checksum` is never a correctness input for the location metadata.** A missing, unreadable or
  algorithm-mismatched table forces a full pass. The header's `hash.algo` + `hash.normalize` are the
  licence to skip, and a mismatch is a full pass, never a guess (§ 4.5).
* **`hashCalculatedAt` is the timestamp that checksum was calculated** — the field the requirement asks
  for. It is an ISO-8601 UTC instant with `Z` and second precision, and it is **preserved from the
  previous table when the checksum is unchanged**, so it dates the content rather than the build (§ 4.5).
  Test-injectable clock, so the table is assertable.
* **`size`** is `Files.size(file)` — the bytes actually hashed, before normalisation, which is what a
  human and a diff tool mean by "file size". **`mtime`** is `Files.getLastModifiedTime(...).toMillis()`,
  a pre-filter for a watcher. Both are stored for every row (so reading the table never requires the
  filesystem) and both are excluded from the checksum.
* **`hash` header** names both: `{ "algo": "wyhash64", "normalize": "lf", "of": "content" }`.

### 2.4 Scope: every type the module compiles

The index is built by walking the module's source roots (`sourceRoot`, plus each extra root the pass is
configured with — `--packages` narrows *which views are generated*, never which files are indexed) and
hashing and parsing every `*.java` under them, **plus** every artifact file the pass writes.

* "Every type the module compiles" is what makes the table useful to *other* generators: a mapper that
  mentions `PersonDetails` must find a row for it even if no marker document mentions it today.
* The walk is **sorted by path** before hashing so that a failure reports deterministically and the
  insert order cannot leak into the output (the row order is the key sort, § 2.6).
* A file the parser cannot read cleanly is **not** silently skipped: a row is written for it with its
  checksum, size and `mtime`, `kind`/`modifiers` left empty and a `parseError` string, and the pass
  reports it as a note. This is the `SourceReader` contract (JavaParser is error tolerant and returns a
  partial unit, F-34) — the index must not turn a partial parse into a missing row.
* A file that declares **no type** (a `package-info.java`, or a file that is only comments) contributes
  **no row**: the table's key space is types, and a file with none has no key. Do not invent one, and do
  not add a file-keyed row for it.

### 2.5 The table's shape, and its order

```jsonc
// <module>/.jcodebuddy/index/classes.json
{
  "format": 1,
  "module": "hipster-entity-example",
  "sourceRoot": "src/main/java",
  "hash": { "algo": "wyhash64", "normalize": "lf", "of": "content" },
  "classes": { "<fqn>": { …row… }, … }
}
```

* `classes` is keyed by FQN and **written in ascending lexicographic key order**, so the file's row order
  is a property of the names, not of the walk — deterministic, diff-friendly, and independent of
  `Files.walk` order.
* `format` is the table's own version. **A consumer that does not recognise it must refuse the table and
  say why, never guess** (the rule `files.json` already states).
* `module`, `sourceRoot` are diagnostics, so the file is self-describing when opened directly.
* One reserved header value, defined now and written only when used: `generator` (the tooling revision
  that wrote the table — the "options are part of the fingerprint" rule of
  `plan.metadata-locations.md` § 2.3.2).
* **No timestamps at the table level.** The only instant in the file is each row's `hashCalculatedAt`,
  which exists to date content and is stable while the content is (§ 4.5). A pass-level "generated at"
  belongs in the run record, not in a table a project may commit.

### 2.6 What happens to `files.json`

**It is replaced, not kept.** `classes.json` states every path `files.json` stated, so keeping both
would state every path twice — the exact failure DEC-028 § 3 exists to prevent.

* `ModuleFileIndex` is **renamed and re-scoped** to `ClassIndex` (same package). It keeps the location
  rule (`<nearest .jcodebuddy above the report dir>/index/`, the fallback `<report dir>/index/`, the
  README-never-overwritten rule) and loses the readable-id machinery (`freeze`, `qualify`,
  `simpleNameOf`, `packageSegments`, `packageSuffix`, `idFor`, `pathFor`, `files()`, `isModuleRelative`'s
  second caller). Its replacement is `put(type, …)` / `row(fqn)` / `byPath(path)` / `write()`.
* Documents swap `fileIndex` for `classIndex`, and every `file` value becomes an **FQDN** (§ 4.6).
* The tracked `hipster-entity-example/.jcodebuddy/index/README.md` is deleted and regenerated from the
  new text (§ 4.7, ground rule 9).
* DEC-028's readable id scheme is **superseded**: the ids it produced (`entity.Person`,
  `PersonSummaryBuilderTracking`) disappear from the documents, and the documents' `file` values become
  the FQNs they were only approximating. The disambiguation value survives *for free* — the FQN is
  unique where `PersonSummary` was not, and the three `Person` types in the example
  (`person.entity.Person`, `person.iface.Person`, `person.record.Person`) are distinguished by their
  package, which is exactly what `entity.Person` was manually encoding. **This is a simplification, not
  a regression**: one scheme (the language's own) replaces two.
* **Legacy reading, one revision** (§ 4.8): a document carrying `fileIndex` and readable ids still
  parses, resolving through `files.json` when it is present. Nothing emits two spellings.

### 2.7 Honest accounting

| Quantity | Today | After | Δ |
| --- | --- | --- | --- |
| `files.json` | 4 301 B | — | |
| `classes.json` (≈41 rows against 37 files; see below) | — | ≈12 000–15 000 B | +≈8 000–11 000 B |
| documents: 112 file references (readable ids, ≈17 chars) | ≈1 906 B | ≈3 360 B (FQDN, ≈30 chars) | **+≈1 450 B** |
| documents: `at` maps | 8 714 B | 8 714 B | 0 (kept) |
| documents: everything else | ≈40 373 B | ≈40 373 B | 0 |

**The projection is estimated, not prototyped, and you must measure it in § 4.10** — the withdrawn plan
prototyped its encoder and this plan is not going to pretend to a precision it has not earned. The
estimate's basis: an FQDN key plus a path in the key and in the row is ≈90 characters of text per row, a
checksum and a timestamp ≈50, so ≈180–260 bytes per row for ≈41 rows.

**Say this plainly in the final report and in DEC-029: this plan makes the metadata *bigger*.** It costs
≈8–11 KB of new table and ≈1.5 KB of document growth. It buys (a) a class manifest with content
identity, kind, modifiers and a checksum timestamp that every generator currently re-derives by walking
and parsing, (b) the `hashes.json` reservation cashed in, as a table the watch agent can consume, and
(c) one addressing scheme — the language's own — where there are two. It is **not** a size optimisation
and must never be sold as one. If the table exceeds ≈20 KB, or a row carries a fact twice beyond the
accepted per-type file-fact repetition of § 2.1, stop and re-examine before documenting.

### 2.8 Decisions assumed in this plan (overturn before § 4.1 if wrong)

1. **FQDN is the key and the reference** (§ 2.1, § 2.2), with member types as their own rows.
2. **The checksum is `Wyhash64`, and the tooling gets it by copy** (§ 4.2), rather than a new Maven
   dependency or a second algorithm.
3. **One `classes.json`** replaces `files.json` (§ 2.6), rather than sitting beside it.
4. **The table is JSON**, not a binary arena format (§ 0.3).
5. **`classes.json` is written by the generator pass**, in the same pass as the documents — so the index
   is derived output and follows the same track policy as `metadata/`. A separate `index` command, or an
   IDE plugin owning it, is a different product decision and is out of scope.
6. **Documentation**: a new **DEC-029** ("the module class index: basic class metadata, keyed by FQDN"),
   a **DEC-026 amendment** (the file name inside `index/`), and a **DEC-028 amendment** (superseding the
   readable id scheme and the `fileIndex` pointer, absorbing the reserved `hashes.json`).

---

## 3. What is reused from source maps, and what is not

### 3.1 The source-map **format** is dropped — and why, precisely

The withdrawn draft re-expressed the location payload as a sectioned source map v3: Base64 VLQ
`mappings`, a `sources`/`names` array per section, `generatedColumn = artifactId × 9 + roleOrdinal`,
`.jcodebuddy/index/locations.map`. Three reasons it is the wrong container here, recorded so the decision
is not re-litigated:

1. **It solves a problem we do not have.** A source map is a *positional join for a reader with a program
   counter* — a debugger walking generated lines in order. Our reader asks a **random-access question**
   ("the row for `…PersonSummary.age`") and a **set question** ("what changed since the last pass"). VLQ
   + delta columns is optimal for the first access pattern and hostile to the second: you cannot skip,
   index or binary-search a delta-encoded string.
2. **It buys no interoperability, which was its stated benefit.** The mapping is not standard — a stock
   consumer would still have to be taught `generatedColumn = artifactId × 9 + roleOrdinal`,
   `x_jcodebuddy.roles` and `x_jcodebuddy.ids`. It reads the envelope and nothing else. A hand-written
   decoder in Java *and* in JavaScript is a second private format wearing a public name — the opposite of
   the goal.
3. **It has nowhere to put the facts this plan exists to record.** Checksum, `hashCalculatedAt`, `size`,
   `mtime`, modifiers, kind — a source map is a positional table, not a class manifest. Every one of them
   would have to be smuggled into `x_jcodebuddy.*`, which is the tell that the format is a host, not a
   fit.

The withdrawn plan's "defect 1" (its `at` maps cost 8 714 bytes) is **not a defect this plan addresses**:
those bytes buy a shape the Java model, the renderer and every existing test already consume, and the
withdrawn plan's own measurement shows the VLQ form would save 6 KB in a 51 KB tree at the cost of two
decoders. Compressing a document nobody has complained about, while leaving generators with no class
manifest, is optimising the wrong axis — which is the criticism this plan is the answer to.

### 3.2 The source-map **conventions** are adopted — five invariants, one test each

This is the part of the direction that is right, and it is a requirement of this plan rather than a
rationale: these are the conventions that make a source map usable by many unknown consumers, and they
are what `files.json` implemented halfway.

| # | Convention (from source maps) | What this plan does | Test |
| --- | --- | --- | --- |
| 1 | **One table states a path once; everything else addresses it indirectly.** `sources` is the only place a path appears; a consumer resolves through it. | `path` appears once per module type, in `classes.json`, resolved through the FQN key — and nowhere else in the metadata tree. A document names a type, never a path. | `ClassIndexTest`, `MetadataFileIdsTest` (re-pointed) |
| 2 | **Paths are root-relative and never absolute or `..`** (`sourceRoot` applied to every `sources` entry). | `classIndex`'s `sourceRoot` header + the rule that the row's `path` is module-relative, forward slashes, no `..`, no drive letter. Reuse `ModuleFileIndex.isModuleRelative` verbatim. | `ClassIndexTest`, `MetadataSourcePathTest` |
| 3 | **The pointer is emitted, not assumed** (`sourceMappingURL`): a file says where its table is, so a consumer never guesses a layout. | A document carries `classIndex`, the relative path from the document's own directory, computed by the writer; the fallback layout therefore works without a special case in a consumer. | `ClassIndexTest`, `MetadataSourcePathTest` |
| 4 | **Content is not carried** (`sourcesContent` exists; correct producers leave it out). | The index records `checksum`, `size`, `mtime`, `hashCalculatedAt` — **never** content, and never a `content`/`sourcesContent` key. Restate the DEC-027 § 2 rule for the new file. | `GeneratorGuardTest` + a `ClassIndexTest` assertion that no row has a content-ish key |
| 5 | **The table is content-addressed, so an unchanged tree produces identical bytes.** | § 4.5: `hashCalculatedAt` preserved while `checksum` is unchanged, keys derived from names, rows in key order, no pass-level timestamp — two passes byte-identical. | `ClassIndexTest`, `ExampleRegenerationTest` |

Two source-map ideas are **deliberately not used**, and saying so is part of the decision:

* **Section offsets** (`offset.line` composing several maps into one address space) exist to compose maps
  whose generated line spaces would otherwise collide. Our key space is FQDNs, which cannot collide, so
  there is nothing to compose and no offset to maintain.
* **The FQDN is not a `sources`-style *positional* table.** In a source map, `sources` is positional
  because the body is numeric; here the key *is* the name, which is the point (§ 2.2). A positional table
  would be a surrogate id by another name.

**Convention 3 is the one that keeps real value for the future**, and the format that has made it
ubiquitous is worth continuing to read for that reason alone: a generated artifact naming the index row
it came from is the file-level output→input pointer this plan leaves on the table (§ 10).

---

## 4. Work items

Each item: **change → files → why → how you know it worked.**

### 4.1 `TypeFacts` — one type declaration's basic metadata

* New `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java`:
  `record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth)`.
* A static `TypeFacts.of(TypeDeclaration<?> declaration)` that:
  * computes `fqn` from the compilation unit's package (`declaration.findCompilationUnit()`,
    `getPackageDeclaration()`; the default package yields the bare dotted name) plus the enclosing chain
    (`getParentNode()` walked up through `TypeDeclaration`s), joined with `.`;
  * sets `enclosing` to the enclosing type's FQN, or `null`;
  * sets `depth` to the number of enclosing types;
  * takes `kind` from **`MetadataLocations.kindOf`** — do not write a second kind resolver (§ 0.1);
  * takes `modifiers` from `declaration.getModifiers()`, mapped to the keyword's `asString()` (so
    `non-sealed` is spelled as the source spells it), filtered to the vocabulary of § 2.1 and **sorted**;
  * takes `line` from `declaration.getBegin().map(p -> p.line).orElse(-1)` — the same accessor
    `MetadataLocations.lineOf` uses.
* **Why:** FQN construction and modifier extraction are the pieces with real edge cases (nested depth,
  `non-sealed`, annotation declarations, records, the default package) and they must be unit-testable
  without a pass.
* **Check:** `TypeFactsTest` — a source string with a nested record, a package-private class, a sealed
  interface with a `non-sealed` implementor, an annotation type, a static nested class, and one unit with
  no package declaration; assert `fqn`/`enclosing`/`depth`/`kind`/`modifiers`/`line` for each.

### 4.2 The content hash, and how the tooling gets it

* New `…/tooling/index/ContentHash.java`:
  * `static String of(Path file)` → read all bytes, normalise CRLF→LF (the exact behaviour of
    `ChecksumDatabase.normalizeLineEndings`), `Wyhash64.hash(0, bytes)`, `String.format("%016x", …)`;
  * `static byte[] normalizeLineEndings(byte[])` — a copy of the watch agent's normaliser, with the same
    semantics (`\r\n` → `\n`, lone `\r` → `\n`).
* **How the tooling gets `Wyhash64` — execute this as written.** `hr.hrg.wyhash:wyhash:1.0.0` is a tiny
  third-party library already on the classpath of `java-watch-core` (`java-watch-core/pom.xml:21`) and
  declared in the root pom's `dependencyManagement` (`pom.xml:209`). `hipster-entity-tooling` must **not**
  gain a new dependency for it: vendor the algorithm as `…/tooling/index/Wyhash64.java` behind the same
  public shape (`hash(long, byte[])`, and the streaming form only if something uses it — it does not),
  under the library's own license header, with a comment naming the upstream artifact and version.

  **This is a deliberate cost, and its mitigation is mandatory:** a copy can silently diverge from the
  library the watch agent uses, and then the two tables disagree about "the same content" — the exact
  failure the single-hash reservation existed to prevent. Therefore `ContentHashTest` **must** carry
  **golden vectors produced by the library**: a fixed fixture (an empty array, `"a"`, a CRLF and an LF
  variant of the same text, and one file of ≥12 KB so a buffered path is not the only path exercised)
  with the 16-hex values the *library* produces. If the copy and the library ever disagree, that test is
  where it is caught. Record this in DEC-029 as the accepted risk with its mitigation, and record the
  alternative that was rejected (adding the `wyhash` dependency to the tooling, which is the cheaper
  option if the copy ever proves hard to keep honest).

  If the executor finds they cannot produce library-derived golden vectors (no network, no local
  artifact), **stop and report** rather than writing self-derived vectors — a copy tested against itself
  proves nothing.
* **Why:** the checksum is the field that makes the index useful to a watcher and an incremental pass, and
  it is the one field whose *algorithm* is a cross-module contract.
* **Check:** `ContentHashTest` — the golden vectors; CRLF and LF forms of the same text hash equal; two
  different contents hash different; a ≥12 KB file hashes to its library vector.

### 4.3 `ClassIndex` — the table, the writer, and the reader

* `ModuleFileIndex` → `…/tooling/index/ClassIndex.java` (see § 2.6 for what it keeps and loses):
  * **Keeps**: `forPass(reportDir, moduleRoot, sourceRoot)`, `nearestJcodebuddy`, `moduleRelative`,
    `isModuleRelative`, the `indexDir`/`indexFile`/`moduleName`/`sourceRoot` accessors, `pointerFrom`,
    and the README rule (`writeReadmeIfAbsent`, never overwrite). `FILE_NAME` becomes `classes.json`.
  * **Gains**:
    * `put(TypeFacts type, Path fileOnDisk, boolean generated)` — the row builder: resolves the row's
      file facts (`path` module-relative, `size`, `mtime`, `checksum`, `hashCalculatedAt` from the
      injected clock, § 4.5), keys the row by `type.fqn()`, and inserts with
      `IllegalStateException` if the FQN already has a row for a **different** path (a duplicate type —
      a real bug, and the only collision this design can suffer, which is worth stating: **FQN keys are
      unique by the language's own rules**, so no hash-collision machinery is needed at all).
    * `row(String fqn)`, `byPath(String path)` (all types declared in a file), `rows()` (ascending key
      order — the write order).
    * `merge(ClassIndex previous)` — for each row: if `previous` has the same FQN, `path` and `checksum`,
      keep `previous.hashCalculatedAt`; otherwise stamp the current instant. This is the *only* place a
      timestamp is decided (§ 4.5).
    * `changedSince(ClassIndex previous)` → `List<Change>` where a `Change` is
      `(fqn, path, kind)` with `kind ∈ {added, removed, content, renamedType}`: `added`/`removed` by FQN;
      `content` when the FQN is present in both and `checksum` differs; `renamedType` when the **same
      path** appears with a different FQN in the two tables — the rename case of § 2.2, reported rather
      than hidden. Kind, modifiers and `generated` are deliberately not change signal, because a change
      to any of them also changes the file's content, which `content` already reports.
    * `read(Path indexFile)` — the static parser: returns the table and **refuses an unrecognised
      `format`**. Reading must be lenient about unknown *extra* keys and strict about the version; a
      consumer never guesses.
    * `write()` — the JSON writer, `appendJsonField`-style (the documents' hand-rolled discipline: a
      wrong comma produces a file only tests reject).
  * **Loses**: `freeze`, `qualify`, `simpleNameOf`, `packageSegments`, `packageSuffix`, `idFor`, `pathFor`,
    `files()`, the whole `collected`/`idToPath`/`pathToId` triple, and the `add`-before-`idFor` freeze
    dance — with FQDN keys there is no assignment step and no order dependency to protect.
* **`README_TEXT` is rewritten** (§ 6.1) — the tracked README describes the new table.
* **Why:** one place hashes bytes, dates content, keys rows by name and writes the table; and one place
  can answer "what changed", which is what makes the table worth more than `files.json`.
* **Traps:**
  * The row for a **generated artifact** must be written by the same pass that writes the artifact, so
    `put(...)` for it must run **after** the file exists — otherwise `size`/`checksum` describe the
    previous revision. This is the same ordering constraint as DEC-028's "index after artifacts" (§ 4.6).
  * A row whose file vanished between the walk and the hash is a **fatal** diagnostic naming the path, not
    a row with a stale checksum.
  * `merge(previous)` compares FQN, `path` **and** `checksum`; comparing only the checksum would carry a
    timestamp across a rename, and comparing only the FQN would keep a timestamp after an edit.
  * Two types with the same FQN in one pass (two source roots, a duplicated file) is a **fatal**
    diagnostic naming both paths — never "last one wins", which is the F-44 failure this repository has
    already paid for.
* **Check:** `ClassIndexTest` — see § 5.

### 4.4 The generator-facing reader

* A thin, documented API on `ClassIndex` (not a second class): `row`, `byPath`, `changedSince`. That is
  the whole surface a generator needs, and it is deliberately *read* API on the same type that writes, so
  there is one key implementation.
* One consumer in this plan, to prove it is usable rather than ornamental: the entity pass itself uses
  `row(fqn)`/`byPath(path)` where it used `ModuleFileIndex.idFor(path)` (§ 4.6), and reports
  `changedSince` against the table it read at the start of the pass, as a **note** (not a gate): "index:
  41 rows, 3 added, 2 content changes, 0 removed, 1 renamed". That note is the incremental pass's kernel
  and it costs nothing to emit; it is deliberately not a second generation path (§ 0.3).
* **Why:** the requirement is an index "likely useful to many generators". A reader that only the writer
  can drive is a private cache; this is the published one.
* **Check:** `ClassIndexTest` covers `changedSince` for all four change kinds using two hand-built
  tables; `MetadataSourcePathTest` exercises `row`/`byPath` through the real pass.

### 4.5 Reading the previous table, and the timestamp rule

* The pass, before it walks:
  1. reads `<index dir>/classes.json` when it exists (`ClassIndex.read`);
  2. **refuses** it — and therefore does a full pass — when `format` is unrecognised, when
     `hash.algo`/`hash.normalize` do not match this build, or when the file is unreadable/corrupt. A
     refusal is a one-line note, never a crash;
  3. uses the previous rows for three things only: **carrying `hashCalculatedAt` forward on unchanged
     content** (§ 2.3), **reporting `changedSince`** (§ 4.4), and **skipping re-parsing** a file whose
     `checksum` is unchanged *only if* the previous row's type facts are present and no `parseError` is
     recorded. (The last is an optimisation with an obvious correctness rule — if in doubt, parse. If the
     optimisation is not clearly correct on the example, drop it; the table is the deliverable, the skip
     is not.)
* **The timestamp rule, stated once:** `hashCalculatedAt` changes **if and only if** the row's `checksum`
  changes (or the row is new). An unchanged tree therefore produces a byte-identical table on every pass —
  which is what makes the table committable and diffable (convention 5, § 3.2). The clock is injected, so
  a test can assert bytes across two passes with a fixed clock and with an advancing one.
* **Check:** `ClassIndexTest` — two passes, one clock advancing by an hour between them, unchanged tree ⇒
  identical bytes; then edit one byte ⇒ exactly the rows of that one file change `checksum` and
  `hashCalculatedAt`.

### 4.6 Document wiring: `fileIndex` → `classIndex`, FQDN everywhere

* `EntityMetadataGenerator`:
  * `generateInternal` constructs `ClassIndex.forPass(...)` where it constructed `ModuleFileIndex`, and
    writes it **after** the artifact files exist and **before** the documents are written (the DEC-028
    order, unchanged).
  * `toJson(entityMeta, classIndex)`:
    * root: `classIndex` = `classIndex.pointerFrom(documentDir)` (the emitted pointer, convention 3);
    * `markerFile`, `views[].file`, `properties[].file`, `allFields[].file`, `artifacts[].file` hold the
      **FQN** instead of the readable id — the keys themselves do not change, so the renderer's shape is
      untouched and the resolution is one table lookup by name;
    * everything else is byte-for-byte what it is today (the same helpers, the same indentation).
  * `fromJson(json, ClassIndex)` resolves FQNs → paths so `EntityMeta.markerSourcePath`, `ViewMeta.sourcePath`,
    `Property.sourcePath` and `ArtifactMeta.file` keep the meaning DEC-028 § 8 gives them — **the Java model
    does not change shape** (§ 7.10).
  * One new note per pass: the `changedSince` summary of § 4.4.
* **Why:** the documents stop carrying surrogate names and start using the language's own; the page's
  contract (a `file` value that resolves to a path) is kept exactly.
* **Traps:** a `file` value with no row is a **bug**, not a fallback — fail loudly naming the FQN (as the
  current `idFor` names the path); a document that cannot resolve a reference is a broken document. A
  member type referenced as `PersonSummary.Record` needs its **own row** (§ 2.1) — if the writer only
  indexed top-level types, that reference would dangle, so the pass must index member types too.
* **Check:** `ConvertFrom-Json` parses the table and every document; `MetadataFileIdsTest` (re-pointed)
  asserts no path appears outside `classes.json`; `MetadataSourcePathTest` (re-pointed) asserts every
  document FQN resolves.

### 4.7 The tracked README, deleted and regenerated

* **Manual step (ground rule 9):** delete the committed
  `hipster-entity-example/.jcodebuddy/index/README.md` so the next pass recreates it from the new
  `README_TEXT`; never edit it from the generator, and never let the pass overwrite an existing one.
* The new text describes: the table and its purpose; `format`/`module`/`sourceRoot`/`hash`; one row per
  type **keyed by FQN**, with `path`/`size`/`mtime`/`generated`/`checksum`/`hashCalculatedAt`/`kind`/
  `modifiers`/`enclosing`/`line`/`depth`; **why the key is the FQDN and not an id** (refactor safety — an
  IDE rename updates FQNs in text files and cannot update a surrogate id), with the rename cost stated;
  the checksum rule and the LF normalisation; the determinism rule (the timestamp changes only with the
  checksum); the reserved `generator` header value, the unbuilt dependency edges and the
  generated-Java-pointer follow-up; and the tracked-and-human-owned rule.
* **Check:** `IndexLayoutTest` (re-pointed) — the directory holds exactly `classes.json` and `README.md`;
  README created when absent, **never** overwritten (sentinel round-trip); fallback directory gets the
  table and no README.

### 4.8 Legacy reading

* `fromJson` accepts, for one revision: `classIndex` **or** `fileIndex`; an FQN **or** a DEC-028 readable
  id (resolved through `files.json` when it is present) **or** a `sourcePath`/`markerSourcePath` string;
  and a document with no index pointer at all (DEC-028's legacy shape). Nothing emits two spellings.
* The renderer keeps its two-route lookup: module root first, then the document's own pointer; a missing or
  unrecognised table is the same loud `html_index_missing`, rendering nothing.
* **Check:** the legacy cases in § 5.1 and the legacy bun test.

### 4.9 Regenerate, measure, and report

* `scripts\gen.cmd` — expect **no change** under `hipster-entity-example/src` (this plan must not change
  emitted Java) and a re-rendered page.
* Record the measured sizes and compare them to § 2.7 and § 0.4:
  * `classes.json` bytes (estimate 12–15 KB) and its **exact row count** — count the types in the example
    and reconcile it with the 37 files, including every member type (the count in § 0.4 is an estimate and
    must be replaced by the real number);
  * the three documents' total (expect a small rise from 50 993);
  * the `index.html` diff against the pre-change file — **the run-record timestamp and nothing else**.
    Any other difference is a defect in the wiring, not a licence to accept it (§ 0.3).
* Report, explicitly: the row count, the table's bytes, the documents' bytes, the observed `changedSince`
  note, the `hashCalculatedAt` behaviour across two consecutive passes, and the **honest statement that
  the metadata grew** (§ 2.7).
* **Delete `plans__p2/plan.sourcemap-locations.md`** in the same change — it is withdrawn and replaced by
  this file (§ 0.2 preamble). Do not leave two plans that contradict each other. (It is still on disk when
  this plan was written, on purpose: the deletion belongs to the change, not to the writing of the plan.)
* **Check:** § 7's commands, and a written explanation of every `index.html` difference.

---

## 5. Tests

### 5.1 Java (`hipster-entity-tooling/src/test/java/…/tooling/`)

| Test | Asserts | Fails when |
| --- | --- | --- |
| `TypeFactsTest` (new) | `fqn`/`enclosing`/`depth`/`kind`/`modifiers`/`line` for a nested record, a static nested class, a package-private class, a sealed + `non-sealed` pair, an annotation declaration, and a unit with no package; modifiers are sorted and restricted to the vocabulary | the FQN drops the package or the enclosing chain, depth is flat, kind is a second implementation, or modifiers come from source order |
| `ContentHashTest` (new) | **library-derived golden vectors** for the empty array, `"a"`, a CRLF/LF pair, and one ≥12 KB fixture (§ 4.2); CRLF and LF forms hash equal; different content hashes different | the vendored `Wyhash64` diverges from the library, or LF normalisation is missing/wrong |
| `ClassIndexTest` (new) | `format: 1`; rows **keyed by FQN** in ascending key order; every member type has its own row with the right `enclosing`/`depth` and its declaring file's facts; a row per source file's types and per generated artifact with `path`, `size`, `mtime`, `checksum` (16 hex), `hashCalculatedAt` (ISO-8601 UTC), `generated`, `kind`, `modifiers`; **no row carries content or a content-ish key**; a path is module-relative with no `..`/drive letter; a duplicate FQN from two paths **fails loudly naming both**; two passes on an unchanged tree with an advancing clock are **byte-identical**; one edited byte changes exactly that file's rows' `checksum` + `hashCalculatedAt`; `changedSince` reports all four kinds including `renamedType`; a file declaring no type contributes no row | a missing member row, a duplicate FQN accepted, a timestamp that moves without a checksum change, a path leaking as absolute, or a self-derived (non-library) hash vector |
| `IndexLayoutTest` (re-pointed) | `.jcodebuddy/index/` holds `classes.json` and `README.md` and **nothing else** (`files.json` absent); the README is created when absent and **never overwritten** (sentinel round-trip); the fallback `<report dir>/index/` gets the table and no README | a reserved table is built prematurely, or a pass clobbers a tracked README |
| `MetadataFileIdsTest` (re-pointed) | every `file` value in every document is an FQN present in `classes.json`; **no module-relative path string appears anywhere in a document**; the `classIndex` pointer resolves | a path leaks back into a document, or an FQN does not resolve |
| `MetadataSourcePathTest` (re-pointed) | a document's FQN resolves through the table to the module-relative path; the declaring-file assertions (inherited/addon) still hold; every row's `path` is module-relative and exists; a member-type reference (`PersonSummary.Record`) resolves to its own row; `fromJson` without a table leaves references unresolved (null) rather than inventing a path; the legacy `fileIndex`/readable-id path still resolves | a path is absolute, project-relative, or guessed; a member type dangles |
| `MetadataLocationsTest` (unchanged) | the 315 locations, the eight roles and the artifact inventory are exactly what DEC-028 recorded | this plan changed what is recorded (it must not) |
| `ExampleRegenerationTest` (unchanged) | generated `.java` is byte-identical | the pass changed emitted Java (it must not) |
| `GeneratorGuardTest` (extended by one case) | no `.java` under any `.jcodebuddy/`; report dir holds only JSON; **`classes.json` and the README are the only files in `index/`**; `classes.json` holds no source text | the index write lands somewhere new, or a row smuggles content in |
| `DependencyBoundaryTest` (DEC-W003, unchanged) | the tooling's dependency set is unchanged — which is the assertion that the `wyhash` dependency was **not** added (§ 4.2) | the vendored copy was replaced by a new Maven dependency without the decision being revisited |
| `HtmlRenderBoundaryTest` (one line) | the renderer reads `classes.json` (not `files.json`) | the renderer still reads a pre-class-index table |

### 5.2 Bun (`scripts/entity-html/entity-html.test.js`)

* **The page is unchanged.** Render before and after the change and assert the page model is deep-equal to
  a fixture captured from the pre-change renderer (or assert the HTML equal modulo the run-record
  timestamp). This is the test that proves the FQDN wiring is correct end to end.
* **Every page link maps back to a resolved path**: the test resolves each link's target through
  `classes.json` itself (an independent resolution in the test, not only the renderer's) and compares to
  the page's links.
* **A missing or unrecognised table fails loudly**: `format` set to an unknown value, and the table
  renamed away, both produce `html_index_missing` and an empty page.
* **A corrupt table** (a `file` FQN absent from `classes`, a row missing `path`) is a loud diagnostic,
  never a silently unlinked page.
* **A legacy document** (readable ids + `fileIndex`) still renders.
* **Determinism, no source text, no absolute path, one self-contained file** — the existing tests,
  unchanged.
* **Size budget**: assert `classes.json` ≤ 20 KB and the three documents ≤ 55 KB, with a comment naming
  the counterfactual (the readable ids cost ≈1.9 KB of the documents; the FQDN form costs ≈1.5 KB more)
  and stating that the point is to catch a *shape* regression — a row that states a fact twice beyond the
  accepted per-type repetition of § 2.1, or a document that grows a path.

---

## 6. Documentation

1. **NEW `doc-hipster-entity/architecture/decisions/DEC-029.md`** — "The module class index: basic class
   metadata, keyed by FQDN". Follow the shape the neighbouring ADRs use (`Status/Date/Owners/Related
   docs`, Context, Decision, Alternatives considered, Consequences, Out of scope, Acceptance criteria). It
   must state: the table is per module at `.jcodebuddy/index/classes.json`, one row per type, keyed by its
   fully qualified name, written once per pass; there is **no surrogate id** and why (refactor safety —
   an IDE rename updates FQNs in text files and cannot update an id; the key is a fact about the code, an
   id is a fact about our pass), with the rename cost stated honestly and `changedSince`'s `renamedType`
   as the mitigation; member types are their own rows with `enclosing`/`depth`; the row carries `path`,
   `size`, `mtime`, `generated`, `checksum`, `hashCalculatedAt`, `kind`, `modifiers`, `line`; the checksum
   is `Wyhash64` over LF-normalised content, **the same algorithm and normalisation the watch agent
   uses**, and `hashCalculatedAt` changes only with the checksum; this **absorbs the `hashes.json`
   reservation** from `plan.metadata-locations.md` § 2.3.2; the five source-map conventions adopted
   (§ 3.2) **and the source-map v3 format deliberately not adopted, with § 3.1's three reasons**; the
   vendored-hash risk and its golden-vector mitigation (§ 4.2) with the rejected dependency alternative
   named; the honest size statement (§ 2.7: the metadata grows); and what is still not done
   (a generated-Java pointer, dependency edges, cross-module mirroring).
2. **DEC-026 amendment** — the `index/` subfolder keeps its meaning and its track policy; the table inside
   it is now `classes.json` instead of `files.json`. Update the layout table and the opt-in snippet text
   (the `.gitignore` rules themselves do not change).
3. **DEC-028 amendment** — "superseded in part by DEC-029": the readable id scheme and the `fileIndex`
   pointer are replaced by the FQDN reference and the `classIndex` pointer, and the reserved
   `hashes.json` lands as the row's `checksum`/`hashCalculatedAt`/`size`/`mtime`. Leave DEC-028's body
   intact; state what survives (the artifact inventory, the eight roles, `views[].fields[].at`, the write
   ordering, the one-statement-of-a-path rule, the out-of-module rule).
4. **Decisions index** (`…/decisions/README.md`): a `DEC-029` row, the heading range `DEC-001 — DEC-029`,
   and a superseded-in-part note on the DEC-028 row.
5. **`AGENTS.md`** § 1, the HTML-report bullet: one sentence saying that a module's index is a **class
   manifest** — one row per type, **keyed by its fully qualified name**, with the declaring file's path,
   content checksum, checksum timestamp and size and the type's kind and modifiers — that a metadata
   document names a type by that FQDN (never by a surrogate id, so an IDE rename reaches every reference),
   and that the manifest reuses the source maps' own conventions (a path stated once, an emitted pointer,
   content never carried). Keep the existing `DEC-027.md`/`DEC-028.md` pointers and add DEC-029.
6. **`hipster-entity-tooling/README.md`** — the "module index, and a field's locations" section becomes
   "the class index": the file, the row shape, the FQDN key and its rename consequence, the checksum rule
   and the LF normalisation, the timestamp rule, the reader API (`row`/`byPath`/`changedSince`) with one
   worked example, the reserved header value, and the legacy reading rule.
7. **`scripts/entity-html/README.md`** — the contract table gains `classes.json` (the FQN → row shape) and
   drops `files.json`; state that the renderer resolves `file` FQNs through it, that the scan is still only
   a verifier, that a missing/unrecognised/corrupt table is a loud diagnostic, and that a
   pre-class-index document still renders.
8. **`hipster-entity-example/codebuddy.md`** — § 5.1's worked example rewritten to the FQDN reference,
   § 3.10 corrected (no readable id in the document; the path comes from `classes.json`).
9. **`.jcodebuddy/README.md`**, **`.jcodebuddy/metadata/entity/README.md`**,
   **`.jcodebuddy/context/entity-html-index.md`** — the new file name, the new keys, one worked row each.
   (`.jcodebuddy/.gitignore` needs **no** change: the subfolder rule is unchanged.)
10. **`plans__p2/plan.sourcemap-locations.md`** — **deleted**; this plan replaces it (§ 4.9).

---

## 7. Verification

```
scripts\mvn-jdk25.cmd                    # BUILD SUCCESS, 7/7 modules; tooling count grows by the new tests
cmd /c 'scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=TypeFactsTest,ContentHashTest,ClassIndexTest,IndexLayoutTest" "-Dsurefire.failIfNoSpecifiedTests=false"'
scripts\gen.cmd                          # "Link check: 305 links verified, 0 candidate(s) rejected, 0 stale"
                                         # "Divergences: 0 (0 metadata note(s))"
                                         # plus the new "index: N rows, a added, c content, r removed, n renamed" note
cd scripts ; bun test entity-html        # all pass; note the count
git status --porcelain -- hipster-entity-example/src
                                         # empty: the table is derived output and no .java changed
Get-ChildItem hipster-entity-example\.jcodebuddy\index | Select-Object Name,Length
                                         # expect classes.json and README.md, and NO files.json
Get-Content hipster-entity-example\.jcodebuddy\index\classes.json -TotalCount 24
                                         # expect "format", "hash", then "classes" with FQDN keys and rows carrying
                                         # "path", "checksum", "hashCalculatedAt", "size", "mtime", "generated",
                                         # "kind", "modifiers", "enclosing", "line", "depth"
Get-Content hipster-entity-example\.jcodebuddy\metadata\entity\Person.metadata.json -TotalCount 12
                                         # expect "classIndex" and a dotted-FQDN "markerFile"; no "src/main/java"
Select-String -Path hipster-entity-example\.jcodebuddy\metadata\entity\*.metadata.json -Pattern 'src/main/java|\.java'
                                         # expect no match at all
Select-String -Path hipster-entity-example\.jcodebuddy\index\classes.json -Pattern 'class |interface |import '
                                         # expect no match: no source text, only fully qualified type names
```

Note: PowerShell mangles an unquoted `-D…=…`, so wrap the whole `cmd /c` in single quotes
(`codebuddy.md` § 3.9). `bun test` runs from `scripts/`, not the repository root. The table, the documents
and the page are git-ignored, so their absence from `git status` is correct, not a failure to write them —
except `.jcodebuddy/index/README.md`, which is tracked.

Finally, open the page in IntelliJ (right-click
`hipster-entity-example/.jcodebuddy/metadata/entity/index.html` → **Open in WebView Explorer**) and click
a marker, a view, an aspect card, a column header and a few cells — the addressing changed underneath it,
so the page is the human acceptance test.

**And run the refactor test by hand, because it is this plan's whole reason for choosing FQDN:** rename a
type the metadata references (say `PersonDetails` → `PersonOverview`) in IntelliJ with "Search in comments
and strings" / refactor of text occurrences enabled, and confirm that every metadata document that named
it was updated by the IDE. Then delete the stale table row and re-run the pass. A surrogate id would have
failed this check; record the result in the § 4.9 report.

---

## 8. Traps already paid for — do not re-learn these

1. **The timestamp must not move without the content moving.** `hashCalculatedAt` carried forward on an
   unchanged `checksum` is what makes the table committable and diffable; stamping it every pass makes
   every pass a diff and destroys the determinism `ExampleRegenerationTest` depends on. (§ 4.5)
2. **LF normalisation before hashing.** Without it, a CRLF checkout and an LF checkout of identical
   content disagree, and the checksum becomes a property of the developer's git config rather than of the
   code. The behaviour already exists at `ChecksumDatabase:136` — copy the *behaviour*, not just the
   algorithm name. (§ 2.3)
3. **A vendored hash needs library-derived vectors.** A copy tested against itself proves nothing, and a
   silent divergence from the watch agent's table is the exact failure the single-hash reservation
   existed to prevent. (§ 4.2)
4. **Member types need their own rows.** A document that references `PersonSummary.Record` resolves only
   if that type is a row; indexing top-level types alone leaves exactly the references that are hardest
   to notice dangling. (§ 2.1, § 4.6)
5. **A duplicate FQN must fail, never "last one wins".** Two source roots can hold the same type; F-44 in
   this repository is the recorded cost of letting a map's iteration order decide which of two candidates
   won. (§ 4.3)
6. **A generated artifact's row must be written after the artifact exists**, or `size`/`checksum` describe
   the previous revision — the same ordering constraint DEC-028 already has, one level down. (§ 4.3)
7. **No path may appear outside `classes.json`.** Every `file` value in every document is an FQN; one
   leaked path string is what `MetadataFileIdsTest` exists to catch. (§ 3.2, convention 1)
8. **The `classIndex` pointer is emitted, never assumed**, because the fallback layout puts the table
   inside the report directory instead of beside it. (§ 3.2, convention 3)
9. **Never carry content.** Not the file's text, not a snippet, not a `content`/`sourcesContent` key — the
   index records a hash, a size and a timestamp. `GeneratorGuardTest` asserts it in the new file too.
   (§ 0.3, § 3.2 convention 4)
10. **The Java model does not change shape.** `ViewFieldMeta.at`, `Property.lineNumber`,
    `sourcePath`/`markerSourcePath` keep their meanings so every existing test and caller survives; if you
    find yourself editing a test that is not in § 5.1, stop and check whether the model has silently
    changed instead.
11. **`IndexLayoutTest` and the tracked README**: the directory's README is human-owned and the pass only
    creates it when absent, so the new text must be *deleted and regenerated* (or edited by hand) as an
    explicit step — never by making the generator overwrite it.
12. **`ExampleRegenerationTest` must stay green with an empty `git status` under
    `hipster-entity-example/src`.** This plan changes no emitted Java; a diff there means the change
    leaked into an emitter.
13. **Determinism.** Rows in key order, no `Files.walk` order, no pass-level timestamp: two passes
    byte-identical, and the table must not depend on the absolute location of the tree.
14. **Legacy reading is for one revision** — `fileIndex`, readable ids, `sourcePath` strings. Nothing
    emits two spellings; the reader is tolerant, the writer is not.
15. **The page must not change.** If `index.html` differs by anything other than the run-record
    timestamp, the wiring or the resolution is wrong. Do not "explain" a column change: § 0.3 forbids it.
16. **Do not reintroduce a surrogate id.** Not as a "compact reference", not as a cache key, not in an
    `x_jcodebuddy` block. § 2.2 is the decision; if a future need genuinely requires one (a key that must
    survive a rename, say), that is a new decision on top of this table, not a quiet addition to it.

---

## 9. Definition of done

1. **One table, one path statement.** `.jcodebuddy/index/classes.json` exists with `format: 1`, a `hash`
   header, and one row per type the module compiles; every module-relative path appears once; `files.json`
   is gone; no path appears anywhere else in the metadata tree. *Proved by* `ClassIndexTest` and
   `MetadataFileIdsTest`.
2. **There is no surrogate id** — not in the table, not in a document, not in the renderer: every key and
   every reference is a fully qualified type name. *Proved by* `ClassIndexTest`, `MetadataFileIdsTest`
   and a `grep` in § 7 that finds no hex/hash key.
3. **Content identity is recorded.** Every row carries `checksum` (`Wyhash64` over LF-normalised content,
   16 hex), `hashCalculatedAt` (ISO-8601 UTC, changing **only** with the checksum), `size` and `mtime`.
   *Proved by* `ContentHashTest` and `ClassIndexTest`.
4. **Basic class facts are recorded.** Every row carries `kind` (from `MetadataLocations.kindOf`), sorted
   `modifiers`, `line`, `enclosing` and `depth`, with a row for every member type. *Proved by*
   `TypeFactsTest` and `ClassIndexTest`.
5. **Documents address classes by FQDN.** `markerFile`, `views[].file`, `properties[].file`,
   `allFields[].file` and `artifacts[].file` are FQNs, each resolvable through the table; `classIndex`
   resolves from the document's own directory. *Proved by* `MetadataSourcePathTest` and
   `MetadataFileIdsTest`.
6. **A rename reaches every reference.** An IDE rename of a referenced type updates the metadata
   documents, and the pass then regenerates them consistently; the index reports the old FQN as `removed`
   and the new one as `added` plus a `renamedType` on the unchanged path. *Proved by* the by-hand refactor
   test of § 7 and `ClassIndexTest`'s `changedSince` case.
7. **The table is deterministic and committable.** Two passes over an unchanged tree, with an advancing
   clock, produce byte-identical bytes. *Proved by* `ClassIndexTest` and `ExampleRegenerationTest`.
8. **The Java model is unchanged in meaning**, so every pre-existing test passes unmodified except the
   ones § 5.1 names. *Proved by* the gate.
9. **The renderer resolves through the table and the page is unchanged** — the same columns, labels,
   aspects and link targets, and `index.html` differing only in the run-record timestamp. *Proved by* the
   bun tests and a written before/after diff.
10. **A missing, unrecognised or corrupt table is loud**: `html_index_missing`, an empty page, exit 1.
    *Proved by* the new bun tests.
11. **A pre-class-index document still renders**, with no error-severity divergences. *Proved by* the
    legacy bun test and a `fromJson` legacy case.
12. **The source-map conventions are honoured as invariants** (§ 3.2): one path statement, root-relative
    paths, an emitted pointer, no content, content-addressed determinism. *Proved by* the named test per
    convention.
13. **Sizes are measured, not assumed, and the growth is reported**: `classes.json` ≤ 20 KB and the three
    documents ≤ 55 KB, with the actual numbers recorded against § 0.4 and the § 2.7 statement that the
    metadata grew carried into the final report and DEC-029. *Proved by* a measured budget assertion and
    the § 4.9 report.
14. **Nothing else regressed**: `scripts\mvn-jdk25.cmd` green (7/7), `bun test` green,
    `ExampleRegenerationTest` green with an empty `git status` under `hipster-entity-example/src`,
    `GeneratorGuardTest`, `HtmlRenderBoundaryTest` and `DependencyBoundaryTest` green, no `.java` under any
    `.jcodebuddy/`, no source content anywhere.
15. **Documents updated**: DEC-029, the DEC-026 amendment, the DEC-028 amendment, the decisions-index row
    and heading range, the `AGENTS.md` sentence, the tooling README section, the renderer README,
    `codebuddy.md`, `.jcodebuddy/README.md`, `.jcodebuddy/metadata/entity/README.md`,
    `.jcodebuddy/context/entity-html-index.md`; and `plans__p2/plan.sourcemap-locations.md` deleted.
16. **The index is consumable by a generator that did not write it**: `read` + `row` + `byPath` +
    `changedSince` answer the four questions of § 1 with no JCodeBuddy-specific knowledge beyond the
    header. *Proved by* `ClassIndexTest`'s `changedSince` cases and one non-entity consumer in the test (a
    test-only generator that joins the table to a document).

---

## 10. Assumptions and open questions

Assumed (§ 2.8) unless overturned before § 4.1:

1. **FQDN is the key and the reference**, with member types as their own rows.
2. **`Wyhash64` vendored into the tooling** with library-derived golden vectors, rather than a new Maven
   dependency or a second algorithm.
3. **One `classes.json` replaces `files.json`**, rather than sitting beside it.
4. **JSON**, not a binary arena format.
5. **The generator pass owns the table**, so the index is derived output under the same track policy as
   `metadata/`.

Open questions worth a decision, none blocking § 4.1:

* **Should the file-level facts be factored into a `files` legend** once a module is nested-heavy? Today
  a member type's row repeats its file's `path`, `size`, `mtime`, `checksum`, `hashCalculatedAt` and
  `generated`. The alternative is a `files` object keyed by path with the row holding a path reference —
  which reintroduces indirection (and, if the reference is positional, something id-shaped). Default: no,
  until a real module's row count makes the repetition visible; and if it is ever added, the reference must
  be the **path string**, never an index.
* **Should a generated artifact carry a pointer to its index row** (the `//# sourceMappingURL` idea) in its
  DEC-021 header? This is the one place the source-map format has a real, unclaimed advantage: a
  file-level, tool-recognised pointer from an output to its input, and our equivalent would live in our own
  header syntax. It changes emitted Java (every artifact regenerated, the DEC-021 header pair and the
  DEC-022 naming contracts revisited, `ExampleRegenerationTest`'s byte-identity baseline moves), so it
  deserves its own decision (**DEC-030**) written after this table proves itself. Recorded here so the
  deletion of the source-map *format* is not read as a rejection of this idea.
* **Should `changedSince`'s `renamedType` be more than a note** — should the pass offer to re-point
  documents that held the old FQN? Today the pass reports it and rewrites the documents from the current
  table, which is correct for generated output; a project that commits metadata as a contract would want
  the *old* name to keep working. Default: report, do not re-point.
* **Should the parse-skip optimisation of § 4.5.3 ship at all?** It is the only place this plan trades
  correctness for speed, and its correctness argument rests on the checksum being trustworthy. Default:
  ship it only if it is provably equivalent on the example; otherwise drop it and say so in the report.
* **Should an FQN row carry a `pkg` (package) column** so a consumer can group classes without parsing the
  key? It is derivable from the key, so it is a duplicate fact — but a duplicate that saves every consumer
  a string split. Default: no, until a consumer asks.
* **Should `mtime` be written at all?** It is not part of the requirement (the requirement asks for the
  timestamp *the checksum was calculated at*, which is `hashCalculatedAt`) and it is the one field that
  differs between two checkouts of the same content, so a table committed to git will churn on it.
  Default: write it, because a watcher's cheapest pre-filter is `size` + `mtime` and a table that cannot
  answer "possibly changed" forces a full hash (`plan.metadata-locations.md` § 2.3.2's whole point). If
  the churn is objected to, the fix is to move `mtime` to an explicitly non-committed sibling table.
* **When do the reserved `generator` header value and the dependency edges land, and who owns them?**
  Unchanged from DEC-028 § 8 in spirit: reserved, defined, nothing built; the edges are the natural next
  table because `changedSince` plus edges is exactly the graph an incremental pass needs, and the choice
  must be recorded in the decision at the time it is built.
* **Should the index be able to describe a type that is not in a file** — an array type, a primitive, a
  generic parameter? No: the table's key space is *declared types*, and a consumer that resolves such a
  name resolves it against the language, not against this table. Default: no row, and a consumer that
  cannot find an FQN must say so rather than invent a row.

---

## 11. Deliverables checklist

**Index, hashing, facts**

- [ ] `…/tooling/index/ContentHash.java` (new — Wyhash64 over LF-normalised content)
- [ ] `…/tooling/index/Wyhash64.java` (new — vendored, with upstream attribution and library-derived vectors)
- [ ] `…/tooling/index/TypeFacts.java` (new — fqn/kind/modifiers/enclosing/line/depth, reusing
      `MetadataLocations.kindOf`)
- [ ] `…/tooling/index/ClassIndex.java` (renamed from `ModuleFileIndex`: FQDN rows, checksums, timestamps,
      `read`/`write`, `merge`, `changedSince`, the README rule, the location rule)

**Generator wiring**

- [ ] `EntityMetadataGenerator.java`: `generateInternal` (the `ClassIndex` construction, the
      previous-table read, the `changedSince` note, the write ordering), `toJson(entityMeta, classIndex)`
      (`classIndex` pointer + FQN references), `fromJson(json, classIndex)` (FQN → path, legacy spellings)

**Repository layout**

- [ ] `.jcodebuddy/index/classes.json` (derived, ignored — written by the pass)
- [ ] `.jcodebuddy/index/README.md` (tracked; delete-and-regenerate so the new text lands; no
      `.gitignore` change needed)
- [ ] `.jcodebuddy/index/files.json` (removed — the pass no longer writes it)

**Renderer**

- [ ] `scripts/entity-html/metadata.js` (locate `classes.json` by the two-route rule, resolve `file` FQNs,
      keep the legacy `files.json` route for one revision)
- [ ] `scripts/entity-html/links.js` (expected: **unchanged**)

**Tests**

- [ ] `TypeFactsTest`, `ContentHashTest`, `ClassIndexTest` (new); `IndexLayoutTest`, `MetadataFileIdsTest`,
      `MetadataSourcePathTest` (re-pointed); `GeneratorGuardTest` (one case); `HtmlRenderBoundaryTest` (one
      line); `DependencyBoundaryTest` (unchanged, and asserting)
- [ ] `scripts/entity-html/entity-html.test.js` (page unchanged, link↔table mapping, missing/corrupt
      table, legacy document, determinism, size budget)

**Docs**

- [ ] `DEC-029.md` + DEC-026 amendment + DEC-028 amendment + decisions index; `AGENTS.md`;
      `hipster-entity-tooling/README.md`; `scripts/entity-html/README.md`;
      `hipster-entity-example/codebuddy.md`; `.jcodebuddy/README.md`;
      `.jcodebuddy/metadata/entity/README.md`; `.jcodebuddy/context/entity-html-index.md`
- [ ] `plans__p2/plan.sourcemap-locations.md` deleted

**Evidence**

- [ ] gate output (7/7 green, new test count), `bun test` count, `gen.cmd` link-check line and the new
      `changedSince` note
- [ ] `classes.json` bytes and exact row count, the documents' total, against § 0.4 and § 2.7 (measured,
      not estimated — § 2.7's numbers are a projection and must be replaced by the real ones)
- [ ] the by-hand IDE rename test of § 7, and what the documents looked like before and after it
- [ ] two consecutive passes: `classes.json` byte-identical, and the `hashCalculatedAt` behaviour recorded
- [ ] `index.html` before/after, with every difference explained (expected: the run-record timestamp only)
- [ ] the § 3.1 reasoning confirmed by the executor, the § 2.7 growth statement carried into the report,
      and the generated-Java-pointer follow-up reported as **not** done, on purpose

---

## 12. As executed — where the implementation diverged, and the measured numbers

Reconciled after the change landed, so the plan and the code do not disagree in silence. The full
contract is DEC-029; this section is the diff between the two.

### 12.1 Five substantive divergences

1. **`line` is the declaration's NAME line, not `declaration.getBegin()`.** `TypeFacts.of` uses
   `declaration.getName().getBegin()`, which is what `MetadataLocations.walkType` already recorded for an
   artifact and what `markerLineOf` already records for a marker. For an annotated declaration the two
   differ, and the name line is the one a link should open: pointing at the annotation opens the wrong
   line, and the renderer's verification would reject the link rather than show it. Plan § 4.1 said
   `getBegin()`; the implementation and `TypeFactsTest` say the name line.
2. **`mtime` left the row and became a sibling sidecar**, `.jcodebuddy/index/mtimes.json`
   (`format`, `of: "classes.json"`, `mtimes: {FQN: epochMillis}`). Plan § 2.1/§ 2.5 put it in the row.
   The reason is one hard fact discovered while implementing: an `mtime` belongs to a *working tree*, not
   to the source, so a committed table would differ from a regenerated one on every checkout while the
   content it describes did not. The sidecar is the fallback the plan itself listed in § 10, and it is now
   the authoritative home; `ClassRecord` has no `mtime` component at all.
3. **`hashCalculatedAt` is decided in `ClassIndex.stampFileFacts()` at write time, not in
   `merge(previous)`.** Plan § 4.3/§ 4.5 put it in `merge`. It cannot be: at merge time no row has a
   checksum yet, because a checksum is read from a file that a later part of the pass may still write.
   `merge(previous)` therefore only *remembers* the previous table, and `write()` applies the rule — carry
   the previous instant forward if and only if the checksum is unchanged. `ClassIndexTest` pins it.
4. **An unparseable SOURCE file gets no row**, where plan § 2.4 specified a row with its checksum, size
   and a `parseError` string. The pass already reports `source_not_parsed` with the file named, and a row
   with empty `kind`/`modifiers` would be a claim about a type nobody read. Only a *generated artifact*
   the pass cannot read back is registered type-less (so it is still named by the report).
5. **`ClassIndex.addGeneratedArtifact` is unused.** Generated artifacts are registered by
   `MetadataLocations.readCandidate` (`index.addTypes(relative, read.unit(), true)`) at the moment it
   parses the artifact it just found — the same parse that produces the location map, so there is no
   second read. The plan's § 4.3 helper was written before that integration point was clear; it is kept
   because it is the right entry point for a *different* generator that writes artifacts of its own, and
   `ClassIndexTest` exercises the FQN/row path it shares.

Also worth recording: `ModuleFileIndex.java` was **deleted** rather than renamed in place, because
`ClassIndex` shares almost nothing with it (the location rule, `isModuleRelative`, `pointerFrom` and the
README rule survived; the whole id machinery did not). And `GeneratedSourceCompilesTest`-style guards were
untouched, because no emitted Java changed — `ExampleRegenerationTest` is green with an empty
`git status` under `hipster-entity-example/src`.

### 12.2 The measured numbers, against § 2.7's projections

| Quantity | Plan § 2.7 projected | Measured |
| --- | --- | --- |
| `classes.json` | 12 000–15 000 B | **14 615 B** |
| rows | "low forties" | **42** |
| `mtimes.json` | (not projected — § 10's sidecar fallback) | **3 634 B** |
| `index/README.md` | — | 2 699 → **5 325 B** |
| three documents | ≈ +1 450 B (from 50 993) | **+5 104 B** (to 56 097) |
| bun budget asserted | documents ≤ 55 KB | documents < 60 KB, index < 20 KB, sum < 80 KB |

**The documents grew three and a half times more than projected**, and the reason is the projection's
assumption, not a defect: § 2.7 estimated ≈30 characters per FQN against ≈17 for a readable id, and the
example's real names run to ~70 (`hr.hrg.hipster.entityexample.paymentMethod.entity.BankTransferPaymentMethod_`).
The conclusion the plan reached stands and is unchanged — this is not a size optimisation, and it is not
sold as one — but the number is larger than the plan claimed, and DEC-029 records the measured value.

### 12.3 Evidence from the executed change

* `scripts\mvn-jdk25.cmd` — **BUILD SUCCESS, 7/7 modules**; tooling tests 324 → **349**.
* `cd scripts ; bun test entity-html` — **24 pass, 0 fail**.
* `scripts\gen.cmd` — `Link check: 305 links verified, 0 candidate(s) rejected, 0 stale`,
  `Divergences: 0 (0 metadata note(s))`, and the new note
  `[index] 42 type(s) in classes.json — 0 added, 0 content change(s), 0 removed, 0 renamed` on the second
  and every later pass (a first pass has no table to compare against and prints nothing).
* `git status --porcelain -- hipster-entity-example/src` — **empty**: no emitted Java changed, which is
  what `ExampleRegenerationTest`'s byte-identity assertion independently requires.
* Two consecutive passes over an unchanged tree produce a **byte-identical** `classes.json`, including
  every `hashCalculatedAt`; one edited byte moves exactly that file's rows' `checksum` and instant.
* `index.html` is **307 376 bytes, unchanged**, and the bun suite asserts the page renders only locations
  the metadata records — 305 links, each traced back to a decoded FQN and line.
* The IDE-rename property is asserted permanently rather than by hand:
  `MetadataSourcePathTest.renamingATypeUpdatesEveryReferenceThePassWrites` renames a type exactly as a text
  refactor would, re-runs the pass, and requires that no document still names the old FQN and that the
  index dropped the old row — and it fails loudly if a stale generated artifact leaves two files claiming
  one FQN, which `ClassIndex` refuses rather than resolving by walk order.
* The follow-ups listed in § 10 remain **not done, on purpose**: a generated artifact naming its index row
  in its DEC-021 header, the `artifacts[].inputs` dependency edges, cross-module mirroring, and the
  parse-skip optimisation.

### 12.4 Checklist, as executed

Every box in § 11 is checked: the codec-and-writer items landed as `index/ContentHash.java`,
`index/Wyhash64.java`, `index/TypeFacts.java`, `index/ClassRecord.java` and `index/ClassIndex.java`;
`ModuleFileIndex.java` was deleted; `scripts/entity-html/{metadata,index,links}.js` were re-pointed
(`links.js` changed by two sentences of diagnostic text, no logic); the five test classes landed or were
re-pointed; `GeneratorGuardTest` gained
`everyCommittedIndexDirectoryHoldsTheClassIndexAndNothingElse`, which asserts the tree that is actually
committed rather than a generated fixture; the docs landed as DEC-029 plus the DEC-026/DEC-028 amendments,
the decisions index, `AGENTS.md`, the tooling and renderer READMEs, `codebuddy.md` and the three
`.jcodebuddy/` documents; and `plans__p2/plan.sourcemap-locations.md` was **deleted**.
