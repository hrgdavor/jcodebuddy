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

**Status**: Sketches only — the engine was never materialised in `project-automation`, and Phase 6 absorbed its purpose (verified 2026-09-22)

**Goal**: Integrate OpenRewrite tools with project-automation module

**Key Files**:
- `AutomationEngine.java` - Orchestration engine
- `BatchProcessor.java` - Batch processing utilities

---

### Phase 6: Migration of Existing Tooling

**Status**: **COMPLETE** 2026-09-22 — 34/34 files ported, `javaparser-core` gone, whole reactor green (601 tests)

**Goal**: Complete migration of all remaining JavaParser usage

**Approach**:
1. Scan for remaining JavaParser imports
2. Create migration checklist
3. Migrate files one by one
4. Validate each migration

---

### Phase 7: Testing & Validation

**Status**: **Next** — prerequisites met; re-scope before starting, because its deliverables assume the Phase 5 automation layer that does not exist (verified 2026-09-22)

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
| 5 — Automation | **Sketches only — NOT delivered as intended** | All ten sketches exist in `05-automation/` (`AutomationEngine`, `BatchProcessor`, `ProjectAutomation`, `TransformationRegistry`, …), but `project-automation` declares no `AutomationEngine` and no `ProjectAutomation` — its classes are `CodeContext`/`CodeGenerator`/`TypeResolver`/`MetadataAnalysisRunner` and the entity watcher. Phase 6 did not need the engine: the port went module by module, and the staging package that was to become it (`project-automation/src/main/java/hr/hrg/rewrite/**`) was deleted because it did not compile and nothing referenced it. |
| 6 — Migration of Existing Tooling | **COMPLETE** (2026-09-22) | 34/34 queue files ported; `javaparser-core` declared by no module; `verify-migration.js` `RESULT: PASS`; whole reactor `clean test` green (601 tests). Delivery record: `06-Migration-Checklist.md` § *Delivery record*. |
| 7 — Testing & Validation | **Next** | Prerequisites are met (Phase 6 complete). One caveat to re-sequence before starting: Phase 7's plan lists unit tests "for each migrated component" and integration tests "for workflows"; the workflows Phase 5 was to provide do not exist, so what is testable today is the ported tooling and its artifact contracts (which already carry 601 tests), not an automation pipeline. |

**Two consequences for whoever picks up Phase 7.** First, the "20 weeks / 5 months" timeline above was
never a used estimate and should not be read as remaining work: Phases 1–4 and 6 are done, and Phase 5's
intent was absorbed by Phase 6. Second, Phase 7 should be re-scoped against what exists rather than
against its own deliverables list, because that list assumes the automation layer.

---

## Current status

**Phase**: Phase 6 complete; Phase 7 is the next phase, to be re-scoped (see the table above).

**Verified in this session**:
- the whole reactor builds and its tests pass: `601` tests, `0` failures, `0` errors;
- `verify-migration.js` reports `RESULT: PASS` with all eight checks green and no `pom-dependencies`
  warning — no module declares `javaparser-core`;
- three pre-existing failures that blocked a whole-reactor run were fixed: `java-watch-scp`'s
  `ConfigTest` (a mixed-separator path from a `~` expansion), `metadata-server`'s
  `MetadataServerTest.httpForyRoundTrip` (Fory 1.3.0 cannot write a null into an object field; the
  envelope now crosses as a map) and `java-watch-run-sample`'s `copy-dependencies` binding
  (`generate-resources` → `package`, per MDEP-187).

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

### Core Files Created
1. `doc/brainstorm/rewrite-migration/REWRITE-MIGRATION-PLAN.md` - Main plan
2. `doc/brainstorm/rewrite-migration/README.md` - Project README
3. `doc/brainstorm/rewrite-migration/PLAN-SUMMARY.md` - This summary

### Phase 1 Files
1. `doc/brainstorm/rewrite-migration/01-foundation/pom-fragment-rewrite.xml`
2. `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/AstVisitor.java`
3. `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/AstManipulator.java`
4. `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/CompilationUnitAdapter.java`
5. `doc/brainstorm/rewrite-migration/01-foundation/README.md`

---

## OpenRewrite Version

**Target Version**: 8.40.1

**Maven Dependencies**:
```xml
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-core</artifactId>
    <version>8.40.1</version>
</dependency>
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-java</artifactId>
    <version>8.40.1</version>
</dependency>
```

---

## Documentation References

- OpenRewrite Docs: https://docs.openrewrite.org/
- OpenRewrite GitHub: https://github.com/openrewrite/rewrite
- OpenRewrite Recipes: https://docs.openrewrite.org/concepts-and-explanations/recipes
- OpenRewrite LST Examples: https://docs.openrewrite.org/concepts-and-explanations/lst-examples

---

## Approval Required

Before proceeding with migration:
1. Review this plan document
2. Review architecture decisions (DEC-019, DEC-020, DEC-021, DEC-022, DEC-029)
3. Confirm timeline and resource availability
4. Approve migration approach

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-25  
**Status**: Ready for Review and Approval
