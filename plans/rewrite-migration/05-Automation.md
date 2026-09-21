# Phase 5: Automation Engine

## Overview

Create automation engine for running OpenRewrite transformations in batch mode. This phase integrates all migrated tools into a cohesive automation system.

## Prerequisites (Phase 4)

Phase 4 must be complete before starting Phase 5:
- All validation rules are migrated
- All analysis tools are migrated
- Code generation tools are migrated (Phase 3)

## Deliverables

### 1. AutomationEngine.java

File: `doc/brainstorm/rewrite-migration/05-automation/AutomationEngine.java`

**Purpose**: Orchestrate running OpenRewrite transformations

**Signature**:
```java
public class AutomationEngine {
    AutomationEngine();
    
    String apply(Path sourceFile, String transformationName);
    List<TransformationResult> applyAll(Path sourceRoot, String transformationName);
    Map<String, String> applyAllSequential(Path sourceRoot, List<String> transformationNames);
    ValidationResult validate(Path sourceFile);
    AnalysisResult analyze(Path sourceFile);
}
```

### 2. BatchProcessor.java

File: `doc/brainstorm/rewrite-migration/05-automation/BatchProcessor.java`

**Purpose**: Process multiple files in parallel

**Signature**:
```java
public class BatchProcessor {
    BatchProcessor(AutomationEngine engine);
    
    void process(Path sourceRoot, String transformationName);
    void processSequential(Path sourceRoot, String transformationName);
    List<Path> findJavaFiles(Path sourceRoot);
    List<Path> findJavaFilesMatching(Path sourceRoot, String globPattern);
    void reportResults(List<Path> processedFiles, int succeeded, int failed);
}
```

### 3. TransformationResult.java

File: `doc/brainstorm/rewrite-migration/05-automation/TransformationResult.java`

```java
public record TransformationResult(
    Path inputFile,
    String transformationName,
    boolean succeeded,
    String output,
    String error,
    long durationMs
) {}
```

### 4. ValidationResult.java

File: `doc/brainstorm/rewrite-migration/05-automation/ValidationResult.java`

```java
public record ValidationResult(
    Path sourceFile,
    boolean isValid,
    List<String> errors,
    List<String> warnings,
    long durationMs
) {}
```

### 5. AnalysisResult.java

File: `doc/brainstorm/rewrite-migration/05-automation/AnalysisResult.java`

```java
public record AnalysisResult(
    Path sourceFile,
    Map<String, Object> analysis,
    List<String> issues,
    long durationMs
) {}
```

### 6. ProjectAutomation.java

File: `doc/brainstorm/rewrite-migration/05-automation/ProjectAutomation.java`

**Purpose**: Integration point with project-automation module

**Signature**:
```java
public class ProjectAutomation {
    ProjectAutomation(AutomationEngine engine);
    
    void registerTransformation(String name, Transformation transformation);
    Transformation getTransformation(String name);
    void execute(String transformationName);
}
```

## Implementation Strategy

### Step 1: Create Data Models

Create `TransformationResult`, `ValidationResult`, `AnalysisResult` records first.

### Step 2: Create AutomationEngine

Implement `AutomationEngine` with:
- Method to apply single transformation
- Method to apply to all files
- Method for sequential transformations
- Validation and analysis methods

### Step 3: Create BatchProcessor

Implement `BatchProcessor` with:
- File discovery methods
- Parallel processing logic
- Sequential processing fallback
- Result reporting

### Step 4: Create ProjectAutomation

Implement `ProjectAutomation` as integration point.

### Step 5: Create Registration

Create transformation registry:

```java
public class TransformationRegistry {
    private final Map<String, Transformation> transformations = new HashMap<>();
    
    public void register(String name, Transformation transformation);
    public <T extends Transformation> T get(String name);
    public List<String> getAll();
}
```

### Step 6: Create Exception Classes

```java
public class TransformationException extends Exception {
    TransformationException(String message);
    TransformationException(String message, Throwable cause);
}

public class ValidationException extends Exception {
    ValidationException(String message);
}
```

## Implementation Steps

1. Create directory: `doc/brainstorm/rewrite-migration/05-automation/`
2. Create data model classes (records)
3. Create `TransformationRegistry`
4. Create `AutomationEngine`
5. Create `BatchProcessor`
6. Create `ProjectAutomation`
7. Create exception classes
8. Create integration tests
9. Run tests and fix issues
10. Optimize performance

## Testing Strategy

Test scenarios:
1. **Single file transformation**: Process one file
2. **Batch processing**: Process multiple files
3. **Error handling**: Test error cases
4. **Parallel processing**: Test parallel execution
5. **Sequential processing**: Test fallback to sequential
6. **Validation**: Test validation logic
7. **Analysis**: Test analysis logic

## Integration Points

### Uses Phase 2
- `NodeTraversal` for file operations
- `AstPrinter` for output
- `SourceManipulation` for text operations

### Uses Phase 3
- Code generation transformations
- Type utilities

### Uses Phase 4
- Validation rules
- Analysis tools

### Used By Phase 6
- Project-automation integration
- Batch codebase processing

## Usage Example

```java
AutomationEngine engine = new AutomationEngine();
BatchProcessor processor = new BatchProcessor(engine);

// Process all files
processor.process(sourceRoot, "view-interface");

// Validate file
ValidationResult result = engine.validate(sourceFile);
if (!result.isValid()) {
    System.out.println(result.getErrors());
}
```

## Architecture Compliance

All automation code must comply with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Status

**Current**: Ready for implementation
**Next**: After Phase 5 completion, proceed to Phase 6

## Quick Reference

Key classes in this phase:
- `AutomationEngine` - Main orchestration engine
- `BatchProcessor` - Batch processing
- `TransformationResult` - Result record
- `ValidationResult` - Validation result record
- `AnalysisResult` - Analysis result record
- `ProjectAutomation` - Integration point
- `TransformationRegistry` - Transformation registry
