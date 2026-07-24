# DEC-W003: Dev-time-only orchestrator boundary (project-automation)

- Status: Accepted
- Date: 2026-07-24
- Owners: project
- Related docs: [Module map](../module-map.md), [README.md](../README.md)
- Supersedes: -
- Superseded by: -

## Context

The `project-automation` module is the dev-time orchestrator that wires together code generators from `java-watch-agent` and `hipster-entity-tooling`. It must remain completely isolated from production/runtime code to ensure that automation tooling is never packaged into the final application artifact.

## Decision

`project-automation` is a **Layer 1 framework library** with compile-scope dependencies only (no transitive propagation):

- It exposes the `CodeGenerator<T>` interface and `CodeContext` API as the unified contract for all generators.
- It depends on `hipster-entity-api`, `java-watch-core`, `jwa-builder`, `hipster-entity-tooling`, `jackson-databind`, and `javaparser-core`.
- **It MUST NOT be a transitive dependency of any production/runtime module** (Layer 3).
- `java-watch-agent` depends on `project-automation` to wrap `ActionTool` generators into the `CodeGenerator<T>` interface.

The module is the sole location for automation code and configuration. No production runtime code belongs here. It exists exclusively to assist development.

## Alternatives considered

- **Making project-automation a Layer 3 app** — rejected because it would force generation tooling into the runtime classpath and risk packaging it in the final artifact.
- **Merging project-automation into java-watch-agent** — rejected because it would make the agent dependent on generation orchestration logic, violating the single-responsibility boundary.
- **Using a separate build profile** — rejected because the compile-scope isolation in Maven is cleaner and more enforceable than profile-based inclusion/exclusion.

## Consequences

- Positive: Strict boundary guarantees automation code is never shipped in production artifacts; clear module responsibility; framework consumers cannot accidentally inherit automation dependencies.
- Negative: Developers must be disciplined about keeping automation-only code in this module; there is no automated Maven enforcement that prevents production code from being added.
- Follow-up: Consider a CI check or `maven-enforcer` rule that verifies `project-automation` has no production-scope dependencies in consumer modules.

## Acceptance criteria

- `project-automation` MUST not be a transitive dependency of any Layer 3 module
- `project-automation` MUST compile-scope only (no runtime/shaded dependencies leaking to consumers)
- All generators and automation configuration MUST reside in this module
- No code that is part of the published application MUST be in `project-automation`
