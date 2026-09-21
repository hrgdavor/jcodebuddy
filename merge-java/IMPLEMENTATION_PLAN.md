# Implementation plan — merge-java

Status legend: **[done]**, **[partial]**, **[todo]**.

> **Superseded for forward work.** Phases 9–12 below were detailed, executed and
> extended by [`IMPROVEMENT_PROPOSAL.md`](IMPROVEMENT_PROPOSAL.md); the outcome is
> recorded in [`IMPROVEMENTS_DELIVERED.md`](IMPROVEMENTS_DELIVERED.md), which is
> the authority on what has been built. The one substantial item still outstanding
> is WS2 step 2: replacing the token-based scanner with OpenRewrite's AST.

## Goal

Resolve the common, reliably-resolvable merge conflicts automatically, give
reviewers actionable fix paths where the answer is ambiguous, and remember this
branch's decisions so repeated base-branch updates stop re-conflicting.

## Design constraint — three cases are never auto-resolved [done]

A plan that only lists features invites the question "why isn't *this* automated
too?". Three cases are excluded by design, and the reasoning, worked examples and
enforcement map are written up in full in
[`DESIGN_NEVER_AUTO_RESOLVED.md`](DESIGN_NEVER_AUTO_RESOLVED.md):

| Excluded | Conflict types | Instead |
|---|---|---|
| Structural change (members added/removed incompatibly) | `STRUCTURAL_CHANGE` | Manual, with a per-side diff summary |
| Public contract change | `API_INCOMPATIBILITY` | Manual, naming the exact contract elements that moved |
| Overlapping edits to one method body | `METHOD_BODY_CHANGE` (overlap only) | Disjoint edits combined for `REVIEW`; overlapping edits reported |

The governing asymmetry: an **additive** change (both sides add, neither removes)
has a correct answer that is a function of its inputs, and getting it wrong
produces a compile error. A **substitutive** change (a side removes or replaces
what the other touched) requires choosing whose intent to discard, and getting it
wrong produces a silent behavioural regression. The module resolves the first kind
and explains the second.

Three arguments, in brief:

1. **Structural cannot be inferred syntactically.** "Both extended the method" and
   "both replaced the method" are textually indistinguishable without resolved
   symbols. A union can call a helper one branch deleted.
2. **API breakage cannot be inferred from the repository.** The affected callers
   are outside the merge — in other modules, in reflection, in consumers the
   analysis cannot enumerate. A merge tool cannot arbitrate a compatibility
   decision whose inputs it does not have, and auto-picking a winner destroys the
   deprecation cycle.
3. **Body merges do not compose.** Two individually-correct edits can combine into
   incorrect code (the classic null-guard plus resource-close case). Compilation
   proves well-formedness, not intent, and tests encode only the interactions
   their authors considered.

The constraint is **enforced, not documented**: those types declare
`Handling.MANUAL`, their resolvers return `null` unconditionally, a
`STRATEGY → KIND` contract test fails any inconsistency, and the extension
pattern's own worked example is an additive-only body resolver that declines every
removal.

Consequence for the workstreams below: improving these cases means better
*detection* and better *explanation*, never automation. `DESIGN_NEVER_AUTO_RESOLVED.md`
§9 states the evidence that would be required to revisit the boundary.

## Phase 1 — Module and dependencies [done]

- `merge-java` registered in the parent POM module list.
- OpenRewrite (`rewrite-core`, `rewrite-java`, `rewrite-maven`) inherited from the
  parent's dependency management, now at **8.90.4** — the module invents no version.

> **Standing maintenance obligation.** The parser is tied to a JDK release, so the
> JDK level, the parser module and the OpenRewrite version must move together
> roughly every six months. See
> [`VERSION_MAINTENANCE.md`](VERSION_MAINTENANCE.md) for the procedure, the
> verification that actually catches a regression, and the traps hit last time.
- JGit for branch and history access, so the module never shells out to `git`.
- JUnit 5 for tests.
- No `JavaParser` dependency: OpenRewrite bundles its own parser.

> The original plan pinned `rewrite-*:2.17.0` and `jgit:6.8.0...-r`. Neither
> version exists / was cached, which made the module unbuildable. Versions now
> come from the parent.

## Phase 2 — Domain model [done]

- `ConflictType` — ten types, each declaring a `Handling` policy
  (`AUTO` / `REVIEW` / `STICKY` / `MANUAL`) that drives both automation level and
  whether decisions are remembered.
- `Conflict` — a conflicting hunk anchored to its file, immutable.
- `ConflictResolution` — resolved code, strategy, `ResolutionKind`, explanation,
  fix paths, stickiness, timestamp, id.
- `FixPath` — options, recommendation, justification, impact.
- `ConflictSignature` — formatting-insensitive identity of a conflict, used to
  decide whether a recorded decision still applies.

## Phase 3 — Resolvers [done]

Ten resolvers, one per conflict type:

| Resolver | Outcome |
|---|---|
| `ImportConflictResolver` | Union of imports, rendered as valid statements |
| `CommentAddConflictResolver` | Union of comments, de-duplicated |
| `ConstantAddConflictResolver` | Union; review when a name has different values |
| `OverloadAddConflictResolver` | Both kept if parameter lists differ; review if identical |
| `TypeChangeConflictResolver` | Adopts the wider type; review if unrelated |
| `MethodBodyChangeConflictResolver` | Combines disjoint edits for review |
| `RenameConflictResolver` | Offers the competing names; sticky |
| `PackageChangeConflictResolver` | Adopts a one-sided move; sticky when both moved |
| `StructuralChangeConflictResolver` | Always manual, with a structural diff summary |
| `ApiIncompatibilityConflictResolver` | Always manual, naming the moved contract element |

## Phase 4 — Detection [done]

`ConflictDetectionService` finds each conflict shape in a file and anchors it to
its path. Detection is conservative: anything unrecognised becomes a single
`STRUCTURAL_CHANGE` so nothing is silently dropped.

An invariant test asserts that every conflict detection can produce has a
registered resolver, so detection and resolution cannot drift apart.

## Phase 5 — Orchestration [done]

`MergeConflictResolver`:

1. replays a recorded sticky decision when the signature matches;
2. otherwise asks the registry for the resolver owning the type;
3. otherwise records the conflict as needing a human, with fix paths.

`MergeReport` partitions the outcome into auto / review / manual and produces a
one-line summary.

## Phase 6 — Branch history [done]

`BranchConflictStore` persists decisions under
`.jcodebuddy/merge-history/<branch>/decisions/<type>-<hash>.json`, atomic-writes
them, reloads them on start, ignores corrupt entries rather than failing a merge,
and supports an in-memory-only mode for pure single-shot resolution.

## Phase 7 — Extension pattern [done]

- `AbstractConflictResolver` reduces a new resolver to three overrides.
- `ConflictResolvers` is the single registration point, and rejects two resolvers
  claiming the same type.
- `AbstractResolverTest` supplies the contract tests every resolver must pass.
- `ResolverExtensionTest` executes the documented pattern, so the guide cannot
  rot.
- Documented in `ADDING_A_RESOLVER.md`.

## Phase 8 — Tests [done]

431 tests: per-resolver behaviour, detection, history persistence and replay,
orchestration, registry contract, signatures, and the extension pattern.

## Phase 9 — OpenRewrite AST [done → WS2]

**Step 1**: `DeclarationScanner` removed the three silent failures that came from
line-by-line scanning — a comment between declarations, an opening brace on the
following line, and annotations in between.

**Step 2**: `ResolvedTypeReader` and `TypeContext` make overload comparison
*type-aware*. Parameter types are compared as **resolved** types, so
`List<String>` and `java.util.List<java.lang.String>` are one signature and
`List<String>`/`List<Integer>` stay distinct. The token comparison is deleted, so
there is one comparison path that cannot disagree with itself.

The type context is optional and required only by resolvers that declare
`requiresTypeContext()` — currently just `OverloadAddConflictResolver`. Placing
imports needs nothing but text.

**Still todo:** `TypeChangeConflictResolver` keeps its hardcoded JDK name table
(`WIDENING_CHAINS`). Replacing it with real supertype resolution through the same
mechanism is the obvious next step and needs no new capability.

## Phase 10 — JGit integration [done → WS5]

`MergeWorkflow` discovers the branch and merge base, reads all three versions from
the object database, applies the independently applicable resolutions to the working
tree, and reports what is left. Dry by default; no staging or committing. Tested
against a real repository built with JGit in a temporary directory.

## Phase 11 — Reviewer-facing reporting [done → WS6]

`MergeReportWriter` writes the facts as JSON; `scripts/merge-report/render.js`
renders one self-contained HTML file with framework-free vanilla JS, no network and
no unverified links, per DEC-027/029. A test runs Bun end to end and asserts the
output is self-contained.

## Phase 12 — Composition, verification and history trust [done → WS1/WS3/WS4/WS7/WS8]

- **Conflict composition**: the residual structural conflict is emitted alongside
  the recognised ones, each conflict carries a `Region`, and
  `MergeReport.getIndependentlyApplicable()` exposes the safe subset.
- **Verification gate**: `ResolutionVerifier` downgrades an automatic resolution
  that fails a structural check; a verifier can never promote one.
- **History trust**: schema versioning, load diagnostics, pruning, and property
  tests for signature stability.
- **Batch**: `MergeBatch` resolves many files with aggregate counts, dry run and a
  CI exit code.
- **Type resolver gaps**: JDK supertype chains, varargs/array equivalence, generic
  canonicalisation.
