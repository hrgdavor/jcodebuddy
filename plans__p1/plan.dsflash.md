# hipster-entity — Combined Plan to First Usable Implementation

> ## ⚠ EXECUTED — this document is a RECORD, not a to-do list
>
> **Every task in Phases 0–7 was executed.** The checkbox markers in this document were *not* ticked
> during execution — status was tracked in
> [`plan.dsflash.notes.md`](plan.dsflash.notes.md) (*Plan scope status*) and
> [`plan.dsflash.followup.notes.md`](plan.dsflash.followup.notes.md) (*Objective status*) instead — so
> **an unchecked `- [ ]` below means "the task was written here", not "the task is outstanding".**
> Reading the 98 unchecked boxes as a backlog is the single most misleading thing about this file, and
> it is why this banner exists.
>
> Where a task ended up different from this text, the deviation is recorded as `D-n`/`F-n` in the two
> notes files, and the task's own paragraph carries an in-place note naming it. The three you are most
> likely to hit:
>
> | Task text says | What was actually built | Record |
> |---|---|---|
> | § 4.2/D2 — keep previous values, add `ChangeRecorder`, `previousValue()`, `diff()` | **Reversed.** No previous value is kept and no such API exists; comparison is the caller's, over the baseline instance, and `changedValues()` replaces `diff()` | notes D-16, DEC-012's revision section |
> | § 12.1 — generate JDBC adapters as part of the release | **Re-scoped.** A draft/exploration behind `--adapters` only, off by default, and the example ships no `*RowAdapter`/`*Binder` | notes D-17 |
> | § 6.3/1.13 — "wire the validator into `generate(...)`" | **Not done during execution** (recorded as done in the notes). Fixed afterwards: `--validate[=OFF\|REPORT\|STRICT]` and a `validate` subcommand now exist, the view-convention rule was rewritten against the real tree, and the example's binding runs it | follow-up § 6.3/1.13 note, `GateParityTest`, `ViewInterfaceRuleTest` |
>
> The one item deliberately **out of scope** remains S3/X1 (the `api`/`core` module-layering refactor,
> § 4.4). Everything else is in the tree and covered by the recorded gate:
> `scripts/mvn-jdk25.cmd` → six modules, `clean test`, BUILD SUCCESS.

**Status:** executed — decisions closed in five rounds (§ 4, § 4.7, the execution review, the
gate review and the execution-readiness review recorded at the close of § 4.7); **scope complete,
no deferred features except S3/X1**
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
| `hipster-entity-tooling` | partial | `EntityMetadataGenerator` (JavaParser scan → `*.metadata.json` + one `_` enum per view), `FieldBoilerplateGenerator`, meta model, four validation rules (`MarkerEntityRule`, `ViewInterfaceRule`, `ViewAnnotationRule`, `AuditableRule`) | `GenLevel` is **never parsed**; only `META` is emitted; `default` methods leak into field enums; obsolete `read`/`write` handling; validator inert (§ 2.5) |
| `hipster-entity-test` | minimal | `PersonEntity`, `PersonSummary`, and a reference-correct hand-written `FieldDef` + `META` enum — **not the only one in the repo**, as an earlier draft claimed: `hipster-entity-example/.../paymentMethod/entity/PaymentMethod_.java` is the second correct-shaped enum, and it is the one that carries `discriminatorField` + permitted subtypes (`PaymentMethod_.type`, lines 70-77) **[verified]** | No proxy, tracking, or builder integration tests |
| `hipster-entity-example` | demo only | Hand-written `Person*` + `PaymentMethod*` views, records, `Write` interfaces, builders, tracking builder, all `_` enums | All of it is hand-written; every `_` enum is broken **except** `paymentMethod/entity/PaymentMethod_.java` (correct `FieldDef` + `META`, raw `Class<?>` types) (§ 2.4); no runnable demo |

**[verified]** The six modules are listed in the root `pom.xml` at lines 56–61, alongside
`project-automation` (62) and the `metadata-*` modules (66–68). Root
`maven.compiler.release` is `25` (line 19).

### 2.2 Build reality — settled in Phase 0

`plan.copilot` § 1 asserts all six modules compile and pass tests. `plan.dsflash` § 2.2
asserts the opposite for the *reactor*: a POM-validation failure for
`hr.hrg.jcodebuddy:metadata-server` / `metadata-mcp-server`, and a wrong default JDK.
`plan.kilo` asserts nothing about the build.

**[verified]** `metadata-server` and `metadata-mcp-server` appear in `<modules>` and in the
reactor's inter-module `<dependencyManagement>` block, but **no `<dependencyManagement>` entry
exists for either** — and they are the two modules that are actually referenced *versionless* by a
sibling (`project-automation/pom.xml:47,51`; `metadata-mcp-server/pom.xml:23`), which is why § 0.2
must add exactly these two. (Gate review, GR-6: these two citations were corrected from `:45,49` and
`:21`; the dependencies and the failure they cause were re-verified.) **Corrected by the pre-execution review:** the generalized form of this
claim ("every other module is managed") is wrong — three further modules also lack an entry
(`java-watch-run-sample`, `hipster-entity-example`, `metadata-arena`; five missing in total, while
root `pom.xml:216-300` manages the other 17 project modules plus the third-party set). None of
those three is referenced versionless today, so no POM change is required for them. The dsflash diagnosis is therefore the credible one for the *reactor*:
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
  (`EEnumSetBuilder.java:34-40`) is a `default` interface method that compares, sets the bit
  (`addOrdinal(ordinal)` at `:38`), and returns — it stores **no previous value**, which is the
  gap D2 closes; it is not a no-op — the change *bit* is recorded. `ViewChangeTracking` (`core/ViewChangeTracking.java`) is
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

**[verified]** No test covers this: `hipster-entity-core/src/test/` contains four `EEnumSet*`
test classes (3 + 27 + 4 + 8 = 42 tests, plus the `EnumTestUtil` helper) and three JMH
benchmarks, and **no `EntityUpdateTrackingArray*` test at all**. The "42 tests pass" claim is
consistent with a completely untested tracking array.

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
**That file is the golden *shape* reference for `GenLevel.META` — not a byte-for-byte target.**
It is deliberately behind § 8.3/3.7 and § 8.3/3.9 in two known ways: its `metadata` constant is
the raw `Map.class` where 3.7 requires `TypeUtils.parameterizedType(...)`, and it emits no
`@FieldSource` accessors where 3.9 requires them for annotated fields. It also carries no
`@View` annotation, so it can never itself be generator output. It is brought up to the
parameterized, `FieldSource`-bearing form in the same commit that lands 3.7/3.9 (§ 8.3 exit
gate), and only then may it be used as a regression witness.

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

1. **One command is green.** The recorded `-pl` command from § 0.3/0.4 (the six modules with
   `-am`), run through the JDK-25 script, is green from the repo root on the JDK the root POM
   requires. There is no `-Phipster-entity` profile — a profile cannot narrow a reactor (§ 0.3).
2. **The generator is level-aware.** For a fixture view, `@View(gen = X)` emits a different,
   correct artifact set for each of `META`, `RECORD`, `WRITABLE`, `BUILDER`,
   `BUILDER_TRACKED`, `BUILDER_ALL` — and the emitted source **compiles** in a test.
3. **`META` output matches the reference shape:** `implements FieldDef`, `javaType()`,
   `forName`, `NAME_MAPPER`, `META`. `no default method leaks in as a field`, **and no
   `ViewWriter`/`ViewChangeTracking` accessor leaks in either** — the live witness of that
   second leak is the hand-written `PersonUpdatableView_.java:13`, whose `changes(...)` constant
   is the tracking accessor, not a field (§ 8.2/3.5).
4. **Tracking is correct through both paths.** Generated tracking builder and array-backed
   proxy produce identical `isChanged()` / `changes()` / previous-value / diff results for the
   same mutation sequence, including the DEC-012 no-op rule. **Scope of that parity, stated
   explicitly:** it holds for ordinals ≥ 1. The immutable-id rule is **array-path-only** —
   `EntityUpdateTrackingArray.set(0, …)` throws `UnsupportedOperationException` (§ 6.2/1.4,
   § 7.1), while `id()` carries no `@FieldSource` and is therefore a `COLUMN` field, i.e.
   writable, so the generated tracking builder keeps an ordinal-0 setter. The verified
   hand-written example does exactly that (`PersonSummaryBuilderTracking.java:79` emits
   `mf.addOrdinalChange(0, id, value)`). A parity test must therefore not drive ordinal 0 on
   both paths and expect the same answer.
5. **A change set is serializable.** `hipster-entity-jackson` can write only the changed
   fields (and, optionally, previous/current pairs) from a `ViewChangeTracking` source.
6. **Regeneration is safe and ordinals are permanent.** Running the generator twice is
   byte-identical; hand-edited recognized blocks survive; divergence is reported in the DEC-022
   format; generated files carry the DEC-021 two-line header. **Field enums are append-only
   (R1, § 4.6):** new fields append after existing constants, a reorder fails the
   `EntityFieldEnumOrderRule` check, and a removed accessor's constant is retained and
   `@Deprecated` rather than deleted. The rule is **marker-scoped (G7, § 4.5):** an existing enum
   that does not yet carry `entityFieldEnum:true` is bootstrapped as a fresh ledger and its stale
   constants are dropped and reported, not tombstoned — which is how the legacy example enums lose
   their leaked `toBuilder`/`toBuilderTracking`/`changes` constants in Phase 4.
7. **The example's entity packages are generated, not hand-written** — X3's scope, i.e.
   `person.entity` and `paymentMethod.entity`; the `example/` leftovers and the doc-sample
   packages stay hand-written by design (§ 4.3/X3, § 4.7/DR-5) — and a runnable `PersonDemo.main`
   prints a one-field diff. Any remaining diff between generator output and the old hand-written
   files is documented as intentional. The `paymentMethod` sealed hierarchy is regenerated too —
   the **four concrete** subclasses and their enums/META (`discriminatorValue`), while the base
   marker enum `paymentMethod/entity/PaymentMethod_.java` **stays hand-written** and supplies the
   discriminator field + permitted subtypes (§ 9/4.9, G8 rule 0), since a marker is not a
   discovered view — so the polymorphic wiring is exercised end to end by generated code. The
   `person.entity` framework surface `PersonSummary.Write` is **excluded from discovery** and its
   orphan `Write_.java` is deleted rather than regenerated (**G8**, § 4.5); the enums still on the
   pre-R1 hand-written shape are bootstrapped fresh rather than tombstoned (**G7**, § 4.5).
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

**§ 4.7 records two decision rounds.** A readiness review of this plan found six items still open —
addon scope, tombstone writability, ownership of the `DEFAULT` resolution, the S5 type parameter,
the `example/` leftovers, and the DEC containers. All six are resolved in **§ 4.7** (DR-1…DR-6);
the subsections they change (§ 4.3/X2 + X3, § 4.5/G1 + G6, § 4.6/R1.4, § 4.1/S5, § 9/4.1,
§ 9/4.8) were edited in place, so each rule lives in exactly one place. A **pre-execution review**
then added two more — **DR-7** (the R1 ledger is marker-scoped; a marker-less existing enum is
bootstrapped fresh, § 4.5/G7) and **DR-8** (view discovery excludes the framework surface and the
generator's own nested output, § 4.5/G8) — and applied a set of factual corrections in place.
An **execution review** then corrected one binding decision — **DR-4**: `S` is the `EEnumSet`
*interface*, never the `final` class `EEnumSet64` (§ 4.7/DR-4, § 6.2/1.7) — rewrote § 6.2/1.4 and
§ 6.2/1.6, and applied a second set of factual corrections in place (§ 4.7). A **gate review**
then re-scoped the phase exit gates to the tests each phase can actually make green (**GR-1**),
removed an unimplementable constructor instruction (**GR-2**), completed the benchmark census
(**GR-3**), and corrected three factual/scope premises (**GR-4**–**GR-6**) — all recorded at the
close of § 4.7; with those applied, the next sentence holds as written.
**No task in this plan requires an unfixed authoring decision mid-flight.** One honest
qualification: tasks **6.1/6.4** (nested/deep tracking) and **7.13** (compaction) each *author a new
standalone DEC* before their implementation starts, so those three tasks contain a scheduled design
step rather than an open question. **Those DECs are self-accepted for this plan:** a DEC counts as
"accepted" once it is written, committed, and cross-referenced from
`architecture/decisions/README.md` (and, where § 9/4.8 requires it, from `roadmap/README.md`), with
its content matching the rule this plan has already fixed — no separate human sign-off is required,
and an executor must not stall on that gate. The same reading covers the "diff reviewed" wording in
§ 15's S1 row: the review is the executor's own documented comparison of the diff against S1.
Nothing else is deferred to a mid-flight choice.

| # | Question | Final rule |
|---|---|---|
| D1 | `changes()` mutable or immutable? | **S5** (§ 4.1). Both, under names that state the variant: `changes()` = immutable snapshot, `changesBuilder()` = live mutable builder. One shared state. No aliases. |
| D2 | Must `previousValue(ord)` exist? | **~~Yes~~ SUPERSEDED — NO.** The plan's original answer ("**Yes** (§ 4.2), new `ChangeRecorder` … previous values are **shallow references**") was reversed by direct instruction after Phase 7: a tracking view must not hold old values and no previous-value API may exist. The comparison is the caller's, over the baseline instance it still holds. See `doc-hipster-entity/architecture/decisions/DEC-012.md` § "Revision — no previous value is kept (supersedes D2)" and `plan.dsflash.notes.md` D-16. Everything else in § 4.2 — the no-op rule, the bitmask, explicit null — is unchanged. |
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
`target/generated-sources`. Confirmed feasible: the working tree is clean
(`git status --porcelain` is empty), so the regeneration diff is isolated. **Correction
[verified]:** the `plans__*` directories are **tracked** files — `git ls-files` lists
`plans__p1/plan.dsflash.md` and all four `plans__m1/*`, and the only plans-related pattern in
`.gitignore` is `.kilo/plans` (the file also carries the usual Maven/IDE/OS entries) — they are
**not** ignored, as an earlier draft of this section claimed. The isolation argument is
unaffected, but Phase 4 must not expect `git status` to stay silent about the plan documents
themselves. **Second checkout on record (gate review, GR-6):**
`.kilo/worktrees/snapdragon-motorcycle/` is a git worktree holding a full copy of this tree — the
same `@View` sites, the same orphan `Write_.java` — so every repo-wide scan, `Files.walk`, or grep
that a task uses as evidence must be scoped to the working tree (`git ls-files`, or a filter that
excludes `.kilo/worktrees/`). It is invisible to `git status --porcelain` because it is a separate
checkout, which is why the isolation argument above still holds.

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
@Override public EEnumSet<PersonSummary_>          changes()       { return mf.toImmutable(); }
```

**`S` is the `EEnumSet` interface, never the final class `EEnumSet64` (corrected by the execution
review, § 4.7/DR-4).** `EEnumSetBuilder64.toImmutable()` is declared to return `EEnumSet<E>` and
returns the cached `EEnumSetEmpty` singleton when the set is empty
**[verified, `EEnumSetBuilder64.java:302-305`, `EEnumSetEmpty.java:6`]**. `EEnumSet64` is `final`
and is a *sibling* of `EEnumSetEmpty`, not its supertype, so an `EEnumSet64<View_> changes()` cannot
compile, and casting would throw `ClassCastException` on a fresh (empty) tracking view — failing
§ 6.4 test 8, the first test of this suite. Narrowing `toImmutable()`'s return type is not an escape
either: § 6.2/1.10 requires the empty case to stay allocation-free through `EEnumSetEmpty.of(...)`.
`EEnumSet` is therefore the correct `S` — both implementations satisfy it, and a caller that wants
the concrete type uses `changesBuilder()`.

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
| `EntityUpdateTrackingArrayLarge.getChangesLarge()` | **delete** | the same duplication on the other variant (`EntityUpdateTrackingArrayLarge.java:47`); the rule is "delete both", not "delete the 64 one" |
| `EEnumSetBuilder.toImmutable()` | keep | established `EnumSet`-family vocabulary; renaming it would churn every use for no gain |

Do **not** keep deprecated overloads or aliases for any of these. The plan's usual rule is to
keep old signatures as delegating overloads (see § 6.2/1.4 for the `EEnumSetBuilder`
constructors, where the JMH harness genuinely calls them) — that rule does **not** apply here,
because every in-repo caller is already in scope for rewriting. The set of callers is: the
tracking arrays, the proxy handler, the one hand-written example, **and the JMH benchmark —
which an earlier draft of this section wrongly omitted.** The pre-execution review found eight
call sites in `EEnumSetTrackingJmhBenchmark.java`: `getChanges64()` (`:266`), `getChanges()`
(`:272`, `:284`), `getChangesLarge()` (`:278`), `changesSnapshot()` (`:309`, `:314`, `:319`,
`:324`). Tasks § 6.2/1.5 and § 6.2/1.7 must update those eight sites in the same commit (the
benchmark is already in scope via § 6.4's "extend `EEnumSetTrackingJmhBenchmark` to the new
signature"); nothing else in the repo calls them.

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
**SUPERSEDED — the final rule is NO.** A tracking view must not hold old values and no
previous-value API may exist; a mutable is built from another mutable or an immutable, the caller owns
both sides, and a consumer that wants an old -> new comparison is handed both and compares them. The
text below is kept as the historical record of what was implemented and then removed. The binding
record is `doc-hipster-entity/architecture/decisions/DEC-012.md` § "Revision — no previous value is
kept (supersedes D2)" and `plan.dsflash.notes.md` D-16; `ChangeRecorder` no longer exists, `diff()` is
`changedValues()`, and `FieldChange` carries `(field, current)`.

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
    which already dereferences `fieldType.getEnumConstants()` and so is the natural place. The
    check is **not** `fieldValues[i].ordinal() == i` — that form is a tautology and can never
    fail (`fieldValues` *is* `getEnumConstants()`, and `Enum.ordinal()` is final and
    declaration-ordered), so it would be untestable. What the constructor asserts instead:
    1. `fieldValues.length >= 1` (an empty `FieldDef` enum is a hard error), and
    2. **the name map is total and lossless**: for every constant `f`,
       `forName.forName(f.name()) == f` — which catches the round-trip failures that actually
       occur in practice (a `forName` switch that lost an arm, a typo'd `case "…"`, a renamed
       constant with a stale `NAME_MAPPER`), and
    3. `fieldCount` and the constant array agree, so a downstream `values[ordinal]` write can
       never index past the array.
    Fail fast with a DEC-022 diagnostic naming the enum and the offending constant.
    **Order/permanence is not checkable here and is not claimed to be:** `values[i]` is
    declaration order by construction, so append-only ordering is enforced at *build* time by
    the R1 checker (§ 4.6/R1.3) and recorded by `allFields` in the metadata JSON. Do not
    re-add the vacuous ordinal assertion in a later "consistency" pass;
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
(A later review round added six more — see § 4.7; **X2 and X3 below were amended in place** by
§ 4.7/DR-2 and § 4.7/DR-1 respectively.)

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
  `default String expression() { return null; }`, and
  `default boolean retired() { return false; }` (§ 4.7/DR-2). Defaults keep every existing
  hand-written and generated enum compiling, and a field with no annotation is `COLUMN` — i.e.
  writable, which preserves today's behaviour for unannotated views. `retired()` is `true` only on
  an R1.4 tombstone; it is the write-side gate that `FieldKind` alone cannot express, because a
  tombstone has no accessor left to carry `@FieldSource` and therefore defaults to `COLUMN`.
- **Generator obligation:** emit overrides for the four `@FieldSource`-derived accessors only when
  the source field actually carries `@FieldSource`, so the generated enum stays small; `retired()`
  is emitted (as `true`) only on a tombstone constant (§ 4.6/R1.4).
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
  extracted by `scripts/update-doc-includes.js`. **Correction (pre-execution review):** the claim
  that the samples differ from `entity/Person` by field set (`name`/`email` vs
  `firstName`/`lastName`) is only half right — `iface/Person.java` and `record/Person.java`
  declare the **same** fields (`name()`, `email()`; `record/Person.java:4-7` is a package-private
  **record**, not an interface), and it is `entity/Person.java:9-10` that carries
  `firstName`/`lastName`. The samples duplicate each other, not `entity/Person`. The decision is
  unchanged — deleting either still breaks the INCLUDE directives — but the justification is
  "live documentation sources required by `materialization-levels.md`", not "different fields". The decision is therefore: **keep both files exactly as they are, and keep them out of
  the generator's scan** — the `@View(gen = hr.hrg.hipster.entity.api.GenLevel.META)` on
  `person/iface/Person.java` (the file writes the **fully-qualified** form, which is one of the two
  live `@View(gen = …)` instances in the tree — the other is the bare
  `@View(gen = GenLevel.BUILDER_ALL)` on `person/entity/PersonSummary.java:13` — so § 8.1/3.2's
  parser must accept **both `gen` spellings**, and G9 (§ 4.5) widens the requirement to **all four
  annotation shapes**; gate review, GR-5 + execution-readiness review, DR-9)
  is
  tutorial narrative for the "record → interface" walkthrough, and that interface is
  package-private, so it could not carry a public generated enum in any case. § 9/4.7 rewrites
  `materialization-levels.md` around the six `GenLevel` values and **must preserve both INCLUDE
  directives**, so the samples keep compiling as documentation.
  **Mechanism (do not leave this to the generator to guess):** the example module's generation
  config (§ 8.8/3.23) passes an explicit package filter — `person.entity` and
  `paymentMethod.entity` — as a `packages` knob. **Clarified in § 4.7/DR-1: `packages` filters
  *generation*, not *indexing*.** Every source file under the source root is still parsed into
  `interfaceMap`, so cross-package supertypes and addons stay resolvable — `PersonAuditable`
  inherits `createdAt`/`updatedAt` from `example.Auditable`, and an addon must be resolvable even
  when it is declared outside the generated packages. Only view discovery and output are
  restricted: no `_` enum, record, builder or `META` is emitted for a package outside the filter,
  which is what keeps the doc samples and the `example/` package untouched. Document that knob,
  with this distinction, alongside `genLevels`/`cooperative`/`strict` in
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

### 4.5 Specification gaps closed — the nine items an implementer would otherwise guess

Everything else in this plan is a concrete edit. **G1–G6** were left implicit by the input plans
and closed in the second decision round; **G7 and G8** were added by the pre-execution review
(§ 4.7/DR-7 and DR-8); **G9** was added by the execution-readiness review (§ 4.7/DR-9) because the
`@View` census and the parse spec covered only the parenthesized annotation forms. All nine are now
fixed, so no task in Phases 0–5 requires an authoring
decision mid-flight; the only scheduled design steps are the DECs that tasks 6.1/6.4 and 7.13
author for themselves (see § 4 intro).

**G1 — `GenLevel.DEFAULT` resolves by interface shape, never upward.**

`GenLevel`'s own javadoc says `DEFAULT` is "determined by the code generator based on the
presence of other options and the type of view" **[verified, `GenLevel.java:4-7`]** — it does not
define the rule, so the generator must. The binding rule, in strict precedence order:

1. If the view declares a nested `record` whose component list matches the field enum order →
   `RECORD`.
2. Else if the view declares a nested `Write` interface → `BUILDER`.
3. Else → `META`.

**Single owner of the rule (§ 4.7/DR-3).** The rule lives in exactly one place: a new
`GenLevelResolver` in `hipster-entity-tooling` (next to `EntityMetadataGenerator`), called by
**both** the generator's `parseViewAnnotation`/`ViewMeta` construction (§ 8.1/3.3) and
`ViewAnnotationRule` (§ 6.3/1.12). The validator must **not** carry its own "`DEFAULT` means
`META`" shortcut: that would let validation and generation disagree about a view whose shape
resolves to `RECORD` or `BUILDER`, which is exactly the class of bug this plan exists to remove,
and it would leave the plan documenting two rules for one concept.

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

The example carries a hand-written `TrackingStrict extends PersonSummaryBuilderTracking`
**[verified, `PersonSummaryBuilderTracking.java:55-59`]**. **Correction [verified]:** it does
*not* re-inject `EEnumSetBuilder64.Strict` — its no-arg constructor passes a plain
`new EEnumSetBuilder64<>(PersonSummary_.class)` (`:57`), so today it is behaviourally identical to
its parent. That strengthens rather than weakens the rule below: the class is a user-authored
extension point, and the generator's only job is to leave it alone. (`EEnumSetBuilder64.Strict`
and `EEnumSetBuilderLarge.Strict` do exist and do re-check equality inside `addOrdinalChange`;
wiring the example's `TrackingStrict` to one is a possible Phase 4 example improvement, not a
generator capability, and is **not** required by this plan.)

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
- **Ordering trap to respect (new):** `exec-maven-plugin` invokes the *installed* tooling, and the
  recorded command runs **offline** (`mvn -o`, § 0.3/0.4). So the example module cannot run
  generation until `hipster-entity-tooling` has been `install`ed. Sequence it explicitly:
  `mvn -o -pl hipster-entity-tooling -am install` **before** the first offline
  `… -pl <six modules> -am test` that triggers generation, and add a note to the example
  module's README. If this proves brittle in practice, the fallback is a
  `maven-invoker-plugin`-driven verification or dropping `-o` for the generation step — decide
  empirically in Phase 4, not now.
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
| **Writability** | Generated setters/binders exist only for fields that are `FieldKind.COLUMN` **and not `retired()`** (S1, § 4.6/R1.4, § 4.7/DR-2). A `DERIVED`/`JOINED` field is read-only, and an `INSERT`/`UPDATE`/binder must never include it or a tombstone. |
| **No new runtime module** | Adapters/mappers/validators are **generated source**, committed per S2. They may use `hipster-entity-core` for the positional-array primitives — the same dependency S3's deferral already accepts. A generated JDBC adapter uses the JDK's `java.sql` only; **no JDBC driver and no connection-pool dependency enters any library POM.** |
| **Failures are diagnostics, not silence** | Where a mapping or constraint cannot be expressed (divergent types, an unsupported constraint annotation), emit the DEC-022 diagnostic and generate nothing for that field — never a lossy cast and never a silently dropped field. |

**G6 — `@View.addons` semantics (the last attribute without a rule).**

`View.addons()` exists (`View.java:16`) and two example views use it
(`person/entity/Person.java:7` → `PersonAuditable.class`; `paymentMethod/entity/PaymentMethod.java:13`
→ `PaymentMethodAuditable.class`), but **the attribute has no semantics anywhere in the
codebase**: a repo-wide search finds it only in the annotation declaration and those two uses.
§ 8.1/3.1–3.2 parse it and § 6.3/1.12 validates it, so the *emission* rule must be fixed here or
every implementer invents a different one. Final rule:

| Concern | Rule |
|---|---|
| **What an addon contributes** | The addon interface's accessors — its own declarations plus whatever its parents resolve to among the **indexed** sources (see X3's generation-vs-indexing split) — are **merged into the view's field list**. That is the only reading under which the attribute has any effect; an addon that contributes nothing would be indistinguishable from a typo. |
| **Where the fields go** | Appended **after** all of the declaring view's own and inherited accessors, in the addon's declaration order, with multiple addons in their listed order. This is the only placement compatible with R1 (§ 4.6): ordinals are append-only, so an addon field may never be inserted into the middle of an existing field run. Concretely, the example's declaring view is `PersonDetails`: its own run `{id, firstName, lastName, email, phoneNumber}` stays at ordinals 0–4 and the addon's `createdAt`/`updatedAt` land at 5 and 6. `PersonSummary_.{id, firstName, lastName, age, departmentName, metadata}` stays at 0–5 because it declares no addon (§ 4.7/DR-1). |
| **Propagation** | **Per view — no propagation (§ 4.7/DR-1).** An `addons` declaration applies to the interface that carries it and to nothing else: a subtype does **not** inherit the addon's fields. A view that physically carries the addon's columns must declare `addons = …` itself. Rationale: the addon is a property of one materialization, so a view's field list stays something a reader can derive from that view's own source; a subtype's positional array is then free to differ from its parent's, which R1 already permits because addon fields append at the end of *the declaring view's* run. `@View(addons = …)` on a non-view interface (a marker — `person.entity.Person`, `paymentMethod.entity.PaymentMethod`) has **no effect**: it is reported as the DEC-022 diagnostic `addon_on_non_view` and removed in Phase 4 (§ 9/4.1, § 9/4.9). A view cannot opt out of an addon it declared; removing the declaration is the R1.4 field-removal path. |
| **Collisions** | An addon accessor whose name already exists on the declaring view (own or inherited) is skipped, the view's own declaration wins, and a DEC-022 diagnostic is emitted. Never a duplicate enum constant — that would not compile. This is the common case: `PersonAuditable` inherits `id`/`firstName`/`lastName` from `Person`, so declaring it as an addon on `PersonDetails` appends only `createdAt`/`updatedAt`. |
| **Writability** | Addon fields follow X2's defaults like any other field: unannotated means `COLUMN`, i.e. writable under S1. A read-only audit field is expressed by annotating the accessor **on the addon interface**; no special case is added for fields named `createdAt`/`updatedAt`. |
| **Generation of the addon itself** | Unchanged and orthogonal: view discovery is **marker-derivation *or* `@View`** — rule 0 of **G8** (§ 4.5), of which only the marker half exists in `EntityMetadataGenerator.java:328-333` today — and `@View` separately supplies `gen`/`discriminatorField`/`addons`. Being a view is what decides whether an enum is emitted; the **`addons` attribute** does not. An addon that derives from a marker is generated as a view in its own right (`PersonAuditable` is); an addon that does not is a field source only and gets no enum. Under X3's `packages` filter this is scoped by **marker-package ownership** (G8 rule 0), not by where the view file itself sits: `person.entity.PersonAuditable` — which derives from the admitted `person.entity.Person` marker as well as from `example.Auditable` — is generated as a view in its own right, while `PaymentMethodAuditable` gets **no** emitted enum even though its own file is in the admitted `paymentMethod.entity` package, because its **only** marker (`example.Auditable`) sits in the filtered-out `example` package, and a marker in a filtered-out package contributes indexing only and generates nothing. (Gate-review correction, GR-4: the earlier wording said `PaymentMethodAuditable` "lives in a package outside the filter" — it does not; the exclusion is by marker, § 4.7/DR-5.) |
| **Removal** | Dropping an addon declaration from the declaring view is a field removal, so R1.4 applies: the constants become `@Deprecated` tombstones reporting `retired()` (§ 4.7/DR-2), never deletions. |
| **Diagnostics** | An `addons` entry that cannot be resolved to an interface, an addon declaration on a **non-view** interface (`addon_on_non_view`, § 4.7/DR-1), and an addon accessor that collides, are DEC-022 diagnostics surfaced by the same `DivergenceReporter` path as the other field-set divergences (§ 8.7/3.20) — never a silent skip. |

Consequence for Phase 4, stated so it is not mistaken for a generator bug, and **rewritten for
per-view resolution (§ 4.7/DR-1)**:

- `person/entity/PersonAuditable_.java` (34 lines) currently **omits** `createdAt()` /
  `updatedAt()`. It gains them because `PersonAuditable extends Person, Auditable<Long>` and
  **inherited accessors are already collected** — `EntityMetadataGenerator.collectViewProperties`
  walks the `extends` chain, and the hand-written `PersonDetails_` proves it
  (`id`/`firstName`/`lastName`/`email`/`phoneNumber`). This is the *structural* path
  (`example/Auditable.java:8-9`) and is **unaffected** by the addon decision.
- `PersonSummary_`, `PersonDto_`, `PersonCreateForm_`, `PersonUpdateForm_` and
  `PersonUpdatableView_` gain **nothing** from addons: they do not extend `Auditable`, no longer
  inherit the marker's declaration, and must not be expected to carry audit columns. Ordinals
  0–5 of `PersonSummary_` stay exactly as `§ 4.5` describes.
- `PersonDetails_` is the example's **deliberate addon use**: it declares
  `@View(addons = {PersonAuditable.class})` (it does not extend `Auditable`), so `createdAt` /
  `updatedAt` are appended after its existing `id`/`firstName`/`lastName`/`email`/`phoneNumber`
  run (ordinals 5 and 6), while the addon's `id`/`firstName`/`lastName` collide with its own and
  are skipped with the collision diagnostic. The feature is therefore exercised by real generated
  code, and no existing ordinal moves.
- The two **markers** drop their inert declarations: `person/entity/Person.java:7` and
  `paymentMethod/entity/PaymentMethod.java:13` carry `@View(addons = …)` on interfaces that are
  not views (they get no `_` enum), so under per-view resolution they do nothing. Phase 4 removes
  both and the generator reports `addon_on_non_view` while they are still present. The
  `paymentMethod` family consequently exercises `discriminatorField`/permitted subtypes
  (§ 9/4.9), not addons.
- `example/PersonAuditable_.java` and `example/PaymentMethodAuditable_.java` are **deleted** in
  Phase 4 (§ 4.7/DR-5): both are stale, wrong-shaped (`getPropertyType()`, no `FieldDef`) and
  unreferenced **[verified]**. `PersonAuditable_` is superseded by the generated
  `person.entity.PersonAuditable_`; `PaymentMethodAuditable_` describes an interface that is not a
  view of the `PaymentMethod` entity (it extends only `Auditable<Long>`) and, although it *is* a
  marker-derived view under G8, its only marker sits in the filtered-out `example` package, so no
  enum is emitted for it and nothing replaces the deleted file (gate review, GR-4).

Add a generator fixture test (a view plus a two-accessor addon) that asserts the merged order, the
collision diagnostic, **and that a derived view does not receive the addon's fields** — that last
assertion is what makes the per-view rule binding.

**G7 — The R1 ledger exists only for enums that already carry the marker (§ 4.7/DR-7).**

R1's append-only preservation and R1.4's tombstone rule protect a *committed ordinal contract*.
They apply to exactly those field enums that **already carry the `entityFieldEnum:true` marker**
(R1.2) in their DEC-021 header. An existing enum **without** the marker has no such contract — it
is pre-R1, hand-written source — and is therefore **bootstrapped as a fresh ledger**:

1. the constant list is rebuilt from the view's resolved field list, in ordinal order;
2. constants that no longer correspond to any field **are dropped**, not tombstoned;
3. the DEC-021 header (including `entityFieldEnum:true`) is written in the same pass, so the
   *next* generation of that file is protected by R1.

This is the bootstrap path, and it is what makes Phase 4 possible: the example's enums are
marker-less and bug-contaminated — `PersonSummary_.java:14-15`, `PersonDto_.java` and `Write_.java`
carry the spurious `toBuilder`/`toBuilderTracking` constants (the § 2.3 `default`-method leak) and
`PersonUpdatableView_.java:13` carries a spurious `changes` constant. G7 is why § 9/4.1 can expect
all of them to *disappear* (DoD #3) instead of surviving as deprecated tombstones with nullable
record components and an inflated `fieldCount` — which is what a literal reading of § 8.3/3.7a
would otherwise produce.

Consequences, stated so they are not re-litigated:

- **The first generation of any file is always a fresh ledger** — there is nothing to preserve.
  The append-only rule first binds on the *second* pass, once the marker is present. A brand-new
  view never produces a tombstone, which is what § 4.6/R1.4 already claims.
- **Opting a hand-written enum into R1 is manual.** A consumer who already persists positional
  arrays but has not adopted R1 adds `entityFieldEnum:true` to the header **by hand** before the
  first generation pass; that puts the enum under R1 and forces tombstoning instead of dropping.
  The generator never infers a ledger from a pre-existing file.
- **`allowReorder: true` is not required for the bootstrap path.** Dropping a constant from an
  enum that never carried the marker is not a reorder of a committed ledger. The drop is still
  reported as a DEC-022 divergence (`enum_constant_removed`, action `bootstrap: dropped pre-R1
  constant`) so it appears in the regeneration report instead of happening silently.
- **A malformed or unparseable header is not "unmarked".** Per R1.2 a malformed marker is a
  diagnostic; the safe failure mode is to treat the file as **marked** and refuse to drop
  constants, never to treat it as bootstrap and delete a live ordinal.

**G8 — View discovery: the predicate, the framework surface, and the generator's own nested output (§ 4.7/DR-8).**

View discovery today is marker-only — every interface transitively derived from a package's marker
is a view (`EntityMetadataGenerator.java:328-333`) — and the scan covers every source under the
source root, including the generator's own committed output (S2 puts generated source in
`src/main/java`). Three rules are needed, or the generator will miss a view the rest of the plan
expects and will rediscover its own framework surface:

0. **The view predicate.** A type is a view iff (a) it derives transitively from the package's
   marker, **or** (b) it carries a `@View` annotation **in any syntactic form** — marker, empty, or
   with pairs; the check is shape-blind, `getAnnotationByName("View").isPresent()`, never a cast to
   `NormalAnnotationExpr` (G9, § 4.5) — and is neither the marker itself (§ 9/4.1)
   nor a surface excluded by rules 1–2 below. **This is a correction to the earlier draft**, which
   described discovery as marker-only while simultaneously listing `PersonCreateForm` among the
   views and expecting a newly generated `PersonCreateForm_`: `PersonCreateForm` carries `@View()`
   but extends nothing marker-derived (verified — `person/entity/PersonCreateForm.java`), so the
   marker-only predicate excludes it, and only predicate (b) reproduces § 9/4.1's arithmetic
   ("seven views, of which five carry `@View`"). The `@View` seed is **new work in task 3.2a**:
   it is also why the tree contains the marker-derived `Write_.java` but no `PersonCreateForm_.java`
   today. `@View` on the marker itself stays inert, and X3's `packages` filter still decides which
   discovered views are *generated* (so `person/iface/Person`'s `@View(gen = META)` seeds a view
   that is filtered out and emits nothing).
   **One emission per view, even when two markers apply.** A view can derive from two markers —
   the example's `PersonAuditable extends Person, Auditable<Long>` does, and both are
   `EntityBase`-derived markers (`person.entity` and `example/`). The view belongs to the marker
   whose package the `packages` filter admits, and is emitted **once**; a marker sitting in a
   filtered-out package contributes indexing only (so its accessors still resolve — X3) and
   generates nothing, not even for views in admitted packages. This keeps Phase 4's output
   deterministic and prevents a duplicate write of the same `_` enum from two marker loops; the
   G8 fixture asserts a single emission for a two-marker view.

The two consequences that must be ruled on explicitly:

1. **The framework surfaces are not views.** An interface that extends `ViewReader`, `ViewWriter` or
   `ViewChangeTracking` is a write/tracking *surface*, and gets **no** `_` enum, `META`, record or
   builder, even though it also derives from a marker. The live witness is the example's
   `person/entity/PersonSummary.Write extends PersonSummary, ViewWriter` — discovered as a view
   today, which is why the orphan `Write_.java` exists with exactly `PersonSummary_`'s constants
   (the bogus ones included). The same applies to a helper that derives from a marker but declares
   no accessors of its own: it is indexed for resolution (X3) and emits nothing.
2. **Never descend into a type the generator emitted.** A nested `Write`/tracking surface inside a
   generated view file must not become a discovery input on the next pass. Discovery skips nested
   type declarations inside an interface that is itself a view. Combined with (1) this makes
   regeneration a fixed point: the pass that emits the nested `Write` for `PersonSummary` is not
   followed by a pass that re-emits it or generates a `Write_` for it. This is also what prevents
   `Write` nesting inside `Write` (which § 8.5/3.12 would otherwise recurse into) and the
   `Write_.java` simple-name collision at `EntityMetadataGenerator.java:344-346`.

Consequence for Phase 4 (§ 9/4.1), stated so the expected file list is unambiguous:
`person.entity` has **seven discovered views plus one excluded framework surface** —
`PersonAuditable`, `PersonCreateForm` (seeded by rule 0(b)), `PersonDetails`, `PersonDto`,
`PersonSummary`, `PersonUpdatableView`, `PersonUpdateForm` (seven), with `PersonSummary.Write`
excluded by rule 1 and `Person` excluded as the marker. Of those seven, five carry `@View`
(`PersonAuditable`, `PersonCreateForm`, `PersonDto`, `PersonSummary`, `PersonUpdateForm`) — which
is exactly § 9/4.1's count, now derived rather than asserted.
`Write_.java` is a stale artifact of the pre-G8 discovery rule and is **deleted** in Phase 4 (grep
confirms only its own self-declarations reference it — scope that grep to the working tree, GR-6,
§ 4.1/S2) — not regenerated, not tombstoned, because
its source interface is no longer a view. The `paymentMethod` family is unaffected: none of its
four subclasses extends a write/tracking surface.

Add a discovery fixture test: one marker-derived view, one `@View`-annotated interface that derives
from nothing, one nested `Write`, and one marker-derived interface that extends `ViewWriter`;
assert that an `_` enum is emitted for each of the two rule-0 views, none for the surfaces, and that
a second generation pass is byte-identical.

**G9 — The `@View` syntactic-form contract: one reader, four shapes, no shape-restricted parsing (§ 4.7/DR-9).**

The earlier census counted only the two `@View(gen = …)` spellings, and § 8.1/3.2 specified the parse
as "read … from `NormalAnnotationExpr.getPairs()`". Both are too narrow: the tree carries **four**
syntactic shapes of the annotation, and two live views depend on the one shape that a
`NormalAnnotationExpr`-restricted parser drops. This is the census, verified against the working tree
(scope to the working tree — GR-6, § 4.1/S2):

| JavaParser shape | Live witness | Parsed result | Diagnostic |
|---|---|---|---|
| `MarkerAnnotationExpr` — bare `@View` | `person/entity/PersonDto.java:5`, `person/entity/PersonUpdateForm.java:5` | `gen = DEFAULT`, `discriminatorField = ""`, `addons = []` | none — but these are **2 of the 5** `@View`-carrying views in `person.entity`, and § 9/4.1 expects `PersonDto_` and `PersonUpdateForm_` as generated output |
| `NormalAnnotationExpr`, no pairs — `@View()` | `person/entity/PersonAuditable.java:6`, `person/entity/PersonCreateForm.java:5`, `paymentMethod/entity/BankTransferPaymentMethod.java:5`, `PayPalPaymentMethod.java:5`, `CreditCardPaymentMethod.java:6`, `CryptoPaymentMethod.java:6` | identical to the marker form | none |
| `NormalAnnotationExpr`, with pairs — `@View(gen = …, addons = {…})` | `person/entity/PersonSummary.java:13` (bare `GenLevel.BUILDER_ALL`), `person/iface/Person.java:7` (fully-qualified `hr.hrg.hipster.entity.api.GenLevel.META`), `person/entity/Person.java:7` + `paymentMethod/entity/PaymentMethod.java:13` (`addons` on the two markers) | pairs parsed | `addon_on_non_view` on the two markers (G6, § 4.7/DR-1); both stay inert |
| `SingleMemberAnnotationExpr` — `@View(true)` | **no live site**; it survives only in the legacy parser's comments (`EntityMetadataGenerator.java:449`, `:454`) | `gen = DEFAULT` | `unsupported_view_annotation_form` — the real `@View` has no `value()` member, so no such source can compile |

Binding rules:

1. **Discovery is shape-blind.** Predicate (b) of G8 rule 0 means exactly
   `decl.getAnnotationByName("View").isPresent()` — any shape, with or without elements, public or
   package-private. Discovery never casts an `AnnotationExpr` to a subtype. `person/iface/Person` is
   package-private and is excluded by X3's `packages` knob, **not** by anything in the parser, so the
   two mechanisms must not be conflated.
2. **One shared reader, introduced with its first consumer.** `ViewAnnotationReader.parse(AnnotationExpr)`
   (equivalently: the refactored `parseViewAnnotation`) is the only place a shape is inspected, and it
   is called by **both** the validator (§ 6.3/1.12) and the generator (§ 8.1/3.2) — the same
   one-owner discipline as DR-3. It lands **with task 1.12 in Phase 1**, because the validator is its
   first consumer, and § 8.1/3.2 then reuses it; this mirrors how `GenLevelResolver` lands with 1.12 and
   is reused by § 8.1/3.3. It returns `DEFAULT` when `gen` is absent, and **`GenLevelResolver` alone**
   resolves `DEFAULT` into `RECORD`/`BUILDER`/`META`.
3. **`gen` spellings all live, resolved by last segment.** Simple (`GenLevel.X`), fully-qualified
   (`hr.hrg.hipster.entity.api.GenLevel.X`), or absent (marker/empty form). Resolution compares the
   **last identifier segment** of the value expression against `GenLevel`'s constant names — no class
   loading, no import-table lookup. An unknown constant is the DEC-022 diagnostic `unknown_gen_level`
   and falls back to `DEFAULT`: never a crash, and never a silent `META`.
4. **`addons` spellings.** `{A.class, B.class}` (the only form in the tree), `{}`, or absent; strip a
   trailing `.class` and any package prefix, yielding simple names resolved against the package's
   `interfaceMap` (X3: indexing is not filtered, generation is). Unresolvable → `unresolved_addon`
   (G6) — never a silent skip.
5. **`discriminatorField`.** String literal; absent → `""`. The example supplies its discriminator
   through the hand-written `PaymentMethod_.` base enum and the four generated subclasses (§ 9/4.9),
   so no view in this tree needs the attribute to be non-default — the parser must still read it.
6. **No propagation, no nested seeding.** A subtype does not become a view by inheriting a
   `@View`-annotated supertype (Java annotations are not inherited on interfaces), and `@View` on a
   nested type declaration never seeds a view (G8 rule 2). Both already hold in the tree:
   `PersonUpdateForm extends PersonCreateForm` carries its **own** bare `@View`, while
   `PersonUpdatableView extends PersonUpdateForm` carries none and is marker-derived; the nested
   `PersonSummary.Write` is never a discovery root.

Acceptance tests, in the same commit that lands the reader (extend the G8 discovery fixture in task
3.2a and grow the § 0.5 compile harness): one fixture per shape above; an unknown `gen` constant; an
unresolvable addon; a `@View` on a nested type (must not seed a second view); and a **real-tree**
assertion that `person.entity` discovery yields seven views of which five carry `@View`, with
`PersonDto` and `PersonUpdateForm` present **because of the marker form** — precisely the case a
`NormalAnnotationExpr`-only parser drops.

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

The same marker scopes the **generator's** behaviour, not just the checker's: an enum that already
carries `entityFieldEnum:true` is preserved and tombstoned, while an existing enum that does not is
bootstrapped as a fresh ledger (**G7**, § 4.5). "Once an entity's field enum has been generated"
therefore means *once the generator's own output carries the marker* — not "once a file with that
name exists".

**R1.2 — The marker: opt-in, and it rides the existing DEC-021 header.**

*(The R1 rule itself is recorded in its own **standalone** DEC — § 4.7/DR-6, § 9/4.8. It reuses
DEC-021's header/JSON5 mechanism but is **not** an amendment of DEC-021, so the order contract can
be found without reading the generator-header decision.)*

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
- **The same marker scopes the generator's preservation behaviour (G7, § 4.7/DR-7).** The
  generator preserves constant order and emits tombstones only in an enum that already carries
  `entityFieldEnum:true`; an existing marker-less enum is bootstrapped as a fresh ledger and its
  stale constants are dropped (reported as `enum_constant_removed`, action `bootstrap`). This is
  what the legacy example enums in § 9/4.1 take, and why their leaked
  `toBuilder`/`toBuilderTracking`/`changes` constants disappear rather than becoming tombstones.
  The full rule, including the hand-edit opt-in for a consumer with an existing persisted layout,
  is § 4.5/G7.
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
| **Marker added in the target revision (bootstrap, G7)** | If the enum is **unmarked in the baseline** revision, the checker skips it for that comparison — opt-in by absence (R1.2). The bootstrap commit is the revision that *establishes* the ledger; the next commit is the first one the checker guards. Without this row the bootstrap commit would be reported as a mass removal. |
| **An addition** | Allowed, and only at the end. An addition in the middle is reported as a reorder, because that is what it is from the bitmask's point of view. |
| **Exit code** | Non-zero on any violation, so it can gate a build and a PR check. Warnings (e.g. `allowReorder` present) go to stderr without failing. |
| **CLI** | At minimum `--repo <path> --baseline <ref> [--target <ref>] [--diff <file>] [--strict]`. Wire it into the `hipster-entity-tooling` entry point alongside `EntityMetadataGenerator.main` **[verified, `EntityMetadataGenerator.java:186`]**. |

**R1.4 — Removed fields: keep the ordinal as a tombstone.**

**Marker scope first (G7, § 4.7/DR-7):** tombstoning applies only to an enum that already carries
`entityFieldEnum:true`. A marker-less existing enum has no committed ledger and is bootstrapped
fresh — its stale constants are dropped, not tombstoned — so the legacy example constants named in
§ 9/4.1 do not become tombstones. Everything below describes a marker-carrying enum.

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
- **A tombstone is marked `retired()` and skipped by every writer (§ 4.7/DR-2).** The generator
  emits `retired()` → `true` on the tombstone constant (new `FieldDef` accessor, § 4.3/X2), and
  every generated writer — the `Write`/builder setters, the `set(int)`/`set(String)` arms, the
  JDBC binder and the `INSERT`/`UPDATE` fragments (§ 12.1/7.2–7.3), and the mapper's source field
  list (§ 12.2) — **skips `retired()` fields**. `FieldKind` alone cannot express this: X2 defaults
  an unannotated constant to `COLUMN`, and a tombstone has no accessor left to carry
  `@FieldSource`, so without `retired()` the retired column would silently reappear in generated
  SQL. Readers stay tolerant — the slot still exists and may be `null` (a row without the column)
  or non-null (a row that still has it): `retired()` gates **writes only**.
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
- **Compaction** — dropping tombstones and renumbering — is **in scope as Phase 7.4 (§ 12.4)**,
  not deferred: § 3's scope table and § 4.4 both list it, and tasks 7.13–7.16 implement and
  document it. What stays out of scope is *automatic* compaction as part of a normal generation
  pass — it is a deliberate, separately invoked operation that may only run when no persisted
  array, patch, or snapshot survives. It requires its own **standalone DEC** before 7.14 starts
  (task 7.13) — the DEC-per-decision convention § 9/4.8 applies to R1, recorded as a separate
  record rather than an amendment of DEC-012/DEC-021 (§ 4.7/DR-6). This bullet
  previously read "a documented future migration, not a feature … record it in § 4.4", which
  contradicted Phase 7.4; that wording is retracted.

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

### 4.7 Decision rounds — the items a readiness review left open, plus the later review rounds

These were the only remaining places where an implementer would have had to make an authoring
decision, and the first of them (DR-1) was the one case where the plan itself asked the reader to
choose. **DR-1–DR-8 are now binding**, and each was applied **in place** in the subsection it
changes, so this table is an audit index rather than a second source of truth. DR-7 and DR-8 were
added by the **pre-execution review** (which also corrected the factual claims listed after the
table); their full rules live in § 4.5/G7 and § 4.5/G8. **DR-9** was added by the
**execution-readiness review** that followed the gate review; its full rule lives in § 4.5/G9.

| # | Question | Final rule | Applied in |
|---|---|---|---|
| DR-1 | Does an `addons` declaration propagate to views derived from the declaring interface? | **No — resolved per view.** `addons` applies to the interface that declares it and to nothing else; a subtype must declare `addons` itself. A declaration on a non-view interface (a marker) has no effect and is reported as the DEC-022 diagnostic `addon_on_non_view`. The `packages` knob filters **generation**, not **indexing**, so a cross-package addon or supertype stays resolvable. | § 4.5/G6 (contribution, propagation and generation rows, plus the Phase 4 consequence list), § 4.3/X3, § 9/4.1, § 9/4.9 |
| DR-2 | How is a tombstoned field kept out of generated writes? | **`FieldDef.retired()`** — a fifth `default` accessor in X2, `default boolean retired() { return false; }`. A tombstone emits `retired()` → `true`; builder setters, `set(int)`/`set(String)` arms, JDBC binder/`INSERT`/`UPDATE` and mapper sources **skip retired fields**; readers stay tolerant. | § 4.3/X2, § 4.6/R1.4, § 6.1/1.2, § 4.5/G5, § 12.1/7.2–7.4, § 6.4 test 25 |
| DR-3 | Who owns the `DEFAULT` resolution rule? | **One shared `GenLevelResolver`** in `hipster-entity-tooling`, called by both the validator (§ 6.3/1.12) and the generator (§ 8.1/3.3). No `DEFAULT` → `META` shortcut anywhere. | § 4.5/G1, § 6.3/1.12, § 8.1/3.3 |
| DR-4 | What is `S` in `ViewChangeTracking<E,S>` once `changes()` is the immutable accessor? | **`EEnumSet<View_>` — the interface, not `EEnumSet64`** (corrected by the execution review; the earlier answer named `EEnumSet64`, which cannot compile). `toImmutable()` returns `EEnumSet<E>` and yields the cached `EEnumSetEmpty` singleton when empty, and `EEnumSet64` is `final` and a sibling of `EEnumSetEmpty`, so `EEnumSet64` is unachievable and a cast would throw on a fresh view; narrowing `toImmutable()` is barred by § 6.2/1.10's allocation-free empty case. Generated and example tracking builders declare `ViewChangeTracking<View_, EEnumSet<View_>>` (`changes()` → `mf.toImmutable()`, `changesBuilder()` → the live `EEnumSetBuilder64<View_>`); the array base implements `ViewChangeTracking<F, EEnumSet<F>>`, which is what its `changesSnapshot()` already returns; the § 6.4 parity fixture uses `EEnumSet<…>` too. The example's `implements` clause is **re-typed**, not merely renamed. | § 4.1/S5, § 6.2/1.7, § 6.4, § 8.6/3.14, § 13 |
| DR-5 | What happens to the stale hand-written enums under `example/`? | `example/PersonAuditable_.java` and `example/PaymentMethodAuditable_.java` are **deleted** in Phase 4 (wrong shape, superseded, unreferenced); `example/Auditable` and the two `*Property` enums stay hand-written. Nothing replaces `PaymentMethodAuditable_`: the interface is a marker-derived view under G8, but its only marker (`example.Auditable`) sits in a filtered-out package, so it emits no enum (gate-review correction, GR-4 — its own file *is* in the admitted `paymentMethod.entity` package, § 4.5/G6). | § 9/4.1, § 4.5/G6 |
| DR-6 | Where are the new binding rules recorded? | **Each gets its own standalone DEC**: R1 (§ 9/4.8), field-enum compaction (§ 12.4/7.13), nested/deep tracking (§ 11/6.1). R1 still *uses* DEC-021's header/marker mechanism, but its order contract is not recorded by amending DEC-021. | § 9/4.8, § 12.4/7.13, § 11/6.1 |
| DR-7 | Does R1's append-only/tombstone rule apply to an existing enum that does not yet carry the `entityFieldEnum` marker (the legacy example enums)? | **No — the ledger is marker-scoped.** Preserve and tombstone only in enums that already carry `entityFieldEnum:true`; a marker-less existing enum is **bootstrapped as a fresh ledger**, its stale constants are **dropped** (the leaked `toBuilder`/`toBuilderTracking`/`changes`) and reported as `enum_constant_removed` with action `bootstrap`. Adding the marker by hand is the sole opt-in that forces tombstoning. A malformed header counts as marked (fail safe). | § 4.5/G7, § 4.6/R1.1 + R1.2 + R1.3 + R1.4, § 8.3/3.7a, § 9/4.1 |
| DR-8 | What makes a view, and may the generator rediscover its own nested output? | **Predicate: marker-derivation *or* `@View`, minus the marker and minus the framework surfaces.** The `@View` seed is new (task 3.2a) and is what makes `PersonCreateForm` a view and `PersonCreateForm_` a real output; interfaces extending `ViewReader`/`ViewWriter`/`ViewChangeTracking` are **not** views, and discovery never descends into nested types of a view file, so exactly one `_` enum is emitted per view and regeneration is a fixed point. The orphan `Write_.java` is **deleted** in Phase 4, not regenerated. | § 4.5/G8 (+ G6), § 8.1/3.2a, § 8.7/3.19, § 9/4.1 |
| DR-9 | Which `@View` syntactic forms must the parser accept, and where is that fixed? | **All four shapes the tree contains** — bare marker `@View` (`PersonDto.java:5`, `PersonUpdateForm.java:5`), empty `@View()`, pairs `@View(gen = …, addons = {…})`, and the retired single-member `@View(true)` — behind one **shape-blind** discovery check and one shared `ViewAnnotationReader` used by both the validator and the generator. `gen` resolves by last identifier segment (simple or fully-qualified); an unknown constant emits `unknown_gen_level` and falls back to `DEFAULT`; no propagation to subtypes and no nested seeding. A `NormalAnnotationExpr`-only parser silently drops `PersonDto_` and `PersonUpdateForm_`, i.e. the two views § 9/4.1 expects. | § 4.5/G9, § 8.1/3.2 + 3.2a, § 6.3/1.12 |

Three factual corrections from the second decision round are applied in place rather than logged here:
S2's claim that `plans__*` is git-ignored (**they are tracked**), G3's claim that `TrackingStrict`
re-injects `EEnumSetBuilder64.Strict` (**it does not**), and the JMH harness's `Enum64` vs `E64`
argument in § 6.2/1.4 and the risk register. DoD #7 was narrowed to X3's example scope.

The **pre-execution review** added DR-7/DR-8 above and applied a further set of factual corrections
in place, so the evidence base matches the tree: the tooling has **four** validation rules, not five
(§ 2.1); **five** modules lack a `dependencyManagement` entry, not two, though only the two
metadata modules are referenced versionless (§ 2.2, § 0.2); the **JMH benchmark is a caller** of the
renamed/deleted accessors — eight sites (§ 4.1/S5, § 13); the two doc-sample `Person` files declare
the **same** `name`/`email` fields and `record/Person.java` is a record (§ 4.3/X3); JavaParser is
already pinned **centrally** (§ 15); the `javax.tools` compile harness **already exists** inside
`EntityMetadataGeneratorTest` (§ 0.5); and § 16's "stale validator message" belongs to § 6.3/1.12,
not § 9/4.7.

The **execution review** then corrected one *binding* decision and two task specifications in place,
because the earlier text could not be executed as written. **DR-4** (above) now types `S` as the
`EEnumSet` interface: `EEnumSetBuilder64.toImmutable()` is declared `EEnumSet<E>` and returns the
cached `EEnumSetEmpty` singleton when the set is empty, while `EEnumSet64` is `final` and a
*sibling* of `EEnumSetEmpty` — so the previously specified `EEnumSet64<View_>` was unachievable, a
cast would have thrown `ClassCastException` on a fresh tracking view (failing § 6.4 test 8, the
first test of the S5 suite), and narrowing `toImmutable()` is barred by § 6.2/1.10's allocation-free
empty case. § 4.1/S5, § 6.2/1.7, § 6.4, § 8.6/3.14 and § 13 carry the corrected type. **§ 6.2/1.4**
keeps `ForNameOrdinal` in the new constructor and factory signature — it is the only route from
`set(String,Object)` to an ordinal, and D5 / test 18 pin the `-1` contract it produces — and now
*corrects* the arity check instead of claiming the tracking array lacks one: it has one
(`EntityUpdateTrackingArray.java:24-26`, same message as `EntityReadArray`), and what it lacks is a
check against the enum universe, which the new `F[] universe` parameter makes possible. **§ 6.2/1.6**
now states how the array path feeds the `ChangeRecorder`, which the earlier draft left implicit:
`set(int,Object)` overwrites `values[ordinal]` before marking, and `mark(int)` carries no
previous value, so the write must go through `addOrdinalChange(ordinal, previous, value)`.

Four further factual corrections are applied in place: `discriminatorField` support **is** exercised
by `FieldBoilerplateGeneratorTest.java:35-36,48-49` (§ 9/4.3); `mvn` on `PATH` is a wrapper script,
`D:\programs\cmd\mvn.bat`, that delegates to `mvnd`, and a green run has been observed to return exit
code `0` as well as the documented `1` (§ 0.1a, § 0.4); the plans-related entry in `.gitignore` is
`.kilo/plans` among many others (§ 4.1/S2); and `@View(gen = …)` occurs in the tree in **both**
forms — fully-qualified on `person/iface/Person.java` and bare on
`person/entity/PersonSummary.java:13` — so § 8.1/3.2's parser must accept both (§ 4.3/X3). § 12.3/7.10 now pins `jakarta.validation-api` to the version the local
repository holds, so the offline build keeps working. Finally, § 4 above now states that a DEC
counts as accepted when it is written, committed and indexed — the gates in tasks 6.1 and 7.13 are
self-accepted design steps, not a wait for sign-off.

The **gate review** is the fourth round. It reconciled the phase exit gates with the tests they name
and applied six corrections in place, numbered **GR-1**–**GR-6** so they cannot be confused with
§ 14's conflict register IDs `C1`–`C12`. None of them is a new decision, and none leaves a choice
open: each is either a gate arithmetic fix, an instruction that could not be executed as written, or
a factual premise that was wrong.

**GR-1 — the Phase 1 exit gate is satisfiable again.** § 6.4 test 25 cannot be green in Phase 1: it
asserts the generator's tombstone emission (§ 8.3/3.7a, **Phase 3**) *and* that a `retired()` field
appears in no JDBC binder/`INSERT`/`UPDATE` (§ 12.1/7.2–7.4, **Phase 7**). Test 13's textual
assertion is likewise vacuous until the emitter produces setters at all (**Phase 3**) — before that
it passes on an empty file set. § 6.4's exit gate and § 13's Phase 1 row now scope Phase 1 to tests
1–12 and 14–24; test 13 is asserted at the Phase 3 gate, and test 25 in two halves — generator at
Phase 3, JDBC at Phase 7.

**GR-2 — the old factory is deleted, not kept as a delegating overload.** § 6.2/1.4 and the risk
register previously said to keep `create(ForNameOrdinal, int, Object...)` as a *deprecated delegating
overload*. No correct delegation can exist: the missing enum universe is exactly what that parameter
list cannot supply, which is the § 2.4 defect itself, so any such overload could only reproduce the
`null`. A repo-wide search also finds **no callers at all** — the only references are the factory's
own body (`EntityUpdateTrackingArray.java:34-35`) and the benchmark constructor calls below — so the
condition ("if anything outside this plan still calls it") is already false and the old signature is
deleted outright.

**GR-3 — the benchmark census is complete.** The harness constructs the tracking arrays at **four**
sites, not two: `EEnumSetTrackingJmhBenchmark.java:98, 115, 167, 188`. Only `:98`/`:115` take their
field count from the *universe* enums `Enum64`/`E96`; `:167`/`:188` already pass the **`FieldDef`**
enums `E64`/`E96`. Every site must end up passing the `FieldDef` constant array as the new `universe`
argument, in the same commit that changes the constructors.

**GR-4 — the `PaymentMethodAuditable` premise is corrected** (§ 4.5/G6, § 4.7/DR-5). The file is
`hr.hrg.hipster.entityexample.paymentMethod.entity.PaymentMethodAuditable`, i.e. **inside** X3's
admitted `paymentMethod.entity` package — it does not "live in a package outside the filter". Its
exclusion from generation follows from **marker-package ownership** (G8 rule 0): its only marker,
`example.Auditable`, sits in the filtered-out `example` package. As written before this round, an
executor implementing a per-file package filter would have emitted a `PaymentMethodAuditable_` that
§ 4.7/DR-5 and § 9/4.9 say must not exist.

**GR-5 — the `@View(gen = …)` census is corrected** (§ 4.3/X3, this section). There are two live
instances, not one: the fully-qualified form on `person/iface/Person.java` and the bare
`@View(gen = GenLevel.BUILDER_ALL)` on `person/entity/PersonSummary.java:13`. § 8.1/3.2's parser must
accept both; the requirement is stronger than the earlier "only the fully-qualified form is live"
wording implied, and testing only one form would be a false pass.

**GR-6 — a second checkout is on record, and two line citations are fixed.** The git worktree
`.kilo/worktrees/snapdragon-motorcycle/` holds a full copy of this tree — the same `@View` sites,
the same orphan `Write_.java` — so every repo-wide scan or grep a task uses as evidence (§ 9/4.1's
`Write_` and stale-enum deletions in particular) must be scoped to the working tree. It is invisible
to `git status --porcelain`, so S2's isolation argument is unaffected. The versionless sibling
dependencies are `project-automation/pom.xml:47,51` (§ 2.2, § 0.2) and `metadata-mcp-server/pom.xml:23`
(§ 0.2), not the `:45,49` / `:21` cited before this round; the failure they cause was re-verified.

With GR-1–GR-6 applied, the claim in § 4 that no task requires an unfixed authoring decision mid-flight
holds as written.

An **execution-readiness review** — run after the gate review and before implementation started —
added one further gap, **G9/DR-9** (§ 4.5/G9): the `@View` census in GR-5 and the parse spec in
§ 8.1/3.2 covered only the parenthesized forms, while the tree carries **four** shapes. The bare
`@View` marker form is live on `person/entity/PersonDto.java:5` and
`person/entity/PersonUpdateForm.java:5`, which are 2 of the 5 `@View`-carrying views that § 9/4.1
expects to generate, so a `NormalAnnotationExpr`-restricted parser would have silently dropped
`PersonDto_` and `PersonUpdateForm_`. GR-5's "two forms" wording therefore means *two `gen`
spellings*, not two shapes. The same review restored an accurate reading of GR-6's line citations:
Maven 3.9 reports the three `dependencies.dependency.version … is missing` errors against the
enclosing `<dependency>` tags at `project-automation/pom.xml:45,49` and
`metadata-mcp-server/pom.xml:21`, while `47,51` / `23` are the `<artifactId>` lines § 2.2 cites for
the versionless references; both pairs are correct for what they describe, and § 0.2 must not be read
as locating the errors at 47/51/23.

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
  `mvn` on `PATH` is `D:\programs\cmd\mvn.bat`, a two-line wrapper around
  `mvnd --raw-streams %*` (`D:\programs\mvnd\bin\mvnd.exe`) — i.e. **mvnd 1.0.0-m4 / Maven
  4.0.0-alpha-4**, with the daemon's `java.home` pinned to jdk-25 by `~/.m2/mvnd.properties`. An
  **Apache Maven 3.9.0** sits at `D:\programs\mvn\bin\mvn.cmd`. Record which one § 0.4's command
  uses. Two consequences:
  Phase 0.2's POM-validation failure **does not reproduce under Maven 4** (verified green), only
  under 3.9; and the mvnd wrapper has been observed both to print its version and exit **non-zero**
  (`Environment mismatch … NoSuchFieldException: fs`) **and** to return `0` on a green run, so a
  raw `$LASTEXITCODE` gate is unreliable in both directions — gate on the build outcome, not on the
  launcher's exit code.
- [ ] **0.2 Fix root reactor POM validation.** Add `<dependencyManagement>` entries for
  `hr.hrg.jcodebuddy:metadata-server:${project.version}` and
  `hr.hrg.jcodebuddy:metadata-mcp-server:${project.version}` — **[verified]** both are missing,
  and they are the only two modules referenced *versionless* by a sibling (see § 2.2, which also
  records the pre-execution correction: five modules lack an entry in total, but only these two
  are referenced versionless, so only these two need adding). **[verified]** the failure reproduces on **Apache Maven
  3.9.0** (three `dependencies.dependency.version … is missing` errors: `project-automation` ×2 at
  `pom.xml:47,51`, `metadata-mcp-server` ×1 at `pom.xml:23`; gate review, GR-6: line numbers
  corrected, the failure re-verified) and **not** on mvnd/Maven
  4.0.0-alpha-4, where the same reactor parses and `mvn -o validate` is green — Maven 4 resolves
  versionless inter-module reactor dependencies, 3.9 does not. Fix it anyway: the entries are
  genuinely missing and a contributor on Maven 3.9 is blocked before compilation starts.
  Alternative if the modules do not build: move them out of `<modules>` temporarily, and record
  that as a deliberate scoping decision.
- [ ] **0.3 Scope the reactor to the six modules — with `-pl`, not with a profile.**
  **[verified, empirically]** a Maven profile **cannot** narrow the reactor: profiles only *add*
  modules, so a `<profile><id>hipster-entity</id><modules>…</modules></profile>` still builds
  every base `<modules>` entry (`project-automation`, `metadata-server`,
  `metadata-mcp-server`, …). This was reproduced on both launchers — Apache Maven 3.9.0 and
  mvnd/Maven 4.0.0-alpha-4 — with a minimal two-module probe: activating a profile that lists
  only module `a` still built `b`. The earlier wording ("add a `hipster-entity` profile … so
  `mvn -Phipster-entity -o test` works from the root without parsing `project-automation`") is
  therefore **not implementable**, and the plan must not promise it.
  Do this instead: keep using the explicit module list
  (`mvn -o -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example -am test` —
  the command § 0.4 records) and wrap it in the JDK-25 run script created by § 0.1, e.g.
  `scripts/mvn-jdk25.cmd`/`.sh` with a `hipster-entity` argument. A profile may still be added
  as a *marker* for `-P` documentation, but nothing may depend on it for scoping. Note that
  `-pl … -am` still parses the whole root POM (the parent appears in the reactor), so § 0.2's
  `dependencyManagement` fix remains the real unblocker for Maven 3.9.
  Note also the G4 ordering trap (§ 4.5): generation runs through `exec-maven-plugin` against the
  *installed* tooling, so `mvn -o -pl hipster-entity-tooling -am install` must precede the first
  generation-triggering build.
- [ ] **0.4 Record the baseline** (test counts per module, exact command, JDK) in a committed
  note. This is the regression baseline for every later phase. **[verified]** on this tree, with
  `JAVA_HOME` = jdk-25 and mvnd/Maven 4.0.0-alpha-4, the command
  `mvn -o -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example -am test`
  is BUILD SUCCESS with counts `api` 0, `core` 42, `tooling` 22, `jackson` 0, `test` 4,
  `example` 0 — 68 tests total. Re-record the numbers if § 0.1a's recorded launcher or JDK differs.
  **Re-verified during plan review** on the same tree and launcher (mvnd 1.0.0-m4 /
  Maven 4.0.0-alpha-4, daemon `java.home` = `C:\Program Files\Java\jdk-25`, with `JAVA_HOME` set to
  the same jdk-25): BUILD SUCCESS, all six modules SUCCESS, the same `42 / 22 / 4` per-module
  counts. Two notes for whoever re-records it. The `mvnd` wrapper has been seen to report **exit
  code 1 while the build was green** (§ 0.1a), but the review's run returned `0` — gate on the
  build outcome, never on `$LASTEXITCODE`. And the `test` count of 4 is the number surefire *runs*:
  the module also contains a fifth `@Test` in `PersonSummaryFileBenchmarkRunner`, which surefire's
  default includes do not match, so a count taken from annotations reads 5 and looks like drift.
- [ ] **0.5 Add a test-compilation gate before any generator work.**
  `hipster-entity-tooling`, starting as `GeneratedSourceCompilesTest`: write a fixture source
  root to a JUnit `@TempDir`, run `EntityMetadataGenerator.generate(...)`, assert the emitted
  file set, then compile the emitted sources with `javax.tools.JavaCompiler` against the real
  `api` + `core` classpath and assert **zero diagnostics**. This test is what closes the
  doc—code gap permanently and it must grow with each level.
  **Correction (pre-execution review):** a `javax.tools` compile harness already exists inside
  `EntityMetadataGeneratorTest.compileSources` (`:318-343`, driven by `:272-298`, classpath =
  `hipster-entity-api/target/classes` + `hipster-entity-core/target/classes`). Task 0.5
  **factors that harness out** into the named `GeneratedSourceCompilesTest` and grows it; it does
  not write a compiler harness from scratch.
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
- [ ] **1.2** Add the five accessors to `FieldDef` (X2, § 4.7/DR-2) with defaults that preserve
  current behaviour — `FieldSource` is **already** `@Retention(RetentionPolicy.RUNTIME)`
  **[verified, `FieldSource.java:15`]**, so the retention is a no-op and the real work here is
  `FieldDef` —
  `default FieldKind fieldKind() { return FieldKind.COLUMN; }`,
  `default String column() { return null; }`, `default String relation() { return null; }`,
  `default String expression() { return null; }`,
  `default boolean retired() { return false; }`. A field with no annotation is therefore
  `COLUMN`, i.e. writable, which is exactly today's behaviour; `retired()` is `true` only on an
  R1.4 tombstone, where it is the write-side gate that `FieldKind` cannot express.
- [ ] **1.3** Realize the `View` write-mode contract from `@FieldSource` alone (S1/D6): no new
  annotation attribute. `FieldKind` decides writability; `column()`/`relation()`/`expression()`
  are labels and must not be confused with it.

### 6.2 `hipster-entity-core` — the blocking NPE, plus the missing contract

- [ ] **1.4 Fix the tracking-array constructor (highest-value commit in the plan).**
  Change the factory to take the field universe, which `ViewMeta` already exposes, instead of
  reflecting a `Class`, **without dropping the `ForNameOrdinal`** that the name-based write path
  needs:

  ```java
  protected EntityUpdateTrackingArray(ForNameOrdinal forNameOrdinal, F[] universe, int fieldCount, Object[] values)
  public static <T, F extends Enum<F> & FieldDef>
      EntityUpdateTrackingArray<T, F> create(ForNameOrdinal forNameOrdinal, F[] universe, Object... values)
  ```

  `F[] universe` is the **new** parameter that fixes the `null` enum class; `ForNameOrdinal` is
  **kept** — it is the only route from `set(String,Object)` to an ordinal
  (`EntityUpdateTrackingArray.java:19,78`), and D5 / § 6.4 test 18 pin the `-1` contract it
  produces. The factory derives `fieldCount` as `universe.length`.
  Then `new EEnumSetBuilder64<>(universe)` in both variants. **Delete the old factory signature — do
  not keep it as a delegating overload** (gate review, GR-2): `create(ForNameOrdinal, int, Object...)`
  has **no callers anywhere** in the repo today (the only references are the factory's own body at
  `EntityUpdateTrackingArray.java:34-35` and the benchmark constructor calls below), and a
  *delegating* overload is impossible in any case — the enum universe is exactly what its parameter
  list cannot supply, which is the § 2.4 defect itself, so a kept overload could only reproduce the
  `null`. Correction to an earlier draft of this task: there is no
  `create(Class<F>, int, Object...)` in the code today — that signature appears only in
  DEC-012's stale "Accepted notes" (§ 9/4.7 amends it), so it must **not** be invented here.
  The real compatibility constraint is the **constructor**, not the factory: the JMH harness
  calls the package-private constructors directly at **four** sites —
  `hipster-entity-core/src/test/.../EEnumSetTrackingJmhBenchmark.java:98` and `:167`
  (`EntityUpdateTrackingArray64`), `:115` and `:188` (`EntityUpdateTrackingArrayLarge`)
  **[verified; gate review, GR-3 — an earlier draft of this task named only `:98` and `:115`]** — so
  changing the constructors to take `F[] universe`
  **breaks the harness in the same commit** and no factory overload can prevent that. Update all
  **four** call sites in the same commit (as the risk register already requires): pass the
  **`FieldDef`** enum's constant array (`E64.values()` / `E96.values()`) **alongside** the existing
  `forNameOrdinal64` / `forNameOrdinal96`, which are added to, not replaced by, the universe
  argument. The two flavours already in the file: `:98`/`:115` build the field count from the
  *universe* enums `Enum64`/`E96`, while `:167`/`:188` already use the **`FieldDef`** enums
  `E64`/`E96` — all four must pass the `FieldDef` array as `universe`.
  **Correct the arity check rather than add one (execution review):** the tracking array
  *already* checks `fieldCount != values.length` and throws the same `IllegalArgumentException` the
  read array throws **[verified, `EntityUpdateTrackingArray.java:24-26`]**; what it lacks is a check
  against the **enum universe** — `EntityReadArray` derives the expected length from
  `getEnumConstants()` **[verified, `EntityReadArray.java:11`]**, while the tracking array trusts the
  caller's `fieldCount`. Now that the universe is a parameter, assert `universe.length == fieldCount`
  as well, so a mis-sized array cannot be constructed at all.
- [ ] **1.5** `EEnumSetBuilder64` / `EEnumSetBuilderLarge`: add an `E[] universe` constructor;
  keep the `Class<E>` constructor delegating to it.
- [ ] **1.6** New `ChangeRecorder` (previous-value store), plus move `addOrdinalChange` out of
  the `default` interface method into both concrete builders so they can hold the recorder as
  state. Preserve the DEC-012 order exactly: compare → record → set bit.
  **The array path must feed the recorder explicitly (added by the execution review):** `mark(int)`
  carries no old/new value, and `EntityUpdateTrackingArray.set(int,Object)` overwrites
  `values[ordinal]` **before** it marks **[verified, `EntityUpdateTrackingArray.java:67-73`]**, so
  the code as it stands cannot populate `previousValue(ord)` — which § 6.4 tests 6/14 and the
  proxy's `previousValue`/`diff` forwarding require. Route the array's write through
  `changes.addOrdinalChange(ordinal, previous, value)` instead of `mark(ordinal)` — the
  `Objects.equals` guard at `:68` stays in front of it, so the verified no-op rule is unchanged —
  or add a `mark(int ordinal, Object previous, Object next)` overload beside `mark(int)`. Tasks 1.7
  and § 6.4 tests 6/14 depend on this being stated rather than inferred.
- [ ] **1.7** Widen `ViewChangeTracking` to the real contract — **in place, in
  `hr.hrg.hipster.entity.core`** (S3 deferred; no module move, no file delete) — with the S5
  two-accessor shape: `isChanged()`, `changes()` (immutable snapshot), `changesBuilder()` (live
  mutable builder), `clearChanges()`, `previousValue(int)`, `hasPreviousValue(int)`, and a
  `record FieldChange<E>(E field, Object previous, Object current)` with
  `default List<FieldChange<E>> diff()`.
  Per S5 this is a **rename-and-delete**, not an additive change: drop
  `EntityUpdateTrackingArray.changesSnapshot()` → `changes()`, `getChanges()` →
  `changesBuilder()`, and delete **both** `EntityUpdateTrackingArray64.getChanges64()` and
  `EntityUpdateTrackingArrayLarge.getChangesLarge()`. Add **no** deprecated aliases.
  **The array must also implement the rest of the S5 interface, which today it does not.**
  `EntityUpdateTrackingArray` declares only `mark`/`unmark`/`clear`/`changesSnapshot`/`getChanges`
  and does **not** implement `ViewChangeTracking`, so § 6.4 tests 8–13 and test 18 cannot compile
  against the proxy path until it gains:
  `isChanged()` (i.e. `!changesBuilder().isEmpty()`), `previousValue(int)`,
  `hasPreviousValue(int)`, `diff()`, and the `clearChanges()` spelling § 4.1/S5's proxy arm
  needs (keep `clear()` as the hot-path alias the concrete subclasses already dispatch
  statically; `clearChanges()` delegates to it and both clear the recorder). This is a
  requirement of task 1.7, not an optional extra, and it is the piece the earlier draft of this
  task left implicit.
  **The generic parameters and their bounds do not change** — keep
  `ViewChangeTracking<E extends Enum<E>, S extends EEnumSetRead<E>>` exactly as it is today
  **[verified]**. The looser bound is now an accepted, deliberate choice (S3 deferred), so there
  is **no** bound-tightening ripple into `EEnumSetBuilder64`, the JMH harness, or any implementor.
  `EEnumSetBuilder64`/`Large` already satisfy `S extends EEnumSetRead<E>`, so the existing
  hand-written `PersonSummaryBuilderTracking` keeps compiling against the widened interface apart
  from the accessor renames **and the type-argument change** it needs anyway.

  **The `S` type argument must change too (§ 4.7/DR-4) — to the `EEnumSet` interface, not
  `EEnumSet64`.** Because `changes()` is now the *immutable* `S`, an implementor whose `changes()`
  used to return a mutable builder can no longer name that builder as `S`. Concretely:
  - `PersonSummaryBuilderTracking` becomes
    `implements PersonSummary.Write, ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>>`,
    with `EEnumSet<PersonSummary_> changes() { return mf.toImmutable(); }` and
    `EEnumSetBuilder64<PersonSummary_> changesBuilder() { return mf; }`. Three lines plus the
    `implements` clause — the type argument is a real edit, not a rename, and § 13's
    same-commit rule covers it.
  - **Why the interface and never `EEnumSet64` (corrected by the execution review):**
    `toImmutable()` is declared `EEnumSet<E>` and returns the cached `EEnumSetEmpty` singleton when
    the set is empty **[verified, `EEnumSetBuilder64.java:302-305`; `EEnumSetEmpty.java:6`]**;
    `EEnumSet64` is `final` **[verified, `EEnumSet64.java:7`]** and is a *sibling* of
    `EEnumSetEmpty`, not a supertype of it. An `EEnumSet64<…> changes()` therefore cannot compile,
    and a cast would throw `ClassCastException` on a fresh (empty) tracking view — i.e. it would
    fail § 6.4 test 8, the first test of the S5 suite. Narrowing `toImmutable()`'s return type is
    not a way out either: § 6.2/1.10 requires the empty case to stay allocation-free through
    `EEnumSetEmpty.of(...)`. A caller that needs the concrete `EEnumSet64` uses `changesBuilder()`
    and `toImmutable()` explicitly.
  - `EntityUpdateTrackingArray` — which gains the interface in this task — implements
    `ViewChangeTracking<F, EEnumSet<F>>`, since its `changes()` returns the immutable set, which is
    exactly what today's `changesSnapshot()` already returns **[verified,
    `EntityUpdateTrackingArray.java:48`]**. The array base uses the interface type; so do the
    generated and example builders.
  - the § 6.4 parity fixture must use `EEnumSet<…>` as `S` for the same reason: a fixture typed on
    `EEnumSetBuilder64` would silently reintroduce the mutable-snapshot confusion S5 exists to
    remove, and one typed on `EEnumSet64` would not compile at all.
- [ ] **1.8** `clear()` / `clearChanges()` must also clear the recorder. Document the semantics
  precisely: they reset the **change set**, not the **comparison baseline**. To re-baseline,
  rebuild the tracking builder from the persisted row. If that is insufficient, add an explicit
  `refreshBaseline()`.
- [ ] **1.9** `ArrayBackedViewProxyFactory`'s updatable handler: add the `changesBuilder` arm
  alongside the existing `changes`/`clearChanges` arms **[verified, lines 117-123]**, forward
  `previousValue` / `hasPreviousValue` / `diff` / `isChanged`, and keep the throw-on-unknown-name
  behaviour (D5). Because the handler returns the array's own builder, the proxy satisfies the S5
  state-sharing requirement without a wrapper — but see § 6.4 steps 8–12, which must prove it
  rather than assume it.
  **Arm placement is part of the task, not an implementation detail** — the handler dispatches by
  method *name* and falls through in this order, so a new arm added in the wrong place is either
  unreachable or actively harmful:
  1. zero-arg arms first, **before** the `fieldByMethodName.apply(name)` lookup at
     `ArrayBackedViewProxyFactory.java:125-128`: `isChanged`, `changes`, `changesBuilder`,
     `diff`, `clearChanges`. Otherwise `isChanged`/`diff` reach the lookup, resolve to `null`,
     and throw `IllegalArgumentException("Unsupported accessor: …")` at `:127`;
  2. one-arg arms (`previousValue`, `hasPreviousValue`) **before** the generic
     `parameterCount == 1` mutator fallback at `:148-154`, which would otherwise call
     `updateArray.set(name, args[0])` with an `Integer` and treat a read as a write.
  Note that `has(int)` on the builder and `hasPreviousValue(int)` on the array are different
  probes; do not collapse them.
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

- [ ] **1.12** Rewrite `ViewAnnotationRule`: parse `gen`, `discriminatorField`, and `addons`
  **through the shape-blind `ViewAnnotationReader` this task introduces** (G9, § 4.5,
  § 4.7/DR-9 — Phase 1 is where the reader lands; § 8.1/3.2 reuses it);
  drop the string-`contains` check; reject a `GenLevel` incompatible with the declared shape
  (e.g. `BUILDER_TRACKED` on a view with no writable fields). Resolve `gen = DEFAULT` **through
  the shared `GenLevelResolver`** that § 8.1/3.3 also calls (G1, § 4.7/DR-3) — the validator must
  not carry its own `DEFAULT` → `META` shortcut, or validation and generation can disagree about
  a view whose shape resolves to `RECORD`/`BUILDER`. Also report `addons` on a non-view interface
  as the `addon_on_non_view` diagnostic (§ 4.5/G6, § 4.7/DR-1). Update `ViewAnnotationRuleTest`,
  `EntityRulesValidatorTest`, and the `EntityMetadataGeneratorTest` fixtures, which all still use
  the non-existent `@View(read = …, write = …)` form — and cover G9's shapes in
  `ViewAnnotationRuleTest` (bare `@View`, `@View()`, and both `gen` spellings), since the rule and
  the generator must agree on every shape.
- [ ] **1.13** Wire the validator into `EntityMetadataGenerator.generate(...)`: collect
  `ValidationIssue`s and fail (or warn behind a `strict` flag) **before** writing files.
  **⚠ NOT DONE during the execution round, although `plan.dsflash.notes.md` records 1.13 as done.**
  `EntityRulesValidator` was instantiated **only from tests**: nothing under `src/main` constructed
  it, there was no `validate` subcommand, and the rules therefore had no way to report anything about
  a real tree. Run by hand over the committed example it produced **20 issues about 19 correct
  interfaces plus one stale annotation** — the evidence that no build had ever run it.
  **Fixed afterwards** (this is the state of the tree now): `EntityMetadataGenerator` gained
  `--validate[=OFF|REPORT|STRICT]` and a `validate [<root>] [--strict]` subcommand, both running the
  rules **before** the first write; `EntityRule` gained `validateAll` so a rule can see the whole
  source set; `MarkerEntityRule` lost its incorrect name and package requirements; `ValidationIssue`
  now carries a kind and a warning flag so `--strict` can promote advisories; and the example's own
  Maven binding passes `--validate`, so every build prints the report. Tests: `ViewInterfaceRuleTest`,
  `EntityRulesValidatorTest`, `DefaultViewMetaContractTest` (5) and `GateParityTest` (6).
  **The policy choice the task left open is now made and documented**: REPORT is the default for a
  build, STRICT is the adopter's gate, OFF is the library default so no unrelated fixture changes
  behaviour.
  **One rule was withdrawn rather than shipped, and that is the more important half of this note.**
  `ViewInterfaceRule`'s naming check (a view's name must end in Summary/Details/Update/Form/Dto) was
  implemented, run over the committed example, and produced **six findings about six correct views**:
  the four `*PaymentMethod` subclasses of the polymorphic family, `PersonAuditable` (an addon
  field-source that is a view in its own right) and the doc-sample `person/iface/Person` that the
  generator deliberately excludes from `packages`. Deciding whether an interface is a view the
  generator will generate needs the generator's own discovery result, which a source-level rule does
  not have; the rule now reports only `view_does_not_derive_from_marker` (named like a view, reaches
  no `EntityBase`, carries no `@View`) and the naming convention is documented as a convention the
  validator does **not** enforce. The three-version history is in the rule's javadoc — it is the
  clearest available record of why "report the obvious violation" is not obvious in a tree where a
  marker, a view, an addon and a documentation sample can all carry the same annotation.
  Verified: the example's pass now reports **no issues**, and the whole gate is green.
- [ ] **1.14** New `EnumConstantOrderChecker` in
  `hipster-entity-tooling/.../validation/` (R1.3, § 4.6): parses a Java file with JavaParser,
  finds the enum carrying the `entityFieldEnum:true` marker, and returns its constant names in
  declaration order. Includes the marker reader (parse the header comment above `package`,
  decode the DEC-021 JSON5 subset, treat a malformed marker as a diagnostic rather than a skip)
  and the subsequence comparison `old` ⊆? `new` preserving order. No git dependency in this
  class — it takes two parsed enums, so it is unit-testable in isolation.
- [ ] **1.15** New `EntityFieldEnumOrderRule implements EntityRule` (R1.3), registered in
  `EntityRulesValidator`'s rule list **[verified, `EntityRulesValidator.java:35-40`]** alongside
  `MarkerEntityRule`/`ViewInterfaceRule`/`ViewAnnotationRule`/`AuditableRule`. It compares the
  working tree against a baseline git ref, reports `enum_order_shuffled` / `enum_constant_removed`
  in the DEC-022 format, and **skips every enum without the marker** (opt-in by absence).
  **As built, the cross-revision half lives in `compareRevisions(baselineSource, targetSource)`**,
  which the CLI drives with two revision sources; the in-place rule checks what one file can decide
  (the marker is present, the enum is non-empty, `allowReorder` is reported as a warning). That split
  is deliberate — it keeps git out of the rule — and it is why `--strict` had to be wired into the
  CLI for the `allowReorder` warning to be promotable.
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
`ArrayBackedViewProxyFactory.createUpdatable(...)`.

**Fixture shape this requires, stated because it is not free:** `createUpdatable` proxies exactly
`new Class[]{viewType}` (`ArrayBackedViewProxyFactory.java:47-51`) — it does **not** add
`ViewChangeTracking` to the proxy's interfaces the way `createRead` adds `ViewReader`. So the
type handed to `createUpdatable` must itself declare the tracking methods, or steps 8–13 will not
compile on the proxy side. The example's `PersonSummary` does not extend `ViewChangeTracking`
(only its builder implements it), so the test needs a dedicated fixture, e.g.
`interface TrackedPersonSummary extends PersonSummary, ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>>`,
and `createUpdatable(TrackedPersonSummary.class, array, NAME_MAPPER)`. Both materializations must
then be driven through the *same* fixture view type so the assertions are literally identical.
Also fix the fixture's field enum in place: `id` stays writable on the builder path, so the
parity sequence must exercise ordinals ≥ 1 (see DoD #4).

8. fresh tracking view → `changes().isEmpty()` and `!isChanged()`;
9. one real change → `changesBuilder().has(ord)` **and** `changes().has(ord)` both `true`;
10. `changesBuilder().removeOrdinal(ord)` (and separately `changesBuilder().clear()`) → both
    `changes().has(ord)` **and** `isChanged()` become `false` — this is the step that fails if
    `changesBuilder()` hands back a copy instead of the shared state;
11. a `changes()` snapshot captured before step 10 still reports the old state — proving
    `changes()` is a time snapshot, not a live alias;
12. `clearChanges()` empties both accessors;
13. no test may call `changes()` on a hot path. **The artifact this asserts against is the
    emitter's own output text, not a new file format:** `GeneratedSourceCompilesTest` (§ 0.5)
    already writes the generated sources to a `@TempDir`; add to it the textual assertion that
    every emitted setter / `set(int,Object)` / `set(String,Object)` body references
    `changesBuilder()` or `mf.addOrdinalChange(...)` and that the string `changes()` appears in
    no setter body (§ 8.6/3.15). The committed, regenerated example from Phase 4 is the
    human-reviewable second witness; no separate "golden file" set is introduced by this plan.
    **Gate-review note (GR-1):** this assertion becomes meaningful only once the emitter produces
    setters, i.e. with § 8.6/3.15 in **Phase 3**; before that it passes vacuously against an empty
    generated file set. It is therefore asserted at the Phase 3 exit gate, not Phase 1's.

**D2 tests — RETIRED with the decision (see the banner on § 4.2/D2 and notes D-16).**
The four tests below were written against `previousValue`/`hasPreviousValue`/`diff()` and a recorder;
none of those APIs exists, so tests 14–17 are **not part of any gate**. What replaced them is the
caller-side comparison, and its contract is pinned in three places instead: the DEC-012 no-op rule
(§ 6.4 test 3 and the example's no-op write), the state-sharing test's snapshot semantics (tests 8–12),
and `DeepChangeTrackingParityTest` for the deep half. Kept verbatim as the historical record:

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

**D7 test** — in `hipster-entity-api` (next to `DefaultViewMeta`). **Prerequisite this task
implies:** `hipster-entity-api` has **no test source root and no JUnit dependency today**
(`hipster-entity-api/pom.xml` is profile-only), so this task also creates
`hipster-entity-api/src/test/java` and adds `junit-jupiter-api` + `junit-jupiter-engine`
(`test` scope) to that POM. Without that POM change the test cannot be written at all.

19. **Name-map and arity check (the checkable half of D7).** Constructing `DefaultViewMeta` whose
    `FieldDef` enum is empty, or whose `NAME_MAPPER` fails to round-trip a constant
    (`forName.forName(f.name()) != f` — the shape of a lost `forName` arm or a renamed constant
    with a stale mapper), fails fast with a DEC-022 diagnostic naming the enum and the offending
    constant, instead of silently mis-resolving that field downstream. Do **not** write this test
    as "an enum whose ordinal order is inconsistent": no such enum can be constructed
    (`Enum.ordinal()` is final and declaration-ordered), so that formulation is untestable.
    Ordinal *order* is covered at build time by tests 20–24, and its runtime witness is
    `allFields` in the metadata JSON.

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
    keeps a nullable component for the tombstone, the constant reports `retired() == true`
    (§ 4.7/DR-2), no builder setter or `Write` method is emitted for it, it appears in no JDBC
    binder/`INSERT`/`UPDATE` output (§ 12.1/7.2–7.4), and `forName` still resolves the retired
    name.
    **Gate-review note (GR-1): this test cannot be green in Phase 1 and is not part of that gate.**
    Its generator half — the tombstone constant kept in place, `@Deprecated`, `retired() == true`,
    no builder setter or `Write` method, `forName` still resolving, ordinals and `fieldCount`
    unchanged — is asserted at the **Phase 3** exit gate (it needs § 8.3/3.7a); its writer half —
    absent from the generated binder/`INSERT`/`UPDATE` — is asserted at the **Phase 7** exit gate
    (it needs § 12.1/7.2–7.4). Write the test as one fixture with the two assertions in it, but do
    not gate Phase 1 on either half.

`hipster-entity-core` JMH: extend `EEnumSetTrackingJmhBenchmark` to the new signature and add a
`previousValue` on/off axis, so the cost of the recorder is measured rather than assumed.

**Exit gate (scoped by the gate review, GR-1):** tests **1–12 and 14–24** are green, with 8–12 passing
on both materializations and 20–24 proving the append-only rule and its opt-in checker. The two
remaining tests are asserted where the code they check first exists, and they block no Phase 1 task:
test 13 at the **Phase 3** gate (§ 8.6/3.15) and test 25 split across the **Phase 3** gate
(generator half) and the **Phase 7** gate (JDBC half, § 12.1/7.2–7.4).

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
  a retired field reports retired() == true: writers skip it, readers still accept its slot
```

- [ ] **2.1** `doc-hipster-entity/user/patterns/ordinal-array-contract.md` (new) — the contract
  above, the `QueryProjection → Object[] → META.create()` flow, and an explicit statement of the
  **R1 append-only rule** with what it means for an adapter author: ordinals never move, a
  retired field's slot still exists and may be null, its constant reports `retired()` and is
  skipped by every writer, and `fieldCount` only ever grows.
- [ ] **2.2** `doc-hipster-entity/user/patterns/jdbc-row-adapter.md` (new) — a ~30-line
  reflection-free `Object[] fromResultSet(ResultSet, FieldDef[])` reference adapter driven by
  `meta.fieldTypeAt(i)`.
- [ ] **2.3** A test in `hipster-entity-test`: build a read view from an `Object[]` produced by
  a fake `ResultSet`, assert every accessor, and assert
  `values.length == PersonSummary_.values().length`.

### 7.2 JSON changes (the visible payoff of tracking)

> **⚠ SUPERSEDED BY NOTES D-16 — the `includePrevious` half of this section was removed before
> release.** A tracking view keeps **no previous value**, so there is nothing to pair with the current
> one: `EntityJacksonChangeSerializer` emits a **JSON Merge Patch of current values only**, the
> three-argument `toJsonChanges(meta, tracking, writer)` is the whole API, and an audit-style
> old&nbsp;→&nbsp;new document is the caller's own comparison of the baseline instance it holds.
> `FieldChange` carries `(field, current)` and `changedValues()` replaces `diff()`. The tasks below are
> kept as the historical record; **read the two `includePrevious` mentions as retired** — no such
> overload, flag or `{"previous":…,"current":…}` shape exists in the tree. Binding record:
> `plan.dsflash.notes.md` D-16 and DEC-012's revision section.

- [ ] **2.4** New `EntityJacksonChangeSerializer`: takes a `ViewChangeTracking` + `ViewMeta`,
  emits only the marked fields; with `includePrevious` enabled, wraps each as
  `{"previous": …, "current": …}`. Field names come from `meta.fieldNameAt(ordinal)` — **no
  `HashMap` name→index lookup** (DEC-016). Per S4, an absent field is not written at all
  (no explicit `null` emission); nulls appear only inside a changed field's
  previous/current pair.
  **As built:** only the marked fields are emitted, names come from `meta.fieldNameAt(ordinal)` with
  no name→index map, an absent field is not written, and a *changed* field whose value is `null` **is**
  written as an explicit `null`. There is no `includePrevious` mode (see the banner).
- [ ] **2.5** `EntityJacksonMapper.toJsonChanges(ViewMeta, ViewChangeTracking, Writer)` plus
  the `includePrevious` overload; register the same shape in `EntityJacksonViewModule` so
  `ObjectMapper` users get it for free.
  **As built:** `toJsonChanges(ViewMeta, ViewChangeTracking, Writer)` exists and the shape is
  registered in the module; the `includePrevious` overload does not exist and must not be re-added.
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
  `gen`, `discriminatorField`, `addons` **through the shared, shape-blind `ViewAnnotationReader` of
  G9** (§ 4.5, § 4.7/DR-9) — never by casting to `NormalAnnotationExpr`, because the bare `@View`
  marker form is live on `person/entity/PersonDto.java:5` and `person/entity/PersonUpdateForm.java:5`
  and a shape-restricted parser silently drops those two views and the `PersonDto_` /
  `PersonUpdateForm_` enums § 9/4.1 expects. Accept every shape in G9's table (marker, empty, pairs;
  the retired `@View(true)` form gets the `unsupported_view_annotation_form` diagnostic), map `gen`
  by last-identifier-segment comparison (no class loading) for **both** the simple `GenLevel.X` and
  the fully-qualified spelling, default to `GenLevel.DEFAULT`, and emit `unknown_gen_level` on an
  unrecognized constant.
  `addons` parses to a `List<String>` of simple names, resolved against the package's
  `interfaceMap`; an unresolvable addon name is a DEC-022 diagnostic, not a silent skip. Its
  **emission** semantics are fixed by **G6** (§ 4.5) — parse it here, apply it in § 8.3.
- [ ] **3.2a Fix the two halves of view discovery** (G8, § 4.7/DR-8).
  **Add the missing seed:** the predicate is marker-derivation **or** a `@View` annotation
  (G8 rule 0). Today only `EntityMetadataGenerator.java:328-333`'s marker walk exists, so
  `person/entity/PersonCreateForm` — annotated but extending nothing marker-derived — is silently
  not a view, and the `PersonCreateForm_` that § 9/4.1 expects would never be emitted. The marker
  itself stays excluded; X3's `packages` filter still decides what is generated.
  **Then exclude the surfaces:** an interface extending `ViewReader`/`ViewWriter`/
  `ViewChangeTracking` is not a view and emits **no** `_` enum, `META`, record or builder, and
  discovery never descends into nested type declarations inside a view file. Without the
  exclusion, `PersonSummary.Write` is discovered as a view — it is today, which is why the orphan
  `Write_.java` exists with exactly `PersonSummary_`'s constants — and 3.12's emitted nested
  `Write` would be rediscovered on the next pass, nesting `Write` inside `Write` and colliding on
  the simple-name-derived `Write_.java`. Land the discovery fixture test from § 4.5/G8 (extended
  with the `@View`-seeded non-marker view) in the same commit, and land **G9's shape matrix**
  (§ 4.5) with it: one fixture per `@View` shape (bare `@View`, `@View()`, `@View(gen = …)` simple
  and fully-qualified, the retired `@View(true)`), an unknown `gen` constant, an unresolvable
  addon, a `@View` on a nested type that must not seed a second view, and the real-tree assertion
  that `person.entity` discovery yields seven views of which five carry `@View` — with `PersonDto`
  and `PersonUpdateForm` present **because of the marker form**.
- [ ] **3.3** Make the `DEFAULT` resolution rule explicit and implement it exactly as **G1**
  (§ 4.5) specifies — precedence `RECORD` (nested record with matching component order) →
  `BUILDER` (nested `Write`) → `META`; never tracked. Implement it **once**, in the shared
  `GenLevelResolver` that § 6.3/1.12's validator also calls (§ 4.7/DR-3). A mismatched nested
  record emits the DEC-022 divergence diagnostic and falls back to `META`.
- [ ] **3.4** Carry `gen` through the tooling's `ViewMeta` into the JSON (`"gen": "META"` in
  `toJson`, parsed back in `fromJson`). Round-tripping must stay lossless — there is an
  existing `fromJson` test.

### 8.2 Two concrete generator bugs to fix while in there

- [ ] **3.5** Exclude `default` methods from the property list
  (`EntityMetadataGenerator.java:293-296`): add `.filter(m -> !m.isDefault())` plus a
  regression test. **[verified]** this is what produced the bogus `toBuilder` /
  `toBuilderTracking` enum constants in the example.
  **`isDefault()` alone is not sufficient — there is a second, still-live leak.** An *abstract*
  zero-arg accessor declared by a view interface that also extends the write/tracking surface is
  collected as a field too. The live witness is
  `hipster-entity-example/.../person/entity/PersonUpdatableView_.java:13`, whose constant
  `changes(TypeUtils.parameterizedType(EEnumSet.class, PersonUpdateForm_.class))` comes from
  `PersonUpdatableView.changes()` — an abstract method, so the `default` filter does not catch
  it. Fix both in this task: exclude the framework accessors by declaration origin (a method
  whose erased signature matches `ViewReader`/`ViewWriter`/`ViewChangeTracking` —
  `isChanged`, `changes`, `changesBuilder`, `changedValues`, `currentValue`, `clearChanges`,
  `changesDeep`, `nestedTrackers`, `shallowPaths`, `get`, `set`) — not by name-matching against a
  hand-kept list. **Note the two corrections to this list as written**: `diff` and
  `previousValue`/`hasPreviousValue` are named in older drafts of this task, and none of the three
  exists (the tracker keeps no previous value — notes D-16); the live members are the ones listed
  here, and the rule matches them by declaration origin rather than by name where it can. Add a
  regression fixture that is a view extending both `ViewWriter` and the tracking contract, and
  assert its field enum contains only real fields. Do this before Phase 4, or the regenerated
  `PersonUpdatableView_` will carry a `changes` field, `values.length` will exceed the view's
  field count, and the R1 checker will see a bogus appended constant.
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
  The reference file named here is a **shape** reference only and is updated in this same task:
  its `metadata` constant is raw `Map.class` where this task requires
  `TypeUtils.parameterizedType(...)`, and it carries no `@FieldSource` accessors where § 8.3/3.9
  requires them (§ 2.4). Bring
  `hipster-entity-test/src/main/java/hr/hrg/hipster/entity/person/PersonSummary_.java` up to the
  emitted form — parameterized `metadata`, `FieldSource` overrides for `age`/`departmentName` —
  so that the § 8.3 exit gate compares like with like. Nothing in the plan may depend on
  byte-equality with the *pre-3.7* content of that file.
  **[verified]** `FieldBoilerplateGenerator`'s `propertyEnumMode` emits
  `getPropertyType()`/`getPropertyName()` — which is exactly what produced the broken example
  enum. Deprecate `propertyEnumMode` for view enums and route view metadata through the
  `fieldEnumMode` path; keep `propertyEnumMode` only for the tooling's own `Property` model.
- [ ] **3.7a Make the field enum append-only (R1, § 4.6) — this is a generator correctness
  requirement, not a style preference.** When regenerating an enum that already exists:
  - **check the marker first (G7, § 4.7/DR-7):** if the existing enum does **not** carry
    `entityFieldEnum:true`, it has no committed ledger — rebuild the constant list from the
    resolved field list, **drop** constants with no matching field (report
    `enum_constant_removed`, action `bootstrap`), and emit the DEC-021 header including the
    marker in the same write. **Only a marker-carrying enum is preserved and tombstoned below.**
    This is the path Phase 4's legacy example enums take, which is why their leaked
    `toBuilder`/`toBuilderTracking`/`changes` constants disappear instead of surviving as
    tombstones with record components and an inflated `fieldCount`. A malformed header is treated
    as marked (fail safe), never as bootstrap;
  - read the existing constants in order; the new constant list **starts** with that exact list
    (no reordering, ever — the generator's enum comparison is the one place where DEC-020's
    "recognize by shape, re-emit canonically" logic must be **order-sensitive**);
  - append constants for fields present in the interface but not yet in the enum, in
    declaration order, **after** all existing ones;
  - for an accessor that has disappeared, **keep its constant in place as a tombstone**,
    annotated `@Deprecated` with a short reason comment (R1.4) — never delete it on a
    marker-carrying enum (the bootstrap bullet above is the only path that drops a constant). The tombstone
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
  `hr.hrg.hipster.entity.core.{ViewChangeTracking, EEnumSetBuilder64, EEnumSet}` and the
  consuming POM needs `hipster-entity-core` — with the S5 two-accessor pair
  (`changes()` → `mf.toImmutable()`, `changesBuilder()` → `mf`), choosing `EEnumSetBuilder64`
  vs `EEnumSetBuilderLarge` **at generation time** from the known field count. Keep the runtime
  `.create()` selection only for the array/proxy route. Because the generated class *is* the
  tracking state holder, both accessors derive from the one `mf` field (§ 4.1 S5) — never emit a
  second field or a snapshot cache. The emitted class declares
  `implements View<…>.Write, ViewChangeTracking<View_, EEnumSet<View_>>` — `S` is the
  **immutable** `EEnumSet<View_>` that `mf.toImmutable()` actually returns, because `changes()` is
  the snapshot and `changesBuilder()` is the live `EEnumSetBuilder64<View_>` (§ 4.7/DR-4, which
  corrected this from the non-compiling `EEnumSet64`). Mirrors the hand-written example's imports
  **[verified, `PersonSummaryBuilderTracking.java:6-7`]** exactly.
- [ ] **3.15** Emit the setter body in exactly the verified order:
  `mf.addOrdinalChange(<ordinal literal>, field, value); field = value; return this;` — the
  ordinal must be a **literal** so the JIT can constant-fold the `1L << ordinal` mask.
  Mirror it in the `set(int,Object)` and `set(String,Object)` arms with an explicit per-field
  cast, and return `-1` for an unknown name. Emit these arms **only for writable fields (S1)**;
  the hand-written example's arms for `age` and `departmentName`
  (`PersonSummaryBuilderTracking.java:93-94, 105-106`) are the S1 violation to remove.
  Per S5, generated setters and `set(int)`/`set(String)` arms touch `mf` **directly** and must
  never route through `changes()` — `changes()` allocates a snapshot and is a read-side API for
  callers, not an internal accessor. The § 6.4/13 textual assertion on the emitter's own output
  enforces this.
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
  position, and, on a marker-carrying enum, never drop a constant the interface no longer
  declares. See § 8.3/3.7a; a marker-less enum takes the bootstrap path there instead.
  **Discovery exception (G8, § 4.7/DR-8):** recognition happens *after* discovery, so an
  interface extending `ViewWriter`/`ViewReader`/`ViewChangeTracking`, and any nested type of a
  view file, is never a generation target — its members survive as part of the enclosing view's
  file rather than being recognized as some view's own output. `CooperativeCodegen` must not
  re-introduce through recognition what `3.2a` excluded from discovery.
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
  `cooperative` / `strict` / **`packages`** knobs as **CLI flags** (`<arguments>` in the plugin
  config), not `-D` system properties, so one flag surface serves both the Maven build and a
  manual run (the `packages` filter restricts **generation**, not indexing — X3, § 4.3;
  § 4.7/DR-1).
  Generated sources are added to the
  compile source roots. **Not** a new `hipster-entity-maven-plugin` module.
- [ ] **3.24** NOT HERE — MOVED: the `project-automation` watcher is **in scope as Phase 7.5**
  (task 7.17, X4). This entry is a pointer only, so the watcher is not mistaken for a missing
  step in Phase 3.

**Exit gate:** `GeneratedSourceCompilesTest` is green for all six levels, with zero diagnostics,
the `META` output **matches the reference shape modulo the DEC-021 header** — where "matches"
means: same members, same member order, same `javaType()` forms including
`TypeUtils.parameterizedType(...)` for generics, and the same `FieldSource` overrides as
§ 8.3/3.9 requires — and the reference file has been updated in the same commit as 3.7/3.9
(§ 2.4). Byte-equality is only ever asserted against the *same revision's* canonical emitter
output, never against a hand-written file. This gate also carries the two test obligations the
Phase 1 gate no longer covers (gate review, GR-1): **§ 6.4 test 13** (the emitted-setter textual
assertion, § 8.6/3.15) and the **generator half of § 6.4 test 25** (the tombstone of § 8.3/3.7a).

---

## 9. Phase 4 — example, demo, and docs (2–3 days)

- [ ] **4.1** Regenerate `hipster-entity-example` from the generator. Any diff against the old
  hand-written files becomes either a generator bug or an intentional documented divergence.
  **Derive the affected file list from the scan, not from the `@View` annotations alone** — the
  view predicate is **marker-derivation *or* a `@View` annotation** (G8 rule 0, § 4.5;
  `EntityMetadataGenerator.java:328-333` implements only the marker half today, task 3.2a adds
  the other), so in `person.entity` the views are `PersonAuditable`,
  `PersonCreateForm`, `PersonDetails`, `PersonDto`, `PersonSummary`, `PersonUpdatableView` and
  `PersonUpdateForm` — seven of them, of which five carry `@View` (`PersonAuditable`,
  `PersonCreateForm`, `PersonDto`, `PersonSummary`, `PersonUpdateForm`; `PersonDetails` and
  `PersonUpdatableView` carry none and are found by marker-derivation). `Person` itself carries
  `@View` but is the **marker**, so it is not a view and never gets a `_` enum; and
  `PersonSummary.Write` also derives from the marker but is a **framework surface excluded by
  G8 rule 1** (§ 4.7/DR-8) — which is why it is not counted here even though the pre-G8 generator
  did treat it as a view. The hand-written
  artifacts in scope are therefore at least
  `PersonSummary_`, `PersonAuditable_`, `PersonDetails_`, `PersonDto_`, `PersonUpdatableView_`,
  `PersonUpdateForm_`, `PersonSummaryBuilder`, `PersonSummaryBuilderTracking`, plus
  newly generated `PersonCreateForm_` — new precisely because the `@View` seed did not exist
  before task 3.2a (the `Person` marker itself gets **no** enum — the same
  paragraph establishes that it is not a view, so an earlier draft's "newly generated `Person_`"
  was self-contradictory and is retracted here); delete each hand-written file **only once
  the generated version passes the same tests**. **`Write_.java` is the exception: it is
  deleted outright, not regenerated** — its source interface is excluded from discovery by G8
  (§ 4.7/DR-8), so no generated counterpart can exist; grep confirms only its own self-declarations
  reference it. Two *locations* are deliberately **not**
  regenerated and stay hand-written: nothing under
  `hipster-entity-example/.../example/` — it lies outside the X3 `packages` filter — and nothing
  in the doc-sample packages `person/iface` + `person/record` (X3, § 4.3). Within `example/`,
  `Auditable` (the field source) and the two `*Property` enums **stay**; the two stale field enums
  `PersonAuditable_.java` and `PaymentMethodAuditable_.java` are **deleted** (§ 4.7/DR-5) — both
  are wrong-shaped (`getPropertyType()`, no `FieldDef`) and unreferenced (see § 4.5/G6 for what,
  if anything, replaces each). Confirm with `grep` — **scoped to the working tree**, since
  `.kilo/worktrees/` holds a second checkout of this repo (gate review, GR-6, § 4.1/S2) — that nothing
  references them before deleting; both are unreferenced today **[verified]**.
  Expect **six** classes of intentional diff: the S1 write-surface reduction (§ 4.1 S1); the addon
  field-set change under per-view resolution — `PersonAuditable_` gains `createdAt`/`updatedAt`
  **structurally** from `Auditable<Long>`, `PersonDetails_` gains them from its own `addons`
  declaration, and the other views gain nothing (§ 4.5/G6, § 4.7/DR-1); the drop of the pre-R1
  leaked constants under the bootstrap rule — `toBuilder`/`toBuilderTracking` in `PersonSummary_`
  and `PersonDto_`, and the spurious `changes` constant that `PersonUpdatableView_.java:13`
  carries today (3.5, **G7** § 4.5); the deletion of the orphan `Write_` (**G8** § 4.5); the
  deletion of the two stale `example/` enums; and the DEC-021 header plus `entityFieldEnum:true`
  marker on every enum (3.8).
  Also in this task: remove the inert `@View(addons = …)` declaration from
  `person/entity/Person.java:7` (the `paymentMethod` marker's inert declaration is removed in
  4.9), and add `@View(addons = {PersonAuditable.class})` to `PersonDetails` so the addon path is
  exercised by real generated code (§ 4.5/G6).
- [ ] **4.2** Replace the empty `PersonController` with a real `PersonDemo.main` (no
  framework): row array → read proxy → JSON → tracking patch → printed diff → the
  parameterised `UPDATE` it would send.
  **Re-scoped by D-17:** the last leg is out — the demo builds no SQL. SQL generation is the draft
  exploration of § 12.1 (opt-in `--adapters`) and the example does not enable it, so the demo ends
  with the change set and the *changed columns* a partial write would touch
  (`plan.dsflash.notes.md` D-17). Everything before that leg is unchanged.
- [ ] **4.3** Drive `paymentMethod/PaymentMethodController` from generated code using the
  polymorphic `PaymentMethod_` / `CreditCardPaymentMethod_` enums — `discriminatorField` support
  already exists in `FieldBoilerplateGenerator` but is exercised **only** by the tooling's own
  boilerplate test (`FieldBoilerplateGeneratorTest.java:35-36`, asserted `:48-49`), never by
  generated view output; this task is what exercises it end to end. Depends on 4.9.
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
  - `architecture/decisions/README.md`: the input plan's claim that this file contains the
    literal text `(line is not valid UTF-8)` is a **search-tool artifact, not file content** —
    a byte-level search across `doc-hipster-entity/` finds no such string anywhere, so there is
    nothing to repair *as text* and the bullet about that string is indeed dropped. **But the
    file's real defect is genuine and must not be dropped with it:** `decisions/README.md`,
    `DEC-020.md` and `DEC-021.md` are **not valid UTF-8** — they are cp1252-contaminated
    (`README.md` has 8 × `0x97`; `DEC-020.md` carries stray `0x97`, `0x86`, `0x92`; `DEC-021.md`
    a stray `0x97`), the decoders report `(line is not valid UTF-8)` for exactly those lines,
    and strict UTF-8 readers refuse the files outright. That matters here because § 9/4.8 must
    edit `decisions/README.md`, and DEC-020/021 are the normative rules the whole generator is
    built on. Handled by the new task **4.10**, which runs before 4.8;
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
  in Phase 1 (DEC-012 amendment, and the new DEC if D3/D4 proceed). **Add a new standalone DEC
  for R1** (§ 4.6, § 4.7/DR-6): the append-only field-enum rule, the `entityFieldEnum` marker, the
  checker contract (baseline source, subsequence comparison, exit code), and the retired-field
  policy including `FieldDef.retired()`. R1 governs a persisted data layout, so per the project's
  DEC-per-decision convention it gets its own traceable record — it **rides DEC-021's
  header/marker mechanism** but is **not** recorded by amending DEC-021, so the order contract can
  be found without reading the generator-header decision. Cross-reference it from DEC-012 and from
  `ordinal-array-contract.md` (§ 7.1).
- [ ] **4.9** Regenerate the **`paymentMethod` sealed hierarchy** (in scope, per § 3): the **four
  concrete** `*PaymentMethod` views and their `_` enums plus the `discriminatorValue` /
  permitted-subtype wiring, then drive `PaymentMethodController` from the generated code as in
  4.3. This is the only place `discriminatorField`/permitted subtypes are exercised by generated
  output, so it is the acceptance test for polymorphic generation. Keep the step **after** 4.1 so
  the single-entity regeneration path is already proven. **[verified]** the fixture is
  `PaymentMethod` (a `sealed interface … permits BankTransferPaymentMethod, PayPalPaymentMethod,
  CryptoPaymentMethod, CreditCardPaymentMethod`, with `type()` as the discriminator).
  **Reconciled with G8 rule 0 (pre-execution review):** `paymentMethod.entity.PaymentMethod` is the
  **marker**, and the marker is excluded from discovery, so there are **four** generated view enums
  (one per subclass), not five. The base `PaymentMethod_.java` is **not** generated — it stays the
  hand-written enum it already is, and it is the correct-shaped half of the polymorphic wiring:
  `PaymentMethod_.type` is the discriminator field and lines 72-77 list the permitted subtypes
  **[verified]**. Generation supplies each subclass's `discriminatorValue` and META, which is what
  DoD #7's "exercised by generated code" means; the base enum is the documented hand-off. (An
  earlier draft's "five views and their `_` enums" counted the marker as a view and is corrected
  here — DoD #7 is qualified the same way.) **Not an
  addon exercise (§ 4.7/DR-1):** `paymentMethod.entity.PaymentMethod` is the marker, so its
  `@View(addons = {PaymentMethodAuditable.class})` is inert and is removed here;
  `PaymentMethodAuditable` does not even extend `PaymentMethod` (it extends only
  `Auditable<Long>`), so it is not a view of this entity and gets no generated enum. This task
  exercises the sealed/discriminator path only — the addon path is exercised by `PersonDetails`
  (4.1) and by the tooling fixture test (§ 4.5/G6).
- [ ] **4.10 Transcode the three non-UTF-8 DEC files** — `architecture/decisions/README.md`,
  `DEC-020.md`, `DEC-021.md` — to UTF-8, **without content changes**. Reproduce with a strict
  decode, which fails today:
  `[System.Text.UTF8Encoding]::new($false,$true).GetString(bytes)` throws
  `Unable to translate bytes [97] at index 438…` for `README.md`; the same for the other two.
  Convert with a cp1252 (Windows-1252) read + UTF-8 write, then verify: a strict UTF-8 decode now
  succeeds, `git diff` shows only the intended files and lines, and the three files still render
  as markdown. **Do this before 4.8**, which appends the R1 DEC row to `decisions/README.md` —
  editing a mis-encoded index is how the corruption gets worse rather than better. Note that
  DEC-020/DEC-021 are cited by `AGENTS.md` § 1 as project-wide rules, so a reader that cannot
  decode them cannot follow the mandatory generator conventions either.

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

- [ ] **6.1** New **standalone DEC** (number per the project's sequence, § 4.7/DR-6): pull over push (D4); the
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

**STATUS: DRAFT / EXPLORATION, AND STRICTLY OPT-IN — re-scoped by direct instruction after Phase 7.**
The generator exists and its tests pass, but it is not a supported generator: it runs only under the
tooling's `--adapters` flag, the default pass emits none of it, the example project does not enable it
(its 24 generated `*RowAdapter`/`*Binder` classes were removed), and no committed project depends on
the emitted shape. **Any future SQL support must keep that shape — an explicit request, never a
default.** The binding record is `plan.dsflash.notes.md` D-17 and `hipster-entity-tooling/README.md`;
`plans_dsflash`-style guidance for the *hand-written* adapter stays valid in
`doc-hipster-entity/user/patterns/jdbc-row-adapter.md`. The tasks below are kept as the historical
specification of what the draft implements.

- [ ] **7.1** Generated positional reader per view: `Object[] fromResultSet(ResultSet, ViewMeta)`
  driven by `meta.fieldTypeAt(i)` and `meta.fieldNameAt(i)` — **no reflection**, no `SELECT *`
  ordering assumption. Tolerates `null` in every slot (S4, tombstones, unfillable
  `DERIVED`/`JOINED`).
- [ ] **7.2** Generated binder per view: `void bind(PreparedStatement, int startIndex, View)`
  writing only fields that are `FieldKind.COLUMN` **and not `retired()`** (S1, § 4.6/R1.4,
  § 4.7/DR-2), in **ordinal order** so `PreparedStatement` plans stay stable and cacheable.
  Column names resolve through X2's `FieldDef.column()` — the annotation's `column()` when set,
  else the accessor name **[verified, `FieldSource.java:21-22`]**.
- [ ] **7.3** Generated `INSERT` / `UPDATE` fragment builders using `@FieldSource.column()`
  labels, parameterised. An `UPDATE` may be generated from a **change set** (§ 7.2/2.4) so a
  partial update touches only changed columns — the natural consumer of Phase 1's tracking.
- [ ] **7.4** Tests: a fake `ResultSet` round-trips a fixture row through reader → view → binder →
  fake `PreparedStatement`, asserting ordinals, null handling, and that a `DERIVED` field and a
  `retired()` tombstone never appear in the binder output (a tombstone slot is still *read* when
  the row carries it — § 4.6/R1.4). No test requires a real database or a driver dependency.

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
  (`jakarta.validation-api`). Pin it to the coordinate the local repository already holds —
  `jakarta.validation:jakarta.validation-api:3.0.2` is present (so is `2.0.2`), which keeps the
  offline `mvn -o` build working; an unpinned or newer version would force a network fetch. It must
  be `provided`/optional in the tooling and only added to a consuming project's POM when it
  actually wants validation — the library modules must not gain a hard validation dependency.
- [ ] **7.11** Alternative for cases annotations cannot express: generate a standalone
  `<View>Validator` with a concrete `validate(View)` body and explicit violation messages (G5's
  naming and dispatch rules). Per constraint, choose annotation-vs-validator and document why.
- [ ] **7.12** Tests: a valid instance passes, each generated constraint fails its negative case,
  and an unsupported constraint yields a diagnostic and no generated code for it.

### 12.4 Field-enum compaction (R1.4)

- [ ] **7.13** A deliberate **compaction mode**: drops `@Deprecated` tombstone constants and
  renumbers the remaining ordinals densely. It is not a default behaviour and must never run as
  part of a normal generation pass. **It needs its own new standalone DEC before 7.14 starts**
  (§ 4.7/DR-6): compaction is the one operation in this plan that deliberately mutates a persisted
  ordinal layout, so it gets its own record rather than an amendment of DEC-012/DEC-021, and it
  must not survive only as a plan bullet. The record also states which classes of persisted
  artifact the operator must have drained, and how `retired()` constants disappear from the enum,
  from `allFields`, and from the generated binders.
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
library module gained a hard JDBC or validation dependency. § 7.4 also carries the **JDBC half of
§ 6.4 test 25** — a `retired()` field absent from the generated binder and `INSERT`/`UPDATE`
fragments (gate review, GR-1).

---

## 13. Sequencing, effort, gates

| Phase | Content | Effort | Exit gate |
|---|---|---|---|
| 0 | JDK-25 pin for compiler **and** test fork, recorded Maven launcher, reactor POM, `-pl` scoping script, baseline, compile-the-output harness | ≈ ½ d | one recorded command green from root |
| 1 | tracking ctor NPE, contract widened + `ChangeRecorder` in `core`, **R1 checker + marker**, validator repair, tests 1–12 + 14–24 (13 lands with Phase 3, 25 with Phases 3/7 — GR-1) | 4–6 d | § 6.4 tests 1–12 and 14–24 green, 8–12 on both materializations, 20–24 for append-only |
| 2 | ordinal-array contract docs (incl. R1 for adapter authors), JSON change serializer, Jackson setup doc | 2–3 d | one-field JSON patch from both paths |
| 3 | `GenLevel` parsing, **discovery exclusion (G8/3.2a)**, levels META→BUILDER_ALL, **append-only enum generation + marker emission + marker-scoped bootstrap (G7/3.7a)**, cooperative blocks, headers, divergence, `exec-maven-plugin` | 5–8 d | `GeneratedSourceCompilesTest` green for all six levels, **plus § 6.4 test 13 and the generator half of test 25** (GR-1) |
| 4 | example regeneration incl. X3 layout work, the S1 write-surface reduction, the DR-1 addon scope change (incl. deleting the two stale `example/` enums), `PersonDemo`, doc corrections, UTF-8 transcode of the DEC corpus | 2–3 d | demo prints a one-field diff; no doc claims a missing level |
| 5 | Jackson round-trip + polymorphism coverage | 1–2 d | round-trip tests green |
| 6 | **nested/deep change tracking**: DEC, `ChangePath`/`changesDeep()`, nested index, collections, generator wiring, deep patch | 7–11 d | 3-level fixture and a `List<Tracked>` produce a correct nested patch |
| 7 | **adapters & codegen extras**: JDBC row/binder, mappers, validation, compaction, watcher | 7–11 d | generated adapter round-trips a fixture row; mapper/validator/compaction tests green; **the JDBC half of § 6.4 test 25** (GR-1) |
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
  `changes()` today and must become `changesBuilder()`, with `changes()` added alongside it — and
  its `implements` clause re-typed from `EEnumSetBuilder64<…>` to `EEnumSet<…>` (§ 4.7/DR-4, which
  corrected that type argument from `EEnumSet64`).
- Per S5, the `changes()`/`changesBuilder()` pair and the underlying array renames
  (`changesSnapshot()`→`changes()`, `getChanges()`→`changesBuilder()`, delete `getChanges64()`
  **and** `getChangesLarge()`) land in **one commit with no aliases**, so there is no window in
  which both naming schemes exist. That commit must also update the eight benchmark call sites
  the earlier draft had missed — `EEnumSetTrackingJmhBenchmark.java:266,272,278,284,309,314,319,324`
  (§ 4.1/S5) — **plus the four constructor call sites** (`:98,115,167,188`, § 6.2/1.4, GR-3) — or the
  module stops compiling.
- **G7's marker-scoped bootstrap rule (§ 4.5/G7, § 8.3/3.7a) lands before Phase 4 regenerates the
  example.** Regenerating the legacy marker-less enums without it would tombstone the leaked
  `toBuilder`/`toBuilderTracking`/`changes` constants, add nullable record components for them and
  inflate `fieldCount` — the opposite of what § 9/4.1 expects and of DoD #3.
- **G8's discovery rules — the predicate *and* the exclusion (§ 4.5/G8, task 3.2a) — land before
  Phase 4 regenerates the example.** Without the exclusion, `PersonSummary.Write` is regenerated as
  a view in its own right, the nested `Write` that 3.12 emits is rediscovered on the next pass,
  `Write` nests inside `Write`, and the simple-name-derived `Write_.java` collides; without the
  `@View` seed there is no `PersonCreateForm_` at all, and § 9/4.1's file list is wrong.
- **G9's shape contract (§ 4.5, tasks 1.12 + 3.2a) lands before Phase 4 regenerates the example.**
  `person/entity/PersonDto` and `person/entity/PersonUpdateForm` carry the **bare `@View` marker
  form**; a parser restricted to `NormalAnnotationExpr` drops both, so the regenerated file list
  silently loses `PersonDto_` and `PersonUpdateForm_` — two of the five `@View`-carrying views
  § 9/4.1 counts. Discovery must be shape-blind and the parse must go through the one shared reader.
- **No module-layering work in this release** (S3/X1 deferred). If a task appears to require
  moving `ViewChangeTracking` or the `EEnumSet*` interfaces between `api` and `core`, it is out of
  scope — take the `core` dependency instead.
- **R1 (§ 4.6) gates ordering everywhere the enum is touched:** the generator appends
  (§ 8.3/3.7a) and the checker verifies (§ 6.3/1.14–1.16). No task may reorder a constant list,
  and, **on a marker-carrying enum**, no task may treat "the enum now differs from the interface"
  as a reason to delete a constant (a marker-less enum takes G7's bootstrap path instead). The
  checker's marker must be emitted (§ 8.3/3.8) before the checker can be exercised
  against real generated output, and the checker must exist before Phase 4 regenerates the
  example — otherwise the first regeneration has no order guard at all.
- **The DEC corpus is transcoded to UTF-8 (4.10) before the decision index is edited (4.8).**
  `decisions/README.md`, `DEC-020.md` and `DEC-021.md` are cp1252-contaminated today; editing a
  mis-encoded index makes the corruption worse, and DEC-020/021 are `AGENTS.md` § 1 rules that a
  strict UTF-8 reader cannot even open.
- **The write/tracking accessor filter (§ 8.2/3.5) lands before Phase 4 regenerates the
  example.** `isDefault()` alone is not enough: `PersonUpdatableView.changes()` is abstract, and
  the hand-written `PersonUpdatableView_.java:13` proves the accessor is collected as a field
  today. Regenerating without the fix would append a bogus `changes` constant and inflate
  `values.length` past the view's field count.

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
| The reactor build is red for reasons unrelated to hipster-entity (metadata modules) | High | High | Phase 0.2's `dependencyManagement` entries fix the validation failure, and the `-pl` module list in the 0.3 run script keeps day-to-day work scoped to the six modules so progress is never blocked by an unrelated module. |
| `EEnumSet*` API changes ripple into the JMH harness and the example | High | Medium | **Delete** the old `create(ForNameOrdinal, int, Object...)` factory — it has no callers and cannot be made to delegate (GR-2, § 6.2/1.4) — and note the **constructors** are what the harness calls, at **four** sites (`EEnumSetTrackingJmhBenchmark.java:98,115,167,188`); update all four in the same commit, since no factory overload can absorb a constructor change; regenerate the example in the same commit. **[verified]** `:98`/`:115` pass `forNameOrdinalNN` plus `Enum64.values().length` / `E96.values().length`, while `:167`/`:188` already pass the `FieldDef` enums `E64`/`E96` — so the new `F[] universe` parameter must receive the **`FieldDef`** enum's constants at every site, a different enum from the `Enum64`/`E96` universe used for the count at `:98`/`:115`. (Gate review, GR-3: the earlier wording named two sites.) |
| Generated output overwrites hand-edits | High | Medium | Shape-based cooperative detection (3.19) + `enabled:false` (3.8); ambiguous cases fall back to overwrite + a diagnostic rather than guessing. |
| Cooperative shape-detection false positives | Medium | High | Start with strict shape matching only; treat ambiguity as "not recognized" and report. |
| Ordinal reordering silently corrupts persisted patches | Medium | High | Freeze the § 7.1 contract as append-only (R1); construction-time **non-empty + lossless name-map** check in `DefaultViewMeta` (order itself is not checkable there — see D7, § 4.2); keep `allFields` JSON as the migration record. |
| A field-enum reorder reaches `main` because the checker is opt-in or not wired into CI | Medium | High | R1.2/R1.3: the generator emits the `entityFieldEnum:true` marker on every field enum it produces, so opt-out has to be deliberate. Wire the checker CLI into the build and the PR check as part of § 6.3/1.16, and assert the *unmarked-enum-is-ignored* case (test 24) so the opt-in boundary is itself tested. |
| A retired accessor's constant is "cleaned up" by a well-meaning later pass | Medium | Medium | R1.4 + § 8.7/3.20: the generator must never delete a constant, reports `enum_constant_removed` if one is missing, and the tooling README states why the deprecated constant stays. Covered by test 25. |
| ~~`ChangeRecorder` retains large object graphs~~ | — | — | **RETIRED — the component does not exist.** D2 was reversed (notes D-16): a tracking view keeps no previous value, so there is no recorder, no `Object[] previous`, and nothing to retain. The risk this row described was removed by removing the feature; it is kept struck-through so a later reader does not go looking for the mitigation. The remaining tracking-state cost is the ordinal bitset alone, measured by § 6.4's JMH axis. |
| Generator scope creep (cooperative blocks, divergence reporting) delays the usable slice | High | Medium | Phase 3 is ordered so `META → BUILDER_TRACKED` is usable *before* DEC-020/021 sophistication lands. |
| Documentation keeps drifting ahead of the code | High | Medium | The compile-the-output test (0.5) makes doc—code drift a build failure, and the Phase 4 corrections are part of the DoD, not optional. |
| JavaParser AST/API churn | Medium | High | Keep it pinned where it already is — centrally: root `pom.xml:21` `<javaparser.version>3.28.0</javaparser.version>` + `dependencyManagement` `pom.xml:74-76`; `hipster-entity-tooling/pom.xml:29-32` deliberately declares no version (pre-execution correction). Add AST snapshot tests. |
| Deep nested tracking is attempted before single-level tracking is solid | Medium | High | Phase 6 is sequenced after Phases 1–5 and its DEC is task 6.1; the nested index is built on the final S5 contract, not alongside it. |
| A consumer cannot take a `hipster-entity-core` dependency and so cannot implement tracking | Low | Medium | Accepted consequence of deferring S3, not an oversight. Documented in the getting-started guide (§ 9/4.6), and the fix is the § 4.4 layering refactor — mechanical and non-blocking. Generated output shape is unaffected. |
| S1 removes setters the example currently uses, so regeneration is not a pure no-op | Certain | Low | Expected, not a surprise: § 4.1 S1 names the four call sites. Land it in Phase 4 with the diff compared against S1 and documented as an intentional write-surface reduction — that is the executor's own review, not a separate sign-off (see § 4's DEC-acceptance note). |
| The generator rediscovers its own emitted nested `Write`/tracking surfaces, so regeneration is not a fixed point (and `Write_.java` collides) | Medium | High | **G8** (§ 4.5, task 3.2a): discovery excludes `ViewReader`/`ViewWriter`/`ViewChangeTracking` surfaces and never descends into nested types of a view file; the G8 fixture asserts one `_` enum per view and a byte-identical second pass. |
| R1's never-delete rule applied to a marker-less legacy enum turns leaked pre-R1 constants into tombstones (and grows the record and `fieldCount`) | High | Medium | **G7** (§ 4.5, § 8.3/3.7a): the ledger is marker-scoped — a marker-less existing enum is bootstrapped fresh, the drop is reported as `enum_constant_removed` with action `bootstrap`, and a malformed header fails safe toward "marked". |
| `@View` on an interface that derives from no marker is silently ignored, so an expected view emits nothing | Medium | Low | Fixed by **G8 rule 0** (§ 4.5, task 3.2a): the predicate is marker-derivation **or** `@View`; the G8 fixture covers the `@View`-only case (`PersonCreateForm`). |

---

## 16. Immediate next actions (first week, in order)

No step below is blocked on a decision — **all of § 4 is finalized, including the six items closed
in § 4.7 and the gate-review corrections recorded there (GR-1–GR-6)**.

1. **Phase 0.1–0.4:** pin `JAVA_HOME` to JDK 25 for the forked tests (0.1), name the recorded
   Maven launcher (0.1a), add the missing `dependencyManagement` entries (0.2), wrap the explicit
   `-pl` module list in a JDK-25 run script (0.3 — **not** a Maven profile, which cannot narrow
   the reactor), run the command, record the baseline.
2. **§ 6.2/1.4:** fix the `null` enum-class NPE in both tracking-array variants, with
   `EntityUpdateTrackingArrayTest` tests 1–3. This is the single highest-value commit in the
   plan — it turns an untestable path into a testable one with no other dependency.
3. **§ 6.2/1.7–1.8 (S5):** widen `ViewChangeTracking` **in place in `core`** — delete nothing,
   move nothing — add `changes()`/`changesBuilder()`, rename the array accessors with no aliases;
   then bring `PersonSummaryBuilderTracking` up to that signature **by hand** and get § 6.4 tests
   8–12 green on both materializations (test 13 is asserted at the Phase 3 gate — GR-1). Only then is
   the target for the generator stable. **This step originally also said to add `previousValue`,
   `diff()` and a `ChangeRecorder`; that half is SUPERSEDED — do not implement it** (notes D-16: no
   previous value is kept, `changedValues()` replaces `diff()`, and the comparison is the caller's).
   § 6.4 tests 14–17 are retired with it.
4. **§ 6.3/1.12–1.13:** repair `ViewAnnotationRule` and wire the validator into the generator.
5. **§ 6.3/1.14–1.17 (R1):** land `EnumConstantOrderChecker` +
   `EntityFieldEnumOrderRule` + its CLI, with § 6.4 tests 20–24. Do this **before** any generator
   work and before Phase 4 regenerates the example, so the append-only rule has a guard in place
   from the first regeneration rather than being retrofitted.
6. **Phase 0.5 + § 8.1–8.3:** parse `GenLevel`, emit a correct `FieldDef` + `META` enum with the
   DEC-021 header **including the `entityFieldEnum:true` marker** (§ 8.3/3.8), implement the
   append-only regeneration (§ 8.3/3.7a) **together with G7's marker-scoped bootstrap rule**
   (§ 4.5/G7), land **G8's discovery exclusion** (task 3.2a, § 4.5/G8), and prove the emitted
   source compiles.
7. **§ 6.4 test 19 (D7):** land the `DefaultViewMeta` contract check — non-empty enum plus a
   total, lossless name map — before Phase 4 regenerates the example. (Ordinal *order* is not
   checkable here and is not claimed to be; R1 enforces it at build time. Do not implement the
   vacuous `fieldValues[i].ordinal() == i` form.) This also requires adding the test source root
   and JUnit dependencies to `hipster-entity-api`, which has neither today.
8. **§ 9/4.7 first two doc defects:** the duplicated ladder in `materialization-levels.md` and the
   wrong Maven coordinates in `user/getting-started.md:9-11` (`hr.hrg` / `0.1.0`; the real
   coordinates are `hr.hrg.jcodebuddy` / `1.0-SNAPSHOT`, and no POM contains `0.1.0`) are the two
   that mislead newcomers today. (The stale validator message belongs to § 6.3/1.12, not § 9/4.7 —
   pre-execution cross-reference correction.) Run the **4.10** UTF-8
   transcode as soon as anything has to read `DEC-020.md`/`DEC-021.md`/`decisions/README.md` —
   they are cp1252-contaminated, and 4.8 must edit the index.
9. **Then Phases 6–7 (§ 11–12)** once Phases 1–5 are green. Phase 6 is on the critical path to
   "deep tracking works"; Phase 7's four features are independent of each other and of Phase 6,
   so they can run in any order or in parallel after Phase 4.

