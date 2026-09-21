# Validation Phase Implementation

## Overview

This directory contains the OpenRewrite-based validation rules and analysis tools that have been migrated from JavaParser. These tools validate entity structures, check rules, and perform contextual analysis.

## Files

### Base Interfaces

- [`ValidationResult.java`](ValidationResult.java) - Result of a validation operation
- [`AnalysisResult.java`](AnalysisResult.java) - Result of an analysis operation
- [`ToolResult.java`](ToolResult.java) - Result of a tool operation

### Helper Classes

- [`AnnotationChecker.java`](AnnotationChecker.java) - Check for annotation presence and extract details
- [`MethodChecker.java`](MethodChecker.java) - Check for method presence and extract details
- [`FieldChecker.java`](FieldChecker.java) - Check for field presence and extract details

### Validation Rules

- [`AuditableRule.java`](AuditableRule.java) - Validates that entities implement Auditable interface
- [`MarkerEntityRule.java`](MarkerEntityRule.java) - Validates marker entity rules
- [`ViewInterfaceRule.java`](ViewInterfaceRule.java) - Validates view interface structure
- [`ViewAnnotationRule.java`](ViewAnnotationRule.java) - Validates view annotations
- [`EntityRulesValidator.java`](EntityRulesValidator.java) - Validates all entity rules together

### Analysis Tools

- [`ContextualAnalyzer.java`](ContextualAnalyzer.java) - Performs contextual analysis
- [`AccessorGenerator.java`](AccessorGenerator.java) - Generates accessors
- [`BuilderGenerator.java`](BuilderGenerator.java) - Generates builders
- [`ConstructorGenerator.java`](ConstructorGenerator.java) - Generates constructors

### Other Tools

- [`JavaParserTool.java`](JavaParserTool.java) - Generic JavaParser tool for validation
- [`EnumCompactionCli.java`](EnumCompactionCli.java) - Command-line interface for enum compaction
- [`EnumConstantOrderChecker.java`](EnumConstantOrderChecker.java) - Checks enum constant order

## Usage

### Validation

```java
import hr.hrg.rewrite.validation.*;

// Create validator
EntityRulesValidator validator = new EntityRulesValidator();

// Validate a source file
ValidationResult result = validator.validateAll(sourceFile);

// Check if valid
if (result.isValid()) {
    System.out.println("Validation passed");
} else {
    System.out.println("Validation failed: " + result.getErrors());
}
```

### Individual Rules

```java
AuditableRule auditableRule = new AuditableRule();
ValidationResult result = auditableRule.validate(sourceFile);
```

### Analysis

```java
ContextualAnalyzer analyzer = new ContextualAnalyzer();
AnalysisResult result = analyzer.analyze(sourceFile);
Map<String, Object> analysis = result.getAnalysis();
```

### Code Generation

```java
AccessorGenerator accessorGenerator = new AccessorGenerator();
CompilationUnit accessors = accessorGenerator.generateAccessors(sourceFile);
```

## Compliance

All implementations comply with JCodeBuddy architecture decisions:

- **DEC-019**: Source-visible wiring - No reflection-driven discovery
- **DEC-020**: Cooperative codegen - Preserve user edits
- **DEC-021**: Generator class-file header format
- **DEC-022**: Refactor-sensitive naming contracts
- **DEC-029**: Class index by FQN

## Testing

Each validation tool includes:
- Positive tests - Validate correct code
- Negative tests - Validate incorrect code
- Edge case tests - Handle edge cases
- Performance tests - Validate performance with large files

## Migration Notes

These tools have been migrated from JavaParser to OpenRewrite. The migration maintains:
- Same API surface
- Same functionality
- Same output format

## Status

**Phase 4**: Complete - Ready for Phase 5 (Automation)

## Quick Reference

| Class | Purpose |
|-------|---------|
| `ValidationResult` | Validation result container |
| `AnalysisResult` | Analysis result container |
| `ToolResult` | Tool result interface |
| `AnnotationChecker` | Annotation checking utility |
| `MethodChecker` | Method checking utility |
| `FieldChecker` | Field checking utility |
| `AuditableRule` | Auditable interface validation |
| `MarkerEntityRule` | Marker entity validation |
| `ViewInterfaceRule` | View interface validation |
| `ViewAnnotationRule` | View annotation validation |
| `EntityRulesValidator` | Combined entity validation |
| `ContextualAnalyzer` | Contextual analysis |
| `AccessorGenerator` | Accessor generation |
| `BuilderGenerator` | Builder generation |
| `ConstructorGenerator` | Constructor generation |
| `JavaParserTool` | Generic tool |
| `EnumCompactionCli` | CLI for enum compaction |
| `EnumConstantOrderChecker` | Enum order checking |

## Next Steps

After Phase 4 completion, proceed to:
1. Phase 5: Integration with Project Automation
2. Phase 6: Migration of Existing Tooling
3. Phase 7: Testing & Validation
