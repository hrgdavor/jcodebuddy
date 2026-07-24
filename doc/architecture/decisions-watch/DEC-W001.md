# DEC-W001: File-watching architecture (debounced batch delivery)

- Status: Accepted
- Date: 2026-07-24
- Owners: project
- Related docs: [Module map](../module-map.md), [java-watch-core](../README.md)
- Supersedes: -
- Superseded by: -

## Context

The JCodeBuddy framework needs reliable file-system change detection to trigger code generation and hot-reload. Two strategies exist: immediate per-event handling and debounced batch delivery. The watch modules must detect changes, filter them, and deliver them to consumers without loss or duplicate processing.

## Decision

The `java-watch-core` module provides two watcher implementations:

1. **`ManagedFileWatcher`** — delivers individual file events after a debounce delay. Ignores `DELETE` events by default (consumers are typically compile triggers, not deletion handlers).
2. **`BatchedFileWatcher`** — accumulates all changes within a debounce window and delivers them as a single `ChangeSet`. Handles `DELETE` events and `OVERFLOW` (full recompile signal).

Both use **trailing-edge debounce**: the timer resets on every new event, and the batch fires only after a quiet window of `delayMs` milliseconds with no new changes.

Consumers receive an immutable `ChangeSet` containing `changed()`, `deleted()`, and `fullRecompile()` fields.

## Alternatives considered

- **Immediate per-event delivery** — rejected because rapid saves produce duplicate redundant events that waste compile cycles.
- **Fixed-interval polling** — rejected because polling introduces latency and misses delete events on some platforms.
- **Leading-edge debounce** (fire on first event, ignore burst) — rejected because it drops the last change in a burst, which is the most common case for save-triggered workflows.

## Consequences

- Positive: Reliable change detection with configurable debounce; overflow-safe full-recompile fallback; both per-event and batch consumers supported.
- Negative: `Delete` handling in `ManagedFileWatcher` is intentionally dropped (by design), which means consumers must use `BatchedFileWatcher` if they need delete awareness.
- Follow-up: Consumers may implement custom debounce strategies by composing `ManagedFileWatcher` with their own scheduling logic.

## Acceptance criteria

- `ChangeSet` MUST be immutable after construction
- `BatchedFileWatcher` MUST include `DELETE` events in `deleted()`
- `BatchedFileWatcher` MUST set `fullRecompile()` to `true` on `OVERFLOW` events
- `ManagedFileWatcher` MUST ignore `DELETE` events
- Both watchers MUST use trailing-edge debounce semantics
