# FAQ

## Do I have to use every module?

No. You can start with `hipster-entity-api` for interface and metadata support, then add `hipster-entity-jackson` or tooling modules only when you need JSON or code generation support.

## Can I start with a record and upgrade later?

Yes. The library is designed to support both records and interfaces. You can start with a record or an interface, then add metadata and generated helpers as your needs grow.

## What is the difference between `EntityBase` and `Identifiable`?

- `EntityBase<ID>` marks a type as an entity view.
- `Identifiable<ID>` means the view exposes an explicit identity method like `id()`.

Use `Identifiable` only when the view needs identity semantics.

## Why do field metadata enums use lowerCamelCase names?

The generated enum values intentionally match the field accessor names exactly.
That keeps metadata and code aligned, and enables reliable generated lookup methods.

## How do I add a write/update view?

Define a separate write-capable interface, such as `PersonUpdateForm`, and generate metadata for it.
This keeps read and write shapes separate while reusing the same base entity contract.

## How does change tracking work?

Change tracking is implemented by generated helpers and builder patterns that keep track of which fields were modified.
If you want the full implementation details, see the architecture guide for `EnumSet`-based update tracking.

## Where can I learn more?

- [Why hipster-entity](why-hipster-entity.md)
- [Getting Started](getting-started.md)
- [Core Concepts](core-concepts.md)
- [Materialization Guide](materialization-guide.md)
- [Architecture docs](../../doc/architecture/README.md)
