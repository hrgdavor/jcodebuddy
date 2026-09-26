# Architecture Decisions

**See [decisions/README.md](decisions/README.md)** for the complete index of architecture decisions.

Architecture decisions are organized individually in the [decisions](decisions/) folder for better maintainability and discovery.

## Quick reference

### hipster-entity subsystem

| Decision                        | Title                                 | Status   |
| ------------------------------- | ------------------------------------- | -------- |
| [DEC-001](decisions/DEC-001.md) | Interface-first entity model          | Accepted |
| [DEC-002](decisions/DEC-002.md) | Documentation structure               | Accepted |
| [DEC-003](decisions/DEC-003.md) | Projection-oriented read path         | Accepted |
| [DEC-004](decisions/DEC-004.md) | Generated metadata over reflection    | Accepted |
| [DEC-005](decisions/DEC-005.md) | Field-source semantics                | Proposed |
| [DEC-006](decisions/DEC-006.md) | Build-time type divergence validation | Proposed |
| [DEC-007](decisions/DEC-007.md) | Projection performance vs ergonomics  | Accepted |
| [DEC-008](decisions/DEC-008.md) | Builder policy and naming             | Proposed |
| [DEC-009](decisions/DEC-009.md) | Source-visible generation             | Proposed |
| [DEC-010](decisions/DEC-010.md) | Proxy-backed view bridge              | Accepted |
| [DEC-011](decisions/DEC-011.md) | Builder interface generation          | Proposed |
| [DEC-012](decisions/DEC-012.md) | Update-array and change-tracking      | Accepted |
| [DEC-013](decisions/DEC-013.md) | Implementation selection factory      | Proposed |
| [DEC-014](decisions/DEC-014.md) | EnumSet concrete dispatch strategy    | Accepted |
| [DEC-015](decisions/DEC-015.md) | Field metadata method lookup strategy | Accepted |
| [DEC-016](decisions/DEC-016.md) | Field-name-to-ordinal: `forName`      | Accepted |
| [DEC-017](decisions/DEC-017.md) | Identifiable as opt-in identity mixin | Accepted |
| [DEC-018](decisions/DEC-018.md) | Generator freeze marker semantics     | Proposed |
| [DEC-019](decisions/DEC-019.md) | Source-visible, IDE-navigable wiring  | Accepted |
| [DEC-020](decisions/DEC-020.md) | Cooperative codegen — preserve user tweaks | Accepted |
| [DEC-021](decisions/DEC-021.md) | Generator class-file header + JSON5 config | Accepted |
| [DEC-022](decisions/DEC-022.md) | Refactor-sensitivity rules and divergence reporting | Accepted |
| [DEC-023](decisions/DEC-023.md) | R1 — field enums are append-only ordinal ledgers | Accepted |
| [DEC-024](decisions/DEC-024.md) | Deep change tracking — pull over push | Accepted |
| [DEC-025](decisions/DEC-025.md) | Field-enum compaction as an acknowledged migration | Accepted |
| [DEC-026](decisions/DEC-026.md) | `.jcodebuddy/` output root and its tracked/ignored split | Accepted |
| [DEC-027](decisions/DEC-027.md) | HTML reports rendered by Bun from JSON metadata | Accepted |
| [DEC-028](decisions/DEC-028.md) | Metadata addresses source through a central index | Accepted |
| [DEC-029](decisions/DEC-029.md) | Module class index keyed by fully qualified name | Accepted |
| [DEC-030](decisions/DEC-030-openrewrite-source-representation.md) | OpenRewrite as the source representation | Accepted |
| [DEC-031](decisions/DEC-031-project-automations-are-living-code.md) | Project automations are living code, never loaded artifacts | Accepted |
| [DEC-032](decisions/DEC-032.md) | `.jcodebuddy/` in two scopes — project and user home | Accepted |
| [DEC-033](decisions/DEC-033.md) | One serving host per project — port, health identity, claim | Accepted |
| [DEC-034](decisions/DEC-034.md) | Entity relations are key references, never object references | Proposed |

> This table is a quick-reference list and stops short of the detail on purpose. The authoritative index,
> with the notes that explain each decision, is [`decisions/README.md`](decisions/README.md).

