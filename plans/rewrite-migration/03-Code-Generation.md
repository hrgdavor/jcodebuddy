# Phase 3: Code Generation Tools

## Overview

Migrate all code generation tools from JavaParser to OpenRewrite. These tools generate View interfaces, builders, boilerplate code, validation rules, and entity metadata.

## Prerequisites (Phase 2)

Phase 2 must be complete before starting Phase 3:
- Core AST utilities (`NodeTraversal`, `AstPrinter`, `SourceManipulation`, `TypeUtils`) are implemented and tested
- OpenRewrite visitor pattern is working correctly
- Type utilities support all required type operations

## Files to Migrate

### 1. ViewInterfaceGenerator.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java`

**New Location**: Same file (rewrite in place)

**Key Changes**:
- Replace JavaParser imports with OpenRewrite equivalents
- Convert `CompilationUnit` handling to `SourceFile`
- Convert `ClassOrInterfaceDeclaration` to `TypeTree`
- Use `NodeTraversal` from Phase 2 for traversal
- Use `AstPrinter` from Phase 2 for printing

**Signature**:
```java
public class ViewInterfaceGenerator {
    public CompilationUnit generateViewInterface(CompilationUnit compilationUnit)
}
```

### 2. ViewBuilderGenerator.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java`

**Key Changes**:
- Use visitor pattern to find methods
- Generate builder methods for view interfaces
- Handle method parameters and return types

**Signature**:
```java
public class ViewBuilderGenerator {
    public CompilationUnit generateViewBuilder(CompilationUnit compilationUnit)
}
```

### 3. FieldBoilerplateGenerator.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java`

**Key Changes**:
- This is the most complex generator
- Handle switch expressions and complex AST structures
- May need temporary JavaParser compatibility layer

**Signature**:
```java
public class FieldBoilerplateGenerator {
    public CompilationUnit generateBoilerplate(CompilationUnit compilationUnit)
}
```

### 4. ValidationGenerator.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java`

**Signature**:
```java
public class ValidationGenerator {
    public CompilationUnit generateValidationRules(CompilationUnit compilationUnit)
}
```

### 5. EntityMetadataGenerator.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java`

**Signature**:
```java
public class EntityMetadataGenerator {
    public CompilationUnit generateEntityMetadata(CompilationUnit compilationUnit)
}
```

### 6. TypeLiterals.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java`

**Signature**:
```java
public class TypeLiterals {
    // Methods for getting type literals
}
```

## Implementation Strategy

### Step 1: Create Wrapper Classes

Before rewriting generators, create wrapper classes that maintain JavaParser API:

```java
package hr.hrg.rewrite.tooling;

public class OpenRewriteViewInterfaceGenerator {
    public static CompilationUnit generateViewInterface(SourceFile sourceFile) {
        // Implementation using OpenRewrite
    }
}
```

### Step 2: Migrate Each Generator

For each generator:

1. **Analyze original code**: Understand what it does
2. **Identify JavaParser calls**: List all JavaParser types and methods used
3. **Create OpenRewrite equivalents**: Use visitor pattern and Phase 2 utilities
4. **Rewrite logic**: Convert logic to use OpenRewrite
5. **Test**: Ensure output matches original JavaParser version

### Step 3: Handle Complex Cases

**Switch Expressions**: OpenRewrite has limited support for switch expressions. May need to:
- Keep original switch expression structure
- Use `visitSwitchExpression` if needed
- Or convert to if-else chains

**Type Annotations**: Handle type annotations carefully:
- Use visitor pattern to traverse type annotations
- Preserve annotation structure

### Step 4: Handle Lexical Preservation

For round-trip conversion:
- Use `LexicalPreservingPrinter` equivalent
- Or use `SourceFile.print()` method
- Test that source code is preserved correctly

## Implementation Steps

1. Create directory: `doc/brainstorm/rewrite-migration/03-codegen/`
2. Create wrapper class for each generator
3. Migrate `ViewInterfaceGenerator`
4. Migrate `ViewBuilderGenerator`
5. Migrate `FieldBoilerplateGenerator`
6. Migrate `ValidationGenerator`
7. Migrate `EntityMetadataGenerator`
8. Migrate `TypeLiterals`
9. Create integration tests
10. Run all tests and fix issues

## Testing Strategy

For each migrated generator:
1. **Unit test**: Test with simple class
2. **Integration test**: Test with full entity hierarchy
3. **Output comparison**: Compare output with JavaParser version
4. **Lexical preservation test**: Verify source code is preserved

## Integration Points

### Uses Phase 2
- `NodeTraversal` for AST traversal
- `AstPrinter` for printing
- `TypeUtils` for type checking

### Used By Phase 4
- Validation rules use generated code
- Entity metadata uses generated interfaces

## Architecture Compliance

All generated code must comply with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Status

**Current**: Ready for implementation
**Next**: After Phase 3 completion, proceed to Phase 4

## Quick Reference

Generators to migrate:
1. `ViewInterfaceGenerator` - Generate view interface from entity
2. `ViewBuilderGenerator` - Generate view builder
3. `FieldBoilerplateGenerator` - Generate field boilerplate
4. `ValidationGenerator` - Generate validation rules
5. `EntityMetadataGenerator` - Generate entity metadata
6. `TypeLiterals` - Type literal utilities
