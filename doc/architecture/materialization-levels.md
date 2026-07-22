# Materialization levels for hipster-entity views


## MINIMAL

As soon as the `@View` annotation is added, an enum is created with fields and a `ViewMeta` instance.
For a `Person` view, that yields a generated metadata enum such as `Person_`.

Level 0 can be jumpstarted from either a plain interface or a record:
- start with `interface` only → metadata is generated for the interface view
- start with `record` only → metadata is generated for the record view

Both paths converge at Level 1 (`RECORD`), where the interface becomes the stable contract and the record provides the concrete materialization.
If you start from a record, this migration can often happen in place because the generated interface method names follow the same naming pattern as the original record components.

<!-- INCLUDE:~iface/Person.java#DOCS -->
```java
@View(gen = hr.hrg.hipster.entity.api.GenLevel.META)
interface Person{
    String name();
    String email();// comment
}
```

Start point V2 (record) 

<!-- INCLUDE:~record/Person.java#DOCS -->
```java
record Person(
    String name,
    String email 
){}
//and this is
```

same meta class is added in both cases

```java
enum Person_ implements FieldDef{
    name(String.class),
    email(String.class),
    ;
    
    private final Type propertyType;
    private PersonSummary_(Type propertyType) {
        this.propertyType = propertyType;
    }
    @Override
    public Type javaType() {
        return propertyType;
    }
}
```

This generated metadata enum intentionally breaks standard naming conventions to ensure each member’s name and case exactly match the entity's field names.


## RECORD

Regardless if starting with `record` or `interface + record` the setup is the same in this step. Interface defines the contract, record gives performant immutable materialization for using the data. 

```java
interface Person{
    String name();
    String email();

    record Record(
        String name, 
        String email){}
}

```

Bolerplate gen levels that define what boilerplate is added (and can be reliably recreated if needed).
Each generation level is cumulative: higher levels also include the capabilities of all lower levels.

- META - required enum `Person_` is generated as soon as you opt-in to using hipster-entity by addng `@View` annotation
- RECORD - record + interface
- WRITABLE - generates write interface inside View interface (enough to support write with proxy)
- BUILDER - generates `PersonBuilder`
- BUILDER_TRACKED - generate `PersonBuilderTracking`
- BUILDER_ALL - generates `PersonBuilder`, `PersonBuilderTracking`


Deserializing/reading (return value true/false if field exists, or enum: NO_CHNAGE, CHANGE, NOT_FOUND)

- JSON  - set(String, Object)
- JDBC  - set(int, Object)
- MONGO - set(String, Object)
- Proxy - set(String, Object) - **!! questionable** use, when we can go step further and generate impl of interface
- BIN   - set(int, Object) - binary format for Fory, or MQ, requires adding enums to back validation

User facing API may not need set(F extends Enum<F>, Object) as it is better to generate methods (implement Entity interface)


In case of git conflicts, just merge using yours/their and rebuild boilerplate, not worying about resolving.

Read is added automatically
- record - the record is used itself that can be later moved in-place to inner type of Interface with same signature
- interface - without record for DTOs and Forms
- interface + record

sketch

- L0 - record (readable)
- L1 - interface - transient only using interface for shaping
  - EntityForm - reads into tracking array backed proxy, to copy data to Entity, allowing mapping non direct proeprties
  - EntityDTO - read from database into array backed proxy, allowing augmentation and derived data directly inquery
- L2 - materialized backing store implementing updatable so no proxy needed
  - EntityForm - same as L1
  - EntityDTO - same as L1
- L3 - materialized as record
- L4 - materilaized builder



Transient view 
At T1 starts with Entity_ field enum and ViewMeta

- T1 interface only - for EntityForm, EntityDTO that only define shape
- T2 interface only - update interface -  for EntityForm, EntityDTO that only define shape and can make user defined addon

Document view (inside document, not top level entity)
At D0 starts with Entity_ field enum and ViewMeta

- D0 record `@View`
- D1 interface + inner record `@View`
- D2 - read/write

Entity view (Uses Identifiable)

- E1 - read
- E2 - read/write `@View` with `write != MINIMAL`



matrix
- **read metadata boilerplate** - starting minimum when `@View`
- interface 
- record + `@View` OR inerface + record + `@View(read=RECORD)` 
- **write interface boilerplate** 
- (interface, inerface + record) + `@View(write=PROXY)`
- updateable - materialization L1 (no need for proxy) `@View(write=UPDATEABLE)`
- Builder - materialization L2 (concrete builder impl, includes updateable, fastest) `@View(write=BUILDER)`





This document defines a graded strategy for view materialization, from metadata-only through concrete implementations and generated paths.

## Background

`hipster-entity` is interface-first. View contracts are declared as interfaces (e.g., `Person`, `OrderDetails`) and can be materialized in different ways:
- Metadata-only with enums and `ViewMeta`
- Immutable record/DTO snapshot
- Mutable `ViewWriter` object
- Array-backed proxy
- Generated concrete implementation

The following levels are designed to provide a clear progression for use cases.

## Level 0: Record-structured baseline (no interface)

- Start with a simple record/POJO as the domain object (e.g., `Person`).
- The record name is the intended root contract name, before interface extraction.
- No multiple view variants yet; this is the minimal implementation form.
- Example:

```java
public record Person(
    Long id,
    String firstName,
    String lastName,
    Integer age,
    String departmentName,
    Map<String, List<Long>> metadata
) {}
```

- This is the most lightweight path and serves as a direct data holder for non-view-heavy use cases.

## Level 1: Interface extraction from record (single view)

- Replace the record's type with an interface (e.g., `Person`).
- Keep the prior record implementation as `Person.Record` inner class.
- Example:

```java
public interface Person implements EntityBase<Long>, Identifiable<Long>{
    Long id();
    String firstName();
    String lastName();
    Integer age();
    String departmentName();
    Map<String, List<Long>> metadata();

    // boilerplate maintained inner class, tools add to it if interface changes
    public record Record(
        Long id,
        String firstName,
        String lastName,
        Integer age,
        String departmentName,
        Map<String, List<Long>> metadata
    ) implements Person {}
}

// can be without id
public interface Person implements EntityBase<Void>{
    String firstName();
    // ...
}
```

- `PersonField` boilerplate generated by the tooling, and also contains `ViewMeta` inside 
- For read-oriented snapshots and simple schema-based serialization
- No `ViewWriter` semantics

## Level 2: Explicit updatable materialization (`ViewWriter` path)

- Explicit class implementing `ViewWriter` + view interface

```java
public static class Person.Update implements Person, ViewWriter<Long, Person, PersonField> {
    // fields, getters, set(field,value), etc.
}
```

- Proxy-free, direct field storage and method dispatch
- Clear semantics for mutable/patch workflows

## Level 3: Array-backed proxy (`ArrayBackedViewProxyFactory`)

- Uses `EntityReadArray` / `EntityUpdateTrackingArray` + proxy dispatch
- Uses metadata from field enums and `FieldNameMapper`
- Good for dynamic projections and minimal concrete class count

## Level 4: Generated concrete implementations

- Generated class per view (e.g., `PersonSummaryImpl`)
- Highest throughput and static typing for hot paths
- Compatible with `ViewMeta` and upstream view contracts

## Guidelines

1. Start with Level 0 for generative tooling and validation.
2. Use Level 1 for immutable snapshot workloads.
3. Use Level 2 for mutable/patch workflows that avoid proxy overhead.
4. Use Level 3 when projection flexiblity is high and generated class explosion is undesirable.
5. Use Level 4 for highest-performance runtime in fixed schema scenarios.

## Reference

- [Entity API docs](../README.md)
- [Naming conventions](naming-conventions.md)
- [DEC-017 identifiable mixin](decisions/DEC-017.md)
- Example: `PersonSummary.Update` in `hipster-entity-example`

```java
public record PersonSummaryRecord(Long id, String firstName, String lastName, Integer age,
        String departmentName, Map<String, List<Long>> metadata) implements PersonSummary {}
```

## Level 1: Entity Meta for generic operations 

- Allows more complex tooling and generic operations for CRUD etc.
- `EntityMeta`, field enum (`PersonSummaryField`/`PersonSummaryProperty`), and `ViewMeta` exist.
- This is enough for most generator and adapter tooling, and for some query compilation paths.
- this is sufficient for ArrayBased Proxy variants

## Level 2: explicit updatable materialization (`ViewWriter` path)

- ViewWriter interface canbe declared
- `PersonSummary.Update` class implements `PersonSummary` and `ViewWriter`.
- Proxy-free, direct field storage with set/get methods.
- Best for mutable/patch workflows and where method call semantics must match change-tracking API.

## Level 3: array-backed proxy (`ArrayBackedViewProxyFactory`)

- Uses `EntityReadArray` / `EntityUpdateTrackingArray` + `ArrayBackedViewProxyFactory`.
- Good for dynamic projection adaptation, fewer generated classes, and metadata-managed semantics.
- `id` and other accessors are resolved via `FieldNameMapper` and field enum ordinals.

## Level 4: generated concrete implementations

- Fully generated class per view (e.g. `PersonSummaryImpl` or `PersonSummaryRecord`) implementing interfaces without proxies.
- Best performance for high-throughput scenarios.
- Still retains compatibility with `ViewMeta`, `FieldDef`, and `ViewWriter` contracts.

```java
public class PersonSummary.Update implements PersonSummary, ViewWriter<Long, PersonSummary, PersonSummaryField> {
    Long id;
    String firstName;
    String lastName;
    Integer age;
    String departmentName;
    Map<String, List<Long>> metadata;

    @Override public Long id() { return id; }
    // other getters

    @Override
    public Object get(PersonSummaryField field) { ... }
    @Override
    public Object set(PersonSummaryField field, Object value) { ... }
}
```

### Why this is enough to avoid proxy

- This level provides a concrete object with local fields and direct method dispatch; no proxy or reflective handler path is needed.
- It is ideal when the cost of a dedicated class is acceptable and method dispatch is latency-critical.
- It also avoids runtime `InvocationHandler` overhead and makes debugging straightforward.

### When to use

- Hot loops where the indirection of proxy dispatch is measurable.
- Narrow, domain-specific models where codegen can produce stable, easy-to-audit classes.
- Integration with existing builders/mappers where a mutable/patch object is desired.

## Level 2: array-backed proxy (`ArrayBackedViewProxyFactory`)

- Uses `EntityReadArray` / `EntityUpdateTrackingArray` + `ArrayBackedViewProxyFactory`.
- Good for dynamic projection adaptation, fewer generated classes, and metadata-managed semantics.
- `id` and other accessors are resolved via `FieldNameMapper` and field enum ordinals.

## Level 3: generated concrete implementations

- Fully generated class per view (e.g. `PersonSummaryImpl` or `PersonSummaryRecord`) implementing interfaces without proxies.
- Best performance for high-throughput scenarios.
- Still retains compatibility with `ViewMeta`, `FieldDef`, and `ViewWriter` contracts.

## Guidelines for leveling

1. Start with Level 1 when you need explicit, stable identity and mutation semantics.
2. Use Level 2 for flexible, metadata-driven mappings and when you want to minimize generated class count.
3. Move to Level 3 for the highest performance and static analysis benefits.
4. Ensure `ViewWriter` and `Identifiable` contract consistency across levels (`id()` on root entities, not on all readers).

## References

- [Entity API docs](../README.md)
- [Naming conventions](naming-conventions.md)
- [DEC-017 identifiable identity mixin](decisions/DEC-017.md)
- `hipster-entity-example` includes Level 1 `PersonSummary.Update` and Level 2 `PersonSummaryField` proxy paths.

