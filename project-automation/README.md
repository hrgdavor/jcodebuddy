# project-automation

The **dev-time orchestrator** for this repository (see
[AGENTS.md § 2](../AGENTS.md) and the root [README](../README.md)):
project-specific generators, the regeneration watcher, and the
LSP sidecar wiring. Nothing here ships with the application — a runtime
app module never depends on this one.

## The entity browser tool

[`entity-browser/`](entity-browser/README.md) is the Bun/jsx6 record browser: it reads a module's
`.jcodebuddy/index/classes.json`, renders `model.json` and a jsx6 bundle, and writes a runnable page
directory into `<link-base>/.jcodebuddy/metadata/browser/` (including the standalone `serve.js`).
Its `build.config.json` is the project-specific setup; changing the page or the resolver is done by
building here, not by registering a plugin. The output is derived and ignored, and `mvn clean` does not
touch it.

The entity-browser tool has its own Bun test suite (`cd entity-browser && bun test`); it is deliberately
outside Maven's test set, like the rest of this dev-time module's non-Java tooling.

## The regeneration watcher

[`EntityRegenerationWatcher`](src/main/java/hr/hrg/jcodebuddy/automation/entity/EntityRegenerationWatcher.java)
pairs **fs-meta's Zig daemon** event stream with **`fs-meta`'s trio/diff state** and
`EntityMetadataGenerator`, takes its flags from the same CLI surface the
generator uses, and breaks the self-write loop with a **content** check
rather than a timing flag — filesystem events arrive asynchronously, and a
flag clears too early. The daemon owns the trio at `<source>/.fs-meta`; the watcher reads it, subscribes over
the Unix socket, and a same-content save is `TOUCHED` and ignored, while a modification, addition or deletion
runs a pass. It runs at most one no-op pass per generated-file set, then goes quiet.

> **W7b (2026-11-06): the watcher is a socket client.** `FsMetaDaemonDiscovery` finds the daemon's socket from
> its host-state `roots.json`; `FsMetaDaemonWatch` uses `FsMetaClient` and handles replay, batch-gap and
> batch-too-old by one full pass then `LATEST`. With no daemon, the watcher stays a read-only trio consumer,
> `live()` is false and `attachProblem()` names the `fsmeta daemon start` / `fsmeta root add` commands.
>
> **W7c: both classes were promoted to `jcodebuddy-watch`** (`hr.hrg.jcodebuddy.watch`) once the agent became a
> second consumer — `ProjectWatcher` now discovers the daemon and subscribes with the same code, so
> `jcodebuddy-watch` starts no watcher of its own. `FileWatch` (the `WatchService` event source) had no consumer
> left and was retired with its test; `WatchBatch` is the module's public batch type and the daemon is its only
> producer.

### Running its tests

```bash
mvn -o -pl project-automation -am test \
    -Dtest=EntityRegenerationWatcherTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

**11 tests, and this command is the verification** — do not use
`mvn -o -pl project-automation -am test` without `-Dtest=`, because that
reactor run fails for a reason that has nothing to do with this module:
`jcodebuddy-meta`'s `MetadataServerTest.httpForyRoundTrip` gets an HTTP 500
from `http://localhost:18510/api/fory`. That module belongs to the
metadata/HTTP subsystem and is outside the six-module entity gate, so the
failure is pre-existing and unowned here (notes F-48). The consequence
worth knowing is that **this module's 11 tests are not part of the recorded
entity gate** — nothing in that gate would catch their regression, which is
why the command above is written down next to the module that owns them.
