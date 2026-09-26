# DEC-W003: Dev-time-only orchestrator boundary (project-automation)

- Status: Accepted
- Date: 2026-07-24
- Updated: 2026-09-26 — corrected to the tree, and extended with the *publishing* half of the rule.
- Owners: project
- Related docs: [Module map](../module-map.md), [AGENTS.md § 1.1](../../../AGENTS.md)
- Supersedes: -
- Superseded by: -

## Context

The `project-automation` module is the dev-time orchestrator that wires together code generators from
`java-watch-agent` and `hipster-entity-tooling`. It must remain completely isolated from production/runtime
code to ensure that automation tooling is never packaged into the final application artifact.

Two corrections to the original text of this record, both found on 2026-09-26 by reading the tree rather
than the decision:

1. **The original decision described a dependency that violated the rule it was stating.** It said
   `java-watch-agent` depends on `project-automation`, and that `project-automation` exposes the
   `CodeGenerator<T>` and `CodeContext` API. Both were true, and together they meant a JCodeBuddy library
   had a compile dependency on a project's private dev-time assistant — see the new clause below. The
   generator SPI now lives in `jcodebuddy-codegen-api`, and no module depends on `project-automation`.
2. **The original decision covered only the *runtime dependency* half of the rule.** It said nothing about
   installation or deployment, so the module was installed into `~/.m2` by an ordinary `mvn install` —
   the command the repository's own README tells a newcomer to run — and nothing in this record was
   violated. "Never a transitive dependency of a runtime module" and "never in a repository" are
   different guarantees, and only the second keeps the module private. Both are now stated, and both are
   enforced.

## Decision

`project-automation` is a **strictly private, dev-time-only module**. It is not a framework library and
not a Layer 1 library: it is one project's own assistant, and nothing else may build against it.

- **No other module may depend on it.** Not in this reactor, not in a sibling project, not from a driver
  project under `proto/`, not by a transitive path. This includes `java-watch-agent`, which used to — the
  types it needed were reusable and were promoted to `jcodebuddy-codegen-api` rather than shared through
  this module.
- **It MUST NOT be a transitive dependency of any production/runtime module** (Layer 3).
- **It is never installed.** `maven-install-plugin` is configured with `<skip>true</skip>` in this
  module's POM, so `mvn install` builds, tests and leaves it out of every local repository, including a
  redirected one.
- **It is never deployed or published.** `maven-deploy-plugin` is likewise skipped, and the module
  declares no `distributionManagement`. A project that later adds a publishing destination still cannot
  publish it.
- **Reusable parts are promoted, never shared through this module.** If a second place needs something
  that lives here, that is the signal it was never project-specific. Move it into a JCodeBuddy library and
  let both depend on that. `jcodebuddy-codegen-api` is the precedent: five leaf types (`CodeGenerator`,
  `CodeContext`, `CodeContextImpl`, `TypeResolver`, `TypeDefinition`) became a library so the rule could
  hold.
- It depends on `hipster-entity-api`, `java-watch-core`, `jwa-builder`, `hipster-entity-tooling`,
  `jcodebuddy-codegen-api`, `jackson-databind`, `metadata-server` and `metadata-mcp-server`.
  `javaparser-core` was removed on 2026-09-22 (Phase 6 of the rewrite migration); the source-manipulation
  representation is OpenRewrite's LST — see
  [DEC-030](../../../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md).

The module is the sole location for this project's automation code and configuration. No production
runtime code belongs here. It exists exclusively to assist development.

## Alternatives considered

- **Making project-automation a Layer 3 app** — rejected because it would force generation tooling into
  the runtime classpath and risk packaging it in the final artifact.
- **Merging project-automation into java-watch-agent** — rejected because it would make the agent
  dependent on generation orchestration logic, violating the single-responsibility boundary.
- **Using a separate build profile** — rejected because the compile-scope isolation in Maven is cleaner
  and more enforceable than profile-based inclusion/exclusion.
- **Sharing it with the modules that needed its types** (the path actually taken, and later undone) —
  rejected in 2026-09-26 because it made a library depend on a project's private assistant, and because
  satisfying that dependency required publishing the module the rule exists to keep private. The two
  faults concealed each other; either alone would have been caught.
- **Stating the rule in a document instead of a build configuration** — rejected, for the same reason:
  the rule *was* stated, in this record and in the README, and was still broken.

## Consequences

- Positive: strict boundary guarantees automation code never ships in a production artifact; clear module
  responsibility; framework consumers cannot inherit automation dependencies; and the module cannot reach
  a repository by any route, so a consumer cannot resolve it by accident.
- Negative: developers must keep automation-only code in this module, and a reusable type that appears here
  must be promoted to a library before a second consumer can use it — which is deliberate friction, since
  the alternative is a published private module.
- Follow-up: none outstanding. The original follow-up asked for "a CI check or `maven-enforcer` rule";
  this repository has no CI, so the check is a JUnit test instead —
  `ProjectAutomationIsolationTest` in `hipster-entity-tooling` reads the POMs and fails the gate if a skip
  is removed, if a module declares a dependency on a private module, or if a publishing destination
  appears.

## Acceptance criteria

- `project-automation` MUST NOT be a transitive dependency of any Layer 3 module.
- **No module MUST declare a dependency on `project-automation`, in any scope, in any profile.** Asserted
  by `ProjectAutomationIsolationTest.noModuleDependsOnAPrivateModule`.
- **`project-automation` MUST skip both `maven-install-plugin` and `maven-deploy-plugin`.** Asserted by
  `ProjectAutomationIsolationTest.everyPrivateModuleSkipsInstallAndDeploy`, which finds the module by
  artifactId so a rename cannot hide it.
- **No POM MUST declare a publishing destination that would let a private module leave the machine**
  (`distributionManagement`, `altDeploymentRepository`). Asserted by
  `ProjectAutomationIsolationTest.noPublishingRouteBypassesTheSkip`.
- All generators and automation configuration for *this* project MUST reside in this module; a reusable
  type MUST be promoted to a JCodeBuddy library instead.
- No code that is part of the published application MUST be in `project-automation`.
