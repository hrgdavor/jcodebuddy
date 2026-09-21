# Phase 3: Code Generation Tools - Usage Guide

## Overview

This directory contains the OpenRewrite-based code generation tools that replace the JavaParser-based generators from `hipster-entity-tooling`.

## Architecture

### Wrapper Layer (project-automation)

All wrapper classes in `project-automation/src/main/java/hr/hrg/rewrite/tooling/` maintain the JavaParser API (`CompilationUnit` as return type) while internally using OpenRewrite.

### Original Layer (hipster-entity-tooling)

Original JavaParser-based generators remain in `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/`.

## Files

### Wrapper Classes (JavaParser API Compatibility)

- `OpenRewriteViewInterfaceGenerator.java` - Generates view interfaces
- `OpenRewriteViewBuilderGenerator.java` - Generates view builders  
- `OpenRewriteFieldBoilerplateGenerator.java` - Generates field boilerplate
- `OpenRewriteValidationGenerator.java` - Generates validation rules
- `OpenRewriteEntityMetadataGenerator.java` - Generates entity metadata
- `OpenRewriteTypeLiterals.java` - Type literal utilities

### Usage Examples

#### Generating View Interface Entry Points

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

#### Generating View Builder

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

#### Generating Field Enum

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

#### Generating Entity Metadata

```java
Result result = OpenRewriteEntityMetadataGenerator.generate(
    outputRoot,
    "com.example.entity",
    "Person",
    properties
);
```

## Migration Notes

### Phase 1: Foundation

Ensure Phase 1 is complete before using Phase 3 tools:
- OpenRewrite dependencies added to pom.xml
- API compatibility layer is functional
- Conversion utilities support round-trip between JavaParser and OpenRewrite

### Phase 2: Core AST Utilities

Ensure Phase 2 is complete before using Phase 3 tools:
- NodeTraversal for AST traversal
- AstPrinter for printing
- TypeUtils for type checking
- SourceManipulation for text manipulation

### Phase 3: Code Generation

This phase is now available:
- ViewInterfaceGenerator wrapper
- ViewBuilderGenerator wrapper
- FieldBoilerplateGenerator wrapper
- ValidationGenerator wrapper
- EntityMetadataGenerator wrapper
- TypeLiterals utilities

### Phase 4: Validation

Coming soon:
- Validation rules integration
- Divergence detection
- Source preservation checks

## Testing

Run all tests with:

```bash
mvn test -pl project-automation
```

Individual test classes:
- `OpenRewriteViewInterfaceGeneratorTest`
- `OpenRewriteViewBuilderGeneratorTest`
- `OpenRewriteFieldBoilerplateGeneratorTest`
- `OpenRewriteValidationGeneratorTest`
- `OpenRewriteEntityMetadataGeneratorTest`
- `OpenRewriteTypeLiteralsTest`

## Compliance

All generated code complies with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Next Steps

After completing Phase 3:
1. Review and integrate the new generators
2. Run existing tests to ensure compatibility
3. Proceed to Phase 4: Validation
4. Migrate remaining JavaParser code to OpenRewrite
