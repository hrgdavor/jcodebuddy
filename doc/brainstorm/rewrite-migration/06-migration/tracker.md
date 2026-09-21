# Phase 6 — Migration Tracker

> **Hand-maintained.** This file is the status source of truth, and nothing
> regenerates it. `Checklist.md` is generated from it, and
> `verify-migration.js` fails when a row here disagrees with the tree.

Created: 2026-09-21

## Status vocabulary

| Mark | Status | Meaning |
| --- | --- | --- |
| `- [ ]` | `not-started` | No port work begun. |
| `- [~]` | `in-progress` | Mid-change; the file may not compile. |
| `- [>]` | `testing` | Ported and compiling; behaviour not yet proven. |
| `- [x]` | `complete` | Ported, and the module gate passes. |
| `- [!]` | `blocked` | Waiting on a named prerequisite — say which in Notes. |
| `- [-]` | `exempt` | Allowlisted; a JavaParser reference here is correct. |

Rules the gate enforces:

- A file that still imports JavaParser must not be `complete` or `testing`.
- A `blocked` row must name its blocker in Notes.
- Every queue file has exactly one row.

## Rules for editing

1. Set `testing` only when the module compiles with `clean`.
2. Set `complete` only when the module’s `clean test` gate passes.
3. Never set `complete` for a file whose port you have not seen run: this
   phase rewrites behaviour-bearing generators, and a compile is not a proof.
4. Add a row when the scanner finds a new file — `verify-migration.js` fails
   until you do.

## Queue

| Status | Module | Priority | Risk | Notes |
| --- | --- | --- | --- | --- |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [~] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java` | hipster-entity-tooling | high | high | see Checklist.md |
| `- [ ] jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java` | jwa-builder | high | high | see Checklist.md |
| `- [ ] jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java` | jwa-builder | high | high | see Checklist.md |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java` | hipster-entity-tooling | high | medium |  |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java` | hipster-entity-tooling | high | medium |  |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java` | hipster-entity-tooling | high | medium |  |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java` | hipster-entity-tooling | high | medium |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRulesValidator.java` | hipster-entity-tooling | high | medium |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java` | hipster-entity-tooling | high | medium |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewAnnotationRule.java` | hipster-entity-tooling | high | medium |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java` | hipster-entity-tooling | high | medium |  |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java` | hipster-entity-tooling | high | medium |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java` | hipster-entity-tooling | high | medium |  |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java` | hipster-entity-tooling | high | medium |  |
| `- [ ] java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java` | java-watch-agent | high | medium |  |
| `- [ ] java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java` | java-watch-agent | high | medium |  |
| `- [ ] java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java` | java-watch-agent | high | medium |  |
| `- [ ] java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java` | java-watch-agent | high | medium |  |
| `- [ ] jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java` | jwa-sidecar | high | medium |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/AuditableRule.java` | hipster-entity-tooling | high | low |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityFieldEnumOrderRule.java` | hipster-entity-tooling | high | low |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java` | hipster-entity-tooling | high | low |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java` | hipster-entity-tooling | high | low | Renamed from JavaParserTool during the port |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java` | hipster-entity-tooling | high | low |  |
| `- [ ] java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/JavaParserFactory.java` | java-watch-agent | high | low |  |
| `- [ ] hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java` | hipster-entity-tooling | medium | medium |  |
| `- [ ] hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java` | hipster-entity-tooling | medium | medium |  |
| `- [ ] jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java` | jwa-builder | medium | medium |  |
| `- [ ] hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java` | hipster-entity-tooling | medium | low |  |
| `- [ ] hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/index/TypeFactsTest.java` | hipster-entity-tooling | medium | low |  |
| `- [ ] hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java` | hipster-entity-tooling | medium | low |  |
| `- [ ] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/InterfaceInfo.java` | hipster-entity-tooling | low | low |  |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java` | hipster-entity-tooling | high | medium | added by generate-checklist.js |
| `- [x] hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java` | hipster-entity-tooling | high | low | added by generate-checklist.js |

## Exempt

| Status | Module | Priority | Risk | Notes |
| --- | --- | --- | --- | --- |
| `- [-] hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/SourceReaderTest.java` | hipster-entity-tooling | exempt | - | Stays as long as javaparser-core remains on the module classpath. When the dependency is finally dropped this test must first be re-expressed as "a record, a switch expression and a sealed type all parse cleanly", which preserves the property without naming the library. |

## Unclassified

None. Every file the scanner found is classified in `curation.js`.

