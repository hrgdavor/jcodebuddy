# Plan — compact source-file identity and complete field locations in the metadata

**Status:** Ready to execute. **Nothing in this document is implemented.** The repository is green at
the revision that added this file — § 0.4 is the baseline you must confirm before you change anything.

**Motivation.** Two observables in the generator's metadata JSON, both about *where a field is*:

1. **A path is repeated for every location.** The metadata (and the page rendered from it) names source
   files by their full module-relative path — 83 characters on average in this repository — and a field
   has many locations, so the same 30 paths are written hundreds of times.
2. **Only one of a field's locations is recorded at all.** Each field carries `sourcePath` +
   `lineNumber`: the *declaration start* in the declaring interface. The constant in the generated field
   enum, the record component, the field and setter in each builder, the ordinal switch arm and the
   name lookup are **not** in the metadata. The HTML page reconstructs them at render time by scanning
   the committed source and guessing by naming convention — so no consumer other than that one renderer
   can answer "where is this field", and the page's correctness rests on a heuristic rather than on the
   pass that wrote the files.

The plan replaces the repeated path with an id resolved through a **central, per-module index** under
`.jcodebuddy/index/`, and records every location the pass knows, so that a consumer reads the answer
instead of reconstructing it. The index is deliberately a *directory of tables* rather than one
addressing file: the content hashes a watcher or an incremental pass will want (§ 2.3) get a defined
home now, without changing anyone's contract later.

---

## 0. Scope, baseline, ground rules

### 0.1 Already implemented and verified — do not rebuild any of this

The immediately preceding change (call it *the path change*) is complete, tested and documented. Read it
before you touch anything; the work below **extends** it and must not regress it:

| What                                                   | Where | State                                                                                   |
| ------------------------------------------------------ | ----- | --------------------------------------------------------------------------------------- |
| each class records its **module-relative** source path | `EntityMetadataGenerator` (`resolveModuleRoot`, `moduleRelativePath`, `parseProperty`, `toJson`, `fromJson`); model records in `…/tooling/meta/` | done, tested, green |
| JSON keys today                                        | root: `entityName`, `package`, `markerInterface`, `idType`, `markerSourcePath`, `views`, `allFields` · view: `name`, `lineNumber`, `sourcePath`, `extends`, `gen`, `discriminatorField`, `addons`, `properties` · property: `name`, `type`, `lineNumber`, `sourcePath`, `fieldKind`, [`column`], [`relation`], [`expression`], [`constraints`], [`typeImports`] · allField: `name`, `type`, `lineNumber`, `sourcePath`, `fieldKind`, [`column`], [`relation`], [`expression`], `views`, `typeByView` | verified against the committed JSON |
| module root resolution order                           | nearest `.jcodebuddy/` above the report dir, then above the source root, then nearest `pom.xml`, then the `…/src/main/java` convention, then the source root | DEC-026 § 2's order |
| the Bun renderer                                       | `scripts/entity-html/` (`index.js`, `metadata.js`, `sources.js`, `links.js`, `render.js`, `entity-html.test.js`, `README.md`) | renders `…/metadata/entity/index.html`, metadata-first, every link verified, vanilla JS |
| the rules in force                                     | `doc-hipster-entity/architecture/decisions/DEC-027.md` (+ its two amendments), `AGENTS.md` § 1 bullets, `hipster-entity-tooling/README.md` → "Source paths in the metadata" | read all three |
| guards                                                 | `MetadataSourcePathTest` (module-relative paths, declaring file, no source content), `GeneratorGuardTest` (no `.java` under any `.jcodebuddy/`, report dir holds only JSON), `HtmlRenderBoundaryTest` (no Java HTML emitter) | green |

Also already true, and easy to break: no report carries **source content** (the metadata records a
path, the page records a path; the only `<pre>` on the page is the renderer's diagnostics), and no
`.java` file exists anywhere under a `.jcodebuddy/` tree.

### 0.2 What this plan adds

1. A **central file index** for the module — `.jcodebuddy/index/files.json`, one table for every
   document in that module (`files`: id → module-relative path) — and **ids everywhere a file is
   referenced**, in every document. No path is written twice, and two documents cannot disagree about
   what an id means, because there is only one table and one place that assigns ids. The directory is
   built as *the module's index*, so the content hashes a watcher or an incremental pass will need
   (§ 2.3) get a defined home instead of a third mechanism.
2. An **artifact inventory** per view (`views[].artifacts[]`) — the files that belong to that view,
   with kind, declaration line, whether the generator owns them, and the DEC-021 header text.
3. A **complete location map per field per view** (`views[].fields[].at`): artifact id → role → line,
   covering the declaring interface, the field enum, the record and both builders.
4. A **renderer that discovers nothing from source**: it reads the index and the documents, and keeps
   the scan only to *verify* a link (file exists, line still contains the member) — DEC-027 § 4.2 stays
   mandatory.

### 0.3 Non-goals

* **Source content.** Never. The metadata names files; it does not quote them (DEC-027 § 2, and your
  standing instruction).
* A **repository-wide** index. The index is per module, beside the documents that reference it
  (DEC-026, and § 2.2): a module-relative path in another module's table would mean nothing.
* Mapper artifacts (`--mapper`) and their locations; adapter/binder artifacts are only included when
  `--adapters` actually emitted them.
* Any change to the page's link contract, its visual layout, or the roles/columns it renders. The page
  after this plan must look the same; only its *inputs* change.
* Changing `Property.lineNumber` semantics, `allFields`' union semantics, or DEC-023's ordinal order.

### 0.4 Baseline — confirm before you start

Run these four, and compare. If they do not match, stop and report; the tree is not at the revision this
plan assumes.

```
scripts\mvn-jdk25.cmd                    → BUILD SUCCESS, 7/7 modules
                                           api 4 · core 88 · tooling 308 · jackson 26 · hipster-entity-test 31  (457)
cd scripts ; bun test entity-html        → 15 pass, 0 fail
scripts\gen.cmd                          → "Link check: 266 links verified, 0 candidate(s) rejected, 0 stale"
                                           "Divergences: 0 (2 metadata note(s))"
git status --porcelain -- hipster-entity-example/src
                                         → empty (the pass is byte-identical on the committed sources)
```

Measured metadata size at this revision (your size budget baseline, § 9.7):

| File                          | Bytes      |
| ----------------------------- | ---------- |
| `Auditable.metadata.json`     | 2 473      |
| `PaymentMethod.metadata.json` | 14 376     |
| `Person.metadata.json`        | 9 159      |
| **three documents together**  | **26 008** |
| `index.html` (rendered)       | 284 677    |

### 0.5 Ground rules for whoever executes this

1. **Every gate run is `clean`** (`scripts\mvn-jdk25.cmd` does `clean test`). A non-clean green is not
   evidence.
2. **A generator change regenerates the example in the same change.** `ExampleRegenerationTest`
   regenerates a copy of the committed tree and asserts byte-identity. If it goes red, regenerate and
   commit the output — never "fix the test". A green gate with a changed `hipster-entity-example/src`
   in `git status` means the example was not regenerated.
3. **A claim is worthless without the test that asserts it.** The one thing this repository keeps
   re-learning (F-34, F-43, F-47) is that a rule which is true but unasserted decays into a rule that is
   false. Every item in § 4 names its test.
4. **Keep commits separable**: (a) model + generator, (b) renderer, (c) docs. Reviewing a metadata
   format change and a rendering change in one diff is where mistakes hide.
5. **`.cmd` files are pure ASCII with CRLF.** `scripts/gen.cmd` and `scripts/entity-html.cmd` — a
   non-ASCII byte or a bare LF makes `cmd.exe` mis-parse them (`GateParityTest` asserts this for the
   Maven launcher; `HtmlRenderBoundaryTest` for `gen.cmd`). Edit them with an explicit ASCII/CRLF write,
   not a text editor that normalises line endings.
6. **The renderer's inline script lives inside a JavaScript template literal** in `render.js`. No
   backticks, no `${…}`, no backslashes may appear in the emitted `<script>` — a backtick closes the
   literal and the file stops parsing (this happened once already).
7. **Determinism.** No timestamps, no hash-order iteration, no `Files.walk` order leaking into output.
   Two runs must stay byte-identical; the renderer's test asserts it, and `ExampleRegenerationTest`
   asserts it for the Java.
8. **No `.java` under `.jcodebuddy/`, and no report directory that holds anything but JSON.**
   `GeneratorGuardTest` asserts both; `hipster-entity-example/.jcodebuddy/.gitignore` keeps a stray
   `.java` ignored even if a project opts the metadata subtree in as a contract.

---

## 1. The defects, with measurements

### 1.1 A path is repeated once per location

Measured on the rendered page of the example (every `data-open` attribute, i.e. every location the page
carries today):

| Quantity                        | Value                     |
| ------------------------------- | ------------------------- |
| locations                       | **420**                   |
| distinct files referenced       | **30**                    |
| average path length             | **83** characters (65–95) |
| path text, inline               | **34 851** characters     |
| the same 30 paths, written once | **2 459** characters      |

The same 30 strings are written 420 times. With a short id and one table row per file — the table written
**once for the module**, in `.jcodebuddy/index/files.json` (§ 2.2) — the identity payload drops from
~35 KB to ~4 KB, and, more importantly, a file's location is stated **once**, so a consumer
cross-references it instead of re-parsing a path it has already seen.

### 1.2 Only one location per field is recorded

`Person.metadata.json` today, for `PersonSummary.age` (a `DERIVED` field, i.e. one of the richer cases):

```json
{
  "name": "age",
  "type": { "type": "java.lang.Integer", "unboxed": "int", "primitive": true },
  "lineNumber": 16,
  "sourcePath": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
  "fieldKind": "DERIVED",
  "expression": "YEAR(NOW()) - YEAR(birthDate)"
}
```

That is the *only* location the metadata has for `age`. What actually exists in the tree, and what the
page shows after scanning for it:

| Location                                          | File                                | Where it comes from                                                                          |
| ------------------------------------------------- | ----------------------------------- | -------------------------------------------------------------------------------------------- |
| `@FieldSource(kind = DERIVED, expression = …)`    | `PersonSummary.java`                | line 16 — note this is what `lineNumber` means: the declaration *start*, annotation included |
| the accessor `Integer age();`                     | `PersonSummary.java`                | line 17 — the scan finds it; the metadata does not                                           |
| enum constant `age(...)`                          | `PersonSummary_.java`               | generated, not in the metadata                                                               |
| `forName` arm `case "age":`                       | `PersonSummary_.java`               | generated, not in the metadata                                                               |
| stored field, getter, setter, `case 3 -> age` arm | `PersonSummaryBuilder.java`         | generated, not in the metadata                                                               |
| the same four in the tracking builder             | `PersonSummaryBuilderTracking.java` | generated, not in the metadata                                                               |

### 1.3 Who reconstructs it today, and why that is the defect

`scripts/entity-html/sources.js` scans the source root (blanking comments and literals, reading DEC-021
headers, classifying members by role) and `scripts/entity-html/links.js` probes the scan for each field
× artifact × role. That machinery works — every link is verified, and the page is correct — but:

* **no other consumer can do it.** The JSON is the contract (DEC-027 § 2), and by that contract a field
  has one location. An IDE plugin, a doc generator or a query tool would have to re-implement a Java
  line scanner to answer "where is this field".
* **the answer is a heuristic.** Artifacts are found by naming convention plus a header comment, and
  member roles by line patterns. The pass that *wrote* those files knows exactly what it emitted; a
  render-time scan can only infer it.
* **the scan is a second place where the same knowledge lives**, and the two can disagree silently
  (the renderer already reports `html_link_stale` when they do — a symptom, not a design).

---

## 2. Decisions

These are the decisions this plan is built on. § 2.7 lists which of them the requester had not chosen and
which are therefore **assumed defaults** — overturn them *before* § 4.1 if you disagree; after § 4.1 they
cost a rewrite.

### 2.1 File identity: a readable id, unique in the module

`id` = the file's **simple name** (`PersonSummary`, `PersonSummary_`), qualified by the **shortest
package suffix that disambiguates it** among all files the pass indexed (`entity.Person`, `iface.Person`,
`record.Person` — all three exist in this repository, which is why a bare basename is not enough), with
the extension and the `src/main/java/` prefix dropped.

Properties this buys, and the reason each matters:

| Property                        | Why                                                                          |
| ------------------------------- | ---------------------------------------------------------------------------- |
| **unique per module**           | a location resolves to exactly one file. Same package + same name = same file, so two ids can never collide |
| **deterministic**               | the id is a function of the module-relative path plus the *set* of indexed paths; nothing depends on visit order or the clock |
| **identical in every document** | `Person.java` gets the same id in `Person.metadata.json` and in `Auditable.metadata.json`, so a consumer can correlate documents without a shared registry |
| **compact**                     | 8–25 characters against 65–95 for a path; the `files` table is written once  |
| **readable in a diff**          | `entity.Person` in a metadata diff says what moved; `f3k9q2` or `7` does not |

Known trade-off, to be documented rather than hidden: adding a file whose simple name collides with an
existing one may **lengthen the existing id** (`Person` → `entity.Person`). That change is caused by a
real new ambiguity and affects only that group; a hash id would avoid it at the cost of readability, and
an index id would renumber on every insertion.

### 2.2 The index is central and per module: `.jcodebuddy/index/files.json`

**The table is not repeated in every document.** It is written once per pass, per module, at
`<module>/.jcodebuddy/index/files.json`, and every `<Marker>.metadata.json` in that module references
its ids:

```jsonc
// <module>/.jcodebuddy/index/files.json
{
  "format": 1,
  "module": "hipster-entity-example",
  "sourceRoot": "src/main/java",
  "files": {
    "Person":         "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java",
    "PersonSummary":  "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
    "PersonSummary_": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary_.java"
  }
}
```

Why central, rather than a table per document:

* **One place assigns the ids.** Module-wide uniqueness is a property of the whole set of files, so
  computing it once is correct by construction; per-document tables would each have to compute the same
  uniqueness over their own subset and could disagree (a file whose simple name collides with a file no
  document happens to mention would get different ids in different documents).
* **One place to regenerate, diff and version.** A rename changes one row; a project that commits its
  metadata as a contract reviews one table instead of three.
* **It is the natural home for the module's other indexes** later (`types.json`, symbol tables): the
  directory is the index, `files.json` is its first table.

What it costs, stated honestly:

* **A document is no longer self-contained.** A consumer needs two files: the document and the index.
  Two mitigations are mandatory rather than optional: each document carries a **`fileIndex` pointer**
  (the index path relative to the document, e.g. `"../../index/files.json"`), so nothing has to guess
  the `.jcodebuddy` layout; and a consumer that cannot read the index must **fail loudly with a
  diagnostic** rather than render a page of dangling ids (`html_index_missing`, in DEC-022's format).
* **The track policy is coupled.** `.jcodebuddy/index/` is derived like `metadata/` and ignored by
  default — but a project that opts its metadata subtree into git **must opt the index in too**, or its
  committed documents reference an uncommitted table. The `.jcodebuddy/.gitignore` opt-in snippet and
  `.jcodebuddy/README.md` must say so, and DEC-026's layout gains the new subfolder (§ 6.1).
* **The pass must finish before any document is written**, because the index must be complete before the
  first id is resolved. That reorders two steps in `generateInternal` — see § 4.5.

Location rule (so tests and out-of-module passes still work): the index goes to
`<nearest .jcodebuddy above the report directory>/index/files.json`; when the report directory is not
inside a `.jcodebuddy` at all (a temp directory in a test), it goes to `<report dir>/index/files.json`,
which is what makes the `fileIndex` pointer differ by layout and why it is emitted rather than assumed.

### 2.3 Reserved by this design: content hashes for watcher mode and incremental passes

The index is a **directory of tables**, not a single addressing file, so that three things can live
together and be extended without changing the layout. This plan implements **only the first**; the other
two are reserved and specified here so that the shape does not have to change when they land. Do not
build them in this pass — say so in the report.

**2.3.1 `files.json` — the addressing table (this plan).** id → module-relative path. This is the
*contract*: a report, an IDE plugin or a doc tool reads it and needs nothing else. Small, sorted,
deterministic, readable in a diff.

**2.3.2 `hashes.json` — content identity (reserved).** id → hash of the file's **content**, so a watcher
or an incremental pass can decide what actually changed without re-reading and re-parsing the tree:

* **Content-derived, never time-derived.** Hash the bytes (normalising CRLF → LF first, so a table
  written on Windows is still valid on a Linux checkout for the same content). No timestamps, no sizes
  that depend on the checkout — that is what keeps the table byte-identical for an unchanged tree and
  therefore usable from git.
* **One table for every file the pass read or wrote** — indexed sources and generated artifacts alike.
  Which of them is an input and which an output is already stated by the documents
  (`artifacts[].generated` / `own`); the hash table does not repeat it.
* **A header with the generation fingerprint**: `format`, `algo`, the tooling revision, and the flags
  that change output (`--packages`, `--adapters`, `--mapper`). Incremental correctness depends on
  options as much as on inputs: the same view with `--adapters` is different output. A table whose
  header does not match the invocation **must cause a full pass**.
* **Never a correctness input.** Missing, unreadable, version-mismatched or partially-trusted table →
  full pass. An incremental run that skips work must be *provably* equivalent: the recorded input
  hashes and the option fingerprint are the only licence to skip, and `ExampleRegenerationTest`'s
  byte-identity check must still hold when the incremental path is exercised on the example.
* **Pick the hash deliberately, once.** Check what `metadata/.../MetadataCache` (project-automation)
  already computes before inventing a second algorithm — but note the module boundary: the tooling must
  not depend on `project-automation` (`DependencyBoundaryTest`, DEC-W003), so either the hash function
  lives in a module both can use, or the tooling owns it and the watcher consumes the tooling's table.
  Record which, in DEC-028, at the time it is built.

**2.3.3 `artifacts[].inputs` — the dependency edges (reserved).** The pass knows, per emitted artifact,
the files it was generated *from*: the view's file, its supertypes, its addons, and (for a mapper) the
source view. Recording those ids turns the index into the dependency graph an incremental pass needs,
instead of a graph it must re-derive by parsing. This is the same "the relationship is written down, not
inferred" principle as DEC-019, applied to generation itself.

**Relationship to what already exists.** `metadata/watch/<toolSet>/metadata.db` (DEC-026 § 3) remains
the *watch agent's* cache and is **not replaced** by this plan; the index's tables are the
*generator's* record, written by a pass and readable as text. Consolidating the two is a follow-up
decision, not an assumption here (§ 10). The watcher's current "did the content actually change?"
check keeps working either way; a hash table in the index simply makes the same question answerable
per file, from a diffable artifact, without a second database.

### 2.4 An artifact inventory per view

```json
"artifacts": [
  { "id": 0, "name": "PersonSummary",        "kind": "interface", "file": "PersonSummary",  "line": 14, "generated": false },
  { "id": 1, "name": "PersonSummary.Record", "kind": "record",    "file": "PersonSummary",  "line": 26, "generated": false },
  { "id": 2, "name": "PersonSummary_",       "kind": "enum",      "file": "PersonSummary_", "line": 17, "generated": true,
    "header": "Field metadata for the PersonSummary view." }
]
```

* `id` — small integer, assigned in **reading order**: the view's own file first, then its nested types,
  then the generated siblings in the order the pass emits them. It is what `fields[].at` keys on, so it
  must be stable for a given view (it is a function of the artifact list, which is itself deterministic).
* `name` — the display name the page already prints (`PersonSummary`, `PersonSummary.Record`,
  `PersonSummaryBuilder`).
* `kind` — `interface` / `record` / `enum` / `class`.
* `file` — a **file id** (§ 2.1), never a path.
* `line` — the declaration line of that type *inside* that file (nested types have their own).
* `generated` — whether the file carries a DEC-021 header, i.e. whether the generator owns it. A
  hand-written nested record is an artifact of the view without being generated, and the page says so.
* `header` — the DEC-021 description text, or omitted for a hand-written file. Carried so a consumer
  does not re-read the file to label the artifact.

The declaring interface of an **inherited** field (`Person` for `PersonSummary.firstName`) is *not* in
the view's artifact list — it is not an artifact of this view. Its location still appears in
`fields[].at`, keyed by an artifact entry that the view's list must therefore contain… **no: see § 2.5**
— the `at` map is keyed by artifact **id**, so a foreign declaring interface needs an entry. Two options
were considered and one is chosen:

* **chosen: widen the artifact list** with the foreign declaring interfaces the view's fields reference,
  marked `"generated": false` and `"own": false` (a new boolean, `own` = "declared in this view's own
  file or generated for it"). The page then shows `Person` as an aspect of `PersonDetails`, which is
  exactly what it does today, and the `at` map needs no second key space.
* rejected: a second key space (`"foreign": {…}`), which doubles the shapes a consumer handles for no
  information gain.

### 2.5 A field's locations: one entry per artifact, `role → line` inside it

```json
"fields": [
  { "name": "age", "ordinal": 4,
    "type": { "type": "java.lang.Integer", "unboxed": "int", "primitive": true },
    "fieldKind": "DERIVED", "expression": "YEAR(NOW()) - YEAR(birthDate)",
    "at": {
      "0": { "annotation": 16, "accessor": 17 },
      "2": { "enum-constant": 43, "name-slot": 92 },
      "4": { "field": 18, "setter": 82, "ordinal-slot": 63 },
      "5": { "field": 28, "setter": 96, "ordinal-slot": 63 }
    } }
]
```

* Keys of `at` are **artifact ids** (1–2 characters); values are `role → line` maps.
* Why grouped rather than a flat list of `{artifact, role, file, line}`: an artifact name is nearly as
  long as a path (`PersonSummaryBuilderTracking` is 28 characters), so repeating it per location keeps
  the payload large — the whole point of the table is lost. Grouping also reads like the artifact it
  describes.
* **Role vocabulary** (exactly these strings; the renderer's existing labels must not change, because
  the page's columns derive from them):

| role               | meaning                                                                     |
| ------------------ | --------------------------------------------------------------------------- |
| `accessor`         | the no-argument read method (`Integer age();`), in a hand-written interface |
| `annotation`       | the `@FieldSource` line that defines a `DERIVED`/`JOINED` field             |
| `enum-constant`    | the constant in `<View>_` — DEC-023's ordinal ledger position               |
| `name-slot`        | the `forName` arm (`case "age":`) in `<View>_`                              |
| `record-component` | a component of the record materialization                                   |
| `field`            | the stored field in a builder                                               |
| `setter`           | the fluent setter in a builder, or a `Write` method in a nested interface   |
| `ordinal-slot`     | the `case 3 ->` arm in a builder's `get(int)`/`set(int, Object)`            |

* A role that does not exist for a field is **absent**, not empty — a `DERIVED` field has no `setter`
  anywhere, and `PersonSummary` has no record location for a field it does not carry. The page renders
  the absence.

### 2.6 What replaces what

| Today                            | After                                                        | Note                                                            |
| -------------------------------- | ------------------------------------------------------------ | --------------------------------------------------------------- |
| `markerSourcePath` (path string) | `"markerFile": "<id>"` + `"markerLine": <int>`               | the marker is a class; give it its declaration line too, so the page needs no scan for its link. The path itself moves to `.jcodebuddy/index/files.json` |
| `views[].sourcePath`             | `views[].file` (id)                                          | the declaration line stays `views[].lineNumber` (declaration start, annotation included) **and** the view's artifact entry carries the name-token line |
| `properties[].sourcePath`        | `properties[].file` (id)                                     | `properties[]` stays the "own declaration" record; it does **not** gain a location map — the view's `fields[].at` already holds the interface-side lines for every field, including these |
| `allFields[].sourcePath`         | `allFields[].file` (id)                                      | the declaring accessor's file, as today                         |
| —                                | `views[].artifacts[]`, `views[].fields[].at`                 | new                                                             |
| —                                | `.jcodebuddy/index/files.json` (`files`)                     | new — **outside** the document, one per module                  |
| —                                | root `"fileIndex": "<index path relative to this document>"` | new — so a consumer never has to guess the `.jcodebuddy` layout |

`allFields` deliberately does **not** gain a location map: it is a per-marker union, and a union of
per-view artifact ids has no stable meaning. The authoritative per-field location map is
`views[].fields[].at`; a consumer that wants "everywhere this field is" walks the marker's views. If you
disagree, say so before § 4.6 — the alternative is a flat `locations` list on `allFields`, which costs
roughly as much as the per-view maps together.

### 2.7 Decisions assumed in this plan (overturn before § 4.1 if wrong)

The requester fixed the *requirements* (a central id index under `.jcodebuddy/index`, more compact than
paths, several locations per field, and room for content hashes later) but not the choices below. This
plan assumes:

1. **Id scheme A**: readable simple name + shortest unique package suffix (§ 2.1) — *not* a hash, *not* an
   index.
2. **Location shape**: grouped `at` map with `role → line` (§ 2.5) — *not* a flat list of full
   location objects.
3. **`sourcePath` is replaced**, not kept alongside the ids (§ 2.6). `fromJson` still *reads* the old
   keys for one revision, and the renderer keeps its legacy fallback, so an old JSON still works; but
   nothing emits two spellings of the same fact.
4. **Index layout**: a directory `.jcodebuddy/index/` holding one table per concern — `files.json`
   (addressing) written by this plan, `hashes.json` and `artifacts[].inputs` merely reserved (§ 2.3) —
   plus a `README.md` in the directory stating all three, because DEC-026 requires a README per
   subfolder. A single `.jcodebuddy/index.json`, or a table under `metadata/`, are the alternatives.
5. **The `fileIndex` pointer** is emitted in every document (so a consumer that has only the document can
   find the table) *and* the renderer resolves the index from the module root it already knows
   (`<linkBase>/.jcodebuddy/index/files.json`), so a wrong pointer cannot silently break the page.
6. **Hashes are reserved, not built** (§ 2.3): the layout and its `format` version make room for them;
   nothing writes `hashes.json` in this plan.
7. **Documentation**: a new **DEC-028** ("metadata addresses source files through a central index, and a
   field carries all its locations"), registered in the decisions index with one line added to the
   `AGENTS.md` bullet, *plus* a **DEC-026 amendment** for the new `index/` subfolder and its track
   policy. (Alternative: fold both into DEC-027 amendments. A new decision is preferred because the
   addressing scheme is a contract for *all* metadata consumers, not only the HTML report.)
8. **Foreign declaring interfaces** are listed in `views[].artifacts[]` with `own: false` (§ 2.4).

### 2.8 Why the size win is real but not the whole story

Honest accounting, because the feature *adds information* (≈420 locations where the metadata has ≈60
path/line pairs today), and because a central index moves the table *out* of the documents instead of
triplicating it:

| Variant                                                               | Path/identity payload          | Module total (est.) |
| --------------------------------------------------------------------- | ------------------------------ | ------------------- |
| today (one location per field, inline path, per document)             | 2.5 KB, rewritten per document | 26.0 KB (measured)  |
| all locations, **path inline per location**                           | ~35 KB                         | ~55–60 KB           |
| all locations, flat objects with ids, **table per document**          | ~15 KB + a table per document  | ~38 KB              |
| **all locations, grouped `at` + ids + one central index (this plan)** | **~9 KB + one 2.5 KB table**   | **~25–30 KB**       |

So the honest claim is: three to four times more location information for *about the same* number of
bytes as today, where inlining paths would have cost more than twice today's size. The durable win is not
the bytes: it is the **single statement of a path**, the **stable handle** a consumer cross-references,
and an index that has a defined place to grow (hashes, dependency edges) without changing anyone's
contract.

---

## 3. Target contract

### 3.1 A worked fragment (shapes are the contract; the line numbers are the current revision's)

**The index**, written once per pass, per module (`.jcodebuddy/index/files.json`):

```jsonc
{
  "format": 1,
  "module": "hipster-entity-example",
  "sourceRoot": "src/main/java",
  "files": {
    "Person":                       "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java",
    "PersonCreateForm":             "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonCreateForm.java",
    "PersonSummary":                "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
    "PersonSummaryBuilder":         "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummaryBuilder.java",
    "PersonSummaryBuilderTracking": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummaryBuilderTracking.java",
    "PersonSummary_":               "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary_.java"
  }
}
```

`format` is the table's own version (an unknown value means "do not trust this table"); `module` and
`sourceRoot` are diagnostics that make the file self-describing when someone opens it directly. When the
reserved tables land, `hashes.json` joins them in the same directory with its own header (§ 2.3) — the
addressing table's shape does not change.

**A document** (`…/.jcodebuddy/metadata/entity/Person.metadata.json`) — note that it contains **no path
at all**, only a pointer to the index:

```jsonc
{
  "entityName": "Person",
  "package": "hr.hrg.hipster.entityexample.person.entity",
  "markerInterface": "Person",
  "idType": "Long",
  "markerFile": "Person",
  "markerLine": 14,
  "fileIndex": "../../index/files.json",

  "views": [
    {
      "name": "PersonSummary",
      "lineNumber": 13,
      "file": "PersonSummary",
      "extends": ["Person"],
      "gen": "BUILDER_ALL",
      "discriminatorField": "",
      "addons": [],
      "properties": [
        { "name": "age", "type": { "type": "java.lang.Integer", "unboxed": "int", "primitive": true },
          "lineNumber": 16, "file": "PersonSummary", "fieldKind": "DERIVED",
          "expression": "YEAR(NOW()) - YEAR(birthDate)" }
      ],
      "artifacts": [
        { "id": 0, "name": "PersonSummary", "kind": "interface", "file": "PersonSummary",
          "line": 14, "generated": false, "own": true },
        { "id": 1, "name": "PersonSummary.Record", "kind": "record", "file": "PersonSummary",
          "line": 26, "generated": false, "own": true },
        { "id": 2, "name": "PersonSummary_", "kind": "enum", "file": "PersonSummary_",
          "line": 17, "generated": true, "own": true,
          "header": "Field metadata for the PersonSummary view." },
        { "id": 3, "name": "PersonSummaryBuilder", "kind": "class", "file": "PersonSummaryBuilder",
          "line": 13, "generated": true, "own": true,
          "header": "Mutable builder for the PersonSummary view." },
        { "id": 4, "name": "PersonSummaryBuilderTracking", "kind": "class",
          "file": "PersonSummaryBuilderTracking", "line": 24, "generated": true, "own": true,
          "header": "Tracking builder for the PersonSummary view." },
        { "id": 5, "name": "Person", "kind": "interface", "file": "Person",
          "line": 14, "generated": false, "own": false }
      ],
      "fields": [
        { "name": "id", "ordinal": 1, "type": { "type": "java.lang.Long", "unboxed": "long", "primitive": true },
          "fieldKind": "COLUMN",
          "at": { "2": { "enum-constant": 19, "name-slot": 89 },
                  "3": { "field": 15, "accessor": 36, "setter": 73, "ordinal-slot": 61 },
                  "4": { "field": 29, "accessor": 46, "setter": 66, "ordinal-slot": 53 } } },
        { "name": "age", "ordinal": 4, "type": { "type": "java.lang.Integer", "unboxed": "int", "primitive": true },
          "fieldKind": "DERIVED", "expression": "YEAR(NOW()) - YEAR(birthDate)",
          "at": { "0": { "annotation": 16, "accessor": 17 },
                  "1": { "record-component": 30 },
                  "2": { "enum-constant": 43, "name-slot": 92 },
                  "3": { "field": 18, "accessor": 48, "ordinal-slot": 63 },
                  "4": { "field": 32, "accessor": 50, "ordinal-slot": 55 } } }
      ]
    }
  ],

  "allFields": [
    { "name": "age", "type": { "type": "java.lang.Integer", "unboxed": "int", "primitive": true },
      "lineNumber": 16, "file": "PersonSummary", "fieldKind": "DERIVED",
      "expression": "YEAR(NOW()) - YEAR(birthDate)",
      "views": ["PersonSummary", "PersonDto"],
      "typeByView": { "PersonSummary": { "type": "java.lang.Integer", "unboxed": "int", "primitive": true } } }
  ]
}
```

### 3.2 Rules the writer must obey

1. **Ordering**: `files` sorted by id (the index); `artifacts` in reading order (view file, nested types,
   generated siblings in emission order, then foreign declaring interfaces in first-reference order);
   `fields` in **DEC-023 ledger order** (the order the emitters used); inside `at`, artifacts in
   `artifacts` order and roles in this order — `accessor`, `annotation`, `enum-constant`, `name-slot`,
   `record-component`, `field`, `setter`, `ordinal-slot`; inside a role map, insertion order.
2. **Paths**: module-relative, forward slashes, `.java` included, `src/main/java/…` prefix, never
   absolute, never `..`, never project-relative. **A source path appears only in the index** — nowhere
   else in any document, and nowhere in the page. The single `fileIndex` pointer is the only path-like
   value in a document, and it points at the index, not at a source file.
3. **No content, no timestamps, no absolute paths.** Non-negotiable (§ 0.5.7–8). The index is written by
   every pass and must be byte-identical for an unchanged tree, or a project that commits it gets diff
   noise.
4. **Index header**: `format` (integer, incremented when the table's meaning changes), `module`,
   `sourceRoot`, `files`. A consumer that does not recognise `format` must refuse the table and say why —
   never guess.
5. **`id` values** are small integers, unique within the view's artifact list, stable for a given tree.
6. **Omitted vs null**: a role that does not exist is omitted; a value that is genuinely unknown
   (`column`, `expression`, `header`, a field's `file` when it is declared outside the module) follows
   the convention its key already has today (some keys are omitted, some are written as `null`) — do not
   introduce a third convention.
7. **The hand-rolled writer's comma discipline.** `toJson` is a `StringBuilder` with an explicit
   `trailingComma` boolean per field; every insertion must keep the document valid at every prefix
   (there is no formatter to catch a mistake — a wrong comma produces a JSON file that only the tests
   will reject, and `fromJson` will throw).
8. **Legacy reading**: `fromJson` accepts `markerSourcePath`/`sourcePath` (string paths) as well as
   `markerFile`/`file` (ids) — a document written before this change must still parse. The renderer's
   legacy path (§ 4.9) covers the same case at the page level.
9. **The index and the documents are written in one pass, atomically enough to be consistent**: the
   index first (it must be complete before the first id is resolved), then the documents (§ 4.5). A
   document that cannot resolve an id is a bug, not a fallback.

---

## 4. Work items

Each item: **change → files → why → how you know it worked.**

### 4.1 Model records (`hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/`)

* New `SourceLocation(String artifact, String role, String path, int line)` — one location, `path` a
  **file id**. Used by the extraction helper internally and, if you keep a flat shape anywhere, in the
  JSON.
* New `ArtifactMeta(int id, String name, String kind, String file, int line, boolean generated,
  boolean own, String header)`.
* New `ViewFieldMeta(String name, int ordinal, String type, String fieldKind, String column,
  String relation, String expression, Map<Integer, Map<String, Integer>> at)` — or a small
  `FieldLocations` type instead of the nested map, if that reads better in Java; the JSON shape must not
  change either way.
* `ViewMeta` += `List<ArtifactMeta> artifacts`, `List<ViewFieldMeta> fields`, plus a copy method
  (`withDetails(artifacts, fields)`) so § 4.5 can build the final list without re-listing components.
* `Property` += `List<SourceLocation> locations` (interface side: `annotation`, `accessor`).
* `EntityFieldMeta` += `List<SourceLocation> locations` (the same shape, union across views) — or drop
  it if § 2.6's `file`-only decision is kept and nothing consumes it. Decide explicitly and document.
* `EntityMeta` += `int markerLine` (and `markerFile` if you convert `markerSourcePath`).

**Why:** the JSON is written from these records; the contract cannot exist before the model does.
**Traps:** `Property`/`ViewMeta` are records — adding a component changes `equals`; keep a
back-compatible overloaded constructor with the old arity for every existing call site (the pattern is
already used in `Property`, `InterfaceInfo`, `ViewMeta`, `EntityMeta`). `EntityFieldMeta` is mutable with
`equals` on `(name, lineNumber)` only — adding fields does not change its identity.
**Check:** `scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am compile`.

### 4.2 The central index (`ModuleFileIndex`, new class in `…/tooling/`)

* **Where**: `indexDir = <nearest .jcodebuddy above the report dir>/index`, falling back to
  `<report dir>/index` when the report directory is not inside a `.jcodebuddy` (§ 2.2). Reuse the
  walk-up the path change already added (`nearestDirectoryNamed(outputDir, JCODEBUDDY_DIR)`).
* **What it collects**: every indexed source file's module-relative path (pass 1 already has them), plus
  every artifact file the pass writes. Nothing else — no hashes in this plan (§ 2.3).
* **Ids**: `idFor(String moduleRelativePath)`, § 2.1's rule; uniqueness is computed over the whole
  collected set, so every document and every run agrees. Group by simple name, then qualify by package
  suffix, shortest first — a function of the *set*, never of visit order.
* **Writes**: `<indexDir>/files.json` — the header (`format`, `module`, `sourceRoot`) plus `files`
  sorted by id. `mkdir -p` first. Deterministic bytes for an unchanged tree.
* **Also writes**: `<indexDir>/README.md` if it is absent — DEC-026 requires a README in every
  `.jcodebuddy/` subfolder, and this one must state what `files.json` is, that ids are module-relative
  and path-derived, that paths appear **only** here, and that `hashes.json`/`inputs` are reserved
  (§ 2.3). Never overwrite an existing README (it is tracked, i.e. owned by a human).
* **Exposes**: `pathFor(id)`, `idFor(path)`, and the whole table for the JSON writer.

**Why:** ids must be unique module-wide and stable across every document and every run; the index is the
one place that can compute that, and it is the home the watcher/incremental work will need (§ 2.3).
**Traps:** id computation must not depend on `Files.walk` order — sort the paths, derive ids from the set.
Do not write a timestamp or a file count that varies with the checkout.
**Check:** `MetadataFileIdsTest` (§ 5).

**Follow-up the executor must note in the report, not build:** a later `hashes.json`
(content hashes + generation fingerprint) and `artifacts[].inputs` (dependency edges) — the design in
§ 2.3 — are what make watcher mode and an incremental pass possible; the index directory and its
`format` version are chosen now so they need no layout change then.

### 4.3 Interface-side locations (`parseProperty`)

* `parseProperty` already holds the `MethodDeclaration`. Record:
  * `accessor` — `method.getName().getBegin().line` (**not** `method.getBegin()`, which includes
    annotations and is what the existing `lineNumber` means);
  * `annotation` — the line of `@FieldSource`, when present.
* The location's `artifact` string is the declaring interface's display name; pass the simple name into
  `parseProperty` (it is available at the call site in pass 2) so a nested or inherited declaration is
  labelled correctly.
* Keep `Property.lineNumber` and `Property.sourcePath` semantics for now — § 4.6 converts them to
  `file`.

**Why:** these two lines are the interface half of "where is this field", and the accessor/annotation
distinction cannot be recovered from `lineNumber` alone.
**Traps:** a lookahead placed *after* `\s+` in a regex blocks the identifier's first character — that bug
silently disabled a whole member scan in the renderer. Here you are reading the AST, so prefer
JavaParser's positions over regexes.
**Check:** in `MetadataLocationsTest`, `age` in `PersonSummary` must show `annotation` 16 and `accessor`
17 (values from the current revision).

### 4.4 Artifact-side locations (`MetadataLocations`, new class in `…/tooling/`)

* `List<Path> candidates(Path javaOutputRoot, Path sourceRoot, String viewPackage, String viewName,
  InterfaceInfo viewInfo)`: the **view's own file** (from `viewInfo.sourcePath()` resolved against the
  module root), plus, under `javaOutputRoot/<viewPackage as dirs>/`, the generator's naming conventions
  that exist: `<View>_`, `<View>Record`, `<View>Builder`, `<View>BuilderTracking`, `<View>Validator`,
  `<View>RowAdapter`, `<View>Binder`. (The emitter `Result` records carry the same paths; either source
  works — the convention list is what the renderer uses today and therefore what the page's artifact
  names already are. If you use the `Result`s, still include the view file, because its nested record and
  `Write` are aspects.)
* For each candidate: read it with `SourceReader.read` (the tooling's only safe read — it distinguishes a
  clean parse from a partial one), then for every type declaration in it (top-level **and** nested):
  * an `ArtifactMeta`: display name (`Outer.Inner` for a nested type), kind, file id, declaration line
    (`decl.getName().getBegin().line`), `generated` (the file carries a DEC-021 header, read from its
    first lines), `header` (that header's description, top-level only), `own` (the file is the view's own
    or was generated for it).
  * per field name of interest, every location: `enum-constant` (enum entries), `record-component`
    (record parameters), `accessor` (0-arg method — use the **name token's** line), `setter` (1-arg
    method), `field` (field declaration variables), `ordinal-slot` (integer-labelled switch arms, for the
    field names the arm references), `name-slot` (string-labelled switch arms, by the literal).
* Unreadable file → report the existing `source_not_parsed` divergence via
  `SourceReader.reportUnparseable` and contribute nothing. **Never fail a pass** over a location you
  could not read; a missing location is honest, a wrong one is not.
* De-duplicate on `(artifact, role, fileId, line)` — the interface-side and the artifact-side scans both
  see an own accessor, and both must collapse to one entry.

**Why:** this is the information the pass has and the renderer currently guesses.
**Traps:** the *view file* holds several aspects (the interface, a nested `Record`, a nested `Write`), so
locations must carry the artifact display name and not just the file; the same field can appear twice in
one file for different artifacts, which is why de-duplication keys on the artifact as well.
**Check:** § 5's `MetadataLocationsTest` cases.

### 4.5 Assemble the per-view detail, and order the writes: artifacts → index → documents

Today `generateInternal` writes `<Marker>.metadata.json` **before** the per-view emission loop, once per
marker. The metadata must describe what the loop produced, **and** no id can be resolved before the
central index is complete, so the order inside a pass becomes:

1. Per marker: build `views` exactly as today (the discovery result) and keep a
   `Map<String, ViewDetail> detailByView`.
2. Per view, after every emitter has run for it (the record, the field enum, both builders, the
   validator, the adapters when enabled): collect the artifact files (§ 4.4), extract the locations, and
   store `artifacts` + `fields` (ledger-ordered, from `ordinalProperties`) in `detailByView`. A view
   skipped by the `--packages` filter gets **empty** lists — the metadata then says what this pass
   produced, which is the honest answer; note the small behaviour change for a filtered view (the page
   will show its declaration and no columns).
3. After the view loop, still per marker: assemble that marker's `EntityMeta` in memory —
   `views.map(v -> v.withDetails(...))`, `collectEntityFields(...)` (§ 4.8) — and **do not write it yet**.
   Keep the per-marker `(entityName, jsonSource)` in a pass-level list.
4. After **all** markers: build and write the central index (§ 4.2), then write each document, resolving
   paths to ids through the index (`toJson(entityMeta, index)`). The documents therefore land after the
   whole pass, not inside the marker loop.

**Why:** locations exist only after emission, and ids exist only after the index — so the documents are
the last thing a pass writes. Writing the JSON first would force either a second pass over the emitters
or a second source scan, which is exactly what this plan deletes.
**Traps:** (a) a pass that throws half-way now writes no document where today it would have written the
earlier markers' documents; that is acceptable — `writeRunRecord` already records a failed pass, and the
guard tests assert the JSON only after a *successful* pass — but state it in the report, because it is a
behaviour change. (b) Check nothing after the old write site depended on it being earlier: the run record
is written by `writeRunRecord` in a `finally`, and the mapper phase uses the separate `emittedViews`
registry — verify both. (c) Keep `ValidationGenerator.reportAndFilter` called exactly once per view:
calling it again to build the field list would report an unsupported constraint twice. (d) Create the
output directory before writing each document, and never write a document before the index exists.
**Check:** `scripts\gen.cmd` writes both the index and the documents, and `ExampleRegenerationTest` stays
green.

### 4.6 `toJson` — the new keys

* Root: `markerFile`, `markerLine`, `fileIndex` (before `views`, after `idType`). **No `files` table** —
  it lives in the index (§ 4.2).
* View: `file` (replacing `sourcePath`), `artifacts`, `fields` (after `properties`).
* Property: `file` (replacing `sourcePath`).
* AllField: `file` (replacing `sourcePath`).
* `at` maps: artifact id → role → line. Object keys are quoted (`"2": { … }`) so any JSON parser reads
  them the same way; the renderer iterates with `Object.keys`.
* `toJson` gains a parameter (the index, or a `path -> id` function) so every recorded path becomes an
  id at the single place where JSON is written. If a path is not in the index, that is a **bug**: fail
  loudly (an `IllegalStateException` naming the path) rather than writing a path into a document and
  quietly breaking the "paths appear once" rule.
* Keep the existing `appendJsonField`/`appendJsonType`/`escapeJson` helpers and the indentation style
  (2 spaces per level, the existing per-key indent numbers).

**Why:** the contract of § 3.
**Traps:** every insertion must preserve the comma discipline (§ 3.2.7); `appendJsonField` writes `null`
for a null value, so *omit* a key you do not want rather than passing null where the neighbours omit.
**Check:** `jq .` (or PowerShell `ConvertFrom-Json`) parses the index and all documents;
`MetadataFileIdsTest` asserts no source path appears outside the index.

### 4.7 `fromJson` — read the new keys, keep the old ones working

* Accept an optional index (or a `Map<String,String> idToPath`) so ids can be resolved; without it, keep
  the ids unresolved rather than inventing paths.
* Decide once and write it down: does the Java model keep **paths** (resolved on read) or **ids**? The
  model is public API for Java consumers (`EntityMeta`). Recommended: **paths in the model, ids in the
  JSON** — one conversion point in `toJson`/`fromJson`, and `Property.sourcePath` keeps its meaning for
  every existing caller and test.
* Accept `markerSourcePath`/`sourcePath` when the id keys are absent (an older document).
* Read `markerLine`, `views[].file`, `properties[].file`, `allFields[].file`, `artifacts`, `fields[].at`
  into the model.

**Why:** a document written before this change must still parse, and a Java consumer should not have to
join two JSON files to get a path.
**Check:** `EntityMetadataGeneratorTest` (it round-trips through `fromJson`) plus a new case that parses a
legacy document and one that parses a document with an index.

### 4.8 `collectEntityFields` — the union keeps working

* It merges each field across the marker's views. Pass it the per-view field detail from § 4.5 instead of
  recomputing `collectViewProperties` (avoid the double `reportAndFilter` of § 4.5).
* Keep the existing "non-derived wins over derived" rule, and carry the location/`file` of the winning
  declaration with it (the same rule already applied to `sourcePath` in the path change).
* `allFields[].file` must be the declaring accessor's file, as today.

**Why:** the marker-level summary is consumed by the page and by any cross-view query.
**Check:** `MetadataSourcePathTest`'s inherited/addon assertions, adapted to the new keys.

### 4.9 Renderer — metadata-driven discovery, scan only for verification

`scripts/entity-html/`:

* `metadata.js`: read the **index** (`files.json`: id → path, resolved once into a `Map`, located from
  the module root the CLI already knows and cross-checked against the document's `fileIndex` pointer),
  then `views[].file`, `properties[].file`, `allFields[].file`, `markerFile`/`markerLine`,
  `views[].artifacts[]`, `views[].fields[].at`. If the index is missing or its `format` is unknown,
  report `html_index_missing` and stop — do **not** render ids as if they were paths.
* `links.js`: replace the discovery half — `resolveViewType`, `artifactsFor`, `seedsOf`, `columnsOf`,
  `cellFor`, `declaringAccessor`, `annotationCell` — with reads of the tables. Build the page model as:
  * entity → marker (name, package, id type, marker file/line),
  * view → artifacts (name, kind, path, line, description) and fields (ordinal, type, chips, and a
    `(artifact, role) → {path, line}` cell map).
  * Column order: artifact order from `artifacts[]`, then the role order above — which reproduces the
    current page's columns, including the "view · accessor" and "declaring interface" columns.
* Keep **verification**: for every cell, read the target file and assert the line still contains the
  member (role `annotation` → the line contains `@FieldSource`); drop and report `html_link_stale`
  otherwise, and exit 1 unless `--soft`. This is DEC-027 § 4.2 and does not change.
* Keep the **legacy path**: when a document has no `files`/`artifacts`/`fields`, fall back to today's
  scan-based discovery — the existing bun test that strips `sourcePath` must keep passing.
* `sources.js` stays, reduced to what verification and the legacy path need (it is still the reader that
  knows how to check a line for a member).

**Why:** this is the point of the plan — a consumer reads the answer; the page stops inferring it.
**Traps:** the page's rendered output must not change (same columns, same labels, same link targets);
diff `index.html` before and after and explain every difference. Watch the template-literal rule
(§ 0.5.6).
**Check:** § 5's bun tests, plus a visual pass in IntelliJ.

### 4.10 Regenerate and measure

* `scripts\gen.cmd` — expect no change under `hipster-entity-example/src` (the generated Java does not
  depend on any of this) and a re-rendered page.
* Record the size of the three documents and the page; compare with § 0.4's baseline and with § 2.8's
  estimate. If the JSON more than doubles, stop and re-examine the shape before documenting it.

---

## 5. Tests

### 5.1 Java (`hipster-entity-tooling/src/test/java/…/tooling/`)

| Test                                            | Asserts                                                                                            | Fails when                                                                           |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `MetadataLocationsTest` (new)                   | a pass over the example records, for `PersonSummary.age`: `annotation` and `accessor` in the view file, `enum-constant` + `name-slot` in `PersonSummary_.java`, `record-component` in the nested record, `field`/`accessor`/`setter`/`ordinal-slot` in both builders; and that **no** `setter` exists for the DERIVED `age` | the extraction misses a role, or invents one |
|                                                 | an inherited field (`PersonDetails.firstName`) records its `accessor` location in `Person.java`, not in the view's file | the declaring file is taken from the view instead of the metadata |
|                                                 | the same field name in two views has **different** `enum-constant` locations (`PersonSummary.age` vs `PersonDto.age`) | locations are keyed by field name only, losing the per-view file  |
|                                                 | every location's file exists and its line contains the member (role `annotation` → `@FieldSource`) | a recorded line is stale or off by one                                               |
| `MetadataFileIdsTest` (new)                     | the **index** is the single table: `files.json` exists at `.jcodebuddy/index/`, carries its `format`/`module`/`sourceRoot` header, and every `file` value used by any document resolves in it; ids are unique in the module; two passes produce identical index bytes; `entity.Person` / `iface.Person` / `record.Person` prove the disambiguation rule; each document's `fileIndex` pointer resolves to that index; **no source path appears anywhere outside the index** (no `.java` and no `/` in any `file` value, and no `sourcePath`/`markerSourcePath` key) | a path leaks back into a document, ids are order-dependent, or the pointer is wrong |
| `MetadataSourcePathTest` (adapt)                | its module-relative / declares-its-owner / no-source-content assertions now run against the **index** (owner assertions resolve id → path → file) | a path is absolute, project-relative, or content sneaks in |
| `IndexLayoutTest` (new, small)                  | `.jcodebuddy/index/` contains `files.json` and `README.md` and **nothing else** in this plan; the README is not overwritten if it exists (write a sentinel, re-run a pass, read it back) | the reserved tables are implemented prematurely, or a pass clobbers a tracked README |
| `ExampleRegenerationTest` (unchanged)           | generated `.java` is byte-identical                                                                | the pass changed emitted Java (it must not)                                          |
| `GeneratorGuardTest` (unchanged)                | no `.java` under any `.jcodebuddy/`, report dir holds only JSON                                    | a location feature writes a file somewhere new                                       |
| `HtmlRenderBoundaryTest` (unchanged + one line) | the renderer reads the metadata for its model — add an assertion that it reads the index (`files.json`) and the artifact inventory | the renderer still discovers by scan                 |

### 5.2 Bun (`scripts/entity-html/entity-html.test.js`)

* **Every page link maps back to a metadata location.** For each `data-open`/`data-line` pair, find the
  location it came from in the page model, and assert the set of page links equals the set of metadata
  locations the views' `at` maps contain (after verification filtering). This is the test that proves
  discovery no longer scans.
* **The page resolves ids through the index**: read `files.json` from the module root (not from the
  document pointer alone) and assert both routes give the same table; with the index renamed away, the
  renderer must fail with `html_index_missing` rather than render dangling links.
* **Legacy document still renders.** Strip `fileIndex`/`artifacts`/`fields` (extend the existing
  "stripped metadata" test, which currently strips `sourcePath`) and assert the page still resolves,
  with no error-severity divergences.
* **Determinism, no source text, no absolute path, one self-contained file** — the existing tests,
  unchanged.
* **Size budget**: measure the index plus the three documents and assert they stay under ~30–35 KB, with
  a comment naming the counterfactual (paths inlined per location would be ~55–60 KB). Keep the number
  generous enough not to break on an added entity; the point is to catch a *shape* regression, not to
  police a byte.

---

## 6. Documentation

1. **`doc-hipster-entity/architecture/decisions/DEC-028.md`** (new): "Metadata addresses source files
   through a central index, and a field carries all its locations." Follow the existing ADR shape
   (`Status/Date/Owners/Related docs`, Context, Decision, Alternatives considered, Consequences, Out of
   scope, Acceptance criteria). It must state: the index is per module at `.jcodebuddy/index/`, written
   once per pass; ids are path-derived and module-relative; a source path appears **once**, in
   `files.json`; every document points at the index with `fileIndex`; a field's locations are grouped per
   artifact with the fixed role vocabulary; the renderer verifies every link and no longer discovers; no
   content is ever recorded; legacy documents still parse; and the index reserves `hashes.json` +
   `artifacts[].inputs` for watcher/incremental work (§ 2.3) without specifying them yet.
2. **`DEC-026` amendment** (same document, or a short new decision if you prefer — but DEC-026 owns the
   layout): `.jcodebuddy/index/` joins the fixed subfolder list as "the module's index: addressing tables
   a metadata document references, and (reserved) content hashes for watcher/incremental use". Record:
   it is derived and ignored by default like `metadata/`; **the opt-in rule must carry it together with
   the metadata subtree**, or committed documents reference an uncommitted table (add the exact
   two-line gitignore snippet next to the existing one); and it keeps a tracked `README.md` like every
   other subfolder. Update `.jcodebuddy/.gitignore`, `.jcodebuddy/README.md` (layout table + track
   policy), and the sibling `metadata/*/README.md` files if they enumerate the layout.
3. **Decisions index** (`…/decisions/README.md`): a `DEC-028` row + note, and the heading range
   `DEC-001 — DEC-028`.
4. **`AGENTS.md`** § 1 HTML-report bullet: one sentence — a metadata document names source files through
   the module's central index id (`.jcodebuddy/index/`), a field carries all its locations, and neither
   the record nor the report ever contains a file's text.
5. **`hipster-entity-tooling/README.md`**: turn "Source paths in the metadata" into "The module index,
   and a field's locations" — the index layout, the id rule, the artifact inventory, the role
   vocabulary, the `fileIndex` pointer, and the legacy reading rule.
6. **`scripts/entity-html/README.md`**: the contract table gains the index, `artifacts` and `at`; state
   that discovery reads the index + documents and the scan is used only to *verify* a link, and that a
   missing index is a loud diagnostic.
7. **`hipster-entity-example/codebuddy.md`** § 5.1 (+ § 3.10 if it names keys), **`.jcodebuddy/metadata/entity/README.md`**,
   **`.jcodebuddy/README.md`** (layout + track policy) and **`.jcodebuddy/context/entity-html-index.md`**:
   the new layout, the new keys, and one worked example each.
8. If you prefer amendments over a new DEC (§ 2.7.7), mirror the amendment style already in DEC-027 and
   update the index note instead — but the DEC-026 layout change is required either way.

---

## 7. Verification

```
scripts\mvn-jdk25.cmd                    # BUILD SUCCESS, 7/7 modules; tooling count grows by the new tests
cmd /c 'scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=MetadataLocationsTest,MetadataFileIdsTest,IndexLayoutTest" "-Dsurefire.failIfNoSpecifiedTests=false"'
scripts\gen.cmd                          # "Link check: N links verified …", "Divergences: 0 (2 metadata note(s))"
cd scripts ; bun test entity-html        # all pass; note the count
git status --porcelain                   # no hipster-entity-example/src changes; the JSON, index and page are ignored
Get-ChildItem -Recurse hipster-entity-example\.jcodebuddy\index | Select-Object Name,Length
Get-Content hipster-entity-example\.jcodebuddy\metadata\entity\Person.metadata.json -TotalCount 12
                                         # expect "fileIndex" and no "src/main/java" anywhere in the document
```

Notes: PowerShell mangles an unquoted `-D…=…`, so wrap the whole `cmd /c` in single quotes
(`codebuddy.md` § 3.9). `bun test` runs from `scripts/`, not the repository root. The index, the metadata
and the page are git-ignored, so their absence from `git status` is correct, not a failure to write them
— except `.jcodebuddy/index/README.md`, which is tracked and must appear as a new file when it is first
created.

Finally, open the page in IntelliJ (right-click
`hipster-entity-example/.jcodebuddy/metadata/entity/index.html` → **Open in WebView Explorer**) and click
a marker, a view, an aspect card, a column header and a few cells.

---

## 8. Traps already paid for — do not re-learn these

1. **`.cmd` files are ASCII + CRLF.** Write them with an explicit ASCII/CRLF writer; a text editor that
   normalises line endings breaks `cmd.exe` parsing.
2. **The renderer's inline script is inside a JS template literal.** No backticks, no `${…}`, no
   backslashes in the emitted script.
3. **Record `equals` changes when you add a component.** Keep back-compatible constructors; check
   `List.contains`/`Set` usage before assuming nothing compares them.
4. **`Stream.findFirst()` throws on a `null` element.** Return the record (then read its nullable field)
   rather than mapping to the nullable value.
5. **A regex lookahead after `\s+` blocks the identifier.** `(?![\w$.])` placed there rejects every
   match, because the next character *is* a word character. Prefer AST positions.
6. **`Property.lineNumber` is the declaration start, annotations included.** The accessor's own line is a
   different number (16 vs 17 for `PersonSummary.age`); do not conflate them.
7. **`allFields` is a per-marker union, not a per-view field list.** Ordinals come from the field enum
   (DEC-023); never derive an order from `allFields`.
8. **JSON keys are additive, and `fromJson` must read what `toJson` writes.** No external consumer
   deserializes `<Marker>.metadata.json` (verified), so adding keys is safe — but `EntityMetadataGeneratorTest`
   round-trips through `fromJson`, so a key that is written and not read is a silent contract hole.
9. **`--packages` filters generation, not indexing.** A skipped view emits nothing; the metadata must say
   so rather than pretend.
10. **`ValidationGenerator.reportAndFilter` must be called once per view.** Calling it again to build the
    field list reports an unusable constraint twice.
11. **Determinism.** No timestamps, no hash-ordered iteration, no walk order in the output.
12. **Nothing under `.jcodebuddy/` may be source**: no `.java`, no file content, and the report directory
    holds only JSON (plus its `README.md`).
13. **The pass regenerates the example in place.** Always check `git status` for
    `hipster-entity-example/src`; an unexpected diff there means the change altered emitted Java.
14. **The index must be complete before the first document is written.** Ids are resolved from it, so a
    document written from a partial table has dangling references. That is why the writes move to
    `artifacts → index → documents` at the end of the pass (§ 4.5), not just "after the view loop".
15. **The index is per module.** Never write it at the repository root, and never let one module's table
    cover another's files: the paths are module-relative and would be meaningless elsewhere. A pass whose
    report directory is outside any `.jcodebuddy` (a test) writes `<report>/index/files.json`.
16. **`.jcodebuddy/index/README.md` is tracked and human-owned.** Create it when it is absent; never
    overwrite an existing one (that would silently revert a human's edit on every pass, which is exactly
    what DEC-020 forbids for generated blocks).
17. **A new `.jcodebuddy/` subfolder is a DEC-026 layout change, not just code.** It needs its README, its
    track policy in `.gitignore`, the layout table in `.jcodebuddy/README.md`, and the coupling note that
    an opt-in for `metadata/` must carry `index/` too. `IndexLayoutTest` keeps the directory honest.

---

## 9. Definition of done

1. **A source path is written once per module.** Every module-relative path lives in
   `.jcodebuddy/index/files.json`; no document, location, artifact or field value contains a source path
   or a `.java` suffix, and the only path-like value in a document is its `fileIndex` pointer. *Proved by*
   `MetadataFileIdsTest`.
2. **Ids are unique, deterministic and cross-document stable.** Two passes produce identical index bytes;
   the same file has the same id in every document that mentions it; the disambiguation rule is exercised
   by `entity.Person` / `iface.Person` / `record.Person`; every `file` value used anywhere resolves in the
   index. *Proved by* `MetadataFileIdsTest`.
3. **Every field of every view carries all its locations**, for the roles that exist for it, and none
   that do not (a `DERIVED` field has no `setter`). *Proved by* `MetadataLocationsTest`.
4. **Every location is true**: the file exists and the line contains the member (`annotation` →
   `@FieldSource`). *Proved by* the renderer's build-time check (a failure is `html_link_stale` and exits
   1) plus the bun test that re-checks every page link.
5. **The renderer discovers nothing from source.** With the index, `artifacts` and `fields` present, every
   page link maps back to a metadata location, and a missing index is a loud `html_index_missing`
   diagnostic rather than a page of dead links. *Proved by* the new bun tests.
6. **A legacy document still renders**, with no error-severity divergences. *Proved by* the extended
   "stripped metadata" bun test and a `fromJson` legacy case.
7. **Size**: the three documents stay under ~35 KB (baseline 26 KB; the same information with paths
   inlined per location would be ~55–60 KB), and the page's column set, labels and link targets are
   unchanged otherwise. *Proved by* a measured budget assertion plus a before/after diff of `index.html`.
8. **Nothing else regressed**: `scripts\mvn-jdk25.cmd` green (7/7), `bun test` green, `ExampleRegenerationTest`
   green (no generated `.java` changed), `GeneratorGuardTest` and `HtmlRenderBoundaryTest` green, no
   `.java` under any `.jcodebuddy/`, no source content anywhere, `git status` clean under
   `hipster-entity-example/src`.
9. **Documents updated**: DEC-028 (or the DEC-027 amendment) + a DEC-026 amendment for the `index/`
   subfolder + decisions-index row + heading range, the `AGENTS.md` line, the tooling README section,
   the renderer README, `codebuddy.md` § 5.1, `.jcodebuddy/README.md` (layout + track policy),
   `.jcodebuddy/.gitignore` (the opt-in carries the index), the metadata README and the module's
   `context/entity-html-index.md`.
10. **The index is the only place a source path is written**, it is written once per module per pass,
    every document resolves against it, its `format` is stated, and the reserved tables
    (`hashes.json`, `artifacts[].inputs`) are documented but **not** implemented. *Proved by*
    `MetadataFileIdsTest` + `IndexLayoutTest`, and by a report line saying the reserved work was left
    out on purpose.

---

## 10. Assumptions and open questions

Assumed (§ 2.7) unless overturned before § 4.1:

1. **Index location and layout**: `.jcodebuddy/index/`, one table per concern, `files.json` now,
   `hashes.json` + dependency edges later. Alternatives: a single `.jcodebuddy/index.json`, or a table
   under `metadata/`.
2. **Id scheme**: readable (simple name + shortest unique package suffix). Alternatives: content-independent
   short hash (most stable, opaque) or an integer index (smallest, renumbers on insertion).
3. **Location shape**: `at` = artifact id → role → line. Alternative: a flat
   `[{artifact, role, file, line}]` list (simpler to consume, ~40 % larger).
4. **`sourcePath` replaced** by `file` ids (with legacy *reading* retained). Alternative: keep both.
5. **A new DEC-028** plus a DEC-026 layout amendment, rather than further DEC-027 amendments.
6. **Foreign declaring interfaces** appear in `views[].artifacts[]` with `own: false`.
7. **`allFields` gets `file` but no location map** (the per-view maps are authoritative).
8. **The hash table is reserved, not built** (§ 2.3): this plan only makes room for it.

Open questions worth a decision, none blocking § 4.1:

* **When do the hashes land, and who owns the algorithm?** The plan reserves them; building them means
  deciding whether the tooling's hash function is shared with `MetadataCache` (project-automation, which
  the tooling must not depend on) or the watcher consumes the tooling's table. Record it in DEC-028 when
  it is built.
* **Do the reserved tables replace `metadata/watch/<toolSet>/metadata.db`?** Default: no — that is the
  watch agent's cache (DEC-026 § 3); the index is the generator's inspectable record. Consolidation is a
  later decision, taken with the watcher's owner.
* **Incremental mode's flag surface** (`--incremental`? a watcher-only mode?), and its safety rule: a
  table that is missing, version-mismatched, or whose option fingerprint differs must force a full pass.
  Out of scope here, but the plan's § 2.3.2 exists so that decision has somewhere to land.
* Should `views[].fields` include a field the metadata attributes to the view but the ledger does not
  carry (the `html_field_not_in_ledger` case)? Today the page shows it without an ordinal. Under this
  plan the field list comes from the ledger order, so such a field would still need the union treatment
  the page applies today — keep that behaviour and record it in DEC-028.
* Should adapter/binder positions be recorded when `--adapters` is on (they are per-view files, so the
  candidate list already covers them)? Default: include whatever exists; document the roles they
  contribute (likely `field`/`ordinal-slot`-like).
* Do you want the marker-level union as a flat list on `allFields` too (§ 2.6)? Default: no.

---

## 11. Deliverables checklist

**Generator / model**

- [ ] `meta/SourceLocation.java`, `meta/ArtifactMeta.java`, `meta/ViewFieldMeta.java` (new)
- [ ] `meta/ViewMeta.java`, `meta/Property.java`, `meta/EntityFieldMeta.java`, `meta/EntityMeta.java` (extended)
- [ ] `ModuleFileIndex.java` (the central index: ids, `files.json`, the directory's `README.md`),
      `MetadataLocations.java` (new, package `…tooling`)
- [ ] `EntityMetadataGenerator.java`: `parseProperty`, the view loop + write ordering
      (artifacts → index → documents), `collectEntityFields`, `toJson(entityMeta, index)`, `fromJson`

**Repository layout**

- [ ] `.jcodebuddy/index/README.md` (new, tracked — the directory's purpose and its reserved tables)
- [ ] `.jcodebuddy/.gitignore` + `.jcodebuddy/README.md`: the new subfolder, its default-ignored policy,
      and the opt-in snippet that must carry `index/` together with the metadata subtree

**Renderer**

- [ ] `scripts/entity-html/metadata.js`, `links.js` (index-driven discovery, verification kept,
      legacy fallback, `html_index_missing` diagnostic)

**Tests**

- [ ] `MetadataLocationsTest`, `MetadataFileIdsTest`, `IndexLayoutTest` (new); `MetadataSourcePathTest`
      (adapted); `HtmlRenderBoundaryTest` (one assertion); `scripts/entity-html/entity-html.test.js`
      (index resolution, mapping, legacy, budget)

**Docs**

- [ ] `DEC-028.md` + DEC-026 amendment + decisions index; `AGENTS.md`; `hipster-entity-tooling/README.md`;
      `scripts/entity-html/README.md`; `hipster-entity-example/codebuddy.md`;
      `.jcodebuddy/README.md`; `.jcodebuddy/metadata/entity/README.md`;
      `.jcodebuddy/context/entity-html-index.md`

**Evidence**

- [ ] gate output (7/7 green, new test count), `bun test` count, `gen.cmd` link-check line
- [ ] before/after `index.html` difference explained
- [ ] index + documents sizes against the § 0.4 baseline, and the reserved work reported as *not done*
