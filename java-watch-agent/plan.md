# Java Watch Agent - Detailed Implementation Plan

The objective is to create a deterministic, modular developer utility that automates boilerplate generation and beyond. 

The IDE landscape has stalled, and although they ofer a lot and a lot of refactorings, and enourmous progress was made since first text editors with syntax highlight, it feels like they ran out of enthusiasm to do more (unless it is paywalled). This project may fail quickly, as providing great tools is not a small feat. At least I may be able to make utilities for myself.

Encourage folks to contiuously reduce need for cloud AI 
- by watching usage patterns 
- strive to make reliable and efficient tools
- if a prompt is more convenient than a tool
  - ask why
  - is it slower
  - is it less flexible
  - less user friendly
  - can it be improved so prompt is no longer better/easier/convenient option
  - if nothing else helps, can it be improved by a local model

Strict GPLV3 license for start.

## 1. Core Architecture

### 1.1 Dual-Trigger Architecture
Both triggers have equal priority, routing to the same underlying tools:
- **Comment-Driven (Passive)**: Monitors files via `java-watch-core`. Markers like `// @gen builder` trigger specific tools. An empty `// @gen` triggers the **Contextual Discovery** menu.
- **API-Driven (Active)**: A JSON-RPC/HTTP server allows IDEs to trigger actions or request discovery suggestions for the current cursor position.

### 1.2 Modular Tool Registry & Configuration
- **Interface-First**: Every action (Builder, DTO, etc.) is a standalone implementation of an `ActionTool`.
- **ToolSet Configuration**: Tools are organized into "ToolSets" defined in `.watch_agent.conf`.
    - Each ToolSet has a name (e.g., "java-core", "maven").
    - Each ToolSet defines **Scope**: Glob patterns for inclusion (`include`) and exclusion (`exclude`).
    - Each ToolSet defines **Active Tools**: Specific tools enabled for that scope.
    - Toolsets should not overlap in which files they match (nto a hard requirement, user should take care, we may display warning) 
- **Portability**: Tools are independent of the UI, allowing them to be reused by other systems like `opencode`.

### 1.3 Contextual Discovery

- **Static Analysis**: Uses `JavaParser` to analyze the code around the trigger site.
- **Inference Engine**: Logic-based rules suggest tools:
    - Current node is a `ClassOrInterfaceDeclaration` with fields? -> Suggest "Builder", "Getters/Setters", "Constructor".
    - Parent is a `RecordDeclaration`? -> Suggest "Inline Record Builder".
- **Discovery Loop**: Triggering `// @gen` without a command opens a menu of suggested tools in the TUI based on this analysis.

### 1.4 Interactive Terminal UI (TUI)
A persistent terminal session that acts as the command center:
- **Discovery Results**: Shows available tools when `// @gen` is hit.
- **Review Loop**: Every tool execution "parks" in the TUI for the user to:
    - `diff`: See exactly what changed.
    - `accept`: Commit changes.
    - `revert`: Roll back to the `before/` snapshot.
- **Browser Link**: Prints a URL to the **Companion HTML Interface** upon startup.

### 1.5 Companion HTML Interface
For a richer visual experience, the agent provides a web-based dashboard:
- **Automated Setup**: Accessible via a local URL (e.g., `http://localhost:6666`).
- **Multi-Instance Support**: If port 6666 is taken, it automatically increments (6667, 6668, etc.) and notifies via TUI.
- **Features**: Visual side-by-side diffing, batch acceptance of changes, and a graphical discovery dashboard.

### 1.6 Shared Action Engine Workflow
1. **Trigger**: Detect marker or receive API call.
2. **Analyze**: Determine context and (if needed) provide discovery suggestions.
3. **Resolve**: Link trigger to a specific `ActionTool` from the registry.
4. **Snapshot**: `AuditManager` creates a `before/` backup.
5. **Transform**: `ActionTool` executes and generates proposed code.
6. **Review**: Present results in TUI or HTML Interface for user finalization.

### 1.7 Persistent State & Maintenance (Fake Annotations)
To support long-running maintenance (e.g., updating a builder when fields/record components change), tools leave behind "Persistent State" markers:
- **Markers**: Comments containing JSON config, e.g., `// @watch:builder {"style": "inline", "fluent": true}`.
- **Auto-Sync**: When a file is saved, the agent checks if these markers exist and if the generated code is out of sync with its source. If out of sync, the TUI surfaces an "Update" action.

### 1.8 Editor Integration & Inverse Navigation
To allow the UI (TUI or HTML) to trigger actions in the editor:
- **Jump to Source**:
    - **CLI Handlers**: Use editor-specific CLI tools (e.g., `code --goto <file>:<line>`, `idea --line <line> <file>`).
    - **Customizable Templates**: Define command templates in `.watch_agent.conf` for different editors.
- **Clipboard Fallback**: 
    - Provide a "Copy Location" action that puts `filepath:line` into the clipboard.
    - Standard format ensures compatibility with most editor command palettes (e.g., VS Code `Ctrl+P`).
- **Instance Discovery (The "Handshake")**:
    - To identify which editor instance is "ours" among multiple live editors:
        - **Root Path Matching**: IDE plugins report their open workspace root via the JSON-RPC server. The agent matches this against its own project root.
        - **PID Tracking**: (Optional) Use `.watch/editor.pid` to track the specific active editor process.

## 2. Auditor & Change Tracking (The ".audit" System)

### 2.1 Per-Action Audit Trails
Every action that modifies files is recorded in a dedicated snapshot:
Location: `.watch/audit/YYYYMMDD_HHMMSS_action_name/`
- **summary.md**: Narrative description and JSON configuration block.
- **manifest.json**: File list with statuses (ADD/CHANGE/DELETE) and SHA256 hashes.
- **before/**: Subdirectory containing verbatim copies of original files.
- **after/**: Subdirectory containing copies of the files with transformations applied.

### 2.2 Metadata Cache (.watch/metadata/<toolset_name>/metadata.db)
To ensure the agent can detect changes that occurred while it was not running:
- **Partitioned Storage**: Metadata is isolated per ToolSet. Each set maintains its own checksum database and audit history within `.watch/metadata/<toolset_name>/`.
- **Project Snapshot**: On startup, the agent iterates through all defined ToolSets and compares the current project state (matching the set's globs) with its cached metadata.
- **Checksum Storage**: Stores SHA256 hashes (Wyhash64) and last-modified times for all scoped files.
- **Resumed Discovery**: If a file changed offline and matches a ToolSet's active tools/markers, the agent surfaces it in the TUI.
- **Maintenance**: TUI provides a `rebuild-cache <toolset>` command to force a full re-scan of a specific set.

## 3. Planned Tool Modules

### 3.1 Boilerplate & Generation
- **Getter/Setter/Constructor**: Standard POJO boilerplate.
- **BuilderGenerator**: Standard nested builder pattern for classes.
- **Inline Record Builder**: A specialized builder for Java `record` types that keeps a builder static inner class in sync with the record components.
- **DTOConverter**: Bean mapping generation.
- **StandardUtils**: `equals`, `hashCode`, `toString`.

### 3.2 Maintenance
- **ImportManager**: Unused import removal and sorting.
- **HeaderManager**: License and copyright enforcement.
- **StateSync**: Background logic that identifies `// @watch:` markers and suggests updates when their source code changes.

### 3.3 OpenRewrite Proposal
For complex refactorings and large-scale migrations, we propose integrating [OpenRewrite](https://github.com/openrewrite/rewrite):
- **Why**: OpenRewrite provides a sophisticated LST (Lossless Semantic Tree) that is superior for cross-file transformations and style-preserving rewrites.
- **Usage**: Use OpenRewrite "Recipes" as the underlying engine for tools like "System-wide renaming", "JUnit 4 to 5 migration", or "Library Upgrades".

## 4. Development Roadmap

### Phase 1: Audit & TUI Foundation
- [x] Implement `AuditManager`.
- [x] Implement `ToolRegistry` and `ActionTool` base.
- [x] Build the basic Interactive TUI loop.
- [x] Implement the **Metadata Cache** for offline change detection.

### Phase 2: Triggers & Discovery
- [x] Integrate `ManagedFileWatcher` and Command Server.
- [x] Implement the Contextual Analyzer for `// @gen` discovery.
- [x] Integration test: Empty `// @gen` opens the TUI menu.

### Phase 3: Core Tools & State
- [x] Implement `BuilderGenerator` (Class). (Done!)
- [x] Implement `Inline Record Builder`.
- [x] Implement Getter/Setter/Constructor generators.
- [x] Implement `StateSync` logic for maintenance triggers.

### Phase 4: User Interface & Connectivity
- [x] Build the **Companion HTML Interface** (Web dashboard).
- [x] Implement **Inverse Navigation** (Jump to source and Clipboard integration).
- [ ] Create lightweight hooks for IntelliJ and VS Code.
- [ ] Prototype an OpenRewrite-based tool module.
