# OpenRewrite Migration Plan: From JavaParser to OpenRewrite

## Executive Summary

This document outlines a multi-step plan to migrate the JCodeBuddy project from JavaParser to OpenRewrite's core module. The migration will leverage OpenRewrite's visitor pattern and AST manipulation capabilities while maintaining compliance with project architecture decisions (DEC-019, DEC-020, DEC-021, DEC-022, DEC-029).

**Target**: Use OpenRewrite core module (not Maven plugin) for programmatic AST manipulation.

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Migration Strategy](#2-migration-strategy)
3. [Phase 1: Foundation & Infrastructure](#3-phase-1-foundation--infrastructure)
4. [Phase 2: Core AST Utilities](#4-phase-2-core-ast-utilities)
5. [Phase 3: Code Generation Tools](#5-phase-3-code-generation-tools)
6. [Phase 4: Validation & Analysis Tools](#6-phase-4-validation--analysis-tools)
7. [Phase 5: Integration with Project Automation](#7-phase-5-integration-with-project-automation)
8. [Phase 6: Migration of Existing Tooling](#8-phase-6-migration-of-existing-tooling)
9. [Phase 7: Testing & Validation](#9-phase-7-testing--validation)
10. [Rollback Plan](#10-rollback-plan)
11. [Risk Assessment](#11-risk-assessment)
12. [Timeline & Milestones](#12-timeline--milestones)

---

## 1. Current State Analysis

### 1.1 JavaParser Usage Locations

Based on codebase scan, JavaParser is used in:

| Module | Location | Primary Use Cases |
|--------|----------|-------------------|
| `hipster-entity-tooling` | `src/main/java/hr/hrg/hipster/entity/tooling/` | View generation, boilerplate generation, metadata extraction, validation rules |
| `jwa-builder` | `src/main/java/hr/hrg/watch2/builder/` | Record builder generation |
| `java-watch-agent` | `src/main/java/hr/hrg/watch2/agent/` | Contextual analysis, builder generation |
| `webview/webview-jetbrains` | `src/main/java/hr/hrg/jetbrains/webview/` | LSP server support |
| `project-automation` | `src/main/java/` | Various tooling utilities |

### 1.2 Key JavaParser Components Used

- `JavaParser` - AST parsing
- `CompilationUnit` - Compilation unit representation
- `ClassOrInterfaceDeclaration` - Class declarations
- `MethodDeclaration` - Method declarations
- `FieldDeclaration` - Field declarations
- `RecordDeclaration` - Record declarations
- `EnumDeclaration` - Enum declarations
- `AnnotationExpr` - Annotation expressions
- `LexicalPreservingPrinter` - Source code printing

### 1.3 Project Architecture Constraints

From AGENTS.md rules:
- **DEC-019**: Source-visible wiring, no reflection-driven discovery
- **DEC-020**: Cooperative codegen, preserve user-tweaked generated blocks
- **DEC-021**: Generator class-file header format
- **DEC-022**: Refactor-sensitive naming contracts
- **DEC-029**: Class index by FQN, not surrogate IDs

---

## 2. Migration Strategy

### 2.1 OpenRewrite Core Module Architecture

OpenRewrite uses a **visitor pattern** for AST traversal and manipulation:

```java
// Example visitor pattern structure
public class MyVisitor extends JavaVisitor<Void> {
    @Override
    public Void visitClass(
        ClassDeclaration clazz,
        Context context
    ) {
        // Handle class declaration
        return super.visitClass(clazz, context);
    }

    @Override
    public Void visitMethod(
        MethodDeclaration method,
        Context context
    ) {
        // Handle method declaration
        return super.visitMethod(method, context);
    }
    // ...
}
```

### 2.2 Migration Approach

1. **Incremental migration**: Migrate one tool at a time, validate, then proceed
2. **API compatibility layer**: Create wrapper classes that maintain JavaParser API surface
3. **Gradual replacement**: Replace JavaParser calls with OpenRewrite equivalents
4. **Testing at each step**: Ensure functionality is preserved

### 2.3 OpenRewrite LST Types Mapping

| JavaParser Type | OpenRewrite Equivalent |
|-----------------|------------------------|
| `CompilationUnit` | `JavaType` / `Tree` |
| `ClassOrInterfaceDeclaration` | `TypeTree` / `ClassTree` |
| `MethodDeclaration` | `MethodTree` |
| `FieldDeclaration` | `FieldTree` |
| `RecordDeclaration` | `TypeTree` (with record modifier) |
| `EnumDeclaration` | `TypeTree` (with enum modifier) |
| `AnnotationExpr` | `AnnotationTree` |

---

## 3. Phase 1: Foundation & Infrastructure

### 3.1 Goals

- Set up Maven dependencies for OpenRewrite core module
- Create utility classes for AST manipulation
- Implement conversion utilities between JavaParser and OpenRewrite ASTs
- Establish testing infrastructure

### 3.2 Tasks

#### 3.2.1 Maven Dependency Setup

**File**: `pom.xml` (parent)

```xml
<properties>
    <openrewrite.version>8.40.1</openrewrite.version>
    <!-- Keep javaparser.version for backward compatibility during migration -->
    <javaparser.version>3.28.0</javaparser.version>
</properties>

<dependencies>
    <!-- OpenRewrite Core -->
    <dependency>
        <groupId>org.openrewrite</groupId>
        <artifactId>rewrite-core</artifactId>
        <version>${openrewrite.version}</version>
    </dependency>
    <dependency>
        <groupId>org.openrewrite</groupId>
        <artifactId>rewrite-java</artifactId>
        <version>${openrewrite.version}</version>
    </dependency>
    <dependency>
        <groupId>org.openrewrite</groupId>
        <artifactId>rewrite-xml</artifactId>
        <version>${openrewrite.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### 3.2.2 Create API Compatibility Layer

**Directory**: `project-automation/src/main/java/hr/hrg/rewrite/api/`

**Files to create**:

1. `AstVisitor.java` - Base visitor interface
2. `AstManipulator.java` - High-level manipulation utilities
3. `CompilationUnitAdapter.java` - Adapter between JavaParser and OpenRewrite
4. `AstPrinter.java` - Source code printing utilities

#### 3.2.3 Create Utility Modules

**Directory**: `project-automation/src/main/java/hr/hrg/rewrite/util/`

**Files to create**:

1. `AstConversion.java` - Conversion utilities between AST formats
2. `TypeUtils.java` - Type manipulation utilities
3. `NodeTraversal.java` - Traversal utilities
4. `SourceManipulation.java` - Source code modification utilities

### 3.3 Deliverables

- [ ] Updated parent `pom.xml` with OpenRewrite dependencies
- [ ] `project-automation` module with API compatibility layer
- [ ] Comprehensive testing infrastructure
- [ ] Documentation for OpenRewrite API usage

---

## 4. Phase 2: Core AST Utilities

### 4.1 Goals

- Implement basic AST traversal utilities
- Create type manipulation utilities
- Implement source printing utilities
- Handle lexical preservation for round-trip conversion

### 4.2 Tasks

#### 4.2.1 Traversal Utilities

**File**: `project-automation/src/main/java/hr/hrg/rewrite/util/NodeTraversal.java`

```java
package hr.hrg.rewrite.util;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import java.util.List;
import java.util.stream.Collectors;

public class NodeTraversal {

    /**
     * Traverse all class declarations in a compilation unit.
     */
    public static List<TypeTree> findAllClasses(Tree tree) {
        // Implementation using OpenRewrite visitor pattern
        return tree.findDescendantsOfType(Tree.class)
            .filter(t -> t instanceof ClassTree)
            .collect(Collectors.toList());
    }

    /**
     * Find all methods in a class.
     */
    public static List<MethodTree> findAllMethods(TypeTree classTree) {
        // Implementation
        return List.of(); // TODO
    }

    /**
     * Find all fields in a class.
     */
    public static List<FieldTree> findAllFields(TypeTree classTree) {
        // Implementation
        return List.of(); // TODO
    }

    /**
     * Find all annotations on a class.
     */
    public static List<AnnotationTree> findAllAnnotations(TypeTree classTree) {
        // Implementation
        return List.of(); // TODO
    }
}
```

#### 4.2.2 Source Printing

**File**: `project-automation/src/main/java/hr/hrg/rewrite/util/AstPrinter.java`

```java
package hr.hrg.rewrite.util;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.SourceFile;
import java.nio.charset.StandardCharsets;

public class AstPrinter {

    /**
     * Print AST back to source code with lexical preservation.
     */
    public static String print(SourceFile sourceFile) {
        // Implementation using OpenRewrite's printing capabilities
        return sourceFile.print(); // TODO
    }

    /**
     * Print with specific formatting options.
     */
    public static String print(SourceFile sourceFile, int tabWidth) {
        // Implementation
        return print(sourceFile); // TODO
    }
}
```

### 4.3 Deliverables

- [ ] `NodeTraversal.java` - AST traversal utilities
- [ ] `AstPrinter.java` - Source printing utilities
- [ ] Unit tests for traversal utilities
- [ ] Unit tests for printing utilities

---

## 5. Phase 3: Code Generation Tools

### 5.1 Goals

- Migrate view interface generator
- Migrate view builder generator
- Migrate field boilerplate generator
- Migrate validation generators

### 5.2 Tasks

#### 5.2.1 View Interface Generator

**Original file**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java`

**New location**: Same file, but rewritten using OpenRewrite

**Migration steps**:

1. Replace `JavaParser` imports with OpenRewrite equivalents
2. Convert `CompilationUnit` handling to OpenRewrite `SourceFile`
3. Convert `ClassOrInterfaceDeclaration` to `TypeTree`
4. Implement visitor pattern for traversal
5. Update printing logic

#### 5.2.2 Field Boilerplate Generator

**Original file**: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java`

**Migration considerations**:

- This is the most complex generator
- Requires careful handling of switch expressions and complex AST structures
- May need to maintain some JavaParser compatibility during transition

### 5.3 Deliverables

- [ ] Migrated `ViewInterfaceGenerator.java`
- [ ] Migrated `FieldBoilerplateGenerator.java`
- [ ] Migrated `ViewBuilderGenerator.java`
- [ ] Migrated `ValidationGenerator.java`
- [ ] Migrated `EntityMetadataGenerator.java`
- [ ] All generators maintain same API surface
- [ ] All generators produce identical output to JavaParser version

---

## 6. Phase 4: Validation & Analysis Tools

### 6.1 Goals

- Migrate validation rules
- Migrate analysis tools
- Ensure validation correctness
- Handle edge cases in AST representation

### 6.2 Tasks

#### 6.2.1 Validation Rules

**Files to migrate**:

1. `AuditableRule.java`
2. `MarkerEntityRule.java`
3. `ViewInterfaceRule.java`
4. `ViewAnnotationRule.java`
5. `EntityRulesValidator.java`
6. `JavaParserTool.java`

#### 6.2.2 Analysis Tools

**Files to migrate**:

1. `ContextualAnalyzer.java`
2. `AccessorGenerator.java`
3. `BuilderGenerator.java`
4. `ConstructorGenerator.java`

### 6.3 Deliverables

- [ ] All validation rules migrated
- [ ] All analysis tools migrated
- [ ] Validation correctness tests passing
- [ ] Performance benchmarks acceptable

---

## 7. Phase 5: Integration with Project Automation

### 7.1 Goals

- Integrate OpenRewrite tools with project-automation module
- Create orchestration utilities
- Enable batch processing of files
- Support incremental updates

### 7.2 Tasks

#### 7.2.1 Orchestration Utilities

**File**: `project-automation/src/main/java/hr/hrg/rewrite/automation/AutomationEngine.java`

```java
package hr.hrg.rewrite.automation;

import hr.hrg.rewrite.util.*;
import org.openrewrite.java.tree.JavaType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestration engine for running OpenRewrite transformations.
 */
public class AutomationEngine {

    private final Map<String, AstManipulator> manipulators;

    public AutomationEngine() {
        this.manipulators = Map.of(
            "view-interface", new ViewInterfaceGenerator(),
            "field-boilerplate", new FieldBoilerplateGenerator(),
            "validation", new ValidationGenerator()
        );
    }

    /**
     * Apply transformation to a file.
     */
    public String apply(Path sourceFile, String transformationName) {
        AstManipulator manipulator = manipulators.get(transformationName);
        if (manipulator == null) {
            throw new IllegalArgumentException("Unknown transformation: " + transformationName);
        }
        // Apply transformation
        return manipulator.transform(sourceFile); // TODO
    }

    /**
     * Apply multiple transformations in sequence.
     */
    public String applySequential(
        Path sourceFile,
        List<String> transformationNames
    ) {
        String current = Files.readString(sourceFile);
        for (String name : transformationNames) {
            current = apply(sourceFile, name);
        }
        return current;
    }
}
```

#### 7.2.2 Batch Processing

**File**: `project-automation/src/main/java/hr/hrg/rewrite/automation/BatchProcessor.java`

```java
package hr.hrg.rewrite.automation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Batch processor for applying transformations to multiple files.
 */
public class BatchProcessor {

    private final AutomationEngine engine;
    private final ExecutorService executor;

    public BatchProcessor(AutomationEngine engine) {
        this.engine = engine;
        this.executor = Executors.newFixedThreadPool(10); // TODO: configurable
    }

    /**
     * Process multiple files in parallel.
     */
    public void process(
        Path sourceRoot,
        String transformationName
    ) {
        List<Path> files = findJavaFiles(sourceRoot); // TODO
        List<Future<String>> futures = files.stream()
            .map(file -> executor.submit(() -> engine.apply(file, transformationName)))
            .collect(Collectors.toList());

        // Collect results
        for (Future<String> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                // Handle error
            }
        }
    }
}
```

### 7.3 Deliverables

- [ ] `AutomationEngine.java` - Orchestration engine
- [ ] `BatchProcessor.java` - Batch processing utilities
- [ ] Integration tests
- [ ] Documentation for using automation engine

---

## 8. Phase 6: Migration of Existing Tooling

### 8.1 Goals

- Migrate all remaining JavaParser usage in the codebase
- Create migration scripts
- Validate migration completeness
- Update documentation

### 8.2 Tasks

#### 8.2.1 Scan Remaining JavaParser Usage

Run command to find all remaining JavaParser imports:

```bash
grep -r "import com.github.javaparser" --include="*.java" .
```

#### 8.2.2 Create Migration Checklist

For each file with JavaParser usage:

- [ ] Identify all JavaParser types used
- [ ] Determine OpenRewrite equivalents
- [ ] Create migration plan for that file
- [ ] Migrate and test
- [ ] Update documentation

### 8.3 Deliverables

- [ ] Migration checklist for all modules
- [ ] Completed migration of all files
- [ ] Updated README documentation
- [ ] Migration report with statistics

---

## 9. Phase 7: Testing & Validation

### 9.1 Goals

- Ensure all functionality is preserved
- Create comprehensive test suite
- Validate performance characteristics
- Test edge cases

### 9.2 Tasks

#### 9.2.1 Unit Tests

Create unit tests for each migrated component:

```java
package hr.hrg.rewrite.test;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.JavaType;
import static org.junit.jupiter.api.Assertions.*;

class OpenRewriteMigrationTests {

    @Test
    void testViewInterfaceGeneration() {
        // Test that generated interface matches expected output
    }

    @Test
    void testFieldBoilerplateGeneration() {
        // Test boilerplate generation
    }

    @Test
    void testLexicalPreservation() {
        // Test that source code is preserved correctly
    }
}
```

#### 9.2.2 Integration Tests

Test full workflows:

- Generate view interfaces from entity classes
- Generate builders from records
- Apply validation rules
- Handle edge cases

#### 9.2.3 Performance Benchmarks

Compare performance characteristics:

- Parse time: JavaParser vs OpenRewrite
- Transformation time
- Memory usage
- Round-trip conversion overhead

### 9.3 Deliverables

- [ ] Comprehensive unit test suite
- [ ] Integration test suite
- [ ] Performance benchmark report
- [ ] Edge case test coverage report

---

## 10. Rollback Plan

### 10.1 Rollback Triggers

Initiate rollback if:

- Critical functionality is broken
- Performance degradation > 50%
- Memory usage increases significantly
- Test coverage drops below 80%

### 10.2 Rollback Steps

1. Revert `pom.xml` changes
2. Restore original JavaParser-based files
3. Update documentation
4. Verify build passes
5. Run full test suite

### 10.3 Rollback Script

Create `project-automation/rollback.sh`:

```bash
#!/bin/bash
# Rollback script for OpenRewrite migration

# Revert pom.xml changes
# Restore original source files
# Verify build
```

---

## 11. Risk Assessment

### 11.1 Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| OpenRewrite API changes | Medium | Medium | Use stable API, monitor releases |
| Lexical preservation issues | Low | High | Thorough testing |
| Performance degradation | Low | Medium | Benchmark and optimize |
| Missing features | Medium | High | Map all JavaParser features, implement equivalents |

### 11.2 Project Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Migration timeline exceeded | Medium | Medium | Phased approach, clear milestones |
| Developer learning curve | Medium | Low | Documentation, training |
| Code review overhead | Low | Low | Clear migration guidelines |

---

## 12. Timeline & Milestones

### 12.1 Phase Timelines

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Foundation | 2 weeks | None |
| Phase 2: Core Utilities | 2 weeks | Phase 1 |
| Phase 3: Code Generation | 4 weeks | Phase 2 |
| Phase 4: Validation | 3 weeks | Phase 3 |
| Phase 5: Automation | 2 weeks | Phase 4 |
| Phase 6: Migration | 4 weeks | Phase 5 |
| Phase 7: Testing | 3 weeks | Phase 6 |

### 12.2 Total Duration

- **Estimated**: 20 weeks (~5 months)
- **Buffer**: 2 weeks for unexpected issues
- **Contingency**: 20% of total time

### 12.3 Milestone Review Points

- End of Phase 1: Review dependency setup and API layer
- End of Phase 3: Review all code generation tools
- End of Phase 6: Review migration completeness
- End of Phase 7: Final review and sign-off

---

## Appendix A: OpenRewrite Documentation References

- [OpenRewrite Core Module](https://docs.openrewrite.org/getting-started/overview/)
- [Recipes Documentation](https://docs.openrewrite.org/concepts-and-explanations/recipes)
- [LST Examples](https://docs.openrewrite.org/concepts-and-explanations/lst-examples)
- [Visitor Pattern](https://docs.openrewrite.org/concepts-and-explanations/visitors)

## Appendix B: JavaParser to OpenRewrite Type Mapping

See Section 2.3 for detailed type mapping table.

## Appendix C: Project Architecture Compliance

All migrations must comply with:

- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-25  
**Author**: JCodeBuddy Migration Team  
**Status**: Draft - Ready for Review
