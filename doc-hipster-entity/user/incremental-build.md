# Incremental generation

`hipster-entity`'s generator is a side tool: nothing in Maven runs it. That makes the edit loop the
developer's choice, and the one-shot incremental pass is now the default choice in this repository:

```sh
bun scripts/gen.js           # incremental (the first run is a full pass)
bun scripts/gen.js --full    # force the reference pass
bun scripts/gen.js --check   # verify in a temp tree; writes nothing
```

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

The one-shot build state is a disposable `fs-meta` trio under

```text
<module>/.jcodebuddy/cache/entity-build/
```

It records the module-relative paths and content hashes of every `.java` file under the source root,
**including generated files**, plus a fingerprint of what changes output without changing a source byte:
generator identity/version, JDK major, source root, metadata output, `--java-out`, package filter, every
emitter flag and the validation policy.

It is a cache, never a correctness input. Delete it at any time; the next run does a full pass and
publishes a fresh one. It is ignored by git, and the scan excludes `.jcodebuddy` so the state cannot
observe itself. The scan deliberately **does not honour `.gitignore`**: every `.java` under the source root
is part of the state.

## The fallback matrix

| Situation                                                                       | Result                                                                 |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| No content change, fingerprint matches                                          | **No-op**                                                              |
| Only the mtime changed, content identical                                       | **No-op**                                                              |
| An existing `.java` changed in place                                            | **Incremental**; only affected views emit                              |
| A file or type was added, removed or renamed                                    | **Full pass**                                                          |
| A generated artifact is missing or was hand-edited                              | **Full pass**, reported and regenerated                                |
| The generator, JDK or an output-changing flag changed                           | **Full pass**                                                          |
| State missing, corrupt, unreadable or an unknown format                         | **Full pass**                                                          |
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
- **No daemon.** The state is written by `fs-meta`'s Java writer for now; the daemon cutover is a separate
  plan (W7b).
- **Parse reuse is implemented, with counted fallbacks.** A warm pass over an unchanged tree parses nothing
  on the example; a one-file edit parses that file. The cache can still fall back to a parse when a view
  needs a fact the base layer cannot supply; the reason is printed and counted. See E10 in
  [`plans/hipster-entity-mvp.md`](../../plans/hipster-entity-mvp.md).
- **No per-file emission cache.** The unit of emission is a view, not a file; an emitted view rewrites all
  of its artifacts.

The decision behind these rules is [DEC-052](../architecture/decisions/DEC-052.md). The MVP surface and
what is still opt-in are listed in [the MVP surface](mvp-surface.md).
