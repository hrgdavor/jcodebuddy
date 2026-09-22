# Phase 2: Core AST Utilities

## Overview

Implement core AST traversal and manipulation utilities that form the foundation for all code generation and validation tools.

## Prerequisites (Phase 1)

Phase 1 must be complete before starting Phase 2:
- OpenRewrite dependencies added to pom.xml
- API compatibility layer (`AstVisitor`, `AstManipulator`, `CompilationUnitAdapter`) is functional
- Conversion utilities support round-trip between JavaParser and OpenRewrite

## Deliverables

### 1. NodeTraversal.java

File: `doc/brainstorm/rewrite-migration/02-utilities/NodeTraversal.java`

Methods to implement:

#### Traversal Methods
- `findAllClasses(Tree tree)` - Find all class declarations
- `findAllMethods(Tree tree)` - Find all method declarations
- `findAllFields(Tree tree)` - Find all field declarations
- `findAllAnnotations(Tree tree)` - Find all annotations
- `findAllRecords(Tree tree)` - Find all record declarations
- `findAllEnums(Tree tree)` - Find all enum declarations
- `findAllInterfaces(Tree tree)` - Find all interface declarations
- `findAllConstructors(Tree tree)` - Find all constructors
- `findAllClassesByType(Tree tree, String fullyQualifiedName)` - Find classes by FQN
- `findMethodsBySignature(Tree tree, String className, String methodName, String... paramTypes)` - Find methods by signature

#### Type Information Methods
- `getType(Tree tree)` - Extract type information from node
- `getTypeArguments(Tree tree)` - Extract type arguments
- `getTypeParameters(Tree tree)` - Extract type parameters

#### Modifier Methods
- `getModifiers(Tree tree)` - Extract modifiers
- `hasModifier(Tree tree, Modifier modifier)` - Check if modifier present
- `getModifiersAsString(Tree tree)` - Convert modifiers to string

### 2. AstPrinter.java

File: `doc/brainstorm/rewrite-migration/02-utilities/AstPrinter.java`

Methods to implement:

#### Printing Methods
- `print(SourceFile sourceFile)` - Print AST to source code
- `printWithTabWidth(SourceFile sourceFile, int tabWidth)` - Print with custom tab width
- `printWithLexer(SourceFile sourceFile)` - Print with lexical preservation

#### Formatting Methods
- `format(SourceFile sourceFile, SourceFormat format)` - Format with specific style
- `prettyPrint(SourceFile sourceFile)` - Pretty print with standard formatting

#### Extraction Methods
- `extractImports(SourceFile sourceFile)` - Extract all import statements
- `extractAnnotations(SourceFile sourceFile)` - Extract all annotations
- `extractComments(SourceFile sourceFile)` - Extract all comments

### 3. SourceManipulation.java

File: `doc/brainstorm/rewrite-migration/02-utilities/SourceManipulation.java`

Methods to implement:

- `replaceText(SourceFile sourceFile, int offset, int length, String replacement)` - Replace text
- `insertAt(SourceFile sourceFile, int offset, String text)` - Insert text at offset
- `delete(SourceFile sourceFile, int offset, int length)` - Delete text
- `findText(SourceFile sourceFile, String pattern)` - Find text pattern
- `countOccurrences(SourceFile sourceFile, String pattern)` - Count pattern occurrences
- `replaceOccurrences(SourceFile sourceFile, String oldText, String newText)` - Replace all occurrences

### 4. TypeUtils.java

File: `doc/brainstorm/rewrite-migration/02-utilities/TypeUtils.java`

Methods to implement:

- `getTypeAsString(TypeTree type)` - Convert type to string
- `getTypeAsFullyQualifiedName(TypeTree type)` - Get fully qualified name
- `isPrimitive(TypeTree type)` - Check if primitive type
- `isReference(TypeTree type)` - Check if reference type
- `isGeneric(TypeTree type)` - Check if generic type
- `getTypeParameters(TypeTree type)` - Get type parameters
- `getTypeArguments(TypeTree type)` - Get type arguments
- `resolveType(TypeTree type, JavaType context)` - Resolve type in context
- `mergeTypes(TypeTree type1, TypeTree type2)` - Merge two types

### 5. Unit Tests

Directory: `doc/brainstorm/rewrite-migration/02-utilities/tests/`

- `NodeTraversalTests.java` - Tests for NodeTraversal
- `AstPrinterTests.java` - Tests for AstPrinter
- `SourceManipulationTests.java` - Tests for SourceManipulation
- `TypeUtilsTests.java` - Tests for TypeUtils

## Implementation Steps

1. Create directory structure: `doc/brainstorm/rewrite-migration/02-utilities/`
2. Implement `NodeTraversal.java` with all traversal methods
3. Implement `AstPrinter.java` with printing methods
4. Implement `SourceManipulation.java` with manipulation methods
5. Implement `TypeUtils.java` with type utilities
6. Create unit tests
7. Run tests and fix issues
8. Optimize performance if needed

## Testing Strategy

Test coverage requirements:
- All public methods must have unit tests
- Round-trip tests: JavaParser -> OpenRewrite -> JavaParser
- Edge cases: empty files, malformed code, large files
- Performance tests for large source files

## Integration Points

### Uses Phase 1
- `AstVisitor` for visitor pattern
- `CompilationUnitAdapter` for conversion
- `AstManipulator` for high-level operations

### Used By Phase 3
- Code generation tools will use `NodeTraversal` to find methods/fields
- `AstPrinter` will be used to print generated code
- `TypeUtils` will help with type checking

## Architecture Compliance

All code must comply with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Status

**Current**: **Delivered as specified, and superseded in practice** (verified 2026-09-22). The four
sketches (`AstPrinter`, `NodeTraversal`, `SourceManipulation`, `TypeUtils`) and their four test sketches
exist under `doc/brainstorm/rewrite-migration/02-utilities/`. The working equivalents were written during
Phase 6, where they are actually used: `TreeQueries` (traversal, kind, annotation and member queries),
`JavaSyntaxCheck` (positions — which this plan did not anticipate needing javac for) and
`TypeLiterals` / `JdkImportSupport` (type text and class literals).
**Next**: nothing here; Phase 6 is complete and Phase 7 is next (see `PLAN-SUMMARY.md` § *Phase status*).

**Do not compile these sketches as they stand.** `02-utilities/util/TypeUtils.java` is written against
`TypeTree`, `JType` and `JTypeParameter` — types that **exist nowhere in the repository**, and the same
imagined API that made `project-automation`'s staging package fail to compile before it was deleted
(`MIGRATION-CAVEATS.md` § 4.3). They are design notes about *what* a utility layer needs, not a starting
point; the LST's own types (`J.ParameterizedType`, `J.Identifier`, `J.Primitive`, `J.TypeParameter`) are
what a real implementation uses, as `TreeQueries` and `TypeLiterals` do.

## Quick Reference

Key classes in this phase:
- `NodeTraversal` - AST traversal
- `AstPrinter` - Source printing
- `SourceManipulation` - Text manipulation
- `TypeUtils` - Type utilities
