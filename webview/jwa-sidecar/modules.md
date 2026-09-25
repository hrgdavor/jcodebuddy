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
3. Refactor Sidecar to load implementation JARs based on `jwa-sidecar.txt`. — **NOT DONE, and never was.** This
   line used to read "done", which was wrong: no code in any language ever referenced that file, in this
   repository's whole history — only the documents that describe it. What exists is the *split* from step 1 plus
   a hard compile-time dependency from the sidecar's `pom.xml` on `jwa-builder`, so the builder is baked into the
   shaded jar and its code action is offered to every client. The dynamic loading described in
   [`README.md`](README.md#configuration-jwa-sidecartxt) needs a classloader over the listed paths and GAVs, and
   an SPI for what an addon contributes — neither exists, because `JwaTextDocumentService` calls the builder
   directly. Recorded here as open so the next reader does not plan around a feature that is not there.
