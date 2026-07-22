// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg

# JWA Sidecar: Architectural Plan

The **Sidecar** is a headless, interactive alternative to the `java-watch-agent`. While the Agent focuses on background directory watching and automated CLI-based synchronization, the Sidecar integrates directly with IDEs via the Language Server Protocol (LSP) to provide real-time, on-demand code generation and navigation.

## 1. Shared Foundation
Both the `Agent` and the `Sidecar` must share the core "Tool Engine" to ensure consistent behavior across all environments.

- **`java-watch-core`**: Shared file scanning, config handling, and AST utilities.
- **`jwa-builder`**: Shared logic for processing Records and generating Builder members.
- **Tool Engine**: A unified transformation API that both interfaces call.

---

## 2. Headless Transformation Model
The Sidecar avoids direct File IO during processing. Instead, it returns logical edits that the IDE applies to its own memory buffers.

```java
public record CodeEdit(
    String uri,
    int startLine, int startCol,
    int endLine, int endCol,
    String replacementText
) {}

public record TransformationResult(
    List<CodeEdit> edits,
    List<NavigationTarget> navigation // For "Jump to secondary file"
) {}
```

---

## 3. Comparison: Agent vs. Sidecar

| Feature | `java-watch-agent` | `jwa-sidecar` |
| :--- | :--- | :--- |
| **Trigger** | File System Events (Watcher) | LSP Requests (Save, Command, Lightbulb) |
| **IO Strategy** | Surgical Disk Writes | WorkspaceEdits (VFS-aware) |
| **User Flow** | Background / "Live Synchronizer" | Interactive / "On-Demand Assistant" |
| **Navigation** | CLI/WebUI Logging | Remote Jump (`mytool/jump`) |

---

## 4. Implementation Steps

### Phase 1: Engine Alignment (COMPLETED)
Refactored `RecordBuilderProcessor` (and future tools) to be IO-agnostic. It now takes a `CompilationUnit` and returns a list of required alterations (`CodeEdit`) rather than assuming a `FileChange` result.
- [x] Universal `CodeEdit` model.
- [x] IO-agnostic `BuilderTransformationEngine`.
- [x] Agent refactored to use surgical edits.

### Phase 2: LSP Wrapper (COMPLETED)
Implemented the `LanguageServer` interface using **Eclipse LSP4J**.
- [x] Basic LSP Skeleton (`JwaLanguageServer`, `JwaTextDocumentService`, `JwaWorkspaceService`).
- [x] `textDocument/codeAction`: Triggers the "Sync Builder" lightbulb.
- [x] `workspace/executeCommand`: Runs the generator logic and triggers `workspace/applyEdit`.
- [x] Refine Record detection (targets specific record name line).
- [x] Handle incremental document updates.

### Phase 3: Remote Jump Service (COMPLETED)
Implemented a mini-service within the Sidecar that listens for navigation requests from the Agent's web UI and tunnels them to the IDE:
- [x] Custom LSP Notification (`mytool/jump`).
- [x] Extended Client/Server interfaces for bidirectional custom messaging.
- [x] HTTP Bridge (port 7979) with CORS support.

## Future Refinement
- [ ] Handle indentation configuration from client properly (currently defaulted to 4 spaces).
- [ ] Add more tools (e.g., toString/equals generator) following the same surgical pattern.

### Phase 4: Integration
Add `lsp4j` dependency to `jwa-sidecar/pom.xml` and wire the existing `RecordBuilderProcessor` into the LSP request handlers.

---

## 5. IDE Clients
The Sidecar serves as a universal backend for:
- **VS Code**: A lightweight extension acting as an LSP bridge.
- **IntelliJ**: Utilizing `LSP4IJ` for seamless integration.
- **Neovim/CLI**: Standard LSP configuration.

---

This architectural split allows the project to maintain one "Brain" (`jwa-builder`, etc.) while providing two distinct "Hands" (Agent for automation, Sidecar for interactivity).
