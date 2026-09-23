# RenameConflictResolver

Resolves `VARIABLE_RENAME` conflicts — the same declaration was renamed
*differently* on each branch. Unlike an import addition, a rename is a choice,
not a fact: both `purchase` and `invoice` may be valid, but the codebase must
end up using one consistently. This resolver therefore never applies a rename
silently. It identifies the competing names, offers them as fix paths, and
marks the resolution **sticky** so the reviewer's pick is recorded and replayed
automatically on the next base-branch update. Declared handling: **STICKY**.

[`RenameConflictResolver`](../../../src/main/java/com/codebuddy/merge/RenameConflictResolver.java) ·
[`RenameConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java)

## What it decides

1. Extract the first declared name of each side with a deliberately
   conservative pattern: a simple local/field declaration `Type name = …` or
   `Type name;` (modifiers, generics and array brackets allowed). Control-flow
   keywords the regex could swallow (`if`, `for`, `return`, `new`, …) are
   rejected — a bare identifier is never treated as a rename.
2. If either side has no declared name, or both sides declare the *same* name,
   decline: an agreed rename is not a conflict.
3. Otherwise answer `REVIEW` + `MERGE_SAFE`, keeping branch 1's text
   provisionally. The explanation names both competing names (and notes when
   one side kept the base name). The resolution is `sticky` and `replayable`.
4. The recommended fix path is the name that matches the base when one side
   kept it — that choice minimises the diff against base — otherwise branch 1's
   name, for determinism.

## The canonical sample

Base declares `order`; ours renames it to `purchase`, theirs to `invoice`:

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:variable-rename-sample)
```java
    static final String VARIABLE_RENAME_BASE = "int order = 1;";
    static final String VARIABLE_RENAME_BRANCH1 = "int purchase = 1;";
    static final String VARIABLE_RENAME_BRANCH2 = "int invoice = 1;";
```

The resolver never picks a winner itself — it surfaces both names and marks the
decision as one that must be replayed later:

[../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java#region:offers-competing-names)
```java
    @Test
    @DisplayName("never renames silently, but offers the competing names")
    void offersCompetingNames() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a rename is a preference, so a reviewer confirms it");
        assertTrue(resolution.isSticky(),
            "a rename decision must be replayable on the next update");
        assertTrue(resolution.isReplayable());
        assertTrue(resolution.getExplanation().contains("purchase"),
            "branch 1's name must be named: " + resolution.getExplanation());
        assertTrue(resolution.getExplanation().contains("invoice"),
            "branch 2's name must be named: " + resolution.getExplanation());
    }
```

## The sticky loop: decided once, replayed forever

What "sticky" means in practice, end to end: a human picks branch 1's name and
agrees to remember it; the decision is recorded in the branch history; the next
update of the same branch sees the same conflict and replays the recorded
decision as `DEFERRED` + `STICKY_REPLAY` instead of re-litigating it —
re-anchored to the incoming file path:

[../../../src/test/java/com/codebuddy/merge/MergeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MergeConflictResolverTest.java#region:replays-sticky-decision)
```java
    @Test
    @DisplayName("replays a sticky decision recorded by a previous run")
    void replaysStickyDecision() {
        Path history = tempDir.resolve(BRANCH);
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);

        // A human picks branch 1's name and agrees to remember it.
        ConflictResolution decision = ConflictResolution.builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .baseCode(conflict.getBaseCode())
            .branch1Code(conflict.getBranch1Code())
            .branch2Code(conflict.getBranch2Code())
            .resolvedCode(conflict.getBranch1Code())
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .explanation("Reviewer chose branch 1's name")
            .sticky(true)
            .branchName(BRANCH)
            .build();

        new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .recordDecision(conflict, decision);

        // A later update of the same branch sees the same conflict.
        ConflictResolution replayed = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.DEFERRED, replayed.getKind(),
            "a recorded decision must be replayed, not re-litigated");
        assertEquals(ConflictResolution.ResolutionStrategy.STICKY_REPLAY,
            replayed.getResolutionStrategy());
        assertEquals(conflict.getFilePath(), replayed.getFilePath(),
            "the replayed decision must be re-anchored to the incoming file");
    }
```

## Example: the base-compatible name is recommended

When one side kept the base name, that side is the recommended choice because
it minimises the diff:

[../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java#region:recommends-base-compatible-name)
```java
    @Test
    @DisplayName("recommends the name that matches the base branch")
    void recommendsBaseCompatibleName() {
        // base uses 'order', branch 1 keeps it, branch 2 renames to 'invoice'.
        Conflict conflict = new Conflict(ConflictType.VARIABLE_RENAME, ConflictFixtures.FILE,
            "one side kept the base name",
            "int order = 1;", "int order = 1;", "int invoice = 1;");

        ConflictResolution resolution = resolver.resolve(conflict);
        FixPath primary = resolution.getAlternativePaths().get(0);

        assertTrue(primary.hasRecommendation(), "there is a base-compatible choice");
        assertTrue(primary.getRecommended().contains("order"),
            "the base-compatible name minimises the diff: " + primary.getRecommended());
    }
```

The reviewer is also always offered the option to make the choice permanent:

[../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java#region:offers-to-remember-decision)
```java
    @Test
    @DisplayName("offers to remember the decision")
    void offersToRememberDecision() {
        List<FixPath> fixPaths = resolver.getFixPaths(ConflictFixtures.sample(ConflictType.VARIABLE_RENAME));

        assertTrue(fixPaths.stream().anyMatch(path -> path.getDescription().toLowerCase().contains("sticky")),
            "the reviewer must be able to make the choice permanent: " + fixPaths);
    }
```

## Example: the name pattern is conservative

Control-flow statements are not declarations, so the resolver never mistakes an
`if` for a renamed variable:

[../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java#region:ignores-control-flow-keywords)
```java
    @Test
    @DisplayName("does not treat control-flow keywords as declarations")
    void ignoresControlFlowKeywords() {
        assertTrue(RenameConflictResolver.firstDeclaredName("if (ready) { return; }").isEmpty(),
            "an if-statement is not a declaration");
    }
```

## When it declines

Both branches renaming to the *same* name is agreement, not a conflict:

[../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/RenameConflictResolverTest.java#region:declines-when-names-agree)
```java
    @Test
    @DisplayName("declines when both branches use the same name")
    void declinesWhenNamesAgree() {
        Conflict conflict = new Conflict(ConflictType.VARIABLE_RENAME, ConflictFixtures.FILE,
            "same rename", "int order = 1;", "int invoice = 1;", "int invoice = 1;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "an agreed rename is not a conflict");
    }
```

## What it emits

- **Kind/strategy**: `REVIEW` + `MERGE_SAFE`, sticky and replayable; `DEFERRED`
  + `STICKY_REPLAY` when a recorded decision is replayed; the MANUAL fallback
  when it declines.
- **Resolved code**: branch 1's text, kept provisionally until the reviewer
  picks a name — a rename must be applied consistently across the codebase,
  which is beyond a single hunk.
- **Fix paths**: "Adopt one name for both branches" (rename to branch 1's name
  everywhere / branch 2's / keep the base name, with the base-compatible
  choice recommended when there is one) and "Make the rename decision sticky"
  (remember the choice for this declaration, removing the same conflict from
  every future update on this branch).

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../package-change-conflict-resolver/README.md`](../package-change-conflict-resolver/README.md)
  — the other sticky resolver
- [`../../CONFLICT_FILE_TOOL.md`](../../CONFLICT_FILE_TOOL.md) — how recorded
  decisions are replayed on marker blocks (`--apply-recorded`)
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
