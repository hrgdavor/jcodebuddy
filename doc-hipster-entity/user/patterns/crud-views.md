# CRUD View Patterns

## Goal

Define reusable read and write shapes for a single entity without duplicating field definitions.

## When to use

Use CRUD views when you need different data shapes for list results, detailed reads, and update payloads.

## Example

```java
public interface PersonEntity extends EntityBase<Long> {
    Long id();
}

public interface PersonSummary extends PersonEntity {
    String firstName();
    String lastName();
}

public interface PersonDetails extends PersonSummary {
    String email();
    String phoneNumber();
}

public interface PersonUpdateForm extends PersonEntity {
    String firstName();
    String lastName();
    String email();
}
```

## What happens under the hood

- `PersonEntity` defines the shared identity contract.
- `PersonSummary` and `PersonDetails` reuse the same base entity shape.
- `PersonUpdateForm` can be used as a write-target with its own generated metadata.

## See also

- [Core Concepts](../core-concepts.md)
- [Materialization Guide](../materialization-guide.md)
