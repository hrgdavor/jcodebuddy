# Why hipster-entity

## Why this matters

Java data models often become a tangle of DTOs, builders, mappers, and handwritten schema logic.
`hipster-entity` is designed to make entity shape explicit, reusable, and metadata-driven with minimal boilerplate.

## What you get

- A simple interface-first entity model you can define as an interface or a record.
- Generated compile-time field metadata so you can build serializers, deserializers, and adapters without reflection.
- Reusable views that let you define a base entity once and project it into different shapes.
- Incremental adoption: start with just metadata, then add builders, proxies, or generated implementations as needed.
- A practical bridge to JSON and adapter layers while preserving strong type semantics.

## What this means for your code

Instead of writing manual POJOs and separate mapping logic, you can:

- define `PersonEntity`, `PersonSummary`, and `PersonUpdateForm` as interfaces
- get field metadata such as `Person_` automatically generated
- use generic helpers to create views, serialize JSON, or build update payloads

## When to use it

Use `hipster-entity` when you want:

- strong compile-time schema for entity fields
- reusable read/write view contracts
- a predictable metadata layer that works with frameworks such as Jackson
- a gradual migration path from plain records/interfaces to richer generated helpers

## Where to go next

- [Getting Started](getting-started.md)
- [Core Concepts](core-concepts.md)
- [Materialization Guide](materialization-guide.md)
