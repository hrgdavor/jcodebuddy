# project-automation

The **dev-time orchestrator** for this repository (see
[AGENTS.md § 2](../AGENTS.md) and the root [README](../README.md)):
project-specific generators, the regeneration watcher, and the
LSP sidecar wiring. Nothing here ships with the application — a runtime
app module never depends on this one.

## The regeneration watcher

[`EntityRegenerationWatcher`](src/main/java/hr/hrg/jcodebuddy/automation/entity/EntityRegenerationWatcher.java)
pairs `java-watch-core`'s batched filesystem watcher with
`EntityMetadataGenerator`, takes its flags from the same CLI surface the
generator uses, and breaks the self-write loop with a **content** check
rather than a timing flag — filesystem events arrive asynchronously, and a
flag clears too early. It runs at most one no-op pass per generated-file
set, then goes quiet.

### Running its tests

```bash
mvn -o -pl project-automation -am test \
    -Dtest=EntityRegenerationWatcherTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

**11 tests, and this command is the verification** — do not use
`mvn -o -pl project-automation -am test` without `-Dtest=`, because that
reactor run fails for a reason that has nothing to do with this module:
`metadata-server`'s `MetadataServerTest.httpForyRoundTrip` gets an HTTP 500
from `http://localhost:18510/api/fory`. That module belongs to the
metadata/HTTP subsystem and is outside the six-module entity gate, so the
failure is pre-existing and unowned here (notes F-48). The consequence
worth knowing is that **this module's 11 tests are not part of the recorded
entity gate** — nothing in that gate would catch their regression, which is
why the command above is written down next to the module that owns them.
