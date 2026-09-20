# Deep (Nested) Change Tracking

## Goal

Answer the question shallow change tracking cannot: **a nested value changed, and no field of the
view you are holding was written.** This page is the practical companion to
[DEC-024](../architecture/decisions/DEC-024.md) — the decision record is the normative text, and
this is the recipe.

## When this applies

Read this page if you are:

- building a JSON patch or event payload from a tracked view whose fields include other tracked
  views, or lists of them;
- deciding whether a change is "at this level" or "inside something this level holds";
- writing a persistence or audit adapter that has to record *where* a change happened, not just
  *that* the row changed.

If your views are flat — scalars only — you do not need any of this. `changes()` and
`changesBuilder()` are unchanged and remain the whole story.

## Shallow vs deep: two different questions

| | Shallow | Deep |
|---|---|---|
| Method | `changes()`, `changesBuilder()` | `changesDeep()` |
| Answers | **which** fields of *this* view changed | **where** every changed leaf is, this view and everything it holds |
| Returns | an `EEnumSet` of marked ordinals | a `List<ChangePath>` |
| A nested view's change | invisible if the reference was not reassigned | reported, with the path to the leaf |
| Cost | one bitset | a walk on read (pull) |

The two are **not** overloads and neither is context-dependent. `changes()` always means "these
fields of this view"; `changesDeep()` always means "these paths, all the way down". A call site
therefore says which one it wants, and a reader can see it.

Do not confuse words here, because the codebase uses two deliberately different phrases:

- **call-chain trace** — following one level from an accessor to a change bit to a snapshot. This is
  what shallow tracking does, and what the ordinal-array contract describes.
- **nested / deep tracking** — the feature on this page.

"Full depth" must never be used for both.

## The problem, concretely

```java
PersonSummary person = load();
Address address = person.address();

address.city("Berlin");   // writes a field of `address`, not of `person`

person.changes();          // empty -- nothing at this level was written
person.changesDeep();      // [ address.city ]
```

The shallow bit means exactly one thing: *this field of this view was reassigned*. Mutating the
object that a field points at does not reassign the field, so there is no bit. That is not a bug and
it is not going to change — it is why the deep accessor exists as a separate method.

> **Hazard, pinned by test.** Writing the *reference* (`person.address(otherAddress)`) **is** a
> field write, so `changes()` marks `address` — that is the shallow-reference hazard of
> `DEC-012`/D2. Clear the bits and mutate only the leaf, and `changes()` stays empty while
> `changesDeep()` reports the path. Assert both halves separately; a single assertion covering both
> would be false.

## The pull model

> **Propagation is pull.** `changesDeep()` walks into every field whose value is itself a
> `ViewChangeTracking`, asks the child, and ORs the child's answer into the *reported* result. The
> parent's own bitset is never touched.

Three consequences worth relying on:

1. **`changes()` never grows to include descendants.** Asking for the deep answer does not change
   what the shallow answer says, now or later.
2. **Children are reusable and shareable.** A nested view can be held by any number of parents (or
   none) and reports the same answer to each. There is no parent/child lifetime coupling and no
   back-reference to keep in sync.
3. **The walk is ordinary Java.** Every hop is a method call on a typed interface, so an IDE can
   follow `changesDeep()` into the child's `changesDeep()` with go-to-definition. Nothing is
   registered, intercepted or reflected.

The model that was **rejected** — and will not be offered as a flag — is push: wrap every tracked
value in a notifying proxy that calls back into its parent. It is zero-cost when nothing changed, but
it makes every value in every slot a wrapper, makes a child single-parent, and hides propagation
inside another object's setter. See DEC-024 § 1.

### Reading a path

```java
for (ChangePath path : view.changesDeep()) {
    path.field();       // the field at this level
    path.listIndex();   // the index inside a List field, or -1 when not a collection element
    path.next();        // the rest of the path, or null at the leaf
    path.leaf();        // the field that actually changed
    path.depth();       // 1 for a leaf-only path
    path.render();      // "addresses[3].city" -- for messages and tests
}
```

`listIndex` is an **integer**, never a field name, so a collection index can never be confused with a
document member.

## Collections of tracked views

A `List<Address>` where `Address` is a tracked view is the hard case. It changes in two independent
ways, and both are reported **distinctly** — one is not expressed in terms of the other:

| You did | You get |
|---|---|
| edited a field of an element that stayed put | a `ChangePath` with `listIndex` set (`ListDelta.kind() == FIELD_CHANGED`) |
| added an element | `ListDelta.kind() == ADDED` |
| removed an element | `ListDelta.kind() == REMOVED` |
| moved an element | `ListDelta.kind() == REORDERED` |

```java
Map<Integer, List<ListDelta>> deltas = view.collectionDeltas();
for (ListDelta delta : deltas.get(addressesOrdinal)) {
    delta.kind();           // ADDED / REMOVED / REORDERED / FIELD_CHANGED / REPLACED / UNCHANGED
    delta.index();          // where it is now (for REMOVED: where it was)
    delta.previousIndex();  // where it was, or -1 for an addition
    delta.identity();       // the entry's id() when it has one
    delta.fieldChanges();   // the per-field delta inside the entry, when there is one
    delta.fallback();       // true when this came from the positional fallback
}
```

A `List` field is walked **whether or not the field itself was marked**, because mutating,
adding or removing an element writes to no field of the parent — the marked ordinals alone would
never reach it.

### The identity requirement

**Reorder detection requires a stable identity per entry, so the element type must be
`Identifiable<ID>` ([DEC-017](../architecture/decisions/DEC-017.md)).**

The reason is not stylistic. "Moved" means *same entry, different index*. Without an identity there
is no way to tell a move from a remove-plus-add, and comparing entries by `equals` answers a
different question — content equality — which reports a move every time two entries happen to be
equal, and again every time an edit makes an entry equal to its neighbour.

Where identities exist, "did this entry move?" is answered **relative to the entries that survived**,
not against absolute indices:

> An entry is `REORDERED` when it is not part of the longest run of surviving entries that is still
> in baseline order.

So removing the first of three entries does **not** report the other two as moved (they shifted, they
did not move), while reversing three entries reports two moves and leaves the middle one alone.

### No identity: a diagnostic and a fallback, never a guess

If the element type is not `Identifiable` — or two entries report the **same** id, which makes an id
name more than one position — you get:

1. positional deltas only, with `fallback() == true`;
2. a diagnostic on the view:

```java
for (CollectionDiagnostic d : view.collectionDiagnostics()) {
    d.ordinal();   // the field that holds the collection, or -1
    d.code();      // collection_element_not_identifiable / collection_duplicate_identity
    d.message();
}
```

3. **no invented move.** The tracker will not pair entries by index and call the result a reorder.

Treat the diagnostic as a signal to give the element type an `id()`; the fallback is a correct but
coarser answer, not an error.

### The baseline

The baseline is the entry identity list the tracker holds, and it is **the version you loaded**. It
is taken the first time the tracker is asked, and moved forward on demand:

```java
view.clearChanges();            // resets the change set AND re-snapshots every collection baseline
view.snapshotCollection(ord);   // one field only, when you want to keep the rest
```

A field write that installs a *different* list instance is reported to the tracker, so replacing the
whole list is a removal of every old entry plus an addition of every new one — which is what
happened.

## Emitting a patch

`hipster-entity-jackson` writes the deep result as a nested RFC 6902-like document:

```java
StringWriter out = new StringWriter();
EntityJacksonMapper.toJsonDeepChanges(meta, tracking, out);
```

```json
{
  "fields": {
    "firstName": { "op": "replace", "current": "Ada-2" },
    "addresses": {
      "op": "replace",
      "paths": [
        { "path": ["addresses", 0, "city"], "current": "Berlin" }
      ],
      "collection": {
        "changes": [ { "op": "remove", "index": 1, "identity": 42 } ]
      }
    }
  }
}
```

No entry carries a `"previous"` member anywhere: a leaf states only the value it holds
**now**, so "the patch touches only the leaf" is visible in the document rather than
inferred from it. That is not an omission the serializer makes — the tracker keeps no
previous value, so there is nothing for it to write. An audit-style
old&nbsp;→&nbsp;new document is a comparison of **two** instances, so you build it from
the baseline view you hold:

```java
PersonSummary baseline = view;                 // the view `tracking` was built from

tracking.changedValues().forEach(change ->     // the shallow level: the new half
        System.out.println(change.fieldName() + " is now " + change.current()));

// The old half is read from the baseline, at the same path the deep patch names.
System.out.println("addresses[0].city " + baseline.addresses().get(0).city()
        + " -> " + tracking.addresses().get(0).city());
```

For a leaf **inside a collection element** the comparison is anchored by identity: find the
baseline element whose `Identifiable.id()` equals the delta's `identity()`, and read the
field there. `previousIndex()` gives the position the entry occupied in the baseline (and
`-1` for an addition, which has no old value to report). Reordering does not break the
lookup — identity is exactly what makes the baseline entry findable after a move, which is
why the tracker keeps that one baseline and never a copy of an element's field values.

Notes that matter when you consume it:

- the **path array** keeps a collection index an integer, so an append (`"add"` at index *n*) is not
  confused with a document member;
- an add, a remove and a move are **separate operations**, never a single "replace the list";
- a field the view does not have is **skipped**; nothing is resolved through a name→ordinal map
  ([DEC-016](../architecture/decisions/DEC-016.md));
- `"fallback": true` and a `"diagnostics"` array appear whenever identity matching was not possible.

The shallow serializer (`EntityJacksonMapper.toJsonChanges`) is unchanged and still the right answer
when a whole field was reassigned. The two documents are complements, not versions of each other.

## Checklist

- [ ] I use `changes()` for "which fields of this view" and `changesDeep()` for "where, all the way
      down", and I know which one each call site uses.
- [ ] I do not expect `changes()` to report a descendant change, and I do not expect
      `changesDeep()` to change what `changes()` says.
- [ ] I read `listIndex()` as an integer index, and `-1` as "not a collection element".
- [ ] My collection element type implements `Identifiable`, or I read
      `collectionDiagnostics()` and accept the positional fallback.
- [ ] Every element of a tracked collection has a **distinct** id.
- [ ] I call `clearChanges()` after wiring a view up, so the wiring is not reported as a change.
- [ ] I distinguish an add, a remove and a move when I apply a patch; I never treat them as one
      "replace".
- [ ] I know a leaf inside a collection element is a field of the *element*, so both halves of any
      old → new comparison for it come from the **element** — its current value from the element's own
      tracker, and its old value from the baseline element I find by the `identity()` the delta reports.

## See also

- [DEC-024 — Deep (nested) change tracking — pull over push](../architecture/decisions/DEC-024.md) —
  the normative record, including the rejected alternatives and the acceptance criteria.
- [DEC-012 — Update-array and change-tracking semantics](../architecture/decisions/DEC-012.md) — the
  shallow contract and the no-op rule, plus the revision that removed the previous-value half (which
  is what retires the shallow-reference hazard by construction).
- [DEC-017 — `Identifiable<ID>` as opt-in identity mixin](../architecture/decisions/DEC-017.md) — the
  mixin reorder detection depends on.
- [DEC-016 — field-name-to-ordinal dispatch](../architecture/decisions/DEC-016.md) — why no
  name→ordinal map is built anywhere in this path.
- [The Ordinal Array Contract](ordinal-array-contract.md) — the positional storage this walks over.
- [Jackson Setup](jackson-setup.md) — the shallow change-set shape next to the deep patch.
- [`ChangePath`](../../../hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/ChangePath.java),
  [`ListDelta`](../../../hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/ListDelta.java),
  [`ListChangeTracker`](../../../hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/ListChangeTracker.java),
  [`ViewChangeTracking`](../../../hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/ViewChangeTracking.java).
