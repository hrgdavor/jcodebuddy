# JWA Sidecar: Modularization Plan (Dynamic Loading)

The JWA Sidecar is evolving into a lightweight LSP shell that dynamically loads feature modules (like `jwa-builder`) based on project-specific configuration. This avoids the overhead of maintaining custom "bundle" JARs and leverages the existing Maven local repository (`.m2`).

## The Strategy: Dynamic `.m2` Loading

## The Strategy: Dynamic `.m2` Loading & Split Publishing

Instead of rebuilding the sidecar JAR for every combination of tools, the sidecar stays generic. Feature modules use a "Split Publishing" approach to keep project dependencies lightweight.

### 1. Split Publishing Pattern
Each feature module (e.g., `jwa-builder`) is split into two components:

1.  **API JAR (`jwa-builder-api`)**: 
    - Contains only annotations (e.g., `@GenerateBuilder`) and interfaces.
    - **Zero dependencies** (or very minimal).
    - Projects being enhanced depend on this JAR at compile time.
2.  **Implementation JAR (`jwa-builder`)**:
    - Contains the actual transformation logic (`BuilderTransformationEngine`).
    - Depends on `jwa-builder-api` and heavy libraries (`rewrite-java` plus the
      version-specific `rewrite-java-25` parser, and `Jackson`).
    - **Only the Sidecar** loads this JAR (and its dependencies) from `.m2`.

### 2. Project Configuration (`jwa-sidecar.txt`)
Projects define the **implementation** modules they want the sidecar to run:
```text
hr.hrg.watch2:jwa-builder:1.0-SNAPSHOT
./libs/custom-tool-impl.jar
```

### 3. External Prefetch Tool
The prefetch tool ensures that both the implementation JARs and their heavy dependencies (the OpenRewrite parser artifacts) are available in the local `.m2` repository.

## Why this approach?
- **Minimal Project Footprint**: Working projects only pull in a few KB of annotations, not the entire transformation engine or its dependencies.
- **Clean Separation**: The Sidecar handles the heavy lifting using the implementation JARs, while the code stays "marked" via the lightweight API.
- **Dynamic Updates**: Implementation logic can be updated in `.m2` without requiring any changes or re-builds of the projects being enhanced.

## What the sidecar module actually contains

Six classes, no tests — see the table in
[`README.md`](README.md#what-the-sidecar-is-today). The split described here is
about where the *worker* logic lives; the shell itself is protocol plumbing.

## Next Steps
1. Split `jwa-builder` into `jwa-builder-api` (annotations) and `jwa-builder` (implementation). — **done**, and the split is what the two poms above resolve.
2. Update the parent POM to manage both modules. — **done**.
3. Refactor Sidecar to load implementation JARs based on `jwa-sidecar.txt`. — **withdrawn, not done, and not to be
   done** ([DEC-031](../../doc-hipster-entity/architecture/decisions/DEC-031-project-automations-are-living-code.md)).
   This line used to read "done", which was wrong: no code in any language ever referenced that file, in this
   repository's whole history — only the documents that describe it. What exists is the *split* from step 1 plus
   a hard compile-time dependency from the sidecar's `pom.xml` on `jwa-builder`, so the builder is baked into the
   shaded jar and its code action is offered to every client.
   The replacement model is **living code**: an automation is a module in the project it automates, compiled by
   that project's own build and visible in code review, with no classloader, no service registry and no scanning.
   A host that needs those automations gets them on its **classpath at launch**, the way
   [`scripts/gen.js`](../../scripts/gen.js) already runs this repository's own generator. A new project starts
   from a **generated stub** (initial requirements in, a compiling automation module out) or by **copying an
   example** from another project — its own or one published online — and then editing it.
   DEC-031 records the reasoning, the accepted costs (no drop-in prebuilt jar; a copied example can drift,
   mitigated by DEC-022's divergence report) and the follow-up work this creates: the stub generator, a
   documented example to copy, and the sidecar's launch path that puts a project's automation module on its
   classpath. None of those three is done.
