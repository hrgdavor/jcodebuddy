# MethodBodyChangeConflictResolver

Resolves `METHOD_BODY_CHANGE` conflicts — both branches edited the body of the
same method. Many of these are false conflicts: the two edits touch different
statements and can be combined mechanically. This resolver detects that case
and offers the combined body — but **still as `REVIEW`**, because "the edits
look independent" is a syntactic judgement and only a human can confirm the
merged behaviour is what was intended. Declared handling: **REVIEW**. Not
sticky.

[`MethodBodyChangeConflictResolver`](../../../src/main/java/com/codebuddy/merge/MethodBodyChangeConflictResolver.java) ·
[`MethodBodyChangeConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java)

## What it decides

1. Reduce each side to its statements with `statementsIn`: trimmed, non-blank
   lines that are not comments; `import` and `package` lines are excluded
   because they belong to their own conflict types (counting them would make a
   pure import conflict look like a body edit as well).
2. If either side has no statements, decline.
3. Compute what each branch changed against the base statements, and intersect
   the two change sets:
   - **Identical edits** (both branches changed exactly the same statements the
     same way): nothing to reconcile — `REVIEW` + `MERGE_SAFE` with branch 1's
     body, explained as "the same edits, so either side is already correct".
   - **Overlapping edits** (a shared changed statement but not identical change
     sets): the edits cannot be combined mechanically — `REVIEW` +
     `MERGE_SAFE`, branch 1's body kept provisionally, the explanation naming
     the shared statements.
   - **Disjoint edits**: combine — `REVIEW` + `MERGE_SAFE`, resolved code is
     branch 2's statements followed by branch 1's statements that are not
     already present (deduplicated, order preserved). The recommended fix path
     is "Combine both edits".

Every outcome is `REVIEW`: even a mechanically clean combination of two
individually-correct edits is not guaranteed to be correct together.

## The canonical sample

Base body `int total = 0; return total;`; ours inserts `total += 1;`, theirs
inserts `total *= 2;` — disjoint edits to the same method (`\n` separates the
lines of a side):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:method-body-change-sample)
```java
    static final String METHOD_BODY_CHANGE_BASE = "int total = 0;\nreturn total;";
    static final String METHOD_BODY_CHANGE_BRANCH1 = "int total = 0;\ntotal += 1;\nreturn total;";
    static final String METHOD_BODY_CHANGE_BRANCH2 = "int total = 0;\ntotal *= 2;\nreturn total;";
```

The combined body keeps both edits, and still asks for confirmation:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:combines-disjoint-edits)
```java
    @Test
    @DisplayName("combines disjoint edits but still asks for confirmation")
    void combinesDisjointEdits() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a syntactic merge of two edits still needs human confirmation");
        assertTrue(resolution.getResolvedCode().contains("total += 1;"),
            "branch 1's edit must be present: " + resolution.getResolvedCode());
        assertTrue(resolution.getResolvedCode().contains("total *= 2;"),
            "branch 2's edit must be present: " + resolution.getResolvedCode());
    }
```

## Example: both branches made the identical edit

Agreement is reported as such — either side's body is already correct:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:recognises-identical-edits)
```java
    @Test
    @DisplayName("recognises an edit both branches made identically")
    void recognisesIdenticalEdits() {
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "identical edit",
            "int total = 0;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;");

        ConflictResolution resolution = resolver.resolve(conflict);
        assertTrue(resolution.getExplanation().toLowerCase().contains("same"),
            "an identical edit should be described as such: " + resolution.getExplanation());
    }
```

## Example: overlapping edits are reported, not combined

The same shape seen from the combination side — when the change sets intersect,
the resolver refuses to merge and says the edits collide:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:reports-overlapping-edits)
```java
    @Test
    @DisplayName("reports overlapping edits instead of combining them")
    void reportsOverlappingEdits() {
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "same statement changed",
            "int total = 0;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;",
            "int total = 0;\ntotal += 1;\nreturn total;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind());
        assertTrue(resolution.getExplanation().contains("same")
                || resolution.getExplanation().contains("both"),
            "the explanation must say the edits collide: " + resolution.getExplanation());
    }
```

A partial overlap — both branches inserted the *same* statement and each
inserted a different one besides — names the shared statement and keeps branch
1's body only:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:reports-overlapping-but-different-edits)
```java
    @Test
    @DisplayName("names the shared statement when the edits only partly overlap")
    void reportsOverlappingButDifferentEdits() {
        // Both branches inserted the same statement, and each inserted a
        // different one besides: the shared edit overlaps, so the bodies
        // cannot be combined mechanically.
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "partly overlapping edits",
            "int total = 0;\nreturn total;",
            "int total = 0;\ntotal += 1;\naudit();\nreturn total;",
            "int total = 0;\ntotal += 1;\nnotify();\nreturn total;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "partly overlapping edits are not mechanically combinable");
        assertTrue(resolution.getExplanation().contains("total += 1;"),
            "the explanation must name the shared statement: " + resolution.getExplanation());
        assertFalse(resolution.getResolvedCode().contains("notify();"),
            "branch 2's extra statement must not be silently combined in: "
                + resolution.getResolvedCode());
    }
```

## Example: what counts as a statement

Blank lines and comments do not participate in the comparison, so formatting
and documentation edits cannot make two bodies look different:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:ignores-blank-lines-and-comments)
```java
    @Test
    @DisplayName("ignores blank lines and comments when comparing statements")
    void ignoresBlankLinesAndComments() {
        List<String> statements = MethodBodyChangeConflictResolver.statementsIn(
            "int total = 0;\n\n// a comment\n  return total;  \n");

        assertEquals(List.of("int total = 0;", "return total;"), statements);
    }
```

## When it declines

A side with no statements leaves nothing to compare:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:declines-without-statements)
```java
    @Test
    @DisplayName("declines when a side has no statements to compare")
    void declinesWithoutStatements() {
        Conflict conflict = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "empty bodies", "int total = 0;", "", "int total = 1;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }
```

## What it emits

- **Kind/strategy**: always `REVIEW` + `MERGE_SAFE` when it answers at all; the
  MANUAL fallback when it declines.
- **Resolved code**: branch 1's body for identical/overlapping edits; the
  combined statement list (branch 2's statements first, then branch 1's
  additions) for disjoint edits.
- **Fix paths**: the primary path recommends "Combine both edits" for disjoint
  edits and offers keeping either side; with overlap it offers choosing which
  edit to keep for the colliding statements and names them in the
  justification:

[../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MethodBodyChangeConflictResolverTest.java#region:recommends-combining)
```java
    @Test
    @DisplayName("recommends combining disjoint edits")
    void recommendsCombining() {
        FixPath primary =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE)).get(0);
        assertEquals("Combine both edits", primary.getRecommended());
    }
```

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../structural-change-conflict-resolver/README.md`](../structural-change-conflict-resolver/README.md)
  — the catch-all for member-level restructuring this resolver does not cover
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
