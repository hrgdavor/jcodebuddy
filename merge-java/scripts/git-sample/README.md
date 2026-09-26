# Turning a fixture into a real git repository

`sample-repo.js` reads one fixture from
[`src/test/resources/fixtures/`](../../src/test/resources/fixtures) and rebuilds
the merge it describes as an actual git repository that can be opened in a git
GUI, driven from the git CLI, or handed to any tool that resolves conflicts.

A JUnit fixture is strings in a process. This is a repository on disk.

```sh
bun run scripts/git-sample/sample-repo.js --list
bun run scripts/git-sample/sample-repo.js import-add-both
```

```
fixture import-add-both
  repository    C:\Users\you\AppData\Local\Temp\merge-java-import-add-both
  branches      feature (ours) <- base -> upstream (theirs)
  paths         PaymentProcessor.java
  git conflicts PaymentProcessor.java
```

## What it builds

```
upstream  o   theirs   what the upstream did since we last synced
           \
base        o         the last-synced upstream state - the merge base
             \
feature       o       what our branch did since we last synced
```

- **`base`** holds the fixture's `base/` — the upstream *as this branch last saw
  it*, which is not the same thing as Git's merge base. See
  [`../docs/WHAT_IS_BASE.md`](../../docs/WHAT_IS_BASE.md).
- **`upstream`** holds `theirs/`, **`feature`** holds `ours/`, and both are one
  commit above `base`, so the base commit really is their common ancestor.
- **`feature` is checked out** and tracks `origin/upstream`, so a plain
  `git merge` conflicts where the fixture says it does.
- A **last-sync marker** is written at
  `.jcodebuddy/merge-history/feature/last-sync`, so `MergeWorkflow` resolves
  against the same base without being told anything:

  ```java
  MergeWorkflow.Result result = MergeWorkflow.open(Path.of(repo))
      .path("PaymentProcessor.java")
      .dryRun(false)
      .run();
  ```

  The marker holds the base commit's **id**, not the name of a branch. That
  distinction is the whole point of the file: `upstream` moves the moment anything
  is pushed to it, so a marker naming the branch would resolve to the upstream tip
  and the workflow would conclude that neither side had changed anything. The
  branch name travels beside it, in `upstreamRef`, for a human to read.

  The marker is working-tree state, deliberately left uncommitted: it is
  regeneration state, like a cache, and committing it would put `.jcodebuddy/`
  into the diffs you compare the fixture against.

## Options

| Option | Meaning |
|---|---|
| `--list` | list every fixture that can be simulated |
| `--out <dir>` | where to build (default: `<tmp>/merge-java-<fixture>`) |
| `--base-branch <name>` | branch holding the base commit (default `base`) |
| `--feature-branch <name>` | our side (default `feature`) |
| `--upstream-branch <name>` | their side (default `upstream`) |
| `--merge` | leave the repository mid-merge, with conflict markers in the tree |
| `--force` | replace an existing `--out` directory |
| `--json` | print the manifest as JSON instead of a summary |
| `--capture <file>` | write this run's output there instead of the console |

Exit codes: `0` built, `1` the fixture or repository is unusable, `2` bad usage.
An existing `--out` directory is refused unless `--force` is given.

`--capture` exists for callers that need the output *and* the exit status
together. A test that pipes the process's output through a shell can lose the
exit status on the way — PowerShell does, for a native command whose error stream
joins its pipeline — so a usage error and a crash become indistinguishable.

## Opening a fixture somewhere else

```sh
# A named fixture
bun run scripts/git-sample/sample-repo.js import-add-remove-same

# Any directory laid out like a fixture
bun run scripts/git-sample/sample-repo.js ../my-cases/constant-same-name-different-value

# Mid-merge, to work on the conflict by hand or with another tool
bun run scripts/git-sample/sample-repo.js import-add-both --out /tmp/case --merge
```

## What is in the generated repository

| Path | What it is |
|---|---|
| `README.md` | the case, its commits, the commands to try, whether git conflicts here, and which side actually changed the file |
| `expected/ours.diff`, `expected/theirs.diff` | the fixture's own diffs, verbatim — the documented intent a resolution should be compared against |
| `.jcodebuddy/merge-history/<branch>/last-sync` | the marker `MergeWorkflow` reads, uncommitted |

The manifest (`--json`) carries the same facts as data: `changes` says, per path,
which sides differ from the base; `conflictsUnderGit` says which paths git conflicts
on; `mergeStaged` says whether a merge was left in progress.

## Two things to know

**Git conflicts on regions; merge-java classifies conflict *types*.** A fixture's
sides often differ in more than the change the fixture is named for — in
`import-add-both`, both sides also rewrote the class comment, which the fixture's
`.diff` files do not mention. The markers a user sees therefore cover more than
the named change, and merge-java classifies the extra divergence on its own terms
(`COMMENT_ADD`, `STRUCTURAL_CHANGE`) rather than as `IMPORT_ADD`. The generated
`README.md` states which paths git conflicts on, so the shape is never a surprise.

**A fixture git merges cleanly is still worth generating.** `import-add-remove-same`
is one: git reports no conflict at all. It is worth generating precisely because the
reason is easy to get wrong — ours is **identical to the base**, so there is nothing
to reconcile and the clean merge proves nothing about any tool. Generate it to
isolate the one side's change, and note that the fixture's *name* (which reads as
though both sides touch the import block) is not evidence of its shape. The generated
`README.md` states which side changed the file, measured rather than assumed.

## No shell, no pipes

The generator runs `git` **directly** — no `sh`, no PowerShell — and gives each
standard stream either a real file it opened itself or nothing at all:

- **No shell**, because a shell adds quoting rules, a `PATHEXT` variable that must
  be declared before a bare `git` resolves to `git.exe`, the absence of a `<`
  operator on Windows PowerShell, and one more program that may not be installed.
  This tool only ever runs one executable, so none of that buys anything.
- **No pipes**, because a stream that is neither a file nor a terminal has to be
  created by somebody, and a locked-down host is entitled to refuse. Output is
  written to a temporary file descriptor and read back from the file, which asks
  for nothing more than a temporary file.

The practical result is that the tool behaves identically on Windows, macOS and
Linux, and a sandbox that permits only file writes can still run it.

## Reproducibility

Every object is written with a fixed author, a fixed clock and no user git
configuration, so regenerating a fixture produces the same commit ids. The three
trees are written with plumbing (`hash-object`, `read-tree`, `update-index`,
`write-tree`, `commit-tree`) rather than by checking a branch out and committing
it, so a deletion, an addition and a modification are all expressed the same way
and no working-tree state can leak between the sides.

Blobs hold the fixture file's **bytes verbatim** (`hash-object --no-filters`), and
`checkout` applies the host's `core.autocrlf` as usual. So commit ids are
reproducible on a machine, and identical across machines whose checkouts agree;
two machines that disagree about line endings will produce different-but-equivalent
objects. The content is the fixture's either way, which is what
[`SampleRepoTest`](../../src/test/java/com/codebuddy/merge/SampleRepoTest.java)
pins.

## Tests

Two layers, and they deliberately do not overlap:

- [`sample-repo.test.js`](sample-repo.test.js) drives the generator **in its own
  process** — it is plain JavaScript over the file system and git, so a second
  process would add a moving part and no coverage — then asks git what actually
  appeared: the three branches, the remote-tracking ref, the marker, the blobs,
  a real conflicting merge and its abort, the clean fixture, and every refusal.
  Run it with `bun run scripts/git-sample/sample-repo.test.js`. Its scratch lives
  in `.sample-repos/.test-scratch/`, which is ignored by git.
- [`SampleRepoTest`](../../src/test/java/com/codebuddy/merge/SampleRepoTest.java)
  runs the **command line** (`bun run … <fixture> --out …`) for every fixture, so
  the argument surface and exit codes are covered where a process boundary is
  genuinely needed, and asserts that each commit holds the fixture file byte for
  byte and that `MergeWorkflow` resolves the result unchanged. Skipped when Bun is
  not installed, the same way the renderer test is.