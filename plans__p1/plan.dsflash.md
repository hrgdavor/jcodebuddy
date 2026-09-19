# hipster-entity — Combined Plan to First Usable Implementation

**Status:** proposed (synthesis) — **scope complete, no deferred features except S3/X1**
**Combines:** `plans__m1/plan.ds.md`, `plans__m1/plan.copilot.md`,
`plans__m1/plan.kilo.md`, `plans__m1/plan.dsflash.md`
**Scope:** `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson`,
`hipster-entity-tooling`, `hipster-entity-test`, `hipster-entity-example`, plus the
`doc-hipster-entity/` corpus that constrains them.
**Goal:** a developer can declare `@View`-annotated entity interfaces, run the generator,
get committed IDE-navigable Java, round-trip it through JSON, and perform a tracked partial
update — and every step is covered by a test.

> This document is a *merge*, not a summary. Where the four input plans disagree, § 9
> records the conflict and the recommended resolution. Where a claim could be checked
> against the source tree without compiling, it was checked in the current working tree
> and is marked **[verified]**; claims that could not be checked without building are
> marked **[unverified]** with the command that would settle them.

---

## 1. What the four input plans agree on

All four converge on the same diagnosis and the same top-level sequence. Treat this as the
spine of the plan:

1. **The runtime primitives are real; the glue is missing.** `hipster-entity-api` and
   `hipster-entity-core` hold working contracts and bitset/array primitives. What does not
   exist is the generator output above `GenLevel.META`, the JSON write path that consumes a
   change set, and any end-to-end test.
2. **No annotation processor, and none is wanted.** Per `AGENTS.md` § 1 / DEC-019,
   `hipster-entity-tooling`'s JavaParser sidecar is the right shape. It must be *extended*,
   never replaced by a `javax.annotation.processing` processor.
3. **The generator must climb the `GenLevel` ladder in order.**
   `META → RECORD → WRITABLE → BUILDER → BUILDER_TRACKED → BUILDER_ALL`, one independently
   shippable step at a time.
4. **Cooperative codegen (DEC-020), class-file headers (DEC-021), and divergence reporting
   (DEC-022) are mandatory, not polish** — they are project-wide rules from `AGENTS.md` § 1.
   But they land *after* the first working level, so they cannot block the slice.
5. **Change tracking must work identically through both materializations:** the generated
   tracking builder, and the array-backed updatable proxy.
6. **`hipster-entity-example` is currently hand-written fiction.** It is the golden output
   the generator must reproduce, not generated output. Any diff after regeneration is either
   a generator bug or a documented divergence.
7. **The docs are ahead of the code and in places actively wrong.** Correcting them is part
   of the definition of done, not a follow-up.

---

## 2. Current state — verified against the working tree

This section is the merge of `plan.dsflash`'s audit (which is by far the most concrete) with
the module inventories in `plan.copilot` § 1 and `plan.kilo` § 2. Corrections where the four
plans disagreed are marked.

### 2.1 Module inventory

| Module | State | What genuinely exists | Gap |
|---|---|---|---|
| `hipster-entity-api` | solid | `View`/`FieldSource`/`FieldKind` annotations, `EntityBase`, `Identifiable`, `FieldDef`, `FieldNameMapper`, `ForNameOrdinal`, `ViewMeta`/`DefaultViewMeta`, `ViewReader`/`ViewWriter`/`ViewReadProxy`, `GenLevel`, `TypeUtils`, `meta/TypeDescriptor` | `FieldSource` runtime exposure; `ViewWriter` has no `get(String)`/`supports(String)` |
| `hipster-entity-core` | solid, one blocking bug | `EntityReadArray`, `EntityUpdateArray`, `EntityUpdateTrackingArray{64,Large}`, `ArrayBackedViewProxyFactory`, full `EEnumSet*` family, `EEnumSetBuilder{64,Large}`, `ViewChangeTracking` | Tracking array cannot be **constructed at all** (§ 2.4); no previous-value storage; no tests for the tracking array |
| `hipster-entity-jackson` | partial | `EntityJacksonViewSerializer` (metadata-driven, writes a `ViewReader`), `EntityJacksonViewDeserializer` (zero-alloc parse loop, benchmarked), `EntityJacksonViewModule`, `EntityJacksonMapper` | `EntityJacksonViewJsonSerializer`/`JsonDeserializer` are thin wrappers; **no patch/change-set serializer exists**; no tests inside the module |
| `hipster-entity-tooling` | partial | `EntityMetadataGenerator` (JavaParser scan → `*.metadata.json` + one `_` enum per view), `FieldBoilerplateGenerator`, meta model, five validation rules | `GenLevel` is **never parsed**; only `META` is emitted; `default` methods leak into field enums; obsolete `read`/`write` handling; validator inert (§ 2.5) |
| `hipster-entity-test` | minimal | `PersonEntity`, `PersonSummary`, and the **only correct hand-written `FieldDef` + `META` enum in the repo** | No proxy, tracking, or builder integration tests |
| `hipster-entity-example` | demo only | Hand-written `Person*` + `PaymentMethod*` views, records, `Write` interfaces, builders, tracking builder, all `_` enums | All of it is hand-written; the `_` enums are **broken** (§ 2.4); no runnable demo |

**[verified]** The six modules are listed in the root `pom.xml` at lines 56–61, alongside
`project-automation` (62) and the `metadata-*` modules (66–68). Root
`maven.compiler.release` is `25` (line 19).

### 2.2 Build reality — settled in Phase 0

`plan.copilot` § 1 asserts all six modules compile and pass tests. `plan.dsflash` § 2.2
asserts the opposite for the *reactor*: a POM-validation failure for
`hr.hrg.jcodebuddy:metadata-server` / `metadata-mcp-server`, and a wrong default JDK.
`plan.kilo` asserts nothing about the build.

**[verified]** `metadata-server` and `metadata-mcp-server` appear in `<modules>` and in the
reactor's inter-module `<dependencyManagement>` block for every other module, but **no
`<dependencyManagement>` entry exists for either** (root `pom.xml` lines 218–311 manage
`watch`, `java-watch-*`, `jwa-*`, `hipster-entity-*`, `project-automation`, `hipster-ioc-*`,
`fory-core`, `mcp`). The dsflash diagnosis is therefore the credible one for the *reactor*:
per-module builds can succeed while a root-level `mvn -pl …` invocation fails POM validation
before it compiles anything. **[unverified]** The exact `JAVA_HOME` on this machine, and
whether the per-module test counts (core 42, tooling 22, test 4) still hold.

**Resolution:** Phase 0 settles this empirically and records the baseline. Do not pick a side
in prose; run one command and write the number down.

### 2.3 What is genuinely not wired

- **[verified]** `hipster-entity-tooling` never reads `GenLevel`. `parseViewAnnotation`
  (`EntityMetadataGenerator.java:438-467`) reads `read`/`write` attributes that the real
  annotation does not have, and returns `new ViewAttributes(Boolean read, Boolean write)`
  (`meta/ViewAttributes.java`, 4 lines). The real `@View` is
  `{GenLevel gen(); String discriminatorField(); Class<?>[] addons();}`
  (`hipster-entity-api/.../View.java`). It follows that `@View(gen = GenLevel.BUILDER_ALL)`
  produces byte-identical output to a bare `@View`.
- **[verified]** `default` methods leak into field enums. The property filter
  (`EntityMetadataGenerator.java:293-296`) checks only "zero parameters, non-void" — there is
  no `!method.isDefault()`.
- **[verified]** The validator rule is dead and wrong.
  `validation/ViewAnnotationRule.java:22-25` inspects `viewAnn.toString()` and reports an
  issue unless the source text contains the substring `"read"` or `"write"`. No such
  attributes exist, so the rule rejects every valid `@View(gen = …)`. It is referenced only
  from tests, which is why nobody noticed.
- **[verified]** `changes()` has no notion of a previous value. `EEnumSetBuilder.addOrdinalChange`
  (`EEnumSetBuilder.java:34-40`) is a `default` interface method that compares, sets the bit,
  and returns — it records nothing. `ViewChangeTracking` (`core/ViewChangeTracking.java`) is
  literally two methods in six lines.

### 2.4 The two artifacts that prove the rest is not wired

**A. The tracking array cannot be constructed. [verified]**

```java
// hipster-entity-core/.../EntityUpdateTrackingArray64.java:16-19
EntityUpdateTrackingArray64(ForNameOrdinal forNameOrdinal, int fieldCount, Object[] values) {
    super(forNameOrdinal, fieldCount, values);
    this.changes = new EEnumSetBuilder64<>(null); // Replace null with appropriate field type if needed
}
```

`EEnumSetBuilder64(Class<E>)` (`EEnumSetBuilder64.java:13-19`) immediately dereferences the
argument via `enumClass.getEnumConstants()`. Every construction of the proxy-backed tracking
path throws `NullPointerException`. The same defect is in `EntityUpdateTrackingArrayLarge`.
The root cause is that the factory signature
`create(ForNameOrdinal, int fieldCount, Object... values)` has no access to the field enum —
so the fix has to change the signature, not just the argument (§ 3.3).

**[verified]** No test covers this: `hipster-entity-core/src/test/` contains five `EEnumSet*`
tests and three JMH benchmarks, and **no `EntityUpdateTrackingArray*` test at all**. The
"42 tests pass" claim is consistent with a completely untested tracking array.

**B. The example's field enums are unusable. [verified]**

`hipster-entity-example/.../person/entity/PersonSummary_.java` (46 lines) does **not**
`implements FieldDef`; it exposes `getPropertyType()` where `FieldDef` requires
`javaType()`; it has no `NAME_MAPPER` and no `META`; and `toBuilder` /
`toBuilderTracking` leaked in as enum constants and `forName` cases because of the `default`
-method bug in § 2.3. It cannot be handed to `DefaultViewMeta`, `EntityReadArray`, or the
Jackson deserializer. It compiled once and was never exercised.

The correct reference shape is
`hipster-entity-test/src/main/java/hr/hrg/hipster/entity/person/PersonSummary_.java`
(69 lines): `implements FieldDef`, `javaType()`, `forName` switch, `NAME_MAPPER`,
and `META = new DefaultViewMeta<>(..., values -> ArrayBackedViewProxyFactory.createRead(...))`.
**That file is the golden output for `GenLevel.META`.**

### 2.5 Runtime facts the implementation must not regress

**[verified]** in `EntityUpdateTrackingArray` (`EntityUpdateTrackingArray.java`):
arity mismatch throws `IllegalArgumentException` (24-26); `set(int,Object)` bounds-checks
(60-62), throws `UnsupportedOperationException` for ordinal 0 (63-65), and short-circuits on
`Objects.equals` **before** marking (67-73); `set(String,Object)` returns `-1` for an unknown
name (77-84). The equality-before-mark ordering is the DEC-012 no-op rule and must be pinned
by a test.

**[verified]** in the hand-written example
`hipster-entity-example/.../PersonSummaryBuilderTracking.java`: the setter ordering is
`mf.addOrdinalChange(ordinal, old, new)` **then** `field = value` (lines 79-84) — correct, and
the generator must emit exactly this order. `changes()` returns the live mutable
`EEnumSetBuilder64` (line 77), and `set(String,Object)` returns `-1` on an unknown field
(line 110) — the same contract as the array path.

---

## 3. Definition of "first usable implementation"

Merging the four DoD sections (dsflash § 1.2 is the most testable, kilo § 10 the most
complete). The release is usable when **all** of the following hold:

1. **One command is green.** `mvn -Phipster-entity -o test` (or the recorded equivalent) is
   green for the six modules from the repo root, on the JDK the root POM requires.
2. **The generator is level-aware.** For a fixture view, `@View(gen = X)` emits a different,
   correct artifact set for each of `META`, `RECORD`, `WRITABLE`, `BUILDER`,
   `BUILDER_TRACKED`, `BUILDER_ALL` — and the emitted source **compiles** in a test.
3. **`META` output matches the reference shape:** `implements FieldDef`, `javaType()`,
   `forName`, `NAME_MAPPER`, `META`. `no default method leaks in as a field`.
4. **Tracking is correct through both paths.** Generated tracking builder and array-backed
   proxy produce identical `isChanged()` / `changes()` / previous-value / diff results for the
   same mutation sequence, including the DEC-012 no-op rule and the immutable-id rule.
5. **A change set is serializable.** `hipster-entity-jackson` can write only the changed
   fields (and, optionally, previous/current pairs) from a `ViewChangeTracking` source.
6. **Regeneration is safe and ordinals are permanent.** Running the generator twice is
   byte-identical; hand-edited recognized blocks survive; divergence is reported in the DEC-022
   format; generated files carry the DEC-021 two-line header. **Field enums are append-only
   (R1, § 4.6):** new fields append after existing constants, a reorder fails the
   `EntityFieldEnumOrderRule` check, and a removed accessor's constant is retained and
   `@Deprecated` rather than deleted.
7. **The example is generated, not hand-written**, and a runnable `PersonDemo.main` prints a
   one-field diff. Any remaining diff between generator output and the old hand-written files
   is documented as intentional. The `paymentMethod` sealed hierarchy is regenerated too, so
   `discriminatorField`/permitted-subtype support is exercised by generated code.
8. **Deep change tracking works.** A change N levels down inside a nested tracked view, and
   inside a `List<Tracked>`, is reportable, and a deep change set serializes to a nested
   patch document. Shallow tracking is unchanged and still free when nothing is nested
   (§ 11/6.3, the DEC-014 "pay only for what you use" discipline).
9. **Generated adapters exist.** For a view, the generator emits a positional-array reader, a
   positional binder, and a statically-dispatched mapper to a compatible view; divergent types
   are reported rather than silently mis-mapped (Phase 7.1/7.2).
10. **Validation is generatable.** Constraints carried on view fields become generated
    `Record`/`Builder` validation, or an explicit diagnostic when the constraint cannot be
    expressed (Phase 7.3).
11. **Compaction is available as a deliberate, gated operation** that drops tombstones and
    renumbers ordinals, refusing to run unless explicitly invoked (Phase 7.4).
12. **Docs state reality.** No doc claims a materialization level that does not exist.

**Explicitly out of scope for this release** — exactly one item:
**S3/X1, the `api`/`core` module-layering refactor** (§ 4.1 S3, § 4.3 X1). Everything else the
four input plans proposed **is in scope**, including the five items that were briefly deferred
during finalization and have since been pulled back in:

| Formerly deferred | Now |
|---|---|
| Nested/deep change tracking (copilot § 4) | **Phase 6** |
| SQL/JDBC adapter generation (kilo P-3) | **Phase 7.1** |
| Mapper generation between views (kilo P-1) | **Phase 7.2** |
| Bean Validation generation (kilo P-6) | **Phase 7.3** |
| Field-enum compaction (R1.4) | **Phase 7.4** |
| `project-automation` entity watcher | **Phase 7.5** (thin; depends on Phase 0.2's outcome) |
| `paymentMethod` sealed-hierarchy regeneration | **Phase 4.9** |

**Binding constraints already settled by review** (§ 4.1): `DERIVED`/`JOINED` fields are not
writable (S1), generated source is committed into `src/main/java` (S2), JSON skips absent fields
on write while tolerating `null` on read (S4), and change-set access is split into `changes()`
(immutable) plus `changesBuilder()` (live, mutable) with no deprecated aliases (S5). S3 (moving
the tracking contract to `hipster-entity-api`) is **deferred** — the contract stays in
`hipster-entity-core` for this release; a generated tracking builder therefore depends on
`hipster-entity-core` rather than on `hipster-entity-api` alone. The remaining original
questions (D2—D5, D7) and four execution resolutions (X1—X4) are also finalized — see § 4.2 and
§ 4.3. **Nothing in this plan is blocked on an unanswered question.**

---

## 4. Decisions — all finalized

**No open questions remain. This section is executable as written.**

Nine decisions (S1, S2, S4, S5, D2—D5, D7, plus four execution resolutions X1—X4 in § 4.3) are
settled and binding. The table records all seven original questions for audit; every row now
carries a final rule. § 4.1 holds the full rationale for the largest, § 4.2 for the remaining
five, and § 4.3 for the decisions the plans never asked but execution requires.

| # | Question | Final rule |
|---|---|---|
| D1 | `changes()` mutable or immutable? | **S5** (§ 4.1). Both, under names that state the variant: `changes()` = immutable snapshot, `changesBuilder()` = live mutable builder. One shared state. No aliases. |
| D2 | Must `previousValue(ord)` exist? | **Yes** (§ 4.2). New `ChangeRecorder` in `hipster-entity-core`, where the contract already lives (S3 deferred). Previous values are **shallow references** — documented and pinned by test. |
| D3 | Single level, or recursion into nested views? | **Both, sequenced** (§ 4.2). Single-level tracking lands in Phases 1–3; nested/deep tracking is **in scope as Phase 6** (§ 11), gated by its own DEC (task 6.1) — the DEC gates the implementation, not the release. |
| D4 | Push or pull for nested propagation? | **Pull** (`changesDeep()` walks children) (§ 4.2). **Implemented in Phase 6** (§ 11); no impact on Phases 1–3. |
| D5 | `set(String,Object)` on an unknown field: `-1` or throw? | **Keep the split** (§ 4.2): array and generated builder return `-1`, proxy throws `IllegalArgumentException`. Documented in the user guide. |
| D6 | Which fields get setters? | **S1** (§ 4.1): only `@FieldSource(kind = COLUMN)`. |
| D7 | Is declaration-order ordinal stability frozen? | **Yes** (§ 4.2) — and concretely **append-only** under **R1** (§ 4.6): existing constants keep their order, new fields are appended, reordering is a hard error. Contract doc in § 7.1, construction-time check in `DefaultViewMeta`, `allFields` JSON as the migration record. |

### 4.1 Settled by review (not open)

Four decisions were ratified during review. They are binding on Phases 1–5. The finalized
resolutions of the original D2—D5/D7 questions are in § 4.2, and § 4.3 records four execution
decisions the input plans never raised.

**S1 — `DERIVED`/`JOINED` fields are not writable (resolves D6).**
Emit setters only for `@FieldSource(kind = COLUMN)` fields.

Consequence, and it is a deliberate one: the hand-written example contradicts this today.
`hipster-entity-example/.../PersonSummary.java` marks `age()` as
`@FieldSource(kind = FieldKind.DERIVED)` and `departmentName()` as
`@FieldSource(kind = FieldKind.JOINED)` **[verified, lines 16-20]**, yet its `Write` interface
(line 35-42) and `PersonSummaryBuilderTracking` (lines 82-84, 94-95, 106-107) both declare
setters for them. Under S1 the regenerated `Write` interface drops `age` and `departmentName`,
and the regenerated tracking builder drops their setters and their `set(int)`/`set(String)`
arms. The fields remain present in the field enum, the record, the builder's read side, and the
positional array — only the *write* surface shrinks. § 8.5/3.12 and § 8.6/3.15 must implement
this, and § 9/4.1 (example regeneration) must show it in the diff.

**S2 — Generated source is committed.** Required by `AGENTS.md` § 1 / DEC-019: the committed
source is the source of truth. The example module becomes the regression artifact, and any
generator change shows up as a reviewable diff. Generate into `src/main/java`, not
`target/generated-sources`. Confirmed feasible: the working tree is clean on `main` with only
the untracked `plans__*` directories, so the regeneration diff will be isolated.

**S3 — DEFERRED: the tracking contract stays in `hipster-entity-core`.**

The original intent was to move `ViewChangeTracking` and `FieldChange` to
`hr.hrg.hipster.entity.api` so that a generated tracking builder in a consuming project could
implement them with an api-only dependency. **That move is deliberately not part of this
release.** The contract stays in `hipster-entity-core`, and we accept the consequence:

> A consuming project whose generated code must implement `ViewChangeTracking` depends on
> `hipster-entity-core`, not on `hipster-entity-api` alone.

- **Why defer:** as § 4.3/X1 records, the contract's accessors are typed on
  `EEnumSetRead`/`EEnumSetBuilder`, whose concrete implementations — and whose static factories —
  live in `core`. Making the move real therefore requires relocating the entire `EEnumSet*`
  interface family plus ~196 references, which is a module-layering refactor, not a contract
  change. The core/api split can be revisited later on its own terms.
- **What this buys:** Phase 1 loses its largest item, the interface-bound ripple disappears, and
  one whole refactor commit is removed from the critical path.
- **What it costs:** generated code imports `hr.hrg.hipster.entity.core.EEnumSetBuilder64` and
  `...core.ViewChangeTracking` (or the generated file declares those imports and the consuming
  POM adds the `hipster-entity-core` dependency). This is already what the hand-written example
  does **[verified, `PersonSummaryBuilderTracking.java:6-7`]**, so nothing about the generated
  output shape changes — only which module a consumer must depend on.
- **No code change in this release:** `ViewChangeTracking` stays exactly where it is
  (`hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/ViewChangeTracking.java`,
  6 lines **[verified]**); § 6.2/1.7 edits it in place.
- **Backlog:** recorded in § 4.4 as a layering refactor. It is a prerequisite for *nothing* in
  this plan; it becomes interesting only if/when a consumer genuinely cannot take a `core`
  dependency.
- **Documentation obligation:** the getting-started guide (§ 9/4.6) must state the required
  dependency set explicitly, so this is a documented choice rather than a surprise.

**S4 — JSON field presence: skip absent on write, tolerate null on read.**
A field absent from a payload is simply not written back out, and `create(Object[])` must
accept `null` for it rather than throw — in particular for `DERIVED`/`JOINED` fields, which
never arrive over the wire. This settles § 8.4/3.11 in favour of "accept null", and it means no
"missing required field" rejection is added in this release. It does **not** permit dropping
nulls from a *changed* field's patch: S4 is about presence in a full payload, and per S1 a
derived field is not writable so it cannot appear as a change either.

**S5 — Change-set access is two explicitly named methods, one per variant (resolves D1).**

The requirement is consistency of the *final* result, and the project has no consumers yet, so
there is no compatibility obligation to preserve and no reason to accept an ambiguous method
name. `ViewChangeTracking` therefore exposes one piece of shared state through two accessors
whose names state which variant they return. It stays in `hipster-entity-core` (S3 deferred), so
the declaration below only **adds** to the existing 6-line interface — the generic parameters and
their bounds are unchanged, which is why no implementation needs touching for this decision:

```java
package hr.hrg.hipster.entity.core;   // unchanged — S3 deferred

public interface ViewChangeTracking<E extends Enum<E>, S extends EEnumSetRead<E>> {

    boolean isChanged();

    /** Immutable snapshot of the changed fields — safe to retain and iterate. */
    S changes();

    /** The live, mutable change set — a view over the same state, not a copy. */
    EEnumSetBuilder<E> changesBuilder();

    /** Reset the change set (not the value baseline — see D2). */
    void clearChanges();
}
```

Note the consequence of S3 being deferred: the bound stays `E extends Enum<E>`, **not**
`Enum<E> & FieldDef`. The looser bound is now intentional rather than accidental — tightening it
would ripple into `EEnumSetBuilder64`'s own bound and every implementor for no benefit this
release. Field *names* for `diff()` come from `getEnumClass()`, which the interface already
exposes (see D2).

**One source of truth for both accessors.** `changes()` must be *derived from* the same
`EEnumSetBuilder` that `changesBuilder()` returns — never a second parallel structure — so the
two can never disagree. In the generated builder the implementing class *is* the tracking
state holder, so:

```java
@Override public EEnumSetBuilder64<PersonSummary_> changesBuilder() { return mf; }
@Override public EEnumSet64<PersonSummary_>        changes()       { return mf.toImmutable(); }
```

Only `changesBuilder()` can mutate; `changes()` is a snapshot taken at call time, so
`changesBuilder().clear()` followed by `changes().isEmpty()` is `false`→`true`, and
`changes()` captured *before* a mutation still reflects the old state.

**One piece of state, two materializations.** The proxy path already routes
`changes`→`EntityUpdateTrackingArray.changesSnapshot()` (immutable) and
`clearChanges`→`clear()` **[verified, `ArrayBackedViewProxyFactory.java:117-123`]**, so it
needs only the new arm:

```java
if (name.equals("changes"))        return updateArray.changes();        // immutable snapshot
if (name.equals("changesBuilder")) return updateArray.changesBuilder(); // same `mf`, live
if (name.equals("clearChanges"))   return /* updateArray.clear() */;
```

Because the handler returns the array's **own** builder rather than a copy, the proxy satisfies
the state-sharing requirement without a wrapper — and **no visibility change is needed**:
`EntityUpdateTrackingArray.getChanges()` is already `public abstract`
**[verified, `EntityUpdateTrackingArray.java:51`]** and simply keeps that modifier when it is
renamed to `changesBuilder()`. § 6.4 steps 8–12 must *prove* the sharing rather than assume it.

**Naming cleanup, safe because nothing consumes this yet.** The library already has the
vocabulary this needs — immutable is the unqualified noun, mutable is suffixed — so align the
underlying primitives rather than introducing a second convention:

| Today | After S5 | Why |
|---|---|---|
| `EntityUpdateTrackingArray.changesSnapshot()` | `changes()` | both it and the fixed `changes()` are immutable; "Snapshot" added nothing |
| `EntityUpdateTrackingArray.getChanges()` | `changesBuilder()` | matches the contract; "get" is noise |
| `EntityUpdateTrackingArray64.getChanges64()` | **delete** | pre-existing duplication of the line above; it is the same reference |
| `EEnumSetBuilder.toImmutable()` | keep | established `EnumSet`-family vocabulary; renaming it would churn every use for no gain |

Do **not** keep deprecated overloads or aliases for any of these. The plan's usual rule is to
keep old signatures as delegating overloads (see § 6.2/1.4 for the `EEnumSetBuilder`
constructors, where the JMH harness genuinely calls them) — that rule does **not** apply here,
because the only in-repo callers are the tracking arrays, the proxy handler, and the one
hand-written example, all of which this plan already rewrites.

**The test that makes this binding.** Consistency of the final result is only real if it is
asserted, so § 6.4 adds a state-sharing test that must hold identically for
`PersonSummaryBuilderTracking` and for the array-backed proxy:

1. `changes().isEmpty()` and `!isChanged()` on a fresh tracking view;
2. after one real change: `changesBuilder().has(ord)` is `true` **and** `changes().has(ord)`
   is `true` — the two accessors agree;
3. `changesBuilder().removeOrdinal(ord)` (or `.clear()`) then makes `changes().has(ord)`
   `false` **and** `isChanged()` `false` — mutation through the builder is visible through the
   snapshot, proving they share state rather than being two copies;
4. a snapshot captured before step 3 still reports the old state — proving `changes()` is a
   snapshot, not a live alias;
5. `clearChanges()` empties both accessors;
6. steps 2–5 repeated against the proxy built over `EntityUpdateTrackingArray` with identical
   assertions.

Step 3 is the one that catches the mistake this decision exists to prevent: an implementation
that returns a *copy* from `changesBuilder()` would pass steps 1–2 and fail step 3.

### 4.2 Final resolutions — D2, D3, D4, D5, D7

These are **decided**, not proposed. Each item states the final rule and the edits it implies.

**D2 — Previous values: YES, via `ChangeRecorder`, shallow semantics.**

- **Rule:** `ViewChangeTracking` gains `previousValue(int)`, `hasPreviousValue(int)`, and
  `default List<FieldChange<E>> diff()`, backed by a new `ChangeRecorder` in
  `hipster-entity-core` — the same module the interface lives in, since S3/X1 are deferred.
  Previous values are **shallow references**.
- **Why:** without it there is no `diff()`, no audit trail, and the JSON patch cannot emit
  `{"previous": …, "current": …}` — which kilo T1.1 and dsflash § 4.3 both assume.
- **Edits (binding):**
  - new `ChangeRecorder` in `core`: `boolean record(int ordinal, Object previous, Object next)`
    (returns `false` when `Objects.equals`), `Object previous(int)`, `boolean hasPrevious(int)`,
    `void clear()`. Storage: a `long[]` ordinal-presence bitset plus an `Object[] previous`
    (or a segmented/`Map` form above 64 ordinals) — deliberately **not** an `EEnumSet*` type.
  - move `addOrdinalChange`'s body out of the `default` interface method
    (`EEnumSetBuilder.java:34-40`) into `EEnumSetBuilder64` and `EEnumSetBuilderLarge`, which
    hold the recorder as a field. Keep the method in the interface as **abstract** so the
    contract is unchanged for callers.
  - **`diff()` needs field names, so it needs `getEnumClass()`** — which `EEnumSetRead` already
    exposes **[verified, `EEnumSetRead.java:51`]**. Implementation: iterate `forEach((value, ord)
    -> …)`, resolve the name via `getEnumClass().getEnumConstants()[ord].name()`, and emit
    `FieldChange`. No new state, no reflection-per-call beyond the cached constants array.
  - related: § 6.2/1.5's new `E[] universe` constructor must keep a usable `enumClass` on the
    concrete builders. If the universe is still derived from a `Class`, store it; the fix for the
    § 2.4 NPE is specifically that the **enum class must reach the constructor** instead of
    `null`, so `getEnumClass()` is never `null` on a constructed tracking builder.
  - preserve the verified DEC-012 order exactly: **compare → record → set bit**.
  - `EntityUpdateTrackingArray.clear()` / `clearChanges()` also clear the recorder.
  - `ArrayBackedViewProxyFactory`'s handler forwards `previousValue`/`hasPreviousValue`/`diff`.
  - amend `DEC-012` with the shallow-reference semantics (it already states the no-op rule and
    the explicit-null rule; only the reference-capture consequence is new).
- **Cost accepted:** one small holder allocated **per tracking state, not per write**, and only
  once a real change occurs (the DEC-012 equality guard runs first). The JMH
  `previousValue` on/off axis (§ 6.4) measures it rather than assuming.
- **Hazard, pinned by test:** mutating a `Map`/`List` field in place instead of replacing it
  leaves `previousValue()` comparing equal to current while `changes()` still marks the field —
  so `diff()` can be empty while `changes()` is not. Test both halves and document it.

**D3 — Both levels are in scope: single-level first (Phases 1–3), nested/deep second (Phase 6).**

- **Rule:** land one level of tracking first, because Phase 6's nested index is built *on top of*
  the final S5 contract and on top of a tracking implementation that actually constructs (§ 2.4).
  Phase 6 then implements nested/deep tracking in full: `ChangePath`, `changesDeep()`, the
  nested-change index, collections of tracked views, generator wiring, and the deep patch output.
  It is **in scope for this plan** — it is simply sequenced after the single-level work, never
  deferred out of it.
- **Wording discipline:** use **"call-chain trace"** for what dsflash § 7 describes (tracing one
  level from accessor to change bit to snapshot) and **"nested/deep tracking"** for what
  copilot § 4 describes. The two are different features and the phrase "full depth" must not be
  used for both.
- **Sequencing and edits:** Phase 6 only — **§ 11 is the authoritative task list** (`ChangePath`,
  `changesDeep()`, the nested-change index, collection add/remove/reorder semantics, generator
  detection of trackable field types, and the RFC 6902-like patch output); the DEC is task 6.1 and
  must be accepted before 6.2 starts. **No edit to Phases 1–3.** There is no appendix to this plan:
  every Phase 6 task is enumerated in § 11, and DoD #8 is part of this release's definition of done.
- **Why it is sequenced last among the tracking work:** it adds a DEC, a new value type, a nested
  index in `core`, collection delta semantics, and generator type-detection — on top of the very
  interfaces S5 fixes. Building it first would mean building it twice.

**D4 — Pull, not push.**

- **Rule:** propagation is **pull**: `changesDeep()` walks into every field whose declared type is
  itself `ViewChangeTracking` and ORs the child's state into the reported result, without mutating
  the parent's own bitset. A distinct accessor pair — shallow `changes()`/`changesBuilder()` and
  deep `changesDeep()` — is exposed so both views are explicit.
- **Why:** children stay reusable and shareable across parents; no parent/child lifecycle
  coupling; and the walk is a concrete, IDE-navigable method body, which is what DEC-019
  requires when a framework would otherwise hide control flow.
- **Rejected alternative:** push (child holds a back-reference to parent + own ordinal and calls
  `parent.mark(ordinal)` on every change). Zero-cost when untouched, but a nested builder can no
  longer be shared or reused, and propagation becomes invisible control flow.
- **Edits:** implemented in **Phase 6** (§ 11). The DEC (task 6.1) records this choice **and**
  includes a push-vs-pull JMH comparison on both "no nested change" and "deep nested change"
  before the decision is treated as locked (DEC-014 precedent) — the benchmark validates the
  chosen model rather than reopening the choice, and would only reopen it if pull turned out
  materially worse on the "no nested change" case, where it should be free.

**D5 — Keep the `-1`/throw split.**

- **Rule, verified as the current behaviour:** the low-level `EntityUpdateTrackingArray.set(String,Object)`
  returns `-1` for an unknown name (`EntityUpdateTrackingArray.java:77-84`); the generated
  builder returns `-1` (`PersonSummaryBuilderTracking.java:110`); the proxy handler converts `-1`
  into `IllegalArgumentException` (`ArrayBackedViewProxyFactory.java:150-152`).
- **Why:** the array and the generated builder are low-level primitives that must be able to
  *probe* a field name, so they report positionally; the proxy is the user-facing facade and
  validates, so a caller typo surfaces loudly.
- **Edits:** no code change. One paragraph in the user guide (§ 9/4.6) stating which layer does
  what, plus a test that pins both behaviours so a future "consistency" cleanup cannot silently
  pick one.
- **Rejected alternative:** make both uniform. Either the array starts throwing (losing the
  probe) or the proxy stops throwing (turning a typo into a silent no-op).

**D7 — Ordinal order is a frozen, append-only contract, enforced at construction.**

- **Rule:** the `FieldDef` enum declaration order, after merging inherited interfaces with `id`
  fixed at ordinal 0, is the persisted array layout. It backs `EEnumSetBuilder*` bit positions,
  the positional `Object[]`, and the JSON/JDBC field names. **It is append-only per R1 (§ 4.6):**
  existing constants keep their position, new fields are appended after them, and neither
  reordering nor deletion is permitted.
- **Edits (binding):**
  - the written contract in § 7.1 (`values[f.ordinal()]` for every `f`, `values.length ==
    fieldValues().length`, ordinal 0 = identity and immutable in tracking arrays, DERIVED/JOINED
    still occupy their ordinal and may be null);
  - a **construction-time check in `DefaultViewMeta`'s constructor** (`DefaultViewMeta.java:44-53`),
    which already dereferences `fieldType.getEnumConstants()` and so is the natural place: assert
    `fieldValues[i].ordinal() == i` and that the array is non-empty, failing fast with a
    diagnostic naming the enum and the offending constant;
  - `allFields` in the emitted `<Entity>.metadata.json` is the migration record — a reorder shows
    up there as a diff, and it is the second, runtime-independent witness alongside the R1 checker;
  - a test that a `FieldDef` enum whose declaration order was changed fails the check rather than
    silently mis-marking fields;
  - **the R1 enforcement tasks** (§4.6/R1.3): the marker in the generator output and the
    `EntityFieldEnumOrderRule` checker — see § 6.3/1.14–1.16 and § 8.3/3.7a.
- **Why it matters more than it looks:** `EEnumSetBuilder64.addOrdinal` rejects out-of-range
  ordinals by returning `false` with no error **[verified, `EEnumSetBuilder64.java:223-230`]**, so
  the failure mode today is *silently missing changes*, not an exception.

### 4.3 Execution resolutions — decisions the input plans never raised

Four things surfaced during finalization that any implementation would otherwise have to guess.

**X1 — SUPERSEDED / DEFERRED: no `EEnumSet*` interface move in this release.**

This item previously said to move `EEnumSetRead`, `EEnumSetBuilder`, and `EEnumSet` to
`hr.hrg.hipster.entity.api`, plus a new `EEnumSets` factory holder in `core`, as the enabling
work for S3. **Both S3 and this move are deferred** — the refactor of the core/api split can be
done later, on its own terms, when the layering is the actual problem being solved.

- **Rule:** `ViewChangeTracking` stays in `hipster-entity-core` **(S3)**; `EEnumSetRead`,
  `EEnumSetBuilder`, `EEnumSet`, and all concrete `EEnumSet*` types stay exactly where they are.
  `EEnumSetBuilder.create(...)`/`of(...)` stay on the interface. **No module move, no import
  churn, no factory relocation.** Phase 1 has no item 1.0.
- **Accepted consequence:** generated tracking builders import from `hipster-entity-core`, so a
  consuming project must depend on `core` (which transitively brings `api`). The hand-written
  example already does this **[verified, `PersonSummaryBuilderTracking.java:6-7`]**, so the
  generated output shape is unchanged.
- **Also retires C7:** the `EEnumSetRead` bound stays `Enum<E>` rather than `Enum<E> & FieldDef`.
  No bound tightening, so there is no ripple into `EEnumSetBuilder64`, the JMH harness, or any
  implementor. What was a "risky isolated commit" simply does not happen.
- **When it becomes worth doing** (recorded in § 4.4, none of it blocking): if a consumer cannot
  take a `core` dependency, or if the `api`/`core` layering starts causing real friction. Doing
  it then is a mechanical, behaviour-free refactor with ~196 references to fix.

**X2 — `FieldDef` gains the `@FieldSource` accessors that D6 depends on.**

D6 says the generator emits setters only for `COLUMN` fields, so writability must be readable.
`FieldDef` today declares only `javaType()`, `name()`, `ordinal()` **[verified]**.

- **Rule:** add to `FieldDef` — `default FieldKind fieldKind() { return FieldKind.COLUMN; }`,
  `default String column() { return null; }`, `default String relation() { return null; }`,
  `default String expression() { return null; }`. Defaults keep every existing hand-written and
  generated enum compiling, and a field with no annotation is `COLUMN` — i.e. writable, which
  preserves today's behaviour for unannotated views.
- **Generator obligation:** emit overrides for the four accessors only when the source field
  actually carries `@FieldSource`, so the generated enum stays small.
- Note the name collision to avoid: `FieldKind.COLUMN` must not be confused with the
  `@FieldSource.column()` *label*; the former decides writability, the latter names the SQL
  column.

**X3 — Scope of the example-module regeneration: the entity packages only.**

`hipster-entity-example` holds a split source tree. The package root is
**`hr.hrg.hipster.entityexample`** (**not** `...entity.example`); under it:
`person/entity/` (the real views), a second `person/iface/` + `person/record/` pair (documentation
samples), `paymentMethod/entity/` with a sealed hierarchy and four subclasses, and a small
`example/` package (`Auditable`, `PersonAuditable_`, `PaymentMethodAuditable_`).

- **Rule — generation scope:** the generator's source root for `hipster-entity-example` is
  **`person/entity/`** (task 4.1) plus **`paymentMethod/entity/`** (task 4.9). Task 4.1 regenerates
  the `person` views; task 4.9 then regenerates the **full `paymentMethod` sealed hierarchy**,
  because that is the acceptance test for `discriminatorField`/permitted subtypes (DoD #7, § 3).
  X3 does **not** defer it: the only thing narrowed here is that 4.1 and 4.9 are separate steps, in
  that order, with 4.1 proving the single-entity path first.
- **Rule — the two doc-sample packages are kept, not folded and not deleted.**
  `person/iface/Person.java` and `person/record/Person.java` are **live documentation sources**:
  `architecture/materialization-levels.md:33,44` include them via
  `<!-- INCLUDE:~iface/Person.java#DOCS -->` and `<!-- INCLUDE:~record/Person.java#DOCS -->`,
  extracted by `scripts/update-doc-includes.js`. They are **not** duplicates of `entity/Person`
  (different fields — `name`/`email` vs `firstName`/`lastName`), and deleting them breaks those
  includes. The decision is therefore: **keep both files exactly as they are, and keep them out of
  the generator's scan** — the `@View(gen = GenLevel.META)` on `person/iface/Person.java` is
  tutorial narrative for the "record → interface" walkthrough, and that interface is
  package-private, so it could not carry a public generated enum in any case. § 9/4.7 rewrites
  `materialization-levels.md` around the six `GenLevel` values and **must preserve both INCLUDE
  directives**, so the samples keep compiling as documentation.
  **Mechanism (do not leave this to the generator to guess):** the example module's generation
  config (§ 8.8/3.23) passes an explicit package filter — `person.entity` and
  `paymentMethod.entity` — as a `packages` knob, so the scan never visits the samples. Document
  that knob alongside `genLevels`/`cooperative`/`strict` in
  `hipster-entity-tooling/README.md` (§ 8.7/3.22).
- **Why:** the split-package layout would otherwise produce duplicate or conflicting generated
  files; stating the scope as "the entity packages only" is prerequisite work for Phase 4 that the
  plan must not leave implicit.

**X4 — Build integration: `exec-maven-plugin` in, the watcher moved to Phase 7.5.**

- **Rule:** § 8.8/3.23 (generation bound to `generate-sources`) is **in scope** — without it no
  real project can adopt the generator, which is the whole point of the release.
  The `project-automation` file watcher is **also in scope** now, as **Phase 7.5** (task 7.17): it is a
  developer convenience rather than part of the adoption path, so it lands after the generated
  adapters and validation work. It is the one task whose feasibility depends on Phase 0.2's
  outcome — if `project-automation` has to be removed from the reactor to unblock the build, the
  watcher is written as a standalone tool in the tooling module instead.

### 4.4 Out of scope (one item) and its consequences

Exactly one thing is **not** in this plan:

- **S3 / X1 — move the tracking contract and the `EEnumSet*` interface family to
  `hipster-entity-api`.** A pure module-layering refactor (~196 references), worth doing only if
  a consumer cannot take a `hipster-entity-core` dependency or the layering starts causing real
  friction. `ViewChangeTracking` currently lives in `hipster-entity-core`; `EEnumSetBuilder`'s
  static factories instantiate core classes, so the factory methods must move to a core
  `EEnumSets` holder as part of it.
- **Retire `C7` with it:** when the move happens, the `EEnumSetRead`/`ViewChangeTracking` bound
  can be tightened to `Enum<E> & FieldDef` in the same commit, since that commit already touches
  every implementor.

Everything else that was ever deferred is now planned: nested/deep tracking (**Phase 6**),
JDBC adapters (**7.1**), mappers (**7.2**), validation (**7.3**), compaction (**7.4**), the
watcher (**7.5**), and the `paymentMethod` regeneration (**4.9**).

### 4.5 Specification gaps closed — the last four items an implementer would otherwise guess

Everything else in this plan is a concrete edit. These four were left implicit; they are now
fixed so that no task requires an authoring decision mid-flight.

**G1 — `GenLevel.DEFAULT` resolves by interface shape, never upward.**

`GenLevel`'s own javadoc says `DEFAULT` is "determined by the code generator based on the
presence of other options and the type of view" **[verified, `GenLevel.java:4-7`]** — it does not
define the rule, so the generator must. The binding rule, in strict precedence order:

1. If the view declares a nested `record` whose component list matches the field enum order →
   `RECORD`.
2. Else if the view declares a nested `Write` interface → `BUILDER`.
3. Else → `META`.

Rationale: `DEFAULT` means "do not add anything the author did not ask for, but do not leave the
declared interface unimplemented". This is safe and testable, and it deliberately **never**
returns `BUILDER_TRACKED`/`BUILDER_ALL` — a view that wants tracking must say so, because
tracking changes the public surface (`changes()`/`changesBuilder()`). A source-level
`// @gen`-style override is not needed; the author writes the level explicitly.

Consequence to implement carefully: rule 1 requires comparing the nested record's component list
against the field enum order. If the two disagree (a stale record), emit the **DEC-022 divergence
diagnostic** and fall back to `META` rather than generating a `create()` that would not compile.
Add this to § 8.3/3.9's `GeneratedSourceCompilesTest` matrix as the ambiguity case.

**G2 — The generator does not author `toBuilder()`/`toBuilderTracking()`.**

The hand-written example declares both as `default` methods on the view interface
**[verified, `PersonSummary.java:44-45`]**:

```java
public default PersonSummaryBuilder toBuilder(){ return new PersonSummaryBuilder(this); }
public default PersonSummaryBuilderTracking toBuilderTracking(){ return new PersonSummaryBuilderTracking(this); }
```

- **Rule:** for `BUILDER`/`BUILDER_TRACKED`/`BUILDER_ALL`, the generator **emits these two
  methods** as part of the view's generated block, because they carry no per-field logic and
  their absence forces every consumer to write the same three lines.
- They are **shape-recognized** per DEC-020 like any other block: if the user has edited the
  body, keep it verbatim; if the user deleted the method, emit a fresh one. Recognition shape:
  a `default` method on the view interface returning the builder type whose body is a single
  `new <View>Builder(this)` (or `...BuilderTracking(this)`) return.
- They must **not** appear in the field enum — which is exactly the § 2.3 `default`-method leak
  bug (§ 8.2/3.5). The two changes are coupled: fix the filter and emit the methods deliberately.
- Name them from the view per the § 8.7/3.22 naming table: refactor-**sensitive** (`toBuilder` is
  a derived name, wired via `{@link}` to the builder type), unlike the string labels.

**G3 — `TrackingStrict` is preserved, not generated.**

The example carries a hand-written `TrackingStrict extends PersonSummaryBuilderTracking` that
re-injects `EEnumSetBuilder64.Strict` **[verified, `PersonSummaryBuilderTracking.java:55-59`]**.

- **Rule:** the generator **never emits** `TrackingStrict` — `@View` has no attribute to request
  it (`gen()`, `discriminatorField()`, `addons()` only **[verified, `View.java:13-16`]**), and
  inventing one is out of scope. It is treated as a **user-added nested class inside a generated
  file**: shape-recognize the enclosing builder and preserve the nested class verbatim.
- **Rationale documented in the generator README:** the plain tracking builder deliberately does
  not re-check equality because `addOrdinalChange` already does (compare → record → set bit), so
  `Strict` is redundant for correctness and exists only as a belt-and-braces variant. Stating
  that prevents a future "consistency" pass from deleting it.
- **Consequence:** the `TrackingStrict` class in the regenerated example survives regeneration.
  Add a regeneration test asserting exactly that (run the generator twice, assert the nested
  class is byte-identical) — it is the strongest available test of the shape-recognition path.

**G4 — Build integration uses `exec-maven-plugin`, not a new plugin module.**

§ 8.8/3.23 previously said "a Maven plugin (or an `exec-maven-plugin` binding)". **Decision:
`exec-maven-plugin`, bound to `generate-sources`**, invoking
`EntityMetadataGenerator`'s `main`/entry point with `sourceRoot` and `outputDir` arguments. This
matches the root POM's existing plugin management, where `exec-maven-plugin` is already managed
**[verified, root `pom.xml:342-343`]**.

- **Why not a `hipster-entity-maven-plugin` module:** a real Mojo module needs its own POM, a
  `maven-plugin-plugin` descriptor, plugin-prefix resolution, and root-POM registration — real
  cost for zero functional gain, since the generator is already a plain-Java entry point. Revisit
  only if a consumer needs incremental-build or dependency-graph awareness that `exec` cannot
  express.
- **Ordering trap to respect (new):** `exec-maven-plugin` invokes the *installed* tooling, but
  Phase 0.3 pins an **offline** profile (`mvn -o`). So the example module cannot run generation
  until `hipster-entity-tooling` has been `install`ed. Sequence it explicitly:
  `mvn -o -pl hipster-entity-tooling -am install` **before** the first `mvn -Phipster-entity -o
  test` that triggers generation, and add a note to the example module's README. If this proves
  brittle in practice, the fallback is a `maven-invoker-plugin`-driven verification or dropping
  the profile's `-o` for the generation step — decide empirically in Phase 4, not now.
- The remaining configuration knobs (`genLevels`, `cooperative`, `strict`) are passed as
  generator CLI/system properties, not Mojo parameters.

**G5 — Where the generated adapters, mappers and validators live, and what they are allowed to do.**

Phases 6–7 add generated code that is *not* a view materialization. Deciding this once prevents
each of them inventing its own convention.

| Concern | Rule |
|---|---|
| **Package** | Generated adapters/mappers/validators go in the **same package as the view**, named `<View>RowAdapter`, `<View>Mapper`, `<View>Validator` — the naming contract of § 8.7/3.22 extended, not a new scheme. |
| **Dispatch** | Direct method calls only (DEC-019). No `Map<String, Method>`, no reflection, no `META-INF/services`. A mapper is `public static <Target> map(<Source> src)` with a concrete body; an adapter is a concrete class with concrete methods. |
| **Ordinal access** | Everything positional goes through `ViewMeta`/`FieldDef` (`meta.fieldNameAt(i)`, `meta.fieldTypeAt(i)`, `meta.fieldCount()`); everything by name goes through `forName` (DEC-016 — never a per-call `HashMap`). |
| **Nullability** | A reader must tolerate `null` in any slot (S4), including a tombstone slot (R1.4) and a `DERIVED`/`JOINED` field an adapter cannot fill. A binder writes `null` as SQL `NULL`, not as a type error. |
| **Column names** | `@FieldSource.column()` when non-empty, else the accessor name — `FieldSource` already documents "Defaults to the method name when empty" **[verified, `FieldSource.java:21-22`]**. This resolution lives in X2's `FieldDef.column()` default, so adapter code never re-implements it. |
| **Writability** | Generated setters/binders exist only for `FieldKind.COLUMN` (S1). A `DERIVED`/`JOINED` field is read-only and an `UPDATE` must never include it. |
| **No new runtime module** | Adapters/mappers/validators are **generated source**, committed per S2. They may use `hipster-entity-core` for the positional-array primitives — the same dependency S3's deferral already accepts. A generated JDBC adapter uses the JDK's `java.sql` only; **no JDBC driver and no connection-pool dependency enters any library POM.** |
| **Failures are diagnostics, not silence** | Where a mapping or constraint cannot be expressed (divergent types, an unsupported constraint annotation), emit the DEC-022 diagnostic and generate nothing for that field — never a lossy cast and never a silently dropped field. |

### 4.6 R1 — Field enums are append-only ordinal ledgers (new binding rule)

This rule is **binding on every phase** and supersedes the weaker wording of D7 wherever the two
could be read as "stable unless you migrate". There is no in-place migration story: an ordinal,
once assigned, is permanent.

**R1.1 — The rule.**

> Once an entity's field enum has been generated, every subsequent generation step that finds new
> fields must **respect the order of the existing constants and append the new ones after them**.
> Existing constants are never reordered, never moved, and never inserted between.
>
> A general JavaParser-based checker (`EntityFieldEnumOrderRule`, §4.6/R1.3) can read a commit or
> a PR diff and **fails the build** if the enum order was shuffled. The checker is **opt-in** and
> applies only to enums that carry the **codebuddy marker** (§4.6/R1.2).

**R1.2 — The marker: opt-in, and it rides the existing DEC-021 header.**

The generator already emits a two-line `//` header above `package` (DEC-021, § 8.3/3.8). The
marker is an **additional key in that JSON5 config blob** — no new comment syntax, no
`// generator:begin`-style marker block:

```java
// {@link hr.hrg.hipster.entityexample.person.entity.PersonSummary} Field metadata for the PersonSummary view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
public enum PersonSummary_ implements FieldDef {
```

- `entityFieldEnum: true` is the **marker**. It is emitted by the generator on every field enum
  it produces, and it is what makes the enum subject to R1.
- **Opt-in is by absence:** an enum without the marker is ignored by the checker entirely. This
  covers hand-written enums, third-party enums, and the tooling's own internal property enums —
  the checker must not flag any of them.
- The marker must be **read from the parsed comment**, not by string-matching the file: parse the
  `CompilationUnit`, locate the header comment above the `package` declaration, and decode the
  JSON5 subset (the seven `JsonReadFeature`s of DEC-021 § 4). A malformed marker is a diagnostic,
  not a silent skip — silently skipping would defeat the rule.
- `allowReorder: true` is the **explicit escape hatch**, available only while an enum has no
  committed consumers (e.g. early development of an unreleased module). It must be set by hand,
  and every generation pass reports it as a divergence so it cannot be left in by accident.

**R1.3 — The checker.**

New, in `hipster-entity-tooling`: `EntityFieldEnumOrderRule implements EntityRule` plus a
reusable `EnumConstantOrderChecker` that does the parsing. Placing it under the existing
`validation` package is required — `EntityRulesValidator` already exists and is a registry of
`EntityRule` implementations **[verified]**, so the checker plugs into the same mechanism rather
than becoming a parallel tool.

| Concern | Rule |
|---|---|
| **Baseline source** | Primary: a **git ref** (`HEAD`, a commit, or a PR base like `origin/main`). Secondary: an explicit **unified diff** file. Never "remember the previous run" — the baseline must be reproducible from the repository. |
| **Comparison** | Parse both revisions with JavaParser; extract the marked enum's constant names in declaration order; assert the **old list is a subsequence of the new list, in the same order**. Do not compare raw text — comments and formatting must not affect the verdict. |
| **A reorder** | Hard error. Message names the enum FQN, the offending constant, its old index and its new index: `kind=enum_order_shuffled, location, cause, current, canonical, action` — the DEC-022 diagnostic format (§ 8.7/3.20). |
| **A removal** | Hard error by default: removing a constant shifts every following ordinal, which is exactly the corruption R1 exists to prevent. The fix is to keep the constant and mark it `@Deprecated` (see R1.4), not to delete it. |
| **An addition** | Allowed, and only at the end. An addition in the middle is reported as a reorder, because that is what it is from the bitmask's point of view. |
| **Exit code** | Non-zero on any violation, so it can gate a build and a PR check. Warnings (e.g. `allowReorder` present) go to stderr without failing. |
| **CLI** | At minimum `--repo <path> --baseline <ref> [--target <ref>] [--diff <file>] [--strict]`. Wire it into the `hipster-entity-tooling` entry point alongside `EntityMetadataGenerator.main` **[verified, `EntityMetadataGenerator.java:186`]**. |

**R1.4 — Removed fields: keep the ordinal as a tombstone.**

When a view loses an accessor, the generator **must not** delete the corresponding enum constant.
It keeps it, in place, as a **tombstone**, and emits a DEPRECATED divergence entry:

```java
/** @deprecated no longer an accessor on PersonSummary; retained to preserve ordinals. */
@Deprecated
middleName(String.class),
```

**A tombstone is reserved but dead, and the record keeps a nullable slot for it.** This is the one
design point where R1 meets the ordinal-array contract (§ 7.1), so it is stated explicitly rather
than left to the implementer:

- **Invariant preserved:** `values.length == fieldValues().length` still holds, and
  `values[f.ordinal()]` is still the value of `f`, for *every* `f` — including tombstones. The
  contract's central promise is not weakened for retired fields.
- **The record keeps a component for the tombstone** (a nullable field of the retired field's
  type), and it is `null` for rows that no longer carry the column. Consequence, stated plainly:
  **a tombstoned field stays in `Record` forever.** That is the deliberate price of never
  renumbering — there is no automatic compaction in this release.
- **The builder and `Write` interface do not** get a setter for it — tombstoning only ever applies
  to a field that was writable, and per S1 a field with no accessor is not writable.
- **Why the record keeps the slot rather than the simpler "drop the component" alternative:**
  dropping it would break `create()`'s verified 1:1 positional mapping
  (`new Record(values[0], values[1], …)`, § 8.4/3.10) and would make `create()` branch on
  deprecated constants. Reserving the slot keeps `create()` a straight positional mapping, keeps
  `fieldCount` stable, and keeps adapters working unchanged — the alternative trades a
  once-per-removal dead component for a permanent special case in the hottest construction path,
  which is the wrong trade.
- `forName("middleName")` still resolves, so an incoming payload that still carries the field is
  accepted rather than silently dropped; it binds to the tombstone constant.
- `META.create()` therefore accepts a non-null value at a tombstone ordinal without error (a row
  that still has the column), and `null` (a row that does not).
- Real removal requires `allowReorder: true` plus a data migration, and is out of scope.
- **Compaction** — dropping tombstones and renumbering — is a documented future migration, not a
  feature: it may only happen when no persisted array, patch, or snapshot survives, and it needs
  its own DEC. Record it in § 4.4.

Also record that tombstones are only reachable through the **enum-preserving regeneration path**
(§ 8.3/3.7a), which is the only place the generator sees an enum constant with no matching
accessor. A fresh generation of a brand-new view never produces one, so the tombstone logic is
independently testable without touching the general emission path.

**R1.5 — Why this is stricter than DEC-020, and how the two compose.**

These are two different guarantees and both must hold:

| | DEC-020 (cooperative blocks) | R1 (this rule) |
|---|---|---|
| Scope | any recognized generated block | the field enum only |
| Guarantee | *user edits survive regeneration* | *ordinals are permanent* |
| Recovery | user deletes the block → generator re-emits it | user may not reorder; additions append |

They do not conflict. DEC-020 governs **whether the generator may rewrite what a human wrote**;
R1 governs **where a new constant may be placed**. An implementer must not let DEC-020's
"recognize by shape, then re-emit canonically" logic reorder enum constants — the enum is the one
block whose *position* is semantic, and the generator's own comparison must therefore be
order-sensitive even though every other block's is not.

---

## 5. Phase 0 — unblock and measure (≈ ½ day)

**Goal:** make every later claim verifiable. Nothing else starts until this is green.

- [ ] **0.1 Pin the JDK to 25 — at the JVM that runs the forked tests, not just the Maven JVM.**
  The root POM requires `maven.compiler.release` = `25` **[verified]**, so **both** the compiler and
  the surefire fork must be JDK 25. **`.mvn/jvm.config` cannot do this** — it only passes JVM
  options to the Maven process and never selects which JDK launches it. The working mechanisms, in
  order of preference: (a) point `JAVA_HOME` at the JDK 25 installation (verified present at
  `C:\Program Files\Java\jdk-25`) from a committed run script — the repo has **no build run scripts
  today** (only the Bun helpers under `scripts/`), so create one, e.g. `scripts/mvn-jdk25.cmd` +
  `.sh`, and reference it from `README.md`; (b) a JDK-25 toolchain — `.mvn/toolchains.xml` wired via
  `-t .mvn/toolchains.xml` in `.mvn/maven.config`, plus a `maven-toolchains-plugin` binding in the
  root POM; (c) an explicit surefire `<jvm>` (absolute path to the JDK 25 `java`).
  **The failure mode is the fork, not Maven:** with the machine default `JAVA_HOME` = jdk-21 the
  six-module `test` run fails with `class file version 69.0 … only recognizes … up to 65.0` while
  compilation itself succeeds. Symptom palette: `release version 25 not supported` (compiler on the
  wrong JDK) and the class-file message above (fork on the wrong JDK).
- [ ] **0.1a Name the recorded Maven.** Two launchers are installed and they behave differently:
  `mvn` on `PATH` resolves to **mvnd 1.0.0-m4 / Maven 4.0.0-alpha-4** (daemon `java.home` pinned to
  jdk-25 by `~/.m2/mvnd.properties`), and an **Apache Maven 3.9.0** sits at
  `D:\programs\mvn\bin\mvn.cmd`. Record which one § 0.4's command uses. Two consequences:
  Phase 0.2's POM-validation failure **does not reproduce under Maven 4** (verified green), only
  under 3.9; and `mvnd -v` prints its version and then exits **non-zero**
  (`Environment mismatch … NoSuchFieldException: fs`), so a raw `$LASTEXITCODE` gate around mvnd
  reports failure on success — gate on the build outcome, not on the launcher's exit code.
- [ ] **0.2 Fix root reactor POM validation.** Add `<dependencyManagement>` entries for
  `hr.hrg.jcodebuddy:metadata-server:${project.version}` and
  `hr.hrg.jcodebuddy:metadata-mcp-server:${project.version}` — **[verified]** both are missing
  while every sibling module is managed. **[verified]** the failure reproduces on **Apache Maven
  3.9.0** (three `dependencies.dependency.version … is missing` errors: `project-automation` ×2 at
  `pom.xml:45,49`, `metadata-mcp-server` ×1 at `pom.xml:21`) and **not** on mvnd/Maven
  4.0.0-alpha-4, where the same reactor parses and `mvn -o validate` is green — Maven 4 resolves
  versionless inter-module reactor dependencies, 3.9 does not. Fix it anyway: the entries are
  genuinely missing and a contributor on Maven 3.9 is blocked before compilation starts.
  Alternative if the modules do not build: move them out of `<modules>` temporarily, and record
  that as a deliberate scoping decision.
- [ ] **0.3 Add a `hipster-entity` Maven profile** listing only the six modules, so
  `mvn -Phipster-entity -o test` works from the root without parsing `project-automation`.
  Note the G4 ordering trap (§ 4.5): generation runs through `exec-maven-plugin` against the
  *installed* tooling, so `mvn -o -pl hipster-entity-tooling -am install` must precede the first
  generation-triggering build.
- [ ] **0.4 Record the baseline** (test counts per module, exact command, JDK) in a committed
  note. This is the regression baseline for every later phase. **[verified]** on this tree, with
  `JAVA_HOME` = jdk-25 and mvnd/Maven 4.0.0-alpha-4, the command
  `mvn -o -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example -am test`
  is BUILD SUCCESS with counts `api` 0, `core` 42, `tooling` 22, `jackson` 0, `test` 4,
  `example` 0 — 68 tests total. Re-record the numbers if § 0.1a's recorded launcher or JDK differs.
- [ ] **0.5 Add a test-compilation gate before any generator work.**
  `hipster-entity-tooling`, starting as `GeneratedSourceCompilesTest`: write a fixture source
  root to a JUnit `@TempDir`, run `EntityMetadataGenerator.generate(...)`, assert the emitted
  file set, then compile the emitted sources with `javax.tools.JavaCompiler` against the real
  `api` + `core` classpath and assert **zero diagnostics**. This test is what closes the
  doc—code gap permanently and it must grow with each level.
- [ ] **0.6 Re-read § 4 before starting Phase 1.** All decisions are finalized there: S1, S2, S4,
  S5 (§ 4.1 — note **S3 is deferred**, the contract stays in `core`), D2—D5/D7 (§ 4.2), and the
  execution resolutions X1—X4 (§ 4.3, where **X1 is superseded** — no module move). Nothing is
  blocked on a further answer.

**Exit gate:** one recorded command is green from the repo root and the compile-the-output
test harness exists.

---

## 6. Phase 1 — make the runtime true (4–6 days)

Everything here is required before the generator can emit anything above `META`, because
generated code must implement the *final* interfaces.

### 6.1 `hipster-entity-api` — small holes

**No module move happens in this phase** (S3/X1 deferred): `ViewChangeTracking` and the whole
`EEnumSet*` family stay where they are. The items below are the only api-side changes.
(Task numbering has a deliberate gap: there is no 1.0, and task **1.4** — the tracking-array
constructor fix — lives in § 6.2 because it is a `core` change.)

- [ ] **1.1** `ViewWriter`: add non-abstract `default Object get(String)` and
  `default boolean supports(String)` so existing generated classes keep compiling; the
  generator needs the writability probe for S1/D6.
- [ ] **1.2** Add the four accessors to `FieldDef` (X2) with defaults that preserve current
  behaviour — `FieldSource` is **already** `@Retention(RetentionPolicy.RUNTIME)`
  **[verified, `FieldSource.java:15`]**, so the retention is a no-op and the real work here is
  `FieldDef` —
  `default FieldKind fieldKind() { return FieldKind.COLUMN; }`,
  `default String column() { return null; }`, `default String relation() { return null; }`,
  `default String expression() { return null; }`. A field with no annotation is therefore
  `COLUMN`, i.e. writable, which is exactly today's behaviour.
- [ ] **1.3** Realize the `View` write-mode contract from `@FieldSource` alone (S1/D6): no new
  annotation attribute. `FieldKind` decides writability; `column()`/`relation()`/`expression()`
  are labels and must not be confused with it.

### 6.2 `hipster-entity-core` — the blocking NPE, plus the missing contract

- [ ] **1.4 Fix the tracking-array constructor (highest-value commit in the plan).**
  Change the factory to take the field universe, which `ViewMeta` already exposes, instead of
  reflecting a `Class`:

  ```java
  protected EntityUpdateTrackingArray(F[] universe, int fieldCount, Object[] values)
  public static <T, F extends Enum<F> & FieldDef>
      EntityUpdateTrackingArray<T, F> create(F[] universe, Object... values)
  ```

  then `new EEnumSetBuilder64<>(universe)` in both variants. Keep the old
  `create(Class<F>, int, Object...)` as a **deprecated delegating overload** — the JMH
  harness in `hipster-entity-core/src/test/.../EEnumSetTrackingJmhBenchmark.java` calls the
  current signature. Add the arity check that `EntityReadArray` already has but the tracking
  array lacks.
- [ ] **1.5** `EEnumSetBuilder64` / `EEnumSetBuilderLarge`: add an `E[] universe` constructor;
  keep the `Class<E>` constructor delegating to it.
- [ ] **1.6** New `ChangeRecorder` (previous-value store), plus move `addOrdinalChange` out of
  the `default` interface method into both concrete builders so they can hold the recorder as
  state. Preserve the DEC-012 order exactly: compare → record → set bit.
- [ ] **1.7** Widen `ViewChangeTracking` to the real contract — **in place, in
  `hr.hrg.hipster.entity.core`** (S3 deferred; no module move, no file delete) — with the S5
  two-accessor shape: `isChanged()`, `changes()` (immutable snapshot), `changesBuilder()` (live
  mutable builder), `clearChanges()`, `previousValue(int)`, `hasPreviousValue(int)`, and a
  `record FieldChange<E>(E field, Object previous, Object current)` with
  `default List<FieldChange<E>> diff()`.
  Per S5 this is a **rename-and-delete**, not an additive change: drop
  `EntityUpdateTrackingArray.changesSnapshot()` → `changes()`, `getChanges()` →
  `changesBuilder()`, and delete `EntityUpdateTrackingArray64.getChanges64()`. Add **no**
  deprecated aliases.
  **The generic parameters and their bounds do not change** — keep
  `ViewChangeTracking<E extends Enum<E>, S extends EEnumSetRead<E>>` exactly as it is today
  **[verified]**. The looser bound is now an accepted, deliberate choice (S3 deferred), so there
  is **no** bound-tightening ripple into `EEnumSetBuilder64`, the JMH harness, or any implementor.
  `EEnumSetBuilder64`/`Large` already satisfy `S extends EEnumSetRead<E>`, so the existing
  hand-written `PersonSummaryBuilderTracking` keeps compiling against the widened interface apart
  from the accessor renames it needs anyway.
- [ ] **1.8** `clear()` / `clearChanges()` must also clear the recorder. Document the semantics
  precisely: they reset the **change set**, not the **comparison baseline**. To re-baseline,
  rebuild the tracking builder from the persisted row. If that is insufficient, add an explicit
  `refreshBaseline()`.
- [ ] **1.9** `ArrayBackedViewProxyFactory`'s updatable handler: add the `changesBuilder` arm
  alongside the existing `changes`/`clearChanges` arms **[verified, lines 117-123]**, forward
  `previousValue` / `diff`, and keep the throw-on-unknown-name behaviour (D5). Because the
  handler returns the array's own builder, the proxy satisfies the S5 state-sharing requirement
  without a wrapper — but see § 6.4 steps 8–12, which must prove it rather than assume it.
- [ ] **1.10** `changes()` must stay allocation-free in the empty case when the value is not
  retained (`EEnumSetEmpty.of(...)`); note that a snapshot call now allocates when non-empty by
  design, so the tracking builder's hot path must keep using `changesBuilder()`, never
  `changes()`.
- [ ] **1.11** Two latent hazards to pin with tests, not fix blindly:
  `EEnumSetBuilder64.addOrdinal` silently rejects `ordinal >= universe.length`
  (`EEnumSetBuilder64.java:223-230` returns `false`), so a mis-ordered enum makes marks vanish
  with no error — add a strict mode or a construction-time diagnostic in `ViewMeta`. And pin
  the 64/65-field boundary that selects the `64` vs `Large` variant.

### 6.3 `hipster-entity-tooling` — repair the validator

- [ ] **1.12** Rewrite `ViewAnnotationRule`: parse `gen` (default `DEFAULT` → resolve to
  `META`), `discriminatorField`, and `addons`; drop the string-`contains` check; reject a
  `GenLevel` incompatible with the declared shape (e.g. `BUILDER_TRACKED` on a view with no
  writable fields). Update `ViewAnnotationRuleTest`, `EntityRulesValidatorTest`, and the
  `EntityMetadataGeneratorTest` fixtures, which all still use the non-existent
  `@View(read = …, write = …)` form.
- [ ] **1.13** Wire the validator into `EntityMetadataGenerator.generate(...)`: collect
  `ValidationIssue`s and fail (or warn behind a `strict` flag) **before** writing files.
- [ ] **1.14** New `EnumConstantOrderChecker` in
  `hipster-entity-tooling/.../validation/` (R1.3, § 4.6): parses a Java file with JavaParser,
  finds the enum carrying the `entityFieldEnum:true` marker, and returns its constant names in
  declaration order. Includes the marker reader (parse the header comment above `package`,
  decode the DEC-021 JSON5 subset, treat a malformed marker as a diagnostic rather than a skip)
  and the subsequence comparison `old` ⊆? `new` preserving order. No git dependency in this
  class — it takes two parsed enums, so it is unit-testable in isolation.
- [ ] **1.15** New `EntityFieldEnumOrderRule implements EntityRule` (R1.3), registered in
  `EntityRulesValidator`'s rule list **[verified, `EntityRulesValidator.java:33-40`]** alongside
  `MarkerEntityRule`/`ViewInterfaceRule`/`ViewAnnotationRule`/`AuditableRule`. It compares the
  working tree against a baseline git ref, reports `enum_order_shuffled` / `enum_constant_removed`
  in the DEC-022 format, and **skips every enum without the marker** (opt-in by absence).
- [ ] **1.16** CLI for the checker (R1.3): `--repo --baseline [--target] [--diff] [--strict]`,
  reachable from the tooling entry point next to `EntityMetadataGenerator.main`
  **[verified]**, exiting non-zero on violation so it can gate a build and a PR. Document the
  invocation in `hipster-entity-tooling/README.md`.
- [ ] **1.17** Checker unit tests (§ 6.4 tests 20–24) plus a fixture repo: a marker-carrying enum,
  an unmarked enum that must be ignored, an appended constant that must pass, a shuffled pair that
  must fail, and a removed constant that must fail.

### 6.4 Tests to write *first* in Phase 1

`hipster-entity-core` — new `EntityUpdateTrackingArrayTest` (this test file does not exist today):

1. `create(universe, values)` builds both variants (… 64 and 65 fields) — the regression test
   for the NPE;
2. arity mismatch → `IllegalArgumentException`;
3. equal-value `set` → no mark, `changes()` empty (DEC-012);
4. `set(0, x)` → `UnsupportedOperationException`;
5. `clear()` empties `changes()`; recorded previous values are released;
6. `previousValue(ord)` returns the pre-write value for a changed field, `null`/absent for an
   unchanged one;
7. boundary: 64- and 65-field views produce equivalent `changes()` for the same mutation.

`hipster-entity-core` / `hipster-entity-test` — the **S5 state-sharing test**, which is the
acceptance test for D1 and must pass twice, once against a generated tracking builder
(`PersonSummaryBuilderTracking`) and once against the array-backed proxy from
`ArrayBackedViewProxyFactory.createUpdatable(...)`:

8. fresh tracking view → `changes().isEmpty()` and `!isChanged()`;
9. one real change → `changesBuilder().has(ord)` **and** `changes().has(ord)` both `true`;
10. `changesBuilder().removeOrdinal(ord)` (and separately `changesBuilder().clear()`) → both
    `changes().has(ord)` **and** `isChanged()` become `false` — this is the step that fails if
    `changesBuilder()` hands back a copy instead of the shared state;
11. a `changes()` snapshot captured before step 10 still reports the old state — proving
    `changes()` is a time snapshot, not a live alias;
12. `clearChanges()` empties both accessors;
13. no test may call `changes()` on a hot path — assert in the generated-code golden files that
    setters reference `changesBuilder()` only (§ 8.6/3.15).

**D2 tests — previous values and `diff()`** (each also counts against the DEC-012 acceptance
criterion "no-op same-value assignment MUST be clearly documented and tested"):

14. `previousValue(ord)` after a change returns the pre-write value; `hasPreviousValue(ord)` is
    `false` before any change and for an untouched field;
15. `diff()` yields exactly `(field, previous, current)` triples over the changed ordinals, and
    is empty when nothing changed;
16. **the shallow-reference hazard, both halves in one test:** write a `Map`/`List` field, then
    mutate that same instance in place without reassigning the field, and assert that
    `changes()` still marks the field **while** `diff()` reports no value difference (or an
    equal pair) — pinning the documented semantics rather than a surprising behaviour;
17. `clearChanges()` clears the recorder as well as the change set, and a subsequent
    `hasPreviousValue` is `false`.

**D5 test** — in `hipster-entity-test`, one test asserting the verified split so a future
"consistency" cleanup cannot silently unify it:

18. `EntityUpdateTrackingArray.set("noSuchField", v)` returns `-1` and the generated builder's
    `set(String,Object)` returns `-1`, **while** the updatable proxy throws
    `IllegalArgumentException` for the same name.

**D7 test** — in `hipster-entity-api` (next to `DefaultViewMeta`):

19. constructing `DefaultViewMeta` with a `FieldDef` enum whose ordinal order is inconsistent
    fails fast with a diagnostic naming the enum and the constant, instead of silently
    mis-marking fields downstream.

**R1 tests — the append-only rule and its checker** (§4.6), in `hipster-entity-tooling`:

20. **appending is accepted:** baseline `[id, firstName, lastName]` → new
    `[id, firstName, lastName, email]` passes, and the new field's ordinal is `3`;
21. **reordering fails:** baseline `[id, firstName, lastName]` → new
    `[id, lastName, firstName]` produces `enum_order_shuffled` naming `lastName`, its old index
    `2` and new index `1`, and the check exits non-zero;
22. **insertion in the middle fails and reads as a reorder:** baseline `[id, firstName]` → new
    `[id, email, firstName]` is reported against `firstName` (old `1` → new `2`), not silently
    accepted as an addition;
23. **removal fails:** baseline `[id, firstName, lastName]` → new `[id, lastName]` produces
    `enum_constant_removed` for `firstName` (and would shift `lastName` from `2` to `1`);
24. **opt-in is honoured:** an identical shuffle in an enum **without** the
    `entityFieldEnum:true` marker passes the check untouched, and a malformed marker in a marked
    enum is reported as a diagnostic rather than skipped;
25. **removed-accessor tombstone (R1.4):** given an interface that drops an accessor, the
    generator keeps the constant in place and `@Deprecated`, and asserts that
    `fieldCount`/`values.length` are unchanged, every later ordinal is unchanged, the record
    keeps a nullable component for the tombstone, no builder setter or `Write` method is emitted
    for it, and `forName` still resolves the retired name.

`hipster-entity-core` JMH: extend `EEnumSetTrackingJmhBenchmark` to the new signature and add a
`previousValue` on/off axis, so the cost of the recorder is measured rather than assumed.

**Exit gate:** tests 1–25 are green, with 8–12 passing on both materializations and 20–25
proving the append-only rule and its opt-in checker.

---

## 7. Phase 2 — the ordinal array contract and JSON changes (2–3 days)

### 7.1 Freeze the integration boundary (1 day)

This is the only interface a real project's persistence layer has to implement. Write it down
before adapters proliferate (dsflash § 5):

```
values[f.ordinal()] == the value of field f, for every f in the companion FieldDef enum
values.length       == FieldDef.values().length
ordinal 0           == the identity field of an entity root (immutable in tracking arrays)
a DERIVED/JOINED field still occupies its ordinal; an adapter may leave it null
column order comes from ViewMeta, never from SELECT *
the ordinal list is APPEND-ONLY: new fields get new ordinals at the end (R1, § 4.6)
  a retired field keeps its ordinal and its slot; its constant is @Deprecated, not deleted
```

- [ ] **2.1** `doc-hipster-entity/user/patterns/ordinal-array-contract.md` (new) — the contract
  above, the `QueryProjection → Object[] → META.create()` flow, and an explicit statement of the
  **R1 append-only rule** with what it means for an adapter author: ordinals never move, a
  retired field's slot still exists and may be null, and `fieldCount` only ever grows.
- [ ] **2.2** `doc-hipster-entity/user/patterns/jdbc-row-adapter.md` (new) — a ~30-line
  reflection-free `Object[] fromResultSet(ResultSet, FieldDef[])` reference adapter driven by
  `meta.fieldTypeAt(i)`.
- [ ] **2.3** A test in `hipster-entity-test`: build a read view from an `Object[]` produced by
  a fake `ResultSet`, assert every accessor, and assert
  `values.length == PersonSummary_.values().length`.

### 7.2 JSON changes (the visible payoff of tracking)

- [ ] **2.4** New `EntityJacksonChangeSerializer`: takes a `ViewChangeTracking` + `ViewMeta`,
  emits only the marked fields; with `includePrevious` enabled, wraps each as
  `{"previous": …, "current": …}`. Field names come from `meta.fieldNameAt(ordinal)` — **no
  `HashMap` name→index lookup** (DEC-016). Per S4, an absent field is not written at all
  (no explicit `null` emission); nulls appear only inside a changed field's
  previous/current pair.
- [ ] **2.5** `EntityJacksonMapper.toJsonChanges(ViewMeta, ViewChangeTracking, Writer)` plus
  the `includePrevious` overload; register the same shape in `EntityJacksonViewModule` so
  `ObjectMapper` users get it for free.
- [ ] **2.6** Document the ObjectMapper registration path in
  `doc-hipster-entity/user/patterns/jackson-setup.md` with the real Jackson 3.x
  (`tools.jackson.*`) coordinates the POMs already use. Note that the write/read round trip is
  **already proven** by the four tests in
  `hipster-entity-test/.../EntityJacksonMapperTest.java` (including
  `registerViewModuleIntoObjectMapper` driving a stock `ObjectMapper`) — so this task is
  documentation, not repair, and the thin `EntityJacksonViewJsonSerializer`/
  `JsonDeserializer` wrappers are reachable code, not stubs.

**Exit gate:** a mutation produces a one-field JSON patch, and the same patch is produced from
the proxy path.

---

## 8. Phase 3 — the generator, level by level (5–8 days)

`EntityMetadataGenerator` currently emits only the metadata enum and JSON, and never
interprets `GenLevel` **[verified]**. Each step below is independently shippable, and each
grows `GeneratedSourceCompilesTest` from Phase 0.5.

### 8.1 First, make `GenLevel` real

- [ ] **3.1** `meta/ViewAttributes` → `record ViewAttributes(GenLevel gen, String discriminatorField, List<String> addons)`.
- [ ] **3.2** Rewrite `parseViewAnnotation` (`EntityMetadataGenerator.java:438-467`) to read
  `gen`, `discriminatorField`, `addons` from `NormalAnnotationExpr.getPairs()`, mapping
  `GenLevel.X` by simple-name comparison (no class loading), defaulting to `GenLevel.DEFAULT`.
- [ ] **3.3** Make the `DEFAULT` resolution rule explicit and implement it exactly as **G1**
  (§ 4.5) specifies — precedence `RECORD` (nested record with matching component order) →
  `BUILDER` (nested `Write`) → `META`; never tracked. A mismatched nested record emits the
  DEC-022 divergence diagnostic and falls back to `META`.
- [ ] **3.4** Carry `gen` through the tooling's `ViewMeta` into the JSON (`"gen": "META"` in
  `toJson`, parsed back in `fromJson`). Round-tripping must stay lossless — there is an
  existing `fromJson` test.

### 8.2 Two concrete generator bugs to fix while in there

- [ ] **3.5** Exclude `default` methods from the property list
  (`EntityMetadataGenerator.java:293-296`): add `.filter(m -> !m.isDefault())` plus a
  regression test. **[verified]** this is what produced the bogus `toBuilder` /
  `toBuilderTracking` enum constants in the example.
- [ ] **3.6** Stop emitting raw source type strings. `parseProperty` stores
  `method.getType().asString()` verbatim (`:354`), so `Map<String, List<Long>>` reaches the
  emitter as text. Resolve through JavaParser's symbol solver or an import table built from
  the `CompilationUnit`, then emit fully-qualified names — matching what
  `EntityMetadataGenerator.classLiteral` already attempts.

### 8.3 Level `META` — regenerate the metadata enum correctly

- [ ] **3.7** Emit, per view, the shape from
  `hipster-entity-test/.../PersonSummary_.java`: `implements FieldDef`, a `Type javaType()`
  accessor (`TypeUtils.parameterizedType(...)` for generics), a `forName` switch, a
  `FieldNameMapper` `NAME_MAPPER`, and
  `META = new DefaultViewMeta<>(View.class, View_.class, NAME_MAPPER, create)`.
  **[verified]** `FieldBoilerplateGenerator`'s `propertyEnumMode` emits
  `getPropertyType()`/`getPropertyName()` — which is exactly what produced the broken example
  enum. Deprecate `propertyEnumMode` for view enums and route view metadata through the
  `fieldEnumMode` path; keep `propertyEnumMode` only for the tooling's own `Property` model.
- [ ] **3.7a Make the field enum append-only (R1, § 4.6) — this is a generator correctness
  requirement, not a style preference.** When regenerating an enum that already exists:
  - read the existing constants in order; the new constant list **starts** with that exact list
    (no reordering, ever — the generator's enum comparison is the one place where DEC-020's
    "recognize by shape, re-emit canonically" logic must be **order-sensitive**);
  - append constants for fields present in the interface but not yet in the enum, in
    declaration order, **after** all existing ones;
  - for an accessor that has disappeared, **keep its constant in place as a tombstone**,
    annotated `@Deprecated` with a short reason comment (R1.4) — never delete it. The tombstone
    keeps its slot in the positional array and its nullable component in the record, so
    `values.length == fieldValues().length` and `create()`'s positional mapping are untouched;
    it gets **no** builder setter and **no** `Write` method. This path is only reachable when an
    existing enum is regenerated, so it is exercised by the test in § 6.4 test 25;
  - emit the deprecated-constant and appended-constant events as DEC-022 divergences, so both
    show up in a regeneration report;
  - respect `allowReorder: true` only when the user has set it by hand, and report its presence
    on every pass so it cannot be left in accidentally.
- [ ] **3.8** Add the DEC-021 two-line header above `package` in every generated file, and
  include the **`entityFieldEnum:true` marker** on every generated field enum (R1.2, § 4.6):

  ```java
  // {@link <viewFqn>} Field metadata for the <View> view.
  // {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
  ```

  `enabled: false` takes the file fully under manual control. `entityFieldEnum: true` opts the
  enum into the R1 order checker; its absence opts out. Document the pinned JSON5 subset (the
  seven Jackson `JsonReadFeature`s of DEC-021 § 4) in `hipster-entity-tooling/README.md`,
  including the marker keys and the `allowReorder` escape hatch.
- [ ] **3.9** Expose per-field `FieldSource` metadata (kind/column/relation/expression) in the
  generated enum — the write path § 8.5 needs the writability classification anyway (D6).

### 8.4 Level `RECORD`

- [ ] **3.10** `create(Object[] values)` returns the concrete record instead of a proxy:
  `new PersonSummaryRecord(values[0], values[1], …)`. If a nested `PersonSummary.Record`
  already exists in hand-written source, **do not emit a second one** — recognize it by shape
  (DEC-020) and generate `create()` against it. Otherwise emit a top-level
  `PersonSummaryRecord.java` whose component order matches enum ordinal order.
- [ ] **3.11** Test the null policy for `DERIVED`/`JOINED` fields that are absent from a payload:
  a `RECORD`-level `create()` **must accept `null`** for them and not throw — settled by S4.
  Add the fixture assertion; do not add a "missing required field" rejection.

### 8.5 Levels `WRITABLE` / `BUILDER`

- [ ] **3.12** `Write` interface, nested when possible, one fluent setter per **writable**
  field only — `@FieldSource(kind = COLUMN)`, settled by S1. Golden output is the hand-written
  `hipster-entity-example/.../PersonSummary.java` **except** that its `age` and `departmentName`
  setters are now known-wrong and must be dropped (§ 4.1 S1). If a view has no writable field,
  omit it and log a diagnostic.
- [ ] **3.13** `<View>Builder`: mutable fields, copy-constructor from the view, getters,
  fluent setters **for writable fields only (S1)**, `Object get(int)`, `int set(String,Object)`
  with the `-1` contract, `void set(int,Object)`, and `build()`. Golden output is the
  hand-written `PersonSummaryBuilder.java`, same S1 caveat. For views that stay at `WRITABLE`,
  fall back to the `ArrayBackedViewProxyFactory.createUpdatable` path — the generator must pick
  the strategy from `GenLevel`, never hardcode one.
- [ ] **3.13a** Add a `ViewWriter.supports(String)`-based guard so a caller probing a
  non-writable field gets a defined answer rather than a silent no-op (feeds § 6.1/1.1 and D6).

### 8.6 Levels `BUILDER_TRACKED` / `BUILDER_ALL`

- [ ] **3.14** Emit `<View>BuilderTracking` to the final interface from § 6.2 — which stays in
  `hr.hrg.hipster.entity.core` (S3 deferred), so the generated file imports
  `hr.hrg.hipster.entity.core.{ViewChangeTracking, EEnumSetBuilder64, EEnumSet64}` and the
  consuming POM needs `hipster-entity-core` — with the S5 two-accessor pair
  (`changes()` → `mf.toImmutable()`, `changesBuilder()` → `mf`), choosing `EEnumSetBuilder64`
  vs `EEnumSetBuilderLarge` **at generation time** from the known field count. Keep the runtime
  `.create()` selection only for the array/proxy route. Because the generated class *is* the
  tracking state holder, both accessors derive from the one `mf` field (§ 4.1 S5) — never emit a
  second field or a snapshot cache. Mirrors the hand-written example's imports
  **[verified, `PersonSummaryBuilderTracking.java:6-7`]** exactly.
- [ ] **3.15** Emit the setter body in exactly the verified order:
  `mf.addOrdinalChange(<ordinal literal>, field, value); field = value; return this;` — the
  ordinal must be a **literal** so the JIT can constant-fold the `1L << ordinal` mask.
  Mirror it in the `set(int,Object)` and `set(String,Object)` arms with an explicit per-field
  cast, and return `-1` for an unknown name. Emit these arms **only for writable fields (S1)**;
  the hand-written example's arms for `age` and `departmentName`
  (`PersonSummaryBuilderTracking.java:94-95, 106-107`) are the S1 violation to remove.
  Per S5, generated setters and `set(int)`/`set(String)` arms touch `mf` **directly** and must
  never route through `changes()` — `changes()` allocates a snapshot and is a read-side API for
  callers, not an internal accessor. The § 6.4/13 golden-file assertion enforces this.
- [ ] **3.16** The copy-constructor from the source view is mandatory: without a baseline,
  "changed" is meaningless.
- [ ] **3.17** `BUILDER_ALL` emits both builders in one pass and shares the field-storage /
  record-reconstruction shape through a small internal helper — not a new public API.
- [ ] **3.17a** Emit `toBuilder()` / `toBuilderTracking()` as `default` methods on the view
  interface for `BUILDER`/`BUILDER_TRACKED`/`BUILDER_ALL` (G2, § 4.5), shape-recognized so user
  edits survive. Coupled with § 8.2/3.5: they are *deliberate* methods, and must not reappear as
  field-enum constants.
- [ ] **3.18** The `TrackingStrict` variant: treat it as a **user-added nested class to preserve,
  never to generate** (G3, § 4.5). Shape-recognize the enclosing builder and carry the nested
  class through verbatim; document in the tooling README *why* the plain tracking builder does not
  re-check equality (`addOrdinalChange` already compares), so a later cleanup pass does not delete
  it. Add the run-twice regeneration test asserting it is byte-identical.

### 8.7 Cooperative codegen and refactor-sensitivity (DEC-019/020/021/022)

- [ ] **3.19** New `CooperativeCodegen`: before overwriting, parse the existing file and
  recognize prior output **by shape**, not by markers — a `record Record(...) implements <View>`
  inside the view body, an `interface Write` inside it, a `<View>Builder` class with matching
  fields plus `build()`, a `<View>BuilderTracking` with an `mf` field plus `isChanged()`/
  `changesBuilder()`, `case "…" ->` arms in `forName`, a `public static final ViewMeta<…> META`,
  the `default toBuilder()`/`toBuilderTracking()` methods (G2), and **any user-added nested class
  inside a generated builder** such as `TrackingStrict` (G3).
  Copy the recognized body verbatim, including user edits; append only genuinely new fields.
  A single optional UX hint comment is allowed; the strict `// generator:begin/end` pair is
  discouraged.
  **Exception for the field enum (R1):** for the constant list, "append only genuinely new
  fields" means **literally at the end** — never re-insert a constant into its "canonical"
  position, and never drop a constant the interface no longer declares. See § 8.3/3.7a.
- [ ] **3.20** New `DivergenceReporter` (DEC-022): after each pass, emit
  `kind, location, cause, current, canonical, action`. The two diagnostics that must exist
  first are "field in the enum but not in the interface" and "field in the interface but not
  in the enum" — those are the two that bit the example. Also report `stale_switch`,
  `missing_setter`, `type_mismatch`, `ordinal_drift`, and the two R1 kinds
  `enum_order_shuffled` and `enum_constant_removed` (plus a warning-level
  `enum_reorder_allowed` whenever `allowReorder: true` is present, so the escape hatch is always
  visible).
- [ ] **3.21** Make determinism a prerequisite and test it: running the generator twice on the
  same tree must be byte-identical.
- [ ] **3.22** Publish the naming-contract table in `hipster-entity-tooling/README.md`:
  refactor-**sensitive** derived names (`View_`, `ViewBuilder`, `ViewBuilderTracking`,
  `ViewRecord`, `Write`, `toBuilder`, `toBuilderTracking`, `META`, `forName`, `NAME_MAPPER` —
  each reachable from the view via `{@link}`) versus refactor-**insensitive** explicit labels
  (`case "firstName"` JSON/JDBC names, discriminator values) which an IDE rename must not
  touch. Add the **R1 order-contract** section in the same README: the
  `entityFieldEnum:true` marker, why the enum constant order is semantic while every other
  generated block is shape-recognized, the `allowReorder` escape hatch, and the
  `EnumConstantOrderChecker` CLI (§ 6.3/1.14–1.16).

### 8.8 Build integration

- [ ] **3.23** Build integration via **`exec-maven-plugin` bound to `generate-sources`** (G4,
  § 4.5), invoking the generator's entry point with `sourceRoot`/`outputDir` and the `genLevels` /
  `cooperative` / `strict` / **`packages`** knobs as CLI or system properties (the `packages`
  filter is what keeps the example's documentation samples out of the scan — X3, § 4.3).
  Generated sources are added to the
  compile source roots. **Not** a new `hipster-entity-maven-plugin` module.
- [ ] **3.24** NOT HERE — MOVED: the `project-automation` watcher is **in scope as Phase 7.5**
  (task 7.17, X4). This entry is a pointer only, so the watcher is not mistaken for a missing
  step in Phase 3.

**Exit gate:** `GeneratedSourceCompilesTest` is green for all six levels, with zero diagnostics,
and the `META` output byte-matches the reference shape modulo the header.

---

## 9. Phase 4 — example, demo, and docs (2–3 days)

- [ ] **4.1** Regenerate `hipster-entity-example` from the generator. Any diff against the old
  hand-written files becomes either a generator bug or an intentional documented divergence.
  Delete the hand-written `PersonSummary_`, `PersonDto_`, `Write_`, `PersonSummaryBuilder`,
  `PersonSummaryBuilderTracking` only once the generated versions pass the same tests.
- [ ] **4.2** Replace the empty `PersonController` with a real `PersonDemo.main` (no
  framework): row array → read proxy → JSON → tracking patch → printed diff → the
  parameterised `UPDATE` it would send.
- [ ] **4.3** Drive `paymentMethod/PaymentMethodController` from generated code using the
  polymorphic `PaymentMethod_` / `CreditCardPaymentMethod_` enums — `discriminatorField` support
  already exists in `FieldBoilerplateGenerator` but is exercised nowhere. Depends on 4.9.
- [ ] **4.4** Add a tracking-enabled fixture to `hipster-entity-test` (nothing there uses
  tracking today **[verified]**), with the proxy-parity assertions from § 6.4 mirrored at the
  view level.
- [ ] **4.5** Add the missing `README.md` files: `hipster-entity-core`, `hipster-entity-tooling`
  (including the § 8.7 naming table), `hipster-entity-test`.
- [ ] **4.6** Write the "getting started in a new project" guide: add the dependency → write
  the interface → run the generator → use the builder/tracking → serialize. Today's
  `user/materialization-guide.md` explains the levels but not the workflow.
- [ ] **4.7** Correct the documentation defects that actively mislead:
  - `user/getting-started.md` states the wrong Maven coordinates and documents an unverified
    run-tooling invocation;
  - `user/faq.md` describes tracking as vague "generated helpers";
  - `architecture/materialization-levels.md` defines **two contradictory** Level 0–4 ladders
    and refers to a `PersonSummaryField` name the code does not use — rewrite it around the six
    `GenLevel` values;
  - `roadmap/plan-for-continuation.md` is a stale generated document describing a `jcodebuddy`
    module that does not exist under that name (the module is `project-automation`) and
    contains a literal `</new_content>}` artifact — mark it superseded by this plan;
  - `architecture/decisions/README.md`: the claim that this file contains the literal text
    `(line is not valid UTF-8)` **does not reproduce** — a repo-wide search under
    `doc-hipster-entity/` finds no such text, so **drop this bullet rather than "repairing" text
    that is not there**;
  - **DEC-012 status conflict (corrected):** `DEC-012.md:3` says *Accepted*, while **both
    indexes** say *Proposed* — `roadmap/README.md:39` and
    `architecture/decisions/README.md:40`. Reconcile all three to Accepted — the
    no-op-on-equal-write and explicit-null-set-the-bit rules this plan depends on
    are already ratified there;
  - **DEC-012 stale "Accepted notes":** that section states the factory is
    `create(enumClass, values)`, but the code is `create(ForNameOrdinal, int, Object...)`. The
    `enumClass` that never arrived is precisely the `null` in § 2.4 — so § 6.2/1.4 restores what
    DEC-012 believed it had. Amend the note rather than leaving it as evidence against the code.
- [ ] **4.8** Update `roadmap/README.md`'s checklist and cross-reference the decisions landed
  in Phase 1 (DEC-012 amendment, and the new DEC if D3/D4 proceed). **Add a DEC for R1**
  (§ 4.6): the append-only field-enum rule, the `entityFieldEnum` marker, the checker contract
  (baseline source, subsequence comparison, exit code), and the retired-field policy. R1 governs
  a persisted data layout, so per the project's DEC-per-decision convention it must be
  traceable — extend DEC-012/DEC-021 or add a new record, but do not leave it as a plan-only
  rule. Cross-reference it from `ordinal-array-contract.md` (§ 7.1).
- [ ] **4.9** Regenerate the **`paymentMethod` sealed hierarchy** (in scope, per § 3): the five
  `*PaymentMethod` views and their `_` enums plus `PaymentMethod_.discriminatorField` /
  permitted-subtype wiring, then drive `PaymentMethodController` from the generated code as in
  4.3. This is the only place `discriminatorField`/permitted subtypes are exercised by generated
  output, so it is the acceptance test for polymorphic generation. Keep the step **after** 4.1 so
  the single-entity regeneration path is already proven.

---

## 10. Phase 5 — Jackson hardening (1–2 days)

- [ ] **5.1** `hipster-entity-jackson` has **no tests of its own**; the only Jackson tests live
  in `hipster-entity-test`. Add round-trip tests next to the code: `RECORD` level (create()
  returns the record), `META` level (proxy), and a polymorphic `PaymentMethod` hierarchy using
  `discriminatorField`/permitted subtypes.
- [ ] **5.2** Pin the S4 policy in a test: `EntityJacksonViewDeserializer` leaves `values[ord]`
  `null` for a `DERIVED`/`JOINED` field absent from the payload, and the `RECORD`-level
  `create()` accepts it (see § 8.4/3.11).
- [ ] **5.3** Add coverage inside `hipster-entity-jackson` itself for the write path and the
  `EntityJacksonViewJsonSerializer`/`JsonDeserializer` wrappers. **Not a repair task:** these
  are already exercised end-to-end from `hipster-entity-test`'s four `EntityJacksonMapperTest`
  cases, including stock-`ObjectMapper` module registration **[verified]** — the module simply
  has no tests of its own.

---

## 11. Phase 6 — nested (deep) change tracking (7–11 days)

Pulled back into scope (§ 3). Depends on Phases 1–3: the nested index is built on the final S5
contract, and the generator wiring on the Phase 3 emitters.

The gap it closes: today a field whose value is itself a tracked view gets exactly one bit — "the
reference was reassigned". It cannot express "the reference is unchanged but its `city` field
changed". **Model choice is already fixed as pull (D4, § 4.2)** — the DEC records a decision
rather than reopening one.

- [ ] **6.1** New **DEC** (number per the project's sequence): pull over push (D4); the
  `ChangePath` shape; the shallow-vs-deep accessor contract; and the collection semantics of
  task 6.4. Must be accepted before 6.2 starts.
- [ ] **6.2** **Contract**, in `hipster-entity-core` alongside `ViewChangeTracking` (S3/X1 out of
  scope — not in `api`): a navigable value type
  `record ChangePath(FieldDef field, int listIndex /* -1 when n/a */, ChangePath next /* null at leaf */)`
  and `List<ChangePath> changesDeep()` (lazily produced if the benchmark justifies it). The deep
  accessor joins the shallow pair as a **third** method with the same immutable/mutable
  discipline — do not overload `changes()` or make it context-dependent.
- [ ] **6.3** **Nested-change index** in `core`: maps ordinal → child tracker, allocated **only**
  when a field's declared type is itself `ViewChangeTracking`, so a view with no nested trackable
  field pays nothing (the DEC-014 "pay only for what you use" discipline). Sibling type to the
  builders — **do not** overload `EEnumSetBuilder64`/`Large` with tree semantics.
- [ ] **6.4** **Collections of tracked views.** `List<Address>` where `Address` is
  `BUILDER_TRACKED` must record per-index field deltas **and** structural changes (add / remove /
  reorder) **distinctly**. Reorder detection needs a stable identity per entry — which requires
  the element view to be `Identifiable` (DEC-017). If an element type is not identifiable, emit a
  diagnostic and fall back to per-index deltas only; do not guess. This is the hardest
  sub-problem: it gets its own DEC section (§ 6.1) and its acceptance tests **before**
  implementation.
- [ ] **6.5** **Generator**: detect, for each field, whether its type is itself a trackable
  `@View` type — **including generic collections thereof** — and emit the deep-index wiring in the
  generated tracking builder. Extend the existing `TypeDescriptor`/type-parsing code; do not
  rewrite it (§ 8.2/3.6 is the prerequisite that makes type resolution trustworthy).
  Discriminated/polymorphic view roots must be representable in the deep path too.
- [ ] **6.6** **Jackson**: emit deep changes as a nested **RFC 6902-like JSON patch** document
  derived from `changesDeep()`, alongside the shallow change serializer from § 7.2/2.4. Unknown
  fields are skipped, never resolved through a `HashMap` (DEC-016).
- [ ] **6.7** **Proxy parity**: an `ArrayBackedViewProxyFactory` updatable view must produce the
  same `changesDeep()` result as the generated builder, exactly as § 6.4 requires for the shallow
  case. Deep tracking is not builder-only.
- [ ] **6.8** **Proof, end to end**: a fixture with **3+ nesting levels** and a
  `List<Tracked>` field; mutate the **nested leaf in place** — plus the explicit
  **shallow-reference hazard** from § 4.2/D2, where the nested instance is mutated without the
  parent field being reassigned — and assert that `changes()` marks the parent field,
  `changesDeep()` reports the leaf path with `listIndex` populated, and the JSON patch touches
  only the leaf.
- [ ] **6.9** **Benchmarks** (JMH, DEC-014 precedent): pull on "no nested change" and "deep nested
  change". Per D4 pull is already chosen; this measures the cost it accepts and would only reopen
  the decision if pull turned out materially worse on the no-nested-change case, where it should
  be free. Gate the result into `hipster-entity-core`'s benchmark set.
- [ ] **6.10** **Docs**: `doc-hipster-entity/user/patterns/deep-change-tracking.md` — the shallow
  vs deep distinction (call-chain trace vs nested/deep tracking), the pull model, and the
  collection identity requirement. Cross-reference the new DEC.

**Exit gate:** 6.8's fixture passes on both materializations; 6.9's no-nested-change case shows
no regression against the Phase 1 baseline.

---

## 12. Phase 7 — generated adapters, mappers, validation, compaction (7–11 days)

Four independent features (kilo P-3 / P-1 / P-6, plus R1.4 compaction) and one convenience tool.
None blocks any earlier phase, and none depends on another, so they can be reordered or
parallelized freely. All generated code obeys **G5** (§ 4.5).

### 12.1 SQL / JDBC adapter generation (kilo P-3)

- [ ] **7.1** Generated positional reader per view: `Object[] fromResultSet(ResultSet, ViewMeta)`
  driven by `meta.fieldTypeAt(i)` and `meta.fieldNameAt(i)` — **no reflection**, no `SELECT *`
  ordering assumption. Tolerates `null` in every slot (S4, tombstones, unfillable
  `DERIVED`/`JOINED`).
- [ ] **7.2** Generated binder per view: `void bind(PreparedStatement, int startIndex, View)`
  writing only `FieldKind.COLUMN` fields (S1), in **ordinal order** so `PreparedStatement` plans
  stay stable and cacheable. Column names resolve through X2's `FieldDef.column()` — the
  annotation's `column()` when set, else the accessor name **[verified, `FieldSource.java:21-22`]**.
- [ ] **7.3** Generated `INSERT` / `UPDATE` fragment builders using `@FieldSource.column()`
  labels, parameterised. An `UPDATE` may be generated from a **change set** (§ 7.2/2.4) so a
  partial update touches only changed columns — the natural consumer of Phase 1's tracking.
- [ ] **7.4** Tests: a fake `ResultSet` round-trips a fixture row through reader → view → binder →
  fake `PreparedStatement`, asserting ordinals, null handling, and that a `DERIVED` field never
  appears in the binder output. No test requires a real database or a driver dependency.

### 12.2 Mapper generation between views (kilo P-1)

- [ ] **7.5** Generate a statically-dispatched mapper for a requested view pair, e.g.
  `public static PersonDto toDto(PersonSummary src)` returning `new PersonDto.Record(src.id(),
  src.firstName(), …)` with **direct field access**, never reflection (DEC-019).
- [ ] **7.6** Type resolution: same type → direct copy; assignable → direct copy; a known widening
  (e.g. `Integer` → `Long`) → explicit conversion the generator understands; anything else →
  **DEC-022 diagnostic and the field is left unmapped**, never a lossy cast. A divergent-type
  converter registry is out of scope — the generator's rule is "map what is provably safe, report
  the rest".
- [ ] **7.7** Field-name matching is by `FieldDef` name (the accessor name), and the generator
  must report fields present in one view but missing from the other, since a silent omission in a
  mapper is a data-loss bug that no compiler catches.
- [ ] **7.8** Tests: a same-shape pair maps exactly; a pair with one missing field produces a
  diagnostic and still compiles; a pair with an unconvertible type produces a diagnostic and no
  mapping code for that field.

### 12.3 Bean Validation generation (kilo P-6)

- [ ] **7.9** Extend the metadata model so field constraints reach the generator. **Source of
  truth: the constraint annotation on the view accessor itself** (`@NotNull`, `@Size`, `@Min`,
  `@Max`, `@Pattern`, …), read with JavaParser exactly as `@FieldSource` already is — do **not**
  invent a second annotation style, and do not duplicate constraints into `@FieldSource`.
  Document the recognized annotation set in the tooling README.
- [ ] **7.10** Emit those annotations onto the generated `Record` components and `Builder` fields
  where the constraint is expressible. Where it is not — an unsupported annotation, or a
  constraint referring to another field — emit a DEC-022 diagnostic instead of silently dropping
  it.
  **Dependency note:** this is the one Phase 7 feature that may add a compile dependency
  (`jakarta.validation-api`). It must be `provided`/optional in the tooling and only added to a
  consuming project's POM when it actually wants validation — the library modules must not gain a
  hard validation dependency.
- [ ] **7.11** Alternative for cases annotations cannot express: generate a standalone
  `<View>Validator` with a concrete `validate(View)` body and explicit violation messages (G5's
  naming and dispatch rules). Per constraint, choose annotation-vs-validator and document why.
- [ ] **7.12** Tests: a valid instance passes, each generated constraint fails its negative case,
  and an unsupported constraint yields a diagnostic and no generated code for it.

### 12.4 Field-enum compaction (R1.4)

- [ ] **7.13** A deliberate **compaction mode**: drops `@Deprecated` tombstone constants and
  renumbers the remaining ordinals densely. It is not a default behaviour and must never run as
  part of a normal generation pass.
- [ ] **7.14** **Safety gate:** compaction refuses to run unless explicitly requested *and* the
  operator acknowledges that no persisted positional array, JSON patch, or snapshot survives —
  a real migration. It emits a report of every ordinal that moved, and it must be invoked with
  `allowReorder` semantics explicitly, not implied.
- [ ] **7.15** After compaction, the R1 checker must **pass** (the constant list is a subsequence
  in order — tombstones removed, nothing shuffled), and `allFields` in the metadata JSON is
  rewritten as the new migration record.
- [ ] **7.16** Document the migration procedure end to end (which callers to drain, which
  persisted forms to convert, how to verify) alongside the R1 contract in the tooling README.

### 12.5 `project-automation` entity watcher (X4)

- [ ] **7.17** Wire a watcher that triggers regeneration when an entity source changes, per
  `README.md`'s description of the module. **Conditional:** if Phase 0.2 concluded that
  `project-automation` must leave the reactor to unblock the build, implement this as a standalone
  tool in `hipster-entity-tooling` instead — the user-visible behaviour is the same and no other
  task depends on which module hosts it.

**Exit gate:** § 12.1–12.4 tests green; the R1 checker passes after a compaction round-trip; no
library module gained a hard JDBC or validation dependency.

---

## 13. Sequencing, effort, gates

| Phase | Content | Effort | Exit gate |
|---|---|---|---|
| 0 | JDK-25 pin for compiler **and** test fork, recorded Maven launcher, reactor POM, profile, baseline, compile-the-output harness | ≈ ½ d | one recorded command green from root |
| 1 | tracking ctor NPE, contract widened + `ChangeRecorder` in `core`, **R1 checker + marker**, validator repair, tests 1–25 | 4–6 d | § 6.4 tests 1–25 green, 8–12 on both materializations, 20–25 for append-only |
| 2 | ordinal-array contract docs (incl. R1 for adapter authors), JSON change serializer, Jackson setup doc | 2–3 d | one-field JSON patch from both paths |
| 3 | `GenLevel` parsing, levels META→BUILDER_ALL, **append-only enum generation + marker emission**, cooperative blocks, headers, divergence, `exec-maven-plugin` | 5–8 d | `GeneratedSourceCompilesTest` green for all six levels |
| 4 | example regeneration incl. X3 layout work and the S1 write-surface reduction, `PersonDemo`, doc corrections | 2–3 d | demo prints a one-field diff; no doc claims a missing level |
| 5 | Jackson round-trip + polymorphism coverage | 1–2 d | round-trip tests green |
| 6 | **nested/deep change tracking**: DEC, `ChangePath`/`changesDeep()`, nested index, collections, generator wiring, deep patch | 7–11 d | 3-level fixture and a `List<Tracked>` produce a correct nested patch |
| 7 | **adapters & codegen extras**: JDBC row/binder, mappers, validation, compaction, watcher | 7–11 d | generated adapter round-trips a fixture row; mapper/validator/compaction tests green |
| — | **total** | **≈ 30–44 d** | § 3 DoD fully checked |

**Scope note, stated plainly:** pulling the five extra features back in roughly **doubles** the
plan — from ≈16–22 d to ≈30–44 d, and from 6 phases to 8. That is the honest cost of the scope
decision, not padding. If the schedule matters more than completeness, the natural cut is
Phase 7 (which is four independent features, none of which any earlier phase depends on) —
Phase 6 is on the path to "tracking actually works end to end" and should not be cut alone.

Ordering constraints that are **not** negotiable:

- Phase 0 before anything — otherwise nothing is verifiable.
- § 6.2 (1.4) before any tracking test can even run.
- § 6.2 (1.7) / (1.8) before § 8.6, because the generated builder must implement the final
  interface — never generate against a contract that is about to change.
- § 6.4 test 19 (D7 check) before Phase 4 regenerates the example, so the example's ordinals are
  validated the first time.
- § 8.1 (`GenLevel` parsing) before § 8.3–8.6; the levels are a ladder.
- The compile-the-output test lands with the first level and grows with each one. Never
  regenerate a level without a compiling assertion.
- The example is regenerated **in the same commit** as any API change it consumes. This is
  certain to be needed for S5: `PersonSummaryBuilderTracking.java:77` returns the live `mf` from
  `changes()` today and must become `changesBuilder()`, with `changes()` added alongside it.
- Per S5, the `changes()`/`changesBuilder()` pair and the underlying array renames
  (`changesSnapshot()`→`changes()`, `getChanges()`→`changesBuilder()`, delete `getChanges64()`)
  land in **one commit with no aliases**, so there is no window in which both naming schemes
  exist.
- **No module-layering work in this release** (S3/X1 deferred). If a task appears to require
  moving `ViewChangeTracking` or the `EEnumSet*` interfaces between `api` and `core`, it is out of
  scope — take the `core` dependency instead.
- **R1 (§ 4.6) gates ordering everywhere the enum is touched:** the generator appends
  (§ 8.3/3.7a) and the checker verifies (§ 6.3/1.14–1.16). No task may reorder a constant list,
  and no task may treat "the enum now differs from the interface" as a reason to delete a
  constant. The checker's marker must be emitted (§ 8.3/3.8) before the checker can be exercised
  against real generated output, and the checker must exist before Phase 4 regenerates the
  example — otherwise the first regeneration has no order guard at all.

**Critical path:** Phase 0 → § 6.2 → § 8.1–8.6 gives a project `@View` + generator + one-level
tracking. That alone is already a usable first release. Phase 2's JSON patch is what makes
tracking *visible* to a user; Phase 4 is what makes it *trustworthy*.

---

## 14. Consolidated conflict register

These are the places the four input plans contradict each other. Each is resolved above; this
table exists so the resolution is auditable.

| # | Conflict | Positions | Resolution |
|---|---|---|---|
| C1 | Does the build currently work? | copilot § 1: all six compile + test. dsflash § 2.2: reactor POM validation fails, wrong JDK. kilo: silent. | **[verified]** `metadata-server`/`metadata-mcp-server` are in `<modules>` but absent from `<dependencyManagement>`, so the reactor-level failure is real while per-module builds can pass. Settle empirically in Phase 0.1–0.4. |
| C2 | Is the Jackson **deserializer** a stub or a finished zero-alloc path? | kilo § 2: "~1 216 ops/ms benchmarked, zero-alloc hot path". copilot § 1: "serialization-only, no deserializer yet". | **[verified]** `EntityJacksonViewDeserializer` is a real 190+ line class with a parse loop and a benchmark in `hipster-entity-test`; only the thin `JsonSerializer`/`JsonDeserializer` wrappers are placeholders. copilot understates it. |
| C3 | Is the Jackson **serializer** done? | kilo: `EntityJacksonViewSerializer` + both Json* variants are all "stub". copilot: "serialization-only (writes a ViewReader)", i.e. the write direction works. | **[verified]** `EntityJacksonViewSerializer.serialize(ViewReader, JsonGenerator)` is a real metadata-driven implementation; the `EntityJacksonViewJsonSerializer` wrapper is thin. Both plans are partly right; the missing piece is the **change-set** serializer, which no plan disputes. |
| C4 | Ordering of generator vs runtime work | kilo/copilot: generator first (their Phase 0/1). dsflash: runtime/validator first, generator at Phase 3. | dsflash's order wins, for one reason: generated code must implement the final `ViewChangeTracking`/`EEnumSetBuilder` signatures, and the tracking array does not even construct today. Fix the target before generating against it. |
| C5 | `changes()` mutable or immutable? | dsflash § 7.7: immutable. kilo § 7.4: mutable is intentional for performance. example line 77 returns the live builder. | **Settled — S5** (§ 4.1). Both variants exist under names that state which is which: `changes()` immutable snapshot, `changesBuilder()` live mutable. One shared state, so neither can go stale; enforced by the § 6.4 state-sharing test. This is a delete-and-rename, not an added alias. |
| C6 | Meaning of "full depth" | dsflash § 7: the full **call chain** of one level. copilot § 4: genuine recursion into nested tracked views and `List<Tracked>`. | Both are legitimate and the phrase collided. **D3 + D4** (§ 4.2): both levels are in scope — single-level in Phases 1–3, nested/deep as **Phase 6**, decided as pull. Use "call-chain trace" vs "nested/deep tracking" to keep the two meanings distinct. |
| C7 | `ViewChangeTracking` type bounds | dsflash § 4.2.3 tightened `F` to `Enum<F> & FieldDef` and claimed the current looser bound is a coincidence. | **Resolved by the S3/X1 exclusion — no change.** `EEnumSetRead<E extends Enum<E>>` is not `FieldDef`-bound **[verified]**, and S3/X1 are out of scope, so the bound stays `Enum<E>` as a deliberate choice. The tightening is recorded in § 4.4 as an optional part of that future module move, where it would be free because the commit already touches every implementor. |
| C8 | Priorities below the slice | kilo adds JDBC adapters, mapper generation, and validation boilerplate as Phase 2. dsflash excludes them; ds/copilot place Jackson earlier than kilo. | **kilo's items are now in scope, but sequenced last** (Phase 7), after the example is generated and the field-enum order guard is in place. Jackson keeps its earlier slot (Phase 2/5) because the change-set serializer is the visible payoff of Phase 1. Ordering follows dependency, not the input plans' phase numbers. |
| C9 | Is the hand-written example's `Write` surface authoritative? | The example marks `age`/`departmentName` as `DERIVED`/`JOINED` yet declares setters for them **[verified]**, while D6 says setters are `COLUMN`-only. | **Settled — S1** (§ 4.1): the example is wrong. The regenerated `Write`/builder drop those setters and the corresponding `set(int)`/`set(String)` arms; the fields stay in the enum, record, and positional array. |
| C10 | Which module owns the tracking contract? | `ViewChangeTracking` lives in `hipster-entity-core`; generated views conceptually want an api-only dependency. | **Out of scope — S3** (§ 4.1): the contract **stays in `core`**, so a consumer of generated tracking builders depends on `hipster-entity-core`. No module move, no `EEnumSet*` relocation, no bound tightening (which also retires C7). Recorded in § 4.4 as the one excluded item. |
| C11 | Is the Jackson work a repair or new feature? | kilo § 2 and copilot § 1 imply an incomplete write path; dsflash § 4.3 adds a patch serializer. | **[verified]** the write+read round trip already passes in `hipster-entity-test` (four tests, incl. stock-`ObjectMapper` registration), so the only *new* Jackson work is the **change-set** serializer (§ 7.2) plus in-module coverage (§ 10). |
| C12 | How is a field-enum reorder caught before it corrupts persisted data? | No input plan addressed it. D7 froze order in prose; `allFields` JSON recorded it; the runtime check was construction-time and post-hoc. | **New binding rule R1** (§ 4.6): field enums are append-only, carry an opt-in `entityFieldEnum:true` marker, and a JavaParser `EntityFieldEnumOrderRule` reads a commit/PR diff and fails on a shuffle. Complements D7 (runtime) with a build-time guard, and resolves the ambiguity about how a retired field is handled (keep the constant, `@Deprecated`). |

---

## 15. Risk register (merged)

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The reactor build is red for reasons unrelated to hipster-entity (metadata modules) | High | High | Phase 0.2 + the `hipster-entity` profile (0.3) scope the work to six modules so progress is never blocked by an unrelated module. |
| `EEnumSet*` API changes ripple into the JMH harness and the example | High | Medium | Keep `Class`-based constructors as delegating overloads; update the JMH harness in the same commit; regenerate the example in the same commit. |
| Generated output overwrites hand-edits | High | Medium | Shape-based cooperative detection (3.19) + `enabled:false` (3.8); ambiguous cases fall back to overwrite + a diagnostic rather than guessing. |
| Cooperative shape-detection false positives | Medium | High | Start with strict shape matching only; treat ambiguity as "not recognized" and report. |
| Ordinal reordering silently corrupts persisted patches | Medium | High | Freeze the § 7.1 contract as append-only (R1); construction-time arity/order check in `DefaultViewMeta`; keep `allFields` JSON as the migration record. |
| A field-enum reorder reaches `main` because the checker is opt-in or not wired into CI | Medium | High | R1.2/R1.3: the generator emits the `entityFieldEnum:true` marker on every field enum it produces, so opt-out has to be deliberate. Wire the checker CLI into the build and the PR check as part of § 6.3/1.16, and assert the *unmarked-enum-is-ignored* case (test 24) so the opt-in boundary is itself tested. |
| A retired accessor's constant is "cleaned up" by a well-meaning later pass | Medium | Medium | R1.4 + § 8.7/3.20: the generator must never delete a constant, reports `enum_constant_removed` if one is missing, and the tooling README states why the deprecated constant stays. Covered by test 25. |
| `ChangeRecorder` retains large object graphs | Medium | Medium | Shallow-reference semantics documented and tested; `clear()` releases; JMH on/off axis; per-view opt-out if warranted. |
| Generator scope creep (cooperative blocks, divergence reporting) delays the usable slice | High | Medium | Phase 3 is ordered so `META → BUILDER_TRACKED` is usable *before* DEC-020/021 sophistication lands. |
| Documentation keeps drifting ahead of the code | High | Medium | The compile-the-output test (0.5) makes doc—code drift a build failure, and the Phase 4 corrections are part of the DoD, not optional. |
| JavaParser AST/API churn | Medium | High | Pin the JavaParser version in `hipster-entity-tooling/pom.xml`; add AST snapshot tests. |
| Deep nested tracking is attempted before single-level tracking is solid | Medium | High | Phase 6 is sequenced after Phases 1–5 and its DEC is task 6.1; the nested index is built on the final S5 contract, not alongside it. |
| A consumer cannot take a `hipster-entity-core` dependency and so cannot implement tracking | Low | Medium | Accepted consequence of deferring S3, not an oversight. Documented in the getting-started guide (§ 9/4.6), and the fix is the § 4.4 layering refactor — mechanical and non-blocking. Generated output shape is unaffected. |
| S1 removes setters the example currently uses, so regeneration is not a pure no-op | Certain | Low | Expected, not a surprise: § 4.1 S1 names the four call sites. Land it in Phase 4 with the diff reviewed as an intentional write-surface reduction. |

---

## 16. Immediate next actions (first week, in order)

No step below is blocked on a decision — **all of § 4 is finalized**.

1. **Phase 0.1–0.4:** pin `JAVA_HOME` to JDK 25 for the forked tests (0.1), name the recorded
   Maven launcher (0.1a), add the missing `dependencyManagement` entries, add the
   `hipster-entity` profile, run the command, record the baseline.
2. **§ 6.2/1.4:** fix the `null` enum-class NPE in both tracking-array variants, with
   `EntityUpdateTrackingArrayTest` tests 1–3. This is the single highest-value commit in the
   plan — it turns an untestable path into a testable one with no other dependency.
3. **§ 6.2/1.7–1.8 (S5+D2):** widen `ViewChangeTracking` **in place in `core`** — delete nothing,
   move nothing — add `changes()`/`changesBuilder()`, `previousValue`, `diff()`, and
   `ChangeRecorder`; rename the array accessors with no aliases; then bring
   `PersonSummaryBuilderTracking` up to that signature **by hand** and get § 6.4 tests 8–17 green
   on both materializations. Only then is the target for the generator stable.
4. **§ 6.3/1.12–1.13:** repair `ViewAnnotationRule` and wire the validator into the generator.
5. **§ 6.3/1.14–1.17 (R1):** land `EnumConstantOrderChecker` +
   `EntityFieldEnumOrderRule` + its CLI, with § 6.4 tests 20–24. Do this **before** any generator
   work and before Phase 4 regenerates the example, so the append-only rule has a guard in place
   from the first regeneration rather than being retrofitted.
6. **Phase 0.5 + § 8.1–8.3:** parse `GenLevel`, emit a correct `FieldDef` + `META` enum with the
   DEC-021 header **including the `entityFieldEnum:true` marker** (§ 8.3/3.8), implement the
   append-only regeneration (§ 8.3/3.7a), and prove the emitted source compiles.
7. **§ 6.4 test 19 (D7):** land the `DefaultViewMeta` ordinal check before Phase 4 regenerates the
   example, so the example's own ordinals are validated the first time. This is the runtime half
   of the same guarantee R1 enforces at build time.
8. **§ 9/4.7 first two doc defects:** the duplicated ladder in `materialization-levels.md` and the
   stale validator message are the two that mislead newcomers today.
9. **Then Phases 6–7 (§ 11–12)** once Phases 1–5 are green. Phase 6 is on the critical path to
   "deep tracking works"; Phase 7's four features are independent of each other and of Phase 6,
   so they can run in any order or in parallel after Phase 4.

