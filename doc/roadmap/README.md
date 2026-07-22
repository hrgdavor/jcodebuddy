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
| `DEC-012` | Update-array and change-tracking semantics | Proposed        | Needs touched/dirty/null semantics and merge-mode contract                                  |
| `DEC-013` | Optional per-view impl. selection factory  | Proposed        | Optional module; needs override precedence, fallback policy, and provider ordering contract |
| `DEC-014` | EnumSet concrete dispatch strategy         | Accepted        | Implemented with JMH benchmarks; strategy is optional for update tracking hot paths        |
| `DEC-015` | Generated field metadata method lookup     | Accepted        | Switch-only method-name lookup for field enums, verified by JMH sensor benchmarks          |

## 4. Direction change log
jdk1.8.0_231/jre/bin/keytool -import -trustcacerts -alias myserver -file /opt/server.crt -keystore jdk1.8.0_231/jre/lib/security/cacerts

- `2026-03-30`: moved docs into `doc/brainstorm`, `doc/architecture`, `doc/roadmap`.
- `2026-03-30`: added projection/JSON streaming path section to brainstorm.

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

