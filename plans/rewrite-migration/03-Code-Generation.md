# Phase 3: Code Generation Tools - Implementation Complete

## Overview

Migrate all code generation tools from JavaParser to OpenRewrite. These tools generate View interfaces, builders, boilerplate code, validation rules, and entity metadata.

## Implementation Status: COMPLETE

Phase 3 has been successfully implemented with wrapper classes that maintain JavaParser API compatibility while using OpenRewrite internally.

## Implemented Components

### Wrapper Classes (JavaParser API Compatibility)

All files created in `project-automation/src/main/java/hr/hrg/rewrite/tooling/`:

1. **OpenRewriteViewInterfaceGenerator.java** - Generates view interfaces
   - Maintains `CompilationUnit` API
   - Handles view interface entry points (toBuilder, toBuilderTracking)
   - Uses lexical preservation for source code

2. **OpenRewriteViewBuilderGenerator.java** - Generates view builders
   - Generates mutable, untracked materialization
   - Handles writable fields only (S1 asymmetry)
   - Supports positional and name-based accessors

3. **OpenRewriteFieldBoilerplateGenerator.java** - Generates field boilerplate
   - Generates field enum metadata
   - Supports append-only ledger (R1)
   - Handles polymorphic root enum detection

4. **OpenRewriteValidationGenerator.java** - Generates validation rules
   - Generates validation methods for entities
   - Supports field constraint annotations
   - Provides validation utilities

5. **OpenRewriteEntityMetadataGenerator.java** - Generates entity metadata
   - Generates entity metadata records
   - Handles property metadata
   - Supports nested record generation

6. **OpenRewriteTypeLiterals.java** - Type literal utilities
   - Class literal expressions
   - Type normalization
   - Array type handling
   - Type parameter resolution

### Unit Tests

All tests created in `project-automation/src/test/java/hr/hrg/rewrite/tooling/`:

1. **OpenRewriteViewInterfaceGeneratorTest.java** - Tests for view interface generation
   - Entry point generation
   - Missing entry point detection
   - Already present entry point handling

2. **OpenRewriteViewBuilderGeneratorTest.java** - Tests for view builder generation
   - Builder class name generation
   - Builder source generation
   - Writable field detection

3. **OpenRewriteFieldBoilerplateGeneratorTest.java** - Tests for field enum generation
   - Source code generation
   - Builder configuration
   - Property enum mode

4. **OpenRewriteValidationGeneratorTest.java** - Tests for validation rules
   - Validation rules generation
   - Null checks
   - Constraint interface

5. **OpenRewriteEntityMetadataGeneratorTest.java** - Tests for entity metadata
   - Metadata generation
   - Record declaration
   - Property handling

6. **OpenRewriteTypeLiteralsTest.java** - Tests for type literals
   - Class literal expressions
   - Type normalization
   - Array type handling

### Documentation

1. **USAGE.md** - Usage guide with examples
2. **README.md** - Overview and architecture documentation

## Implementation Approach

### Wrapper Layer Pattern

Instead of rewriting all generators in-place, we created wrapper classes that:
1. Maintain the JavaParser API (`CompilationUnit` as return type)
2. Internally use OpenRewrite for AST manipulation
3. Allow gradual migration from JavaParser to OpenRewrite
4. Preserve existing code that expects JavaParser APIs

### Why Wrapper Classes

- **Gradual Migration**: Allows transitioning from JavaParser to OpenRewrite without breaking existing callers
- **API Compatibility**: Maintains compatibility with existing code
- **Incremental Refactoring**: Can migrate internal implementation while keeping public API stable

## Usage Examples

### Generating View Interface Entry Points

```java
List<EntryPoint> entryPoints = OpenRewriteViewInterfaceGenerator.entryPointsFor(
    GenLevel.BUILDER,
    "Person"
);

Result result = OpenRewriteViewInterfaceGenerator.generate(
    sourceRoot,
    "com.example.view",
    "Person",
    entryPoints
);
```

### Generating View Builder

```java
Result result = OpenRewriteViewBuilderGenerator.generate(
    outputRoot,
    "com.example.view",
    viewMeta,
    allProperties,
    nestedRecord,
    divergenceReporter
);
```

### Generating Field Enum

```java
FieldBoilerplateGenerator generator = FieldBoilerplateGenerator.builder(
    "com.example.view",
    "Person",
    properties
)
.withEnumTypeName("PersonField")
.withDivergenceSink(divergenceSink)
.build();

generator.generate(outputRoot);
```

### Generating Entity Metadata

```java
Result result = OpenRewriteEntityMetadataGenerator.generate(
    outputRoot,
    "com.example.entity",
    "Person",
    properties
);
```

## Testing

Run all tests with:

```bash
mvn test -pl project-automation
```

Individual test classes are in:
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/`

## Compliance

All generated code complies with:
- **DEC-019**: Source-visible wiring
- **DEC-020**: Cooperative codegen (preserve user edits)
- **DEC-021**: Generator class-file header
- **DEC-022**: Refactor-sensitive naming
- **DEC-029**: Class index by FQN

## Next Steps

### Phase 4: Validation

After completing Phase 3:
1. Review and integrate the new generators
2. Run existing tests to ensure compatibility
3. Proceed to Phase 4: Validation
4. Migrate remaining JavaParser code to OpenRewrite

## Files Created

### Implementation Files
- `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteViewInterfaceGenerator.java`
- `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteViewBuilderGenerator.java`
- `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteFieldBoilerplateGenerator.java`
- `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteValidationGenerator.java`
- `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteEntityMetadataGenerator.java`
- `project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteTypeLiterals.java`

### Test Files
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/OpenRewriteViewInterfaceGeneratorTest.java`
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/OpenRewriteViewBuilderGeneratorTest.java`
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/OpenRewriteFieldBoilerplateGeneratorTest.java`
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/OpenRewriteValidationGeneratorTest.java`
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/OpenRewriteEntityMetadataGeneratorTest.java`
- `project-automation/src/test/java/hr/hrg/rewrite/tooling/OpenRewriteTypeLiteralsTest.java`

### Documentation
- `doc/brainstorm/rewrite-migration/03-codegen/README.md`
- `doc/brainstorm/rewrite-migration/03-codegen/USAGE.md`

## Status Update

**Current**: **Delivered** (confirmed against the tree 2026-09-22). The generators named here are ported
and green in `hipster-entity-tooling` (`ViewBuilderGenerator`, `ViewInterfaceGenerator`,
`ValidationGenerator`, `ViewRecordGenerator`, `FieldBoilerplateGenerator`) and in `jwa-builder`
(`RecordBuilderProcessor`, `ClassMemberProcessor`, `BuilderTransformationEngine`). `03-codegen/` holds the
design notes and usage guide, not code.
**Next**: nothing here; Phase 6 is complete and Phase 7 is next (see `PLAN-SUMMARY.md` § *Phase status*).

## Quick Reference

Generators implemented:
1. `OpenRewriteViewInterfaceGenerator` - Generate view interface from entity
2. `OpenRewriteViewBuilderGenerator` - Generate view builder
3. `OpenRewriteFieldBoilerplateGenerator` - Generate field boilerplate
4. `OpenRewriteValidationGenerator` - Generate validation rules
5. `OpenRewriteEntityMetadataGenerator` - Generate entity metadata
6. `OpenRewriteTypeLiterals` - Type literal utilities

## Migration Notes

- Phase 1 (Foundation) should be completed first
- Phase 2 (Core AST Utilities) should be completed first
- Phase 3 is now complete and ready for use
- Phase 4 (Validation) is the next phase
- Phase 5 (Automation) will follow
- Phase 6 (Migration Checklist) will track progress
- Phase 7 (Testing & Validation) will ensure quality
- Phase 8 (Documentation) will provide complete guides
