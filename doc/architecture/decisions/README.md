# Architecture Decisions Index

This folder contains individual decision records for the JCodeBuddy project architecture. The hipster-entity subsystem decisions (DEC-001 through DEC-018) are complemented by the watch & project-automation subsystem decisions (DEC-W001 through DEC-W005) in the sibling [decisions-watch](decisions-watch/) folder.

**Status categories:**
- `Accepted`: agreed direction used in current work
- `Trial`: direction being exercised before full acceptance
- `Proposed`: candidate decision under review
- `Superseded`: replaced by a newer decision
- `Rejected`: explicitly not adopted

## Decisions

### hipster-entity subsystem (DEC-001 — DEC-018)

| ID                    | Title                                                                   | Status   | Date       |
| --------------------- | ----------------------------------------------------------------------- | -------- | ---------- |
| [DEC-001](DEC-001.md) | **Interface-first entity model**                                        | Accepted | 2026-03-30 |
|                       | Notes: Root contract and naming rules documented                        |          |            |
| [DEC-002](DEC-002.md) | **Separate brainstorming, architecture, and roadmap documentation**     | Accepted | 2026-03-30 |
|                       | Notes: Folder structure and linking in place                            |          |            |
| [DEC-003](DEC-003.md) | **Projection-oriented read path**                                       | Proposed | 2026-03-30 |
|                       | Notes: Needs adapter shape and benchmark criteria                       |          |            |
| [DEC-004](DEC-004.md) | **Generated metadata over runtime reflection**                          | Proposed | 2026-03-30 |
|                       | Notes: Needs metadata sufficiency and versioning policy                 |          |            |
| [DEC-005](DEC-005.md) | **Field-source semantics**                                              | Proposed | 2026-03-30 |
|                       | Notes: Needs write-path rules and diagnostics                           |          |            |
| [DEC-006](DEC-006.md) | **Build-time type divergence validation**                               | Proposed | 2026-03-30 |
|                       | Notes: Needs converter registry and validation UX                       |          |            |
| [DEC-007](DEC-007.md) | **Projection performance vs ergonomics**                                | Proposed | 2026-03-30 |
|                       | Notes: Needs layered API examples and benchmarks                        |          |            |
| [DEC-008](DEC-008.md) | **Builder policy and naming guarantees**                                | Proposed | 2026-03-30 |
|                       | Notes: Needs final API and merge policy decisions                       |          |            |
| [DEC-009](DEC-009.md) | **Source-visible generation strategy**                                  | Proposed | 2026-03-30 |
|                       | Notes: Needs freeze semantics, patching rules                           |          |            |
| [DEC-010](DEC-010.md) | **Proxy-backed entity and view bridge**                                 | Proposed | 2026-03-30 |
|                       | Notes: Needs dispatch rules, diagnostics defaults                       |          |            |
| [DEC-011](DEC-011.md) | **Automatic builder interface generation**                              | Proposed | 2026-03-30 |
|                       | Notes: Needs canonical contract and test kit                            |          |            |
| [DEC-012](DEC-012.md) | **Update-array and change-tracking semantics**                          | Proposed | 2026-03-30 |
|                       | Notes: Needs touched/dirty/null semantics and merge contract            |          |            |
| [DEC-013](DEC-013.md) | **Optional per-view implementation selection factory**                  | Proposed | 2026-03-30 |
|                       | Notes: Optional module; needs override precedence and provider contract |          |            |
| [DEC-014](DEC-014.md) | **EnumSet concrete dispatch strategy**                                  | Accepted | 2026-03-31 |
|                       | Notes: JMH benchmarks validate dispatch benefit; strategy is optional   |          |            |
| [DEC-015](DEC-015.md) | **Generated field metadata method lookup strategy**                     | Accepted | 2026-04-01 |
|                       | Notes: Generated sorted arrays + binary search baseline; char-bucket optimization is optional and benchmark-gated |          |            |
| [DEC-016](DEC-016.md) | **Field-name-to-ordinal dispatch: `forName` + ordinal indexing; per-call HashMap forbidden** | Accepted | 2026-04-03 |
|                       | Notes: Mandates `ViewMeta.forName` + pre-built `readers[]`; prohibits per-call HashMap in all parse/map paths; see implementation guide in user docs |          |            |
| [DEC-017](DEC-017.md) | **Identifiable<ID> as opt-in identity mixin**                            | Accepted | 2026-04-03 |
|                       | Notes: Root entity identity is now explicit; `ViewReader` no longer declares `id()` |          |            |
| [DEC-018](DEC-018.md) | **Generator freeze marker semantics**                                   | Proposed | 2026-04-13 |
|                       | Notes: Defines `@GeneratedFrozen`, comment freeze markers, and frozen-file preservation policy |          |            |

### Watch & project-automation subsystem (DEC-W001 — DEC-W005)

| ID                    | Title                                                                   | Status   | Date       |
| --------------------- | ----------------------------------------------------------------------- | -------- | ---------- |
| [DEC-W001](DEC-W001.md) | **File-watching architecture (debounced batch delivery)**            | Accepted | 2026-07-24 |
|                       | Notes: `ManagedFileWatcher` and `BatchedFileWatcher` in `java-watch-core` |      |            |
| [DEC-W002](DEC-W002.md) | **Hot-swap daemon architecture for java-watch-run**                  | Accepted | 2026-07-24 |
|                       | Notes: Incremental ECJ compilation, URLClassLoader reload, native profile |      |            |
| [DEC-W003](DEC-W003.md) | **Dev-time-only orchestrator boundary (project-automation)**         | Accepted | 2026-07-24 |
|                       | Notes: `project-automation` must NOT be a transitive dependency of any runtime module | | |
| [DEC-W004](DEC-W004.md) | **Agent daemon architecture (java-watch-agent)**                      | Accepted | 2026-07-24 |
|                       | Notes: `ToolRegistry`, `ToolSetAgent`, `ProjectWatcher`, `CommandServer`, `InteractiveSession` | | |
| [DEC-W005](DEC-W005.md) | **Code generation interface contract (CodeGenerator/CodeContext)**   | Accepted | 2026-07-24 |
|                       | Notes: Unified `CodeGenerator<T>` interface with optional type resolution |        |            |

## Template

New decisions should follow this template:

```md
# DEC-XXX: Short title

- Status: Proposed | Trial | Accepted | Superseded | Rejected
- Date: YYYY-MM-DD
- Owners: team or person
- Related docs: links to brainstorm / roadmap / code
- Supersedes: DEC-... | -
- Superseded by: DEC-... | -

## Context
Why this decision is needed.

## Decision
What is being decided.

## Alternatives considered
- Option A
- Option B
- Option C

## Consequences
- Positive effects
- Negative effects
- Follow-up work

## Out of scope
- (optional) Items explicitly not covered by this decision

## Acceptance criteria
- Observable condition 1
- Observable condition 2
```

## Related documents

- [Brainstorm folder](../../brainstorm/) — Exploratory design work and candidate decisions
- [Roadmap tracking](../../roadmap/) — Implementation status and progress
- [Module map](../module-map.md) — Module layout, dependency layers, and critical boundaries
- [ADR-GUIDE](../ADR-GUIDE.md) — ADR authoring guide
