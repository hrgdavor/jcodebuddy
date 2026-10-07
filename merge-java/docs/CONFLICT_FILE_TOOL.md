# The conflict-file tool

`MergeFileTool` is the entry point for the situation the other entry points do not
cover: **one file on disk that already carries git conflict markers**. `MergeUtil`,
`MergeWorkflow` and `MergeBatch` all start from three versions of a file; a
developer (or an agent) mid-merge starts from a single `<<<<<<<`-scarred file and
a path.

## The single-file variants, and how they differ from the batch ones

The module has two families, and they are deliberately separate rather than two
spellings of one thing:

| Entry point                                 | Starts from                        | Writes                                                     | Classpath                                            |
| ------------------------------------------- | ---------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------- |
| `MergeFileTool.forFile(path)`               | one marked-up file on disk         | the file, when asked; a fixture workspace for what remains | `classpath(...)` / `--classpath`, else the JVM's own |
| `MergeFileTool.reverify(caseDir, resolver)` | a prepared fixture case            | nothing (it reports)                                       | the case's own reconstruction                        |
| `MergeUtil.create(typeContext)`             | three strings in memory            | nothing                                                    | **required**: the caller passes one                  |
| `MergeWorkflow` / `MergeBatch`              | a repository, a branch, a path set | per its options                                            | built per run from the repository root               |

The **single-file** family is the one this page is about: it exists because a merge
in progress is one file at a time, and it is kept separate from the batch family
because its inputs (marker text, one path, no repository guarantee) and its outputs
(a fixture workspace) have nothing in common with a branch-wide run.

**Every one of them resolves with a classpath, and none of them uses the degraded
no-context mode.** That mode belongs to `TypeChangeConflictResolver` for a library
caller who has no classpath to give; a tool that can be told the classpath is never
in that position, so `--classpath` is the knob rather than a fallback. See
[The classpath, and what it changes](#the-classpath-and-what-it-changes).

The tool does two things with such a file:

1. **Fixes what the existing resolvers can fix.** Every conflict block is parsed
   out, classified by `ConflictDetectionService`, resolved through the ordinary
   `MergeConflictResolver` pipeline - history replay, verification gate and all -
   and written back **only** when the answer is provably safe. Dry run by default,
   like everything else in this module.
2. **Prepares the rest as a fixture for a new resolver.** Whatever cannot be
   resolved automatically is copied - together with a whole-file reconstruction,
   the diffs, and a set of agent instructions - into a **temporary, private
   workspace** outside the repository. That workspace is the raw material for the
   loop in [ADDING_A_RESOLVER.md](../ADDING_A_RESOLVER.md): anonymize the case,
   build and test a resolver on the anonymized fixture, then re-verify the
   finished resolver against the original case.

The privacy model is not a side effect, it is the reason the workspace exists:
**proprietary user code must never leak into this module's sources, tests or
fixtures.** The original conflict stays in the temporary workspace; only the
LLM-produced *anonymized* fixture may ever enter the repository.

## Running it

```java
MergeFileTool.Result result = MergeFileTool
    .forFile(Path.of("src/main/java/com/example/demo/OrderService.java"))
    .applyFixes(true)        // default: false - report only, write nothing
    .run();

System.out.println(result.describe());   // per-block report, console-ready
result.exitCode();                       // 0 fully resolved, 1 conflicts remain
result.fixtureRunDir();                  // the prepared workspace, or null
```

The same thing from a console:

```
java -cp … com.codebuddy.merge.MergeFileTool <file> [--apply] [--apply-recorded]
     [--no-fixtures] [--fixtures <dir>] [--branch <name>]
```

| Flag                      | Meaning                                                                                   |
| ------------------------- | ----------------------------------------------------------------------------------------- |
| `--apply`                 | write the safely resolved blocks back to the file (default is a dry run)                  |
| `--apply-recorded`        | also write blocks whose answer was replayed from this branch's recorded decisions         |
| `--report <path>`         | write the report the review page renders for this file, so a reviewer sees the three sides of every block instead of a count. Render it with `merge-java/review` — no host, no server. |
| `--decisions <file.json>` | the decisions a reviewer exported from that page: they are recorded into this branch's history and then applied, because a reviewer who picked an answer wants it used. Implies `--apply-recorded`. |
| `--no-fixtures`           | do not prepare the temporary fixture workspace                                            |
| `--fixtures <dir>`        | where workspaces are created (default: `${java.io.tmpdir}/merge-java-fixtures`)           |
| `--branch <name>`         | the branch whose decision history is consulted (default: the repository's current branch) |
| `--classpath <entries>`   | the project's compile classpath, so a conflict about its own types can be **decided instead of escalated**. Entries are separated by the platform's path separator and may repeat; each must exist. They are **added to** the JVM classpath, which is what carries the platform |

## The classpath, and what it changes

The single-file path always resolves types with a classpath — `--classpath`, or the JVM's
own. It never uses `TypeChangeConflictResolver`'s degraded, no-context mode: that exists
for a library caller who has no classpath to give, and it is not a state a tool that can
be told the classpath should be in.

`--classpath` is **additive**, and that is measured rather than assumed
([`ProjectClasspathResolutionTest`](../src/test/java/com/codebuddy/merge/ProjectClasspathResolutionTest.java)):
a parser given *only* the extra entries resolves neither the project's types nor
`java.util`, because the JVM classpath is what carries the platform. A replacing flag
would make "resolve my types" mean "stop resolving everything else".

Entries may be **jars or directories of compiled classes**; both forms are pinned by that
test, so `target/classes` works and so does the jar a build produces. An entry that does
not exist is a usage error (exit 2) rather than a silently ignored one: a misspelled entry
attributions nothing, and the resulting "could not be resolved" would look like a
limitation of the tool instead of a typo.

What it changes, concretely — the same conflict, with and without:

```
[TYPE_CHANGE/AUTO] 'com.example.Widget' is a widening of 'com.example.Gadget', …
[TYPE_CHANGE/REVIEW] Declared types differ ('com.example.Gadget' vs 'com.example.Widget')
                     without a widening relationship, …
```

Off the classpath both declarations resolve to `Unknown` and the resolver says so in a
**warning** on the resolution (`UNRESOLVED_WARNING`), which is the difference between "not
a widening" and "not checkable here".

Exit status is `0` when no conflict block remains, `1` while the file still
carries conflicts, and `2` on a usage or input error - CI-shaped on purpose.

## How a block is decided

`ConflictMarkerParser` reads the file into blocks. Both marker styles are
supported: the plain two-sided block and the `diff3` block with its
`|||||||` base section. Corrupt markers (unterminated block, nested start,
missing or duplicate separator) **throw** - a misread block would become a
false clean, which is the one failure this module refuses to have.

Each block is then detected and resolved **on its own**: the sides of a block
are exactly the texts `ConflictDetectionService` expects, and blocks are
disjoint by construction, so per-block resolution composes into a per-file
result without the resolvers knowing anything about markers.

The base is never fabricated (see [WHAT_IS_BASE.md](WHAT_IS_BASE.md)):

- the **block's** base comes only from a `diff3` `|||||||` section - without it
  the block is detected against an empty base, which is still enough for the
  additive types (imports, comments, constants, overloads);
- the **whole-file** base additionally comes from the git index stage-1 entry
  while a real merge is in progress (`RepositoryProbe`, through JGit as a
  library). It is used for the fixture's `whole/base/` reconstruction and diffs,
  never invented when absent.

A block's replacement is applied only when **all** of these hold:

- exactly one resolution covers the block, and it is `AUTO` - or it is a
  recorded decision (`DEFERRED`) and the caller opted in with
  `applyRecordedDecisions(true)` - or both sides are literally identical. A
  residual `STRUCTURAL_CHANGE` that is **subsumed** by another conflict on the
  same block is not counted here: it is still emitted and still reported, it
  just stops deciding (the rule is stated under the limitations below);
- the resolution passed the `ResolutionVerifier` gate (as everywhere in this
  module);
- the replacement contains no conflict markers;
- the resolved code **covers the block**: every non-blank line of both sides
  appears in it, except lines the resolver owns (an import union may drop an
  import the other side removed) and except an explicit `PREFER_BRANCH1/2`
  decision, where dropping the other side *is* the decision. A resolution that
  answers only part of a mixed block - the imports in a block that also carries
  a method edit - is refused rather than applied, because applying it would
  silently delete the rest.

Everything else keeps its markers and becomes a fixture case:

| Outcome                     | Meaning                                                                      |
| --------------------------- | ---------------------------------------------------------------------------- |
| `APPLIED_AUTO`              | one automatic resolution covered the block                                   |
| `APPLIED_IDENTICAL_SIDES`   | both sides were the same text; collapsed to one copy                         |
| `APPLIED_RECORDED_DECISION` | a replayed sticky decision was written (opt-in)                              |
| `LEFT_REVIEW`               | viable, but a human must confirm - fixtured                                  |
| `LEFT_MANUAL`               | no safe automatic answer - fixtured                                          |
| `LEFT_DEFERRED`             | history has an answer; re-run with `applyRecordedDecisions(true)` - no fixture, the shape is already understood |
| `LEFT_MULTIPLE_AUTOMATIC`   | several automatic answers for one block cannot be composed safely - fixtured |
| `LEFT_PARTIAL_RESOLUTION`   | the automatic answer rewrites only part of the block - fixtured              |
| `LEFT_UNCLASSIFIED`         | the sides differ but detection recognised no type - fixtured                 |

## The fixture workspace

When anything is left, the tool writes one timestamped workspace under the
fixture root (default `${java.io.tmpdir}/merge-java-fixtures`):

```
merge-java-20260214-101530-9f3c21ab/
├── .gitignore                     contains `*` — deliberate, keep it
├── AGENTS.md                      the instructions every agent in here must follow
├── run.json                       what the run saw and did (counts, cases, privacy notes)
├── source/
│   ├── conflicted.java.txt        the original file, markers and all
│   ├── ours.java.txt              whole-file reconstruction, ours side
│   ├── theirs.java.txt            whole-file reconstruction, theirs side
│   ├── base.java.txt              only when a real base was known
│   └── ours.diff / theirs.diff    only when there is a base to diff against
└── cases/
    └── case-1-structural-change-3f9a1c2b8d41e6a7/
        ├── conflict.json          type, handling, outcome, signature, resolutions, next step
        ├── block/                 the raw block and its sides, as `.txt`
        │   ├── conflicted.txt
        │   ├── ours.java.txt
        │   ├── theirs.java.txt
        │   └── base.java.txt      only for a diff3 block
        └── whole/                 the three-way fixture layout of
            ├── ours/<File>.java.txt     docs/THREE_WAY_FIXTURES.md,
            ├── theirs/<File>.java.txt   reconstructed for the whole file
            └── base/<File>.java.txt     (only when a base was known)
```

Sources are `.txt`, exactly like the checked-in fixtures: a fixture workspace is
never compilable, never importable, never mistaken for module code.

**Privacy guard rails, always on:**

- the workspace lives **outside the repository** by default;
- it carries a `.gitignore` with `*`, so even a workspace created inside a
  repository cannot be committed;
- `AGENTS.md` (copied from this module's bundled
  `src/main/resources/com/codebuddy/merge/FIXTURE_AGENTS.md` by
  `FixtureAgentInstructions`) binds any LLM agent that opens the folder:
  treat everything under `source/` and `cases/` as **proprietary user code**,
  never copy it into a repository, a commit, or an external service.

## The loop the workspace drives

`AGENTS.md` prescribes four steps, and the tool provides the machinery for the
last one:

1. **Anonymize** each case into `anonymized/<shape-name>/` inside the
   workspace: invent a neutral domain, replace every identifier, literal and
   comment, keep the structural shape identical, and self-check that no
   original identifier survives and that detection classifies the anonymized
   three-way exactly like the original.
2. **Build the resolver** from the anonymized fixture *only*, following
   [ADDING_A_RESOLVER.md](../ADDING_A_RESOLVER.md). A shape that is inherently
   human gets a `VERDICT.md` instead of a forced resolver.
3. **Re-verify on the ORIGINAL case**, programmatically:

   ```java
   MergeFileTool.Reverification check = MergeFileTool.reverify(
       caseDir,                      // cases/<case> of the original workspace
       new MyNewResolver());         // the resolver built on the anonymized fixture

   check.viable();       // true when it answers AUTO or REVIEW with real code
   check.kind();         // what the verification gate concluded
   check.explanation();  // why
   ```

   `reverify` rebuilds the `Conflict` from the case's whole-file sides, runs the
   candidate through the same `ResolutionVerifier.structural()` gate every other
   resolution passes, and reports the verdict. The original text goes into the
   verdict's provenance, never into the repository - and the gate is never
   weakened to make a candidate pass.
4. **Report and clean up**: the workspace's report table records which case led
   to which resolver and what re-verification concluded; afterwards the
   workspace is deleted, because the repository keeps the anonymized fixture and
   the resolver, and nothing else needs archiving.

## Limitations, stated rather than discovered

- **Blocks are resolved independently.** A file whose blocks interact - an
  import in block 1 that block 3's code needs - is fixed per block; the tool
  does not compile the result. The verification gate is structural
  (markers, balance, non-emptiness), exactly as everywhere else in this module.
- **Fragment detection is text-shaped.** A block's sides are fragments, not
  whole files; detection works on them the same way it works on any text, which
  is conservative: when in doubt a block stays marked and becomes a fixture.
  An `unclassified` outcome is an invitation to teach the detectors a new
  shape, not a bug report.
- **The tool never stages or commits.** It writes the file when asked and
  nothing else; git operations remain the caller's (or the human's) decision.
- **A residual with nothing of its own to say no longer vetoes a decided block.**
  Detection emits the residual `STRUCTURAL_CHANGE` **alongside** the recognised conflict —
  by design, because a residual that replaced the recognised conflicts once lost a
  mechanical import addition — and a block is applied only when one resolution claims it.
  That combination used to leave a block whose only real change was a decided widening at
  `LEFT_MANUAL`, with the report carrying `[TYPE_CHANGE/AUTO] 'Widget' is a widening of
  'Gadget'` and a human still asked for a decision that already existed. A subsumed
  residual is now dropped from the block's **decision**, while staying in the report, so
  the resolutions that remain decide it. Which residual counts as subsumed is decided by
  the strongest evidence the run has:

  - **with a base side** (`diff3`/`zdiff3` — git writes one only when asked), the
    residual's region is the span of base lines that neither branch kept, and it is
    subsumed exactly when another conflict's region **covers** that span. A residual that
    reaches a line no other conflict places keeps its veto, which is the case the
    alongside-emission exists for;
  - **without one** (git's default `merge` style writes no base), the residual can place no
    line at all, because every claim it makes is relative to a base this run does not have.
    It is subsumed only by one **complete automatic** answer: a single other conflict that
    is `AUTO` and whose code accounts for the whole block. A review, a partial answer, or
    several automatic answers that cannot compose each leave the veto in place.

  Both halves are asserted in
  [`MergeFileToolTest`](../src/test/java/com/codebuddy/merge/MergeFileToolTest.java) — a
  subsumed residual lets the block apply and the applied code compiles, and a residual
  covering a line no recognised conflict explains still leaves the block marked.
- **History replay needs a branch.** Outside a repository and without
  `--branch`, the history is per-run and in-memory - replay then has nothing to
  replay, by design rather than by guesswork.
