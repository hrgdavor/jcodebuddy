# Naming conventions for hipster-entity

This document records naming conventions for entity/view/model shapes and runtime artifacts.
It is a reference for generator and runtime engineers, as well as consumers of the API.

## 1. Core entity naming

- Root entity marker: `<Domain>Entity` (e.g., `PersonEntity`, `OrderEntity`).
- Must extend `EntityBase<IdT>`, and for identity-carrying roots also `Identifiable<IdT>`.
- Should be package-scoped under domain package (e.g., `hr.hrg.hipster.entity.person`).

## 2. View naming

- Read-only view: `<Domain>Summary`, `<Domain>Details`, etc.
- Update view: `<Domain>Update` or `<Domain>Mutable` for mutable command objects.
- For a view interface `X`, the view field enum should be named `XField` (or `XProperty`).
- Field enum constants must be lowerCamelCase exactly matching view accessor methods.

Example:

```java
public interface PersonSummary extends PersonEntity {
    String firstName();
    String lastName();
}

public enum PersonSummaryField implements FieldDef {
    id(Long.class),
    firstName(String.class),
    lastName(String.class);
}
```

## 3. Builder/update naming

- Builder interface: `<View>Builder` (e.g. `PersonSummaryBuilder`).
- Updater interface: use `Update` not `Mutable` — e.g. `PersonUpdateView`, `ViewWriter<ID, E, F>`.
  - `update` is the preferred verb in Java libraries (Spring Data, JOOQ, QueryDSL) and avoids the ambiguity of "mutable".
  - use `update` for state-change intent; use builder for construction/immutable paths that imeplent te `Update` interface.
- Internal array classes:
  - `EntityUpdateArray<ID, T, F>` (write-through values)
  - `EntityUpdateTrackingArray<ID, T, F>` (change-tracking)
  - `EntityUpdateTrackingArray64` / `EntityUpdateTrackingArrayLarge` (specializations).

## 4. Identity naming

- `Identifiable<ID>` is the explicit identity mixin.
- Views that provide an explicit identity method should extend `Identifiable<ID>`, otherwise avoid.
- For non-identifiable fragments use `EntityBase<Void>` or `EntityBase<Object>`.

## 5. Proxy/factory naming

- `ArrayBackedViewProxyFactory` for dynamic proxy creation.
- `FieldNameMapper` for method-name-to-field resolution.

## 6. Metadata naming

- `ViewMeta<V,F>` for view metadata.
- `DefaultViewMeta<V,F>` for runtime metadata implementations.
- `*FieldMeta` classes in generated code are okay with adapter naming.

## 7. JSON / serialization naming

- Jackson path classes: `EntityJacksonMapper`, `EntityJacksonViewDeserializer`, `EntityJacksonViewSerializer`.
- In generated view serializers, preserve fields in natural value order with `id` first.

## 8. Versioning and consistency

- Keep naming deterministic across generator runs.
- Record style: `id`, `firstName`, `departmentName` (no getter prefixes)
- Update arrays and proxies should align on the same name for generated metadata and call-site methods.

## 9. Mutability-related conventions

- `*Update` names are for mutable/update semantics contract interface.
- `*Builder` names are for construction/fluent APIs that implement mutable/update semantics.
- `EntityReadArray` and `ViewReader` are read-only accessor paths.
- `*Update` and `*Builder` include/assume the read part

