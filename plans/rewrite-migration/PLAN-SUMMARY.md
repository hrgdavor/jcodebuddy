# OpenRewrite Migration Plan - Summary

## Project

**JCodeBuddy** - Multi-module Java project currently using JavaParser for AST manipulation

## Target

Migrate from **JavaParser** to **OpenRewrite core module** for AST manipulation

## Rationale

OpenRewrite provides:
- Modern visitor pattern for AST traversal
- Better integration with build tools
- Active maintenance and community
- Recipe-based transformation approach
- Support for multiple language versions

## Architecture Compliance

All migration work must comply with JCodeBuddy architecture decisions:
- **DEC-019**: Source-visible wiring (no reflection-driven discovery)
- **DEC-020**: Cooperative codegen (preserve user edits)
- **DEC-021**: Generator class-file header format
- **DEC-022**: Refactor-sensitive naming contracts
- **DEC-029**: Class index by FQN, not surrogate IDs

---

## Migration Phases

### Phase 1: Foundation & Infrastructure ✓ STARTED

**Status**: Delivered as specified (verified 2026-09-22 — see § *Phase status* above)

**Files Created**:
- `pom-fragment-rewrite.xml` - Maven dependency fragment
- `AstVisitor.java` - Base visitor interface
- `AstManipulator.java` - High-level manipulation utilities
- `CompilationUnitAdapter.java` - AST conversion utilities

**Goal**: Set up infrastructure for gradual migration

---

### Phase 2: Core AST Utilities

**Status**: Delivered as specified, superseded in practice by `TreeQueries` / `JavaSyntaxCheck` / `TypeLiterals` (verified 2026-09-22)

**Goal**: Implement core AST traversal and manipulation utilities

**Key Files**:
- `NodeTraversal.java` - AST traversal utilities
- `AstPrinter.java` - Source printing utilities

---

### Phase 3: Code Generation Tools

**Status**: Delivered — the ported generators are green in the tree (verified 2026-09-22)

**Goal**: Migrate code generation tools from JavaParser to OpenRewrite

**Tools to Migrate**:
- `ViewInterfaceGenerator.java`
- `FieldBoilerplateGenerator.java`
- `ViewBuilderGenerator.java`
- `ValidationGenerator.java`
- `EntityMetadataGenerator.java`

---

### Phase 4: Validation & Analysis Tools

**Status**: Delivered — the ported validation rules are green in the tree (verified 2026-09-22)

**Goal**: Migrate validation rules and analysis tools

**Tools to Migrate**:
- `AuditableRule.java`
- `MarkerEntityRule.java`
- `ViewInterfaceRule.java`
- `ViewAnnotationRule.java`
- `EntityRulesValidator.java`
- `ContextualAnalyzer.java`
- `AccessorGenerator.java`
- `BuilderGenerator.java`
- `ConstructorGenerator.java`

---

### Phase 5: Integration with Project Automation

**Status**: **Delivered** 2026-09-22 — `AutomationEngine`, `BatchProcessor`, `ProjectAutomation`, `TransformationRegistry`, the result records, `SourceFiles` and `SourceFacts` are in `project-automation/.../automation/` with 67 tests; the sketches' defects were fixed rather than copied (`05-Automation.md` § *Status*)

**Goal**: Integrate OpenRewrite tools with project-automation module

**Key Files**:
- `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/AutomationEngine.java` - Orchestration engine
- `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/BatchProcessor.java` - Batch processing utilities
- `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/ProjectAutomation.java` - The facade

---

### Phase 6: Migration of Existing Tooling

**Status**: **COMPLETE** 2026-09-22 — 34/34 files ported, `javaparser-core` gone, whole reactor green (1164 tests)

**Goal**: Complete migration of all remaining JavaParser usage

**Approach**:
1. Scan for remaining JavaParser imports
2. Create migration checklist
3. Migrate files one by one
4. Validate each migration

---

### Phase 7: Testing & Validation

**Status**: **Delivered** (2026-09-22) — re-scoped against the classes that exist, then implemented: 95 new tests across three modules, generated `TEST-REPORT.md`, absolute JMH baselines, three production defects found and fixed. Reactor `clean test`: 1326 tests, 0 failures.

**Goal**: Comprehensive testing and validation

**Test Types**:
- Unit tests for each migrated component
- Integration tests for full workflows
- Performance benchmarks
- Edge case testing

---

## Phase status — verified against the tree, 2026-09-22

The per-phase "Status" lines further up this file are the **plan's own optimistic headings** from when it
was written, and they are wrong in both directions: they say Phase 1 is in progress and Phases 2–7 are
pending. This table is what was actually verified, with the evidence for each row. Where a phase's
deliverables are *documents* rather than compiled code, the row says so, because that distinction is what
made the plan's status lines misleading.

| Phase | Status | Evidence |
| --- | --- | --- |
| 1 — Foundation | **Delivered as specified** | `01-foundation/pom-fragment-rewrite.xml` exists, and its content is in the root POM: `openrewrite.version` 8.90.4 plus managed `rewrite-core` / `rewrite-java`, with `rewrite-java-25` pinned in the modules that parse. `01-foundation/api-compatibility/{AstVisitor,AstManipulator,CompilationUnitAdapter}.java` exist. OpenRewrite is a real dependency of four modules. |
| 2 — Core AST Utilities | **Delivered as specified, and superseded in practice** | `02-utilities/util/{AstPrinter,NodeTraversal,SourceManipulation,TypeUtils}.java` and their four test sketches exist. The *working* equivalents were written during Phase 6 where they are used: `hipster-entity-tooling`'s `TreeQueries` (traversal/kind/annotation queries), `JavaSyntaxCheck` (positions, which the plan did not anticipate needing javac for) and `TypeLiterals`/`JdkImportSupport` (type text and class literals). The plan's `util/` files are sketches in the docs tree, not compiled code. |
| 3 — Code Generation | **Delivered** | Its own Status line already said "Implementation Complete"; the generators are ported and green in the tree (`ViewBuilderGenerator`, `ViewInterfaceGenerator`, `ValidationGenerator`, `ViewRecordGenerator`, `FieldBoilerplateGenerator`, and `jwa-builder`'s `RecordBuilderProcessor`/`ClassMemberProcessor`). `03-codegen/` holds the design notes, not code. |
| 4 — Validation | **Delivered** | Its own heading says so, and the tree agrees: `EntityRulesValidator` plus `EntityRule`, `MarkerEntityRule`, `ViewAnnotationRule`, `ViewInterfaceRule`, `AuditableRule`, `EntityFieldEnumOrderRule` and `EnumConstantOrderChecker` are all ported and green. |
| 5 — Automation | **Delivered** (2026-09-22) | The eleven classes are in `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/` (`Transformation`, `TransformationRegistry`, `AutomationEngine`, `BatchProcessor` + `BatchReport`, `ProjectAutomation`, `TransformationResult`, `ValidationResult`, `AnalysisResult`, `TransformationException`, `SourceFiles`, `SourceFacts`) with 67 tests. The ten sketches in `05-automation/` were reviewed line by line and rewritten: two registries became one, the reflective registration overload and the empty `initialize()` are gone, `apply` returns a result rather than a `String`, writes are opt-in, and the regex-based counting / whole-path glob / hard-coded thread count / stdout reporting were replaced. `ValidationException` had no caller and was deleted. Delivery record: `05-Automation.md` § *Status*. |
| 6 — Migration of Existing Tooling | **COMPLETE** (2026-09-22) | 34/34 queue files ported; `javaparser-core` declared by no module; `verify-migration.js` `RESULT: PASS`; whole reactor `clean test` green (1164 tests at that point; 1231 after Phase 5). Delivery record: `06-Migration-Checklist.md` § *Delivery record*. |
| 7 — Testing & Validation | **Delivered** (2026-09-22) | Re-scoped first, because its deliverables named Phase 2 sketches (`AstVisitorTests`, `TypeUtilsTests`) and two of them compared against JavaParser, which Phase 6 removed. What exists: `TreeQueriesTest` (40), `JavaSyntaxCheckTest` (22), `LargeSourceEdgeCaseTest` (4), `MigrationCompletenessTest` (3 over 283 real files), four more `SourceReader` entry-point cases, `AutomationChainIntegrationTest` (4, a chain over a generated tree), `ToolSeamTest` (18, `java-watch-agent`'s first tests), two `*JmhBenchmark` classes with 18 measured results, and Bun-rendered `TEST-REPORT.md` / `BenchmarkReport.md`. Reactor total 1231 to **1326 tests, 0 failures**, gate `RESULT: PASS`. The new tests found and fixed three production defects in the position-lookup half (reversed enclosing chain; `@Foo`/`Outer.Foo` stealing a name line; a match inside a string literal), with committed output byte-identical. Delivery record: `07-Testing-Validation.md` § *Status*. |

**With Phase 7 delivered, the migration itself is complete.** The "20 weeks / 5 months" timeline above was
never a used estimate and should not be read as remaining work. Phase 8 (Documentation) is the only phase
left, and it is about writing down a finished migration rather than about the parser.

---

## Current status

**Phase**: Phases 1–7 are complete. Phase 8 (Documentation) is the only one left.

**Verified in this session**:
- the whole reactor builds and its tests pass: `1326` tests, `0` failures, `0` errors, counted from the
  fresh surefire reports of a single `clean test` (1231 before Phase 7, plus the 95 it added:
  `hipster-entity-tooling` 361 to 434, `project-automation` 81 to 85, `java-watch-agent` 0 to 18).
  The largest module is `merge-java` (601), which is also the last in the reactor — the earlier "601
  tests" figure in this file was that module's own total mistaken for the reactor's;
- `verify-migration.js` reports `RESULT: PASS` with all eight checks green and no `pom-dependencies`
  warning — no module declares `javaparser-core`. Phase 7 added two `curation.js` allowlist entries for
  its own new files, which name the retired library only in prose;
- `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md` is generated, not written: it is rendered
  by Bun from the surefire XML of that build, the captured gate run and `benchmarks/latest.json`, and it
  states a missing input as missing rather than rendering it as zero;
- three production defects in the position-lookup half were found by the new tests and fixed
  (`TreeQueries.typesWithEnclosing` returned the enclosing chain reversed; `JavaSyntaxCheck.namePositionIn`
  let `@Foo`, `Outer.Foo` and a string literal steal a declaration's name line). Committed generated
  output stayed byte-identical, which `ExampleRegenerationTest` asserts;
- three pre-existing failures that blocked a whole-reactor run were fixed earlier in the migration:
  `java-watch-scp`'s `ConfigTest` (a mixed-separator path from a `~` expansion), `metadata-server`'s
  `MetadataServerTest.httpForyRoundTrip` (Fory 1.3.0 cannot write a null into an object field; the
  envelope now crosses as a map) and `java-watch-run-sample`'s `copy-dependencies` binding
  (`generate-resources` → `package`, per MDEP-187);
- Phase 5's engine is in the tree with 67 tests of its own, plus the 4 integration tests Phase 7 added.
  No production `Transformation` is registered — that is the intended state, not an omission (see
  `05-Automation.md` § *Status*); Phase 7's integration test declares its own rather than inventing one.

---

## Timeline

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Foundation | 2 weeks | None |
| Phase 2: Core Utilities | 2 weeks | Phase 1 |
| Phase 3: Code Generation | 4 weeks | Phase 2 |
| Phase 4: Validation | 3 weeks | Phase 3 |
| Phase 5: Automation | 2 weeks | Phase 4 |
| Phase 6: Migration | 4 weeks | Phase 5 |
| Phase 7: Testing | 3 weeks | Phase 6 |

**Total Estimated Duration**: 20 weeks (~5 months)

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| OpenRewrite API changes | Medium | Medium | Use stable API, monitor releases |
| Lexical preservation issues | Low | High | Thorough testing |
| Performance degradation | Low | Medium | Benchmark and optimize |
| Missing JavaParser features | Medium | High | Map all features, implement equivalents |

---

## Current Status

> **Superseded** by § *Phase status — verified against the tree* above, which records what was actually
> delivered and what was checked. The lines below are the plan's original wording, kept because they are
> what the phase documents were written against: "Phase 1 directory structure created" and "API
> compatibility layer scaffolding created" describe the *sketches*, and the phases they call pending are
> not pending any more.

**Phase**: Phase 1 - Foundation (In Progress)

**Completed**:
- Phase 1 directory structure created
- Maven dependency fragment created
- API compatibility layer scaffolding created
- Conversion utilities created

**Next Action**: Continue Phase 1 implementation, complete Phase 1, then proceed to Phase 2

---

## Key Files Reference

### Core Files

The plan documents live in `plans/rewrite-migration/`; the sketches they were written against live in
`doc/brainstorm/rewrite-migration/`:

1. `plans/rewrite-migration/REWRITE-MIGRATION-PLAN.md` - Main plan
2. `plans/rewrite-migration/README.md` - Project README
3. `plans/rewrite-migration/PLAN-SUMMARY.md` - This summary
4. `plans/rewrite-migration/01-Foundation.md` … `07-Testing-Validation.md` - The phase documents

### Phase 1 Files

1. `doc/brainstorm/rewrite-migration/01-foundation/pom-fragment-rewrite.xml`
2. `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/AstVisitor.java`
3. `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/AstManipulator.java`
4. `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/CompilationUnitAdapter.java`
5. `doc/brainstorm/rewrite-migration/01-foundation/README.md`

### Phase 5/6 Files (the code the migration actually runs)

- `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/` - the automation layer
- `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/` - the ported generators, validators
  and the OpenRewrite hub (`TreeQueries`, `JavaSyntaxCheck`, `SourceReader`, `EntityMetadataGenerator`)
- `scripts/rewrite-migration/` - the phase gate, checklist generator and migration report

---

## OpenRewrite Version

**Delivered version**: `8.90.4` (the plan targeted `8.40.1`; the offline repository carries `8.90.4`, which
is what the root POM's `openrewrite.version` pins and what the whole migration was ported against).

**Maven Dependencies** (managed in the root POM; a module declares only what it uses, with no version):

```xml
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-core</artifactId>
    <version>${openrewrite.version}</version>
</dependency>
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-java</artifactId>
    <version>${openrewrite.version}</version>
</dependency>
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-maven</artifactId>
    <version>${openrewrite.version}</version>
</dependency>
```

`rewrite-java-25` is not managed by the parent: the modules that parse Java 25 source
(`hipster-entity-tooling`, `jwa-builder`, `merge-java`) declare it themselves, because only they need the
version-specific parser. `javaparser-core` is declared by no module — that is the migration's own gate
(`verify-migration.js`).

---

## Documentation References

- OpenRewrite Docs: https://docs.openrewrite.org/
- OpenRewrite GitHub: https://github.com/openrewrite/rewrite
- OpenRewrite Recipes: https://docs.openrewrite.org/concepts-and-explanations/recipes
- OpenRewrite LST Examples: https://docs.openrewrite.org/concepts-and-explanations/lst-examples

---

## Approval Required

Historical: this section is the plan's original gate, and the gate was passed — Phases 1–7 have been
executed. It is kept only so the sequence is on record.

1. Review this plan document
2. Review architecture decisions (DEC-019, DEC-020, DEC-021, DEC-022, DEC-029)
3. Confirm timeline and resource availability
4. Approve migration approach

---

**Document Version**: 1.2  
**Last Updated**: 2026-09-22  
**Status**: Phases 1–7 delivered; the migration is complete. Phase 8 (Documentation) remains — see
§ *Phase status*.
