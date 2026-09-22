# Follow-up brief — Phase 6, the emission decision (4 files)

> **Status: RESOLVED — closed on 2026-09-22.** Route A (text emission) was taken and all four files are
> ported; the module gate is `362/362` with `javaparser-core` gone from every POM in the tree. This file
> is kept as the record of the decision and of the reasoning behind it — `MIGRATION-CAVEATS.md` § 4.2 is
> the summary, and the resolution notes are in § "Resolution" below. Nothing here is outstanding.
> **Decides:** how the last four `com.github.javaparser` consumers in
> `hipster-entity-tooling` write Java once JavaParser is gone.
> **Context:** `MIGRATION-CAVEATS.md` § 4.2 (the routes), § 1.11 (the printer difference),
> § 1.8 (why `LexicalPreservingPrinter` is not an option).
> **Tracker rows that name these files:** `tracker.md` → `## Queue`, now all `complete`.

## Resolution

**Route A — text emission — was chosen and executed.** The deciding argument was the one this brief
already made: route B changes committed artifacts, and *nothing in this repository changes committed
artifacts without a human deciding that*, while route A could satisfy the existing byte-identical gate as
it stood.

What the round found, beyond the brief's own analysis, is that "text emission" meant reproducing the
**printer's artefacts** rather than writing idiomatic Java. Each of these is now asserted by a committed
file or a test, and the first three are in `MIGRATION-CAVEATS.md` § 4.2:

1. the enum layout switches from horizontal to vertical at **five** constants, and also when any constant
   carries a comment (a tombstone's javadoc);
2. `@Override()` keeps its empty parentheses, with a blank line between overrides;
3. a constant's separating comma is on its own line when the constant has a class body, inline otherwise;
4. the **generic comma has two spellings in one pass** — declarations compact, creator-body casts spaced —
   which `ViewRecordGeneratorTest` pins four lines apart in the same class;
5. the DEC-021 header uses `\n` while the body uses the platform separator, so a generated file really
   does mix line endings.

`EnumCompactionCli` was ported by a different mechanism from `FieldBoilerplateGenerator`, and the brief
did not anticipate the split: it **deletes**, and an LST has no `remove`, so its two deletions became two
javac character spans sliced out of the file (`TreeQueries.memberTextSpan` for the tombstone including its
javadoc, `TreeQueries.caseSpans` for the `forName` arm). The result is the original file minus those
ranges, which is strictly better than the old whole-unit reprint — nothing outside the deleted ranges
moves — and changes no contract, because `CompactionRoundTripTest` already recorded that compaction's
output is not the generator's canonical emission and that the documented procedure ends with a
generation pass.

**Definition of done, all met:** `verify-migration.js` reports `RESULT: PASS` with no
`pom-dependencies` warning; every queue row is `complete`; the `SourceReader` JavaParser bridge and the
two guard assertions that belonged to it are deleted (the readability guard was *re-expressed* against
the LST path rather than dropped); `Checklist.md` and the migration report are regenerated; and the
committed example regenerates byte-identically, so no generated artifact changed.

---
## 1. What is left, exactly

| file | lines | what it does with a tree | imports |
| --- | --- | --- | --- |
| `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java` | 1007 | **builds** a whole `CompilationUnit` (`addEnum`, `addEntry`, `addMember`, `setJavadocComment`, `parseExpression`, `parseBodyDeclaration`) and prints it through `PrettyPrinterConfiguration`, then applies two textual fix-ups | 35 JavaParser |
| `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java` | 403 | **mutates** a parsed enum — drops `forName` switch arms and removes constants — and prints it | 12 JavaParser |
| `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java` | — | drives the mutation and asserts on its output | via `EnumCompactionCli` |
| `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java` | — | ditto | via `EnumCompactionCli` |

Everything else in the module is ported and green. These four are the whole remainder, and they are
the whole reason `hipster-entity-tooling` still declares `javaparser-core` — the one remaining
`pom-dependencies` warning in `verify-migration.js`.

The other consumers the earlier drafts of this plan listed are **done**: `EntityMetadataGenerator`,
`MetadataLocations`, `ClassIndex`, `TypeFacts`, `CooperativeCodegen`, `SourceReader`, the four
validation rules, `TypeLiterals`, `ValidationGenerator`, `ViewAnnotationReader`,
`ViewInterfaceGenerator`, `ViewBuilderGenerator`, `AddonAndInheritanceTest`, the `jwa-builder` and
`jwa-sidecar` files, and the five `java-watch-agent` files.

## 2. Why these four are not a translation

They are the only files in the phase whose job is **to produce Java**, and the two mechanisms they
rely on do not exist in an LST:

- **`cu.addEnum(...)` / `addEntry(...)` / `addMember(...)` / `enumDecl.addEntry(...)`.** An LST is
  immutable: there is no `add`, and every "change" returns a new tree (`withXxx`) or is expressed as a
  template (`JavaTemplate`).
- **`entries.remove(...)` / `switchStmt.getEntries().remove(...)`.** There is no `remove` at all.

So the question is not "which API replaces `addEnum`" but **which of three emitters this module
wants**, and the answer has a consequence the phase's own gate measures.

## 3. The two routes, and what each one costs

### Route A — text emission (recommended)

Generate the enum's text and write it, the way the rest of the module already emits
(`ValidationGenerator`, the record/builder emitters, `jwa-builder`'s `SourceSplicer`,
`CooperativeCodegen`'s slice-and-splice).

- **Why it fits:** the emitters already do this, so there is one mechanism in the module rather than
  two; the output can be made byte-identical to today's, because today's bytes are *in the
  repository* (`hipster-entity-example/src/main/java/.../PersonSummary_.java` and its siblings) and
  `ExampleRegenerationTest` checks every one of them.
- **Cost:** the enum's six member shapes have to be written by hand (`javaType()`/`propertyType()`
  field, the constructor, the `javaType()`/`propertyMethods` group, `forName` with its `name-slot`
  and `ordinal-slot` arms, the `NAME_MAPPER` field, and the `META` field with its permit-list and
  discriminator arguments), plus the tombstone shape (`@Deprecated`, javadoc, `retired()` override).
  `EnumCompactionCli`'s two mutations become text edits on the `forName` arm list and the constant
  list.
- **Acceptance:** `ExampleRegenerationTest` byte-identical **without committing a single generated
  change** — that is the whole test of this route. Then the four files' gates:
  `hipster-entity-tooling`'s `clean test` green, `javaparser-core` removed from its POM, and
  `SourceReaderTest`'s allowlist entry retired with the bridge.

### Route B — LST construction + OpenRewrite's printer

Build the enum with `withXxx`/`JavaTemplate` and print it.

- **Why it might be preferred:** it is the mechanism `TreeQueries` was deliberately kept neutral for,
  and it is how a future `JavaTemplate`-based generator would work.
- **Cost, and it is the important part:** OpenRewrite's printer is **not** JavaParser's. Measured and
  already paid for once in this phase: it writes `Map<String, List<Long>>` where JavaParser wrote
  `Map<String,List<Long>>` (§ 1.11). Every committed field enum is therefore regenerated **with
  different bytes**, so this route is a Phase 4.1-style commit that also shifts the line numbers the
  metadata documents and the report links point at — and `ExampleRegenerationTest` must be updated in
  the same commit rather than satisfied.
- **Acceptance:** if this route is chosen, the acceptance criterion is *not* "byte-identical"; it is
  "the regenerated example compiles, its metadata and report links verify, and the diff is reviewed
  and committed as a generator change".

**Nothing in this repository changes committed artifacts without a human deciding that.** That is why
the phase stopped here and wrote this file instead of picking a route.

## 4. Facts a solver needs, so none of them is rediscovered

1. **The printer difference is load-bearing in two places at once**: the byte-identical example gate
   *and* cooperative codegen's recognition of its own previous output (DEC-020). A printer change can
   make the generator report `generated_member_diverged` for members it wrote itself.
2. **`TreeQueries.memberText` already returns a declaration's exact source text**, with its attached
   comment, from javac offsets. If a route needs "the text of an existing member", it exists; do not
   re-print it.
3. **A `J.Case`'s rule-arm body is on `getBody()`, not `getStatements()`.** Reading only
   `getStatements()` finds no ordinal slot in any builder, and the failure is a *missing* fact.
4. **`J.Literal.toString()` is the literal's value, not its source** — quotes are lost. Use
   `TreeQueries.expressionText`.
5. **A parser is never a field.** It caches parsed sources and refuses a second set declaring the same
   fully qualified names; `jwa-builder` constructs one per call and says why.
6. **`EnumCompactionCli` has a documented temporary bridge** — `SourceReader.portingParser()` — that is
   deleted with the last JavaParser caller in the module.

## 5. Verification commands

```sh
# the module gate (must be run with `clean`: note F-47, where a stale build reported success)
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am ^
        -Dmaven.compiler.useIncrementalCompilation=false clean test"

# the Phase 6 gate: no remaining imports, POM dependency gone, tracker/checklist/report current
bun run scripts/rewrite-migration/verify-migration.js
bun run scripts/rewrite-migration/generate-checklist.js --check
bun run scripts/rewrite-migration/generate-migration-report.js
bun test scripts/rewrite-migration
```

Green baselines to preserve, measured at the time of writing:

| module | tests | note |
| --- | --- | --- |
| `hipster-entity-tooling` | 362 | includes `ExampleRegenerationTest` (byte-identical example) |
| `jwa-builder` | 20 | includes the 6 `ClassMemberProcessorTest` tests added by this phase |
| `jwa-sidecar` | 3 | |
| `hipster-entity-core` | 88 | untouched by the migration; listed so a drop is noticed |
| `java-watch-agent` | 0 | it has no tests: `-pl java-watch-agent -am -DskipTests clean compile` is its gate |

## 6. Definition of done for Phase 6

1. `verify-migration.js` reports **no** `pom-dependencies` warning: `javaparser-core` is gone from
   `hipster-entity-tooling`'s POM.
2. No `QUEUE` row remains in any status other than `complete` or `exempt`.
3. The `SourceReader` JavaParser bridge (`portingParser`, `readJp`, `readJpText`, `readUnitJp`,
   `readUnitJpText`, `ReadJp`) and the `SourceReaderTest` / `DependencyBoundaryTest` allowlist entries
   that guard it are deleted in the same commit — an allowlisted reference that no longer has a
   subject is a hole, not a record.
4. `Checklist.md` and the migration report are regenerated, and `generate-checklist.js --check`
   passes.
5. The committed example regenerates exactly as the chosen route specifies — byte-identical for
   Route A, reviewed-and-committed for Route B.
