# Phase 4: Validation & Analysis Tools

## Overview

Migrate all validation rules and analysis tools from JavaParser to OpenRewrite. These tools validate entity structures, check rules, and perform contextual analysis.

## Prerequisites (Phase 3)

Phase 3 must be complete before starting Phase 4:
- All code generation tools are migrated and working
- AST traversal utilities work correctly
- Type utilities support all required operations

## Files to Migrate

### 1. Validation Rules

#### AuditableRule.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/AuditableRule.java`

**Purpose**: Validates that entities implement Auditable interface

**Signature**:
```java
public class AuditableRule {
    public ValidationResult validate(SourceFile sourceFile)
}
```

#### MarkerEntityRule.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java`

**Purpose**: Validates marker entity rules

**Signature**:
```java
public class MarkerEntityRule {
    public ValidationResult validate(SourceFile sourceFile)
}
```

#### ViewInterfaceRule.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java`

**Purpose**: Validates view interface structure

**Signature**:
```java
public class ViewInterfaceRule {
    public ValidationResult validate(SourceFile sourceFile)
}
```

#### ViewAnnotationRule.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewAnnotationRule.java`

**Purpose**: Validates view annotations

**Signature**:
```java
public class ViewAnnotationRule {
    public ValidationResult validate(SourceFile sourceFile)
}
```

#### EntityRulesValidator.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRulesValidator.java`

**Purpose**: Validates all entity rules

**Signature**:
```java
public class EntityRulesValidator {
    public ValidationResult validateAll(SourceFile sourceFile)
}
```

### 2. Analysis Tools

#### ContextualAnalyzer.java

**Original Location**: `java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java`

**Purpose**: Performs contextual analysis

**Signature**:
```java
public class ContextualAnalyzer {
    public AnalysisResult analyze(SourceFile sourceFile)
}
```

#### AccessorGenerator.java

**Original Location**: `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java`

**Purpose**: Generates accessors

**Signature**:
```java
public class AccessorGenerator {
    public CompilationUnit generateAccessors(SourceFile sourceFile)
}
```

#### BuilderGenerator.java

**Original Location**: `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java`

**Purpose**: Generates builders

**Signature**:
```java
public class BuilderGenerator {
    public CompilationUnit generateBuilder(SourceFile sourceFile)
}
```

#### ConstructorGenerator.java

**Original Location**: `java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java`

**Purpose**: Generates constructors

**Signature**:
```java
public class ConstructorGenerator {
    public CompilationUnit generateConstructors(SourceFile sourceFile)
}
```

### 3. Additional Validation Tools

#### JavaParserTool.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/JavaParserTool.java`

**Purpose**: Generic JavaParser tool for validation

**Signature**:
```java
public class JavaParserTool {
    public ToolResult execute(SourceFile sourceFile)
}
```

#### EnumCompactionCli.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java`

**Purpose**: Command-line interface for enum compaction

**Signature**:
```java
public class EnumCompactionCli {
    public void execute(String[] args)
}
```

#### EnumConstantOrderChecker.java

**Original Location**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java`

**Purpose**: Checks enum constant order

**Signature**:
```java
public class EnumConstantOrderChecker {
    public ValidationResult check(SourceFile sourceFile)
}
```

## Implementation Strategy

### Step 1: Create Validation Framework

Create base classes and interfaces:

```java
package hr.hrg.rewrite.validation;

public interface ValidationResult {
    boolean isValid();
    List<String> getErrors();
    List<String> getWarnings();
}

public interface AnalysisResult {
    Map<String, Object> getAnalysis();
}

public interface ToolResult {
    boolean succeeded();
    String getMessage();
}
```

### Step 2: Migrate Each Rule

For each validation rule:

1. **Analyze original code**: Understand validation logic
2. **Identify JavaParser calls**: List all JavaParser types and methods
3. **Rewrite using visitor pattern**: Convert to OpenRewrite visitor
4. **Handle edge cases**: Test with various input structures
5. **Test**: Ensure validation results match original

### Step 3: Handle Validation Logic

**Finding Annotations**:
- Use `findAnnotations()` from Phase 2 utilities
- Check for specific annotation presence
- Extract annotation arguments

**Finding Methods**:
- Use `findMethods()` from Phase 2 utilities
- Check method signatures
- Validate method implementations

**Finding Fields**:
- Use `findFields()` from Phase 2 utilities
- Check field types and modifiers
- Validate field structure

### Step 4: Create Helper Classes

```java
package hr.hrg.rewrite.validation;

public class AnnotationChecker {
    public boolean hasAnnotation(SourceFile sourceFile, String fullyQualifiedName)
    public List<AnnotationTree> getAnnotations(SourceFile sourceFile)
}

public class MethodChecker {
    public boolean hasMethod(SourceFile sourceFile, String className, String methodName)
    public MethodTree getMethod(SourceFile sourceFile, String className, String methodName)
}

public class FieldChecker {
    public boolean hasField(SourceFile sourceFile, String className, String fieldName)
    public FieldTree getField(SourceFile sourceFile, String className, String fieldName)
}
```

## Implementation Steps

1. Create directory: `doc/brainstorm/rewrite-migration/04-validation/`
2. Create base interfaces and helper classes
3. Migrate `AuditableRule`
4. Migrate `MarkerEntityRule`
5. Migrate `ViewInterfaceRule`
6. Migrate `ViewAnnotationRule`
7. Migrate `EntityRulesValidator`
8. Migrate `ContextualAnalyzer`
9. Migrate `AccessorGenerator`
10. Migrate `BuilderGenerator`
11. Migrate `ConstructorGenerator`
12. Migrate `JavaParserTool`
13. Migrate `EnumCompactionCli`
14. Migrate `EnumConstantOrderChecker`
15. Create integration tests
16. Run all tests and fix issues

## Testing Strategy

For each validation tool:
1. **Positive test**: Validate correct code
2. **Negative test**: Validate incorrect code
3. **Edge case test**: Handle edge cases
4. **Performance test**: Validate performance with large files

## Integration Points

### Uses Phase 2
- `NodeTraversal` for AST traversal
- `AstPrinter` for printing results
- `TypeUtils` for type checking

### Uses Phase 3
- Generated code structure from Phase 3

### Used By Phase 6
- Validation tools are used in project-automation
- Batch validation of codebase

## Architecture Compliance

All validation logic must comply with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Status

**Current**: Ready for implementation
**Next**: After Phase 4 completion, proceed to Phase 5

## Quick Reference

Tools to migrate:
1. Validation Rules:
   - `AuditableRule`
   - `MarkerEntityRule`
   - `ViewInterfaceRule`
   - `ViewAnnotationRule`
   - `EntityRulesValidator`
2. Analysis Tools:
   - `ContextualAnalyzer`
   - `AccessorGenerator`
   - `BuilderGenerator`
   - `ConstructorGenerator`
3. Other Tools:
   - `JavaParserTool`
   - `EnumCompactionCli`
   - `EnumConstantOrderChecker`
