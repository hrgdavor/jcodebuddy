# Phase 1: Foundation & Infrastructure

## Overview

Set up the foundation for OpenRewrite migration by:
1. Adding OpenRewrite dependencies to Maven
2. Creating API compatibility layer
3. Establishing testing infrastructure

## Prerequisites

- JCodeBuddy project structure (already exists)
- Maven build system (already configured)

## Dependencies

Add to parent `pom.xml`:

```xml
<properties>
    <openrewrite.version>8.40.1</openrewrite.version>
    <!-- Keep javaparser.version for backward compatibility -->
    <javaparser.version>3.28.0</javaparser.version>
</properties>

<dependencyManagement>
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
            <artifactId>rewrite-java-11</artifactId>
            <version>${openrewrite.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openrewrite</groupId>
            <artifactId>rewrite-java-17</artifactId>
            <version>${openrewrite.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openrewrite</groupId>
            <artifactId>rewrite-java-21</artifactId>
            <version>${openrewrite.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openrewrite</groupId>
            <artifactId>rewrite-maven</artifactId>
            <version>${openrewrite.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Deliverables

### 1. Maven Dependency Fragment

File: `doc/brainstorm/rewrite-migration/01-foundation/pom-fragment-rewrite.xml`

Contains all OpenRewrite dependencies for inclusion in parent pom.xml.

### 2. API Compatibility Layer

Directory: `doc/brainstorm/rewrite-migration/01-foundation/api-compatibility/`

Files to create:

#### AstVisitor.java
Base visitor interface extending OpenRewrite's `JavaIsoVisitor`.

```java
package hr.hrg.rewrite.api;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

public abstract class AstVisitor<R, P> extends JavaIsoVisitor<R, P> {
    // Override visit methods for each AST node type
}
```

#### AstManipulator.java
High-level utilities for common AST operations.

Methods to implement:
- `addMethod()` - Add method to class
- `addField()` - Add field to class
- `removeMethod()` - Remove method
- `removeField()` - Remove field
- `addAnnotation()` - Add annotation
- `findAllClasses()` - Find all classes
- `findAllMethods()` - Find all methods
- `findAllFields()` - Find all fields
- `findAllAnnotations()` - Find all annotations
- `findAllRecords()` - Find all records
- `findAllEnums()` - Find all enums
- `findAllInterfaces()` - Find all interfaces
- `addClassDeclaration()` - Create class declaration
- `createMethodDeclaration()` - Create method declaration

#### CompilationUnitAdapter.java
Converter between JavaParser and OpenRewrite ASTs.

Methods to implement:
- `toOpenRewrite()` - Convert JavaParser CompilationUnit to OpenRewrite SourceFile
- `toJavaParser()` - Convert OpenRewrite SourceFile to JavaParser CompilationUnit
- `fromPath()` - Create SourceFile from file path
- `fromPathJavaParser()` - Create CompilationUnit from file path
- `fromSource()` - Create SourceFile from source string
- `toSource()` - Extract source from AST
- `findClasses()` - Find all classes
- `findMethods()` - Find all methods
- `findFields()` - Find all fields
- `findAnnotations()` - Find all annotations
- `findRecords()` - Find all records
- `findEnums()` - Find all enums
- `findInterfaces()` - Find all interfaces

### 3. Testing Infrastructure

Directory: `doc/brainstorm/rewrite-migration/01-foundation/tests/`

Files to create:

#### ApiCompatibilityTests.java
Unit tests for API compatibility layer.

```java
class ApiCompatibilityTests {
    @Test
    void testConversionRoundTrip() {
        // Test JavaParser -> OpenRewrite -> JavaParser preserves source
    }
    
    @Test
    void testFindAllClasses() {
        // Test finding all classes
    }
    
    // Add more tests
}
```

#### AstManipulatorTests.java
Unit tests for AstManipulator methods.

## Implementation Steps

1. Create `pom-fragment-rewrite.xml`
2. Create `AstVisitor.java`
3. Create `AstManipulator.java` with all method signatures
4. Create `CompilationUnitAdapter.java` with all conversion methods
5. Create test files
6. Implement conversion logic in `CompilationUnitAdapter`
7. Run tests to verify functionality

## Next Phase Dependency

Phase 2 (Core AST Utilities) depends on Phase 1 completing with:
- Working API compatibility layer
- Functional conversion utilities
- Passing unit tests

## Architecture Compliance

All code must comply with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Status

**Current**: Ready for implementation
**Next**: After Phase 1 completion, proceed to Phase 2
