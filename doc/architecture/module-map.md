# JCodeBuddy Module Map

## Final Module Layout

```
jcodebuddy-parent (POM)
│
├── watch/                     the standalone watcher library and its own tools
│   ├── java-watch-app
│   ├── java-watch-core
│   ├── java-watch-scp
│   ├── java-watch-run
│   └── java-watch-run-sample
│
├── hipster-entity/            the entity model, its tooling, its examples
│   ├── hipster-entity-api
│   ├── hipster-entity-core
│   ├── hipster-entity-example
│   ├── hipster-entity-jackson
│   ├── hipster-entity-test
│   └── hipster-entity-tooling
│
├── jcodebuddy/                the JCodeBuddy libraries, the engine and the tools
│   ├── jcodebuddy-core        the engine: one parse path, the model, the index, the queries, freshness
│   ├── jcodebuddy-generated   the marker vocabulary and its parser — a leaf (DEC-035, step 3.0l)
│   ├── jcodebuddy-agent       the code-action server (was `java-watch-agent`; it left `watch/` at 3.0s)
│   ├── jcodebuddy-watch-tools
│   ├── jwa-builder
│   ├── jwa-builder-api
│   ├── jcodebuddy-meta        metadata serving: JSON-RPC and Fory over HTTP and Unix sockets (was `metadata-server`)
│   ├── jcodebuddy-meta-mcp    the MCP tool surface over it (was `metadata-mcp-server`)
│   └── metadata-arena
│
├── hipster-ioc/               the IoC product; its own `doc/` stays at the group root
│   ├── hipster-ioc-api
│   ├── hipster-ioc-tooling
│   └── hipster-ioc-test
│
├── webview/                   the webview product and its editor hosts
│   ├── core/webview-core
│   ├── core/webviewd
│   ├── jwa-sidecar            (the LSP sidecar lives with the webview product — PLAN-webview-suite D9)
│   └── eclipse/webview-eclipse
│
├── merge-java
└── project-automation         strictly private: never installed, never deployed (§ 1.1)
```

## Dependency Direction

### Layer 1: Framework Libraries
The following modules have **no dependency** on any other JCodeBuddy module:

| Module                 | Role                                       |
| ---------------------- | ------------------------------------------ |
| `hipster-entity-api`   | Shared entity interfaces and annotations   |
| `java-watch-core`      | File monitoring, hashing, change detection |
| `jwa-builder-api`      | Lightweight annotations for JWA Builder    |
| `jcodebuddy-generated` | DEC-035's marker vocabulary and the parser that reads it: three types, no compile dependency at all, so a tool that only wants to know where generated code stops resolves neither OpenRewrite nor Jackson (step 3.0l) |
| `jcodebuddy-meta`      | DEC-W006–W009's metadata serving: cache access over JSON-RPC and Apache Fory, on HTTP and Unix sockets. Declares slf4j, Jackson and Fory, and **no workspace artifact** — step 3.0m renamed it from `metadata-server` without changing what it depends on |
| `webview-core`         | The host-neutral webview kernel: the security model (`AllowedOrigins`, `RateLimiter`, `PathResolver`), `/health` (`HostHealth`), the port claim (`HostPortClaim`) and descriptor (`HostDescriptor`), page/file serving (`PageServer`) and the write surface (`WriteSurface`, `EditService`, `CheckpointStore`). Depends only on Gson |

`project-automation` used to be listed here and does not belong: it depends on Layer 2 modules, so it was
never a Layer 1 library, and it is now not a library at all. It is one project's private dev-time
assistant — never installed, never deployed, and not depended on by anything (see
[AGENTS.md § 1.1](../../AGENTS.md) and [DEC-W003](decisions-watch/DEC-W003.md)).

### Layer 2: Add-on Libraries
These modules depend on Layer 1:

| Module                   | Depends On                                          |
| ------------------------ | --------------------------------------------------- |
| `hipster-entity-core`    | `hipster-entity-api`                                |
| `jwa-builder`            | `jwa-builder-api` + `java-watch-core`               |
| `hipster-entity-tooling` | `hipster-entity-api` + `hipster-entity-core` (test) |
| `hipster-ioc-api`        | `hipster-entity-api`                                |
| `jcodebuddy-core`        | OpenRewrite (`rewrite-core`, `rewrite-java`, `rewrite-java-25` — DEC-030's one representation) and Jackson (`tools.jackson.core:jackson-databind`, the metadata JSON); JUnit in test scope |

`jcodebuddy-core` was a leaf with no dependencies at all until step 3.0f gave it the engine, which is what
DEC-037 decision 1 is about. **Step 3.0l extracted the generated-code marker vocabulary and its parser into
`jcodebuddy-generated`, a leaf of their own** — done 2026-10-03 — so a tool that only wants to know where
generated code stops resolves neither a Java parser nor a JSON library, while the engine is free to carry
both.

### Layer 3: Applications & Runtimes
These modules depend on Layer 1 and/or Layer 2, or are applications built from them:

| Module                   | Depends On                                                                                         |
| ------------------------ | -------------------------------------------------------------------------------------------------- |
| `java-watch-app`         | `directory-watcher`, `slf4j` — the watcher's own app, and no workspace artifact                    |
| `java-watch-scp`         | `java-watch-core`                                                                                  |
| `java-watch-run`         | `java-watch-core`, `ecj`, `polyglot`                                                               |
| `java-watch-run-sample`  | `java-watch-run` (provided)                                                                        |
| `jwa-sidecar`            | `java-watch-core`, `jwa-builder-api`, `jwa-builder`                                                |
| `jcodebuddy-agent`       | `java-watch-core`, `jwa-builder-api`, `jwa-builder`, `jackson-databind`, `slf4j` (it was `java-watch-agent`, and it left `watch/` at step 3.0s) |
| `jcodebuddy-watch-tools` | `jcodebuddy-agent`, `java-watch-core`, `jwa-builder`, `jwa-builder-api`, `slf4j`                   |
| `jcodebuddy-meta-mcp`    | `jcodebuddy-meta` and the `mcp` library — the MCP tool surface over the metadata server (was `metadata-mcp-server`; step 3.0m) |
| `hipster-ioc-tooling`    | `hipster-ioc-api`, `hipster-entity-tooling`, `java-watch-core`, `jcodebuddy-core`                  |
| `merge-java`             | OpenRewrite (`rewrite-core`, `rewrite-java`, `rewrite-java-25`, `rewrite-maven`) and `org.eclipse.jgit` — no workspace artifact |
| `webviewd`               | `webview-core` — the reference host of the webview contract, and the only one that needs no editor |
| `hipster-entity-jackson` | `hipster-entity-api`, `hipster-entity-core`                                                        |
| `hipster-entity-example` | `hipster-entity-core`, `hipster-entity-api`                                                        |
| `hipster-entity-test`    | `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson`                              |
| `hipster-ioc-test`       | `hipster-ioc-api`, `hipster-ioc-tooling` (test)                                                    |
| `webview-eclipse`        | `webview-core` (core + Gson unpacked into the bundle jar; Eclipse platform bundles are provided)   |

*This table describes the tree as of 2026-10-03 (step 3.0i). It is a description, not a check: nothing in the
build reads it, so a module that moves or gains a dependency has to be written here by hand in the same
change — which is what did not happen for `java-watch-agent`'s rename, three steps before this correction.*

## Critical Boundaries

### `project-automation` — Dev-Time Only, and Strictly Private
- **This module is the ORCHESTRATOR.** It wires together generators from `jcodebuddy-agent` and `hipster-entity-tooling`.
- It has compile-scope dependencies on `hipster-entity-api`, `java-watch-core`, `jwa-builder`, `hipster-entity-tooling`, `jcodebuddy-core`, `jackson-databind`, `metadata-server` and `metadata-mcp-server`. `javaparser-core` was removed on 2026-09-22 (Phase 6 of the rewrite migration); the source-manipulation representation is OpenRewrite's LST — see [DEC-030](../../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md). *(The engine replaced the SPI module here at step 3.0i; `jcodebuddy-core` is what `MetadataTypeResolver` extends, and it is also where the `SourceReader`/`TreeQueries` this module uses already came from — previously by inheritance through `hipster-entity-tooling`.)*
- **It must NOT be a transitive dependency of any production/runtime module.**
- **No module may depend on it at all** — not in this reactor, not from a driver project. It is one
  project's own assistant, not a library; see [AGENTS.md § 1.1](../../AGENTS.md) and
  [DEC-W003](decisions-watch/DEC-W003.md).
- **It is never installed and never deployed.** `maven-install-plugin` and `maven-deploy-plugin` are
  skipped in its POM. `java-watch-agent` used to depend on it, which both broke the clause above and
  required the module to be published for the dependency to resolve — the two faults concealed each other.
  The reusable generator SPI (`CodeGenerator<T>`, `CodeContext`, `CodeContextImpl`, `TypeResolver`,
  `TypeDefinition`) was promoted out of it so that neither is needed: first to a library of its own
  (`jcodebuddy-codegen-api`), and at step 3.0i into the engine, `jcodebuddy-core` — the module that library
  existed for, `jcodebuddy-agent`, no longer implements the SPI at all.
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
- `project-automation` consumes it as a library, and so does `hipster-ioc-tooling`. Its metadata types (the
  representation, the parse path, the class index) are the **engine's** since step 3.0f, and its own
  `meta`/`index` packages are re-exports of them; the emitters and the validators are what remains here.
  Until step 3.0i, `jcodebuddy-codegen-api` also depended on this module — for `SourceMetadata` alone, which
  was the one-type dependency that made the SPI impossible to implement next to the index it reads.

### `jcodebuddy-core` — The Engine

> **Where this came from ([DEC-037](../../doc-hipster-entity/architecture/decisions/DEC-037.md), `Accepted`
> 2026-10-02):** the module holds the one metadata engine — parsing, the metadata model, the indexes with
> their relations, search, and the freshness contract — and it gained OpenRewrite and Jackson with it (steps
> 3.0f–3.0h, and the generator SPI's own module dissolved into it at 3.0i). It also held the generated-code
> marker vocabulary until step 3.0l moved that out, which is what restored the leaf property for the one
> reader that needs no engine at all.

- Holds the engine: `engine/source` (the one parse path, through DEC-030's representation), `engine/meta`
  (the file-scoped model), `engine/index` (the class index of DEC-029, with relations), `engine/query` (the
  queries a consumer asks, and the generator-facing `TypeResolver` seam) and `engine/fresh` (the freshness
  contract that keeps an answer honest about its age). Plus `DiagnosticSink`, `JcodebuddyDirectory` and
  `MetadataJson`, which are the ports and the vocabulary the engine owns so that it never names a consumer.
- It exists because codegen, analysis, reporting, the watch loop and an LSP sidecar all need **fresh
  metadata**, and each used to grow a path of its own — a second index is the failure this module removes.
  See [`DEC-037`](../../doc-hipster-entity/architecture/decisions/DEC-037.md) and the note below.
- **It is a leaf no longer, deliberately**: a consumer of the engine resolves OpenRewrite and Jackson, which
  is the price of one model, one index and one freshness contract. The exception was carved out on purpose —
  a tool that only wants to know where generated code stops reads `jcodebuddy-generated` and nothing else.

### `jcodebuddy-generated` — The Marker Vocabulary, and a Leaf (since step 3.0l)
- Holds `GeneratedCodeMarkers` (how a generator spells a marker, and how a parser recognises one),
  `GeneratedCodeParser` (the parser that turns markers into line spans) and `GeneratedBlock` (a span) —
  three types, all importing nothing but `java.util`.
- It exists so that a tool which is **not** the generator can find the generated regions of a file —
  DEC-035's vocabulary, DEC-020's cooperative preservation. The consumer is an external linter, a migration
  tool, an IDE inspection or an AI agent, and the point is that none of them can depend on a generator's
  internals, nor resolve a Java parser or a JSON library to answer "where does it stop".
- **It has no compile dependency at all**, which is the reason it is a module rather than three classes in
  the engine. DEC-038 decision 1 is that split; step 3.0l executed it. The dependency direction is the
  honest one: a generator depends on the vocabulary it emits, the parser depends on nothing, and a parser
  that depended on a generator could not read a file produced by a different one.

### The generator SPI — `hr.hrg.jcodebuddy.engine.codegen`, in the engine (since step 3.0i)
- Holds `CodeGenerator`, `CodeContext`, `CodeContextImpl`; the engine's query seam holds `TypeResolver` and
  `TypeDefinition` (`hr.hrg.jcodebuddy.engine.query`). They were the module `jcodebuddy-codegen-api`, and
  DEC-037 decision 2 is why they are not one package: two are metadata queries, three are the SPI.
- The SPI exists so that a tool which generates code can implement a generator **without depending on a
  project's `project-automation`** (AGENTS.md § 1.1). That was the reason the five types were promoted into a
  library at all, and the reason it no longer needs to be one is that the engine — the only place an
  implementation can read the class index from — is their home now.
- It must stay thin. A type here that needs a generator *implementation* belongs in
  `hipster-entity-tooling` or the engine's own machinery — this is the seam, not the machinery.

## Excluded from Maven Reactor

The following directories are **NOT** part of the Maven build:

| Directory       | Reason                                          |
| --------------- | ----------------------------------------------- |
| `vscode-jwa`    | VS Code extension (npm/Gradle build)            |
| `vscode-jswa`   | VS Code extension (npm/Gradle build)            |
| `intellij-jwa`  | IntelliJ plugin (Gradle build)                  |
| `intellij-jswa` | IntelliJ plugin (Gradle build)                  |
| `jswa-core`     | Vendored TypeScript/undici node_modules runtime |
| `demo`          | Static HTML demo                                |

## Naming Convention Rationale

- **`jwa`** = Java Sidecar (JWA). Used by `jwa-builder`, `jwa-builder-api`, `jwa-sidecar`, `vscode-jwa`, `intellij-jwa`.
- **`jswa`** = JS/TS Sidecar (JSWA). Used by `vscode-jswa`, `intellij-jswa`, `jswa-core`.
- **`watch`** = Legacy file watcher module, retained for backward compatibility.

Do **not** rename `jswa` to `watch`. The `jwa`/`jswa` branding is intentional: Java vs JS sidecars.

## JUnit Strategy

| Layer   | Test Framework                           |
| ------- | ---------------------------------------- |
| `java-watch-app`, `java-watch-core`, `java-watch-scp`, `java-watch-run`, `jwa-builder-api`, `jwa-builder`, `jwa-sidecar`, `jcodebuddy-agent` | JUnit 4 |
| `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-example`, `hipster-entity-jackson`, `hipster-entity-test`, `hipster-entity-tooling`, `jcodebuddy-core`, `webview-core`, `webviewd`, `webview-eclipse` | JUnit 5 (the default; no profile needed) |

JUnit 5 is not gated behind profile activation. Each module with tests declares
`junit-jupiter-engine` as an ordinary test dependency, and surefire 3.2.5 selects its
`surefire-junit-platform` provider automatically when a JUnit Platform engine is on the
test classpath. The root `junit5` profile still exists and `-Pjunit5` is still accepted,
but it is an empty no-op kept for invocation compatibility — it contributes no
configuration. Previously it injected the engine into surefire's plugin dependencies with
an illegal `test` scope, which made `-Pjunit5` abort the reactor at POM validation. See
``../../plans``.
