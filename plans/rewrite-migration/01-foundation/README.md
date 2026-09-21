# Phase 1: Foundation & Infrastructure

## Overview

This phase sets up the foundation for the OpenRewrite migration by:

1. Adding OpenRewrite dependencies to Maven
2. Creating API compatibility layer
3. Establishing testing infrastructure

## Directory Structure

```
01-foundation/
├── pom-fragment-rewrite.xml          # Maven dependency fragment for OpenRewrite
├── api-compatibility/
│   ├── AstVisitor.java              # Base visitor interface
│   ├── AstManipulator.java          # High-level manipulation utilities
│   └── CompilationUnitAdapter.java   # Conversion utilities
├── tests/
│   ├── ApiCompatibilityTests.java   # Unit tests
│   └── AstManipulatorTests.java     # Unit tests
└── README.md                        # Phase documentation
```

## Files Created

### pom-fragment-rewrite.xml

Maven dependency fragment to be included in the parent `pom.xml` to add OpenRewrite dependencies.

**Key Dependencies**:
- `rewrite-core` - Core OpenRewrite functionality
- `rewrite-java` - Java AST support
- `rewrite-java-11/17/21` - Java version-specific support
- `rewrite-maven` - Maven support (test scope)

### AstVisitor.java

Base visitor interface extending OpenRewrite's `JavaIsoVisitor`. Provides type-safe traversal of the Java AST.

**Features**:
- Extends OpenRewrite's visitor pattern
- Provides default implementations for all AST node types
- Ready for custom visit methods to be overridden

### AstManipulator.java

High-level utilities for common AST operations:

**Implemented Methods**:
- `addMethod()` - Add a method to a class
- `addField()` - Add a field to a class
- `removeMethod()` - Remove a method
- `removeField()` - Remove a field
- `addAnnotation()` - Add an annotation
- `findAllClasses()` - Find all classes
- `findAllMethods()` - Find all methods
- `findAllFields()` - Find all fields
- `findAllAnnotations()` - Find all annotations
- `findAllRecords()` - Find all record declarations
- `findAllEnums()` - Find all enum declarations
- `findAllInterfaces()` - Find all interface declarations
- `addClassDeclaration()` - Create class declaration
- `createMethodDeclaration()` - Create method declaration
- `classExists()` - Check if class exists
- `methodExists()` - Check if method exists
- `fieldExists()` - Check if field exists
- `getFullyQualifiedName()` - Get FQN of a type tree
- `getClassDeclaration()` - Get class from type tree
- `createClassDeclaration()` - Create class declaration
- `createMethodDeclaration()` - Create method declaration

### CompilationUnitAdapter.java

Adapter for converting between JavaParser and OpenRewrite AST representations.

**Conversion Methods**:
- `toOpenRewrite()` - Convert JavaParser CompilationUnit to OpenRewrite SourceFile
- `toJavaParser()` - Convert OpenRewrite SourceFile to JavaParser CompilationUnit
- `fromPath()` - Create OpenRewrite SourceFile from file path
- `fromPathJavaParser()` - Create JavaParser CompilationUnit from file path
- `fromSource()` - Create OpenRewrite SourceFile from source string
- `toSource()` - Extract source code from AST
- `findClasses()` / `findMethods()` / `findFields()` etc.

### ApiCompatibilityTests.java

Comprehensive unit tests for the API compatibility layer:

- Round-trip conversion tests
- Find all classes/methods/fields/annotations tests
- Existence checks tests
- Modification tests (add/remove method, field, annotation)

### AstManipulatorTests.java

Unit tests for AstManipulator method signatures and functionality.

## Next Steps

1. Review the API compatibility layer
2. Test conversion utilities with existing code
3. Proceed to Phase 2 (Core AST Utilities)

## Usage Examples

### Converting Between ASTs

```java
// Convert JavaParser to OpenRewrite
CompilationUnit javaParserCpu = ...;
SourceFile openRewriteSource = CompilationUnitAdapter.toOpenRewrite(javaParserCpu);

// Convert OpenRewrite to JavaParser
SourceFile openRewriteSourceFile = ...;
CompilationUnit javaParserCpu = CompilationUnitAdapter.toJavaParser(openRewriteSourceFile);
```

### Finding Elements

```java
// Find all classes
List<TypeTree> classes = AstManipulator.findAllClasses(sourceFile);

// Find methods by name
List<MethodTree> methods = AstManipulator.findMethodsByName(sourceFile, "hr.hrg.test.Person", "sayHello");

// Check if class exists
boolean exists = AstManipulator.classExists(sourceFile, "hr.hrg.test.Person");
```

## Notes

- The API compatibility layer maintains JavaParser API surface during migration
- Conversion utilities allow gradual replacement of JavaParser calls
- Testing should verify round-trip conversion preserves source code
- All implementations follow DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator headers)

## Status

**Current**: Implementation complete
**Next**: Ready for Phase 2 (Core AST Utilities)

