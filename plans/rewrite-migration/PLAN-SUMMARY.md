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

**Status**: In Progress

**Files Created**:
- `pom-fragment-rewrite.xml` - Maven dependency fragment
- `AstVisitor.java` - Base visitor interface
- `AstManipulator.java` - High-level manipulation utilities
- `CompilationUnitAdapter.java` - AST conversion utilities

**Goal**: Set up infrastructure for gradual migration

---

### Phase 2: Core AST Utilities

**Status**: Pending

**Goal**: Implement core AST traversal and manipulation utilities

**Key Files**:
- `NodeTraversal.java` - AST traversal utilities
- `AstPrinter.java` - Source printing utilities

---

### Phase 3: Code Generation Tools

**Status**: Pending

**Goal**: Migrate code generation tools from JavaParser to OpenRewrite

**Tools to Migrate**:
- `ViewInterfaceGenerator.java`
- `FieldBoilerplateGenerator.java`
- `ViewBuilderGenerator.java`
- `ValidationGenerator.java`
- `EntityMetadataGenerator.java`

---

### Phase 4: Validation & Analysis Tools

**Status**: Pending

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

**Status**: Pending

**Goal**: Integrate OpenRewrite tools with project-automation module

**Key Files**:
- `AutomationEngine.java` - Orchestration engine
- `BatchProcessor.java` - Batch processing utilities

---

### Phase 6: Migration of Existing Tooling

**Status**: Pending

**Goal**: Complete migration of all remaining JavaParser usage

**Approach**:
1. Scan for remaining JavaParser imports
2. Create migration checklist
3. Migrate files one by one
4. Validate each migration

---

### Phase 7: Testing & Validation

**Status**: Pending

**Goal**: Comprehensive testing and validation

**Test Types**:
- Unit tests for each migrated component
- Integration tests for full workflows
- Performance benchmarks
- Edge case testing

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
