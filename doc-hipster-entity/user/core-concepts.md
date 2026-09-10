# Core Concepts

## Interface-first entities

In `hipster-entity`, entities are defined as interfaces rather than concrete POJOs.
That means the shape of your data is expressed directly in Java method signatures.

Example:

```java
public interface PersonEntity extends EntityBase<String> {
    String id();
}

public interface PersonSummary extends PersonEntity {
    String firstName();
    String lastName();
    String email();
}
```

## Views

A view is a projection of an entity. Views extend a base entity interface and add fields.
Common view types include:

- `Summary` — a compact read-only snapshot
- `Details` — a richer read model
- `UpdateForm` — a write-target view for updates

## Field metadata

The tooling generates a companion enum for each view. The enum is named with an underscore suffix,
for example `PersonSummary_`.

That enum contains:

- field names that exactly match accessor names
- field type metadata
- a `forName(String)` lookup method
- a `ViewMeta` instance for generic runtime use

This makes your schema available at compile time without reflection.

## EntityBase vs Identifiable

- `EntityBase<ID>` marks a type as an entity type.
- `Identifiable<ID>` is used when the view has an explicit identity method such as `id()`.

Use `Identifiable` only when identity is meaningful for the view.

## Field sources

Fields can represent different kinds of data:

- `COLUMN` — regular stored data
- `DERIVED` — computed values
- `JOINED` — values from a related entity or joined table

For users, this means you can express whether a field is stored, computed, or sourced from another object.

## Materialization levels

`hipster-entity` supports an ordered adoption ladder.
Each level is cumulative — higher levels include the capabilities of all earlier levels and then add more materialization or write support.

- **Meta** — interface + generated metadata (`ViewMeta` and field enum)
- **Record** — includes Meta and adds an immutable record implementation for the view
- **Writable** — includes Record and Meta, and adds a write-capable interface for proxy-backed updates
- **Builder** — includes Writable, Record, and Meta, and adds a generated concrete builder class
- **BuilderTracked** — includes Builder and all earlier levels, plus a builder that tracks which fields were modified
- **BuilderAll** — includes BuilderTracked and Builder, generating both a regular builder and a tracking builder

For a deeper explanation, see [Materialization Guide](materialization-guide.md).
