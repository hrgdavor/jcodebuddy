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
├── jcodebuddy-core          (leaf: markers + the generated-code parser)
├── jcodebuddy-codegen-api
│
└── project-automation          (strictly private: never installed, never deployed)
```

## Dependency Direction

### Layer 1: Framework Libraries
The following modules have **no dependency** on any other JCodeBuddy module:

| Module | Role |
|--------|------|
| `hipster-entity-api` | Shared entity interfaces and annotations |
| `java-watch-core` | File monitoring, hashing, change detection |
| `jwa-builder-api` | Lightweight annotations for JWA Builder |

`project-automation` used to be listed here and does not belong: it depends on Layer 2 modules, so it was
never a Layer 1 library, and it is now not a library at all. It is one project's private dev-time
assistant — never installed, never deployed, and not depended on by anything (see
[AGENTS.md § 1.1](../../AGENTS.md) and [DEC-W003](decisions-watch/DEC-W003.md)).

### Layer 2: Add-on Libraries
These modules depend on Layer 1:

| Module | Depends On |
|--------|-----------|
| `hipster-entity-core` | `hipster-entity-api` |
| `jwa-builder` | `jwa-builder-api` + `java-watch-core` |
| `hipster-entity-tooling` | `hipster-entity-api` + `hipster-entity-core` (test) |
| `jcodebuddy-codegen-api` | `hipster-entity-tooling` (for `SourceMetadata` only) |
| `jcodebuddy-core` | none — a leaf, and deliberately so |

### Layer 3: Applications & Runtimes
These modules depend on Layer 1 and/or Layer 2:

| Module | Depends On |
|--------|-----------|
| `watch` | `directory-watcher`, `slf4j` |
| `java-watch-scp` | `java-watch-core` |
| `java-watch-run` | `java-watch-core`, `ecj`, `polyglot` |
| `java-watch-run-sample` | `java-watch-run` (provided) |
| `jwa-sidecar` | `java-watch-core`, `jwa-builder-api`, `jwa-builder` |
| `java-watch-agent` | `java-watch-core`, `jwa-builder-api`, `jwa-builder`, `jcodebuddy-codegen-api` |
| `hipster-entity-jackson` | `hipster-entity-api`, `hipster-entity-core` |
| `hipster-entity-example` | `hipster-entity-core`, `hipster-entity-api` |
| `hipster-entity-test` | `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson` |

## Critical Boundaries

### `project-automation` — Dev-Time Only, and Strictly Private
- **This module is the ORCHESTRATOR.** It wires together generators from `java-watch-agent` and `hipster-entity-tooling`.
- It has compile-scope dependencies on `hipster-entity-api`, `java-watch-core`, `jwa-builder`, `hipster-entity-tooling`, `jcodebuddy-codegen-api`, `jackson-databind`, `metadata-server` and `metadata-mcp-server`. `javaparser-core` was removed on 2026-09-22 (Phase 6 of the rewrite migration); the source-manipulation representation is OpenRewrite's LST — see [DEC-030](../../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md).
- **It must NOT be a transitive dependency of any production/runtime module.**
- **No module may depend on it at all** — not in this reactor, not from a driver project. It is one
  project's own assistant, not a library; see [AGENTS.md § 1.1](../../AGENTS.md) and
  [DEC-W003](decisions-watch/DEC-W003.md).
- **It is never installed and never deployed.** `maven-install-plugin` and `maven-deploy-plugin` are
  skipped in its POM. `java-watch-agent` used to depend on it, which both broke the clause above and
  required the module to be published for the dependency to resolve — the two faults concealed each other.
  The reusable generator SPI (`CodeGenerator<T>`, `CodeContext`, `CodeContextImpl`, `TypeResolver`,
  `TypeDefinition`) was promoted to `jcodebuddy-codegen-api` so that neither is needed.
- `ProjectAutomationIsolationTest` asserts all of the above, so a removed skip or a new dependency fails
  the gate rather than silently reopening the hole.

### `hipster-entity-api` / `hipster-entity-core` — No Watch2 Dependency
- These modules **must not** depend on any `hr.hrg.watch2` (now `hr.hrg.jcodebuddy`) artifacts.
- They remain standalone and reusable outside the JCodeBuddy framework.

### `java-watch-core` — No Hipster Dependency
- This module **must not** depend on any `hr.hrg.hipster.entity` artifacts.

### `hipster-entity-tooling` — Standalone Library
- This module depends only on `hipster-entity-api` (and `hipster-entity-core` for tests), plus the OpenRewrite parser artifacts (`rewrite-core`, `rewrite-java`, `rewrite-java-25`).
- It has **zero dependency** on `project-automation` or any `watch` modules.
- `project-automation` consumes it as a library, and so does `jcodebuddy-codegen-api` — the latter for
  `SourceMetadata` alone, which is the one type its `CodeContext` carries.

### `jcodebuddy-core` — Generated-Code Markers and Their Parser, and a Leaf
- Holds `GeneratedCodeMarkers` (the marker vocabulary: how a generator spells one, and how a parser
  recognises one), `GeneratedCodeParser` (the parser that turns markers into line spans) and
  `GeneratedBlock` (a span).
- It exists so that a tool which is **not** the generator can find the generated regions of a file —
  DEC-035's vocabulary, DEC-020's cooperative preservation. The consumer is an external linter, a
  migration tool, an IDE inspection or an AI agent, and the point is that none of them can depend on a
  generator's internals.
- **It has no dependencies at all**, and that is the reason it is separate from
  `hipster-entity-tooling`, where the emitters live. The tooling carries the OpenRewrite LST, Jackson and
  the entity model; a tool that only wants to know where generated code stops should not resolve any of
  that. The dependency direction is the honest one: the generator depends on the vocabulary it emits, and
  the parser never depends on the generator.

### `jcodebuddy-codegen-api` — The Generator SPI, and a Leaf
- Holds exactly five types: `CodeGenerator`, `CodeContext`, `CodeContextImpl`, `TypeResolver`,
  `TypeDefinition`. All are leaf declarations depending on nothing but `SourceMetadata` and the JDK.
- It exists so that a tool which generates code can implement a generator **without depending on a
  project's `project-automation`** (AGENTS.md § 1.1). `java-watch-agent` is the consumer that forced the
  split: it implements `CodeGenerator`, and those types previously lived in `project-automation`, which
  made a JCodeBuddy library depend on a private dev-time assistant.
- It must stay thin. A type here that needs a generator *implementation* belongs in
  `hipster-entity-tooling` instead — this module holds the seam, not the machinery.

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
| `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-example`, `hipster-entity-jackson`, `hipster-entity-test`, `hipster-entity-tooling` | JUnit 5 (the default; no profile needed) |

JUnit 5 is not gated behind profile activation. Each module with tests declares
`junit-jupiter-engine` as an ordinary test dependency, and surefire 3.2.5 selects its
`surefire-junit-platform` provider automatically when a JUnit Platform engine is on the
test classpath. The root `junit5` profile still exists and `-Pjunit5` is still accepted,
but it is an empty no-op kept for invocation compatibility — it contributes no
configuration. Previously it injected the engine into surefire's plugin dependencies with
an illegal `test` scope, which made `-Pjunit5` abort the reactor at POM validation. See
``../../plans``.
