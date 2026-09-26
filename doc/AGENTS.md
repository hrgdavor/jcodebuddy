# AGENTS.md — rules for working on JCodeBuddy itself

> **Scope: the JCodeBuddy libraries, tooling and generators in this checkout.** This file is read by an
> AI coding agent (or a human) who is about to change JCodeBuddy *as a producer*. If you are working in a
> project under [`proto/`](../proto/README.md), read [`proto/AGENTS.md`](../proto/AGENTS.md) instead — a
> driver project is a **consumer**, and this file's rules are mostly about the producer's internals.
>
> The rows below marked **JCodeBuddy-only** do not bind a driver project. Which file wins, and the three
> rules nothing may suspend, are stated in the root [`AGENTS.md`](../AGENTS.md) § 3.

## Read first

| File | Why |
| ---- | --- |
| [`AGENTS.md`](../AGENTS.md) (root) | § 1 source-visible, IDE-navigable wiring and § 2's Bun-JavaScript rule bind everything, including this file. § 3 says how this file and `proto/AGENTS.md` relate. |
| [`README.md`](../README.md) | the recorded gate, the driver-project boundary, and the expectation that JCodeBuddy changes until a major release so that `proto/` projects can be implemented properly |
| [`doc/architecture/module-map.md`](architecture/module-map.md) | which module owns what, before you look for a home for a change |
| [`doc_knowledge/code.graph.md`](../doc_knowledge/code.graph.md) | the reading/querying/writing contract for Java source. Read it before touching any generator |
| [`doc-hipster-entity/architecture/decisions/`](../doc-hipster-entity/architecture/decisions/README.md) | the decision that governs whatever you are about to change, most likely |

## JCodeBuddy-only rules

These come from the root file's § 2 and are restated here in the terms that matter when you are the
*producer*. A driver project is not bound by them.

### The gate, and how to run it

The recorded gate is `bun scripts/mvn-jdk25.js` — the six `hipster-entity` modules, `clean test`, with
`-Dmaven.compiler.useIncrementalCompilation=false`. `clean` is not optional: without it a build can be
satisfied by a previous revision's class files, which is how a source that did not compile once reported
`BUILD SUCCESS`. One definition of the gate lives in `scripts/lib/gate.js`, and `GateContractTest`
asserts it.

**JCodeBuddy-only.** A driver project has its own build and its own commands; the gate is this
repository's, and no command in the root README's table builds anything under `proto/`.

### The reactor, and the one boundary inside it

The layout is multi-module Maven: a `project-automation` module orchestrates dev-time codegen, and
**runtime app modules depend on released JCodeBuddy libraries, never on `project-automation`** — a
`project-automation` module is a project's own dev-time assistant and is never shared or installed as an
artifact (DEC-031, DEC-W003).

**JCodeBuddy-only.** A driver project has exactly **one** `project-automation` module of its own and must
not mirror this reactor's module graph. The "never a transitive dependency" half *does* bind it.

### The tool chain this repository builds against

- **JDK 25** on both the Maven JVM and the forked test JVM. `.mvn/jvm.config` cannot select a JDK; the
  committed launchers set `JAVA_HOME` for you.
- **OpenRewrite's Lossless Semantic Tree** is the only Java source representation. No module declares
  `com.github.javaparser`. Read, query and write through `SourceReader` / `TreeQueries` / `SourceSplicer`,
  and never print a tree back to a file (DEC-030). The contract:
  - **reading** — `SourceReader.read(Path)` / `readText(String)`. The `Read` carries **two** channels:
    `readable()` is the verdict, `problemsIn(...)` is detail that is often empty even for a file javac
    rejects. A file javac *recovers* from is **not** a readable file (F-34). "A parse produced a result"
    is never the test.
  - **positions** — from javac, never from the tree, which has none.
  - **writing** — splice text; reprinting reformats the hand-written code around the change.
- **`metadata-arena`** for memory and off-heap work: `Arena`, `LongToLongsIndex`, the mmap formats. Do not
  invent another.
- **`hr.hrg.dialog` (dia-log)** for structured JSON logging, where a module needs it.

**JCodeBuddy-only.** A driver project consumes JCodeBuddy as an artifact and is under no obligation to
mirror these choices. (If a project *does* generate Java source through JCodeBuddy, the splice rule still
applies to it — but that is the library's API, not this repository's internal convention.)

### `.jcodebuddy/`, and the generated-`.java` rule

`.jcodebuddy/` is per-module and means "this module applies `project-automation`". `conf/` is the tracked
subtree; every other subfolder is derived and ignored by default, opted in with a `!` rule. **Generated
`.java` never goes there** — it stays under `src/main/java`. `GeneratorGuardTest` walks this repository
three levels deep and fails the build if a `.jcodebuddy/` it finds holds a `.java` file.

This rule binds a driver project too, **but read the guard's limit**: three levels covers
`proto/<project>/.jcodebuddy/` and not a project nested deeper. The guard's silence is not permission.

### Host state is not configuration

A port, a pid or a token is a fact about one checkout on one machine and belongs in the served project's
`.jcodebuddy/webview/`, never in `conf/` and never in `~/.jcodebuddy/`. A **port default** is
configuration; the **current port** and its pin are not. One serving host per project; never invent a
second copy of the port-claim rule — it is `HostPortClaim` (Java) and `BridgePolicy.decidePort` +
`HostRegistration.claimPort` (TypeScript) with shared vectors in `webview/conformance/bridge-decisions.json`
(DEC-032, DEC-033).

**JCodeBuddy-only** in its details (the webview modules are this repository's); the *principle* — config is
a choice that survives a clone, host state is a fact about one machine — applies anywhere.

## Working on a producer: what a change here owes the driver projects

**Until JCodeBuddy reaches a major release, the expected response to a driver project that cannot be
implemented properly is to change JCodeBuddy.** The projects under `proto/` are deliberately ahead of the
library; they are how a missing API, a wrong default or an unimplementable convention is found. So a
change here has an obligation that a change in a normal library does not:

1. **A driver project that breaks is information, not an obstacle.** If a change to a generator or an API
   breaks a project under `proto/`, the change is not finished. Update the project in the same change, or
   say in the change why it is unaffected.
2. **A workaround in a driver project is the failure mode.** If the project cannot do the right thing,
   the gap is here. Fix it here, or record it as an open question with the evidence — do not let the
   project paper over it, because the paper is what hides the finding.
3. **Changing a library means reinstalling it.** A driver project resolves `hr.hrg.jcodebuddy:*` from the
   **local** Maven repository, so a change to `hipster-entity-api` (or any consumed artifact) is invisible
   to it until that module is installed:
   ```sh
   mvn -o -pl hipster-entity-api install -DskipTests
   ```
   This is the most common "the driver project still sees the old code" confusing failure, and it is not a
   caching bug.
4. **Do not install `project-automation`.** A plain root `mvn install` publishes this repository's own
   `project-automation` to `~/.m2/repository/hr/hrg/jcodebuddy/project-automation/`, where an outside
   consumer could pick it up by accident — the one thing a project-automation module must never be. If a
   build does that, remove the artifact. The producer-side fix (skip installing that module) is not in
   place yet.
5. **No compatibility promise before the major release.** APIs, generated output shape, metadata formats
   and the `project-automation` conventions may change without a deprecation cycle. The R1 append-only
   ledger (DEC-023) and the cooperative-codegen rules (DEC-020/021/022) are the exceptions that exist to
   protect a *consumer's* data, and they are not suspended by this one.

## Where a change goes

Before writing code, find the module that owns the behaviour — [`doc/architecture/module-map.md`](architecture/module-map.md)
is the map. Two mistakes are common enough to name:

- **The generator is not the model.** A report (the entity HTML page and anything like it) is rendered by
  Bun from the JSON metadata a pass wrote, never by a Java generator. Adding a fact to a report means
  adding it to that JSON (DEC-027).
- **The decision comes first.** If a change alters the entity model, the generated code shape, a runtime
  contract, or a module boundary, it needs an ADR — see [`doc-hipster-entity/architecture/ADR-GUIDE.md`](../doc-hipster-entity/architecture/ADR-GUIDE.md)
  for when and how, and register it in the decisions index. A change that contradicts an Accepted
  decision either updates that decision or explains why it does not.
