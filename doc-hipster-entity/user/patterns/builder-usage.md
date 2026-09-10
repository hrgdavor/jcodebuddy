# Builder Usage

## Goal

Use generated builders to create or update entity views with a fluent API.

## When to use

Use builders when you want a convenient, typed way to construct views and track changed fields.

## Example

```java
PersonBuilder builder = PersonBuilder.create();

PersonSummary summary = builder
        .firstName("Alice")
        .lastName("Smith")
        .email("alice@example.com")
        .build();
```

## What happens under the hood

Generated builders capture field values and can support change-tracking semantics.
This makes it easier to merge partial updates and construct targets consistently.

## When to choose a builder

Use a builder when:

- you want fluent construction instead of manual array-based creation
- you need to build partial updates or patch payloads
- you want generated helper methods instead of hand-written setters

## See also

- [Materialization Guide](../materialization-guide.md)
- [Core Concepts](../core-concepts.md)
