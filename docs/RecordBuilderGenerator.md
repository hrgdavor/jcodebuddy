# RecordBuilderGenerator Implementation Detail

The `RecordBuilderGenerator` is a high-value tool designed to automate the generation and maintenance of fluent builders for Java Records. It follows a "Surgical Update" philosophy to ensure that it remains helpful without being intrusive.

## The Problem

Traditional code generators often treat source files as throwaway output, overwriting entire blocks of code. This causes several issues:
1. **Formatting Loss**: Indentation, blank lines, and trailing comments are often lost or forced into a generic style.
2. **Loss of Custom Logic**: If a developer adds a custom validation check within a setter, a naive generator will overwrite it on the next run.
3. **Messy Diffs**: Entire classes are deleted and re-inserted, making Git history difficult to read.

## The Solution: Surgical Updates

The `RecordBuilderGenerator` solves these problems by using **JavaParser's LexicalPreservingPrinter** combined with a synchronization-based logic.

### 1. Lexical Preservation
By using `LexicalPreservingPrinter.setup(cu)`, the agent captures the exact source text of the file. When changes are applied via `LexicalPreservingPrinter.print(cu)`, only the specific AST nodes added or modified are rendered into the string. The rest of the file (including Javadocs, comments, and whitespace) remains untouched.

### 2. Synchronization vs. Replacement
Instead of replacing the `Builder` class, the tool **synchronizes** it:
- **Presence Check**: It first looks for an existing inner class named `Builder`. If found, it uses it as the target for updates.
- **Member-by-Member Sync**: It iterates through the record components and only adds a field or a fluent setter if it is **not already present**.
    - *Benefit*: This allows developers to write their own custom setters with validation or transformation logic. The agent will see the method exists and skip it.
- **Forced Update of `build()`**: The `build()` method is the only part that is forcefully replaced. This ensures that the constructor call inside `build()` always matches the current record signature, preventing compilation errors.

### 3. Comprehensive Builder Pattern
The tool generates a complete builder pattern that enhances the usability of immutable records:
- **`static Builder builder()`**: Provides a clean entry point for creating new instances.
- **`Builder toBuilder()`**: Injects an instance method into the record. This allows "modifying" an immutable record by creating a builder pre-populated with current values (e.g., `user.toBuilder().name("New Name").build()`).

## Key Steps in Execution

1. **Setup**: Parse the file and initialize Lexical Preservation.
2. **Locate**: Find the `RecordDeclaration` near the triggered line using AST range analysis.
3. **Inject Factories**: Add `builder()` and `toBuilder()` methods to the record if they don't exist.
4. **Locate/Create Builder**: Find the existing `Builder` inner class or create a new one with `public static` modifiers.
5. **Sync Components**: Add missing private fields and missing fluent setters.
6. **Refresh `build()`**: Remove any existing `build()` method and insert a fresh one that calls the Record's canonical constructor with all current components.
7. **Print**: Use the lexical printer to generate the final source code, ensuring minimal diffs.

## Why this way?
This approach makes the tool "safe" to run on every save. It serves as a collaborator that handles the "boring" parts of code maintenance while respecting and preserving the developer's custom work and stylistic choices.
