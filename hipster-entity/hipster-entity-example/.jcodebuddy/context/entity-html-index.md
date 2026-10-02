# Spec: the HTML entity index for this module

Module-scoped spec for the page `scripts/entity-html/` renders from this module's metadata
(DEC-027) and its class index (DEC-029). It records what *this module* expects the page to
contain, so a change to the renderer can be judged against something other than the renderer's own
tests.

## What the page must answer

For every entity the metadata declares — `Auditable`, `PaymentMethod`, `Person` — and for every view
of it:

1. **Which artifacts exist?** One entry per artifact, opening at its declaration:
   - the `@View` interface itself, and its nested types (a hand-written `Record` or `Write`, as in
     `PersonSummary`);
   - the generated field enum `<View>_`, and, where the view's `gen` level produced them, the
     record (`<View>Record` or the nested record), the builder (`<View>Builder`) and the tracking
     builder (`<View>BuilderTracking`), plus a validation/validator or adapter file when one exists.
2. **Where is each field in each of them?** One row per field, one column per (artifact, member
   role) pair that actually occurs, and a click on any cell opens that file at that line.

Both answers are now **read from the metadata, not inferred**: `views[].artifacts[]` is the artifact
list (with the foreign declaring interfaces the view's fields reference, marked `own: false`), and
`views[].fields[].at` holds every location as `artifact id → role → line`. The page resolves the
artifact's file — a fully qualified type name — through `.jcodebuddy/index/classes.json`, which is
the module's single statement of every source path. It still reads the committed source, but only to
**verify** that a link's line contains the member (DEC-027 § 4.2); discovery is gone. The roles are
the fixed eight: `accessor`, `annotation`, `enum-constant`, `name-slot`, `record-component`, `field`,
`setter`, `ordinal-slot`.

## What the page reads

Two inputs per module — the `<Marker>.metadata.json` documents and the class index they point at:

```jsonc
// .jcodebuddy/index/classes.json (excerpt) — every path is stated once, here
{ "format": 1, "module": "hipster-entity-example", "sourceRoot": "src/main/java",
  "hash": { "algo": "wyhash64", "normalize": "lf", "of": "content" },
  "classes": {
    "hr.hrg.hipster.entityexample.person.entity.PersonDetails": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonDetails.java",
      "kind": "interface", "modifiers": ["public"], "line": 18, "depth": 0 },
    "hr.hrg.hipster.entityexample.person.entity.Person": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java" },
    "hr.hrg.hipster.entityexample.example.Auditable": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/example/Auditable.java" } } }
```

```jsonc
// Person.metadata.json — the PersonDetails view (abridged; line numbers are this revision's)
{ "name": "PersonDetails", "lineNumber": 18,
  "file": "hr.hrg.hipster.entityexample.person.entity.PersonDetails",
  "extends": ["Person"], "addons": ["PersonAuditable"],
  "artifacts": [
    { "id": 0, "name": "PersonDetails",  "kind": "interface",
      "file": "hr.hrg.hipster.entityexample.person.entity.PersonDetails", "line": 19,
      "generated": false, "own": true },
    { "id": 1, "name": "PersonDetails_", "kind": "enum",
      "file": "hr.hrg.hipster.entityexample.person.entity.PersonDetails_", "line": 16,
      "generated": true,  "own": true, "header": "Field metadata for the PersonDetails view." },
    { "id": 2, "name": "Person",         "kind": "interface",
      "file": "hr.hrg.hipster.entityexample.person.entity.Person", "line": 14,
      "generated": false, "own": false },
    { "id": 3, "name": "Auditable",      "kind": "interface",
      "file": "hr.hrg.hipster.entityexample.example.Auditable", "line": 7,
      "generated": false, "own": false } ],
  "fields": [
    { "name": "firstName", "ordinal": 2, "fieldKind": "COLUMN",
      "at": { "1": { "enum-constant": 26, "name-slot": 91 }, "2": { "accessor": 15 } } },
    { "name": "createdAt", "ordinal": 6, "fieldKind": "COLUMN",
      "at": { "1": { "enum-constant": 58, "name-slot": 99 }, "3": { "accessor": 8 } } } ] }
```

The document's key set is unchanged: the root `markerFile`/`markerLine`, the `classIndex` pointer
(`"../../index/classes.json"`, the only path-like value a document contains), and
`views[].file`, `views[].properties[].file`, `allFields[].file` and `artifacts[].file` — but every
one of those values is now the fully qualified name of a type. A document written while DEC-028's
`files.json` existed — readable ids plus a `fileIndex` pointer — still renders through that legacy
table, and a document written before DEC-028 — plain `sourcePath` strings, no pointer, no
`artifacts`, no `fields` — still renders from the source scan. A document whose names cannot be
resolved is a loud `html_index_missing` error that renders nothing.

## Module-specific expectations

These are the facts the committed example is *for*, so the page is wrong if it hides them:

- **Every path the page links with is module-relative.** The class index states each class's and each
  field's declaring file relative to *this module's* root (`src/main/java/…`), never to the repository
  above it, and the documents name those types by FQNs that resolve there; the page resolves the
  resulting path against its own location. Opening the page must therefore work whether the IDE
  project is `hipster-entity-example` or the whole repository — and must never link outside the
  module that holds the class, because that is where this module's `.jcodebuddy/` output and its code
  live.

- **`PersonSummary` is the `BUILDER_ALL` view.** Its page must show both builders, and its `age` and
  `departmentName` must show a `DERIVED`/`JOINED` chip with the expression/relation from the
  metadata, an `annotation` cell at the `@FieldSource` line in `PersonSummary.java` (16) and an
  `accessor` cell at 17 in the same file — the declaration-start line and the name-token line are two
  different recorded facts — and **no setter cell** in either builder, because the generator emits
  setters for writable fields only. An empty setter cell is the correct rendering of that rule.
- **`PersonDetails` shows the addon path.** `createdAt`/`updatedAt` must link to `Auditable.java`
  (the addon source) and `firstName`/`lastName` must link to
  `hr.hrg.hipster.entityexample.person.entity.Person` — the interface that declares them — not to
  `PersonDetails` itself and not to the enum. In the metadata those are artifacts `3` and `2`, both
  `own: false`, and each field's `at` map names the one its accessor lives in.
- **`id` is inherited from outside the source root** (`Identifiable` in `hipster-entity-api`). It
  must appear with an `inherited` chip, an enum-constant link, and **no** accessor link: the page
  must not invent a source file for a classpath type.
- **The payment family is polymorphic.** Each `*PaymentMethod` view must show its own accessors and
  the ones it inherits from the hand-written sealed root `PaymentMethod`, with the inherited ones
  linking to `PaymentMethod.java`.
- **The ordinals are the ledger's.** `PersonDetails_` is `{id, firstName, lastName, email,
  phoneNumber, createdAt, updatedAt}` and the page must show those seven, in that order, with those
  ordinal numbers, including after an addon has appended fields (DEC-023's R1 rule).
- **`PersonUpdateForm_` and `PersonUpdatableView_` show all five fields, with no warning.** Their
  enums declare `id`, `email`, `phoneNumber`, `firstName`, `lastName`. Until DEC-028 the renderer
  found a constant with a line regex that did not match the way the generator writes one
  (`, email(java.lang.String.class) {` — the comma on the same line), so it read both views as
  carrying only `id`, showed the other four rows without an ordinal, and reported a false
  `html_field_not_in_ledger` warning for each. The page must now show the five rows with ordinals
  1–5 and report **no** warning. The warning kind still exists for a genuine ledger/metadata
  disagreement — the renderer's own test injects one — it simply has nothing to report here.
- **The page renders 305 of the 315 locations this module's metadata records.** The ten it does not
  are a foreign declaring interface's `accessor` for a field whose view has no row in the
  marker-level `allFields` union: `PersonAuditable.firstName`/`.lastName`, `PersonAuditable`'s and
  `PaymentMethodAuditable`'s `createdAt`/`.updatedAt`, and `type` on each of the four
  `*PaymentMethod` views. The cause is pre-existing and untouched: `allFields` is built with the
  **marker's own package** as lookup key, so a view declared outside the marker's package (the
  `Auditable` marker lives in `example/`, its views in `person/entity/` and `paymentMethod/entity/`)
  contributes only `id`, and the page's "declared in" label and view-aspect accessor cell derive from
  those declaration keys. Consuming the recorded foreign accessor instead would **add an `accessor`
  column to those six views**, and DEC-028 freezes the page's column set, labels and link targets.
  The page renders what its columns cover; the metadata is a strict superset.

## Boundaries

- The page is derived output in `metadata/entity/index.html` and is **not** committed: this module's
  tracked `.jcodebuddy/` material is this spec, `context/README.md`, `index/README.md` and the
  reports. A reviewer regenerates the page (`scripts\entity-html.cmd`) rather than reading it in a
  diff.
- The page never becomes the source of truth for anything. If it disagrees with the committed
  sources, the sources win and the renderer's link check is what says so.
- Rendering must not require Maven, a JDK, or a network: `bun run` and the files in the tree are the
  whole requirement.
