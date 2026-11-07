# Roadmap & Implementation Status

This document tracks roadmap progress, current work, and changes in direction for the hipster-entity project.

## 0. The usable MVP — declared 2026-11-05

**Yes, `hipster-entity` has a usable MVP.** A consumer-shaped project can add a marker and a view, run one
command to generate committed Java, compile it, run it, edit a view, and run again incrementally — locally,
with no daemon, no watch loop and no Maven Central release. The batch plan is
[`plans/hipster-entity-mvp.md`](../../plans/hipster-entity-mvp.md); its steps E0–E8 are done.

| Measured                                        | Result                                  |
| ----------------------------------------------- | --------------------------------------- |
| Clean consumer, full pass                       | **3.1 s**                               |
| Clean consumer, one-view edit + `--incremental` | **4.3 s**, `emitted 1, reused 1`        |
| Clean consumer, `--check`                       | **5.0 s**                               |
| Whole clean-consumer check                      | **20.3 s** (includes compile/run twice) |
| Example, second `bun scripts/gen.js`            | **no-op**, no HTML re-render            |

**In the MVP:** markers and `@View` discovery; `GenLevel.META`/`RECORD`/`BUILDER`/`BUILDER_TRACKED`/
`BUILDER_ALL`; field kinds, `@FieldSource` and addons; the generated field enum, `META`, record, builders and
tracking builder; `META.create`, array-backed read/update, `ViewChangeTracking`/`EEnumSet`; Jackson
read/change-set output; validation; metadata JSON and the class index; **one-shot `--incremental`**,
`--full` and `--check`; DTO projections and mappers as opt-in but incremental features.

**Watch mode is no longer deliberately out:** E11 landed on 2026-11-07 as daemon-only `--watch` (one project-root
daemon, module source-root subscription, same `EntityBuildState`); it adds no Java `WatchService`. Sidecar/LSP,
Maven Central/semver and SQL generation as a supported output remain out; patch appliers and merge are available
but force a full pass. **Parse reuse (E10) landed after the MVP declaration on 2026-11-06:** a warm pass over the
example parses nothing and a one-file edit parses one, with a printed/counted fallback when a view needs a
fact the base layer cannot supply. The current status page is
[`user/mvp-surface.md`](../user/mvp-surface.md); the incremental contract is
[DEC-052](../architecture/decisions/DEC-052.md).

**Verification.** The recorded full gate is green as of **2026-11-07**: `bun scripts/mvn-jdk25.js`,
**BUILD SUCCESS**, 18 modules, **7:46 min**, **617 tooling tests**, 0 failures. The targeted suites and the
clean-consumer check remain the edit-loop evidence.

> **This file is the status tracker; the schedule is [`plans/unified-plan.md`](../../plans/unified-plan.md).**
> (2026-10-01) The unchecked rows of § 1 and the `Proposed` rows of § 3 are the open work recorded here.
> The unified plan orders that work against everything else open in the repository, one gated step at a
> time. Keep the two in step: when a row closes here, its step closes there, and vice versa.

## 1. Roadmap Checklist

The unchecked rows here are **open work**, and the ones that are only partly done say so — an earlier
revision of this list showed `Mapper generation from TypeDescriptor deep flags` as open while
`ViewMapperGenerator` already existed, and `API/core/module responsibility split enforced by generator`
as open with no note that the split is deliberately deferred (plan.dsflash § 4.4/S3-X1). A **third**
revision on 2026-10-09 corrected the same class of mistake twice more: `Type divergence analyzer` and
`FieldAnnotation` were marked closed by their steps while each still had named work outstanding, which
[§ 6](#6-decisions-from-the-steps-61-65-refinement-2026-10-09) records along with what remains.

- [ ] Core entity interface contract (marker interface, per-package semantics) — the contract and its
  validator rule (`view_does_not_derive_from_marker`, `marker_declares_domain_method`) landed; what
  remains is deciding whether the conventions should be enforceable rather than advisory
  **-> closed in step 6.3** (the "advisory rules -> hard failures" decision, below)
- [ ] View interface hierarchy rules (summary/details/update patterns) — same: the naming rule is
  implemented and reported, not yet a hard failure in the example's build (`--validate`, not
  `--validate=STRICT`)
  **-> closed in step 6.3**: the same decision — its wording is exactly "should these be enforceable rather
  than advisory", which is one question, not two
- [x] Type divergence analyzer + converter manifest generation — **done 2026-10-26 (step 6.9)**: DEC-006 accepted
  and measured, all three criteria met, `TypeDivergence` and the contract-class wiring in; the manifest is generated
  into the tracked `.jcodebuddy/conf/converters.json`, sorted by source, target and location, byte-reproducible, and
  compared with a fresh pass by `ExampleConverterManifestTest` — see
  [§ 6](#6-decisions-from-the-steps-61-65-refinement-2026-10-09) item 2
- [ ] Projection + DTO marker pattern for SQL/NoSQL direct JSON output
  **-> closed in step 6.5** (done 2026-10-09: `@View(dto = true)`, `ViewJsonGenerator`, the example, 6 tests)
- [x] Annotation metadata exposure in generated view enums (FieldAnnotation) — **done 2026-10-26 (step 6.10)**:
  DEC-047, `FieldAnnotation`, `FieldDef.annotations()` and the emitter are in; the example carries the annotation
  (step 6.1), and the metadata JSON now carries the per-field `constraints` a document consumer needs. The "CLI is
  the JSON path" premise was corrected in [§ 6](#6-decisions-from-the-steps-61-65-refinement-2026-10-09) item 1.
- [ ] API/core module responsibility split enforced by generator — **deliberately deferred**: S3/X1 keep
  the tracking contract in `hipster-entity-core` for this release, so a generated tracking builder
  depends on `core` (plan.dsflash § 4.4). Not a gap, a recorded scope decision.
  **-> dropped by decision, not pending** (its own words: "Not a gap, a recorded scope decision")
- [x] Deep change tracking, the nested half (step 6.2) — **done 2026-10-26 (step 6.8)**. The runtime, the emitters
  and the per-view `<View>PatchApplier` are in; **unknown field is skipped and reported** (DEC-048 § 3), a missing
  converter fails a `STRICT` pass, and the remaining nested/collection/polymorphic recursion landed with the generated
  builder implementing its view's `Write`: a child's writer is `child.toBuilder()`, a stale collection index is a
  named finding, and a patch addressed to a marker root dispatches through the generated
  `<Root>PatchApplier`.
- [x] **Generated view-to-view mappers landed** — `ViewMapperGenerator` emits a
  statically-dispatched `static <Target> map(<Source>)` into the target's package, requested with
  `--mapper <Src>:<Tgt>[:<ClassName>]`, with a widening-only conversion table, a `null` plus a
  diagnostic for anything else, and both missing-field directions reported (plan.dsflash § 12.2).
  This is the row this checklist used to record as `Mapper generation from TypeDescriptor deep flags`;
  the `TypeDescriptor` deep-flag input is the part that is still open.
- [x] **Entity rules are wired and runnable** — `validate [<root>] [--strict]` as a subcommand and
  `--validate[=OFF|REPORT|STRICT]` on a generation pass; `ViewInterfaceRule` and `MarkerEntityRule`
  rewritten against the real conventions after running them over the example exposed 20 issues about
  19 correct interfaces. This closes plan.dsflash § 6.3/1.13.
- [x] **R1 ordinal-ledger contract landed** — a generated field enum's constant order is a persisted
  ordinal layout: constants are appended, never re-inserted, and a removed field is tombstoned
  (`@Deprecated` + `FieldDef.retired()` → `true`) rather than deleted. Scoped by the
  `entityFieldEnum:true` marker in DEC-021's header; enforced by `EnumConstantOrderChecker` /
  `EntityFieldEnumOrderRule` / `EnumConstantOrderCli` in `hipster-entity-tooling`. Recorded as
  [DEC-023](../architecture/decisions/DEC-023.md) and cross-referenced from the amended
  [DEC-012](../architecture/decisions/DEC-012.md).
- [x] **DEC-012 reconciled to Accepted and amended** — both indexes and the record now agree;
  the stale "Accepted notes" factory signature was corrected to the implemented
  `create(ForNameOrdinal, F[] universe, Object... values)`, and the record now points at DEC-023.
- [x] **Generator class-file header + refactor-sensitivity rules landed** — DEC-021
  (`{@link}` + JSON5 config header, `entityFieldEnum`/`allowReorder`/`enabled` knobs) and DEC-022
  (naming contract, uniform divergence format) are implemented and documented.
- [x] **Module READMEs and the new-project guide landed** — `hipster-entity/hipster-entity-core/README.md`,
  `hipster-entity/hipster-entity-tooling/README.md` (naming-contract table + R1 order contract),
  `hipster-entity/hipster-entity-test/README.md`, and
  [user/getting-started-new-project.md](../user/getting-started-new-project.md).
- [x] **Deep (nested) change tracking landed on the array path** — `changesDeep()` pulls into nested
  tracked views (`ChangePath`), a `List` of tracked views reports add/remove/reorder as `ListDelta`
  distinctly from per-index deltas, the leaf is reachable through the array-backed proxy exactly as
  on the builder path, and Jackson emits the deep result as an RFC 6902-like patch. Recorded as
  [DEC-024](../architecture/decisions/DEC-024.md) with its procedure in
  [user/patterns/deep-change-tracking.md](../user/patterns/deep-change-tracking.md). The
  **generator** wiring (task 6.5) and a patch applier are not part of this delivery.

## 2. Milestone status

| Milestone                     | Owner     | Status       | Notes                                                  |
| ----------------------------- | --------- | ------------ | ------------------------------------------------------ |
| Usable MVP (E0–E8)            | Core team | **Declared** | Full/incremental/`--check` + clean-consumer check; § 0 |
| `EntityBase` marker API       | Core team | Done         | In `hipster-entity-api`                                |
| Converter manifest proposal   | Core team | In progress  | Design in `doc/brainstorm`                             |
| SQL/Mongo projection pipeline | TBD       | Draft        | Begin in `doc/brainstorm`                              |
| Roadmap/architecture split    | Infra     | Done         | Folder restructure complete                            |

## 3. Decision traceability

| Decision  | Topic                                                                 | Delivery status | Notes                                                                                       |
| --------- | --------------------------------------------------------------------- | --------------- | ------------------------------------------------------------------------------------------- |
| `DEC-001` | Interface-first entity model                                          | Accepted        | Root contract and naming rules are documented                                               |
| `DEC-002` | Doc structure separation                                              | Implemented     | Folder split and linked docs exist                                                          |
| `DEC-003` | Projection-oriented read path                                         | Proposed        | Needs adapter shape and benchmark criteria                                                  |
| `DEC-004` | Generated metadata over reflection                                    | Proposed        | Needs metadata sufficiency and versioning policy                                            |
| `DEC-005` | Field-source semantics                                                | Proposed        | Needs write-path rules and diagnostics policy                                               |
| `DEC-006` | Build-time type divergence validation                                 | Proposed        | Needs converter registry and validation UX                                                  |
| `DEC-007` | Projection performance vs ergonomics                                  | Proposed        | Needs layered API examples and benchmarks                                                   |
| `DEC-008` | Builder policy and naming guarantees                                  | Proposed        | Needs final builder API and merge policy decisions                                          |
| `DEC-009` | Source-visible generation strategy                                    | Proposed        | Needs freeze semantics, patching rules, and sidecar workflow                                |
| `DEC-010` | Proxy-backed entity/view bridge                                       | Proposed        | Needs dispatch rules, strict diagnostics defaults, and proxy vs generated benchmarks        |
| `DEC-011` | Automatic builder interface generation                                | Proposed        | Needs canonical builder interface contract and compatibility test kit                       |
| `DEC-012` | Update-array and change-tracking semantics                            | Accepted        | Ruled: no-op-on-equal-assignment compared at the write site, explicit-null-sets-the-bit, and **no previous value kept** (`changedValues()` replaces `diff()`; the revision in the record supersedes D2); factory signature amended; R1 fields the append-only enum rule (see DEC-023) |
| `DEC-013` | Optional per-view impl. selection factory                             | Proposed        | Optional module; needs override precedence, fallback policy, and provider ordering contract |
| `DEC-014` | EnumSet concrete dispatch strategy                                    | Accepted        | Implemented with JMH benchmarks; strategy is optional for update tracking hot paths         |
| `DEC-015` | Generated field metadata method lookup                                | Accepted        | Switch-only method-name lookup for field enums, verified by JMH sensor benchmarks           |
| `DEC-016` | Field-name-to-ordinal dispatch (`forName`)                            | Accepted        | The generated `switch` is the only sanctioned name→ordinal path; a per-call `HashMap` is forbidden. `ViewMeta.forName()` returns the mapper — call `meta.forName().forName(name)` |
| `DEC-017` | `Identifiable<ID>` as opt-in identity mixin                           | Accepted        | Identity is opt-in; deep collection reorder detection requires it and falls back with a diagnostic without it |
| `DEC-018` | Generator freeze marker semantics                                     | Proposed        | The whole-file freeze is **real, in its per-file form, as of 2026-10-01**: `enabled:false` in DEC-021's header freezes the file (DEC-021 § 6 now states what is implemented rather than what was intended). DEC-018's own two spellings — the `@GeneratedFrozen` annotation and the `// generator:freeze` comment — are still read by no code path; only the header form is. |
| `DEC-019` | Source-visible, IDE-navigable wiring                                  | Accepted        | Annotations are markers; every connection is materialized as committed, navigable source    |
| `DEC-020` | Cooperative codegen (preserve user blocks)                            | **Accepted**    | Recognise prior output by structural shape, preserve it verbatim; opt back into regen by deleting the block |
| `DEC-021` | Generator class-file header                                           | **Accepted**    | Two `//` header lines above `package`; pinned JSON5 config with `enabled`, `entityFieldEnum`, `allowReorder` |
| `DEC-022` | Refactor-sensitivity and divergence reporting                         | **Accepted**    | Naming contract per generator; uniform `kind, location, cause, current, canonical, action` divergence format |
| `DEC-023` | R1 — field enums are append-only ordinal ledgers                      | **Accepted**    | Constant order is a persisted ordinal layout; append-only, tombstone instead of delete; `EnumConstantOrderChecker` CLI gates the build. Cross-referenced from the amended `DEC-012` |
| `DEC-024` | Deep (nested) change tracking — pull over push                        | **Accepted**    | `changesDeep()` pulls into nested tracked views without touching the parent's bitset; `ChangePath(field, listIndex, next)`; a `List` of tracked views reports add/remove/reorder as `ListDelta` distinctly from per-index deltas, identity (DEC-017) required for reorder detection and a diagnostic + fallback without it; deep JSON patch is RFC 6902-like and builds no name→ordinal map (DEC-016). Procedure in `user/patterns/deep-change-tracking.md` |
| `DEC-025` | Field-enum compaction is a deliberate, acknowledged ordinal migration | **Accepted**    | The only operation that may shorten a ledger, reachable only via `enum-compact`; needs both `--allow-reorder` and `--acknowledge-drained-data`, drops only recognisable tombstones, and prints every moved ordinal as the migration record |

## 4. Direction change log

- `2026-03-30`: moved docs into `doc/brainstorm`, `doc/architecture`, `doc/roadmap`.
- `2026-03-30`: added projection/JSON streaming path section to brainstorm.
- `2026-09-20`: `DEC-012` reconciled to **Accepted** in the record and in both indexes; its stale
  "Accepted notes" factory signature corrected; the R1 append-only field-enum rule recorded as the
  standalone **`DEC-023`** and cross-referenced from `DEC-012`.
- `2026-09-20`: the deliberate ordinal migration recorded as the standalone **`DEC-025`** — R1's
  escape valve for reclaiming retired ordinals — with its procedure in
  `user/patterns/field-enum-compaction.md`, and implemented as the `enum-compact` subcommand.
- `2026-09-20`: nested/deep change tracking recorded as the standalone **`DEC-024`** — pull
  propagation, `ChangePath`, the collection add/remove/reorder semantics and their identity
  requirement, and the deep JSON patch — with its procedure in
  `user/patterns/deep-change-tracking.md`.

## 4.1 Array-backed view proxy implementation plan

This plan targets read and updatable view proxies backed by ordered array storage.

### Goal

- Provide array-backed runtime materialization for read views and updatable interfaces.
- Keep field ordinal order deterministic and aligned with explicit field enums.
- Preserve compatibility with record-based materialization by matching array order and field types.
- Expand `hipster-entity-example` with concrete usage of these concepts.

### Current state

- Core has `EntityReadArray`, `EntityUpdateArray`, and `EntityUpdateTrackingArray*` primitives.
- Core now supports direct immutable enum-set snapshot constructors and tracking snapshots.
- Example module defines interface-first views but lacked array-backed proxy wiring.

### Phase 1: Field-order contracts (done in example)

- Define explicit view field enums per view contract (for example `PersonSummaryField`, `PersonUpdateFormField`).
- Encode expected Java type per field enum constant for validation and record mapping.
- Treat ordinal order as canonical array layout contract.

### Phase 2: Read proxy bridge (done in example)

- Add a read-proxy factory that wraps `EntityReadArray` and exposes target view interface via dynamic proxy.
- Support record-style accessor methods and strict method handling.
- Keep method-to-field mapping deterministic and explicit.

### Phase 3: Updatable proxy bridge (done in example)

- Add an updatable view interface with record-style getters and fluent mutators.
- Back mutators with `EntityUpdateTrackingArray` set/mark semantics.
- Expose change snapshot and reset methods for patch-style flows.

### Phase 4: Record materialization alignment (done in example)

- Add record types implementing read views (for example `PersonSummaryRecord`).
- Provide factory method converting from `ViewReader` using field enum order.
- Keep record component order and field enum order intentionally synchronized.

### Phase 5: Validation and hardening (next)

- Add focused tests for proxy method dispatch, strict error handling, and field type mismatch diagnostics.
- Add ordering-guard tests to verify enum ordinal and record component mapping stay aligned.
- Add example docs showing projection row -> read proxy -> updatable proxy -> record snapshot flow.

### Explicit invariants

- Field enum ordinal order MUST match array index positions.
- Field enum declared Java type SHOULD match runtime value type at that index.
- `id` field (ordinal 0) is immutable in update arrays.
- Updatable proxy mutators MUST track changes only on value change (no-op on equal assignment).

## 5. Entity Rule Quick Reference

This section is a delivery-oriented summary. Architecture documents remain the source of truth:
- [Entity interface design](../architecture/README.md)
- [Architecture decisions](../architecture/DECISIONS.md)

Current high-level rules:

1. Entity contract shape
- Every entity SHOULD have a minimal marker/root interface.
- The root interface MUST extend `hr.hrg.hipster.entity.api.EntityBase<IdT>`.
- Views SHOULD extend the root interface rather than redefining identity independently.

2. Package and naming conventions
- Each entity SHOULD live in its own package.
- View names SHOULD follow stable patterns such as `Summary`, `Details`, and `Update`.
- Accessors SHOULD remain record-style property methods.

3. Read/write semantics
- Views MAY declare mode semantics such as read-only or write-only.
- Derived and joined fields SHOULD not silently participate in normal write-path generation.

4. Module boundaries
- `hipster-entity-api` MUST contain shared contracts and annotations only.
- `hipster-entity-core` SHOULD contain infrastructure and generic behavior.
- `hipster-entity-example` MAY act as the canonical validation/demo module for concrete interfaces.

5. Tooling expectations
- Tooling MUST validate marker inheritance, package structure, and generated metadata assumptions.
- Generation SHOULD remain deterministic and aligned with the accepted ADR set.

## 6. Decisions from the steps 6.1–6.5 refinement, 2026-10-09

Steps 6.1–6.5 were closed as plan steps by their own `Done` records, and reading those records against the tree
found three things still open and one that was closed twice by mistake. The maintainer answered the open
questions; this section is the answer and a note on what it obliges.

**What was verified in the tree, rather than taken from the records**: `FieldAnnotation` and `TypeDivergence`
exist; `DEC-003` and `DEC-007` both read `Accepted` (6.5's gate); and **no generated class implements `Write`** —
`PersonSummaryBuilder` is `public final class PersonSummaryBuilder`, with no `implements` clause and zero matches
for `implements .*Write` anywhere in the example. That last one is the measurement DEC-048 § 5 made, re-checked
here because items 3–6 depend on it.

**The maintainer's decisions, as given:**

1. **The converter manifest is machinery output** — generated, committed, and byte-reproducible. It is not an
   authored allowlist in `conf/` and not a derived report: the pass writes it, and a committed copy that differs
   from what a pass would write today is a failure the way a stale committed enum is.
2. **A generated builder implements its view's `Write`.** This is the route from a view instance to a child's
   writer, which DEC-048 § 5 measured as missing, and it is what lets the applier recurse through the child's own
   generated applier — the answer chosen over a caller-supplied resolver and over emitting whole child values.
3. **The polymorphic dispatcher is generated on the root family**, dispatching on `discriminatorField()` against
   each subtype's `META.discriminatorValue()` — the shape `PaymentMethodController` hand-writes today, emitted
   instead. The convention itself already exists end to end (`@View(discriminatorField)`, the generated
   `discriminatorValue`, the generated `permittedSubtypes`); what was missing was only apply-time dispatch.
4. **Collection support is element writes only.** Add/remove/reorder wait for the runtime-bookkeeping reading
   DEC-048's Follow-ups already ask for. A **stale index fails with a named finding** rather than being skipped:
   the document is a description of changes and an applier may be stricter than its emitter.
5. **Only views reachable from a patch target** get an applier and a dispatcher, so a project pays nothing for a
   family it never patches.

**What this obliges, and the honest part of it.** Item 2 is an **emitter change to every generated builder**, not
an addition beside them: `PersonSummaryBuilder` gains `implements PersonSummary.Write`, which also closes a gap
DEC-048 § 5 recorded without fixing. Items 2–5 change generated output, so by this repository's own rule the
decision record comes first — **DEC-048 § 5 is amended** with these answers rather than a new ADR being opened for
one section's remainder, since § 5 is the section that named the question.

**Still open, and deliberately not answered here:**

- **6.1's example half — closed 2026-10-26 (step 6.10).** The premise measured on 2026-10-08 was wrong:
  `bun scripts/gen.js` runs `EntityMetadataGenerator`'s **source** path, not a JSON-reading CLI, and the example has
  carried `@NotNull` since step 6.1's Done record. The real gap was the JSON writer, which omitted the
  `constraints` the pass had already resolved; it now writes them, and `MetadataJsonAnnotationTest` asserts the
  document's property list emits the same `FieldAnnotation` metadata as the source path. No separate source → JSON
  CLI was needed.
- **6.9's manifest path — closed 2026-10-26 (step 6.9).** It lands in the tracked
  `.jcodebuddy/conf/converters.json` (DEC-026's `conf/` is the subtree that survives a clone), and its ordering is
  part of the format: source type, then target type, then location. Two passes over the same inputs are byte-identical
  and a changed pair changes the file; `ExampleConverterManifestTest` compares the committed copy with a fresh pass.

**Item 1 above was closed on 2026-10-26 (step 6.10), and item 2 by step 6.9 the same day.**

