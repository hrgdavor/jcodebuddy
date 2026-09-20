# The Ordinal Array Contract

## Goal

Define the one interface a real project's persistence layer has to implement in order to feed a
`hipster-entity` view: a positional `Object[]` whose slots line up with the field ordinals of the
view's companion `FieldDef` enum.

Everything else in the library — the array-backed proxy, the Jackson reader, the generated
builder — is written against this contract. An adapter that satisfies it needs to know nothing
else about `hipster-entity`; an adapter that violates it fails quietly, because the array is
addressed by ordinal and has no way to detect that a slot holds the wrong field's value.

## When this applies

Read this document if you are writing any of the following:

- a persistence adapter that fills a view from a query result (see
  [JDBC Row Adapter](jdbc-row-adapter.md) for the reference implementation);
- a non-JDBC wire adapter — binary formats, message queues, columnar readers;
- a mapper between two views;
- generated code that materializes a view from a positional array.

You do **not** need this contract to *consume* a view. A caller that only invokes accessors on a
`PersonSummary` never sees the array.

## Terminology

| Term | Meaning |
|---|---|
| view | a `hipster-entity` interface, e.g. `PersonSummary`, whose zero-argument methods are its fields |
| field enum | the generated companion enum, e.g. `PersonSummary_`, which `implements FieldDef` |
| field constant | one constant of that enum, e.g. `PersonSummary_.firstName` |
| ordinal | `FieldDef.ordinal()` — the constant's declaration position, which is also its array index |
| values array | the `Object[]` an adapter produces, indexed by ordinal |
| `META` | the generated `ViewMeta` singleton, e.g. `PersonSummary_.META` |

## The contract

> **`values[f.ordinal()]` is the value of field `f`, for every `f` in the companion `FieldDef`
> enum, and `values.length == FieldDef.values().length`.**

The contract is stated once in the plan and once in code
(`EntityUpdateTrackingArray`'s class Javadoc); this page is its normative, user-facing form.
Written as rules:

| # | Rule |
|---|---|
| 1 | `values[f.ordinal()] ==` the value of field `f`, for every `f` in the companion `FieldDef` enum. |
| 2 | `values.length == FieldDef.values().length == meta.fieldCount()`. |
| 3 | Ordinal `0` is the **identity field of an entity root**. See [Ordinal 0](#ordinal-0-identity) — it is immutable in tracking arrays but writable through the generated tracking builder. |
| 4 | A `DERIVED` or `JOINED` field **still occupies its ordinal**. An adapter that cannot fill it stores `null`. |
| 5 | Column order comes from `ViewMeta`, **never** from `SELECT *`. |
| 6 | The ordinal list is **append-only**: new fields get new ordinals at the end. A retired field keeps its ordinal and its slot. |
| 7 | Field **names** are `enum.name()`, and must match the zero-argument accessor name on the view interface exactly. |
| 8 | Name→ordinal resolution goes through `ViewMeta.forName()`, never a per-call `HashMap`. |

Rules 1, 2, 4, 5 and 7 are the adapter's daily working rules. Rule 3 is the one asymmetry to be
aware of; rule 6 is the change-management rule; rule 8 is a performance rule with a correctness
component.

### A worked layout

`PersonSummary_` declares six constants. Its array always has six slots, in exactly this order:

| Ordinal | Constant | `values[ordinal]` holds |
|---|---|---|
| 0 | `id` | the entity identity |
| 1 | `firstName` | the first name, or `null` |
| 2 | `lastName` | the last name, or `null` |
| 3 | `age` | `@FieldSource(kind = DERIVED)` — `null` when the adapter cannot compute it |
| 4 | `departmentName` | `@FieldSource(kind = JOINED)` — `null` when the adapter cannot join it |
| 5 | `metadata` | an arbitrary `Map`, or `null` |

An adapter that "skips" the derived and joined fields by *omitting their slots* and returns a
four-element array has not produced a `PersonSummary`. It has produced an array that no longer
matches the enum: callers indexing ordinal 3, 4 and 5 either read the wrong field or walk off the
end of the array. Slot count and slot position are both part of the contract, and an array of the
right length with the wrong contents is the worse of the two failures, because it is silent.

### Ordinal 0: identity

Ordinal 0 is the entity's identity field (`id()` on an entity root). Two write paths treat it
differently, and the asymmetry is deliberate:

- **Array path — immutable.** `EntityUpdateTrackingArray.set(0, …)` throws
  `UnsupportedOperationException`. The identity of an array-backed updatable view cannot be
  rewritten through the positional writer.
- **Generated tracking builder — writable.** The generated tracking builder *does* keep an
  ordinal-0 setter, because `id()` carries no `@FieldSource` and therefore defaults to
  `FieldKind.COLUMN`, which is the writability rule. A builder built from a form payload may
  legitimately set the identity before insert.

So "ordinal 0 is immutable" is an **array-path-only** statement. A parity test between the two
tracking materializations must not drive ordinal 0 on both paths and expect the same answer; it
holds for ordinals ≥ 1. When an adapter fills an array from a result set, slot 0 is written like
any other slot — the immutability applies to *updating* an existing tracked view, not to
*constructing* a read array.

### `DERIVED` and `JOINED` fields still occupy their ordinal

`FieldKind.DERIVED` and `FieldKind.JOINED` fields are not writable
(`@FieldSource`-annotated fields with those kinds get no generated setters), but they are still
**fields of the view**: they have a constant, an ordinal, and a slot.

A plain result-set adapter usually cannot fill them:

- a `DERIVED` field is computed downstream from other fields;
- a `JOINED` field comes from another table that this particular query did not touch.

The adapter's obligation is only to leave `null` in the slot. The slot must exist, because a
reader or a mapper may legitimately read the array positionally, and because the record
implementation holds one component per constant — a `DERIVED` field is a record component like
any other.

### Column order comes from `ViewMeta`

Never derive the slot order from the shape of a `SELECT`. `SELECT *` returns whatever column
order the schema or the query planner produces, and an unrecognized column makes the result
silently wrong rather than loudly wrong.

The order is `ViewMeta.fieldValues()`, which is the `FieldDef` enum's declaration order. Query
builders that project a view should emit their column list from `meta.fieldNameAt(i)` for
`i` in `[0, meta.fieldCount())` in that order, which makes the `SELECT` list and the array layout
the same artifact.

If you cannot control the query's column order, do not index the result set by position. Resolve
each column to its field once, up front, and write to `values[field.ordinal()]` — the
[JDBC Row Adapter](jdbc-row-adapter.md) shows the shape.

## Rule R1 — field enums are append-only ordinal ledgers

This rule is binding on every `hipster-entity` project and deserves its own statement here,
because it is what makes ordinals safe to persist in the first place. It has a standalone
decision record, [**DEC-023 — R1: field enums are append-only ordinal ledgers**](../../architecture/decisions/DEC-023.md)
(indexed in [`decisions/README.md`](../../architecture/decisions/README.md); it rides the
generator's class-file header mechanism of [DEC-021](../../architecture/decisions/DEC-021.md) but is
deliberately *not* an amendment of it, so the order contract can be read without reading the
generator-header decision).

> Once an entity's field enum has been generated, every subsequent generation step that finds new
> fields must respect the order of the existing constants and append the new ones after them.
> Existing constants are never reordered, never moved, and never inserted between.

The rule is opt-in by a marker in the generated file's header (`entityFieldEnum: true`), and a
generator-side checker plus a build-gating CLI enforce it against a git baseline. For an adapter
author the consequences are these:

1. **Ordinals never move.** A field that was ordinal 3 stays ordinal 3 for the life of the
   persisted layout. Adding a field appends it; it never inserts.
2. **A retired field keeps its ordinal and its slot.** When a view drops an accessor, the
   constant is *not* deleted. It becomes a tombstone: `@Deprecated`, still present at its
   original position, and still counted in `fieldCount`.
3. **A retired field reports `retired() == true`.** `FieldDef.retired()` is the write-side gate.
   Writers skip a retired field; readers stay tolerant.
4. **A retired field's slot still exists and may be `null`.** A row written before the column was
   dropped may still carry a value; a row written after it does not. Both are legal array
   contents, and the record keeps a component for the tombstone (nullable) so the 1:1 positional
   mapping in `create()` stays a straight mapping.
5. **`fieldCount()` may grow; it does not shrink on its own.** Additions append. The only way
   `fieldCount` shrinks is **compaction**, a deliberate, separately invoked migration that drops
   tombstones and renumbers every ordinal after them. Compaction may only run when no persisted
   array, patch, or snapshot survives, because it invalidates all of them.

Points 2–4 are the ones that show up in adapter code:

- **Never emit a column for a `retired()` field** in an `INSERT` or `UPDATE`. Retired is not the
  same as `DERIVED`/`JOINED`: a tombstone defaults to `FieldKind.COLUMN` (there is no accessor
  left to carry `@FieldSource`), so `fieldKind()` alone would happily include it. `retired()` is
  the only signal that says "do not write this".
- **Keep reading a retired slot if the row still carries it.** A reader that rejects an
  unexpected column is more brittle than one that accepts it.
- **Do not renumber.** If you have persisted arrays, patches, or snapshots, an "obvious"
  cleanup that removes the dead constant corrupts every one of them.

The concrete case, from the R1 record:

```java
/** @deprecated no longer an accessor on PersonSummary; retained to preserve ordinals. */
@Deprecated
middleName(String.class),
```

## Names are `enum.name()`, with no mapping layer

The constant name **is** the field name, and it must match the view's zero-argument accessor name
character for character. There is no `@Column(name = "…")`-style rename step, no
`methodName()`, and no naming-strategy hook — this mirrors Java records, where the component name
is the accessor name.

```java
public interface PersonSummary extends PersonEntity {
    String firstName();   // accessor name
}

public enum PersonSummary_ implements FieldDef {
    firstName(String.class),   // must be exactly "firstName" — not "first_name", not "firstname"
    // …
}
```

Breaking this has a specific, unpleasant symptom: the proxy resolves the accessor `firstName()` to
the name `"firstName"`, `forName.forName("firstName")` returns `null`, and every accessor call fails
at runtime even though the code compiled. It is a runtime mismatch, not a compile error — which
is why the generator emits the constant name from the accessor name directly.

## Name→ordinal resolution: `ViewMeta.forName()`

When an adapter has a field **name** (a result-set column label, a JSON token, a map key) and needs
its ordinal, the only sanctioned route is:

```java
F field = forName.forName(name);      // generated switch — O(1), zero allocation
if (field != null) {
    int ordinal = field.ordinal(); // capture once
    values[ordinal] = /* … */;
} else {
    // unknown name — skip
}
```

`forName.forName(name)` is backed by a **generated `switch` statement** returning the enum constant,
so it is O(1), allocation-free, and inlinable. It is the only permitted name→ordinal mechanism.

### DEC-016 — a per-call `HashMap` is forbidden

Building a `HashMap<String, Integer>` (or any other dynamic name→ordinal structure) instead of
calling `forName` is prohibited by decision record
[DEC-016](../../architecture/decisions/DEC-016.md). The reasons are concrete:

- the map **allocates** on every call that builds it;
- it hashes **every field name** during setup;
- it hashes **every incoming token** on lookup;
- it adds a virtual-dispatch chain (`HashMap.get → getNode → hashCode → equals`) that the JIT
  cannot inline as cheaply as the switch it replaced.

And the structure reconstructs at runtime something the compiler already encoded statically. A
generated `switch` on string literals is exactly the lookup the map was emulating.

```java
// ❌ FORBIDDEN — per-call map, hashed both ways
Map<String, Integer> byName = new HashMap<>();
for (int i = 0; i < meta.fieldCount(); i++) {
    byName.put(meta.fieldNameAt(i), i);
}
Integer ordinal = byName.get(name);

// ✅ CORRECT — generated switch, zero allocation
F field = forName.forName(name);
if (field != null) {
    values[field.ordinal()] = value;
}
```

A pre-built `readers[]`/`writers[]` array indexed by ordinal, built **once per adapter instance**
rather than per row, is the companion pattern and is equally expected. The full implementation
guide lives at [Implementing field-name-to-ordinal dispatch](../../architecture/field-lookup-guide.md).

One clarification that trips people up: a `switch (name)` the *generator* emits with hardcoded
string literals is already compliant — the switch **is** the O(1) lookup, and it does not need to
call `forName` at all. Only dynamically built maps are prohibited.

## The flow: `QueryProjection → Object[] → META.create()`

A view read from a database moves through three stages:

1. **`QueryProjection`** — the SQL side. It decides which columns to select and in what order, and
   is responsible for emitting them in `ViewMeta` order (rule 5). This is the layer that knows
   about tables, joins, and expressions.
2. **Adapter** — the row side. It walks one result set row and writes each column's value into the
   slot its field occupies: `values[field.ordinal()] = …`. This is the layer this contract
   constrains.
3. **`META.create(Object[] values)`** — the materialization. It hands the array to the view's
   factory, which builds the array-backed read proxy (or the record, depending on the
   materialization level). `create()` is a straight positional mapping; it never branches on which
   constants are present.

```java
ViewMeta<PersonSummary, PersonSummary_> meta = PersonSummary_.META;

// 1-2. the adapter produced one positional row
Object[] values = adapter.fromResultSet(rs, meta);

// 3. materialize
PersonSummary person = meta.create(values);
```

Because step 3 is purely positional, any error in step 2 surfaces as a *wrong value in a valid
field*, never as an exception. Unit-test the adapter by asserting each accessor, and assert
`values.length == PersonSummary_.values().length` — both halves matter.

The same `Object[]` is what the JSON deserializer fills, so an adapter and the JSON reader are
producing the same artifact by different routes. That is the point: one contract, many producers.

## Checklist for adapter authors

- [ ] I allocate `new Object[meta.fieldCount()]`, not a fixed-size array from the query.
- [ ] I write `values[field.ordinal()]`, or I know that the result set is in `ViewMeta` order and
      assert it rather than assume it.
- [ ] `values.length == meta.fieldCount()` before I call `meta.create(values)`.
- [ ] I never reorder slots to "skip" a field I do not have a value for — I store `null` in it.
- [ ] A `DERIVED` or `JOINED` field gets `null`; I do not omit its slot.
- [ ] Ordinal 0 is filled like any other slot when building a read array.
- [ ] I do not emit a column for a field whose `retired()` is `true`.
- [ ] I do not renumber, compact, or drop ordinals.
- [ ] Every field name I use comes from `enum.name()` / `meta.fieldNameAt(i)` /
      `FieldDef.column()`, never from a hand-written string.
- [ ] I resolve names through `forName.forName(name)` and never build a per-call `HashMap`.
- [ ] Any `readers[]`/`writers[]` structure is built once per adapter instance, not per row.
- [ ] I have a test that asserts every accessor of the materialized view, plus the array length.

## See also

- [JDBC Row Adapter](jdbc-row-adapter.md) — the reference reflection-free adapter.
- [Implementing field-name-to-ordinal dispatch](../../architecture/field-lookup-guide.md) — the full
  DEC-016 guide.
- [Core Concepts](../core-concepts.md) — views, field metadata, and field sources.
- [`FieldDef`](../../../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/FieldDef.java) —
  `javaType()`, `name()`, `ordinal()`, `fieldKind()`, `column()`, `relation()`, `expression()`,
  `retired()`.
- [`ViewMeta`](../../../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/ViewMeta.java) —
  `fieldCount()`, `fieldValues()`, `fieldNameAt(int)`, `fieldTypeAt(int)`, `forName()`,
  `create(Object[])`.
- [`EntityUpdateTrackingArray`](../../../hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/EntityUpdateTrackingArray.java) —
  the array path, including the ordinal-0 rule.
- [DEC-016 — field-name-to-ordinal dispatch](../../architecture/decisions/DEC-016.md).
- [DEC-023 — R1: field enums are append-only ordinal ledgers](../../architecture/decisions/DEC-023.md) —
  the full order contract: the `entityFieldEnum:true` marker, tombstoning, the `allowReorder`
  escape hatch, and the `EnumConstantOrderChecker` CLI.
- [`decisions/README.md`](../../architecture/decisions/README.md) — the decision index.
