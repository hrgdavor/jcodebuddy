# JCodeBuddy

**Project Overview**

Code generation has moved too much into background, and this project aims to provide coopeartive code generation where all generated code lives alongside manually typed code with clear visibility and deterministic behavior. The idea itself mimics one direction AI coding popularized where we accept the generated code directly into the codebase.

Technically **JCodeBuddy** is a development buddy(companion) for Java that automates the synchronization between project structure and source code.Unlike traditional annotation processing, which is isolated and happens during the compilation phase, JCodeBuddy reads and writes source through OpenRewrite's **Lossless Semantic Tree** to enable **cooperative code generation**: it reads a tree for its shape, splices generated members into the original text so nothing around them is reformatted, and asks javac — not the tree — where anything is. The reading/querying/writing contract is in [`doc_knowledge/code.graph.md`](doc_knowledge/code.graph.md). 

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

1.  **The Tool (`JCodeBuddy`)**: The framework/library providing the engine, the OpenRewrite-based source readers and splicers, and the watching APIs.
2.  **The Implementation (`project-automation`)**: The project-specific module where the tool is applied. **This is never packaged into the final deliverable.** It is the project's "brain" for defining automation behavior (used here, and suggested name for projects using JCodeBuddy).

### Dev-Time Only Guarantee
`project-automation` classes and dependencies do **not** participate when the project is packaged. The runtime application (the `app` module and friends) depends only on the released JCodeBuddy framework libraries. The `project-automation` module is the developer's customization layer that tells JCodeBuddy *what* to build, *when* to build it, and *how* to watch for changes.

## Building and Testing

The reactor requires **JDK 25** on *both* the Maven JVM and the forked test JVM — the root POM pins
`maven.compiler.release=25`, and `.mvn/jvm.config` cannot select a JDK (it only passes JVM options to
the Maven process). The committed launchers set `JAVA_HOME` for you:

| Command | What it does |
|---|---|
| `scripts/mvn-jdk25.js` | `mvn -o -pl <six hipster-entity modules> -am -Dmaven.compiler.useIncrementalCompilation=false clean test` — the recorded gate: compile and run the test set with JDK 25. It no longer **regenerates** anything; the generator is not part of the build (see `scripts/gen.js` below) |
| `scripts/mvn-jdk25.js hipster-entity test` | the same module set with an explicit goal (no implicit `clean`) |
| `scripts/mvn-jdk25.js hipster-entity install` | install the six modules into the local repository |
| `scripts/mvn-jdk25.js -o -pl <mods> -am test` | a free-form Maven invocation with the JDK pinned |
| `scripts/gen.js` | run the generator as a **side tool** (not a build step): regenerate the example's committed entity output. Compile-only — no jars, no `mvn install` |
| `scripts/gen.js with-tests` | the same pass, then the entity test set |
| `scripts/gen.js watch` | the same pass, then regenerate on every save (long-running, Ctrl+C to stop) |
| `scripts/run-demo.js` | builds and runs `PersonDemo`, the end-to-end walk (row array → view → JSON → tracking builder → JSON change set → changed columns → no-op write) |
| `scripts/entity-html/index.js` | render the HTML entity index from the JSON a pass wrote (DEC-027); `--module <dir>` for another module |

Every one of them is a **Bun** script — run them as `bun scripts/mvn-jdk25.js`, and so on. They were batch and
shell files until 2026-09-26; `AGENTS.md` § 2 requires Bun JavaScript for anything an agent writes to run or check
something, because a check that only runs in one shell on one OS is invisible wiring for the workflow. One
implementation also cannot drift from itself, which is the other reason: see the note on `GateContractTest` below.

**JCodeBuddy is a side tool, not a build step.** This project uses no annotation processing and
no compile hooks: `mvn compile`, `package` and `test` only compile the committed generated source
that already sits under `src/main/java`, because no execution in
`hipster-entity-example/pom.xml` carries a `<phase>`. The pass that actually rewrites that source is
`bun scripts/gen.js` — run by hand, or in `watch` mode — which compiles the tooling in the reactor,
exports the reactor classpath with `dependency:build-classpath` (the "no `mvn install` needed"
mechanism) and runs the generator with `java -cp`. It selects the same JDK 25 the Maven launcher uses,
by reading `JCODEBUDDY_JDK25`/`JAVA_HOME` and **running** the candidate to check its version, rather
than trusting whatever `java` is on `PATH`.

### How a pass gets triggered: three layers, only the first of which is required

JCodeBuddy works with **just the first layer**. The other two exist to make it more pleasant to use,
and neither is a prerequisite for the generator:

| Layer | What it is | Needed? |
|---|---|---|
| **The pass** | `java -cp … EntityMetadataGenerator …`, or the `bun scripts/gen.js` wrapper around it. Run it whenever you want the generated source refreshed. | **Required.** This is the whole tool. |
| **Watch mode** | The same pass, run continuously: `bun scripts/gen.js watch` (`EntityRegenerationWatcher`) regenerates after each save, so generated output keeps up with your edits without you asking. | Optional, and worth it — this is the normal development loop. Still just the generator, triggered by a file watcher. |
| **Sidecar / LSP** | IDE integration *on top of* watch mode: in-editor diagnostics, code actions, hover for the class-file header, divergence warnings. The `project-automation` module is the conventional home for it. | **Purely a user-friendliness expansion.** Not implemented for the entity generator in this repository; the pipeline above works fully without it. |

The important part of that table is the boundary between the last two rows: **watch mode is
JCodeBuddy's own live loop, not a sidecar feature.** A sidecar consumes what watch mode already
produces; it never replaces it, and removing the sidecar would leave the tool complete.

`clean` in the recorded gate is not optional: without it the build can be satisfied by a previous
revision's class files, which is how a source that did not compile once reported `BUILD SUCCESS`
(notes F-47). The `-Dmaven.compiler.useIncrementalCompilation=false` is the other half of the same
fix — it stops the compiler plugin from deciding within a run that a module is up to date. Both flags,
the six-module list and the `clean test` default are declared once, in `scripts/lib/gate.js`, which the
launcher, `scripts/gen.js` and `scripts/run-demo.js` all import; `GateContractTest` asserts that
definition, and asserts that no shell twin exists to diverge from it. (It used to be `GateParityTest`,
and the name was the problem: it existed because `mvn-jdk25.cmd` and `mvn-jdk25.sh` had already
diverged once, and a parity test only catches that after the fact.)

Override `JCODEBUDDY_JDK25`, `JCODEBUDDY_MVN` or `JCODEBUDDY_HE_MODULES` to point at another JDK,
another Maven launcher, or another module set. A Maven property argument must still arrive as **one
token** — `bun scripts/mvn-jdk25.js hipster-entity test "-Dtest=SomeTest"` — and a `-D` token with no
`=` is refused with exit 2, because that is the signature of a property some shell split before the
script saw it.

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

### The gate is local — there is no CI

This repository has **no CI workflow**, so the recorded gate above is the only thing that runs the
full test set, and nothing runs it unless a developer does. Two further checks are worth running by hand
before a commit that touches entities, because neither can be part of the build:

```text
# the entity rules: naming conventions, marker shape, the R1 ledger
#   exit 0 clean, 1 a violation, 2 a usage error
java -cp hipster-entity-tooling/target/classes hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     validate hipster-entity-example/src/main/java --strict

# the R1 append-only field-enum contract against a baseline revision
java -cp hipster-entity-tooling/target/classes hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     enum-order --repo . --baseline origin/main --strict
```

The example runs the first set on every **generation** pass — `bun scripts/gen.js`, manual or watched —
not on every build, because no build runs the generator at all. That pass is report-and-continue
(`--validate`), so its output is in the pass log; a clean example prints `Validation: no issues in ...`
plus three informational divergence lines (`addon_field_collision` ×2, `nested_record_reused`).
`--validate=STRICT` is how a project makes the pass refuse to write. Adding either command to a real CI
pipeline is a repository-policy decision, not a code change — the commands above are the whole
interface.

`ExampleRegenerationTest` is the other half of the safety net: it regenerates the example in place in a
temp copy and asserts the result is byte-identical to what is committed, so a generator change cannot
silently rewrite committed source. It calls the generator API directly, so it never depended on the
removed Maven binding and is unaffected by the generator moving out of the build. A red one means **regenerate
and commit**, not "fix the test".
