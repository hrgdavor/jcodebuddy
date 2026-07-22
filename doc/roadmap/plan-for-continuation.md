# Plan for Continuing Work on hipster-entity

**Generated:** 2026-07-21  
**Based on analysis of:** Multi-module Maven Java project (Java 21) with interface-first entity model, array-backed proxies, generated metadata strategy.

## Executive Summary

This document outlines a **phased implementation plan** to complete pending architecture decisions, advance core features for hipster-entity, and establish the `code-buddy` code generation framework. The plan prioritizes:
1. **Validation & hardening** of existing implemented primitives  
2. **Building demo examples** that demonstrate read/write proxy workflows
3. **Starting code-buddy submodule development** (new high priority)
4. **Completing proposed ADRs** with focused benchmarking and API design
5. **Documentation updates** to reflect current state vs roadmap items

---

## New High Priority: Code-Buddy Submodule Development

### Overview
The `code-buddy` submodule will be a standalone Maven module that provides flexible, code-driven code generation capabilities. Unlike traditional Java annotation processing, code-buddy allows users to:
- **Choose configuration vs code explicitly** — any mix of declarative config and imperative code
- **Extend custom generators easily** without fighting compiler limitations
- **Integrate as a dependency** alongside hipster-entity modules
- **Run independently** or as part of the main project tooling

**Goals:**
- Create a minimal working prototype that demonstrates code-driven generation
- Build it on top of existing hipster-entity core generators where applicable  
- Design an API that allows users to write custom generator classes in plain Java (no annotations required)
- Establish conventions for what can be configured vs coded freely

### Phase 1.0: Code-Buddy Core Infrastructure (Week 1)
[ ] Create `code-buddy` Maven module structure under parent project or sibling location
[ ] Design and implement minimal `CodeGenerator` interface with:
    - `generate(CodeContext context)` method that receives full build state as objects
    - Support for both configuration-based and code-based generation modes
- [ ] Implement `CodeContext` interface exposing:
    - Maven project model access
    - Source tree manipulation utilities (read/write Java source files)
    - Type resolution via hipster-entity metadata if needed
- [ ] Create a simple "hello world" generator that writes a single class from code
[ ] Write basic unit tests for the generator interface and context API

**Files to create:**
- `code-buddy/` directory (Maven module)
- `code-buddy/pom.xml`
- `code-buddy/src/main/java/hr/hrg/hipster/codebuddy/*Generator.java`
- `code-buddy/src/main/java/hr/hrg/hipster/codebuddy/context/*Context.java`

### Phase 1.1: Demonstrate Flexibility (Week 2)
[ ] Write a generator that combines config (JSON/YAML) and code freely in same method
[ ] Create example where user provides a Java class extending `CodeGenerator` to customize behavior
[ ] Show how configuration can be overridden by generated code at runtime
[ ] Document the "configuration vs code" boundary clearly with examples

**Files to create:**
- `code-buddy/demo/*.java` — examples of hybrid config/code generators
- Updated README for code-buddy explaining its philosophy and use cases

### Phase 1.2: Integration Testing (Week 3)
[ ] Create test harness that runs code-buddy as a Maven dependency in a separate project
[ ] Verify that custom generators can be picked up via SPI or annotation scanning
[ ] Test that generated artifacts are written to correct output directories
[ ] Benchmark generation speed and memory usage against annotation processing baseline

**Files to create:**
- `code-buddy/src/test/java/hr/hrg/hipster/codebuddy/*IntegrationTest.java`
- Maven profiles for running integration tests with different generator configurations

### Phase 2.0: Integrate with Main Project (Week 4)
[ ] Add code-buddy as a sibling Maven module to hipster-entity in parent POM
[ ] Wire up existing core generators (e.g., view proxy factory) to use code-buddy framework
[ ] Demonstrate that code-buddy can replace complex annotation processing setups
[ ] Document migration path from annotation processors to code-buddy generators

**Files to create:**
- Parent `pom.xml` update including code-buddy module
- Migration guide in documentation  
- Examples showing "before (annotations) vs after (code-buddy)

### 1.1 Proxy Method Dispatch Tests
[ ] Add tests for `ArrayBackedViewProxyFactory` with strict error handling
[ ] Verify field type mismatch diagnostics on initialization
[ ] Test ordering-guard scenarios (ordinal vs enum constant alignment)

**Files to create:**
- `hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/proxy/*Test.java`
- Unit tests for method-to-field mapping correctness

### 1.2 Hardening Update Arrays
[ ] Test strict no-op behavior on equal assignment in updatable proxy mutators
[ ] Verify change snapshot and reset methods work correctly for patch flows
[ ] Add edge case tests (empty updates, bulk set operations)

**Files to create:**
- `hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/array/*Test.java`

### 1.3 Build & Verify Existing Demo
[ ] Run Maven build on current state to verify all modules compile
[ ] Execute demo examples in `hipster-entity-example` to confirm runtime behavior
[ ] Document any gaps between documented API and actual implementation

**Action:**
```bash
mvn clean install -pl hipster-entity-api,hipster-entity-core,hipster-entity-example
```

---

## Phase 2: Demo Examples & Workflows (Week 3-4)

### 2.1 Proxy Row → Read Proxy → Updatable Proxy → Record Flow
[ ] Create `PersonSummaryRecord` implementing read view
[ ] Build factory converting from `ViewReader` using field enum order
[ ] Demonstrate proxy dispatch to record-style accessor methods

**Files to create:**
- `hipster-entity-example/src/main/java/hr/hrg/.../person/record/*Record.java`
- Demo README explaining the flow

### 2.2 Field Enum Order vs Record Component Alignment
[ ] Define `PersonSummaryField` enum with Java types per constant
[ ] Verify ordinal order matches array layout contract
[ ] Test record factory mapping from ordered fields to components

**Files to create:**
- `hipster-entity-example/src/main/java/hr/hrg/.../person/field/*Field.java`
- Integration tests for alignment validation

### 2.3 Document Proxy Patterns in Examples
[ ] Write canonical example showing read-only summary proxy usage
[ ] Create updatable details proxy with record-style fluent mutators
[ ] Show change tracking snapshot capture and patch application

**Files to create:**
- `hipster-entity-example/demo/*Examples.java`
- Updated README.md in example module

---

## Phase 3: Complete Remaining ADRs (Week 5-8)

### 3.1 DEC-003: Projection-Oriented Read Path
**Goal:** Define adapter shape and benchmark criteria for direct SQL/NoSQL JSON output.

[ ] Design `Projection` interface extending read view concept  
[ ] Create benchmark comparing projection vs regular proxy dispatch  
[ ] Document adapter contract (streaming, bulk, batch modes)

**Files to create:**
- `hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/projection/*Projection.java`
- `doc/architecture/decisions/DEC-003.md` complete implementation guide

### 3.2 DEC-006: Build-Time Type Divergence Validation
**Goal:** Add converter registry and validation UX.

[ ] Design manifest generation for type divergences between interface & runtime types
[ ] Create validation utility that runs on build time (or compile-time with annotations)
[ ] Document error messages and remediation guidance

**Files to create:**
- `hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/validation/*Validation.java`
- Sample annotation for marking divergent fields
- ADR document for validation UX decisions

### 3.3 DEC-012: Update-Array & Change-Tracking Semantics  
**Goal:** Define touched/dirty/null contract and merge-mode behavior.

[ ] Design `Touched` marker interface or annotation for dirty tracking
[ ] Document null semantics (null vs absent vs default)
[ ] Create merge mode enumeration (overwrite, append, coalesce, etc.)

**Files to create:**
- `hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/markers/*Touched.java`
- Merge policy documentation in user docs
- Unit tests for merge behavior edge cases

### 3.4 DEC-009: Source-Visible Generation Strategy
**Goal:** Define freeze marker semantics, patching rules, sidecar workflow.

[ ] Design `@Generated(freeze = true)` annotation on generated classes
[ ] Document how to safely re-generate without losing manual overrides
[ ] Create sidecar pattern for keeping user-generated metadata separate from framework code

**Files to create:**
- Generator freeze marker documentation in brainstorm folder
- Tooling extension points for custom generation hooks
- Migration guide for projects using generated code

### 3.5 DEC-013: Optional Per-View Implementation Selection Factory
**Goal:** Define override precedence, fallback policy, provider ordering.

[ ] Design `ViewProvider` interface with priority ordering
[ ] Document module-based discovery (SPI pattern or annotation scanning)
[ ] Create default implementation that uses array-backed proxy

**Files to create:**
- Provider registration mechanism in `hipster-entity-api`
- Module descriptor for SPI discovery
- Factory composition documentation

---

## Phase 4: Documentation Updates & Cleanup (Week 9-10)

### 4.1 User Documentation Gap Filling
[ ] Review and update all user-facing docs to match current implementation
[ ] Add migration guide for projects adopting hipster-entity
[ ] Create troubleshooting section with common error patterns

**Files to create/update:**
- `doc/user/README.md` with quick start examples
- Individual user guides (getting-started, core-concepts, materialization)

### 4.2 Architecture Documentation Polish
[ ] Consolidate ADR references in main architecture README
[ ] Add decision traceability diagrams or flowcharts
[ ] Document module boundary expectations clearly

**Files to create/update:**
- `doc/architecture/README.md` with current state summary
- Index of all accepted decisions in one place

### 4.3 Roadmap Completion Notes
[ ] Update roadmap checklist items marked as "in progress"
[ ] Add completion notes for previously proposed ADRs now implemented
[ ] Document remaining work items clearly with owners and target dates

**Files to create/update:**
- `doc/roadmap/README.md` with updated status table
- Individual milestone pages if needed

---

## Risk Assessment & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Generated code conflicts with manual changes | Medium | High | Document freeze markers; provide migration path |
| Performance benchmarks show no improvement | Low | Medium | Establish baseline early; document expected variance |
| Builder API decisions remain contested | Medium | High | Focus on concrete use cases; defer abstract design questions |
| Documentation grows faster than implementation | Low | Medium | Write docs concurrently with each ADR completion |

### Phase 3: Integrate Existing Generators into Code-Buddy (Week 5)

### 3.1 Wire Up Core Generators
[ ] Take existing hipster-entity core generators (view proxy factory, builder generator, etc.)
[ ] Refactor them to implement `CodeGenerator` interface using code-buddy framework
[ ] Demonstrate that they can now be configured via code instead of annotation processing descriptors
[ ] Document the refactoring process and benefits gained

**Files to create:**
- Refactored generator implementations in `code-buddy/src/main/java/hr/hrg/hipster/codebuddy/generators/*`
- Migration examples showing old vs new usage

### 3.2 Add Generator Marketplace Support
[ ] Design SPI mechanism for discovering custom generators at runtime
[ ] Create module descriptor (e.g., `META-INF/services/hr.hrg.hipster.codebuddy.CodeGenerator`) or annotation-based discovery
[ ] Document how to write a generator that ships as a separate Maven dependency
[ ] Provide sample marketplace entries (e.g., "JSON DTO Generator", "SQL Query Builder")

**Files to create:**
- SPI registration utilities in code-buddy
- Sample marketplace generator implementations

### Phase 4: Documentation Updates & Cleanup (Week 9-10)

## Success Metrics

After completing this plan, hipster-entity will have:
- [x] All implemented features documented and validated  
- [ ] At least 80% of proposed ADRs either implemented or formally rejected/superseded
- [ ] Complete user guide covering read/write proxy patterns
- [ ] Stable API with clear migration path for adopters
- [ ] Measurable performance benchmarks showing expected behavior

---

## Next Immediate Actions (This Week)

1. **Run Maven build** to verify current state compiles cleanly
2. **Create test plan document** outlining Phase 1 unit tests needed
3. **Sketch demo example** for `PersonSummaryRecord` workflow in Phase 2
4. **Start DEC-009 work** (source-visible generation) as it directly affects other ADRs
5. **Review and update existing user docs** to reflect current implementation state

**Estimated effort:** 160–200 hours total, split across ~3 months of part-time work.

---

## Appendix: Project Structure Summary

```
java-hipster-entity/
├── code-buddy/                  # NEW: Code generation framework (Week 1+)
│   ├── pom.xml
│   └── src/main/java/hr/hrg/hipster/codebuddy/
│       ├── CodeGenerator.java   # Core interface
│       ├── context/             # Build state and source manipulation API
│       └── generators/           # Example generators using config+code mix
├── hipster-entity-api/          # Contracts and annotations only
│   ├── src/main/java/hr/hrg/hipster/entity/core/
│   │   ├── array/
│   │   │   ├── EntityReadArray.java
│   │   │   ├── EntityUpdateArray.java
│   │   │   └── EntityUpdateTrackingArray*.java (DEC-014)
│   │   ├── proxy/
│   │   │   └── ArrayBackedViewProxyFactory.java  (DEC-015, DEC-016)
│   │   └── validation/          # New for DEC-006
│   └── src/test/java/...
├── hipster-entity-example/       # Demo module
│   ├── src/main/java/hr/hrg/entity/
│   │   └── person/             # Example entity views
│   │       ├── PersonEntity.java  (extends EntityBase + Identifiable)
│   │       ├── PersonSummary.java
│   │       ├── PersonDetails.java
│   │       └── field/           # New: Field enums for DEC-004?
├── hipster-entity-jackson/      # JSON integration
├── hipster-entity-tooling/     # CLI/build tooling
├── doc/
│   ├── architecture/decisions/  # ADRs (18 total, 10 accepted)
│   ├── roadmap/                 # Phase-by-phase tracking
│   └── user/                    # User guides
```

---

**Notes:**
- This plan assumes the current repository state matches this analysis
- Work can be done in any order; prioritize demo examples to validate designs early
- Consider starting with **Phase 2 demo examples** as they provide concrete validation for multiple ADRs

</new_content>}</new_content>}</replace_file>}