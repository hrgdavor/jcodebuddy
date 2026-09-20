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

Change tracking is a real contract, not a vague "generated helper".
The generator emits a `<View>BuilderTracking` class for a view at
`GenLevel.BUILDER_TRACKED` or `BUILDER_ALL`, and both that class and
the array-backed updatable proxy implement
`hr.hrg.hipster.entity.core.ViewChangeTracking<E, S>`:

| Member | Contract |
|---|---|
| `isChanged()` | whether any field was written with a value different from the one it held |
| `changes()` | an **immutable snapshot** of the changed fields, safe to retain and iterate |
| `changesBuilder()` | the **live, mutable** change set — a plain ordinal set, no value state |
| `clearChanges()` | resets the change set (no value baseline is involved, because none is kept) |
| `currentValue(E field)` | the value a changed field currently holds |
| `changedValues()` | one `FieldChange` per marked ordinal, in ascending ordinal order — its `field` and its `current`; there is no `previous` component |
| `changesDeep()` | deep change paths, one per changed leaf |
| `shallowPaths()` | one `ChangePath` per marked ordinal |
| `nestedTrackers()` | the nested trackers of this view, keyed by field ordinal |
| `collectionDeltas()` | the add/remove/reorder/field findings of each tracked `List` field, keyed by field ordinal |
| `hasCollection(int ordinal)` | whether that field holds a tracked collection |
| `collectionDiagnostics()` | findings that could not be expressed as a delta (e.g. `NOT_IDENTIFIABLE`) |

`changes()` and `changesBuilder()` are **two views over one shared
piece of state**, never two copies, so they cannot disagree. Only
`changesBuilder()` can mutate; `changes()` allocates when the set is
non-empty.

Two semantics are worth knowing before you rely on the values:

- **The comparison happens at the write site.** A generated tracking
  setter is
  `if (!java.util.Objects.equals(this.firstName, value)) { this.firstName = value; mf.addOrdinal(1); }`,
  and `EntityUpdateTrackingArray.set(int, Object)` compares the slot it
  holds before marking. The change set itself keeps no values.
- **A no-op write marks nothing.** Writing a value equal to the one the
  field currently holds neither marks the field nor records anything
  (DEC-012).

The consequence to know before you rely on a mark: **a write back to the
original value afterwards leaves the field marked.** No previous value is
kept, so there is no stored baseline that could unmark it — relative to
the state the tracker saw, the field *was* written with a different value.

### How do I get old → new pairs?

Hold the baseline view you built the mutable from and compare the two
yourself. The tracker keeps nothing to compare against, deliberately: the
old value belongs to the instance the write started from, which is your
object.

```java
PersonSummary baseline = view;                    // what the mutable below was built from
PersonSummaryBuilderTracking tracked = new PersonSummaryBuilderTracking(view);

tracked.firstName("Grace");

// The new half comes from the tracker…
tracked.changedValues().forEach(c ->
        System.out.println(c.fieldName() + " is now " + c.current()));

// …and the old half from the baseline you still hold.
System.out.println("firstName " + baseline.firstName() + " -> " + tracked.firstName());
```

That is the whole contract: the tracker says **which** fields changed, the
caller compares **what** changed. For the same reason the shallow JSON
change set is a JSON Merge Patch of current values — `{"firstName":"Grace"}`,
not `{"firstName":{"previous":"Ada","current":"Grace"}}`. Deep tracking is
the one place a baseline survives, and it is an **identity** baseline
(each entry's `Identifiable.id()`), never an element's field values.

For the array path, see
[The Ordinal Array Contract](patterns/ordinal-array-contract.md); for
the JSON patch shape, see [Jackson Setup](patterns/jackson-setup.md).

## Where can I learn more?

- [Why hipster-entity](why-hipster-entity.md)
- [Getting Started](getting-started.md)
- [Getting started in a new project](getting-started-new-project.md)
- [Core Concepts](core-concepts.md)
- [Materialization Guide](materialization-guide.md)
- [The Ordinal Array Contract](patterns/ordinal-array-contract.md)

