# StructuralChangeConflictResolver

Handles `STRUCTURAL_CHANGE` conflicts — whole members added, removed or
reworked in ways that do not compose: a method deleted on one branch and
modified on the other, or two branches each restructuring the same member
differently. There is no safe automatic answer, because any choice silently
discards someone's work. The resolver's job is to **refuse clearly** and hand
the reviewer a precise, quantified description of what is at stake rather than
a bare `<<<<<<<` block. Declared handling: **MANUAL**. Never sticky.

[`StructuralChangeConflictResolver`](../../../src/main/java/com/codebuddy/merge/StructuralChangeConflictResolver.java) ·
[`StructuralChangeConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java)

## What it decides

Nothing — deliberately. `doResolve` always returns `null`, which routes the
conflict to the manual fallback in
[`AbstractConflictResolver`](../../../src/main/java/com/codebuddy/merge/AbstractConflictResolver.java):
`MANUAL` kind, `MANUAL` strategy, the manual marker as resolved code, and the
fix paths below. The value this resolver adds is the *description* of the
disagreement:

- lines only in branch 1 (not present in branch 2),
- lines only in branch 2,
- base lines branch 1 dropped or rewrote,

counted and phrased so the reviewer sees what each choice would discard.
`STRUCTURAL_CHANGE` is also the type the detector falls back to when both
branches changed overlapping members and no narrower type fits — see
[`ConflictDetectionService`](../../../src/main/java/com/codebuddy/merge/ConflictDetectionService.java).

## The canonical sample

Both branches reworked `process()` — ours added a `charge()` call, theirs a
`refund()` call — while `audit()` stayed put (`\n` separates the lines of a
side):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:structural-change-sample)
```java
    static final String STRUCTURAL_CHANGE_BASE = "void process() { audit(); }\nvoid audit() { }";
    static final String STRUCTURAL_CHANGE_BRANCH1 = "void process() { audit(); charge(); }\nvoid audit() { }";
    static final String STRUCTURAL_CHANGE_BRANCH2 = "void process() { audit(); refund(); }\nvoid audit() { }";
```

The outcome is always a human decision, with options attached:

[../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java#region:always-requires-human-decision)
```java
    @Test
    @DisplayName("always hands structural change to a human")
    void alwaysRequiresHumanDecision() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.MANUAL,
            resolution.getResolutionStrategy());
        assertTrue(resolution.requiresHumanDecision());
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "a refusal must still tell the reviewer what the options are");
    }
```

## Example: the marker as resolved code

Nothing was applied, and the caller can detect that mechanically — the resolved
code is the manual marker, never a half-merged guess:

[../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java#region:emits-manual-marker)
```java
    @Test
    @DisplayName("emits the manual marker as the resolved code")
    void emitsManualMarker() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode(),
            "the caller must be able to detect that nothing was applied");
    }
```

## Example: the refusal quantifies both sides

The fix paths offer both branches' structures *and* a combined reading, and the
impact statement counts the lines each choice would discard:

[../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java#region:describes-both-sides)
```java
    @Test
    @DisplayName("describes what each branch does that the other does not")
    void describesBothSides() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        FixPath primary = resolution.getAlternativePaths().get(0);
        assertTrue(primary.getOptions().contains("Apply branch 1"),
            "both sides must be offered: " + primary.getOptions());
        assertTrue(primary.getOptions().contains("Apply branch 2"),
            "both sides must be offered: " + primary.getOptions());
        assertTrue(primary.getImpact().contains("unique"),
            "the impact must quantify what would be discarded: " + primary.getImpact());
    }
```

The manual escape hatch is always among them:

[../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java#region:offers-manual-escape-hatch)
```java
    @Test
    @DisplayName("always offers the manual escape hatch")
    void offersManualEscapeHatch() {
        List<FixPath> fixPaths =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertTrue(fixPaths.stream().anyMatch(path ->
                path.getDescription().contains("by hand")),
            "the reviewer must always be able to take it over: " + fixPaths);
    }
```

## Why it is never sticky

A structural decision is a one-off judgement about *this* restructuring;
replaying it onto a different future conflict would apply an answer to a
question nobody asked:

[../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/StructuralChangeConflictResolverTest.java#region:never-sticky)
```java
    @Test
    @DisplayName("never marks a structural resolution as replayable")
    void neverSticky() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertFalse(resolution.isSticky(),
            "a one-off structural decision must not be replayed blindly");
        assertFalse(resolution.isReplayable());
    }
```

## What it emits

- **Kind/strategy**: always `MANUAL` + `MANUAL` (identical branches included —
  the type says a human must look, and the resolver does not second-guess the
  detector).
- **Resolved code**: the manual marker. In the conflict-file tool this lands as
  a `LEFT_MANUAL` block and the case is copied into the fixture workspace for
  a resolver to be built against (see
  [`CONFLICT_FILE_TOOL.md`](../../CONFLICT_FILE_TOOL.md)).
- **Fix paths**: "Take branch 1's structure" (apply branch 1 / branch 2 /
  combine both / rewrite by hand, quantifying what each discards), "Combine
  both structures" (merge member by member or keep the union, with the caveat
  that a reviewer must confirm nothing is duplicated or lost), and the manual
  escape hatch.

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../method-body-change-conflict-resolver/README.md`](../method-body-change-conflict-resolver/README.md)
  — the narrower type for same-method body edits
- [`../../CONFLICT_FILE_TOOL.md`](../../CONFLICT_FILE_TOOL.md) — what happens to
  manual blocks in a marked-up conflict file
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
