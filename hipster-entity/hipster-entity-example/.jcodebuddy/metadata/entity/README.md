# `metadata/entity/` — entity metadata reports

One `<Entity>.metadata.json` per entity and view, written here by
`EntityMetadataGenerator` — by every generation pass: `scripts\gen.cmd` (manual or
watched), this module's explicit, phase-less `exec:java@hipster-entity-generate`
goal, or `EntityRegenerationWatcher` under a watched run. No Maven build writes it;
nothing binds the generator to a lifecycle phase.

`generation.json` is the last pass's run record (`--run-record`): which generator
revision ran, the artifact its classes came from, the roots it resolved, the flags
it ran with, and the validation and divergence counts. Every generation pass rewrites
it, and no build does. It is machine state rather than a document to read by hand —
but it answers "what generated this tree?", which no build log keeps.

Each `<Marker>.metadata.json` also says where the classes it describes are declared — but it no
longer carries a path, and no longer a short id either (DEC-029). The module's **class index**,
`../../index/classes.json`, states every module-relative source path **once**, and a document names a
type by its **fully qualified name**, which resolves there; the root `classIndex` key points at that
table (`"../../index/classes.json"`, the only path-like value a document contains):

```jsonc
// .jcodebuddy/index/classes.json
{ "format": 1, "module": "hipster-entity-example", "sourceRoot": "src/main/java",
  "hash": { "algo": "wyhash64", "normalize": "lf", "of": "content" },
  "classes": {
    "hr.hrg.hipster.entityexample.person.entity.PersonSummary": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
      "kind": "interface", "modifiers": ["abstract", "public"], "line": 14, "depth": 0,
      "size": 1837, "checksum": "3f2c8d91a4b7e601",
      "hashCalculatedAt": "2026-05-14T09:12:33Z" } } }
```

A key is the type's fully qualified name — package-qualified, member types joined with `.`
(`…PersonSummary.Record` has a row of its own) — so the same type has the same name in every document
and a rename an IDE performs reaches the reference in these text files. A document uses FQNs in
`markerFile` (with `markerLine`), `views[].file`, `properties[].file`, `allFields[].file` and
`artifacts[].file`. A field's `file` is its *declaring* accessor's type, so an inherited field names
another type (`PersonDetails.firstName` → `…person.entity.Person`) and a field from outside the
source root (`id`) carries `null` rather than a guess — it has no row in this module's class index.
Every path is **relative to this module's root** (`src/main/java/…`), never to the project above it
and never absolute.

Each view also carries the **artifact inventory** and its fields' locations:

```jsonc
{ "name": "PersonSummary", "lineNumber": 13,
  "file": "hr.hrg.hipster.entityexample.person.entity.PersonSummary",
  "artifacts": [
    { "id": 0, "name": "PersonSummary", "kind": "interface",
      "file": "hr.hrg.hipster.entityexample.person.entity.PersonSummary", "line": 14,
      "generated": false, "own": true },
    { "id": 3, "name": "PersonSummary_", "kind": "enum",
      "file": "hr.hrg.hipster.entityexample.person.entity.PersonSummary_", "line": 17,
      "generated": true, "own": true, "header": "Field metadata for the PersonSummary view." },
    { "id": 6, "name": "Person", "kind": "interface",
      "file": "hr.hrg.hipster.entityexample.person.entity.Person", "line": 14,
      "generated": false, "own": false } ],
  "fields": [
    { "name": "age", "ordinal": 4, "fieldKind": "DERIVED",
      "expression": "YEAR(NOW()) - YEAR(birthDate)",
      "at": { "0": { "accessor": 17, "annotation": 16 },
              "3": { "enum-constant": 43, "name-slot": 98 },
              "4": { "accessor": 48, "field": 18, "ordinal-slot": 66 } } } ] }
```

`artifacts[]` lists the files that belong to a view — its own file and the nested types it declares,
the generated siblings, then the foreign declaring interfaces its fields reference, marked
`"own": false`. `fields[].at` is `artifact id → role → line` over the eight roles `accessor`,
`annotation`, `enum-constant`, `name-slot`, `record-component`, `field`, `setter` and
`ordinal-slot`; a role that does not exist is **absent** (a `DERIVED` field has no `setter`
anywhere). `properties[].lineNumber` stays the declaration start, annotations included (16 for
`PersonSummary.age`), which is a different fact from the `accessor` role's name-token line (17).
`allFields` carries `file` but deliberately no location map: it is a per-marker union, and the
per-view maps are authoritative.

What is recorded is a **name, not the file**: neither these JSON documents, the index beside them,
nor `index.html` quotes a Java file's text. A report that carried content would be a second, stale
copy of the tree, and the file is one click away instead. Nothing here is generated source — a
`.java` under `.jcodebuddy/` is always a mistake (DEC-026 § 5), and `GeneratorGuardTest` asserts the
tree stays clear of one.

Both are ignored by default, and `index/` must be opted in **together with** this subtree — a
document's FQNs resolve only there — see `../README.md` for the opt-in rule that makes them
tracked.

`index.html` is the same model rendered for a human (DEC-027): one page listing every entity, every
artifact generated from it, and every field clickable through to the exact source line of every
artifact. It is written here by `scripts\entity-html\` — Bun JavaScript reading the JSON above, never
by a Java generator — as the last step of `scripts\gen.cmd`, and it opens in the IDE's WebView
Explorer tool window (right-click the file, "Open in WebView Explorer"). It is derived output like
the JSON beside it: regenerable, ignored by git, and never a source of truth.
