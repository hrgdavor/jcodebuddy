# Architecture Decisions Index

This folder contains individual decision records for the JCodeBuddy project architecture.

**Status categories:**
- `Accepted`: agreed direction used in current work
- `Trial`: direction being exercised before full acceptance
- `Proposed`: candidate decision under review
- `Superseded`: replaced by a newer decision
- `Rejected`: explicitly not adopted

## Decisions

### hipster-entity subsystem (DEC-001 — DEC-026)

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
| [DEC-012](DEC-012.md) | **Update-array and change-tracking semantics**                          | Accepted | 2026-03-30 |
|                       | Notes: Ruled method semantics: no-op-on-equal-assignment and explicit-null-sets-the-bit; amended to record the real factory signature `create(ForNameOrdinal, int, Object...)`; R1 pointer added |          |            |
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
| [DEC-019](DEC-019.md) | **Source-Visible, IDE-Navigable Wiring**                              | Accepted | 2026-09-03 |
|                       | Notes: Annotations are markers; every connection is materialized as committed source; applies to REST / JSON-RPC dispatchers (no `Map<String, Method>` + `Method.invoke`) | | |
| [DEC-020](DEC-020.md) | **Cooperative codegen — preserve user-tweaked generated blocks**      | Accepted | 2026-09-03 |
|                       | Notes: Default is **implicit block detection** by structural shape (case arm, builder method, scaffold method); no mandatory `// generator:begin/end` markers; single-line optional hint comment allowed; user opts into regen by deleting the block; reporting mode flags preserved-vs-canonical diffs; complements DEC-018 freeze and DEC-019 source-visible | | |
| [DEC-021](DEC-021.md) | **Generator class-file header — short id, JSON5 config, per-file options** | Accepted | 2026-09-03 |
|                       | Notes: Two `//` line comments above the imports — `// {@link <fqn>} <one-line description>.` plus `// {<json5>}`; JSON5 subset pinned to specific Jackson `JsonReadFeature`s (single-quoted strings, unquoted field names, trailing commas, leading decimal, leading plus, non-numeric numbers, backslash escape); mandatory `enabled` knob is the per-file equivalent of DEC-018 freeze; user edits a value to customise that file only | | |
| [DEC-022](DEC-022.md) | **Refactor-sensitivity rules for generated code and divergence reporting** | Accepted | 2026-09-07 |
|                       | Notes: Defines when generated names must track source renames (implicit derivation, wired through navigable `{@link}` / `@see` references) vs when they must be explicit API names (refactor-insensitive, e.g. JSON-RPC case labels); mandates divergence reporting when IDE refactor makes generated output differ from canonical; divergence format includes kind, cause, current vs canonical, and suggested action; each generator must publish a naming-contract table in its README | | |
| [DEC-023](DEC-023.md) | **R1 — Field enums are append-only ordinal ledgers**                   | Accepted | 2026-09-20 |
|                       | Notes: A generated field enum's constant order **is** a persisted ordinal layout, so constants are never re-inserted and a removed one is tombstoned (`@Deprecated` + `FieldDef.retired()` → `true`) rather than deleted; scoped by the `entityFieldEnum:true` marker in DEC-021's header (opt-in by absence; marker-less enums are bootstrapped, reporting `enum_constant_removed`); enforced by `EnumConstantOrderChecker` / `EntityFieldEnumOrderRule` / `EnumConstantOrderCli` (`--repo`, `--baseline`, `--target`, `--strict`; exit 0/1/2) with the removal-then-queue-jump comparison; `allowReorder:true` is the visible escape hatch; kinds `enum_order_shuffled` and `enum_constant_removed` use DEC-022's format | | |
| [DEC-024](DEC-024.md) | **Deep (nested) change tracking — pull over push**                     | Accepted | 2026-09-20 |
|                       | Notes: Propagation is **pull** — `changesDeep()` walks every field whose value is itself a `ViewChangeTracking` and ORs the child's state into the *reported* result without mutating the parent's bitset; `changes()`/`changesBuilder()` keep their shallow meaning and are never overloaded (the deep view is a third method); `ChangePath(field, listIndex /* -1 when n/a */, next /* null at leaf */)`; a `List` of tracked views reports add/remove/reorder as `ListDelta` (`ADDED`/`REMOVED`/`REORDERED`/`REPLACED`, plus `FIELD_CHANGED` for a per-index delta) **distinctly** from an in-place field delta, with reorder detection requiring an `Identifiable` element type (DEC-017) and a non-identifiable — or duplicate-identity — element type yielding a `CollectionDiagnostic` plus a positional fallback instead of a guess; the nested index is allocated only where a nested trackable field or a `List` field exists (DEC-014); the deep JSON patch is RFC 6902-like, names the leaf through an integer-indexed path array, and builds no name→ordinal `HashMap` (DEC-016) | | |
| [DEC-025](DEC-025.md) | **Field-enum compaction is a deliberate, acknowledged ordinal migration** | Accepted | 2026-09-20 |
|                       | Notes: The one operation that may shorten a ledger; reachable only through the `enum-compact` subcommand, never a normal pass. It refuses (exit 2, nothing written) unless **both** `--allow-reorder` (the schema may change) and `--acknowledge-drained-data` (no positional array, JSON patch or snapshot survives) are present — neither implies the other. It drops a constant only when it is `@Deprecated` **and** declares `retired() == true`, refuses a file it cannot parse, and verifies the result is a subsequence in order of the original before writing (the R1 checker cannot be the gate, because it reports a removal as a violation by design). It prints every dropped constant and every moved ordinal; that report plus the following regeneration is the migration record. Procedure in `user/patterns/field-enum-compaction.md` | | |
| [DEC-026](DEC-026.md) | **`.jcodebuddy/` — the per-module output root, and its tracked/ignored split** | Accepted | 2026-09-20 |
|                       | Notes: `.jcodebuddy/` is a **per-module** marker meaning "this module applies `project-automation`" — never a repository-wide default (the root MAY have one only for a repo-wide generator). Resolution is nearest marker, then nearest `pom.xml` as fallback, then system temp; a nearer `pom.xml` MUST NOT shadow a marker further up. Subfolders by purpose: `context/` (module specs, tracked), `metadata/{entity,watch,project}/` (derived, **ignored by default** with a documented `!` opt-in for projects that want metadata as a contract), `reports/` (run records, tracked), `agent-state/` (scratch, ignored). Every ignored subfolder keeps a tracked `README.md` so the taxonomy survives in git. Generated `.java` is explicitly **not** in `.jcodebuddy/` — it stays under `src/main/java` per DEC-019; and `.jcodebuddy/**` MUST be excluded from every scanner so a pass never observes its own report | | |

### Watch & project-automation subsystem (DEC-W001 — DEC-W005)

| ID                    | Title                                                                   | Status   | Date       |
| --------------------- | ----------------------------------------------------------------------- | -------- | ---------- |
| [DEC-W001](../../../doc/architecture/decisions-watch/DEC-W001.md) | **File-watching architecture (debounced batch delivery)**            | Accepted | 2026-07-24 |
|                       | Notes: `ManagedFileWatcher` and `BatchedFileWatcher` in `java-watch-core` |      |            |
| [DEC-W002](../../../doc/architecture/decisions-watch/DEC-W002.md) | **Hot-swap daemon architecture for java-watch-run**                  | Accepted | 2026-07-24 |
|                       | Notes: Incremental ECJ compilation, URLClassLoader reload, native profile |      |            |
| [DEC-W003](../../../doc/architecture/decisions-watch/DEC-W003.md) | **Dev-time-only orchestrator boundary (project-automation)**         | Accepted | 2026-07-24 |
|                       | Notes: `project-automation` must NOT be a transitive dependency of any runtime module | | |
| [DEC-W004](../../../doc/architecture/decisions-watch/DEC-W004.md) | **Agent daemon architecture (java-watch-agent)**                      | Accepted | 2026-07-24 |
|                       | Notes: `ToolRegistry`, `ToolSetAgent`, `ProjectWatcher`, `CommandServer`, `InteractiveSession` | | |
| [DEC-W005](../../../doc/architecture/decisions-watch/DEC-W005.md) | **Code generation interface contract (CodeGenerator/CodeContext)**   | Accepted | 2026-07-24 |
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
- [ADR-GUIDE](../ADR-GUIDE.md) — ADR authoring guide
