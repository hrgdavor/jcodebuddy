# DEC-W004: Agent daemon architecture (java-watch-agent)

- Status: Accepted
- Date: 2026-07-24
- Owners: project
- Related docs: [Module map](../module-map.md)
- Supersedes: -
- Superseded by: -

## Context

The `java-watch-agent` module is the AI agent daemon that scaffolds, watches, and generates code for a Java project. It must coordinate multiple tools, detect when tools should run, and expose an interface for reviewing and applying generated changes.

## Decision

`java-watch-agent` uses a multi-component architecture:

1. **`WatchAgent`** — entry point; reads `.watch_agent.conf`, registers tools, spawns `ToolSetAgent` instances, starts `ProjectWatcher` and optional `CommandServer`.
2. **`ToolRegistry`** — holds `ActionTool` instances (`HelloTool`, `BuilderGenerator`, `AccessorGenerator`, `ConstructorGenerator`, `RecordBuilderGenerator`).
3. **`ToolSetAgent`** — per-toolset orchestrator that walks the project tree, uses `MetadataCache` for checksum-based change detection, runs `ContextualAnalyzer` for trigger/watch discovery, dispatches through `ActionEngine`, tracks dependencies via `DependencyTracker`, and produces `PendingAction` objects consumed by `InteractiveSession`.
4. **`ProjectWatcher`** — wraps `ManagedFileWatcher` from `java-watch-core` on the project root; dispatches file events to all `ToolSetAgent` instances.
5. **`CommandServer`** (HTTP) — web interface for reviewing/applying pending actions.
6. **`InteractiveSession`** (CLI) — interactive loop for action management.
7. **`ContextualAnalyzer`** — discovers tool triggers in source and cross-file `@Watch` annotations.
8. **`ActionEngine`** — runs tools with audit logging via `AuditManager`.

## Alternatives considered

- **Single monolithic agent** — rejected because combining watching, discovery, execution, and UI into one class would create an unmaintainable god object.
- **External orchestration framework** — rejected because `java-watch-agent` is the natural place for these responsibilities and adding an external framework would increase complexity without benefit.
- **Event bus for internal communication** — rejected for now; direct method calls between components are sufficient for the current scope and make the control flow easier to debug.

## Consequences

- Positive: Clear separation of concerns; each component is testable in isolation; the HTTP CLI provides flexibility for both automated and interactive use.
- Negative: The component count may feel high for a dev-time tool; the `ToolSetAgent` is the most complex component and requires careful testing.
- Follow-up: Consider extracting `MetadataCache` into a shared library if other modules need checksum-based change detection.

## Acceptance criteria

- `ToolRegistry` MUST allow registering and discovering `ActionTool` instances by name
- `ProjectWatcher` MUST dispatch file events to all registered `ToolSetAgent` instances
- `CommandServer` MUST expose a REST endpoint for listing pending actions
- `InteractiveSession` MUST support at least: list, apply, and reject actions
- `ContextualAnalyzer` MUST support `@Watch` annotation discovery
