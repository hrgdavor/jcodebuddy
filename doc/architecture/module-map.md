# JCodeBuddy Module Map

## Final Module Layout

```
jcodebuddy-parent (POM)
│
├── watch
├── java-watch-core
├── java-watch-scp
├── java-watch-run
├── java-watch-run-sample
├── jwa-builder-api
├── jwa-builder
├── jwa-sidecar
├── java-watch-agent
│
├── hipster-entity-api
├── hipster-entity-core
├── hipster-entity-example
├── hipster-entity-jackson
├── hipster-entity-test
├── hipster-entity-tooling
│
└── project-automation
```

## Dependency Direction

### Layer 1: Framework Libraries
The following modules have **no dependency** on any other JCodeBuddy module:

| Module | Role |
|--------|------|
| `hipster-entity-api` | Shared entity interfaces and annotations |
| `java-watch-core` | File monitoring, hashing, change detection |
| `jwa-builder-api` | Lightweight annotations for JWA Builder |
| `project-automation` | **Dev-time orchestrator** (depends on Layer 2 modules) |

### Layer 2: Add-on Libraries
These modules depend on Layer 1:

| Module | Depends On |
|--------|-----------|
| `hipster-entity-core` | `hipster-entity-api` |
| `jwa-builder` | `jwa-builder-api` + `java-watch-core` |
| `hipster-entity-tooling` | `hipster-entity-api` + `hipster-entity-core` (test) |

### Layer 3: Applications & Runtimes
These modules depend on Layer 1 and/or Layer 2:

| Module | Depends On |
|--------|-----------|
| `watch` | `directory-watcher`, `slf4j` |
| `java-watch-scp` | `java-watch-core` |
| `java-watch-run` | `java-watch-core`, `ecj`, `polyglot` |
| `java-watch-run-sample` | `java-watch-run` (provided) |
| `jwa-sidecar` | `java-watch-core`, `jwa-builder-api`, `jwa-builder` |
| `java-watch-agent` | `java-watch-core`, `jwa-builder-api`, `jwa-builder`, `project-automation` |
| `hipster-entity-jackson` | `hipster-entity-api`, `hipster-entity-core` |
| `hipster-entity-example` | `hipster-entity-core`, `hipster-entity-api` |
| `hipster-entity-test` | `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson` |

## Critical Boundaries

### `project-automation` — Dev-Time Only
- **This module is the ORCHESTRATOR.** It wires together generators from `java-watch-agent` and `hipster-entity-tooling`.
- It has compile-scope dependencies on `hipster-entity-api`, `java-watch-core`, `jwa-builder`, `hipster-entity-tooling`, `jackson-databind`, and `javaparser-core`.
- **It must NOT be a transitive dependency of any production/runtime module.**
- `java-watch-agent` depends on `project-automation` to wrap `ActionTool` generators into the unified `CodeGenerator<T>` interface.

### `hipster-entity-api` / `hipster-entity-core` — No Watch2 Dependency
- These modules **must not** depend on any `hr.hrg.watch2` (now `hr.hrg.jcodebuddy`) artifacts.
- They remain standalone and reusable outside the JCodeBuddy framework.

### `java-watch-core` — No Hipster Dependency
- This module **must not** depend on any `hr.hrg.hipster.entity` artifacts.

### `hipster-entity-tooling` — Standalone Library
- This module depends only on `hipster-entity-api` (and `hipster-entity-core` for tests).
- It has **zero dependency** on `project-automation` or any `watch` modules.
- `project-automation` consumes it as a library.

## Excluded from Maven Reactor

The following directories are **NOT** part of the Maven build:

| Directory | Reason |
|-----------|--------|
| `vscode-jwa` | VS Code extension (npm/Gradle build) |
| `vscode-jswa` | VS Code extension (npm/Gradle build) |
| `intellij-jwa` | IntelliJ plugin (Gradle build) |
| `intellij-jswa` | IntelliJ plugin (Gradle build) |
| `jswa-core` | Vendored TypeScript/undici node_modules runtime |
| `demo` | Static HTML demo |

## Naming Convention Rationale

- **`jwa`** = Java Sidecar (JWA). Used by `jwa-builder`, `jwa-builder-api`, `jwa-sidecar`, `vscode-jwa`, `intellij-jwa`.
- **`jswa`** = JS/TS Sidecar (JSWA). Used by `vscode-jswa`, `intellij-jswa`, `jswa-core`.
- **`watch`** = Legacy file watcher module, retained for backward compatibility.

Do **not** rename `jswa` to `watch`. The `jwa`/`jswa` branding is intentional: Java vs JS sidecars.

## JUnit Strategy

| Layer | Test Framework |
|-------|---------------|
| `watch`, `java-watch-core`, `java-watch-scp`, `java-watch-run`, `jwa-builder-api`, `jwa-builder`, `jwa-sidecar`, `java-watch-agent` | JUnit 4 |
| `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-example`, `hipster-entity-jackson`, `hipster-entity-test`, `hipster-entity-tooling` | JUnit 5 (via `junit5` profile activation) |
