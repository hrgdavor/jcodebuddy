# Phase 7: Testing & Validation

> **Not started.** The deliverables below are the plan as written; § *Status* at the end states what exists
> today and the six decisions the re-scope has to make before any of it is implemented. Read that section
> first — several files named here are sketches that never existed, and two deliverables compare against
> JavaParser, which the migration removed.

## Overview

Comprehensive testing and validation of the entire migration. This phase ensures all functionality is preserved, performance is acceptable, and edge cases are handled correctly.

## Prerequisites (Phase 6)

Phase 6 must be complete before starting Phase 7:
- All JavaParser usage has been migrated
- Build passes
- Basic tests pass

## Deliverables

### 1. Unit Tests

Directory: `doc/brainstorm/rewrite-migration/07-testing/unit-tests/`

Files:
- `AstVisitorTests.java`
- `AstManipulatorTests.java`
- `CompilationUnitAdapterTests.java`
- `NodeTraversalTests.java`
- `AstPrinterTests.java`
- `SourceManipulationTests.java`
- `TypeUtilsTests.java`
- `ValidationRuleTests.java`
- `AnalysisToolTests.java`

### 2. Integration Tests

Directory: `doc/brainstorm/rewrite-migration/07-testing/integration-tests/`

Files:
- `CodeGenerationIntegrationTests.java`
- `ValidationIntegrationTests.java`
- `AutomationIntegrationTests.java`
- `MigrationCompletenessTests.java`

### 3. Performance Benchmarks

Directory: `doc/brainstorm/rewrite-migration/07-testing/benchmarks/`

Files:
- `ParseBenchmark.java`
- `TransformationBenchmark.java`
- `MemoryBenchmark.java`
- `BenchmarkReport.md`

### 4. Edge Case Tests

Directory: `doc/brainstorm/rewrite-migration/07-testing/edge-cases/`

Files:
- `EmptyFileTests.java`
- `MalformedCodeTests.java`
- `LargeFileTests.java`
- `ComplexHierarchyTests.java`
- `AnnotationTests.java`
- `GenericTests.java`

### 5. Regression Tests

Directory: `doc/brainstorm/rewrite-migration/07-testing/regression/`

Files:
- `RegressionTestSuite.java`
- `OriginalBehaviorTests.java`
- `OutputComparisonTests.java`

### 6. Test Report Template

File: `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md`

## Implementation Strategy

### Step 1: Create Unit Tests

For each migrated component:

1. **Create test class**: Follow JUnit 5 conventions
2. **Test basic functionality**: Test normal cases
3. **Test edge cases**: Test boundary conditions
4. **Test error handling**: Test error cases
5. **Test performance**: Measure performance

### Step 2: Create Integration Tests

Test full workflows:

1. **Code generation workflow**:
   - Parse source file
   - Generate view interface
   - Validate result
   - Print output

2. **Validation workflow**:
   - Parse source file
   - Run validation rules
   - Check results
   - Report errors

3. **Automation workflow**:
   - Apply transformations
   - Check results
   - Handle errors

### Step 3: Create Performance Benchmarks

Benchmark key operations:

1. **Parse time**: JavaParser vs OpenRewrite
2. **Transformation time**: Measure transformation speed
3. **Memory usage**: Profile memory consumption
4. **Round-trip overhead**: Measure conversion overhead

### Step 4: Create Edge Case Tests

Test edge cases:

1. **Empty files**: Handle empty source
2. **Malformed code**: Handle parse errors
3. **Large files**: Handle large source files
4. **Complex hierarchies**: Handle deep type hierarchies
5. **Annotations**: Handle complex annotations
6. **Generics**: Handle generic types

### Step 5: Create Regression Tests

Ensure original behavior is preserved:

1. **Original behavior**: Test against expected output
2. **Output comparison**: Compare with JavaParser output
3. **Behavior verification**: Verify behavior matches

### Step 6: Run All Tests

1. Run unit tests
2. Run integration tests
3. Run edge case tests
4. Run regression tests
5. Run performance benchmarks
6. Generate test report

## Test Coverage Requirements

### Unit Tests
- 80% code coverage minimum
- All public methods tested
- Edge cases covered

### Integration Tests
- All workflows tested
- Error paths tested
- Integration points tested

### Performance
- Parse time within 20% of JavaParser
- Transformation time within 50% of JavaParser
- Memory usage within 150% of JavaParser

## Implementation Steps

1. Create directory: `doc/brainstorm/rewrite-migration/07-testing/`
2. Create unit test classes
3. Create integration test classes
4. Create performance benchmark classes
5. Create edge case test classes
6. Create regression test classes
7. Create test report template
8. Run all tests
9. Analyze results
10. Fix issues
11. Update tests
12. Generate final test report

## Testing Tools

### JUnit 5
```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class MyTests {
    @Test
    @DisplayName("Test description")
    void testMethod() {
        // Test code
    }
}
```

### Mockito for Mocking
```java
import static org.mockito.Mockito.*;

MyClass mock = mock(MyClass.class);
when(mock.method()).thenReturn(value);
```

### JMH for Benchmarks
```java
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
public class ParseBenchmark {
    @Benchmark
    public void parseJavaParser() {
        // JavaParser parsing
    }
    
    @Benchmark
    public void parseOpenRewrite() {
        // OpenRewrite parsing
    }
}
```

## Test Report Format

```markdown
# Test Report

## Summary
- Total tests: X
- Passed: Y
- Failed: Z
- Coverage: W%

## Unit Tests
- AstVisitorTests: X passed, Y failed
- AstManipulatorTests: X passed, Y failed
- ...

## Integration Tests
- CodeGenerationIntegrationTests: X passed, Y failed
- ValidationIntegrationTests: X passed, Y failed
- ...

## Performance Benchmarks
- Parse time: JavaParser Xms, OpenRewrite Yms
- Transformation time: JavaParser Xms, OpenRewrite Yms
- Memory: JavaParser XMB, OpenRewrite YMB

## Edge Cases
- Empty files: All passed
- Malformed code: All passed
- Large files: All passed
- ...

## Regression Tests
- All original behaviors preserved

## Issues Found
- <issue-description>
- <issue-description>

## Recommendations
- <recommendation>
- ...
```

## Architecture Compliance

All tests must verify:
- DEC-019: Source-visible wiring preserved
- DEC-020: Cooperative codegen preserved
- DEC-021: Generator class-file header preserved
- DEC-022: Refactor-sensitive naming preserved
- DEC-029: Class index by FQN preserved

## Status

**Current**: **Next — re-scope before starting** (2026-09-22). Prerequisites are met: Phase 6 is complete
(34/34 files ported, `javaparser-core` declared by no module, `verify-migration.js` `RESULT: PASS`) and
Phase 5's automation layer is now delivered, so "integration tests for workflows" finally has a subject.
The repository already carries most of what this phase's deliverables ask for, in a different shape: the
whole reactor runs **1231 tests green** (1164 after Phase 6 plus Phase 5's 67), the migrated tooling keeps
its recorded module gates (`hipster-entity-tooling` 361, `merge-java` 601, `hipster-entity-core` 88,
`jwa-builder` 20, `project-automation` 81), and `doc/brainstorm/rewrite-migration/07-testing/` does not
exist yet. `jwa-sidecar` and `java-watch-agent` carry no tests at all — their gate is that they compile —
so "unit tests for each migrated component" would start by creating them.

**Six things the re-scope has to decide, because the plan was written against code that does not exist:**

1. **The unit-test file list names Phase 2's sketches.** `AstVisitorTests`, `AstManipulatorTests`,
   `CompilationUnitAdapterTests`, `NodeTraversalTests`, `AstPrinterTests`, `SourceManipulationTests`,
   `TypeUtilsTests` are classes in a docs tree that never compiled. Their working equivalents are
   `TreeQueries` (traversal, kinds, annotations, type text), `JavaSyntaxCheck` (positions, which the plan
   did not anticipate needing javac for) and `SourceReader`, already covered by `SourceReaderTest`,
   `ParseGuardTest`, `AddonAndInheritanceTest` and the per-generator suites. The list has to be rewritten
   against real class names or it will be implemented as a set of tests for nothing.
2. **`AutomationIntegrationTests` is the one deliverable that is now straightforward.**
   `project-automation/.../automation/` exists with 67 tests; what a Phase 7 integration test can add is an
   end-to-end chained run over a real source tree, using a registered production transformation. Note that
   **no production `Transformation` is registered yet** (`05-Automation.md` § *Status*), so this phase either
   registers the first one or keeps the test-layer transformation.
3. **The comparative benchmarks are impossible as written.** `ParseBenchmark`/`TransformationBenchmark`
   compare JavaParser with OpenRewrite, but Phase 6 removed `javaparser-core` from every module and
   `verify-migration.js` fails if one declares it again. Re-adding it as a test-only dependency to measure
   against it would defeat the migration's own gate; the honest re-scope is absolute measurements
   (parse/transform time and memory against recorded baselines) or no benchmark at all. JMH itself is
   available: `jmh-core` and `jmh-generator-annprocess` are managed in the root POM.
4. **The regression deliverable's comparison baseline is gone** for the same reason. "Compare with
   JavaParser output" is now covered by something stronger the tree already has: the byte-identical
   regeneration tests (`ExampleRegenerationTest`, `FieldEnumLedgerRegenerationTest`,
   `MetadataSourcePathTest`) and the compile gates (`GeneratedSourceCompilesTest`, `AllLevelsCompileTest`,
   `GeneratedAdapterRoundTripTest`).
5. **Most edge cases already have a home.** Empty and malformed source:
   `SourceFactsTest`, `ParseGuardTest`, `SourceReaderTest`, `UnresolvedTypeNameTest`. Deep hierarchies and
   generics: `PolymorphicGenerationTest`, `AddonAndInheritanceTest`, `DeepTrackingWiringTest`,
   `AllLevelsCompileTest`. Annotations: `ViewAnnotationRuleTest`, `AuditableRuleTest`,
   `UnifiedDiffTest`. Large files and annotation-heavy files are the parts with no coverage today.
6. **Mockito is not available.** It is not in the root POM's `dependencyManagement` and the offline
   repository is the only source of artifacts, so "Mockito for Mocking" would need a new dependency. The
   suite currently mocks nothing: the ported code takes its collaborators as constructor arguments or
   parameters, which is why it does not need to.

The coverage and performance targets above (80% coverage, "within 20% of JavaParser") are the plan's
original numbers and are not measurements of anything. Keep them only if a tool is chosen to measure them —
the repository has no coverage tool configured.

**Next**: after the re-scope, Phase 7 completion finishes the migration; Phase 8 (Documentation) follows.

## Quick Reference

Test files to create:
- Unit tests for each component
- Integration tests for workflows
- Performance benchmarks
- Edge case tests
- Regression tests

Test directories:
- `unit-tests/`
- `integration-tests/`
- `benchmarks/`
- `edge-cases/`
- `regression/`
