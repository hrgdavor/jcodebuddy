# JCodeBuddy

**Project Overview**

**JCodeBuddy** is a sophisticated development orchestration framework for Java that automates the synchronization between project structure and source code. Unlike traditional annotation processing, which is isolated and happens during the compilation phase, JCodeBuddy utilizes **JavaParser** to enable **cooperative code generation**. This allows generators to work together, sharing state and context to produce complex, interrelated code structures.

To ensure a seamless developer experience, the framework integrates **real-time file watching**. This creates a "live" development loop where changes to the project structure or configuration are immediately detected and reflected in the codebase via the cooperative generators.

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
