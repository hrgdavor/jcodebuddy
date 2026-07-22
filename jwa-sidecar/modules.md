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
    - Depends on `jwa-builder-api` and heavy libraries like `JavaParser`.
    - **Only the Sidecar** loads this JAR (and its dependencies) from `.m2`.

### 2. Project Configuration (`jwa-sidecar.txt`)
Projects define the **implementation** modules they want the sidecar to run:
```text
hr.hrg.watch2:jwa-builder:1.0-SNAPSHOT
./libs/custom-tool-impl.jar
```

### 3. External Prefetch Tool
The prefetch tool ensures that both the implementation JARs and their heavy dependencies (like `JavaParser`) are available in the local `.m2` repository.

## Why this approach?
- **Minimal Project Footprint**: Working projects only pull in a few KB of annotations, not the entire transformation engine or its dependencies.
- **Clean Separation**: The Sidecar handles the heavy lifting using the implementation JARs, while the code stays "marked" via the lightweight API.
- **Dynamic Updates**: Implementation logic can be updated in `.m2` without requiring any changes or re-builds of the projects being enhanced.

## Next Steps
1. Split `jwa-builder` into `jwa-builder-api` (annotations) and `jwa-builder` (implementation).
2. Update the parent POM to manage both modules.
3. Refactor Sidecar to load implementation JARs based on `jwa-sidecar.txt`.
