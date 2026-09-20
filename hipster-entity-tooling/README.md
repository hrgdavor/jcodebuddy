# hipster-entity-tooling

The **generator and validator** module for
[`hipster-entity`](../doc-hipster-entity/README.md).

It reads hand-written view interfaces with JavaParser and writes the
companion boilerplate back into the source tree: the field enum, the
record, the `Write` interface, the builders, the tracking builder and the
`ViewMeta`. SQL materialization — a generated `<View>RowAdapter` /
`<View>Binder` pair — exists as a **draft/exploration behind the explicit
`--adapters` flag** (opt-in, never a default; see the status note below).
It also hosts the R1 ordinal-ledger checker.

It is a **dev-time** module. Per the Dev-Time Only Guarantee in the root
[`README.md`](../README.md) and
[DEC-W003](../doc/architecture/decisions-watch/DEC-W003.md), it must not
become a transitive dependency of a runtime module — generated code must
compile against `hipster-entity-api` / `hipster-entity-core` alone.

## What the generator emits

For a view at `@View(gen = GenLevel.<level>)`, the generator emits the
members implied by the cumulative
[`GenLevel`](../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/GenLevel.java)
ladder (`DEFAULT < META < RECORD < WRITABLE < BUILDER < BUILDER_TRACKED
< BUILDER_ALL`):

| Output | Emitted by | Generator |
|---|---|---|
| `<View>_` field enum (implements `FieldDef`, with `ViewMeta`, `forName`, `NAME_MAPPER`) | `META` and above | `FieldBoilerplateGenerator` |
| `<View>Record` / the nested record | `RECORD` and above | `ViewRecordGenerator` |
| the nested `Write` interface | `WRITABLE` and above | — (declared in the view) |
| `<View>Builder` | `BUILDER` and above | `ViewBuilderGenerator` |
| `<View>BuilderTracking` | `BUILDER_TRACKED` / `BUILDER_ALL` | `ViewTrackingBuilderGenerator` |
| `<View>RowAdapter` + `<View>Binder` — **DRAFT / EXPLORATION, opt-in** | `--adapters` only | `ViewAdapterGenerator` |

Adapters give you
`<View>RowAdapter.fromResultSet(ResultSet, ViewMeta)` and
`<View>Binder.bind(...)` / `bindChanged(...)` / `insertSql(String)` /
`updateSql(String, boolean[])`, plus the `COLUMNS`, `ORDINALS` and
`parameterCount()` tables. A retired (`retired() == true`) field is
skipped by every writer.

> **Status: draft / exploration — and strictly opt-in.** SQL generation is
> not a supported generator. It is switched on by `--adapters` and by
> nothing else: the default pass emits none of it, no POM property or
> profile can enable it implicitly, the example project does not enable it
> (so no committed example depends on the emitted shape), and the emitted
> API may change or be withdrawn.
> `ViewAdapterGeneratorTest#sqlGenerationIsOptInAndOffByDefault` pins the
> rule. **Any future SQL support must keep that shape — an explicit
> request, never a default.** The hand-written adapter *pattern* for
> reading a `ResultSet` into a view is documented independently in
> [`user/patterns/jdbc-row-adapter.md`](../doc-hipster-entity/user/patterns/jdbc-row-adapter.md);
> that guidance stands on its own and needs no generator.

`GenLevel.DEFAULT` is resolved by
[`GenLevelResolver`](src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java),
the single owner of the rule, called by both the validator and the
generator: a matching nested `record` → `RECORD`, else a nested `Write`
interface → `BUILDER`, else `META`. It never resolves to
`BUILDER_TRACKED`/`BUILDER_ALL`.

### The class index, and a field's locations

`<Marker>.metadata.json` no longer names a source file by its path *or* by an id. Each **module**
writes one **class index** per pass, at `<module>/.jcodebuddy/index/classes.json`
([DEC-029](../doc-hipster-entity/architecture/decisions/DEC-029.md) — superseding the addressing half
of [DEC-028](../doc-hipster-entity/architecture/decisions/DEC-028.md)), beside a derived
`mtimes.json` sidecar:

```jsonc
{
  "format": 1,
  "module": "hipster-entity-example",
  "sourceRoot": "src/main/java",
  "hash": { "algo": "wyhash64", "normalize": "lf", "of": "content" },
  "classes": {
    "…person.entity.PersonSummary": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
      "kind": "interface", "modifiers": ["abstract", "public"], "line": 14, "depth": 0,
      "size": 1837, "checksum": "3f2c8d91a4b7e601",
      "hashCalculatedAt": "2026-05-14T09:12:33Z" },
    "…person.entity.PersonSummary.Record": {
      "path": "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
      "kind": "record", "modifiers": ["public"], "line": 26, "depth": 1,
      "enclosing": "…person.entity.PersonSummary", "generated": 1,
      "size": 1837, "checksum": "3f2c8d91a4b7e601",
      "hashCalculatedAt": "2026-05-14T09:12:33Z" } } }
```

One row per **type** the module compiles — a hand-written source file and a generated artifact alike
— **keyed by the type's fully qualified name**, member types joined with `.` so
`…PersonSummary.Record` has a row of its own. A row carries `path` (module-relative, forward slashes,
never absolute and never `..`), `kind` (`class` / `interface` / `enum` / `record` / `annotation`,
from the tooling's single kind resolver), `modifiers` (the declaration's Java modifier keywords,
**sorted**, so a reordered modifier list is not a diff), `line` (the declaration's start line,
1-based), `depth` and `enclosing` (a member type's nesting; `0`, and no `enclosing`, for a top-level
type), `generated` (`1` when the pass wrote the file — it carries a DEC-021 header — omitted
otherwise), `size`, `checksum` and `hashCalculatedAt`. A file that declares no type
(`package-info.java`) contributes no row, and two paths claiming one FQN fail the pass rather than
letting an iteration order pick a winner.

**An id is a fact about the writing pass; an FQN is a fact about the code.** A hash, a counter or a
positional index changes when a file moves, when a row is inserted or when the id scheme changes, and
it means nothing to a tool that cannot run our writer. A fully qualified name is the one reference a
Java IDE's rename refactor updates everywhere it appears — **including in text files that are not
Java**. The cost is stated honestly: an FQN is longer than a short id, and **a package or type rename
changes the key**, so a document's reference is stale until the next pass rewrites it. The pass
reports that as a removed row plus an added row, and the document it rewrites carries the new name;
`changedSince` also reports a retained FQN whose `path` moved (`renamed`).

**Measured, not estimated: this is not a size optimisation.** `files.json` was 4 301 bytes;
`classes.json` is **14 615 bytes for 42 rows**, and the three metadata documents grew from 50 993 to
56 097 bytes, because an FQN is longer than the short id it replaced. What the growth buys is one
addressing scheme (the language's own), content identity, and the class facts every generator
otherwise re-derives by walking and parsing.

`checksum` is 16 hex characters: `Wyhash64` over the file's bytes with CRLF normalised to LF first —
the same algorithm *and* the same normalisation the watch agent's own tables use, so the two agree
about what "the same content" means. The normalisation is not cosmetic: without it a CRLF checkout
and an LF checkout of the same content would disagree, and the table would describe the developer's
git configuration rather than the code. The `hash` header names both (`algo`, `normalize`, `of`), and
a table whose header this build does not recognise must force a full pass, never be half-believed.

`hashCalculatedAt` is the ISO-8601 UTC instant that checksum was **calculated**, at second precision.
It changes **if and only if** the row's `checksum` changes (or the row is new): the pass carries the
previous instant forward on unchanged content, which is what makes the table byte-identical across
two passes over an unchanged tree, and therefore committable and reviewable in a diff. The file's
last-modified time is deliberately **not** a column — an `mtime` belongs to a working tree and cannot
survive a checkout — so it lives in `mtimes.json` beside the table, where a watcher may compare it
against the filesystem as a cheap pre-filter before hashing anything, and where nothing may treat it
as a correctness input.

**The reader API** is on
[`ClassIndex`](src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java), and it is read
methods on the type that also writes, so there is one key implementation and no second class:
`row(fqn)` (the row a name resolves to), `byPath(path)` (every row a file declares),
`fqnForPath(path)` (the FQN a document should use for a file — its primary type; a file with no type
is a caller bug and fails loudly), `changedSince(previous)` with its one-line `summarize`, and the
static `read(indexFile, reportDir, moduleRoot, sourceRoot)`, which refuses a table whose `format` or
`hash` contract this build does not recognise. `ClassIndex.pathsByFqn(indexFile)` is the
`FQN → module-relative path` map a document's references resolve through; `legacyPathsById(filesJson)`
is the DEC-028 table's counterpart for the one-revision legacy read. After writing, the pass prints
`[index] N type(s) in classes.json — a added, c content change(s), r removed, n renamed`.

Paths are **module-relative** (`src/main/java/…`, forward slashes), never absolute and never `..`,
and **a path is written exactly once per module — only here**. Every document points at the table
through a `classIndex` pointer (e.g. `"../../index/classes.json"`), the only path-like value a
document contains, and every file reference in a document is an **FQN**: `markerFile` (with
`markerLine`), `views[].file`, `properties[].file`, `allFields[].file` and `artifacts[].file`. The
index lives under the nearest `.jcodebuddy/` above the report directory — the same walk-up the module
root uses (DEC-026 § 2) — and falls back to `<report dir>/index/classes.json` when the report
directory is not inside a `.jcodebuddy/` at all (a temp directory in a test); the fallback is not a
module layout, so it gets the table and no README. `format` is the table's version: a consumer that
does not recognise it must refuse the table rather than guess. A pass writes `index/README.md` when it
is absent and **never overwrites** it, because that file is tracked, i.e. owned by a human.

The Java model keeps **paths**. `EntityMeta.markerSourcePath`, `ViewMeta.sourcePath` and
`Property.sourcePath` keep their meaning for every Java caller, and `ArtifactMeta.file` is a path in
the model and an FQN in the JSON; `toJson`/`fromJson` are the single conversion point, because the
document carries the language's own name while the model keeps the path. `fromJson(json)` without a
table leaves references unresolved (`null`) rather than inventing a path, while
`fromJson(json, fqnToPath)` resolves them; both accept the pre-DEC-028 `markerSourcePath`/`sourcePath`
keys, so an older document still parses. **Legacy reading lasts one revision**: a document carrying
the retired `fileIndex` pointer and DEC-028 readable ids still parses, resolving through `files.json`
when that table is present. The reader is tolerant; nothing emits two spellings.

**The artifact inventory** (`views[].artifacts[]`) lists the types that belong to a view:
`{ id, name, kind, file, line, generated, own, header? }`. `id` is a small integer assigned in
reading order — the view's own file, the nested types it declares, the generated siblings in
emission order, then the **foreign declaring interfaces** its fields reference, marked
`"own": false`. `generated` says whether the file carries a DEC-021 header (i.e. the generator owns
it), `header` is that header's description text, and `line` is the type's declaration line inside its
file. An artifact whose `--java-out` is outside the module has no module-relative path — and so no
FQN to name it by: it is reported as an `artifact_outside_module` divergence and is absent from the
inventory.

**A field's locations** (`views[].fields[]`) are `{ name, ordinal, type, fieldKind, column?,
relation?, expression?, at }`, where `at` is `{ "<artifact id>": { "<role>": line } }` — for
`PersonSummary.age`:

```json
"at": { "0": { "accessor": 17, "annotation": 16 },
        "1": { "record-component": 30 },
        "3": { "enum-constant": 43, "name-slot": 98 },
        "4": { "accessor": 48, "field": 18, "ordinal-slot": 66 },
        "5": { "accessor": 49, "field": 32, "ordinal-slot": 58 } }
```

The eight roles, written in this order so two runs are byte-identical, are `accessor` (a no-argument
read method), `annotation` (the `@FieldSource` line), `enum-constant` (the constant in `<View>_`,
DEC-023's ledger position), `name-slot` (the `forName` arm), `record-component`, `field` (the stored
field in a builder), `setter` (the fluent setter, or a `Write` method in a nested interface) and
`ordinal-slot` (the `case 3 ->` arm). A role that does not exist for a field is **absent**, not
empty — a `DERIVED` field has no `setter`. The view's `properties[]` stays the "own declaration"
record (its `lineNumber` is the declaration start, annotations included, which is a different fact
from the accessor role's name-token line) and does not gain a location map; `allFields` carries
`file` but deliberately **no** location map either, because it is a per-marker union.

The reserved `hashes.json` is **cashed in, not still outstanding**: it was reserved by
`plan.metadata-locations.md` § 2.3.2 and now lands as the row's `checksum`/`hashCalculatedAt`/`size`
columns plus the `mtimes.json` sidecar, rather than as a second table.

**Reserved, not implemented.** The directory is a directory of tables on purpose, so these can be
added without changing any consumer's contract: the `generator` header value (the tooling revision
and the flags that change output — defined, not written until something consumes it, following the
same "options are part of the fingerprint" rule the `hash` header already follows), the dependency
edges (`artifacts[].inputs` — per emitted artifact, the FQNs it was generated *from*), and a pointer
in a generated artifact's DEC-021 header naming the class index row it came from. None is written
today; they are what a watcher or an incremental pass would need, and
`metadata/watch/<toolSet>/metadata.db` (DEC-026 § 3) remains the watch agent's own cache rather than
being replaced by the index.

The metadata's consumers are the Bun HTML entity reference page (DEC-027,
[`scripts/entity-html/`](../scripts/entity-html/README.md)) and any other tool that needs to open a
class the generator described. The example records **315 locations** across 84 fields and 45
artifacts; the page renders 305 of them, because ten are a foreign declaring interface's accessor
for a field whose view has no row in the marker-level `allFields` union, and DEC-028 freezes the
page's column set rather than widening it. What is recorded is a **name, never content**: no
metadata document or report ever contains a source file's text, a report directory holds metadata
only, and a `.java` under `.jcodebuddy/` is a mistake whatever wrote it (DEC-026 § 5).
`GeneratorGuardTest` asserts both (a pass leaves only JSON in the report directory; no
`.jcodebuddy/` tree in this repository holds a `.java`), and `.jcodebuddy/.gitignore` keeps such a
file ignored even after a project opts its metadata subtree in as a contract.

## Entry points

[`EntityMetadataGenerator`](src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java)
is the entry point (and the shaded jar's `Main-Class`):

```java
public static void generate(Path sourceRoot, Path outputDir) throws IOException;
public static void generate(Path sourceRoot, Path outputDir, Path javaOutputRoot) throws IOException;
public static void generate(Path sourceRoot, Path outputDir, Path javaOutputRoot, DivergenceReporter divergences) throws IOException;

public static void setGenerationPackages(Collection<String> packages);

/** Draft/exploration, opt-in only: see the adapter status note above. */
public static void setGenerateAdapters(boolean enabled);
public static boolean isGenerateAdapters();

/** The divergences produced by the most recent generate(...) pass. */
public static List<String> lastDivergences();
```

The three-argument form writes generated Java **into the source tree**
(`javaOutputRoot`), which is how the committed `src/main/java` output is
kept reproducible.

## CLI

```text
java -jar hipster-entity-tooling.jar <source-root|java-source-file> <output-dir> [--packages a.b,c.d] [--adapters]
                                    [--java-out <dir>] [--mapper <Src>:<Tgt>[:<ClassName>]] [--validate[=MODE]]
                                    [--run-record <file>] [--version]
```

| Flag | Meaning |
|---|---|
| *(positional 1)* | the source root, or a single `.java` file (the tool then searches upward for `src/main/java` or `src/test/java`) |
| *(positional 2)* | the output directory for the metadata JSON, one `<Marker>.metadata.json` per entity. It **must not be under a `.jcodebuddy/` directory** when generated Java would land there — see the layout guard below |
| `--packages a.b,c.d` | restrict **generation** to these packages. It does **not** restrict indexing: every source file under the root is still parsed, so cross-package supertypes and addons stay resolvable. Omitting the flag generates everything (the historical behaviour) |
| `--adapters` | **[DRAFT/EXPLORATION, opt-in]** also emit `<View>RowAdapter` / `<View>Binder` next to each view. Off unless given; no other flag, property or profile enables it |
| `--java-out <dir>` | write generated `.java` there instead of into the positional output directory. A pass passes it so committed source is regenerated **in place** while the metadata JSON stays in `.jcodebuddy/metadata/entity` — `scripts\gen.cmd`, the module POM's explicit `exec:java` goal, and a hand run all do |
| `--mapper <Src>:<Tgt>[:<ClassName>]` | also emit a statically-dispatched mapper between two **views**. Repeatable. Defaults: class `<Src>To<Tgt>Mapper`, method `to<Tgt>` |
| `--validate[=OFF\|REPORT\|STRICT]` | run the entity rules over the source root **before** writing anything. Bare `--validate` means `REPORT`: print every issue and continue. `STRICT` refuses to write until they are fixed, so a violating tree is never half-regenerated. `OFF` is the default for a library caller, so introducing validation cannot change an unrelated build. Warnings (the R1 `allowReorder` escape hatch) do not fail a pass unless `STRICT` |
| `--run-record <file>` | also write what this pass ran with — generator revision, the artifact its classes came from, resolved roots, flags, validation count, divergences, and `status` (`ok` / `failed`) — as JSON. Opt-in, so a library caller and the existing tests are unaffected. The example's pass — `scripts\gen.cmd` — writes `.jcodebuddy/metadata/entity/generation.json` |
| `--version` | print the generator identity (name, revision, and the artifact the classes came from) plus its flag surface, then stop. This is the first thing to run when a pass appears to have mis-generated a tree |

Flags may appear anywhere after the two positionals, and `--packages=a.b`
is accepted as well as `--packages a.b`. That matters because **every
invocation shares one flag surface** (§ 8.8/3.23): `scripts\gen.cmd`, the
module POM's goal-only `exec:java` executions, the watcher and a hand run
all pass the same CLI arguments, never `-D` system properties.

### The layout guard, and the stale-tooling trap it closes

Generated `.java` is **refused** when it would be written under a `.jcodebuddy/`
directory: that path is a module's metadata root (entity JSON for tooling
consumers, indexes, caches), never a source tree — DEC-026 and `AGENTS.md` § 2.
The guard fires before the first write, at every entry point.

In practice it fires for one specific mistake: **the classpath points at an
older tooling than the pass assumes.** The generator runs as a side tool with no
lifecycle binding, so a pass assembles its own classpath — `scripts\gen.cmd`
does, the module POM's goal-only `exec:java` executions do (they resolve the
`provided` tooling dependency from the local repository), and a reader typing
`java -cp` does. An outdated artifact does not know `--java-out`, treats it and
`--packages` as positional arguments, and writes generated Java into positional
2, i.e. the metadata directory, while reporting success.

Two guards close that, and they are complementary:

- [`GeneratorPreflight`](src/main/java/hr/hrg/hipster/entity/tooling/GeneratorPreflight.java)
  is a class that exists **only in a current tooling build**. It is a
  stand-alone check that the tooling on the classpath is new enough to
  understand the flags a pass passes; `scripts\gen.cmd` runs it first before
  every pass, and the module POM also exposes it as the
  `hipster-entity-preflight` exec goal. An outdated artifact fails the check on
  the missing class, before anything is written. A flag could not do this job:
  an old artifact does not know it and swallows it as another positional
  argument.
- `EntityMetadataGenerator.rejectJavaOutputUnderJcodebuddy` refuses the write at
  the generator end, which also covers a hand run and
  `EntityRegenerationWatcher`.

A pass needs **no `mvn install`** and builds **no jar**. `scripts\gen.cmd`
compiles the tooling in the reactor, exports a classpath with
`dependency:build-classpath` (which maps a reactor dependency to that module's
`target/classes` directory), and runs a plain `java -cp`.

`hipster-entity-example/codebuddy.md` § 6.1 is the full account, and
`scripts/gen.cmd` is the one-command regeneration path that always works.

### Entity rules (`validate`)

```text
java -jar hipster-entity-tooling.jar validate [<source-root>] [--strict]
```

Four rules plus the R1 ledger rule, registered literally in `EntityRulesValidator` (no discovery
mechanism — the list is an array, so an IDE's find-usages shows exactly what runs):

| Rule | Reports |
|---|---|
| `MarkerEntityRule` | a marker named `*Entity` that extends `EntityBase` and **declares an accessor** — that accessor would appear in every view's field list |
| `ViewInterfaceRule` | `view_does_not_derive_from_marker` (an interface named like a view that reaches no marker, so the generator silently emits nothing) and `view_name_convention` (a view whose name ends in none of `Summary`/`Details`/`Update`/`Form`/`Dto`) |
| `ViewAnnotationRule` | `@View` on a non-interface, `addon_on_non_view`, unknown `gen` levels, a builder level with no accessor |
| `AuditableRule` | an `Auditable` interface outside an entity module or package |
| `EntityFieldEnumOrderRule` | `empty_field_enum`, an undecodable DEC-021 header, and the R1 `allowReorder` warning |

Exit codes: **0** clean (or warnings only), **1** a violation, **2** usage error. `--strict` promotes
the `allowReorder` warning to a failure, as R1.3 requires.

The rules need the **whole source set**, not one file at a time: "is this interface a view?" is a
question about inheritance. That is why `EntityRule` has a second entry point (`validateAll`) and why
pointing the validator at a *module root* rather than a source root reports other modules' test
fixtures as defects — pass `src/main/java` (or run it through a generation pass, which derives the
same root).

When the first positional is a `.java` file, generated Java boilerplate
is written back into the source tree using the underscore-suffix
convention (`PersonSummary` → `PersonSummary_`).

### Generated mappers

A mapper is requested, not derived: there is no way to know which of the
n×n−1 view pairs a project wants, and generating all of them would be
noise. `--mapper PersonSummary:PersonDto` emits

```java
public final class PersonSummaryToPersonDtoMapper {
    private PersonSummaryToPersonDtoMapper() { }

    public static PersonDto toPersonDto(PersonSummary src) {
        return new PersonDto.Record(src.id(), src.firstName(),
                src.age() == null ? null : src.age().longValue(), null);
    }
}
```

into the **target** view's package, so the return type and the record it
constructs need no import. The source is named fully-qualified when it
lives elsewhere.

Type policy — map what is provably safe, report the rest:

| Source → target | Emitted |
|---|---|
| identical erased type | `src.x()` |
| anything → `Object` | `src.x()` |
| `Integer`/`Short`/`Byte` → `Long` | `src.x() == null ? null : src.x().longValue()` |
| `Integer`/`Long`/`Float`/`Short`/`Byte` → `Double` | `… doubleValue()` |
| `Short`/`Byte` → `Integer` | `… intValue()` |
| a primitive widening (`int` → `Long`, `long` → `Double`, …) | `src.x()` |
| **anything else** | a literal `null` plus a `mapper_type_incompatible` divergence |

A narrowing (`Long` → `Integer`) is refused even though a cast would
compile, because it truncates on overflow; a nullable source into a
primitive target is refused because it would be an NPE the compiler
cannot flag. Fields missing on either side are reported in both
directions (`mapper_field_missing_in_source`, `mapper_field_missing_in_target`),
because a silent omission in a mapper is a data-loss bug no compiler
catches. A request naming a view that this pass did not emit is reported
as `mapper_view_not_found` rather than half-generated.

### Generated Bean Validation

The **source of truth is the constraint annotation on the view accessor
itself** — not a second annotation style, and never duplicated into
`@FieldSource`. The generator recognises these by simple name, reads
their arguments as written, and carries them to the generated record's
components and the builders' fields:

`NotNull`, `Null`, `NotEmpty`, `NotBlank`, `Size`, `Min`, `Max`,
`DecimalMin`, `DecimalMax`, `Digits`, `Positive`, `PositiveOrZero`,
`Negative`, `NegativeOrZero`, `Pattern`, `Email`, `Past`, `PastOrPresent`,
`Future`, `FutureOrPresent`, `AssertTrue`, `AssertFalse`, and
`jakarta.validation.Valid`.

Applicability follows Bean Validation's own rules, because an
inapplicable constraint would throw at validation time rather than check
anything: `@Size` needs a `CharSequence`/`Collection`/`Map`/array,
`@Pattern` and `@Email` need a `CharSequence`, the numeric constraints
need a numeric type or a `CharSequence`, the temporal ones need a
`java.time` (or legacy date) type, and `@AssertTrue`/`@AssertFalse` need a
`boolean`. A constraint that does not apply produces
`validation_constraint_type_mismatch` and is **not** emitted. A
`jakarta.validation` annotation outside the list above produces
`validation_constraint_unsupported`.

Two limits worth knowing:

- **An unqualified custom constraint is invisible to the generator.** It
  recognises the names above plus anything written fully-qualified under
  `jakarta.validation`/`javax.validation`; it cannot tell that an
  unfamiliar simple name is a constraint, and guessing would be worse
  than leaving it alone. A custom constraint therefore needs its
  fully-qualified form if you want it reported.
- **The dependency is `provided`.** The tooling declares
  `jakarta.validation:jakarta.validation-api:3.0.2` as `provided` and a
  consuming project adds it for itself — a library that only uses the
  entities never gains a validation dependency. Generated sources that
  carry constraints need it on their compile path.

A `<View>Validator` with a `public static List<String> validate(View)`
body and explicit messages is emitted alongside, for callers who want
messages without a provider. It is **not** a fallback for constraints the
annotations could not express — those are reported instead — and it says
in its own javadoc which constraints it left to the provider.

### The `enum-order` subcommand

The R1 order checker is reachable from the same entry point as a
subcommand:

```text
java -jar hipster-entity-tooling.jar enum-order --repo <path> --baseline <git-ref> [--target <ref>] [--strict]
```

Its contract — the comparison, the exit codes, the escape hatch — is in
[§ R1 order contract](#r1-order-contract) below and in
[DEC-023](../doc-hipster-entity/architecture/decisions/DEC-023.md).

### The `enum-compact` subcommand

The one operation that deliberately shortens a ledger. It is a
subcommand rather than a flag so it can never be reached by adding an
argument to a normal run:

```text
java -jar hipster-entity-tooling.jar enum-compact --repo <path> --allow-reorder \
        --acknowledge-drained-data [--target <path-substring>]
```

It drops every R1.4 tombstone constant and renumbers the survivors
densely. It **refuses** — exit `2`, nothing written — unless both
acknowledgements are present: `--allow-reorder` is a statement about the
schema, `--acknowledge-drained-data` is a statement about the data, and
neither implies the other. It drops a constant only when it is both
`@Deprecated` **and** declares `retired()` returning `true`, refuses a
file it cannot parse, and verifies that the result is a subsequence in
order of the original before writing. Every dropped constant and every
ordinal that moved is printed: that report is the migration record.

The end-to-end procedure is in
[Field-enum compaction](../doc-hipster-entity/user/patterns/field-enum-compaction.md)
and the decision is
[DEC-025](../doc-hipster-entity/architecture/decisions/DEC-025.md).

## The module's role in `project-automation`

`hipster-entity-tooling` is the **library**; it decides nothing about
*when* or *whether* to generate. That policy belongs to the
[`project-automation`](../project-automation/) module, the project's
dev-time orchestrator, which declares this module as a dependency and is
the place a project wires the generator into its file watcher or, on top of
that, into an IDE sidecar.

The split is deliberate:

- **`hipster-entity-tooling`** — parsers, rules, emitters, and the CLI.
  Standalone; runs on demand; safe to invoke from a build.
- **`project-automation`** — the project-specific automation layer that
  calls it: on demand, live while watching, or through the sidecar. It is
  never packaged into the application artifact.

Of those trigger modes only the **pass** is required — running the generator when
you ask is the whole tool. **Watch mode** (the batched file watcher) is the same
pass driven continuously, and is what the normal development loop uses. A
**sidecar / LSP** is a user-friendliness expansion that sits on top of watch mode
(in-editor diagnostics, code actions, divergence warnings); it consumes what the
watcher already produces and is not a prerequisite for anything above.

A runtime module therefore depends only on the *generated* source plus
`hipster-entity-api` / `hipster-entity-core`, never on this module.

## Naming contract

The generator derives several names from the view's own simple name. Per
[DEC-022](../doc-hipster-entity/architecture/decisions/DEC-022.md) and
`AGENTS.md` § 1, every derived name is **refactor-sensitive**: it must be
reachable from the view through a navigable Java reference, so a standard
IDE rename of the view offers to rename it too. The relationship is
supplied by the DEC-021 class-file header on every generated class
(`// {@link <view-fqn>} …`), which is the `{@link}` that makes the
generated declaration findable from the view.

### Refactor-sensitive: derived from `<View>`

| Emitted name | Derivation | IDE contract |
|---|---|---|
| `View_` (the field enum) | `<View>` + `_` | DEC-021 header `{@link <view-fqn>}`; the enum is the package-mate of the view |
| `ViewBuilder` | `<View>` + `Builder` | DEC-021 header `{@link <view-fqn>}` |
| `ViewBuilderTracking` | `<View>` + `BuilderTracking` | DEC-021 header `{@link <view-fqn>}` |
| `ViewRecord` | `<View>` + `Record` (or the nested `record Record`) | DEC-021 header `{@link <view-fqn>}` |
| `Write` (nested interface) | fixed member name on the view | declared in the view itself; the IDE sees the declaration |
| `toBuilder()` | fixed method name on the view | declared on the view (or emitted as a `default`); the IDE sees the declaration |
| `toBuilderTracking()` | fixed method name on the view | declared on the view (or emitted as a `default`); the IDE sees the declaration |
| `META` | fixed constant on `View_` | static field on the field enum, which already links to the view |
| `forName` | fixed method on `View_` | declared on the field enum |
| `NAME_MAPPER` | fixed constant on `View_` | declared on the field enum |

Rule of thumb: **if the name is `<View>` plus a suffix, or a fixed
member of a generated class that the header already links to the view, it
is refactor-sensitive and must stay navigable.** The generated class
*declaration* is itself the strongest part of the contract — an IDE can
find `PersonSummaryBuilder` by name from `PersonSummary` because the
header names it and the class is in the same package.

### Refactor-insensitive: explicit labels

These names are **persisted or external API labels**. They are written by
the generator as explicit string/annotation values, and an IDE rename
refactor must **not** touch them — that is the correct behaviour, because
they are contracts with a database or a peer system, not with the Java
type system:

| Label | Where it lives | Why the rename must not reach it |
|---|---|---|
| `case "firstName" ->` arms | the generated `forName` switch | the label is the persisted field name; renaming the accessor would break every stored payload that carries the old name |
| `case "firstName"` JSON names | the generated `forName` switch used by the Jackson path | same — an incoming payload uses the wire name |
| `@FieldSource(column = "…")` values | the generated enum's `column()` override | the value is the database column name; a Java rename must not issue a DDL change |
| an unannotated `COLUMN` field's `column()` | the generated enum's `column()` override | the value is the **accessor name** (`FieldDef.column()`'s documented default), so this one *is* refactor-sensitive by construction: renaming the accessor renames the column. Every `COLUMN` field carries the override, annotated or not, so an adapter never needs a name table of its own |
| discriminator values (`discriminatorValue`) | generated polymorphic wiring | the value is the wire/database discriminator for a subtype; it is a protocol constant |
| the `entityFieldEnum: true` key | the DEC-021 header | a config key, not a Java identifier |
| the header `enabled` / `allowReorder` keys | the DEC-021 header | config keys, not Java identifiers |

There is no derivation from a Java identifier here: the value is chosen
explicitly (by the annotation, by the discriminator declaration, or by
the generator's fixed protocol), so the IDE has nothing to follow and
must leave it alone. A generator must **not** derive one of these labels
from a Java identifier that the IDE might rename.

### Why the table is part of the public API

A developer reading this file must be able to answer: *"if I rename the
view or one of its accessors, what generated names change?"* The answer
is exactly the first table. Anything not in it must be assumed explicit
and stable.

## R1 order contract

The generated field enum's **constant order is a persisted ordinal
layout**: `values[field.ordinal()]` is that field, in every persisted
array, patch and snapshot. Two enums with the same constants in a
different order are different data formats. The rule is recorded in full
in [DEC-023 — R1: field enums are append-only ordinal ledgers](../doc-hipster-entity/architecture/decisions/DEC-023.md);
this section is the operational summary.

### The `entityFieldEnum:true` marker

The generator emits a DEC-021 header on every field enum, and that header
carries a second key:

```java
// {@link hr.hrg.hipster.entityexample.person.entity.PersonSummary} Field metadata for the PersonSummary view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
public enum PersonSummary_ implements FieldDef {
```

`entityFieldEnum: true` is what marks the enum as an R1 ledger. **Opt-in
is by absence:** an enum without the marker is ignored by the checker
entirely, which is what keeps hand-written, third-party, and tooling
enums out of the rule.

The marker is read by **parsing** the header comment and decoding the
pinned JSON5 subset of DEC-021 § 4 — never by string-matching the file. A
malformed marker is a diagnostic, and it **fails safe toward "marked"**
(refuse to drop constants), never toward "unmarked".

### Why the constant order is semantic while every other block is shape-recognised

[DEC-020](../doc-hipster-entity/architecture/decisions/DEC-020.md) is the
cooperative-codegen rule: the generator recognises its previous output
**by structural shape** and preserves it verbatim, letting the user opt
back into regeneration by deleting the block. That works because the
blocks it recognises — a `case "…":` arm, a `withXxx(...)` builder
method, a scaffold method, a nested record, a nested `Write` interface —
mean the same thing wherever they sit.

The **constant list is the one block whose position is semantic**. It is
not recognisable by shape alone: two constant lists with the same members
in a different order are different layouts, not two spellings of one. So
the field enum is the single place where the generator's own comparison
is **order-sensitive** and where "recognise by shape, then re-emit
canonically" is the wrong algorithm — canonical re-emission is exactly
what reorders.

Operationally, for the enum only:

- a new constant is always **appended at the end**;
- an existing constant is **never moved and never re-inserted** into a
  "canonical" position;
- a removed field's constant is **tombstoned, not deleted**: it stays in
  place, is marked `@Deprecated`, and its `FieldDef.retired()` override
  returns `true` (the `default` is `false`, so nothing else changes).
  Writers skip a retired constant; readers stay tolerant of its slot.

### The `allowReorder` escape hatch

Deliberate reordering — early development of an unreleased module, or a
migration with a data conversion — is permitted by a **hand-set** flag in
the same JSON5 header:

```java
// {enabled:true, entityFieldEnum:true, allowReorder: true}
```

The generator never emits this flag. Every pass reports it: the checker
emits a **warning-level `enum_reorder_allowed` diagnostic whenever the
flag is present**, and `EntityFieldEnumOrderRule` surfaces the same
warning during validation. Under `--strict` that warning fails the build.
The escape hatch is therefore always visible and cannot be left in by
accident.

### Adding a field: the operational rule

To add a field to a view that already has a generated enum:

1. **Add the accessor** to the view interface (`String middleName();`).
   Put it wherever it reads best in the *interface* — accessor order in
   the interface is not the contract.
2. **Run the generator**.
3. The generator emits the new constant **at the end** of the constant
   list, after every existing constant, and updates `forName`, the
   record's component list, `META`, and (if the project opted into the
   draft SQL generator) the adapters accordingly. It does
   **not** insert the constant next to its accessor, even if that is
   where the accessor sits in the interface.
4. **Never hand-edit the constant list into a "nicer" order.** If a
   constant must move, that is the `allowReorder` path plus a data
   migration, not an edit.
5. **Run the checker** before pushing:
   `java -jar hipster-entity-tooling.jar enum-order --repo . --baseline origin/main`.
   Exit `0` means the ledger is append-only.

To remove a field, delete the accessor and regenerate. The constant is
kept in place as a tombstone; do not delete it by hand.

### The checker CLI

```text
java -jar hipster-entity-tooling.jar enum-order --repo <path> (--baseline <git-ref> | --diff <file>) [--target <ref>] [--strict]
```

| Flag | Meaning |
|---|---|
| `--repo <path>` | the repository root to read (default: the working directory) |
| `--baseline <git-ref>` | `HEAD`, a commit, or a PR base such as `origin/main` |
| `--diff <file>` | a unified diff to use as the baseline — what a PR check has when the branch is not fetched locally. It is reverse-applied to `--repo` (via `git apply -R`), and a diff that does not apply exactly is refused with exit 2 rather than approximated |
| `--target <ref>` | the revision to compare; default `WORKING_TREE` |
| `--strict` | promote warnings (an `allowReorder` escape hatch) to a failure |

Exactly one of `--baseline` / `--diff` is required; giving both exits 2. Neither is "the previous
run": the baseline must be reproducible from the repository.

**Exit codes: `0` = ok, `1` = violation, `2` = usage or environment
error.** That is what lets it gate a build and a PR check.

The comparison is the **removal-then-queue-jump** rule, implemented in
[`EnumConstantOrderChecker`](src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java):

1. every baseline constant must still exist (else
   `enum_constant_removed`);
2. every constant standing before a baseline constant in the target must
   itself be an earlier baseline constant — otherwise the constant that
   jumped the queue is reported, with its old and new index, as
   `enum_order_shuffled`.

A plain "baseline is a subsequence of the target" test is **not** enough:
`[id, firstName]` → `[id, email, firstName]` is a valid subsequence even
though `firstName` moved from ordinal 1 to 2. The queue-jump rule catches
it.

Both kinds are reported in the DEC-022 format
(`kind, location, cause, current, canonical, action`). An enum that is
**unmarked in the baseline** is skipped for that comparison, so the
bootstrap commit that first adds the marker is not reported as a mass
removal.

### Validation of the working tree
[`EntityRulesValidator`](src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRulesValidator.java)
runs the registered [`EntityRule`](src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java)
implementations — `MarkerEntityRule`, `ViewInterfaceRule`,
`ViewAnnotationRule`, `AuditableRule` and
[`EntityFieldEnumOrderRule`](src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityFieldEnumOrderRule.java) —
over a module root. In its in-place form the order rule checks one
revision's self-consistency (marker present, constants unique, header
decodable, enum not empty) and surfaces the `allowReorder` warning; the
cross-revision comparison is driven by the CLI above.

## Reading source outside Maven

The generator reads hand-written source with the **pinned** JavaParser
(`javaparser-core`, version declared once in the root POM's
`javaparser.version`; this module declares no version — see
`DependencyBoundaryTest#javaParserIsPinnedOnceInTheRootPom`). That pin
matters more than it looks: a local Maven repository can hold many
JavaParser versions, and an old one silently fails to parse modern
syntax.

`scripts\gen.cmd` already does exactly this — the mechanism is described
[above](#the-layout-guard-and-the-stale-tooling-trap-it-closes). If you
assemble the classpath yourself instead, build it from Maven rather than
globbing the repository:

```bash
mvn -o -pl hipster-entity-tooling,hipster-entity-example -am compile \
    dependency:build-classpath "-Dmdep.outputFile=<abs path>/cp.txt"
```

and put the directory that holds the entry point **first**, ahead of the
exported dependencies:

```
hipster-entity-tooling/target/classes
<the resolved classpath from cp.txt — a reactor sibling comes back as its
 target/classes directory, not as a jar>
```

Both halves of that recipe were learned from failures:

- a repository glob picked `3.25.1`, which cannot parse `sealed`, and
  five example files generated **nothing** with no error (the only
  symptom was files missing from a diff);
- `dependency:build-classpath` maps a reactor dependency to that
  module's `target/classes` **only while that module is in the same
  reactor invocation**. Run it for one module alone and this module's
  siblings resolve to the **installed** `~/.m2` jars, which may be a
  previous revision, so a hand run compiles generated source against
  stale classes and reports `cannot find symbol` or "does not override"
  for code that is correct. The `-am` above keeps the siblings in the
  reactor; `ExampleMetadataGeneratorTest` and friends do not hit this
  because they run in the reactor too.

## Divergence reporting

Every generation pass collects
[`DivergenceReporter`](src/main/java/hr/hrg/hipster/entity/tooling/DivergenceReporter.java)
entries in the uniform DEC-022 shape
`kind, location, cause, current, canonical, action`, printed at the end
of the pass and readable from `EntityMetadataGenerator.lastDivergences()`.
The recognised kinds include `enum_order_shuffled`,
`enum_constant_removed`, `enum_constant_appended`,
`enum_reorder_allowed`, `enum_not_parsed`, `field_retired`,
`addon_on_non_view`, `field_in_enum_not_in_interface`,
`field_in_interface_not_in_enum`, `stale_switch`, `missing_setter`,
`type_mismatch`, `ordinal_drift`, `nested_record_reused`,
`polymorphic_root_enum_preserved`, `mapper_field_missing_in_source`,
`mapper_field_missing_in_target`, `mapper_type_incompatible`,
`mapper_view_not_found`, `mapper_request_malformed`,
`validation_constraint_unsupported` and
`validation_constraint_type_mismatch`.

Two of them are worth spelling out because they are the generator
declining to guess rather than reporting a defect:

- **`type_mismatch` is not reported when the generator could not resolve
  the declared type.** `classLiteral` maps the type parameter name `ID`
  (from `Identifiable<ID>`) to `java.lang.Object`, so a view resolved
  under a marker whose id type is still that parameter would otherwise
  have its perfectly correct `Long.class` constant reported as a hand
  edit on every pass. A divergence line has to be actionable or it
  trains readers to ignore the report.
- **`missing_setter` matches arity, not the name.** Every builder
  declares a no-arg read accessor per field, so a name-only check would
  always find `lastName()` and never notice the absent `lastName(...)`.

## See also

- [`hipster-entity-api/README.md`](../hipster-entity-api/README.md) — the
  contracts the generated code implements.
- [`hipster-entity-core/README.md`](../hipster-entity-core/README.md) — the
  runtime the generated code builds on.
- [`hipster-entity-test/README.md`](../hipster-entity-test/README.md) — where
  the integration fixtures live.
- [DEC-019](../doc-hipster-entity/architecture/decisions/DEC-019.md),
  [DEC-020](../doc-hipster-entity/architecture/decisions/DEC-020.md),
  [DEC-021](../doc-hipster-entity/architecture/decisions/DEC-021.md),
  [DEC-022](../doc-hipster-entity/architecture/decisions/DEC-022.md) —
  source-visible wiring, cooperative codegen, the class-file header, and
  refactor-sensitivity.
- [DEC-023](../doc-hipster-entity/architecture/decisions/DEC-023.md) — R1,
  the append-only ordinal ledger.
- [DEC-025](../doc-hipster-entity/architecture/decisions/DEC-025.md) — the
  deliberate, acknowledged ordinal migration.
- [DEC-027](../doc-hipster-entity/architecture/decisions/DEC-027.md) — HTML
  reports are rendered by Bun from the JSON metadata this module writes; the
  renderer is [`scripts/entity-html/`](../scripts/entity-html/README.md). This
  module emits **no** HTML: a fact a report needs belongs in `toJson`.
- [Materialization levels](../doc-hipster-entity/architecture/materialization-levels.md) —
  what each `GenLevel` emits.
- [Getting started in a new project](../doc-hipster-entity/user/getting-started-new-project.md) —
  the end-to-end workflow.
- [Field-enum compaction](../doc-hipster-entity/user/patterns/field-enum-compaction.md) —
  the migration procedure, step by step.
