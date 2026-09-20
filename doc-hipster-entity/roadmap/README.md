# Roadmap & Implementation Status

This document tracks roadmap progress, current work, and changes in direction for the hipster-entity project.

## 1. Roadmap Checklist

- [ ] Core entity interface contract (marker interface, per-package semantics)
- [ ] View interface hierarchy rules (summary/details/update patterns)
- [ ] Type divergence analyzer + converter manifest generation
- [ ] Projection + DTO marker pattern for SQL/NoSQL direct JSON output
- [ ] Mapper generation from `TypeDescriptor` deep flags
- [ ] Annotation metadata exposure in generated view enums (FieldAnnotation)
- [ ] API/core/module responsibility split enforced by generator
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
- [x] **Module READMEs and the new-project guide landed** — `hipster-entity-core/README.md`,
  `hipster-entity-tooling/README.md` (naming-contract table + R1 order contract),
  `hipster-entity-test/README.md`, and
  [user/getting-started-new-project.md](../user/getting-started-new-project.md).
- [x] **Deep (nested) change tracking landed on the array path** — `changesDeep()` pulls into nested
  tracked views (`ChangePath`), a `List` of tracked views reports add/remove/reorder as `ListDelta`
  distinctly from per-index deltas, the leaf is reachable through the array-backed proxy exactly as
  on the builder path, and Jackson emits the deep result as an RFC 6902-like patch. Recorded as
  [DEC-024](../architecture/decisions/DEC-024.md) with its procedure in
  [user/patterns/deep-change-tracking.md](../user/patterns/deep-change-tracking.md). The
  **generator** wiring (task 6.5) and a patch applier are not part of this delivery.

## 2. Milestone status

| Milestone                     | Owner     | Status      | Notes                       |
| ----------------------------- | --------- | ----------- | --------------------------- |
| `EntityBase` marker API       | Core team | Done        | In `hipster-entity-api`     |
| Converter manifest proposal   | Core team | In progress | Design in `doc/brainstorm`  |
| SQL/Mongo projection pipeline | TBD       | Draft       | Begin in `doc/brainstorm`   |
| Roadmap/architecture split    | Infra     | Done        | Folder restructure complete |

## 3. Decision traceability

| Decision  | Topic                                      | Delivery status | Notes                                                                                       |
| --------- | ------------------------------------------ | --------------- | ------------------------------------------------------------------------------------------- |
| `DEC-001` | Interface-first entity model               | Accepted        | Root contract and naming rules are documented                                               |
| `DEC-002` | Doc structure separation                   | Implemented     | Folder split and linked docs exist                                                          |
| `DEC-003` | Projection-oriented read path              | Proposed        | Needs adapter shape and benchmark criteria                                                  |
| `DEC-004` | Generated metadata over reflection         | Proposed        | Needs metadata sufficiency and versioning policy                                            |
| `DEC-005` | Field-source semantics                     | Proposed        | Needs write-path rules and diagnostics policy                                               |
| `DEC-006` | Build-time type divergence validation      | Proposed        | Needs converter registry and validation UX                                                  |
| `DEC-007` | Projection performance vs ergonomics       | Proposed        | Needs layered API examples and benchmarks                                                   |
| `DEC-008` | Builder policy and naming guarantees       | Proposed        | Needs final builder API and merge policy decisions                                          |
| `DEC-009` | Source-visible generation strategy         | Proposed        | Needs freeze semantics, patching rules, and sidecar workflow                                |
| `DEC-010` | Proxy-backed entity/view bridge            | Proposed        | Needs dispatch rules, strict diagnostics defaults, and proxy vs generated benchmarks        |
| `DEC-011` | Automatic builder interface generation     | Proposed        | Needs canonical builder interface contract and compatibility test kit                       |
| `DEC-012` | Update-array and change-tracking semantics | Accepted        | Ruled: no-op-on-equal-assignment compared at the write site, explicit-null-sets-the-bit, and **no previous value kept** (`changedValues()` replaces `diff()`; the revision in the record supersedes D2); factory signature amended; R1 fields the append-only enum rule (see DEC-023) |
| `DEC-013` | Optional per-view impl. selection factory  | Proposed        | Optional module; needs override precedence, fallback policy, and provider ordering contract |
| `DEC-014` | EnumSet concrete dispatch strategy         | Accepted        | Implemented with JMH benchmarks; strategy is optional for update tracking hot paths        |
| `DEC-015` | Generated field metadata method lookup     | Accepted        | Switch-only method-name lookup for field enums, verified by JMH sensor benchmarks          |
| `DEC-019` | Source-visible, IDE-navigable wiring      | Accepted        | Annotations are markers; every connection is materialized as committed, navigable source    |
| `DEC-020` | Cooperative codegen (preserve user blocks)| Proposed        | Recognise prior output by structural shape, preserve it verbatim; opt back into regen by deleting the block |
| `DEC-021` | Generator class-file header               | Proposed        | Two `//` header lines above `package`; pinned JSON5 config with `enabled`, `entityFieldEnum`, `allowReorder` |
| `DEC-022` | Refactor-sensitivity and divergence reporting | Proposed    | Naming contract per generator; uniform `kind, location, cause, current, canonical, action` divergence format |
| `DEC-023` | R1 — field enums are append-only ordinal ledgers | Proposed | Constant order is a persisted ordinal layout; append-only, tombstone instead of delete; `EnumConstantOrderChecker` CLI gates the build. Cross-referenced from the amended `DEC-012` |
| `DEC-024` | Deep (nested) change tracking — pull over push | Proposed | `changesDeep()` pulls into nested tracked views without touching the parent's bitset; `ChangePath(field, listIndex, next)`; a `List` of tracked views reports add/remove/reorder as `ListDelta` distinctly from per-index deltas, identity (DEC-017) required for reorder detection and a diagnostic + fallback without it; deep JSON patch is RFC 6902-like and builds no name→ordinal map (DEC-016). Procedure in `user/patterns/deep-change-tracking.md` |
| `DEC-025` | Field-enum compaction is a deliberate, acknowledged ordinal migration | Proposed | The only operation that may shorten a ledger, reachable only via `enum-compact`; needs both `--allow-reorder` and `--acknowledge-drained-data`, drops only recognisable tombstones, and prints every moved ordinal as the migration record |

## 4. Direction change log
jdk1.8.0_231/jre/bin/keytool -import -trustcacerts -alias myserver -file /opt/server.crt -keystore jdk1.8.0_231/jre/lib/security/cacerts

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

