# Phase 5: Automation Engine

> **Delivered** (2026-09-22). The classes described below now exist in
> `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/`, with 67 tests. The signatures in
> § *Deliverables* are the **sketch's**, kept as the record of what was planned; § *Status* at the end
> states what was delivered and every place the two disagree. Read that section before copying any
> signature out of this file.

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

Against the delivered API (the sketch's example called `result.getErrors()`, which no longer exists):

```java
AutomationEngine engine = new AutomationEngine();
engine.register(new MyViewInterfaceTransformation());   // a direct, navigable call (DEC-019)
BatchProcessor processor = new BatchProcessor(engine);
try {
    // Report what would change; nothing is written.
    BatchProcessor.BatchReport report = processor.process(sourceRoot, "view-interface");
    System.out.println(report.render());

    ValidationResult result = engine.validate(sourceFile);
    if (!result.isValid()) {
        System.out.println(result.errors());
    }
} finally {
    processor.shutdown();
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

**Current**: **Delivered** (2026-09-22). The ten sketches under
`doc/brainstorm/rewrite-migration/05-automation/` were reviewed line by line and rewritten into
`project-automation/src/main/java/hr/hrg/jcodebuddy/automation/`; nothing was copied unchanged.

| Delivered | Where | What it does |
| --- | --- | --- |
| `Transformation` | same package | text → text, `throws TransformationException`; the extension point |
| `TransformationRegistry` | same package | the one registry, owned by the engine, fail-fast on duplicates, no reflection |
| `AutomationEngine` | same package | `apply`, `applyInPlace`, `applyAll`, `applyAllSequential`, `applySequential`, `validate`, `analyze` |
| `BatchProcessor` + `BatchReport` | same package | parallel and sequential batch runs, a tally as data, `processMatching` |
| `ProjectAutomation` | same package | the facade: register, list, run on a file, run on a tree, validate, analyse |
| `TransformationResult`, `ValidationResult`, `AnalysisResult` | same package | the result records |
| `TransformationException` | same package | the checked refusal a transformation reports |
| `SourceFiles` | same package | which files a run covers: sorted, glob-matched, `target`/`.jcodebuddy`/`.git` excluded |
| `SourceFacts` | same package | package-private; LST structure plus a text line classifier |

### Where the delivered code differs from the signature above

Each difference is a defect the sketch shipped, not a preference:

1. **`apply` returns `TransformationResult`, not `String`.** A string cannot say whether the
   transformation ran, and the sketch's `execute(String, …)` went further and applied a transformation to
   an empty string — a type error *and* a meaningless operation.
2. **Reading and writing are separate calls.** `apply`/`applyAll`/`process` never write;
   `applyInPlace`/`applyAll(root, name, true)`/`processInPlace` do, and only when the text changed. The
   sketch produced output text and left the caller to guess.
3. **`applyAllSequential` returns `Map<String, List<TransformationResult>>`.** The sketch's
   `Map<String, String>` lost the per-file outcome and the failure reason. Each step sees the previous
   step's output; the file is read once and chained in memory.
4. **`BatchProcessor.process` returns a `BatchReport`.** The sketch returned `void` and printed to
   `System.out` from inside the processor, which made the one thing a caller needs to assert on
   unobservable. `BatchReport.render()` produces the text; nothing prints.
5. **File discovery lives in `SourceFiles`, not on the processor**, and it is a `PathMatcher` glob
   against the path *relative to the root* — not `path.toString().matches(glob)` against the absolute
   path, which matched nothing for `*Controller.java`.
6. **`ValidationResult` does not store `isValid`.** It is derived from `errors`, so a stored verdict and
   the reason printed next to it cannot drift apart (note F-44).
7. **`TransformationResult` gained `changed`.** "Ran and changed nothing" is the normal result of a
   second idempotent pass; collapsing it into `succeeded` would report a no-op run as a failure or a
   failure as a no-op.
8. **`ProjectAutomation` has one registry, and no reflective overload.** `registerTransformation` takes
   an instance and registers it *into the engine's* registry — the sketch's facade kept a second registry
   the engine never read, so a transformation registered through the only public entry point could never
   run (test: `registeringOnTheFacadeIsVisibleToTheEngine`). `registerTransformation(String, Class)`
   called itself recursively and would have instantiated a class reflectively — the invisible wiring
   DEC-019 rejects; the overload does not exist. `initialize()` (empty, "called automatically", called by
   nothing) does not exist either.
9. **`TransformationRegistry.get` is typed**: `get(name, Class)` refuses a wrong type at the call, where
   the sketch's `<T> T get(String)` inferred it from the assignment target and failed later with a
   `ClassCastException`. Registration is fail-fast on a duplicate name.
10. **No `ValidationException`.** The sketch declared one; `validate` always produces a verdict (an
    unreadable file is a finding, not the absence of one), so it would have had no caller. It was deleted
    rather than shipped as a class whose javadoc describes behaviour nothing has.
11. **The engine has no thread pool** (the sketch created one and never used it); concurrency is
    `BatchProcessor`'s, whose thread count defaults to the machine's processor count rather than a
    hard-coded eight, and whose `shutdown()` waits ten seconds, not sixty.

**No production `Transformation` is registered yet**, and that is deliberate: the first real ones are the
Phase 3 emitters, and registration belongs to the module that owns them, as a direct call a reader can
navigate to. The registry is the extension point; `ProjectAutomationTest` uses a test transformation to
exercise it.

**Tests**: 67 in `project-automation/src/test/java/hr/hrg/jcodebuddy/automation/` —
`AutomationEngineTest` (22), `TransformationRegistryTest` (9), `SourceFactsTest` (8),
`ProjectAutomationTest` (8), `BatchProcessorTest` (7), `SourceFilesTest` (7), `AutomationResultsTest` (6).
They assert the read/write boundary, the ordering guarantee (a parallel report equals a sequential one),
the glob's real semantics, the line classifier's traps, and the registry's refusals. The module's suite is
81 tests (14 before this phase), 0 failures; the whole reactor was green with it — `clean test`, 1231
tests.

**Next**: Phase 7 (Testing/Validation) was written assuming this layer, so it should be re-scoped against
the classes above before it starts. See `PLAN-SUMMARY.md` § *Phase status*.

## Quick Reference

Key classes in this phase, all in `project-automation/src/main/java/hr/hrg/jcodebuddy/automation/`:
- `Transformation` — text-to-text extension point
- `AutomationEngine` — runs transformations, validates and analyses
- `BatchProcessor` — parallel and sequential batch runs, `BatchReport`
- `TransformationRegistry` — the one registry
- `TransformationResult` / `ValidationResult` / `AnalysisResult` — result records
- `SourceFiles` — file discovery
- `SourceFacts` — package-private facts and the line classifier
- `ProjectAutomation` — the facade
