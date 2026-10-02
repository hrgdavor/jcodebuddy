# hipster-entity-api

This module contains the base API contracts for entities and shared annotations.

## Intent

- `EntityBase` is the canonical marker for identity semantics.
- All core and tooling modules depend on this module for surface interface types.
- Additional annotations or shared API interfaces can be added here (e.g., `@Entity`, `@Id`, `@ReadOnly`).

## Entity / View structure patterns

This module defines the foundational contracts to express top-level roots vs embedded views.

### Core interfaces

- `EntityBase<ID>`: marker for objects with an identity type; no method required.
- `Identifiable<ID>`: only objects with explicit identity semantics must implement this.
- `ViewReader<ID, T extends EntityBase<ID>, F extends Enum<F>>`: readable field-oriented view interface; lookup by field and ordinal.
- `ViewWriter<ID, E extends EntityBase<ID>, F extends Enum<F>>`: mutable view extending `ViewReader`.

### Top-level entity (root) pattern

Top-level entity types should implement both `EntityBase` and `Identifiable`.

```java
public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {
}
```

Concrete root views can also carry field accessors:

```java
public interface PersonSummary extends PersonEntity {
    String firstName();
    String lastName();
}

public interface PersonDTO extends PersonSummary {
    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
    int age();
}

public interface PersonFull extends PersonSummary {
    ObjectNode metadata();
}

```

### Embedded/document fragment pattern

Embedded or nested document fragments should be `EntityBase` (or other marker) but not `Identifiable`.
Use `EntityBase<Void>` when the embedded fragment has no identity meaning.

```java
public interface AddressView extends EntityBase<Void> {
    String street();
    String city();
}
```

### Internal array-backed implementation

These concrete arrays used by `ArrayBackedViewProxyFactory` keep values in `Object[]` and return `values[field.ordinal()]`.
They do not expose identity directly; identity is resolved via view API (e.g., `PersonEntity::id()`).

### Proxy/mapping behavior

`ArrayBackedViewProxyFactory` dispatches method names through field mapper (`FieldNameMapper`) and retains no special case for `id`.
This ensures all accessors, including `id`, follow the same mapping behavior and enforces contract consistency.

### Why this shape

- keeps root identity explicit and opt-in (`Identifiable`) and avoids accidentally treating every view as identifiable
- keeps read-only and update contracts orthogonal to identity
- allows reuse of same array-backed value representation for different semantic roles
- supports streaming/json serialization/deserialization pathways (Jackson mapping, parse+create patterns)



