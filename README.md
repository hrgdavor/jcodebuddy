### Project Summary: CodeBuddy

**Project Overview**
**CodeBuddy** is a sophisticated development orchestration framework for Java that automates the synchronization between project structure and source code. Unlike traditional annotation processing, which is isolated and happens during the compilation phase, CodeBuddy utilizes **JavaParser** to enable **cooperative code generation**. This allows generators to work together, sharing state and context to produce complex, interrelated code structures.

To ensure a seamless developer experience, the framework integrates **real-time file watching**. This creates a "live" development loop where changes to the project structure or configuration are immediately detected and reflected in the codebase via the cooperative generators.

**Architectural Convention**
The project enforces a strict **multi-module Maven structure** to incentivize modularity and maintain a clear separation of concerns:
*   **`app` module**: Houses the primary business logic and main codebase.
*   **Additional modules**: Users are encouraged to split their domain and infrastructure into separate modules.
*   **`project-automation` module**: A dedicated, recognizable module in every project that serves as the "brain" for the project's custom tooling. This module contains the specific logic for both the **file watchers** (the triggers) and the **cooperative generators** (the actions).

**Bootstrapping & Naming Strategy**
To solve the "recursion problem" (the fact that CodeBuddy uses itself to be built), a clear naming distinction has been established to prevent confusion for both human developers and AI coding agents:

1.  **The Tool (`CodeBuddy`)**: The framework/library providing the engine, JavaParser wrappers, and watching APIs.
2.  **The Implementation (`project-automation`)**: The project-specific module where the tool is applied.

By naming the implementation module `project-automation` rather than `code-buddy`, the project avoids naming collisions. In the main CodeBuddy repository, the `code-buddy-core` provides the power, while the `project-automation` module defines how that power is used to maintain and generate the framework itself.