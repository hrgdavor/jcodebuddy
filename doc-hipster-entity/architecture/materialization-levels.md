# Materialization levels for hipster-entity views

> Remember: this project does not use get/set java beans notation for a
> POJO or an Entity, but goes with Java Record where field name and
> getter are the same, and the setter is also the same name but with a
> single parameter and void return.

A view's materialization is chosen by the `gen` attribute of `@View`.
The value is one of the constants of
`hr.hrg.hipster.entity.api.GenLevel`, and the ladder is **cumulative**:
a higher level also emits everything the lower levels emit.

## The ladder

| `GenLevel` | Emits | Cumulative effect |
|---|---|---|
| `DEFAULT` | nothing on its own | resolved to `META`, `RECORD` or `BUILDER` by the view's shape (below) |
| `META` | the field enum `<View>_` implementing `FieldDef` + a `ViewMeta` | the entry point; read-only through interface accessors |
| `RECORD` | a record implementing the view | `META` + the immutable concrete materialization |
| `WRITABLE` | a nested `Write` interface extending the view and `ViewWriter` | `META` + `RECORD` + a writable contract for proxy/builder-backed writes |
| `BUILDER` | `<View>Builder` | everything above + a concrete mutable builder with typed setters and `build()` |
| `BUILDER_TRACKED` | `<View>BuilderTracking` | everything above + field-level change tracking |
| `BUILDER_ALL` | both `<View>Builder` **and** `<View>BuilderTracking` | everything above; no tracking is traded away |

The order of the enum constants is exactly
`DEFAULT < META < RECORD < WRITABLE < BUILDER < BUILDER_TRACKED < BUILDER_ALL`.

Because the ladder is cumulative, the two top levels differ only in
*how many* builders you get:

- `BUILDER_TRACKED` emits the **tracking** builder **and the plain
  builder** — the plain one is not dropped, because a tracked view that
  needs a bulk construction path must not lose it.
- `BUILDER_ALL` emits **both** as well; it is the explicit way to say
  "I want the choice", and it is what the example's `PersonSummary`
  declares (`@View(gen = GenLevel.BUILDER_ALL)`).

An implementer must not "optimize" `BUILDER_TRACKED` by skipping the
untracked builder: the ladder is asserted by
`AllLevelsCompileTest.theLadderIsCumulative`.

## `DEFAULT` resolves from the view's shape

`DEFAULT` is not `META` by definition — it is resolved by
`GenLevelResolver` in `hipster-entity-tooling`, which is the **single
owner** of the rule and is called by both the validator and the
generator, so validation and generation cannot disagree. In strict
precedence order:

1. If the view declares a **nested `record`** whose component list
   matches the view's resolved field order → `RECORD`.
2. Else if the view declares a **nested `Write` interface** →
   `BUILDER`.
3. Else → `META`.

`DEFAULT` never resolves to `BUILDER_TRACKED` or `BUILDER_ALL`: a view
that wants tracking must say so, because tracking changes the public
surface (`changes()` / `changesBuilder()`).

## `META` — field enum and `ViewMeta`

As soon as a type is a view, an enum is generated with one constant per
field, plus a `ViewMeta` instance. For a `Person` view that yields a
generated metadata enum such as `Person_`.

A view can be declared as an interface or as a record:

- start with `interface` only → metadata is generated for the interface view
- start with `record` only → metadata is generated for the record view

Both paths converge at `RECORD`, where the interface becomes the stable
contract and the record provides the concrete materialization. The record
is **recommended** — it is immutable and safe for sharing — but it is
**not required** when starting from an interface.

If you start from a record, this migration can often happen in place
because the generated interface method names follow the same naming
pattern as the original record components.

When starting from an interface only, you may stop at the interface (or
a generated builder) without ever materializing a record. This is valid
whenever the view is used transiently — for example, to shape complex
parameters for a method call that will likely be caught by escape
analysis and inlined. In such cases a builder (level `BUILDER`) is
sufficient, and forcing a record adds unnecessary allocation and
coupling.

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

The same metadata enum is produced in both cases:

```java
enum Person_ implements FieldDef{
    name(String.class),
    email(String.class),
    ;
    
    private final Type javaType;
    private Person_(Type javaType) {
        this.javaType = javaType;
    }
    @Override
    public Type javaType() {
        return javaType;
    }
}
```

This generated metadata enum intentionally breaks standard naming
conventions so that each constant's name and case exactly match the
entity's field names. The constant name **is** the field name — see the
naming contract in
[`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md).

## `RECORD` — interface + record

The interface defines the contract, and the record gives performant
immutable materialization for using the data.

```java
interface Person{
    String name();
    String email();

    record Record(
        String name, 
        String email){}
}
```

The generated `ViewMeta.create(Object[])` builds that record
positionally, so the record's component order must match the field
enum's declared order. That order is a persisted contract: see
[R1 — field enums are append-only ordinal ledgers](#r1--field-enums-are-append-only-ordinal-ledgers).

## `WRITABLE` — the `Write` interface

A view at `WRITABLE` gains a nested `Write` interface extending the view
and `ViewWriter`. This is enough to support writes through the
array-backed proxy:

```java
public interface Write extends Person, ViewWriter {
    Write name(String value);
    Write email(String value);
}
```

Only `FieldKind.COLUMN` fields are writable (S1): a `DERIVED` or
`JOINED` field stays readable and keeps its ordinal, but gets no setter.

## `BUILDER` — a concrete builder

`BUILDER` emits `<View>Builder`: private mutable fields for every field,
copying `get(int)` / `set(int,Object)` / `set(String,Object)` accessors,
typed fluent setters for the writable fields only, and `build()`
returning the materialization. The name-based `set(String, Object)`
returns the ordinal it wrote, or `-1` for an unknown name; the
user-facing proxy is the layer that turns `-1` into an exception (D5).

## `BUILDER_TRACKED` — the tracking builder

`BUILDER_TRACKED` emits `<View>BuilderTracking` (and, per the cumulative
rule, the plain builder too). It implements
`ViewChangeTracking<<View>_, EEnumSet<<View>_>>`:

- one `EEnumSetBuilder64` / `EEnumSetBuilderLarge` holding the marked
  ordinals — a plain ordinal set, with no value state at all;
- `changes()` as the immutable snapshot and `changesBuilder()` as the
  live mutable set — two views over the same state;
- a mandatory copy constructor from a baseline view: the baseline stays
  the **caller's** object, and it is what the caller compares against when
  it needs an old&nbsp;→&nbsp;new pair, because the tracker keeps no
  previous value to compare with;
- no setter for a `DERIVED` field.

See [FAQ](../user/faq.md) for the full accessor list and the write-site
comparison, and
[The Ordinal Array Contract](../user/patterns/ordinal-array-contract.md)
for the array path.

## `BUILDER_ALL` — both builders

`BUILDER_ALL` emits `<View>Builder` and `<View>BuilderTracking`
side by side, so a project can use the lightweight plain builder for
bulk construction and the tracked builder for patch-aware updates
without choosing one at generation time.

## How the level relates to the usage matrix

| Usage | Interface | record | builder | builder-tracking | meta |
| --- | --- | --- | --- | --- | --- |
| RPC param | | record | | | optional metadata |
| complex method param | | | builder | | |
| entity | interface | record | | builder-tracking | metadata |
| POJO inside entity (doc part) | interface | record | | builder-tracking | metadata |
| EntityDTO | interface | | | | optional metadata |
| EntityForm | interface | | | | optional metadata |

> "optional metadata" — metadata is not needed at runtime if
> serialization/deserialization is delegated to a framework that uses
> reflection anyway, or if the shape is materialized into source.

## Reading and writing paths

Deserializing/reading into a materialized view is expressed as
`set(...)` returning whether the field exists (or an enum: `NO_CHANGE`,
`CHANGE`, `NOT_FOUND`):

| Source | Shape |
|---|---|
| JSON | `set(String, Object)` |
| JDBC | `set(int, Object)` |
| MONGO | `set(String, Object)` |
| Proxy | `set(String, Object)` — useful when generating a concrete impl is not desirable |
| BIN | `set(int, Object)` — binary formats (Fory, MQ) that require enum-backed validation |

The user-facing API rarely needs `set(F extends Enum<F>, Object)`: it is
better to generate the typed setters (the generated classes implement
the view interface).

Reading is added automatically:

- **record** — the record itself is used, and it can later be moved
  in-place to a nested type of the interface with the same signature;
- **interface** — no record, for DTOs and Forms;
- **interface + record**.

In case of git conflicts, merge using yours/their and rebuild the
boilerplate rather than resolving generated bodies by hand.

## R1 — field enums are append-only ordinal ledgers

The field enum's **constant order is a persisted ordinal layout**:
`values[field.ordinal()]` is that field, in every persisted array,
patch and snapshot. Therefore:

- a new constant is always **appended at the end** — never re-inserted
  into a "canonical" position;
- a removed field's constant is **tombstoned**, not deleted: it stays in
  place, is marked `@Deprecated`, and its `FieldDef.retired()` override
  returns `true`. Writers skip retired constants; readers stay tolerant
  of the slot.

The rule is scoped by the `entityFieldEnum:true` marker in the
[DEC-021](decisions/DEC-021.md) class-file header. In this repository the
checker is
[`hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker`](../../hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java),
driven by
[`EntityFieldEnumOrderRule`](../../hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityFieldEnumOrderRule.java)
and
[`EnumConstantOrderCli`](../../hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderCli.java);
the runnable invocation is documented in
[`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md).

## Reference

- [Entity API docs](../README.md)
- [Naming conventions](naming-conventions.md)
- [`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md) —
  the naming contract and the R1 order contract
- [DEC-017 identifiable mixin](decisions/DEC-017.md)
- [DEC-021 generator class-file header](decisions/DEC-021.md)
- [DEC-023 R1 — field enums are append-only ordinal ledgers](decisions/DEC-023.md)
- [User: Materialization Guide](../user/materialization-guide.md) — the
  same ladder from the user's point of view
- Example: `PersonSummary` at `BUILDER_ALL` in `hipster-entity-example`
