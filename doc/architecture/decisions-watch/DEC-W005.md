# DEC-W005: Code generation interface contract (CodeGenerator and CodeContext)

- Status: Accepted
- Date: 2026-07-24
- Owners: project
- Related docs: [Module map](../module-map.md)
- Supersedes: -
- Superseded by: -

## Context

The `project-automation` module provides a unified interface for code generators. Before this decision, generators in different modules had inconsistent APIs: some used `ActionTool`, some used raw JavaParser AST manipulation, and some used custom interfaces. A common contract was needed for generators to be composable, testable, and tool-agnostic.

## Decision

The `CodeGenerator<T>` interface is the unified contract for all generators in the JCodeBuddy framework:

```java
public interface CodeGenerator<T> {
    String name();
    boolean isApplicable(CodeContext context);
    T generate(CodeContext context);
}
```

The `CodeContext` interface provides inspection capabilities:

```java
public interface CodeContext {
    Path getRootPath();
    Path getFilePath();
    int getLine();
    String getIndent();
    TypeResolver getTypeResolver();
}
```

`TypeResolver` provides compile-time type lookup, returning `TypeDefinition` (qualifiedName, simpleName, fields, fieldTypes). `TypeResolver.empty()` is the no-op fallback for contexts where type resolution is not available.

`CodeContextImpl` is the default implementation combining `CodeContext` with type resolution.

## Alternatives considered

- **Annotation-based generator discovery** — rejected for now because it adds annotation processing overhead and complexity; the generator registry approach is simpler and more transparent.
- **Return type as void with side effects** — rejected because returning `T` allows generators to produce both code artifacts and metadata, enabling pipelines where one generator consumes the output of another.
- **Mandatory type resolution** — rejected because not all generators need type information; `TypeResolver.empty()` provides a safe no-op fallback.

## Consequences

- Positive: Generators are composable, testable, and tool-agnostic; `project-automation` can wrap any `ActionTool` into the `CodeGenerator<T>` interface; type resolution is optional.
- Negative: The `CodeContext` interface may grow as new generator use cases emerge; consumers must implement it for custom contexts.
- Follow-up: Consider a `CodeGeneratorRegistry` that auto-discovers generators via SPI or annotation scanning in a future ADR.

## Acceptance criteria

- `CodeGenerator<T>` MUST have `name()`, `isApplicable()`, and `generate()` methods
- `CodeContext` MUST provide root path, file path, line, indent, and type resolver
- `TypeResolver.resolve()` MUST return `null` for unknown types
- `TypeResolver.empty()` MUST return a no-op resolver that returns `null` for all queries
- `CodeContextImpl` MUST be the default implementation

---

## Appendix note — Phase 8 of the rewrite migration (2026-09-22)

**A problem statement is a record of the state the decision replaced; this one is left as written.**

The Context paragraph says that before this decision "some used raw JavaParser AST manipulation". That describes the landscape this decision replaced, and it stays for that reason — the decision it motivated (one `CodeGenerator<T>` / `CodeContext` contract for every generator) is unchanged. `JavaParser` there names `com.github.javaparser`, which is no longer how this project reads Java source.

Today the tooling reads through OpenRewrite's LST — `SourceReader.read(Path)` / `readText(String)` and `TreeQueries` in `hipster-entity-tooling` — takes positions from javac via `JavaSyntaxCheck`, and writes by splicing into text with `SourceSplicer` rather than by manipulating an AST towards a printed form.

The representation decision is [DEC-030](../../../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) and the reader's guide is [`doc_knowledge/code.graph.md`](../../../doc_knowledge/code.graph.md).
