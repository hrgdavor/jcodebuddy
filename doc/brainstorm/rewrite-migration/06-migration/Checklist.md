# Phase 6 — Migration Checklist

> **Generated file.** Written by
> `bun run scripts/rewrite-migration/generate-checklist.js --write`.
> Do not edit by hand: edit `scripts/rewrite-migration/curation.js` for what a file
> needs, or `doc/brainstorm/rewrite-migration/06-migration/tracker.md` for its status, and regenerate.
>
> Regenerate after any change to the Java tree, or `verify-migration.js` will fail
> on the disagreement.

Generated: 2026-09-21

## First pass — completed

The read-only spine is ported: **11 files settled**, 2 in progress, 24 remaining. `hipster-entity-tooling` builds and its full test suite is green.

Three API findings from that pass are recorded in `MIGRATION-GUIDE.md` § 4, and each one matters for every file still to be ported because each is silent:

- **§ 4.5** OpenRewrite *recovers* from syntax errors — no throw, no `ParseExceptionResult` marker, still a well-formed `J.CompilationUnit`. The check the guide previously prescribed cannot detect this, so `JavaSyntaxCheck` asks javac instead.
- **§ 4.6** a reused parser must be `reset()` between parse sets, or the second read of any type reports "unparseable".
- **§ 4.7** an interface's `extends` clause is held in `getImplements()`, not `getExtends()`; reading the latter finds no supertype for any interface.

Read [`MIGRATION-CAVEATS.md`](MIGRATION-CAVEATS.md) **before** porting a file: it lists the five ways a port fails silently, the model differences that force a rewrite rather than a rename, and the open gaps.

## Phase 6 state

| Measure | Count |
| --- | --- |
| Files in the migration queue | 35 |
| Settled (complete or exempt) | 11 (31%) |
| Remaining | 24 |
| Still importing JavaParser | 19 |
| Unclassified by curation | 0 |
| JavaParser import lines to remove | 159 |

## Phase-0 prerequisites

These are not part of the queue: they are the conditions that must hold
before a port can be verified. Each was found by running the tooling, and each
is real in the tree today.

| Id | Prerequisite | Done |
| --- | --- | --- |
| P0-1 | Repair project-automation staging code so the module compiles | **no** |
| P0-2 | Re-point the hr.hrg.rewrite package at the real OpenRewrite API | **no** |
| P0-3 | Add the OpenRewrite dependency set to project-automation | **no** |
| P0-4 | Establish the clean-build baseline for every module in the queue | **no** |
| P0-5 | Reconcile the plan’s file list with the tree | **no** |

### P0-1 — Repair project-automation staging code so the module compiles

A clean compile of the module fails. Stale class files made a plain `compile` report success ("Nothing to compile - all classes are up to date"), so the breakage is invisible until someone cleans — the same stale-class hazard the repo records as note F-47.

*Evidence:* project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteValidationGenerator.java:83 — `sb.append("}")\n\n");` is an unbalanced close paren; the escape never reaches the string. OpenRewriteFieldBoilerplateGenerator.java:131 — `sb.append("    public String ").append(property.name()).append "() {\n");` is missing the parens on the second `.append`.

### P0-2 — Re-point the hr.hrg.rewrite package at the real OpenRewrite API

Behind P0-1’s syntax errors the package references types that do not exist: `hr.hrg.hipster.entity.tooling.TypeTree`, `InterfaceTree`, `MethodTree`, `ClassTree`. The real names are `org.openrewrite.java.tree.TypeTree` and the `J.*` node types; there is no `MethodTree` at all. It also calls `SourceReader.readText()` (package-private) and `getTypes()`/`addMember()` on JavaParser’s `CompilationUnit` as though it were an LST.

*Evidence:* OpenRewriteViewInterfaceGenerator.java:141-221, OpenRewriteViewBuilderGenerator.java:132, plus the same pattern across hr/hrg/rewrite/validation/*.

### P0-3 — Add the OpenRewrite dependency set to project-automation

The module declares no `org.openrewrite` dependency at all, so even once P0-2 is fixed the `hr.hrg.rewrite` package has no OpenRewrite API to compile against. Without this, Phase 5’s automation engine cannot be wired into the module Phase 6 depends on.

*Evidence:* project-automation/pom.xml declares javaparser-core (line 42) and no OpenRewrite artifact.

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

## The queue (35 files, in work order)

Ordered by priority, then by risk. See `## Per-file detail` for the notes.

| Status | Priority | Risk | Imports | File |
| --- | --- | --- | --- | --- |
| `[ ]` not-started | high | high | 11 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java` |
| `[ ]` not-started | high | high | 12 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java` |
| `[ ]` not-started | high | high | 35 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java` |
| `[ ]` not-started | high | high | 20 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java` |
| `[~]` in-progress | high | high | 12 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java` |
| `[x]` complete | high | high | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java` |
| `[ ]` not-started | high | high | 5 | `jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java` |
| `[ ]` not-started | high | high | 9 | `jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java` |
| `[~]` in-progress | high | medium | 2 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java` |
| `[ ]` not-started | high | medium | 3 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java` |
| `[ ]` not-started | high | medium | 3 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java` |
| `[x]` complete | high | medium | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java` |
| `[ ]` not-started | high | medium | 10 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java` |
| `[ ]` not-started | high | medium | 6 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java` |
| `[ ]` not-started | high | medium | 6 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java` |
| `[ ]` not-started | high | medium | 6 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java` |
| `[ ]` not-started | high | medium | 6 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java` |
| `[ ]` not-started | high | medium | 3 | `jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java` |
| `[x]` complete | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java` |
| `[x]` complete | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java` |
| `[x]` complete | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java` |
| `[ ]` not-started | high | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java` |
| `[ ]` not-started | high | low | 2 | `java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/JavaParserFactory.java` |
| `[ ]` not-started | medium | medium | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java` |
| `[ ]` not-started | medium | medium | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java` |
| `[ ]` not-started | medium | medium | 4 | `jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java` |
| `[ ]` not-started | medium | low | 0 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java` |
| `[ ]` not-started | medium | low | 4 | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java` |
| `[ ]` not-started | low | low | 0 | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/InterfaceInfo.java` |

## Per-file detail

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 400

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.Range` — **inferred**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.Node` — **inferred**
- `com.github.javaparser.ast.body.BodyDeclaration` — **inferred**
- `com.github.javaparser.ast.body.ConstructorDeclaration` — **inferred**
- `com.github.javaparser.ast.body.EnumConstantDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.body.TypeDeclaration` — **verified**
- `com.github.javaparser.ast.comments.Comment` — **inferred**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 3488

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.body.TypeDeclaration` — **verified**
- `com.github.javaparser.ast.expr.AnnotationExpr` — **inferred**
- `com.github.javaparser.ast.expr.MemberValuePair` — **inferred**
- `com.github.javaparser.ast.expr.NameExpr` — **inferred**
- `com.github.javaparser.ast.expr.SimpleName` — **inferred**
- `com.github.javaparser.ast.expr.StringLiteralExpr` — **inferred**
- `com.github.javaparser.ast.type.ClassOrInterfaceType` — **inferred**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 1008

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.NodeList` — **inferred**
- `com.github.javaparser.ast.Modifier` — **inferred**
- `com.github.javaparser.ast.body.ConstructorDeclaration` — **inferred**
- `com.github.javaparser.ast.body.EnumConstantDeclaration` — **verified**
- `com.github.javaparser.ast.body.EnumDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.ArrayCreationLevel` — **inferred**
- `com.github.javaparser.ast.body.VariableDeclarator` — **inferred**
- `com.github.javaparser.ast.expr.ArrayCreationExpr` — **inferred**
- `com.github.javaparser.ast.expr.ArrayInitializerExpr` — **inferred**
- `com.github.javaparser.ast.expr.AssignExpr` — **inferred**
- `com.github.javaparser.ast.expr.BinaryExpr` — **inferred**
- `com.github.javaparser.ast.expr.ClassExpr` — **inferred**
- `com.github.javaparser.ast.expr.Expression` — **inferred**
- `com.github.javaparser.ast.expr.FieldAccessExpr` — **inferred**
- `com.github.javaparser.ast.expr.MethodCallExpr` — **inferred**
- `com.github.javaparser.ast.expr.NameExpr` — **inferred**
- `com.github.javaparser.ast.expr.NullLiteralExpr` — **inferred**
- `com.github.javaparser.ast.expr.ObjectCreationExpr` — **inferred**
- `com.github.javaparser.ast.expr.StringLiteralExpr` — **inferred**
- `com.github.javaparser.ast.expr.SwitchExpr` — **inferred**
- `com.github.javaparser.ast.expr.ThisExpr` — **inferred**
- `com.github.javaparser.ast.stmt.BlockStmt` — **inferred**
- `com.github.javaparser.ast.stmt.ExpressionStmt` — **inferred**
- `com.github.javaparser.ast.stmt.IfStmt` — **inferred**
- `com.github.javaparser.ast.stmt.ReturnStmt` — **inferred**
- `com.github.javaparser.ast.stmt.Statement` — **inferred**
- `com.github.javaparser.ast.stmt.SwitchEntry` — **inferred**
- `com.github.javaparser.ast.type.ClassOrInterfaceType` — **inferred**
- `com.github.javaparser.ast.type.Type` — **inferred**
- `com.github.javaparser.ast.type.WildcardType` — **inferred**
- `com.github.javaparser.printer.configuration.PrettyPrinterConfiguration` — **inferred**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 612

**JavaParser surface on disk**:

- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.Node` — **inferred**
- `com.github.javaparser.ast.body.AnnotationDeclaration` — **inferred**
- `com.github.javaparser.ast.body.BodyDeclaration` — **inferred**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.EnumConstantDeclaration` — **verified**
- `com.github.javaparser.ast.body.EnumDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.body.Parameter` — **verified**
- `com.github.javaparser.ast.body.RecordDeclaration` — **inferred**
- `com.github.javaparser.ast.body.TypeDeclaration` — **verified**
- `com.github.javaparser.ast.body.VariableDeclarator` — **inferred**
- `com.github.javaparser.ast.comments.Comment` — **inferred**
- `com.github.javaparser.ast.expr.AnnotationExpr` — **inferred**
- `com.github.javaparser.ast.expr.FieldAccessExpr` — **inferred**
- `com.github.javaparser.ast.expr.NameExpr` — **inferred**
- `com.github.javaparser.ast.expr.StringLiteralExpr` — **inferred**
- `com.github.javaparser.ast.stmt.Statement` — **inferred**
- `com.github.javaparser.ast.stmt.SwitchEntry` — **inferred**

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

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: qualified-only · **Lines**: 463

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `private static final com.github.javaparser.JavaParser JPARSER =`
  - `new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()`
  - `.setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_25));`
  - `public static com.github.javaparser.JavaParser portingParser() {`
  - `com.github.javaparser.ParseResult<com.github.javaparser.ast.CompilationUnit> parsed =`
  - `public record ReadJp(com.github.javaparser.ast.CompilationUnit unit, boolean unparseable) {`
  - `public static com.github.javaparser.ast.CompilationUnit readUnitJp(Path file) throws IOException {`
  - `public static com.github.javaparser.ast.CompilationUnit readUnitJpText(String source) {`
- 22 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser was <strong>error tolerant</strong>: for genuinely broken source it still returned a`
  - `* <p>This class now parses with OpenRewrite instead of JavaParser. The <strong>contract is unchanged`
  - `*   <li><strong>Two failure channels, not one.</strong> JavaParser reported failure through a single`
  - `*       checked: it <em>throws</em> where JavaParser was tolerant, and a tree that is not a`
  - `*       JavaParser too old for {@code sealed} made five example files generate nothing, with no error`
  - `* repeat per file. Unlike JavaParser, OpenRewrite's parser carries no language-level setting — the`
  - …and 16 more

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

**Status**: `[~]` in-progress · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 404

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.EnumConstantDeclaration` — **verified**
- `com.github.javaparser.ast.body.EnumDeclaration` — **verified**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.expr.BooleanLiteralExpr` — **inferred**
- `com.github.javaparser.ast.expr.StringLiteralExpr` — **inferred**
- `com.github.javaparser.ast.stmt.ReturnStmt` — **inferred**
- `com.github.javaparser.ast.stmt.SwitchEntry` — **inferred**
- `com.github.javaparser.ast.stmt.SwitchStmt` — **inferred**
- `com.github.javaparser.printer.configuration.PrettyPrinterConfiguration` — **inferred**

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

**Status**: `[x]` complete · **Priority**: high · **Risk**: high · **Detected**: qualified-only · **Lines**: 395

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `for (com.github.javaparser.ast.comments.Comment comment : jpComments(cu)) {`
  - `private static List<com.github.javaparser.ast.comments.Comment> jpComments(J.CompilationUnit cu) {`
  - `List<com.github.javaparser.ast.comments.Comment> comments = new ArrayList<>();`
  - `comments.add(new com.github.javaparser.ast.comments.LineComment(textComment.getText()));`
  - `public static HeaderConfig readHeader(com.github.javaparser.ast.CompilationUnit cu) {`
  - `for (com.github.javaparser.ast.comments.Comment comment : cu.getAllComments()) {`
  - `private static Optional<String> configLine(com.github.javaparser.ast.comments.Comment comment) {`
- 8 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser had a real comment model, {@code cu.getAllComments()}, and the old code leaned on a`
  - `* subtlety of it: JavaParser attaches the last comment of a run to the <em>following</em>`
  - `* <p>JavaParser had a comment model, so {@code cu.getAllComments()} returned both header lines and`
  - `* <p>Kept for the call sites that still hold a JavaParser tree — {@code FieldBoilerplateGenerator}`
  - `* {@code getAllComments()} and the JavaParser attachment rule (the last comment of a run attaches`
  - `* @param cu a JavaParser compilation unit, from a call site that has not yet been ported`
  - …and 2 more

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 106

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParserConfiguration` — **inferred**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.RecordDeclaration` — **inferred**
- `com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter` — **verified**

**Migration notes**:

> **Why this is not mechanical**: Lexical preservation is the file’s entire purpose; the LST guarantees it differently, and `requirePrintEqualsInput` will reject non-idempotent output.

Removes a `LexicalPreservingPrinter` round trip. The replacement guarantee is weaker in one respect and stronger in another: weaker because the LST printer reformats anything it believes it owns, stronger because OpenRewrite verifies print-idempotency and fails loudly rather than silently reformatting. Keep that verification on here — this is generation, not fragment analysis.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.tree.J.ClassDeclaration`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java
```

### `jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: high · **Detected**: imports · **Lines**: 166

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.Modifier` — **inferred**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.body.RecordDeclaration` — **inferred**
- `com.github.javaparser.ast.stmt.BlockStmt` — **inferred**

**Migration notes**:

> **Why this is not mechanical**: It is a build-time processor: its input is generated record source and its output is edited source, so a port that reformats output breaks the build for every consumer of the module.

Record support is the crux: `RecordDeclaration` becomes a kind-Record `J.ClassDeclaration` whose components live on the primary constructor. Depends on hipster-entity-tooling only for tooling classes, not for the parser, so it needs its own `rewrite-java-25` dependency.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.tree.J.ClassDeclaration`, `org.openrewrite.java.tree.J.MethodDeclaration`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: qualified-only · **Lines**: 282

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration decl,`
  - `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration decl) {`
  - `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration decl,`
  - `for (com.github.javaparser.ast.body.BodyDeclaration<?> member : decl.getMembers()) {`
  - `if (!(member instanceof com.github.javaparser.ast.body.RecordDeclaration record)) {`
  - `.map(com.github.javaparser.ast.body.Parameter::getNameAsString)`
  - `for (com.github.javaparser.ast.body.BodyDeclaration<?> member : decl.getMembers()) {`
  - `if (member instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration nested`
- 4 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>Two kind tests replace two JavaParser types, and both are the kind of change that keeps`
  - `* The JavaParser-tree overload, for the queue files that are <strong>not yet ported</strong>.`
  - `* <p>The shape census maps exactly: JavaParser's {@code RecordDeclaration} is the LST's`
  - `* @param decl a JavaParser declaration, from a call site that has not yet been ported`

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

**Status**: `[~]` in-progress · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 1031

**JavaParser surface on disk**:

- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.TypeDeclaration` — **verified**

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

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: qualified-only · **Lines**: 231

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `public static TypeFacts of(com.github.javaparser.ast.body.TypeDeclaration<?> declaration,`
  - `.flatMap(com.github.javaparser.ast.CompilationUnit::getPackageDeclaration)`
  - `for (com.github.javaparser.ast.Modifier modifier : declaration.getModifiers()) {`
  - `private static String kindOfJp(com.github.javaparser.ast.body.TypeDeclaration<?> declaration) {`
  - `if (declaration instanceof com.github.javaparser.ast.body.EnumDeclaration) {`
  - `if (declaration instanceof com.github.javaparser.ast.body.RecordDeclaration) {`
  - `if (declaration instanceof com.github.javaparser.ast.body.AnnotationDeclaration) {`
  - `if (declaration instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classOrInterface) {`
- 6 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* the JavaParser path in the call sites that have not yet been ported — the latter by way of`
  - `* <p>Phase 6: the enclosing chain is supplied rather than discovered. JavaParser's`
  - `* have two spellings of a nested type's FQN — so this resolves the JavaParser node's own local`
  - `* chain is supplied by the caller's own recursion, which is how the JavaParser side already walked`
  - `* <p>Deleted with the last JavaParser caller.</p>`
  - `* The kind of a JavaParser declaration, in DEC-029's vocabulary.`

**Migration notes**:

Feeds DEC-029’s class index: the per-type kind and modifiers written into `.jcodebuddy/index/classes.json`. The index is keyed by FQN and consumers read `kind` as a string, so the port must preserve the *spelling* of every kind it reports even though the LST discriminates differently. A changed spelling is a silently broken index, not a compile error. Ported: the FQN comes from the cursor-captured enclosing chain, and `non-sealed` is rendered explicitly rather than via the enum constant’s `toString()` — the latter would emit `NON_SEALED` and break DEC-029’s vocabulary for exactly the two hyphenated keywords. One caveat: `line` is -1 pending MIGRATION-CAVEATS.md § 4.1, because the LST exposes no positions.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J`, `org.openrewrite.java.tree.J.Modifier`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: comment-only · **Lines**: 264

**JavaParser surface on disk**:

- 4 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>{@link SourceReader}'s whole reason for existing is the fail-safe recorded as F-34: JavaParser is`
  - `* and DEC-028 verifies every link against the line a member is declared on. JavaParser answered with`
  - `* Re-parsing with JavaParser would work but re-introduces the dependency this migration exists to`
  - `* <p>Replaces JavaParser's {@code getName().getBegin().line}, which is why this class exists at all`

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

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 210

**JavaParser surface on disk**:

- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 556

**JavaParser surface on disk**:

- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.expr.AnnotationExpr` — **inferred**
- `com.github.javaparser.ast.expr.MemberValuePair` — **inferred**

**Migration notes**:

Reads constraint annotations off methods. Note `J.Annotation.getArguments()` normalises the single-element form `@Foo(Bar.class)` into an assignment named `value`, where JavaParser leaves the name implicit — a reader keyed by pair name must handle that or it will miss the common single-argument case.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.Annotation`, `org.openrewrite.java.tree.J.Assignment`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java
```

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java`

**Status**: `[x]` complete · **Priority**: high · **Risk**: medium · **Detected**: qualified-only · **Lines**: 382

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `public static ViewAttributes read(com.github.javaparser.ast.expr.AnnotationExpr view) {`
  - `public static Parsed parse(com.github.javaparser.ast.expr.AnnotationExpr view) {`
  - `for (com.github.javaparser.ast.expr.MemberValuePair pair`
- 9 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* <p>JavaParser had four distinct node types for the four forms, so the old code branched on`
  - `// The LST collapses JavaParser's ArrayCreationExpr and ArrayInitializerExpr`
  - `* The JavaParser-tree overload, for the queue files that are <strong>not yet ported</strong>.`
  - `* {@code EntityMetadataGenerator} (not yet ported, JavaParser) both read {@code @View} through`
  - `* It is deleted with the last JavaParser caller — {@code EntityMetadataGenerator} — and the`
  - `* @param view a JavaParser annotation, from a call site that has not yet been ported`
  - …and 3 more

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 227

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.MethodDeclaration` — **verified**
- `com.github.javaparser.ast.body.TypeDeclaration` — **verified**
- `com.github.javaparser.ast.expr.ObjectCreationExpr` — **inferred**
- `com.github.javaparser.ast.stmt.BlockStmt` — **inferred**
- `com.github.javaparser.ast.stmt.ReturnStmt` — **inferred**
- `com.github.javaparser.ast.type.ClassOrInterfaceType` — **inferred**
- `com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter` — **verified**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 151

**JavaParser surface on disk**:

- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.Node` — **inferred**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.RecordDeclaration` — **inferred**
- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 112

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.BodyDeclaration` — **inferred**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**

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

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 109

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.BodyDeclaration` — **inferred**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**

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

### `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 91

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParseResult` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.BodyDeclaration` — **inferred**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.body.FieldDeclaration` — **inferred**

**Migration notes**:

See AccessorGenerator. Constructors are `J.MethodDeclaration` with `isConstructor()`, so any `instanceof ConstructorDeclaration` becomes that test.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.java.tree.J.VariableDeclarations`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl java-watch-agent -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java
```

### `jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: medium · **Detected**: imports · **Lines**: 176

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParserConfiguration` — **inferred**
- `com.github.javaparser.ast.body.RecordDeclaration` — **inferred**

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

**Status**: `[x]` complete · **Priority**: high · **Risk**: low · **Detected**: comment-only · **Lines**: 544

**JavaParser surface on disk**:

- 20 bare-name mention(s) — `JavaParser` or `javaparser`
  with no package qualifier. Prose, artifact ids and test assertions:
  - `* from the JavaParser code being replaced:</p>`
  - `*       Every JavaParser {@code findAll} in this module becomes the same fifteen-line`
  - `* <p>Replaces JavaParser's`
  - `* <p>Includes nested types. The old JavaParser code that read {@code cu.getTypes()} saw only`
  - `* The type declarations directly under the compilation unit — the set JavaParser's`
  - `* <p>Note the shape this replaces. JavaParser expressed "is an interface" as`
  - …and 14 more

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

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: low · **Detected**: qualified-only · **Lines**: 302

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `com.github.javaparser.ast.CompilationUnit cu = read.unit();`
  - `for (com.github.javaparser.ast.body.MethodDeclaration method`
  - `: cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {`

**Migration notes**:

No imports at all: its three references are fully qualified inline (`com.github.javaparser.ast.CompilationUnit cu = read.unit();`), so a port driven by the import list would miss this file entirely. That is the case the scanner’s `qualified-only` classification exists to catch.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.MethodDeclaration`, `org.openrewrite.SourceFile`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java
```

### `java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/JavaParserFactory.java`

**Status**: `[ ]` not-started · **Priority**: high · **Risk**: low · **Detected**: imports · **Lines**: 21

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParserConfiguration` — **inferred**

**Migration notes**:

Twenty lines, and the natural first port in this module: it owns parser construction, so every other file in the module inherits the change. Rename it in the same commit — a class named `JavaParserFactory` that builds OpenRewrite parsers is a trap for the next reader.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/JavaParserFactory.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl java-watch-agent -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/JavaParserFactory.java
```

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java`

**Status**: `[ ]` not-started · **Priority**: medium · **Risk**: medium · **Detected**: qualified-only · **Lines**: 167

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `var declaration = new com.github.javaparser.JavaParser().parse(Files.readString(enumFile))`
  - `.findFirst(com.github.javaparser.ast.body.EnumDeclaration.class).orElseThrow();`
  - `.map(com.github.javaparser.ast.body.EnumConstantDeclaration::getNameAsString)`

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

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java`

**Status**: `[ ]` not-started · **Priority**: medium · **Risk**: medium · **Detected**: qualified-only · **Lines**: 265

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `var parsed = new com.github.javaparser.JavaParser().parse(source);`
  - `.findFirst(com.github.javaparser.ast.body.EnumDeclaration.class).orElseThrow();`
  - `.map(com.github.javaparser.ast.body.EnumConstantDeclaration::getNameAsString)`

**Migration notes**:

Same shape as CompactionRoundTripTest; port with the CLI, not after it.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.java.tree.J.EnumValue`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java
```

### `jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java`

**Status**: `[ ]` not-started · **Priority**: medium · **Risk**: medium · **Detected**: imports · **Lines**: 146

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ParserConfiguration` — **inferred**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter` — **verified**

**Migration notes**:

Test-side port. It asserts on `LexicalPreservingPrinter` output; rewrite the assertion as "the transformed source equals the expected source" and let the print-idempotency check carry the formatting guarantee.

**OpenRewrite classes involved**: `org.openrewrite.java.JavaParser`, `org.openrewrite.SourceFile`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl jwa-builder -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java
```

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java`

**Status**: `[ ]` not-started · **Priority**: medium · **Risk**: low · **Detected**: qualified-only · **Lines**: 334

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `com.github.javaparser.ast.CompilationUnit cu = SourceReader.readJp(enumFile).unit();`
  - `for (com.github.javaparser.ast.body.EnumConstantDeclaration constant`
  - `: cu.findAll(com.github.javaparser.ast.body.EnumConstantDeclaration.class)) {`

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

**Status**: `[ ]` not-started · **Priority**: medium · **Risk**: low · **Detected**: imports · **Lines**: 130

**JavaParser surface on disk**:

- `com.github.javaparser.JavaParser` — **verified**
- `com.github.javaparser.ast.CompilationUnit` — **verified**
- `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration` — **verified**
- `com.github.javaparser.ast.expr.AnnotationExpr` — **inferred**

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

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/InterfaceInfo.java`

**Status**: `[ ]` not-started · **Priority**: low · **Risk**: low · **Detected**: qualified-only · **Lines**: 84

**JavaParser surface on disk**:

- No import lines. Every use is fully qualified, so an import-driven port
  misses this file:
  - `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration declaration,`
  - `com.github.javaparser.ast.body.ClassOrInterfaceDeclaration declaration) {`

**Migration notes**:

Two fully-qualified `ClassOrInterfaceDeclaration` parameters, no imports. It sits in the `meta` package, which is otherwise JavaParser-free, so it is the one file keeping that package off the finished list.

**OpenRewrite classes involved**: `org.openrewrite.java.tree.J.ClassDeclaration`

**Port procedure**:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/InterfaceInfo.java
# ... edit ...
cmd /c "scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test"
bun run scripts/rewrite-migration/migrate-file.js --after hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/InterfaceInfo.java
```

## Exempt (25)

Files that legitimately keep a JavaParser reference. Each states why, and what
removes the exemption — an allowlist entry without an exit condition is a hole.

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JdkImportSupport.java`

**Reason**: One prose mention, explaining why a general solution is out of scope: "The general solution is JavaParser's symbol solver". It names the library as the thing *not* being used, so it carries no dependency.

**Deferred to**: Remove the sentence when JavaParser leaves the tree: the symbol solver it declines to use will no longer exist to decline.

### `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/FieldConstraint.java`

**Reason**: One prose mention in a doc comment ("read with JavaParser exactly as …"), describing how the constraint annotations are interpreted. It does not name a JavaParser type.

**Deferred to**: Reword to name the LST instead, in whichever edit next touches that comment.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DependencyBoundaryTest.java`

**Reason**: Mentions JavaParser only in a comment. It is the test that asserts the module’s dependency boundary, so it is the file that will police the JavaParser removal.

**Deferred to**: Never — a comment naming the dependency it polices is correct.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DivergenceKindTest.java`

**Reason**: One prose mention explaining a platform-dependent expectation: "The emitter prints through JavaParser, which uses the platform line separator". It names the printer as the cause of a Windows-vs-Linux difference.

**Deferred to**: Reword when the emitter prints through the LST: the property under test (the platform line separator) stays, only its cause changes.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ParseGuardTest.java`

**Reason**: One prose mention in a comment recording why the guard exists: "JavaParser returns a PARTIAL unit for this, which is how the …". It documents the JavaParser behaviour the fail-safe was written against.

**Deferred to**: Stays while the guard exists: the comment is the record of the failure mode (a partial unit mistaken for a readable file) that the port must not reintroduce. Update the wording when the parser changes, not the test.

### `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/SourceReaderTest.java`

**Reason**: Asserts the configured parser language level is `ParserConfiguration.LanguageLevel.JAVA_25` (by fully-qualified name, with no import). That assertion is the regression guard for notes F-23/F-34 — the whole reason the generator can read its own output. The JavaParser constant is the thing under test, so removing the reference removes the guard.

**Deferred to**: Stays as long as javaparser-core remains on the module classpath. When the dependency is finally dropped this test must first be re-expressed as "a record, a switch expression and a sealed type all parse cleanly", which preserves the property without naming the library.

### `merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java`

**Reason**: The reference port: it is the file this migration’s `verified` mappings are evidenced from. Its bare-name mentions are an `{@link JavaParser}` that resolves to `org.openrewrite.java.JavaParser`, plus prose explaining the parser-reuse rule it had to discover (a JavaParser caches parsed sources and refuses a second set declaring the same FQNs).

**Deferred to**: Never. This is the destination, not the source: it is what the queue is being ported towards.

### `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteEntityMetadataGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteFieldBoilerplateGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteTypeLiterals.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteValidationGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteViewBuilderGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteViewInterfaceGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/AccessorGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/AuditableRule.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/BuilderGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/ConstructorGenerator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/ContextualAnalyzer.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/EntityRulesValidator.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/EnumCompactionCli.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/EnumConstantOrderChecker.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/JavaParserTool.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/MarkerEntityRule.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/ViewAnnotationRule.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

### `project-automation/src/main/java/hr/hrg/rewrite/validation/ViewInterfaceRule.java`

**Reason**: The staged ports. Every file here is already written against OpenRewrite and mentions JavaParser only in a provenance annotation — `<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API while internally using OpenRewrite". Those annotations are the DEC-019 requirement that a reader can navigate from the ported file back to what it replaced, so they must not be "cleaned up" as leftover JavaParser usage. The same comment also explains why a case-insensitive grep is misleading here: the package has no `com.github.javaparser` reference at all.

**Deferred to**: Never for the provenance annotation — it is the navigational half of the port. The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), which is Phase-0 repair work and not a Phase 6 migration.

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

