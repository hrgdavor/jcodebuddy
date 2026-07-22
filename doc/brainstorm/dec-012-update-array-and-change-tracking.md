# DEC-012 Brainstorm: Builder proxies backed by update arrays and change tracking

This document explores backlog items about builder proxies using array-backed update storage, with and without explicit change tracking.

## 1. Problem statement

Builder workflows need efficient mutable storage during composition and update flows. The backlog proposes:
- a proxy-backed builder over `EntityUpdateArray`
- a proxy-backed builder over `EntityUpdateTrackingArray`

The missing piece is a clear semantic model for overwrite, presence, dirty tracking, and patch behavior.

## 2. Core concepts

## 2.1 EntityUpdateArray (baseline)

Intent:
- store latest assigned values by property ordinal
- prioritize speed and compact memory

Characteristics:
- overwrite semantics: last write wins
- no intrinsic knowledge of whether value changed from original
- suitable for full replace workflows

## 2.2 EntityUpdateTrackingArray (tracking variant)

Intent:
- track whether fields were touched during builder operations
- enable patch-oriented writes and selective persistence

Characteristics:
- stores value and touched/dirty flag per property
- supports partial update generation
- adds memory and logic overhead versus baseline

## 3. Semantic options

## 3.1 Presence model choices

Option A: bitmask touched flag (chosen)
- field is either untouched or touched, tracked via bitmask indexed by field ordinal
- touched with null means explicit null assignment
- field enums and ordinals exist precisely for this: one bit per ordinal, no parallel boolean arrays

Dirty tracking behavior:
- setter treats same-value assignment as no-op
- no-op assignment does not set the touched/updated bit
- changed-value assignment sets touched/updated bit

Decision:
- change tracking uses a bitmask keyed by field ordinal
- explicit null is represented as touched bit set with a null value slot
- there is no separate dirty-tracking implementation; same-value sets are no-op and remain unmarked

## 3.2 Merge behavior choices

When `mergeFrom` is used:

- overwrite-all: incoming values always replace
- non-null overwrite: only non-null incoming values replace
- touched-only merge: only fields marked touched in source apply

Recommendation:
- require explicit merge mode naming to avoid ambiguity

## 4. Runtime model sketch

Array-backed storage primitives:
- `Object[] values`
- touched mask (tracking variant) — two specialized implementations based on field count:
  - `EntityUpdateTrackingArray64` (compact): single `long` field for entities with fewer than 64 fields; no array allocation, single word test/set
  - `EntityUpdateTrackingArray` (wide): `long[]` for entities with 64 or more fields; sized to `ceil(fieldCount / 64)` longs
- dirty state is intrinsic to the tracking implementation: setting the same value is treated as a no-op and does not mark the field updated

Proxy handler responsibilities:
- mutator writes value and marks touched
- getter reads current value
- build materializes target object/view
- optional export of change set for write adapter

## 5. Write-path integration scenarios

1. Full replace write
- baseline array is enough
- all writable fields emitted

2. Patch update
- tracking required
- only touched fields emitted

3. Optimistic update with no-op equality check
- handled by the same tracking implementation
- setter compares incoming value to current value and performs no-op on equality
- emit only truly changed fields

## 6. Diagnostics and safety

Must-have diagnostics:
- detect attempt to write non-writable/derived field
- report unknown property ordinal access
- surface merge mode in logs/debug output

Safety concerns:
- accidental silent null propagation
- inconsistent touched semantics across implementations

## 7. Performance considerations

Baseline array likely offers:
- minimal allocation
- fast ordinal indexing

Tracking variant costs:
- extra arrays and branch checks
- potential higher cache pressure

Bitmask tracking properties:
- `EntityUpdateTrackingArray64` compact variant (`long`): single field, no heap allocation for the mask, one instruction per bit operation; covers the common case where field count is below 64
- `EntityUpdateTrackingArray` wide variant (`long[]`): array sized to `ceil(fieldCount / 64)`; same bit-per-ordinal layout, handles arbitrarily large entities
- field enum ordinals map directly to bit positions in both variants with no secondary lookup
- implementation selection is determined at generation time from the entity field count, transparent to callers

Recommendation:
- builder proxies are the baseline implementation and should be treated as sufficiently performant for most use cases
- materialized/generated boilerplate for additional performance should be introduced only for specific hotspots validated by profiling and benchmark evidence
- optimization decisions should use end-to-end impact share, not isolated speedups alone (for example, a 100% gain in a path contributing ~1% of total runtime is usually not a priority)

## 8. Alternatives

1. Map-backed update model
- easier introspection
- slower and more allocation-heavy

2. Generated mutable POJO per view
- intuitive debugging
- larger generated code surface

3. Immutable persistent state transitions
- safer functional semantics
- likely too costly for high-frequency write paths

## 9. Open questions

- Is null assignment always legal for nullable fields in patch mode?
- Do we expose change set as field ordinals, names, or property enum values?
- How are nested object updates represented?

## 10. Suggested acceptance signals for eventual ADR

- update semantics are unambiguous for overwrite, null, and merge behaviors
- tracking model is consistent across proxy and generated implementations
- write adapters can consume change sets deterministically
- benchmark demonstrates acceptable overhead for tracking mode relative to baseline
- any move from proxy baseline to generated boilerplate is justified by measured end-to-end workload impact, not microbenchmark delta alone
