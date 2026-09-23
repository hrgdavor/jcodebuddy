# PackageChangeConflictResolver

Resolves `PACKAGE_CHANGE` conflicts — a class was moved to a different package
on one or both branches. A package is a naming decision and a class cannot live
in two packages at once, so this resolver does not guess between two
destinations. It adopts a move only when exactly one branch made it, surfaces
both destinations otherwise, and is **sticky**: the chosen home is recorded and
replayed on the next update. Declared handling: **STICKY**.

[`PackageChangeConflictResolver`](../../../src/main/java/com/codebuddy/merge/PackageChangeConflictResolver.java) ·
[`PackageChangeConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java)

## What it decides

1. Extract the first `package …;` declaration of each side. A missing
   declaration on either side, or equal packages, means there is nothing to
   arbitrate — decline.
2. Compare against the base package. If exactly one branch kept the base
   package, the other branch's move is the only deliberate change: answer
   `AUTO` with strategy `PREFER_BRANCH1`/`PREFER_BRANCH2`, resolved code = the
   moved side's text, the explanation naming the destination and the base
   package.
3. If both branches moved the class to different destinations, it is a genuine
   disagreement: `REVIEW` + `MERGE_SAFE`, branch 1's text kept provisionally,
   the explanation naming both destinations. One destination must win, and the
   choice fixes every import of this class across the repository — so it is
   recorded as a sticky decision.
4. Fix paths offer the three homes (branch 1's, branch 2's, the base package),
   recommending a destination that is not the base when one side kept the base,
   otherwise branch 1's for determinism. A second fix path lists the imports on
   each side that must be re-pointed to whichever package wins.

## The canonical sample

Base `com.example.payments`; ours moved the class to `com.example.billing`,
theirs to `com.example.ledger`:

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:package-change-sample)
```java
    static final String PACKAGE_CHANGE_BASE = "package com.example.payments;";
    static final String PACKAGE_CHANGE_BRANCH1 = "package com.example.billing;";
    static final String PACKAGE_CHANGE_BRANCH2 = "package com.example.ledger;";
```

Two different destinations — a human picks the home, and the choice is
replayable:

[../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java#region:escalates-two-different-destinations)
```java
    @Test
    @DisplayName("escalates two different destinations and remembers the choice")
    void escalatesTwoDifferentDestinations() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.PACKAGE_CHANGE));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "both branches moved the class, so a human picks the home");
        assertTrue(resolution.isSticky(), "a package choice must be replayable");
        assertTrue(resolution.getExplanation().contains("com.example.billing"));
        assertTrue(resolution.getExplanation().contains("com.example.ledger"));
    }
```

The replay machinery is the branch history: a recorded sticky decision comes
back as `DEFERRED` + `STICKY_REPLAY` on the next update (demonstrated end to
end in the
[rename resolver's documentation](../rename-conflict-resolver/README.md#the-sticky-loop-decided-once-replayed-forever)).

## Example: a one-sided move is adopted

Only ours moved the class; theirs left it in the base package. The move is the
only deliberate change, so it is applied automatically:

[../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java#region:adopts-one-sided-move)
```java
    @Test
    @DisplayName("adopts a move when only one branch moved the class")
    void adoptsOneSidedMove() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "one side moved the class",
            "package com.example.payments;",
            "package com.example.billing;",
            "package com.example.payments;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "the move is the only deliberate change, so it can be adopted");
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH1,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("com.example.billing"));
    }
```

## Example: the imports that must follow the move

A package move is never local — the fix paths flag the imports on each side so
the reviewer knows what must be re-pointed repository-wide:

[../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java#region:flags-imports-to-update)
```java
    @Test
    @DisplayName("flags imports that must be re-pointed after a move")
    void flagsImportsToUpdate() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "move with imports",
            "package com.example.payments;",
            "package com.example.billing;\nimport com.example.billing.Ledger;",
            "package com.example.ledger;\nimport com.example.ledger.Account;");

        List<FixPath> fixPaths = resolver.getFixPaths(conflict);

        assertTrue(fixPaths.stream().anyMatch(path ->
                path.getDescription().toLowerCase().contains("import")),
            "a package move implies import updates: " + fixPaths);
    }
```

## When it declines

Equal package declarations leave nothing to arbitrate:

[../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/PackageChangeConflictResolverTest.java#region:declines-when-packages-agree)
```java
    @Test
    @DisplayName("declines when the package declarations agree")
    void declinesWhenPackagesAgree() {
        Conflict conflict = new Conflict(ConflictType.PACKAGE_CHANGE, ConflictFixtures.FILE,
            "same package", "package a;", "package a;", "package a;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }
```

## What it emits

- **Kind/strategy**: `AUTO` + `PREFER_BRANCH1`/`PREFER_BRANCH2` for a
  one-sided move; `REVIEW` + `MERGE_SAFE` for two competing destinations,
  sticky and replayable; the MANUAL fallback when it declines.
- **Resolved code**: the winning side's text (the moved side for a one-sided
  move; branch 1's provisionally for a competing pair).
- **Fix paths**: "Choose the class's home package" (both destinations plus the
  base package, with the impact stating that the choice must be applied
  repository-wide) and "Re-point imports of the moved class" (listing each
  side's imports, with the warning that un-updated imports fail to compile).

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../rename-conflict-resolver/README.md`](../rename-conflict-resolver/README.md)
  — the other sticky resolver, with the full replay example
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
