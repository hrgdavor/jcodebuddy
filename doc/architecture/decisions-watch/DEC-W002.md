# DEC-W002: Hot-swap daemon architecture for java-watch-run

- Status: Accepted
- Date: 2026-07-24
- Owners: project
- Related docs: [Module map](../module-map.md)
- Supersedes: -
- Superseded by: -

## Context

The `java-watch-run` module provides a long-running daemon that compiles Java source and reloads changed classes at runtime. The architecture must handle incremental recompilation, class loading, and graceful recovery from compilation errors without restarting the process.

## Decision

`java-watch-run` uses a single-threaded reload executor that serializes all reload operations:

1. **Full compile on startup**: Performs a complete ECJ compilation of the source tree.
2. **Incremental recompile on change**: When `BatchedFileWatcher` delivers a `ChangeSet`, the daemon passes it to the `Reloader`, which attempts path-sensitive incremental compilation using ECJ.
3. **Fallback on overflow**: If `ChangeSet.fullRecompile()` is `true` (OS event queue overflow) or incremental compilation fails, the daemon performs a full clean recompile.
4. **Dynamic class reload**: After successful compilation, a new `URLClassLoader` is created and the main class is re-instantiated.
5. **GraalVM native-image support**: A `native` Maven profile configures `--no-fallback` for native binary builds.

## Alternatives considered

- **Multi-threaded concurrent reloads** — rejected because overlapping reloads with a shared `URLClassLoader` cause classloader leaks and undefined behavior.
- **OSGi-based reloading** — rejected as over-engineering for a dev-time tool; the single-classloader swap approach is simpler and sufficient.
- **Compile-only (no hot-reload)** — rejected because the primary value of a watch daemon is continuous feedback; compile-only would not close the development loop.

## Consequences

- Positive: Fast feedback loop with incremental recompilation; full recompile fallback is safe; GraalVM native profile available.
- Negative: Single-threaded executor means rapid saves are serialized; if a reload hangs, subsequent reloads are blocked until it completes.
- Follow-up: Consider a reload timeout mechanism to detect and recover from hangs in future work.

## Acceptance criteria

- Daemon MUST start with a full compile before entering watch mode
- Incremental recompile MUST fire when a `ChangeSet` contains only `changed()` files and no overflow
- Full recompile MUST fire when `ChangeSet.fullRecompile()` is `true`
- Daemon MUST NOT crash on compilation errors; it MUST log errors and continue watching
- Native profile MUST build a self-contained binary with `--no-fallback`
