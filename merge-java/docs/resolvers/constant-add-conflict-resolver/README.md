# ConstantAddConflictResolver

Resolves `CONSTANT_ADD` conflicts — both branches added constants at the same
place. Independent additions coexist; the same constant *name* defined with
*different values* is a real disagreement that a human must settle, because the
value is observable at runtime. Declared handling: **AUTO**. Not sticky.

[`ConstantAddConflictResolver`](../../../src/main/java/com/codebuddy/merge/ConstantAddConflictResolver.java) ·
[`ConstantAddConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java)

## What it decides

1. Collect the constants of each side with `constantsIn`: declarations carrying
   `static final` (or `final static`) modifiers, and enum-member lines written
   in `SCREAMING_CASE` (skipping modifier-like words such as `PUBLIC` or
   `CLASS`). Each constant maps from its name to its declaration line.
2. If neither side declares a constant, decline — the MANUAL fallback takes
   over.
3. Intersect the two name sets. A shared name whose declaration *differs*
   between the sides is a collision: answer `REVIEW` with strategy
   `MERGE_SAFE`, keeping branch 1's text as the provisional resolution and
   naming the offending constants in the explanation. One definition must win,
   and choosing it is a judgement about callers.
4. Otherwise (disjoint names, or shared names defined identically) answer
   `AUTO` with strategy `KEEP_BOTH`. The resolved code keeps both sides'
   declaration text — branch 1's followed by branch 2's — and the explanation
   reports how many constants were merged and how many were defined identically
   on both sides.

## The canonical sample

Both branches kept the base constant and each added a different one
(`\n` separates the lines of a side; branch 1 is ours, branch 2 is theirs):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:constant-add-sample)
```java
    static final String CONSTANT_ADD_BASE = "static final int MAX_RETRIES = 3;";
    static final String CONSTANT_ADD_BRANCH1 = "static final int MAX_RETRIES = 3;\nstatic final int TIMEOUT_MS = 500;";
    static final String CONSTANT_ADD_BRANCH2 = "static final int MAX_RETRIES = 3;\nstatic final int RETRY_DELAY_MS = 250;";
```

The two additions are independent, so both are kept automatically:

[../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java#region:keeps-independent-constants)
```java
    @Test
    @DisplayName("keeps constants that were added independently on each branch")
    void keepsIndependentConstants() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.CONSTANT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertTrue(resolution.getResolvedCode().contains("TIMEOUT_MS"));
        assertTrue(resolution.getResolvedCode().contains("RETRY_DELAY_MS"));
    }
```

## Example: the same name with different values

Two definitions of `LIMIT`, two different values — textually both sides merely
"added a line"; only reading the declarations shows the *same name* is defined
twice. This escalates, and the explanation names the constant:

[../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java#region:escalates-on-differing-values)
```java
    @Test
    @DisplayName("escalates when both branches define the same constant differently")
    void escalatesOnDifferingValues() {
        Conflict conflict = new Conflict(ConflictType.CONSTANT_ADD, ConflictFixtures.FILE,
            "same constant, different value",
            "static final int MAX_RETRIES = 3;",
            "static final int MAX_RETRIES = 3;\nstatic final int LIMIT = 10;",
            "static final int MAX_RETRIES = 3;\nstatic final int LIMIT = 99;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a differing value is a real disagreement, not an additive change");
        assertTrue(resolution.getExplanation().contains("LIMIT"),
            "the explanation must name the offending constant: " + resolution.getExplanation());
    }
```

## Example: an identically-defined constant is a duplicate

Both branches defining the same name with the same value is agreement, not
conflict — it stays `AUTO`:

[../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java#region:treats-identical-definition-as-duplicate)
```java
    @Test
    @DisplayName("treats an identically-defined constant as a duplicate")
    void treatsIdenticalDefinitionAsDuplicate() {
        Conflict conflict = new Conflict(ConflictType.CONSTANT_ADD, ConflictFixtures.FILE,
            "same constant, same value",
            "static final int MAX = 1;",
            "static final int MAX = 1;\nstatic final int LIMIT = 10;",
            "static final int MAX = 1;\nstatic final int LIMIT = 10;");

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolver.resolve(conflict).getKind(),
            "identical additions need no human decision");
    }
```

## Example: enum members count as constants

Enum member lines are collected by name like any other constant, so two
branches extending the same enum with different members compose:

[../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java#region:extracts-enum-members)
```java
    @Test
    @DisplayName("extracts enum members")
    void extractsEnumMembers() {
        Map<String, String> constants = ConstantAddConflictResolver.constantsIn(
            "PENDING,\nACTIVE,\nCLOSED;");

        assertTrue(constants.containsKey("PENDING"), "was " + constants.keySet());
        assertTrue(constants.containsKey("ACTIVE"), "was " + constants.keySet());
    }
```

## When it declines

Sides with no constant declarations at all are not this resolver's conflict:

[../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java#region:declines-when-no-constants-present)
```java
    @Test
    @DisplayName("declines when neither side adds a constant")
    void declinesWhenNoConstantsPresent() {
        Conflict conflict = new Conflict(ConflictType.CONSTANT_ADD, ConflictFixtures.FILE,
            "no constants", "int total = 0;", "int total = 1;", "int total = 2;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }
```

## What it emits

- **Kind/strategy**: `AUTO` + `KEEP_BOTH` for non-colliding additions;
  `REVIEW` + `MERGE_SAFE` when a name is defined with different values (branch
  1's text is the provisional resolution); the MANUAL fallback when it declines.
- **Resolved code**: for the AUTO case, both sides' declaration text — a
  declaration present on both sides appears once per side; the decision the
  resolution carries is "keep both additions", and the explanation states the
  shared-identical count. For the REVIEW case, branch 1's text, awaiting the
  reviewer's pick.
- **Fix paths**: with no collision, "Keep both" is recommended; with a
  collision the primary path lists the disagreeing values per branch and there
  is an explicit path for renaming one side's constant (with the warning that
  renaming a public constant breaks callers):

[../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ConstantAddConflictResolverTest.java#region:recommends-keeping-both)
```java
    @Test
    @DisplayName("recommends keeping both when nothing collides")
    void recommendsKeepingBoth() {
        FixPath primary =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.CONSTANT_ADD)).get(0);
        assertEquals("Keep both", primary.getRecommended());
    }
```

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
