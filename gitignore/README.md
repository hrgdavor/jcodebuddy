# gitignore — one matcher, one set of vectors, many ports

The `.gitignore` matcher and the directory-chain lookup that `fs-meta` used, lifted out so that **ports can share
the format, the vectors and the measurements** instead of each re-deriving them.

**These basis projects are vibe coded.** That is said plainly because it is true: the Zig implementation here was
written with an AI agent, in one long session, against a real tree (Apache Fory) whose `.gitignore` defeated three
earlier designs. Every hand-crafted improvement is welcome — a faster matcher, a clearer API, a different data
structure — and so is using this as the **basis for another port or a reimplementation**. The vectors and the
benchmark exist so that a rewrite can prove it is both faster and still correct; they are not here to protect this
implementation.

## What moves here

| from `fs-meta/src` | what it is                                                                             |
| ------------------ | -------------------------------------------------------------------------------------- |
| `gitignore.zig`    | pattern parsing, the `Rules` matcher, the segment-DP `globMatch`                       |
| `ignore.zig`       | the directory chain: which `.gitignore` files govern a directory, flattened and cached |

`fs-meta` will depend on this project rather than carry the code. The batch diff, the trio, the engine and the
watcher stay in `fs-meta`; nothing about a trio belongs here.

## Why it is its own project

Three problems are the same for every port, and each one is cheaper to solve once:

1. **Correctness.** Git's precedence is subtle — last match wins, a deeper file wins, an excluded directory is not
   entered, `**` has three forms, `!` re-includes, a trailing `/` means directories only. A port that gets one wrong
   behaves plausibly and wrongly.
2. **Performance.** The failures were not mysterious once measured, and every one of them was a shape a synthetic tree
   can reproduce:
   - a **naive recursive** matcher, exponential on `**` with a deep path: ~4,700 ms for a 3,900-entry pass;
   - a **superlinear `decide`** — 20 rules already cost 1,466 ms, because every entry scanned every rule;
   - **anchored `**` rules** left in the general-glob class, each walking ancestor prefixes: 2.97× the no-matcher
     pass;
   - the same rules **with their literal first segment pruned**: 1.6–1.8×, inside budget.
3. **Comparability.** A Java or JS port cannot know whether its 4 ms is good or 40× worse than it should be. One
   workload, one output format, one number.

## Shape

```text
gitignore/
  src/gitignore.zig        the matcher (moved from fs-meta)
  src/ignore.zig           the chain (moved from fs-meta)
  conformance/             vectors every port must pass, and the generator
  bench/                   the workloads and the runner
  doc/                     the rules as implemented, and where the test corpus comes from
```

The **vectors** are JSON: a case is `{ rules, path, isDir, kind, expect }` where `expect` is `include`, `exclude` or
`no-opinion` (the chain's three-valued answer). A port ships a runner that reads the file and reports pass/fail per
case; the Zig runner is the basis, and the file is generated *from* the Zig implementation, which is exactly why a
port failing a case is worth investigating rather than dismissing.

The **bench contract** is one CLI:

```text
<port> --rules <file.gitignore> --paths <file.txt>   → one line per path: include|exclude|no-opinion
```

so a port is measured on identical input, and the runner does not need to know the language. The workloads are
generated, never checked into a repository: a synthetic tree with the pathological shapes (a long `.gitignore` with
many `**` rules, deep Java-like paths, a dependency folder) plus the small real-world rule sets quoted inline, so
**no checkout of Apache Fory or any other project is required**.

## Plan

1. **Move the code and keep `fs-meta` green.** Copy `gitignore.zig` and `ignore.zig` here, add this project as a
   dependency of `fs-meta`'s `build.zig.zon` (path dependency, local), and switch the imports — that is the commit
   that makes `fs-meta` smaller, and it is all-or-nothing: both files move together with their tests.
2. **Conformance.** A generator that writes `conformance/gitignore-vectors.json` by running the Zig matcher over a
   curated list of cases (the rules table in `doc/rules.md` read as a test matrix), plus the three-valued cases only
   the chain can answer. A runner for Zig, then one for Java, then one for JS.
3. **Benchmarks that reproduce the failures.** Build the workloads (naive/deep/`**`-heavy/anchored) and the runner that
   reports `ns/decision` and `ms/pass` per workload. Reproduce the four numbers above with the *old* designs kept as
   branches inside the bench (`naive` and `unindexed` modes), so a newcomer can see what the fixes bought rather than
   take it on faith.
4. **The Zig CLI** behind the bench contract, and a small `bench/compare.mjs` that runs any number of ports and prints
   one table. This is the piece that lets a port "push performance for all": a faster Java matcher is visible, and the
   Zig side can adopt the idea.
5. **Documentation for ports**: a porting guide that lists the invariants (in order), the three-valued result, the
   precedence rules, and the two reserved behaviours (case sensitivity, `.git/info/exclude` not read).

## Research: where correctness cases come from

The matcher's rules are documented, but *cases* are what a port is judged on, and a hand-written dozen is not a
catalog. The plan for gathering sources is deliberately open and belongs in `doc/corpus.md`:

- **git's own tests** — `t/t0008-ignore.sh` and `t/t7063-status-untracked-cache.sh` in git's tree are the
  authoritative behaviour, written as shell with `.gitignore` fixtures; they can be read as cases, not executed.
- **`.gitignore` files in the wild, with their trees** — a rule set alone does not say whether a path is ignored, but
  a real repository plus `git check-ignore` does. Any checkout with `git` available is a generator: enumerate paths
  with `git ls-files --others --ignored`, pair them with the rules, and keep only cases where git and this matcher
  agree *or* where the disagreement is understood. This is the highest-value source and the one to automate first.
- **specification-shaped cases** — for every row of the rules table, one case that exercises it and one that
  exercises its boundary (`**/foo`, `foo/**`, `a/**/b`, `!` after a match, a dir-only rule on a file, an escaped `#`,
  a trailing space, a character class with `]` first).
- **issue trackers** — git's and other tools' ignore bugs are cases already distilled by someone else.
- **licence and provenance**: cases extracted from git's tests carry git's licence and must be attributed as such;
  generated cases from a checkout must not carry the repository's file contents, only names and rules.

The catalog is a *plan*, not a promise: the first version of this project ships the rules table's cases and the
`git check-ignore` generator stub, and a port that finds a disagreement is expected to send the case back here.

## Using it from Zig

```zig
const gitignore = @import("gitignore");
var stack = try gitignore.Stack.init(allocator, io, root, true);
const verdict = try stack.decide("src/main/java/Foo.java", false); // include | exclude | no-opinion
```

The library does no file walking of its own: it answers about a path you give it, and caches the chains it built.
That is what lets a port's own walker use it without adopting its I/O.

## Licence

Same as `fs-meta`: `LicenseRef-GPL-3.0-with-Commons-Clause`, `Copyright (c) 2026 Davor Hrg`.
