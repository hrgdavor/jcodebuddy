# hipster-entity-core

The **ordinal-array runtime** for [`hipster-entity`](../doc-hipster-entity/README.md).

Where [`hipster-entity-api`](../hipster-entity-api/README.md) declares the
contracts, this module provides the concrete materializations that make
them work without reflection: positional arrays, bitset-backed change
tracking, and the array-backed view proxy.

It depends on `hipster-entity-api` only. Nothing here knows about
Jackson, JavaParser, or the generator.

## What lives here

### Ordinal arrays

| Class | Role |
|---|---|
| [`EntityReadArray`](src/main/java/hr/hrg/hipster/entity/core/EntityReadArray.java) | a read-only `ViewReader` over an `Object[]`; validates that the field-enum constant count matches `values.length` at construction |
| [`EntityUpdateArray`](src/main/java/hr/hrg/hipster/entity/core/EntityUpdateArray.java) | a plain `ViewWriter` over an `Object[]`; last write wins, no change tracking |
| [`EntityUpdateTrackingArray`](src/main/java/hr/hrg/hipster/entity/core/EntityUpdateTrackingArray.java) | the abstract base for change-tracking arrays; implements `ViewWriter` and `ViewChangeTracking<F, EEnumSet<F>>` |
| [`EntityUpdateTrackingArray64`](src/main/java/hr/hrg/hipster/entity/core/EntityUpdateTrackingArray64.java) | the ≤ 64-field variant: one `long` bitmask, no bitmask array allocation |
| [`EntityUpdateTrackingArrayLarge`](src/main/java/hr/hrg/hipster/entity/core/EntityUpdateTrackingArrayLarge.java) | the > 64-field variant: `long[]` bitmask |

Use the factory rather than a constructor — it picks the variant from the
field count. The first argument is a `ForNameOrdinal`, **not** the field enum:
the enum's `forName` returns a constant, while this factory needs
`int forNameOrdinal(String)`. The generated `META` implements that interface, so
pass it:

```java
// META is the ForNameOrdinal: `int forNameOrdinal(String)`, which is what
// set(String, Object) needs. `PersonSummary_::forName` does NOT compile here —
// it returns PersonSummary_, not int.
EntityUpdateTrackingArray<PersonSummary, PersonSummary_> array =
        EntityUpdateTrackingArray.create(PersonSummary_.META, PersonSummary_.values(), rowValues);
```

The `universe` argument (`PersonSummary_.values()` here) is what makes the array
know its own field enum: it is the fix for the `NullPointerException` that every
proxy-backed tracking construction used to throw, when the factory had no route
to the enum class at all.

### The `EEnumSet` family

A minimal, allocation-conscious bitset over an enum's ordinals, used as
the change set both by the arrays and by generated tracking builders.

| Type | Role |
|---|---|
| `EEnumSetRead<E>` | the read surface: `has(int)`, `has(E)`, `hasAny`, `hasAll`, `size()`, `isEmpty()`, `forEach` (both `ObjIntConsumer` and `Consumer`), `getBits0()`, `getBits(int)`, `getSegmentCount()`, `toEnumSet()`, `toList()`, `toArray(E[])` |
| `EEnumSet<E>` | the immutable set; adds `union`, `intersect`, `difference`, `toBuilder()`, and the `copyOf` factories |
| `EEnumSetBuilder<E>` | the mutable builder; adds `add`/`remove` (ordinal and enum-value forms), `addOrdinal(int)`/`removeOrdinal(int)`, `addAll`/`removeAll`/`retainAll`, `clear()`, and `toImmutable()` |
| `EEnumSetBuilder64` / `EEnumSetBuilderLarge` | the two concrete builders, picked by `EEnumSetBuilder.create(Class)` at 64 constants |
| `EEnumSet64` / `EEnumSetLarge` | the two concrete immutable sets |
| `EEnumSetEmpty` | the cached empty singleton; `EEnumSetEmpty.of(Class)` keeps the empty case allocation-free |
| `EEnumSetAll` | the full-universe set |

`EEnumSet64` and `EEnumSetEmpty` are **siblings**, not subtype and
supertype. That is why the tracking contract's `S` parameter is the
`EEnumSet<E>` **interface**, never `EEnumSet64`: `toImmutable()` returns
`EEnumSet<E>` and yields `EEnumSetEmpty` when empty, so no cast to
`EEnumSet64` can be correct.

### Change tracking

| Type | Role |
|---|---|
| [`ViewChangeTracking`](src/main/java/hr/hrg/hipster/entity/core/ViewChangeTracking.java) | the shared contract of the generated `<View>BuilderTracking` and the array-backed updatable proxy: **which** fields changed, and the value each holds now |
| [`FieldChange`](src/main/java/hr/hrg/hipster/entity/core/FieldChange.java) | one `record FieldChange<E extends Enum<E>>(E field, Object current)` — the unit of `changedValues()`; there is no `previous` component |
| [`ListDelta`](src/main/java/hr/hrg/hipster/entity/core/ListDelta.java) | one finding about a tracked collection: an entry added, removed or moved (an **identity** baseline from `Identifiable.id()`), or a per-entry field change |
| [`ChangePath`](src/main/java/hr/hrg/hipster/entity/core/ChangePath.java) | one link of a **deep** change path: `record ChangePath(FieldDef field, int listIndex, ChangePath next)`, with `of`, `then`, `isListElement`, `leaf()`, `depth()`, `render()` |

### The array-backed proxy

[`ArrayBackedViewProxyFactory`](src/main/java/hr/hrg/hipster/entity/core/ArrayBackedViewProxyFactory.java)
wraps an array as a view interface:

- `createRead(ViewMeta, EntityReadArray)` / `createRead(Class, EntityReadArray, FieldNameMapper)`
  produce a JDK proxy implementing the view interface (and `ViewReader`),
  resolving each zero-arg accessor through the field mapper to
  `values[field.ordinal()]`.
- `createUpdatable(Class, EntityUpdateTrackingArray, FieldNameMapper)`
  produces a proxy implementing the view interface **and**
  `ViewChangeTracking`, so `changes()`, `changesBuilder()`,
  `changedValues()` and `currentValue(...)` can be read straight
  off the proxy. `changesBuilder()` returns the array's **own** builder —
  not a copy — which is what makes the proxy satisfy the state-sharing
  rule below without a wrapper.

## The contracts

These are the rules a caller (and a generated adapter) may rely on.
They are stated once here and in
[The Ordinal Array Contract](../doc-hipster-entity/user/patterns/ordinal-array-contract.md);
that document is the long form, with the adapter consequences.

### The positional contract

`values[field.ordinal()]` holds the value of `field`, for every constant
of the view's `FieldDef` enum, so `values.length == universe.length ==
meta.fieldCount()`. The enum's **constant order is a persisted ordinal
layout**, which is why it is append-only — see
[DEC-023](../doc-hipster-entity/architecture/decisions/DEC-023.md).

Ordinal 0 is the entity identity and is **immutable** in both
`EntityUpdateArray` and `EntityUpdateTrackingArray`: `set(0, …)` throws
`UnsupportedOperationException`.

### S1 — only `FieldKind.COLUMN` fields are writable

Writability is decided by the field's `FieldKind`:

- `COLUMN` (the default for an unannotated field) — writable;
- `DERIVED` and `JOINED` — **readable only**.

A derived or joined field keeps its ordinal and its value slot; it simply
gets no setter and is rejected by the positional `set(int, Object)` arms
of the generated builders. The arrays themselves are kind-agnostic —
they are positional storage — so the gate lives in the generated
classes, which is where the field kinds are known at compile time.

### S4 — skip absent on write, tolerate null on read

A field **absent** from a payload is simply not written back out; it is
not the same thing as a field that arrived as an explicit `null`:

- **absent** → no write, no change bit;
- **explicit `null`** → a real write of `null`: the change bit is set, and
  the field's `currentValue(...)` is `null`. No parallel presence array is
  needed anywhere, because the change bit itself already carries the
  presence question: a field that was never written is unmarked, and a
  field written as `null` is marked.

`ViewMeta.create(Object[])` must **accept `null`** at any ordinal rather
than throw — in particular for `DERIVED`/`JOINED` fields, which never
arrive over the wire. No "missing required field" rejection is added.

Two limits on this, so it is not over-read: S4 is about presence in a
*full payload* and does **not** permit dropping a null from a *changed*
field's patch; and a `DERIVED` field is not writable (S1), so it cannot
appear as a change in the first place.

### S5 — snapshot vs live view

`changes()` and `changesBuilder()` are **two views over one piece of
state**, never two copies, so they cannot disagree:

- `changes()` is an **immutable snapshot** taken at call time. A
  snapshot captured before a mutation still reports the old state.
- `changesBuilder()` is the **live, mutable** set. Mutating it is
  visible through a *later* `changes()` call.
- Only `changesBuilder()` can mutate. `changes()` allocates when the set
  is non-empty and stays allocation-free (via `EEnumSetEmpty`) when it is
  empty, so a hot path must use `changesBuilder()`.

An implementation that returns a copy from `changesBuilder()` violates
this contract.

### D5 — the `-1` probe vs the proxy's exception

`set(String fieldName, Object value)` on an array or builder is a
**probe**: it returns the ordinal it wrote, or `-1` when the name is
unknown. It does not throw, so a low-level caller can test a name
without a try/catch.

The user-facing proxy is the layer that turns the probe into an error:
`ArrayBackedViewProxyFactory`'s updatable handler throws
`IllegalArgumentException("Unsupported mutator: …")` when the probe
returns `-1`. The read path behaves the same way
(`IllegalArgumentException("Unsupported accessor: …")`).

So: `-1` from the array, exception from the proxy. Both are contract,
not accident.

### The write-site comparison (and no baseline)

A write of a value `equals` to the one the field currently holds marks
nothing: the generated tracking setter is
`if (!java.util.Objects.equals(this.firstName, value)) { this.firstName = value; mf.addOrdinal(1); }`,
and `EntityUpdateTrackingArray.set(int, Object)` compares the slot it
holds before marking. The comparison lives **at the write site**, not in
the change set — the change set is a plain ordinal set
(`addOrdinal`/`removeOrdinal`/`has`/`toImmutable`) that keeps no values
at all.

The stated consequence, so it is not over-read: **a write back to the
original value afterwards leaves the field marked.** There is no stored
baseline that could unmark it, because no previous value is kept. Relative
to the state the tracker saw, the field *was* written with a different
value; that is the whole of what the tracker claims.

A consumer that needs "does this differ from my baseline?" therefore
compares the current instance with the **baseline instance the caller
still holds** — the mutable was built from another mutable or from an
immutable, and that source belongs to the caller, not to the tracker:

```java
// The caller owns both sides of the comparison; the tracker owns neither the old value…
PersonSummary baseline = view;                    // what the mutable below was built from
PersonSummaryBuilderTracking tracked = new PersonSummaryBuilderTracking(view);

tracked.firstName("Grace");                       // marks firstName
tracked.changedValues().forEach(c ->
        System.out.println(c.fieldName() + " is now " + c.current()));

// …so the old half is read from the baseline the caller holds.
if (!java.util.Objects.equals(baseline.firstName(), tracked.firstName())) {
    System.out.println("firstName " + baseline.firstName() + " -> " + tracked.firstName());
}
```

That is the deliberate design: the library supplies `changedValues()` for
the new half and nothing for the old half. Keeping a copy of the old value
inside the tracker would duplicate state the caller already owns and would
make "did this change?" depend on two sources of truth.

#### The shallow-reference hazard is gone by construction

The previous design stored a copied reference to the overwritten value, so
mutating a `Map`/`List` field **in place** instead of replacing it left the
recorded "previous" value reflecting the later mutation — a stale shallow
reference that had to be documented and pinned by test. **That hazard is
now impossible by construction**, because there is no stored previous value
to go stale. Nothing in the library holds a reference to the value a field
used to have, so nothing can be captured, copied or mutated behind the
caller's back.

### DEC-012 no-op rule

Compare, then mark: a difference is what sets the bit, and an equal
assignment sets nothing. The comparison happens at the write site (see
above) — in the generated setter for a tracking builder, and in
`EntityUpdateTrackingArray.set(int, Object)` for the array-backed
materialization.

## Running the benchmarks

The pull-vs-push axis (`changesDeep()` versus a hand-built shallow
set) is measured with JMH, under the `jmh` profile:

```bash
# generate the harness (clean is required, and -am so core builds against this reactor's api)
mvn -o clean test-compile -Pjmh -pl hipster-entity-core -am

# run four benchmarks, short runs
mvn -o -Pjmh -pl hipster-entity-core -am exec:java \
    -Dexec.mainClass=org.openjdk.jmh.Main \
    -Dexec.classpathScope=test \
    -Dexec.args="pullNoNestedChange|shallowOnlyNoNestedChange|pullDeepNestedChange|shallowOnlyDeepNestedChange -f 1 -wi 4 -i 6 -r 2s -w 1s"
```

Both flags in that first line earn their place, and both were measured
while writing this section: without `-am`, `hipster-entity-core` resolves
`hipster-entity-api` from the installed `~/.m2` jar — a previous revision
— and the build dies on `method does not override or implement a method
from a supertype` for code that is correct (F-52's trap again). After the
correct command, `target/test-classes` contains 52 `*_jmhTest` classes and
`META-INF/BenchmarkList`, which is what "the harness exists" means —
before the `-proc:full` fix the benchmark sources compiled as ordinary
classes and none of that was produced.

**`clean` is not optional, and neither is `-Pjmh`.** JDK 23 and later do
not run annotation processors discovered on the classpath unless
processing is requested explicitly, so the profile adds `-proc:full` to
`maven-compiler-plugin`; without a `clean`, Maven's incremental check
skips the recompile, the JMH processor never gets its second chance,
and the run dies with "no benchmarks" — while the build stays green.
That is notes F-49, and it is the same hazard as F-47 (a gate satisfied
by a previous revision's class files) in a second place.

The measured result (JDK 25, the flags above, ops/ms) is recorded in
[`../plans`](../plans/plan.dsflash.notes.md)
under F-49, together with the two caveats that matter when reading it:
the `shallowOnly*` variants reach through an extra indirection, so their
~6% gap is a property of the harness rather than of push-vs-pull, and
the error bars on short runs on a shared machine are wide enough that the
numbers are fit for a regression gate, not for a performance claim.

## See also

- [The Ordinal Array Contract](../doc-hipster-entity/user/patterns/ordinal-array-contract.md) —
  the long-form contract, with the adapter consequences.
- [JDBC Row Adapter](../doc-hipster-entity/user/patterns/jdbc-row-adapter.md) —
  how a `ResultSet` reaches `values[field.ordinal()]`.
- [DEC-012 — Update-array and change-tracking semantics](../doc-hipster-entity/architecture/decisions/DEC-012.md) —
  the no-op rule, explicit-null-sets-the-bit, and the array factory.
- [DEC-014 — EnumSet concrete dispatch strategy](../doc-hipster-entity/architecture/decisions/DEC-014.md) —
  why the `64`/`Large` split exists.
- [DEC-016 — Field-name-to-ordinal dispatch](../doc-hipster-entity/architecture/decisions/DEC-016.md) —
  why lookup is a generated switch and never a per-call `HashMap`.
- [DEC-023 — R1: field enums are append-only ordinal ledgers](../doc-hipster-entity/architecture/decisions/DEC-023.md) —
  why the ordinal a tombstone occupies never moves.
