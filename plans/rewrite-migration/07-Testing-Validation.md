# Phase 7: Testing & Validation

> **Delivered** (2026-09-22). The deliverables below are the plan as written and are kept as the record of
> what was planned; several files named here are sketches that never existed, and two deliverables compare
> against JavaParser, which Phase 6 removed. Section *Status* at the end states what was built instead: the
> real test classes, the generated report, the absolute benchmarks, the three production defects the new
> tests found, and every place this page and the delivered code disagree. Read that section first.

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

**Delivered** (2026-09-22), after the re-scope the previous revision of this section demanded. The tests are
real code in module `src/test/java` (not prose in a docs tree), the report is generated from the artifacts a
build leaves behind rather than written by hand, and the benchmarks are absolute baselines. Three production
defects were found by the new tests and fixed in the same change; committed output stayed byte-identical.

Reactor total moved from **1231 to 1326 tests, 0 failures**, and `bun run
scripts/rewrite-migration/verify-migration.js` reports **`RESULT: PASS.`** with 8/8 checks OK.

### What exists now

| Deliverable | Path | Measured |
| --- | --- | --- |
| Position queries (`TreeQueries`) | `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/TreeQueriesTest.java` | 40 tests |
| Validity and positions (`JavaSyntaxCheck`) | `.../JavaSyntaxCheckTest.java` | 22 tests |
| Read-path entry points (`SourceReader`) | `.../SourceReaderTest.java`, nested `OtherEntryPoints` | +4 tests (16 in the class) |
| Large and annotation-heavy sources | `.../LargeSourceEdgeCaseTest.java` | 4 tests |
| Regression sweep over the real tree | `.../MigrationCompletenessTest.java` | 3 tests over 283 files |
| Automation integration | `project-automation/src/test/java/hr/hrg/jcodebuddy/automation/AutomationChainIntegrationTest.java` | 4 tests |
| Agent tool seam | `java-watch-agent/src/test/java/hr/hrg/watch2/agent/tools/ToolSeamTest.java` | 18 tests (module had none) |
| JMH benchmarks | `.../ReadPathJmhBenchmark.java`, `.../PositionQueryJmhBenchmark.java` | 18 results, `jmh` profile |
| Benchmark runner (Bun) | `scripts/rewrite-migration/run-tooling-benchmarks.js` | refuses a non-25 JVM |
| Report generator (Bun) | `scripts/rewrite-migration/generate-test-report.js` | +5 Bun tests, 35 in the file |
| Generated report | `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md` | 1326 tests, 128 suites |
| Benchmark write-up | `doc/brainstorm/rewrite-migration/07-testing/benchmarks/BenchmarkReport.md` | rendered from `benchmarks.json` |
| Captured gate run | `doc/brainstorm/rewrite-migration/07-testing/gate-run.txt` | the input the report reads |

Per module: `hipster-entity-tooling` 361 to 434, `project-automation` 81 to 85, `java-watch-agent` 0 to 18;
every other module unchanged (`merge-java` 601, `hipster-entity-core` 88, `hipster-entity-test` 31,
`hipster-entity-jackson` 26, `jwa-builder` 20, `metadata-arena` 8, `metadata-server` 5, `hipster-entity-api` 4,
`java-watch-core` 3, `java-watch-scp` 3, `hipster-ioc-test` 0).

### The six decisions, answered

1. **The unit-test list was rewritten against real class names.** The plan's `AstVisitorTests`,
   `NodeTraversalTests`, `AstPrinterTests`, `TypeUtilsTests` and friends name Phase 2 sketches that never
   compiled. What exists instead is one suite per class the migration actually produced: `TreeQueriesTest`
   (32 names that had never been called directly by any test, plus the quirks they have),
   `JavaSyntaxCheckTest`, and the four `SourceReader` entry points no generator test reached
   (`readUnit`, `problemsIn`, `readFragmentUnit`, `reportUnparseable`).
2. **The automation integration test runs a chain over a generated tree.** `AutomationChainIntegrationTest`
   drives `EntityRegenerationWatcher.onBatch` to produce real committed-shaped source, asserts every file it
   wrote is readable by the same reader that wrote it, then runs a three-step `applyAllSequential` chain over
   the whole tree and compares the engine's structural facts with `TreeQueries`' own count of the same file.
   The transformations are declared in the test and registered by `new`, in source-visible order (AGENTS.md
   section 1). **No production `Transformation` was invented for it**: Phase 5 shipped the engine with none
   registered, and a phase about testing is the wrong place to add a feature.
3. **Comparative benchmarks are gone; absolute baselines are in.** `javaparser-core` is declared by no module
   and `verify-migration.js` fails if one declares it again, so re-adding it as a test-only dependency to
   measure against would break the migration's own gate. Two JMH classes measure the read path and the
   position queries instead, following the repository's existing convention (module `jmh` profile,
   `-proc:full`, `*JmhBenchmark` in `src/test/java`). Headline numbers, JDK 25.0.3, one fork: a read costs
   roughly 30-55 ms regardless of file size (56 ms for a five-line fragment, 165 ms for the largest file in
   the repository), the F-34 javac guard is about 60% of a cold read and about 0% of a warm one, and a cached
   position lookup is nanoseconds. Full table and findings: `benchmarks/BenchmarkReport.md`.
4. **Regression comparison without JavaParser is stronger than the plan's version.** Instead of diffing two
   parsers' output, `MigrationCompletenessTest` sweeps all 283 `src/main/java` files of 17 modules and asserts,
   per file: javac validity agrees with `SourceReader.readText().readable()` in both directions, every recorded
   type/member/annotation line actually declares the name it was recorded for, every span is a valid half-open
   range inside the file, and the LST's type and method counts match javac's. The byte-identical regeneration
   tests (`ExampleRegenerationTest`, `FieldEnumLedgerRegenerationTest`, `MetadataSourcePathTest`) remain the
   output comparison, and they are what proved the three fixes below changed no committed file.
5. **The edge cases that fail safely today are pinned, not wished for.** `LargeSourceEdgeCaseTest` covers a
   ~1 MB interface with 2000 accessor pairs (positions asserted arithmetically, member text asserted verbatim
   on both sides of the 32767-byte boundary), a 1001-arm `switch`, and seven-level nesting. Empty and
   malformed source, deep hierarchies, generics and annotations already had homes
   (`SourceFactsTest`, `ParseGuardTest`, `UnresolvedTypeNameTest`, `PolymorphicGenerationTest`,
   `AddonAndInheritanceTest`, `DeepTrackingWiringTest`, `ViewAnnotationRuleTest`), and are listed rather than
   duplicated. Nothing here asserts a duration except a hang-detector ceiling of 60 s.
6. **Mockito: not used, and the plan's reason was wrong.** `mockito-core` 5.13.0 *is* in the offline
   repository, so "not available" was a factual error. The honest reason is that the suite mocks nothing: the
   ported code takes its collaborators as constructor arguments or parameters, so a mock would only stand in
   for a real object that is cheaper to construct. The agent tests follow the same rule with a hand-written
   `StubTool` implementing the `ActionTool` SPI.

The plan's 80% coverage target and "within 20% of JavaParser" were dropped: the repository has no coverage
tool configured, and the second number has no denominator left. Neither is a measurement of anything.

### Issues found

Three production defects, all in the position-lookup half, all fixed and all verified against committed
output (`ExampleRegenerationTest` still byte-identical):

1. `TreeQueries.typesWithEnclosing` returned the enclosing chain innermost-first, contradicting its own
   javadoc and `JavaSyntaxCheck.TypePosition.enclosingNames()`. Consequences: `lineOfChained` missed (returned
   -1) and DEC-029 fully-qualified names came out reversed. Fixed with a reversed copy of the walk stack;
   pinned by `aThreeDeepChainIsOutermostFirstResolvesItsOwnLine`.
2. `JavaSyntaxCheck.namePositionIn` let an annotation or a qualified use steal a declaration's name line:
   `@Foo` and `Outer.Foo` both matched before the real declaration. Fixed by rejecting a match preceded by `@`
   or `.`; pinned by `anAnnotationNamedLikeItsTypeDoesNotStealTheNameLine` and
   `aDeclarationNamedLikeItsOwnAnnotation`.
3. The same scan matched inside string literals and comments, so
   `@FieldSource(name = "birthDate") LocalDate birthDate();` reported the literal's line. Found only by the
   2000-member scale test. Latent (no committed source uses that annotation shape) and fixed with an
   `insideLiteralOrComment` walk reusing the existing `skipQuoted`/`skipComment` helpers; pinned by
   `anAnnotationArgumentRepeatingTheMemberNameDoesNotStealIt`.

Documented behaviour that measurement contradicted, recorded in the tests rather than changed:

- `SourceReader.problemsIn` is empty even for files javac rejects (measured on the F-34 enum and on a
  truncated method body), which is exactly why `readText().readable()` is the verdict and the messages are
  detail. `SourceReaderTest.OtherEntryPoints` pins both halves.
- `SourceReader.readFragmentUnit` does not throw for a fragment that is not an expression: the wrapper parses
  it as garbage and returns it, so the javadoc's `IllegalArgumentException` promise covers only failures
  OpenRewrite itself reports.
- `ToolRegistry.getAllTools()` iterates `HashMap` bucket order, not registration order, and
  `HelloTool.isApplicable()` returns true unconditionally (the `// @gen` trigger is matched in
  `ContextualAnalyzer`/`ActionEngine`, not in the tool). Both pinned by `ToolSeamTest`; no production code
  touched.
- A plain inner test class contributes **zero** tests and the build stays green: the four new `SourceReader`
  cases did not run until the class carried `@Nested`. Worth knowing before trusting a test count.

### Deliberately not done

- No `ValidationRule`, `AnalysisTool` or `AstVisitor` tests: those classes never existed outside the plan.
- No `unit-tests/`, `integration-tests/`, `benchmarks/`, `edge-cases/` or `regression/` directories. Tests live
  in the module's `src/test/java` next to the code they pin, and benchmarks in the same tree under the module's
  `jmh` profile, matching how the other 1231 tests are organised.
- No production `Transformation` registered to give the integration test a subject.
- No coverage tool, no comparative benchmark, no timing assertions in unit tests.

### Verification

```
cmd /c "scripts\mvn-jdk25.cmd -o -Dmaven.compiler.useIncrementalCompilation=false clean test"
# Tests run: 1326, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS

bun run scripts/rewrite-migration/verify-migration.js
# RESULT: PASS.

cd scripts && bun test rewrite-migration/rewrite-migration.test.js
# 35 pass, 0 fail

bun run scripts/rewrite-migration/run-tooling-benchmarks.js
# writes benchmarks/benchmarks.json and latest.json, then BenchmarkReport.md is rendered from them

bun run scripts/rewrite-migration/generate-test-report.js --gate-out doc/brainstorm/rewrite-migration/07-testing/gate-run.txt
# Wrote doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md: 1326 tests, 128 suites, gate RESULT: PASS.
```

Keep the gate capture out of `target/`: a `clean` build deletes it, and the generator then reports the gate as
not supplied rather than inventing a verdict.

**Next**: Phase 8 (Documentation) - the migration itself is complete with this phase.

## Quick Reference

Test files delivered:
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/TreeQueriesTest.java`
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheckTest.java`
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/LargeSourceEdgeCaseTest.java`
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/MigrationCompletenessTest.java`
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/SourceReaderTest.java` (extended)
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ReadPathJmhBenchmark.java`
- `hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/PositionQueryJmhBenchmark.java`
- `project-automation/src/test/java/hr/hrg/jcodebuddy/automation/AutomationChainIntegrationTest.java`
- `java-watch-agent/src/test/java/hr/hrg/watch2/agent/tools/ToolSeamTest.java`

Scripts and reports:
- `scripts/rewrite-migration/run-tooling-benchmarks.js`
- `scripts/rewrite-migration/generate-test-report.js`
- `scripts/rewrite-migration/rewrite-migration.test.js` (extended)
- `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md`
- `doc/brainstorm/rewrite-migration/07-testing/benchmarks/BenchmarkReport.md`
- `doc/brainstorm/rewrite-migration/07-testing/benchmarks/benchmarks.json`
- `doc/brainstorm/rewrite-migration/07-testing/gate-run.txt`

