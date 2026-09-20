# JCodeBuddy

**Project Overview**

Code generation has moved too much into background, and this project aims to provide coopeartive code generation where all generated code lives alongside manually typed code with clear visibility and deterministic behavior. The idea itself mimics one direction AI coding popularized where we accept the generated code directly into the codebase.

Technically **JCodeBuddy** is a development buddy(companion) for Java that automates the synchronization between project structure and source code.Unlike traditional annotation processing, which is isolated and happens during the compilation phase, JCodeBuddy utilizes **JavaParser** to enable **cooperative code generation**. 

To ensure a seamless developer experience, the framework integrates **real-time file watching**. This creates a "live" development loop where changes to the project structure or configuration are immediately detected and reflected in the codebase via the cooperative generators.

Code generators should to work together, sharing state and context to produce code structures in cooperation with the developer, and ideally common things should work their way into a core library, so generators do not depend on each other and are as standalone as possible.

## Project Scope & Current State

JCodeBuddy is designed as a **project automation and boilerplate generation** engine for Java. Its primary purpose is to eliminate repetitive coding tasks by generating and synchronizing code structures based on project configuration.

The project is currently in **early development**. What was originally intended as separate ecosystem projects — namely the **`hipster-entity`** (entity/DTO generation) and **`hipster-ioc`** (dependency injection container) modules — are temporarily integrated into this repository for faster iteration. Both modules are designed to use JCodeBuddy as their development-assistance engine, leveraging the cooperative code generation and live-watch capabilities to drive their own scaffolding and infrastructure code.

Once the core framework stabilizes, these modules will be extracted back into dedicated repositories.

## `project-automation` Architectural Convention

The project recommends a **multi-module Maven structure** to incentivize modularity and maintain a clear separation of concerns:
*   **`app` module**: Houses the primary business logic and main codebase.
*   **Additional modules**: Users are encouraged to split their domain and infrastructure into separate modules.
*   **`project-automation` module**: A dedicated, recognizable module in every project that serves as the **dev-time orchestrator** for the project's custom tooling. **This module is active only during development and does not participate in the project's packaged artifact.** It defines all automation behavior: when and how generators run (on demand, live while watching, or via LSP sidecar), what gets generated, and how toolsets are configured. Every project that uses JCodeBuddy must have exactly one `project-automation` module.

The `project-automation` module can either be a standalone project folder inside the Maven project using JCodeBuddy or a dedicated module within a multimodule setup. Its sole purpose is to contain all automation code and configuration for the project — no code that needs to ship in the published application should reside here, as its function is purely to assist development. 

> In 2026 context, think of it more like SKILLS folder for agents.

### Bootstrapping & Naming Strategy

To solve the "recursion problem" (the fact that JCodeBuddy uses itself to be built), a clear naming distinction has been established to prevent confusion for both human developers and AI coding agents:

1.  **The Tool (`JCodeBuddy`)**: The framework/library providing the engine, JavaParser wrappers, and watching APIs.
2.  **The Implementation (`project-automation`)**: The project-specific module where the tool is applied. **This is never packaged into the final deliverable.** It is the project's "brain" for defining automation behavior (used here, and suggested name for projects using JCodeBuddy).

### Dev-Time Only Guarantee
`project-automation` classes and dependencies do **not** participate when the project is packaged. The runtime application (the `app` module and friends) depends only on the released JCodeBuddy framework libraries. The `project-automation` module is the developer's customization layer that tells JCodeBuddy *what* to build, *when* to build it, and *how* to watch for changes.

## Building and Testing

The reactor requires **JDK 25** on *both* the Maven JVM and the forked test JVM — the root POM pins
`maven.compiler.release=25`, and `.mvn/jvm.config` cannot select a JDK (it only passes JVM options to
the Maven process). The committed launchers set `JAVA_HOME` for you:

| Command | What it does |
|---|---|
| `scripts/mvn-jdk25.cmd` (Windows) / `scripts/mvn-jdk25.sh` (POSIX) | `mvn -o -pl <six hipster-entity modules> -am test` — the recorded gate |
| `scripts/mvn-jdk25.cmd hipster-entity test` | the same run with an explicit goal |
| `scripts/mvn-jdk25.cmd hipster-entity install` | install the six modules into the local repository |
| `scripts/mvn-jdk25.cmd -o -pl <mods> -am test` | a free-form Maven invocation with the JDK pinned |
| `scripts/run-demo.cmd` | builds and runs `PersonDemo`, the end-to-end walk (row array → view → JSON → tracking builder → printed diff → parameterised `UPDATE`) |

Override `JCODEBUDDY_JDK25`, `JCODEBUDDY_MVN` or `JCODEBUDDY_HE_MODULES` to point at another JDK,
another Maven launcher, or another module set. Two cmd.exe details are worth knowing before editing
the `.cmd`: a Maven property argument must be **quoted** (`"...\mvn-jdk25.cmd hipster-entity test "-Dtest=SomeTest""`)
because cmd splits an unquoted argument at `=` and `.`, and the file must stay **ASCII + CRLF** or
cmd mis-parses it.

The `hipster-entity` shortcut **refuses to run** when any property argument lacks an `=` (exit 2,
with the quoted forms in the message), because that is what a property split by cmd.exe looks like.
That is deliberate: the fragments used to be forwarded as malformed arguments, Maven then dropped the
`-pl` list, and the "gate" silently became a build of the whole 23-module reactor — so a failure in an
unrelated module (`java-watch-scp`, `metadata-server`) looked like a failure of the recorded gate.
Quote **every** property you pass through the shortcut, including boolean ones
(`"-DskipTests=true"`), or invoke Maven directly with the same `-pl` list.

One consequence worth knowing: an unquoted `-Dtest=X` on the shortcut used to reach Maven as
`-Dtest X`, which Maven reported as `Unknown lifecycle phase "X"`. It is now refused earlier, with the
fix in the message.

Entity-specific generators, the R1 field-enum order contract and its checker CLI are documented in
[`hipster-entity-tooling/README.md`](hipster-entity-tooling/README.md); the architecture decisions
behind them are under [`doc-hipster-entity/architecture/decisions/`](doc-hipster-entity/architecture/decisions/).
