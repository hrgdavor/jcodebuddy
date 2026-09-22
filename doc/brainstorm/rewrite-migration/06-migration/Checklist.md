# Phase 6 — Migration Checklist

> **Generated file.** Written by
> `bun run scripts/rewrite-migration/generate-checklist.js --write`.
> Do not edit by hand: edit `scripts/rewrite-migration/curation.js` for what a file
> needs, or `doc/brainstorm/rewrite-migration/06-migration/tracker.md` for its status, and regenerate.
>
> Regenerate after any change to the Java tree, or `verify-migration.js` will fail
> on the disagreement.

Generated: 2026-09-22

## First pass — completed

The read-only spine is ported: **34 files settled**, 0 in progress, 0 remaining. `hipster-entity-tooling` builds and its full test suite is green.

Three API findings from that pass are recorded in `MIGRATION-GUIDE.md` § 4, and each one matters for every file still to be ported because each is silent:

- **§ 4.5** OpenRewrite *recovers* from syntax errors — no throw, no `ParseExceptionResult` marker, still a well-formed `J.CompilationUnit`. The check the guide previously prescribed cannot detect this, so `JavaSyntaxCheck` asks javac instead.
- **§ 4.6** a reused parser must be `reset()` between parse sets, or the second read of any type reports "unparseable".
- **§ 4.7** an interface's `extends` clause is held in `getImplements()`, not `getExtends()`; reading the latter finds no supertype for any interface.

Read [`MIGRATION-CAVEATS.md`](MIGRATION-CAVEATS.md) **before** porting a file: it lists the eight ways a port fails silently, the model differences that force a rewrite rather than a rename, and the open gaps.

## Phase 6 state

| Measure | Count |
| --- | --- |
| Files in the migration queue | 34 |
| Settled (complete or exempt) | 34 (100%) |
| Remaining | 0 |
| Still importing JavaParser | 0 |
| Unclassified by curation | 0 |
| JavaParser import lines to remove | 0 |

## Phase-0 prerequisites

These are not part of the queue: they are the conditions that must hold
before a port can be verified. Each was found by running the tooling, and each
is real in the tree today.

| Id | Prerequisite | Done |
| --- | --- | --- |
| P0-1 | Repair project-automation staging code so the module compiles | yes |
| P0-2 | Re-point the hr.hrg.rewrite package at the real OpenRewrite API | yes |
| P0-6 | Repair java-watch-agent, which has never compiled | **no** |
| P0-7 | metadata-server has a failing test that blocks every downstream module gate | **no** |
| P0-3 | Add the OpenRewrite dependency set to project-automation | yes |
| P0-4 | Establish the clean-build baseline for every module in the queue | **no** |
| P0-5 | Reconcile the plan’s file list with the tree | **no** |

### P0-6 — Repair java-watch-agent, which has never compiled

With `project-automation` compiling, `java-watch-agent`’s own breakage became visible and it is not a migration problem at all: **`FileChange` and `ToolContext` do not exist anywhere in the repository**, and `git log --all` shows they never did. `ActionToolAdapter` references both, as do `ActionEngine`, `ActionTool`, `HelloTool`, `RecordBuilderGenerator` and the three generator tools. There is also a Jackson 3 incompatibility in `AuditManager` (`ObjectMapper.enable(SerializationFeature)` no longer exists).

*Evidence:* `git show HEAD:.../ActionToolAdapter.java` contains the same `FileChange` / `ToolContext` references as the working tree, so this predates every change in this migration. Measured: 6 compile errors in 2 files after the staging package was removed.

### P0-7 — metadata-server has a failing test that blocks every downstream module gate

`MetadataServerTest.httpForyRoundTrip` fails with an HTTP 500, and `java-watch-agent` (and `project-automation`) depend on `metadata-server`, so no downstream `clean test` gate can pass. It fails in isolation — `mvn -pl metadata-server -am test` reproduces it with none of this migration’s changes on the classpath — so it is pre-existing and unrelated to the port, but it must be resolved or excluded before any module whose gate includes it can be called green.

*Evidence:* `mvn -o -pl metadata-server -am -Dmaven.compiler.useIncrementalCompilation=false test` → `Tests run: 5, Failures: 0, Errors: 1` — `MetadataServerTest.httpForyRoundTrip:155 » IO Server returned HTTP response code: 500 for URL: http://localhost:18291/api/fory`.

### P0-4 — Establish the clean-build baseline for every module in the queue

A port cannot be verified against a build that was already broken. Record the JavaParser-era result of `scripts\mvn-jdk25.cmd` per module before the first port, so a later failure is attributable.

*Evidence:* Measured 2026-09-21: hipster-entity-tooling compiles clean on JDK 25 (`clean compile`, BUILD SUCCESS); project-automation does not (7 errors). jwa-builder and jwa-sidecar still need a recorded baseline.

### P0-5 — Reconcile the plan’s file list with the tree

The plan names paths that do not exist and omits files that do, so following it literally migrates the wrong set.

*Evidence:* Listed: `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/HttpBridgeStartupActivity.java` and `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/JwaTextDocumentService.java` — neither exists; the real file is jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java. Also listed `hipster-entity-tooling/.../validation/JavaParserTool.java` under test with an extra `validation/EnumCompactionCliTest.java`; the real JavaParserTool is main-source and there is exactly one EnumCompactionCliTest. Omitted entirely: the six files whose JavaParser use is fully qualified and therefore invisible to the plan’s own scan — ViewBuilderGenerator.java and meta/InterfaceInfo.java in main, plus AddonAndInheritanceTest, CompactionRoundTripTest, SourceReaderTest and validation/EnumCompactionCliTest in test. `project-automation` is listed as "all files using JavaParser", but its `hr.hrg.rewrite` package uses none.

## Dependency set

Leaving JavaParser 3.28.0, arriving at OpenRewrite 8.90.4.
Versions are managed by the root POM; the set below mirrors `merge-java`, which
is the one module already fully ported.

| Artifact | Version | Why |
| --- | --- | --- |
| `org.openrewrite:rewrite-core` | (parent-managed) | LST, visitors, execution context, markers. |
| `org.openrewrite:rewrite-java` | (parent-managed) | The Java LST types (`J`), `JavaIsoVisitor`, `org.openrewrite.java.JavaParser`. |
| `org.openrewrite:rewrite-java-25` | 8.90.4 (pinned, not parent-managed) | The version-specific parser implementation. `rewrite-java` is a facade and needs exactly one of these; -25 is the newest in 8.90.4 and matches the parent’s `maven.compiler.release=25`. |

## How to read an entry

Each file below carries: the JavaParser surface measured on disk, the
OpenRewrite mapping for each type, and the migration notes from
`curation.js`. Mapping confidence is marked on every type:

- **verified** — the OpenRewrite side is already used in this repository
  (`merge-java`), so the shape is one this project has compiled.
- **inferred** — the documented LST type for the concept, but unused here.
  Confirm against the OpenRewrite reference for 8.90.4 before
  relying on it.

## The queue (34 files, in work order)

Ordered by priority, then by risk. See `## Per-file detail` for the notes.

| Status | Priority | Risk | Imports | File |
| --- | --- | --- | --- | --- |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java` |
| `[x]` complete | high | high | 0 | `jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java` |
| `[x]` complete | high | high | 0 | `jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceSplicer.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java` |
| `[x]` complete | high | medium | 0 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java` |
| `[x]` complete | high | medium | 0 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java` |
| `[x]` complete | high | medium | 0 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java` |
| `[x]` complete | high | medium | 0 | `jwa-builder/src/main/java/hr/hrg/watch2/builder/SourceSplicer.java` |
| `[x]` complete | high | medium | 0 | `jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java` |
| `[x]` complete | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java` |
| `[x]` complete | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java` |
| `[x]` complete | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java` |
| `[x]` complete | high | low | 0 | `jwa-builder/src/main/java/hr/hrg/watch2/builder/LineLookup.java` |
| `[x]` complete | medium | medium | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java` |
| `[x]` complete | medium | medium | 0 | `jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java` |
| `[x]` complete | medium | low | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java` |
| `[x]` complete | medium | low | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java` |
| `[x]` complete | medium | low | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGeneratorTest.java` |

## Per-file detail

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 409

**JavaParser surface on disk**:

- 3 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6: the span comes from javac, because the LST has no positions at all. JavaParser answered`
  - `* {@link J.MethodDeclaration#isConstructor()} and keyed by the class name, which is what JavaParser's`
  - `* {@link J.VariableDeclarations} holding N declarators where JavaParser held one`

**Migration notes**:

> **Why this is not mechanical**: It implements DEC-020 block preservation, whose recognition is structural and whose range/comment inputs are exactly what the LST models differently. A port that compiles here can start overwriting hand-edited generated code.

The riskiest file in the phase and the one to leave until last, after the read and print paths are proven. DEC-020 recognition is "the generator recognises its previous output by the same structural shape it emitted it with", so the port must keep recognising a block whose body a human edited. `Range` has no LST equivalent: JavaParser supplies line/column, the LST supplies character offsets that must be resolved to lines through the `SourceFile` text, and a location that shifts by one line changes which generated block is considered present. Comments are the other half — `com.github.javaparser.ast.comments.Comment` is a positioned node, while the LST keeps comment text as a prefix on the following tree. Port only with the DEC-020 three-state behaviour covered by a test on each side (present-and-tweaked, present-and-pristine, deleted).

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`, `org.openrewrite.java.JavaIsoVisitor`, `org.openrewrite.marker.SearchResult`

**Blocks**: every generator that emits a cooperative block

**Steps**:

1. Port `SourceReader` first and re-run the DEC-020 tests against the unchanged CooperativeCodegen, so the read path is proven before the recognition logic moves.
2. Replace `Range` comparisons with an offset→line helper over the `SourceFile` text, and assert the helper against a fixture whose line numbers are known.
3. Replace comment-node reads with the LST printer’s comment accessors; add the deleted-block case to the test set before changing the recognition code.
4. Keep the emitted hint comment (`// generated by ... — edit freely, delete to regen`) byte-identical: it is a UX aid, and DEC-020 forbids relying on it for recognition.

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 3621

**JavaParser surface on disk**:

- 10 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `// The old code asked JavaParser for a result and then for the package declaration; the`
  - `* <p>This is the offline substitute for JavaParser's symbol solver: it does not answer "what does`
  - `// JavaParser's `getFullyQualifiedName()` — the LST has no such accessor, and a node does not`
  - `// Files JavaParser could not read cleanly. Collected rather than reported inline so the`
  - `// JavaParser unit now, and the source is what its line numbers come from.`
  - `// single `J.EnumValueSet` statement, which is the same list JavaParser's `getEntries()``
  - …and 4 more

**Migration notes**:

> **Why this is not mechanical**: The largest generator (3487 lines) and the module’s shaded main class; it mixes parsing, annotation reading and node construction, so it needs all three ported foundations at once.

Split the port rather than attempting it whole: the annotation-reading half is mechanical (AnnotationExpr → J.Annotation, MemberValuePair → J.Assignment, NameExpr/SimpleName → J.Identifier), while the node-emitting half is a rewrite because the LST is immutable. Decide the emission strategy deliberately — `JavaTemplate` for readable generated source, or `withXxx` builders where a template is not worth it — and record the choice, because mixing the two styles in one generator is how this becomes unmaintainable.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`, `org.openrewrite.java.tree.J.Annotation`, `org.openrewrite.java.JavaIsoVisitor`

**Steps**:

1. Port the annotation readers first and cover them with the existing tests; they are behaviour-preserving.
2. Choose and record the emission strategy for the whole module before porting any emitter.
3. Port one generated artifact end-to-end and diff its output against the JavaParser result byte-for-byte.
4. Only then port the remaining artifacts, one diff at a time.

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 1080

**JavaParser surface on disk**:

- 8 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The JavaParser version <em>built a {@code CompilationUnit}</em> — {@code addEnum}, {@code addEntry},`
  - `* rejected for this file: OpenRewrite's printer formats differently from JavaParser's (measured: a space`
  - `* <p>Phase 6: JavaParser gave five expression classes here; the LST has one node per shape, and the`
  - `* <p>Phase 6: JavaParser modelled a constant as its own member declaration; the LST groups an enum's`
  - `* The enum as <strong>text</strong>, byte-identical to what the JavaParser printer produced.`
  - `// `MAX_HORIZONTAL_CONSTANTS` is JavaParser's own default rather than a number chosen here.`
  - …and 2 more

**Migration notes**:

> **Why this is not mechanical**: 35 JavaParser imports and an array-construction path (ArrayCreationLevel / ArrayCreationExpr / ArrayInitializer) whose LST shape differs most from JavaParser’s.

The module’s broadest single surface. Two specifics: `NodeList.nodeList(...)` construction becomes immutable list construction plus `withXxx` calls, and the array-default path collapses three JavaParser nodes onto one `J.NewArray`. The emitted `get(int)` methods use switch *expressions* (`J.SwitchExpression`, distinct from the statement `J.Switch`), and the default values are string literals — keep escape sequences exactly as emitted, since OpenRewrite normalises literals and a changed escape is a changed generated file rather than a changed tree.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.NewArray`, `org.openrewrite.java.tree.J.Literal`, `org.openrewrite.java.template.JavaTemplate`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 684

**JavaParser surface on disk**:

- 6 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser answered every line here with {@code node.getBegin().line}. The LST exposes`
  - `* {@link J.EnumValueSet} statement (JavaParser had a separate {@code EnumDeclaration} class with`
  - `* {@link J.VariableDeclarations} with one or more declarators (JavaParser had one`
  - `// the JavaParser walk collected, because a false positive needs a field and a local of one`
  - `* <p>JavaParser gave five distinct types here — {@code EnumDeclaration},`
  - `* than a fallback. JavaParser exposed a compilation unit's comments with their positions, so the`

**Migration notes**:

> **Why this is not mechanical**: Location and comment semantics both change, and those are the two inputs this file exists to compute.

Turns a declaration into the `SourceLocation` records that DEC-028/029 address a member by. Twenty imports, nearly all renamed: fields become `J.VariableDeclarations`, enums become `J.ClassDeclaration` with `J.EnumValue` children, records are kind-Record class declarations, and comments lose their node. Treat the emitted `SourceLocation` values as the contract and re-verify every one against a real file before trusting the port; a location that is off by a line makes the generated HTML report link to the wrong member, which is what DEC-029’s link verification exists to catch.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`, `org.openrewrite.java.tree.Expression`, `org.openrewrite.SourceFile`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 367

**JavaParser surface on disk**:

- 10 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser was <strong>error tolerant</strong>: for genuinely broken source it still returned a`
  - `* <p>This class now parses with OpenRewrite instead of JavaParser. The <strong>contract is unchanged`
  - `*   <li><strong>Two failure channels, not one.</strong> JavaParser reported failure through a single`
  - `*       checked: it <em>throws</em> where JavaParser was tolerant, and a tree that is not a`
  - `*       JavaParser too old for {@code sealed} made five example files generate nothing, with no error`
  - `* repeat per file. Unlike JavaParser, OpenRewrite's parser carries no language-level setting — the`
  - …and 4 more

**Migration notes**:

> **Why this is not mechanical**: It is the one place the tooling reads source, and its whole design is a fail-safe against a mis-read being mistaken for a fresh file (notes F-23, F-34). A port that loses the "could not read" signal reintroduces the silent renumbering of a persisted positional array.

Port this first: 33 of the 34 queue files reach the AST through it, so a mistake here is inherited everywhere. The contract to preserve is exact: a clean parse yields a unit; a broken file yields a distinguishable "unparseable" outcome, never a partial tree. JavaParser gave that for free via `ParseResult.isSuccessful()`; OpenRewrite needs two checks — catch the throw, and treat "the source file is not a `J.CompilationUnit`" as a failure. The `LanguageLevel.JAVA_25` pin exists because a default parser cannot read the generator output it produced; the replacement is the `rewrite-java-25` artifact, and the property to re-assert in a test is "a record, a switch expression and a sealed type all parse".

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.SourceFile`, `org.openrewrite.ParseExceptionResult`

**Steps**:

1. Add `rewrite-core` / `rewrite-java` / `rewrite-java-25` to hipster-entity-tooling/pom.xml, parent-managed, mirroring merge-java.
2. Replace the static `JavaParser` field with a `JavaParser.fromJavaVersion()...build()` instance; keep one shared instance per parse set and reset between sets declaring the same FQNs.
3. Keep `Read` and its `readable()` / `ofUnparseable()` factories unchanged in shape — callers depend on the distinction, not on the parser.
4. Map `parse(source)` to `parseInputs(...)` over a `Parser.Input`; take the first `SourceFile`, require `instanceof J.CompilationUnit`, and collect `ParseExceptionResult` markers as the unparseable reason.
5. Re-point the JAVA_25 regression test at "these three language features parse" and confirm it fails when the parser artifact is wrong.

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 518

**JavaParser surface on disk**:

- 3 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The JavaParser version did {@code entry.remove()} on the constant and {@code entry.remove()} on`
  - `* that is the property the JavaParser version's whole-unit print did not have.</p>`
  - `* annotation list, a name, and a {@code new} expression whose body is the block JavaParser held as`

**Migration notes**:

> **Why this is not mechanical**: Same persisted-order hazard as EnumConstantOrderChecker, plus it prints whole files through the pretty printer, so its output diff is expected to change.

The one file where dropping `PrettyPrinterConfiguration` changes the artifact: it formats a complete file rather than preserving it, so the compaction CLI’s output diff must be reviewed and accepted deliberately. `SwitchEntry` maps to `J.Case`, which covers both `case X:` and the arrow form — confirm label extraction on both, since the CLI matches arms by literal.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.EnumValue`, `org.openrewrite.java.tree.J.SwitchExpression`, `org.openrewrite.java.tree.J.Case`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 342

**JavaParser surface on disk**:

- 4 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser had a real comment model, {@code cu.getAllComments()}, and the old code leaned on a`
  - `* subtlety of it: JavaParser attaches the last comment of a run to the <em>following</em>`
  - `* <p>JavaParser had a comment model, so {@code cu.getAllComments()} returned both header lines and`
  - `* {@code // {@link com.example.Foo} description}, which also begins with a brace. JavaParser never`

**Migration notes**:

> **Why this is not mechanical**: It derives enum constant order — a persisted positional contract — and reads comments to hold that order. Both inputs change shape in the LST.

Enum constants are `J.EnumValue` entries inside the class body’s statement list rather than a dedicated constant list, and the comment that anchors the order is no longer a node. Since the compaction output is a positional array, an off-by-one in the constant list silently renumbers persisted data — port this with the compaction round-trip test as the gate, not a compile check.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.J.EnumValue`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java
```

### `jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 81

**JavaParser surface on disk**:

- 4 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The JavaParser version parsed with {@code LexicalPreservingPrinter} enabled, mutated the record`
  - `* the record through JavaParser's {@code Range}.</p>`
  - `* JavaParser version produced — so {@code CodeEditApplier} and the callers in {@code jwa-sidecar} and`
  - `*         cannot be located — the same fail-safe the JavaParser version had, and deliberately not`

**Migration notes**:

> **Why this is not mechanical**: Lexical preservation is the file’s entire purpose; the LST guarantees it differently, and the edit’s range must keep meaning the same thing so the two consumers do not silently rewrite whole files.

Ported, and it lost three of its four steps: lexical preservation (no longer needed — the edit is a range replacement, so the surrounding text is untouched by construction), the line-by-line re-indentation pass (the indent is applied where the text is built), and `Range` (the span comes from javac). What it kept is the `CodeEdit` contract: the edit still replaces the record’s own span with the completed record, which is why `jwa-sidecar` and `java-watch-agent` compiled unchanged. The parser is built per read rather than shared: a parser refuses a second set of sources declaring the same FQNs, which is exactly what completing a record twice produces (MIGRATION-CAVEATS.md § 1.2).

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.tree.J`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java
```

### `jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: comment-only · **Lines**: 290

**JavaParser surface on disk**:

- 4 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The JavaParser version mutated a live AST — {@code record.addMethod(...)},`
  - `*       keep JavaParser's node identity stable so the printer would emit each member once. Generating`
  - `* <p>Selection reproduces the JavaParser version: prefer a record whose <em>name</em> line is within`
  - `* <p>Both spellings of the annotation are accepted, exactly as the JavaParser version accepted them:`

**Migration notes**:

> **Why this is not mechanical**: It is a build-time processor: its input is generated record source and its output is edited source, so a port that reformats output breaks the build for every consumer of the module. It also mutates an AST, which an LST cannot do at all — the port is a rewrite, not a rename.

Ported by replacing AST mutation with text generation, and that is the design rather than a stopgap. The JavaParser version added methods, swept stale fields and setters, and reordered members on a live tree, relying on `LexicalPreservingPrinter` to write it back — none of which exists on an immutable LST, and none of which was ever what the feature is. Generating the builder from the record’s component list produces the same text with no node-identity bookkeeping. Recognition of the *previous* builder (the two entry-point methods and the nested `Builder` class) is by name and structure, which is what makes the operation idempotent without a marker comment. This module therefore answers the migration guide’s open emission-strategy question for the append-style case: text, not `withXxx`.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.JavaIsoVisitor`, `org.openrewrite.java.tree.J`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 206

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Two kind tests replace two JavaParser types, and both are the kind of change that keeps`

**Migration notes**:

Reads a declaration to decide the generation level. `RecordDeclaration` has no LST counterpart: a record is a `J.ClassDeclaration` whose kind is Record, so both `instanceof` branches collapse into kind tests. Small file, but it decides which builder levels are emitted, so a wrong kind test changes generated output rather than failing loudly.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 991

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* captures the ancestry from the traversal cursor, which is what replaces JavaParser's`

**Migration notes**:

DEC-029 owner: one row per compiled type, keyed by FQN, carrying the file path, content checksum, checksum instant, size, kind and modifiers. It parses generated artifacts to describe them, so it depends on the ported `SourceReader`. The published contract is the JSON shape — keep it byte-identical and let only the tree access change. The type-collection path is ported (`addTypes(J.CompilationUnit, source, generated)`); a documented JavaParser bridge remains for `EntityMetadataGenerator`, which still holds a JavaParser unit, and it delegates to the same `List<TypeFacts>` overload so there is one index-building implementation rather than two that can drift.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`, `org.openrewrite.SourceFile`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 174

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6: the enclosing chain is supplied rather than discovered. JavaParser's`

**Migration notes**:

Feeds DEC-029’s class index: the per-type kind and modifiers written into `.jcodebuddy/index/classes.json`. The index is keyed by FQN and consumers read `kind` as a string, so the port must preserve the *spelling* of every kind it reports even though the LST discriminates differently. A changed spelling is a silently broken index, not a compile error. Ported: the FQN comes from the cursor-captured enclosing chain, and `non-sealed` is rendered explicitly rather than via the enum constant’s `toString()` — the latter would emit `NON_SEALED` and break DEC-029’s vocabulary for exactly the two hyphenated keywords. `line` comes from javac’s `LineMap` over the same text, matched on the simple name *and* the enclosing chain — the chain alone answers a nested type’s query with its parent’s line (see MIGRATION-CAVEATS.md § 4.1 for the case that cost three attempts).

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`, `org.openrewrite.java.tree.J.Modifier`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 653

**JavaParser surface on disk**:

- 6 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>{@link SourceReader}'s whole reason for existing is the fail-safe recorded as F-34: JavaParser is`
  - `* and DEC-028 verifies every link against the line a member is declared on. JavaParser answered with`
  - `* Re-parsing with JavaParser would work but re-introduces the dependency this migration exists to`
  - `* <p><strong>Two lines, because JavaParser exposed two.</strong> {@code getBegin().line} is the`
  - `* would differ from the one the developer wrote. JavaParser answered this with`
  - `// javac reports. JavaParser modelled `private int a, b;` as one `FieldDeclaration` with two`

**Migration notes**:

Added by the first porting pass to restore a guard OpenRewrite does not provide, and the most important finding of the pass: OpenRewrite **recovers** from syntax errors. On F-34's own fixture (`id(java.lang.Long.class;` inside an enum) `parseInputs` neither throws, nor attaches a `ParseExceptionResult` marker, nor returns anything but a well-formed `J.CompilationUnit` with one enum in it — so the marker check the migration guide prescribes cannot see this class of breakage, and a straight port silently loses the fail-safe `SourceReader` exists for. javac is asked instead, because OpenRewrite's Java parser *is* a javac front end, so this adds no dependency and cannot drift from the parser's own grammar. Syntax only: type errors are ignored by diagnostic code, because this reader is handed single files whose dependencies are not on any classpath.

**OpenRewrite classes involved**: `javax.tools.JavaCompiler`, `com.sun.source.util.JavacTask`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceSplicer.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 162

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The JavaParser version added a member to a live AST and relied on`

**Migration notes**:

Added when the two view emitters were ported: it is the shared splice that inserts generated members into a developer-owned file. Its whole reason for existing is a property the JavaParser version could not guarantee — `LexicalPreservingPrinter` refuses an added `default` modifier ("Not supported keywordDEFAULT"), and `ViewInterfaceGenerator` caught that and fell back to `cu.toString()`, so its normal path reformatted the whole hand-written interface. Splicing into the text leaves every other byte untouched by construction. Known limit, documented on the class: brace matching is textual, which is acceptable because the caller only ever hands it source that has already parsed cleanly.

**OpenRewrite classes involved**: `(none — generates text)`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceSplicer.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceSplicer.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 194

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6: the walk is {@link TreeQueries#typeParameterNames}. JavaParser needed three`

**Migration notes**:

Supports DEC-021’s `{@link …}` first header line. Two fully-qualified `RecordDeclaration` references survive with no import (a half-migrated file), so port by FQN search, not by import list. The `{@link}` target is refactor-sensitive and must stay generated from the type’s own name.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.Identifier`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 84

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `*       {@code null} and {@code getImplements()} is {@code [EntityBase<String>]}. The old JavaParser`

**Migration notes**:

The other rule that renames across the board: interfaces become `J.ClassDeclaration` with an interface kind, and methods lose nothing but `getNameAsString()`. Together with `EntityRulesValidator` it is the template for the remaining rules — port these two first, then the rest are copies.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.J.MethodDeclaration`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 148

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `// enclosing findAll matched one JavaParser type; here the type covers every kind, so`

**Migration notes**:

Interface + supertype check: `ClassOrInterfaceType` splits into `J.Identifier` (bare) or `J.ParameterizedType` (generic), so a supertype comparison must handle both spellings of the same type.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.TypeTree`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 615

**JavaParser surface on disk**:

- 4 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The constraint annotation on the <strong>view accessor itself</strong>, read with JavaParser`
  - `* <p>Recognition is by simple name, because JavaParser resolves nothing: an author writing`
  - `*       JavaParser's four annotation node types.</li>`
  - `* <p>The LST has one annotation node where JavaParser had four, and the forms are told apart by the`

**Migration notes**:

Ported. Reads constraint annotations off methods and renders their arguments as text. The recognition rule — namespace prefix or a known simple name — now lives in ONE place (`constraintOf`) that both parsers feed, with a bridge overload for the un-ported `EntityMetadataGenerator`; only the argument *rendering* differs per parser, because the LST has one annotation node where JavaParser had four and tells the forms apart by the argument list. That discrimination is the trap: a single `J.Empty` means `@NotNull()` (no text), and treating it as a value yields garbage in the emitted constraint.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.Annotation`, `org.openrewrite.java.tree.J.Assignment`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 316

**JavaParser surface on disk**:

- 3 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser had four distinct node types for the four forms, so the old code branched on`
  - `// The LST collapses JavaParser's ArrayCreationExpr and ArrayInitializerExpr`
  - `// JavaParser bridge path cannot disagree about what an attribute means. The LST path`

**Migration notes**:

Reads `@View` attributes: annotation arguments, array initialisers, class literals and enum-constant references. `ClassExpr` is the weakest mapping in the table (a class literal is modelled in expression position rather than by a dedicated node) — confirm that one against the LST reference before porting the rest, because several attributes are class-valued.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.Annotation`, `org.openrewrite.java.tree.J.Assignment`, `org.openrewrite.java.tree.Expression`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 214

**JavaParser surface on disk**:

- 2 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `*       made this stronger rather than merely different: the JavaParser version asked`
  - `// JavaParser version set up the lexical printer and then fell back to `cu.toString()` whenever`

**Migration notes**:

The one generator whose output is *added to an existing file*, so it is the clearest test of the print path: `LexicalPreservingPrinter` disappears because the LST preserves surrounding formatting by construction. The `ThisExpr`/`ObjectCreationExpr`/`ReturnStmt`/`BlockStmt` construction of the entry-point method becomes immutable node construction.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.NewClass`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java
```

### `java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 130

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6: the JavaParser walk is gone. The type declarations and their line spans come from`

**Migration notes**:

Analyses a file for context: types, records and members. Record handling collapses into the kind test, and `Node`-typed traversal should become a `JavaIsoVisitor` rather than a hand-rolled parent walk.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.J`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl java-watch-agent -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java
```

### `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 71

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>One behaviour changed with the move, deliberately: the JavaParser version returned`

**Migration notes**:

One of three near-identical tools (Accessor/Builder/Constructor) sharing a shape: parse, find a class, read fields, add methods. Port the trio together and prove the shared helper once; `BodyDeclaration` has no LST supertype, so each filter becomes a predicate over `J`.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.VariableDeclarations`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl java-watch-agent -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java
```

### `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 66

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* rather than re-printed). The idempotence the JavaParser version got from looking for an existing`

**Migration notes**:

See AccessorGenerator — identical shape, port as one unit with it.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.VariableDeclarations`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl java-watch-agent -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java
```

### `jwa-builder/src/main/java/hr/hrg/watch2/builder/SourceSplicer.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 325

**JavaParser surface on disk**:

- 2 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The JavaParser implementation built the builder by mutating an AST — adding methods, removing`
  - `* <p><strong>This is what makes the operation idempotent.</strong> The JavaParser version updated a`

**Migration notes**:

Added when jwa-builder was ported: it is what replaced the AST mutation. The processor reads a tree and never writes one, so the builder is generated as text and spliced into the record. The contract is exact and pinned by RecordBuilderFormattingTest: entry points, then the nested Builder class whose members are grouped fields → build() → setters, with indentation read from the record itself so a nested record is indented correctly. Replacing the *previous* builder (recognised by name and structure) is what makes the operation idempotent without a marker comment.

**OpenRewrite classes involved**: `(none — generates text)`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/main/java/hr/hrg/watch2/builder/SourceSplicer.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/main/java/hr/hrg/watch2/builder/SourceSplicer.java
```

### `jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 170

**JavaParser surface on disk**:

- 2 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6 of the rewrite-migration plan: this class used to hold its own JavaParser configured at`
  - `// answer here: nothing to sync, and the save itself is already on disk. The JavaParser version`

**Migration notes**:

The LSP text-document service. The plan lists this under the module `webview-jetbrains`, which does not exist; the real path is `jwa-sidecar`. It needs only record detection, so the parse itself is the whole port — but it is on an interactive path, where a parser built per request would be a latency regression. Keep the parser shared and reset it between parse sets.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.SourceFile`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-sidecar -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: low · **Detected**: comment-only · **Lines**: 1133

**JavaParser surface on disk**:

- 31 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* from the JavaParser code being replaced:</p>`
  - `*       Every JavaParser {@code findAll} in this module becomes the same fifteen-line`
  - `* <p>Replaces JavaParser's`
  - `* <p>Includes nested types. The old JavaParser code that read {@code cu.getTypes()} saw only`
  - `* The type declarations directly under the compilation unit — the set JavaParser's`
  - `* <p>Replaces the three {@code findAll} walks JavaParser needed — one for`
  - …and 25 more

**Migration notes**:

Added by the first porting pass, and the reason the rest of it was tractable. Every ported rule needs the same three things — a `findAll` replacement, a package name, and a kind test — and `J.ClassDeclaration` covers five kinds, so the kind test is where a port silently starts matching records and enums. It deliberately builds nothing: the generator port must stay free to mix `JavaTemplate` with `withXxx` construction, and a helper that owned construction would force that choice early. Public because the validation rules are the heaviest users and live in a subpackage. Two shapes it absorbs that a mechanical port gets wrong silently: an interface's `extends` clause is held in `getImplements()`, not `getExtends()`; and a supertype is an Identifier when bare but a ParameterizedType when generic.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaIsoVisitor`, `org.openrewrite.java.tree.J`, `org.openrewrite.java.tree.TypeTree`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: low · **Detected**: comment-only · **Lines**: 74

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* instead of a JavaParser {@code CompilationUnit}, because the read that produces them is`

**Migration notes**:

The rule interface itself — one `CompilationUnit` parameter. Changing this signature changes every rule, so port it at the same time as the first rule, not before.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.CompilationUnit`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: low · **Detected**: comment-only · **Lines**: 59

**JavaParser surface on disk**:

- 3 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>This class was {@code JavaParserTool}. It is no longer a JavaParser tool — the whole point of the`
  - `* greps for {@code JavaParser} to find what is left and lands here. It is the only class in this`
  - `* <p>Two JavaParser behaviours are gone with the port, and both change the contract:</p>`

**Migration notes**:

Ported and renamed in the first pass — it was `JavaParserTool`, which is no longer a truthful name for a class that builds OpenRewrite trees, and a grep for `JavaParser` landing on the tooling's own query helper is a trap. The port also tightened one behaviour: the old code called `result.getResult().orElseThrow(...)`, which accepted a *partial* tree (the F-34 hazard); the read now goes through `SourceReader`, so an unreadable file fails explicitly. It is kept rather than deleted because it is a public entry point of the module, even though nothing in this repository calls it.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.SourceFile`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java
```

### `jwa-builder/src/main/java/hr/hrg/watch2/builder/LineLookup.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: low · **Detected**: comment-only · **Lines**: 164

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>This module selects a record by proximity to the cursor line, which the JavaParser version did`

**Migration notes**:

Added when jwa-builder was ported: the engine selects a record by proximity to the cursor line, which JavaParser did with `Range` and the LST cannot do at all (a node exposes no position). The line comes from javac, which costs no dependency because OpenRewrite’s Java parser is a javac front end. Same recipe as the tooling module’s JavaSyntaxCheck; the matching rule and the trap that cost three attempts are in MIGRATION-CAVEATS.md § 4.1.

**OpenRewrite classes involved**: `com.sun.source.util.JavacTask`, `com.sun.source.tree.LineMap`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/main/java/hr/hrg/watch2/builder/LineLookup.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/main/java/hr/hrg/watch2/builder/LineLookup.java
```

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java`

**Status**: `[x]` complete · **Priority**: medium · **Risk**: medium · **Detected**: comment-only · **Lines**: 182

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6: read through the LST. JavaParser modelled each constant as its own member declaration;`

**Migration notes**:

The round-trip gate for enum compaction, and the test that should catch a constant-order port error. Keep it passing at every step of the EnumCompactionCli port rather than porting it afterwards.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.tree.J.EnumValue`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java
```

### `jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java`

**Status**: `[x]` complete · **Priority**: medium · **Risk**: medium · **Detected**: comment-only · **Lines**: 216

**JavaParser surface on disk**:

- 3 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The old version built a JavaParser {@code JavaParser}, parsed a snippet, called`
  - `* <p>The JavaParser version needed {@code LexicalPreservingPrinter} plus a line-by-line re-indent`
  - `* <p>These replaced a JavaParser walk in {@code JwaTextDocumentService}: which records carry the`

**Migration notes**:

Ported. It no longer parses anything itself — the processor reads a tree but never mutates one, and the output is generated text rather than a printed tree — so the test now asserts on the text a developer’s file is given. Every behavioural assertion is preserved: obsolete members gone, member grouping (fields, `build()`, setters) and indentation relative to the record. One assertion was tightened rather than weakened: the nested-record case now pins the indent as `record indent + one engine step` instead of `>= 4`, because the old threshold happened to pass for a 2-space engine while asserting nothing about the relationship.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java
```

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java`

**Status**: `[x]` complete · **Priority**: medium · **Risk**: low · **Detected**: comment-only · **Lines**: 347

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Phase 6: the LST replaces JavaParser's {@code findAll(EnumConstantDeclaration.class)}, and the`

**Migration notes**:

Fully-qualified `CompilationUnit` / `EnumConstantDeclaration` uses with no imports, including a `cu.findAll(...)` call that becomes a visitor walk.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.EnumValue`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java
```

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java`

**Status**: `[x]` complete · **Priority**: medium · **Risk**: low · **Detected**: comment-only · **Lines**: 152

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>The test used to build its fixtures with JavaParser and hand the reader a JavaParser`

**Migration notes**:

Test-side port; follows the main-source annotation mapping verbatim.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.tree.J.Annotation`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java
```

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGeneratorTest.java`

**Status**: `[x]` complete · **Priority**: medium · **Risk**: low · **Detected**: comment-only · **Lines**: 113

**JavaParser surface on disk**:

- 1 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* "nothing <em>else</em> changed". The JavaParser implementation could not guarantee that: it fell back`

**Migration notes**:

Added with the `ViewInterfaceGenerator` port. That class had no direct coverage — it is reached only from `EntityMetadataGenerator` — so a port could not be verified at all without a test, and the property that most needed pinning is not "a method appears" but "nothing else changed". The three cases: byte-preservation of hand-written formatting (including a comment the generator must not disturb), idempotence when the entry point is already present, and insertion inside the interface rather than after a trailing type. The last one is what a naive "find the last brace" implementation would fail.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGeneratorTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGeneratorTest.java
```

## Exempt (9)

Files that legitimately keep a JavaParser reference. Each states why, and what
removes the exemption — an allowlist entry without an exit condition is a hole.

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JdkImportSupport.java`

**Reason**: One prose mention, explaining why a general solution is out of scope: "The general solution is JavaParser's symbol solver". It names the library as the thing *not* being used, so it carries no dependency.

**Deferred to**: Remove the sentence when JavaParser leaves the tree: the symbol solver it declines to use will no longer exist to decline.

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/FieldConstraint.java`

**Reason**: One prose mention in a doc comment ("read with JavaParser exactly as …"), describing how the constraint annotations are interpreted. It does not name a JavaParser type.

**Deferred to**: Reword to name the LST instead, in whichever edit next touches that comment.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DependencyBoundaryTest.java`

**Reason**: Mentions JavaParser only in prose, now including the retirement note for `javaParserIsPinnedOnceInTheRootPom` — the test that policed the dependency boundary and was deleted with the dependency. The remaining assertions are about Jakarta Validation and Jackson, which are untouched by this migration.

**Deferred to**: Never — a comment naming the dependency it policed is correct.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DivergenceKindTest.java`

**Reason**: One prose mention explaining a platform-dependent expectation: "The emitter prints through JavaParser, which uses the platform line separator". It names the printer as the cause of a Windows-vs-Linux difference.

**Deferred to**: Reword when the emitter prints through the LST: the property under test (the platform line separator) stays, only its cause changes.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ParseGuardTest.java`

**Reason**: One prose mention in a comment recording why the guard exists: "JavaParser returns a PARTIAL unit for this, which is how the …". It documents the JavaParser behaviour the fail-safe was written against.

**Deferred to**: Stays while the guard exists: the comment is the record of the failure mode (a partial unit mistaken for a readable file) that the port must not reintroduce. Update the wording when the parser changes, not the test.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/SourceReaderTest.java`

**Reason**: Re-expressed when the dependency went (2026-09-22, end of Phase 6): the language-level assertion against `ParserConfiguration.LanguageLevel.JAVA_25` is gone, and what remains is the property it guarded — a clean file reads and a file whose enum hides a syntax error does not (`aCleanFileReadsAndARecoveredSyntaxErrorDoesNot`). The file still mentions the library by name, in the javadoc that records what was removed and why, so the scan still reports it.

**Deferred to**: Never for the javadoc, which is the provenance of the current contract. The *guard* it used to be was re-expressed rather than deleted, so nothing here is waiting on a port.

### `jwa-builder/src/main/java/hr/hrg/watch2/builder/ClassMemberProcessor.java`

**Reason**: The only `JavaParser` this file names is OpenRewrite's own `org.openrewrite.java.JavaParser` — the trap MIGRATION-CAVEATS.md opens with, and the reason the scanner's bare-name test cannot be taken at face value. There is no `com.github.javaparser` reference here, and no dependency on one.

**Deferred to**: Never: the name is the ported API's, and a file that parses with OpenRewrite has to say so. It is recorded rather than filtered because the scanner should keep reporting the collision — a rename in a future OpenRewrite release is exactly the event this entry makes visible.

### `jwa-builder/src/test/java/hr/hrg/watch2/builder/ClassMemberProcessorTest.java`

**Reason**: Same as its subject: the JavaParser mentions are OpenRewrite's class and prose about what the port replaced. No `com.github.javaparser` reference.

**Deferred to**: Never, for the same reason as ClassMemberProcessor.

### `merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java`

**Reason**: The reference port: it is the file this migration’s `verified` mappings are evidenced from. Its bare-name mentions are an `{@link JavaParser}` that resolves to `org.openrewrite.java.JavaParser`, plus prose explaining the parser-reuse rule it had to discover (a JavaParser caches parsed sources and refuses a second set declaring the same FQNs).

**Deferred to**: Never. This is the destination, not the source: it is what the queue is being ported towards.

## Outside the queue (7)

Documentation and archive. These files *are* the migration’s own notes or a
historical record, so their JavaParser references are the subject matter.

| File | Why excluded |
| --- | --- |
| `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/CompilationUnitAdapter.java` | lives under doc/ |
| `doc/brainstorm/rewrite-migration/02-utilities/util/AstPrinter.java` | lives under doc/ |
| `doc/brainstorm/rewrite-migration/02-utilities/util/NodeTraversal.java` | lives under doc/ |
| `doc/brainstorm/rewrite-migration/02-utilities/util/TypeUtils.java` | lives under doc/ |
| `plans/rewrite-migration/01-foundation/api-compatibility/AstManipulator.java` | lives under plans/ |
| `plans/rewrite-migration/01-foundation/api-compatibility/CompilationUnitAdapter.java` | lives under plans/ |
| `plans/rewrite-migration/01-foundation/tests/ApiCompatibilityTests.java` | lives under plans/ |

## Corrections to the plan’s file list

`plans/rewrite-migration/06-Migration-Checklist.md` § Files to Migrate names
paths that do not exist and omits files that do. Following it literally migrates
the wrong set. The differences:

| Plan says | Reality |
| --- | --- |
| `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/HttpBridgeStartupActivity.java` | does not exist |
| `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/JwaTextDocumentService.java` | the real file is `jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java` |
| `hipster-entity-tooling/src/test/java/.../validation/JavaParserTool.java` | the real `JavaParserTool` is main-source, at `.../tooling/validation/JavaParserTool.java` |
| `validation/EnumCompactionCliTest.java` listed twice | one file: `hipster-entity-tooling/src/test/java/.../validation/EnumCompactionCliTest.java` |
| `project-automation` — "all files using JavaParser" | the `hr.hrg.rewrite` package uses no JavaParser and does not compile; it needs Phase-0 repair, not a port |
| (not listed) | seven files use JavaParser **fully qualified with no import**: `ViewBuilderGenerator.java` (main), `meta/InterfaceInfo.java` (main),
| | `AddonAndInheritanceTest.java`, `CompactionRoundTripTest.java`, `SourceReaderTest.java`, `EnumCompactionCliTest.java`, and the `test` half of `DependencyBoundaryTest.java` |

## Verification

```sh
bun run scripts/rewrite-migration/verify-migration.js          # the gate
bun run scripts/rewrite-migration/generate-checklist.js --check # this file is current
cmd /c "scripts\mvn-jdk25.cmd -o -pl <module> -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
```

The plan’s Check 1 (`grep -r "import com.github.javaparser" | grep -v test |
grep -v comment`) is **not** used, for two reasons recorded in
`scan-remaining-javafiles.js`: the filter drops every test file from the count,
and the pattern misses fully-qualified uses while matching
`org.openrewrite.java.JavaParser`, which is a different library’s class.

