# Incremental generation

`hipster-entity`'s generator is a side tool: nothing in Maven runs it. That makes the edit loop the
developer's choice, and the one-shot incremental pass is now the default choice in this repository:

```sh
bun scripts/gen.js           # incremental (the first run is a full pass)
bun scripts/gen.js --full    # force the reference pass
bun scripts/gen.js --check   # verify in a temp tree; writes nothing
```

## Daemon setup

The state is a **daemon-generation marker**, so incremental/no-op results need an `fsmeta` daemon serving the
**project root**:

```sh
fsmeta daemon start
fsmeta root add /path/to/project
```

Each module then filters that one daemon to its own `sourceRoot/**`; a project needs one root, not one per
module. The server-side subfolder filter requires the daemon built from `fs-meta` commit **`2da83ae`**
(which includes the filter from `f4be95d`; the local checkout's rebuilt binary is
`D:\wrk\core\fs-meta\zig-out\bin\fsmeta.exe`). A root's scheduler tick is configurable with
`--tick` / `FSMETA_TICK_MS` (default 50 ms, range 5–1000; the 25 ms quiet floor still applies). **The tick should be
chosen per editor/SSD rather than globally.** 50 ms is the new default; 10–25 ms is reasonable for a fast local
editor, while 50–100 ms is safer for many roots or slower storage. An older daemon still works
through the Java client's compatibility fallback, but it filters client-side rather than in the daemon.

Without a daemon the generator still runs a full pass; it cannot claim a no-op, and it records no baseline.

## Watch mode

`--watch` uses the same daemon and the same incremental pass; it never starts a Java `WatchService`:

```sh
bun scripts/gen.js --watch
```

It runs one initial pass (honouring `--full`/`--incremental`), subscribes at the daemon's latest generation with
a filter limited to the module's `sourceRoot/**/*.java`, coalesces saves over a 300 ms quiet window, stops the
subscription before each pass (so generated output cannot trigger another pass), runs the pass, waits for the
daemon head to settle, and resubscribes at the latest generation. An overflow/`batch-too-old`/gap is a full
pass. No daemon is exit 3 with the `fsmeta daemon start` / `fsmeta root add` command; `--watch --check` is exit
2. Ctrl+C stops the subscription and joins the loop.

The same three modes exist on the generator itself, so a consumer does not need this repository's
wrapper:

```text
EntityMetadataGenerator <source-root> <output-dir> --java-out <source-root> \
  [--incremental | --full | --check] [...]
```

## What "incremental" means

There are only two results, and both are safe:

- **No-op.** The tree's content hashes and the output-changing flags match what the last successful pass
  recorded. The pass prints one line and writes nothing — no parse, no validation, no metadata, no HTML
  re-render.
- **A pass with selective emission.** If an existing file changed, the whole source set is parsed (the MVP
  does not reuse parsed units yet), the changed files are mapped to type FQNs through the module's class
  index, and only the views whose recorded `views[].inputs` intersect those FQNs run their emitters. Every
  other view keeps its committed generated files and metadata.

The **full pass is the reference**. An incremental result must be byte-identical to a full pass over the
same tree, generator and flags; a difference is a bug. `IncrementalEmissionTest` asserts that equality,
including for a mapper endpoint.

## The state

The one-shot build state is a **daemon-generation marker**, not a local `fs-meta` trio:

```text
<module>/.jcodebuddy/cache/entity-build/run.json
```

The daemon serves the **project root**. The module's marker records the last successful daemon generation
and a fingerprint of what changes output without changing a source byte: generator identity/version, JDK
major, source root, project root, metadata output, `--java-out`, package filter, every emitter flag and the
validation policy. It also records the daemon root/id and the pass's selection/validation outcome.

A module is a **subfolder subscription**, not a second daemon root: `FsMetaClient` sends the protocol's
include/exclude patterns, with include `sourceRoot/**` relative to the daemon root and exclude
`**/.jcodebuddy/**`, so one project-root daemon serves several module caches independently. The daemon
applies the filter to both replay and live batches.

The daemon is the only tracker and writer. There is no `files.meta`/`files.txt`/`files.json` under
`entity-build`, no Java `FsMetaWriter` call, and no separate change tracker. The marker is a cache, never a
correctness input: delete it at any time; the next run does a full pass and records a fresh generation.

**Daemon scanning honours `.gitignore`.** The daemon does not publish a gitignored `.java` file, so an edit
to one cannot be observed as an incremental change. Reproduced on 2026-11-07 against the rebuilt daemon:
`fsmeta scan` listed 2 names while `fsmeta scan --no-gitignore` listed 3; after editing the ignored
`.java`, the root's `head_batch` stayed at 1 (pass ran, batch bytes 0), while editing the tracked `.java`
advanced it to 2. Keep model sources tracked, or run a full pass after editing an ignored source. This is
the one semantic difference from the retired Java state, which deliberately scanned every `.java`.

## The fallback matrix

| Situation                                                                       | Result                                                                 |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| No daemon serving the project root                                              | **Full pass**, with an actionable reason; nothing is marked as a no-op |
| No marker, unreadable/foreign marker, fingerprint mismatch                      | **Full pass**                                                          |
| Daemon generation equals the recorded generation                                | **No-op**                                                              |
| Daemon is later; changed `.java` under the source root                          | **Incremental**; only affected views emit                              |
| Daemon is later; only non-`.java`/outside-source-root changes                   | **No-op**                                                              |
| Added/deleted `.java` under the source root                                     | **Full pass**                                                          |
| Batch too old / batch gap / generation moved backwards                          | **Full pass**                                                          |
| A generated artifact is missing or was hand-edited                              | **Full pass**, reported and regenerated                                |
| The generator, JDK or an output-changing flag changed                           | **Full pass**                                                          |
| An unsupported emitter is enabled (`--adapters`, `--patch-appliers`, `--merge`) | **Full pass** in the MVP                                               |
| A changed file has no type FQN, or the source cannot be parsed                  | **Full pass**                                                          |
| `--check`                                                                       | **Full pass into a temporary copy**, compared with the committed files |

A view with an unresolved input is **always affected**: the pass does not guess that skipping it is safe.
DTO projections and mappers are view-scoped and stay incremental; SQL adapters, patch appliers and merges
are full-pass in the MVP.

## `--check`

`--check` is the CI/verification form. It copies the `.java` tree into a temporary module, runs a full
reference pass there, and compares the generated `.java` files (identified by their DEC-021 header) and the
tracked `.jcodebuddy/conf/converters.json` with the committed tree. It writes nothing in the real module.

- exit `0`: generated source and manifest are current;
- exit `1`: a file differs or is missing; every difference is named;
- exit `2`: usage error.

## What remains out of scope

- **No watcher.** Watch mode is deliberately postponed. When it lands it will call the same pass and reuse
  the same state; it will not add a second dependency graph or a second state directory.
- **No daemon means no no-op, but never a wrong answer.** A one-shot command without a daemon still runs the
  full pass; it cannot record or compare a baseline. Start `fsmeta daemon` and add the project root to get
  incremental/no-op results. The daemon root is the project root; each module filters to its own source-root
  subfolder.
- **Parse reuse is implemented, with counted fallbacks.** A warm pass over an unchanged tree parses nothing
  on the example; a one-file edit parses that file. The cache can still fall back to a parse when a view
  needs a fact the base layer cannot supply; the reason is printed and counted. See E10 in
  [`plans/hipster-entity-mvp.md`](../../plans/hipster-entity-mvp.md).
- **No per-file emission cache.** The unit of emission is a view, not a file; an emitted view rewrites all
  of its artifacts.

The decision behind these rules is [DEC-052](../architecture/decisions/DEC-052.md). The MVP surface and
what is still opt-in are listed in [the MVP surface](mvp-surface.md).
