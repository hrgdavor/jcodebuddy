# merge-java

Reliable resolution of common merge conflicts, with a memory of how this branch
resolved them last time.

## The problem

Long-lived branches that regularly merge from their base branch keep hitting the
same conflicts. Standard merge tools are poor at the easy ones:

```java
// base
import java.util.List;

// our branch                    // their branch
import java.util.List;           import java.util.List;
import java.math.BigDecimal;     import java.time.Instant;
```

A human reads this and resolves it in two seconds by keeping both imports. A
merge tool reports a conflict and stops, every single time, on every update.

`merge-java` classifies each conflict, resolves the ones that are genuinely
mechanical, and for everything else hands the reviewer a **fix path**: the
options, what each one costs, and which is recommended. It also records the
branch's decisions so the same conflict is not re-litigated on the next update.

## What it does per conflict type

| Conflict | Decision | Behaviour |
|---|---|---|
| `IMPORT_ADD` | Both branches add imports | **Automatic** - keeps the union, de-duplicated |
| `COMMENT_ADD` | Both branches add documentation | **Automatic** - keeps the union |
| `CONSTANT_ADD` | Both branches add constants | **Automatic**, or review if the same name has different values |
| `OVERLOAD_ADD` | Both branches add a method of the same name | **Automatic** if the parameter lists differ, review if identical |
| `TYPE_CHANGE` | Declared type differs | **Automatic** when one side widens the type, review otherwise |
| `METHOD_BODY_CHANGE` | Both edited the same body | **Review** - disjoint edits are combined, overlapping ones are reported |
| `VARIABLE_RENAME` | Renamed differently on each side | **Review + remembered** - the chosen name is replayed later |
| `PACKAGE_CHANGE` | Class moved on both sides | **Automatic** if one side moved it, **review + remembered** if both did |
| `STRUCTURAL_CHANGE` | Members added/removed incompatibly | **Manual**, with a description of what each side does |
| `API_INCOMPATIBILITY` | Public contract changed | **Manual**, naming exactly which part moved |

Three kinds of outcome fall out of this:

- **AUTO** - applied without asking, and only after passing the verification gate.
- **REVIEW** - correct as far as the tool can tell, but a human confirms.
- **MANUAL** - no safe automatic answer; the fix paths describe the choices.

### Composing rather than replacing

A file can be mostly mechanical and still contain one thing no resolver understands,
and both parts must survive that:

```
Payment.java: 2 conflict(s) - 1 auto (1 applicable), 0 for review, 1 manual.
```

The automatic part is reported as **independently applicable** only when its region
is known and does not overlap the manual conflict, so a caller can apply the safe
subset and leave the rest. Never the other way round: an unknown region blocks
application rather than being assumed safe.

### Keeping a branch up to date

```java
MergeWorkflow.Result result = MergeWorkflow.open(Path.of("."))
    .upstream("origin/main")
    .path("src/main/java/com/example/Payment.java")
    .dryRun(false)
    .reportTo(Path.of(".jcodebuddy/metadata/merge-report.json"))
    .run();

System.out.println(result.describe());
System.exit(result.exitCode());   // non-zero while a human is still needed
```

The workflow discovers the branch and merge base itself, reads all three versions
from the Git object database (JGit as a library, never a subprocess), applies the
independently applicable resolutions, and reports the rest. It is dry by default and
never stages or commits.

The base it resolves against is the **last-synced upstream state**, recorded in
`.jcodebuddy/merge-history/<branch>/last-sync` after each run — not Git's merge base
([why](docs/WHAT_IS_BASE.md)). That file's `upstreamCommit` must be a full commit id:
a branch name, a tag or a revision expression such as `HEAD~3` is refused with a
`SyncMarkerException`, because such a name means whatever it points at when it is
read. A base that had moved up to the upstream would make a conflicting merge look
clean, and a false clean is the one answer this module must never give. A missing
marker is not an error — it means the branch has never synced, and the merge base is
used instead. Delete the file to return to that state.

## Quick start

```java
MergeUtil util = MergeUtil.create();

MergeConflictResolver.MergeReport report = util.resolve(
    "src/main/java/com/example/Payment.java",   // path recorded in history
    "Base.java", "Ours.java", "Theirs.java",    // the three versions on disk
    ".jcodebuddy/merge-history/feature-payments/");

System.out.println(report.summarize());
// src/main/java/com/example/Payment.java: 3 conflict(s) - 2 auto, 1 for review, 0 manual.

if (report.hasUnresolvedConflicts()) {
    report.getManualResolutions().forEach(r ->
        System.out.println(r.getExplanation()));
}

report.getAutoResolutions().forEach(r -> System.out.println(r.getResolvedCode()));
```

`MergeUtil` is a convenience facade over `MergeConflictResolver` — an ordinary
library class, **not** a Maven plugin, build extension or CLI.

For control over resolvers, history location and branch name, build the
resolver directly:

```java
MergeConflictResolver resolver = new MergeConflictResolver.Builder()
    .setBranchName("feature-payments")
    .setHistoryPath(Path.of(".jcodebuddy/merge-history/feature-payments"))
    .build();

ConflictResolution resolution = resolver.resolve(new Conflict(
    ConflictType.IMPORT_ADD,
    "src/main/java/com/example/Payment.java",
    "both branches added imports",
    "import java.util.List;",
    "import java.util.List;\nimport java.math.BigDecimal;",
    "import java.util.List;\nimport java.time.Instant;"));

System.out.println(resolution.getKind());          // AUTO
System.out.println(resolution.getResolvedCode());  // all three imports, once each
System.out.println(resolution.getExplanation());   // why
```

## The conflict-file tool

The entry points above all start from three versions of a file. `MergeFileTool`
starts from **one file that already carries conflict markers** - the situation a
developer or an agent is actually in mid-merge:

```
java -cp … com.codebuddy.merge.MergeFileTool src/main/java/com/example/demo/OrderService.java --apply
```

Every `<<<<<<< … >>>>>>>` block is parsed, classified and resolved through the
ordinary pipeline (history replay and verification gate included), and written
back only when the answer provably covers the whole block. Dry run by default;
exit status `0/1/2` for CI. Whatever cannot be resolved automatically is copied
into a **temporary private workspace** - `.gitignore *`, the original never
committable - together with an `AGENTS.md` that binds any LLM agent opening it:
anonymize the case first, build and test the new resolver on the *anonymized*
fixture per [ADDING_A_RESOLVER.md](ADDING_A_RESOLVER.md), then re-verify it
against the original case with `MergeFileTool.reverify(caseDir, resolver)`.
Proprietary user code never enters this repository; only the anonymized fixture
may. Full flow, layout and privacy model:
[docs/CONFLICT_FILE_TOOL.md](docs/CONFLICT_FILE_TOOL.md).

## How the memory works

Recorded decisions live beside the module, one directory per branch, and are
meant to be **checked in**:

```
.jcodebuddy/merge-history/
└── feature-payments/
    └── decisions/
        ├── variable_rename-3f9a1c...json
        └── package_change-77b0e2...json
```

A decision is replayed only when the incoming conflict has the same
**signature** — same type, same file, and the same shape of disagreement. The
signature ignores formatting churn (indentation, spacing, blank lines), so
reformatting does not invalidate it, but a genuinely different disagreement
never matches. A decision can therefore never be applied to code it was not made
for.

Replay is asymmetric on purpose:

- **Additive conflicts** (imports, comments, constants, overloads) are
  deterministic, so their resolutions are remembered.
- **Preference conflicts** (renames, package moves) are remembered because the
  answer is a choice that must stay consistent across updates.
- **Review and manual conflicts** are **not** remembered. A judgement call made
  once should not silently become policy.

When a decision is replayed the resolution is marked
`ResolutionKind.DEFERRED` with strategy `STICKY_REPLAY`, so a caller can always
tell "decided just now" from "reused from history".

```java
// A human picks branch 1's name, and the choice is remembered.
ConflictResolution decision = ConflictResolution.builder()
    .filePath(conflict.getFilePath())
    .type(conflict.getType())
    .resolvedCode(conflict.getBranch1Code())
    .resolutionStrategy(ConflictResolution.ResolutionStrategy.KEEP_BOTH)
    .kind(ConflictResolution.ResolutionKind.AUTO)
    .sticky(true)
    .explanation("Reviewer chose 'purchase' consistently")
    .build();

resolver.recordDecision(conflict, decision);

// Next base-branch update: replayed, not re-asked.
ConflictResolution replayed = resolver.resolve(conflict);
replayed.getKind();               // DEFERRED
replayed.getResolutionStrategy(); // STICKY_REPLAY
```

## Fix paths

When a resolver cannot decide, it offers options rather than a bare conflict
marker:

```java
List<FixPath> options = resolver.fixPathsFor(ConflictType.VARIABLE_RENAME);

for (FixPath option : options) {
    System.out.println(option.getDescription());
    System.out.println("  options:       " + option.getOptions());
    System.out.println("  recommended:   " + option.getRecommended());
    System.out.println("  why:           " + option.getJustification());
    System.out.println("  cost:          " + option.getImpact());
}
```

Every resolver guarantees at least one fix path — the manual escape hatch — so a
reviewer is never left without a next step. `FixPath.getRecommended()` is always
one of `getOptions()`.

## Layout

```
merge-java/
├── pom.xml
├── README.md
├── ADDING_A_RESOLVER.md          <- extension guide
├── IMPLEMENTATION_PLAN.md
├── CHANGELOG.md
├── QUICKSTART.md
├── .jcodebuddy/
│   ├── README.md
│   ├── metadata/schema.json
│   └── branches/.gitkeep
└── src/
    ├── main/java/com/codebuddy/merge/
    │   ├── MergeUtil.java                     convenience facade
    │   ├── MergeConflictResolver.java         orchestration + MergeReport
    │   ├── ConflictResolvers.java             the registry
    │   ├── ConflictDetectionService.java      finds conflicts in a file
    │   ├── AbstractConflictResolver.java      extension point
    │   ├── ConflictResolver.java              contract
    │   ├── ConflictType.java                  types + Handling policy
    │   ├── Conflict.java / ConflictResolution.java / FixPath.java
    │   ├── ConflictSignature.java             conflict identity
    │   ├── BranchConflictStore.java           per-branch decision history
    │   ├── MergeFileTool.java                 marker-file entry point + CLI + fixture loop
    │   ├── ConflictMarkerParser.java          git conflict markers -> blocks + whole versions
    │   ├── ConflictFixtureWriter.java         the private fixture workspace
    │   ├── RepositoryProbe.java               branch + staged base through JGit
    │   ├── FixtureAgentInstructions.java      copies the workspace AGENTS.md
    │   └── *ConflictResolver.java             ten resolvers
    ├── main/resources/com/codebuddy/merge/
    │   └── FIXTURE_AGENTS.md                  instructions bundled into every workspace
    └── test/java/com/codebuddy/merge/
        ├── AbstractResolverTest.java          reusable resolver contract tests
        ├── ConflictFixtures.java              one sample conflict per type
        └── *Test.java                         431 tests
```

## Adding a resolver

See [ADDING_A_RESOLVER.md](ADDING_A_RESOLVER.md). In short: add a
`ConflictType` with its `Handling`, extend `AbstractConflictResolver`
implementing three methods, and add one line to
`ConflictResolvers.defaultResolvers()`. A registry test fails and names the type
if you forget the last step.

## Tests

```
mvn -f merge-java/pom.xml test
```

**Requires JDK 25 on `JAVA_HOME`**, the same level as the parent POM.

`OverloadAddConflictResolver` compares resolved parameter types through
OpenRewrite's Java parser, which drives the JDK's own `javac` through internal,
version-specific APIs. A parser implementation must therefore run on the JDK it was
built for: `rewrite-java-25` needs a JDK 25 runtime, exactly as `rewrite-java-21`
needed 21 and failed outright on 25. OpenRewrite 8.90.4 ships implementations for
Java 8, 11, 17, 21 and 25, and this module uses the 25 one, so it builds and runs at
the parent's level with no override.

> **This creates a standing maintenance obligation.** A new JDK ships roughly every
> six months, and OpenRewrite publishes a matching parser module shortly after — so
> the JDK level, the parser module and the OpenRewrite version must move together.
> See [`VERSION_MAINTENANCE.md`](VERSION_MAINTENANCE.md) for what to bump, how to
> verify it, and the traps. Leaving it to drift is what produced the Java 21
> override this module briefly needed.

Roughly seven hundred tests covering resolver behaviour, detection, region attribution,
conflict composition, the verification gate, history persistence and replay, the
registry contract, the extension pattern, type-aware parameter comparison, the batch
facade, the JGit workflow against a real repository, a fixture rebuilt as a real git
repository, the conflict-file tool (marker parsing, application rule, private fixture
workspaces, repository probing), and the JSON-to-HTML report (the renderer test skips
itself when Bun is not installed). Run `mvn test` for the number rather than trusting
one written here.

The fixture generator has its own harness, which runs without Maven:

```
bun run scripts/git-sample/sample-repo.test.js
```

## Dependencies

| Dependency | Why |
|---|---|
| `org.openrewrite:rewrite-core`, `rewrite-java`, `rewrite-maven` | Java parsing and source-level recipes |
| `org.eclipse.jgit:org.eclipse.jgit` | Branch and history access from the library, never shelling out to `git` |
| JUnit 5, Mockito | Tests |

OpenRewrite bundles its own Java parser, so no separate `JavaParser` dependency
is needed. Versions of OpenRewrite come from the parent POM.

## Status

Detection, classification, resolution, fix paths, history and verification are
implemented and tested. So is the loop the module was built for: `MergeWorkflow`
keeps a branch up to date through JGit, `MergeBatch` handles many files at once,
`MergeReportWriter` plus a Bun script produce a reviewer-facing report, and
`MergeFileTool` fixes a marker-carrying file in place - preparing everything it
cannot fix as a private, anonymization-first fixture for the next resolver.

Overload comparison is now **type-aware**: it compares resolved parameter types, so
two spellings of one signature are recognised as one. Only that resolver needs type
information — placing imports, merging comments and the rest work from the conflict
text alone, which is why a type context is optional and declared per resolver.

`TypeChangeConflictResolver` still classifies widening from a hardcoded table of JDK
type names, which is the remaining known approximation.

Structural, API and overlapping-body conflicts are **never** resolved
automatically, by design. The reasoning, with worked examples, is in
[`DESIGN_NEVER_AUTO_RESOLVED.md`](DESIGN_NEVER_AUTO_RESOLVED.md).

## Documents

| Document | Contents |
|---|---|
| [QUICKSTART.md](QUICKSTART.md) | Runnable examples for each capability |
| [ADDING_A_RESOLVER.md](ADDING_A_RESOLVER.md) | The extension pattern |
| [docs/CONFLICT_FILE_TOOL.md](docs/CONFLICT_FILE_TOOL.md) | **`MergeFileTool`**: fixing a marker-carrying file, and the private fixture loop that turns what stays broken into a new resolver |
| [docs/THREE_WAY_FIXTURES.md](docs/THREE_WAY_FIXTURES.md) | How to add a merge case as real source files, and why |
| [docs/WHAT_IS_BASE.md](docs/WHAT_IS_BASE.md) | **What `base` means**: the last-synced upstream state, not Git's merge base |
| [VERSION_MAINTENANCE.md](VERSION_MAINTENANCE.md) | **The half-yearly version-update obligation**: what to bump, how to verify, and the traps |
| [DESIGN_NEVER_AUTO_RESOLVED.md](DESIGN_NEVER_AUTO_RESOLVED.md) | Why three conflict kinds stay manual |
| [IMPROVEMENT_PROPOSAL.md](IMPROVEMENT_PROPOSAL.md) | The analysis and evidence behind the last round of work |
| [IMPROVEMENTS_DELIVERED.md](IMPROVEMENTS_DELIVERED.md) | What that round actually produced |
| [IMPROVEMENT_PROPOSAL_ROUND_2.md](IMPROVEMENT_PROPOSAL_ROUND_2.md) | **Where the evidence is thin**: fixture coverage, marker validation (fixed, §7), and what to fix first |
| [scripts/git-sample/README.md](scripts/git-sample/README.md) | Turning one fixture into a real git repository, to open in a git GUI or hand to another tool |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Delivery phases |
| [CHANGELOG.md](CHANGELOG.md) | Change history |
