# CommentAddConflictResolver

Resolves `COMMENT_ADD` conflicts — both branches added comments or
documentation at the same place. Comments carry no semantics, so two branches
adding *different* comments is purely additive and the union is always safe.
This is a classic false conflict: merge tools report it only because the added
lines are adjacent. Declared handling: **AUTO**. Not sticky.

[`CommentAddConflictResolver`](../../../src/main/java/com/codebuddy/merge/CommentAddConflictResolver.java) ·
[`CommentAddConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java)

## What it decides

1. Collect the comment text of each side with `commentsIn`: `//` line comments,
   single-line `/* … */` block comments, and javadoc `* …` continuation lines.
   The comment marker is stripped and the text trimmed; blank comments are
   dropped.
2. If neither side has any comment text, decline — there is nothing additive to
   merge and the conflict routes to the MANUAL fallback.
3. Otherwise build the union: branch 1's comments in order, then branch 2's,
   deduplicated (insertion-ordered set).
4. Answer `AUTO` with strategy `KEEP_BOTH`. The resolved code is the union
   joined by newlines — the distinct comment *texts*, markers stripped — and
   the explanation reports how many distinct comments were merged and how many
   duplicates were dropped.

## The canonical sample

Every resolver test starts from the shared per-type sample in
[`ConflictFixtures`](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java):
both branches added a different comment line above the same statement
(`\n` separates the lines of a side; branch 1 is ours, branch 2 is theirs):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:comment-add-sample)
```java
    static final String COMMENT_ADD_BASE = "int total = 0;";
    static final String COMMENT_ADD_BRANCH1 = "// branch 1 explains the running total\nint total = 0;";
    static final String COMMENT_ADD_BRANCH2 = "// branch 2 records the currency\nint total = 0;";
```

Both comments survive in the union:

[../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java#region:keeps-both-branches-comments)
```java
    @Test
    @DisplayName("keeps the documentation from both branches")
    void keepsBothBranchesComments() {
        ConflictResolution resolution = resolver.resolve(ConflictFixtures.sample(ConflictType.COMMENT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertTrue(resolution.getResolvedCode().contains("branch 1 explains the running total"));
        assertTrue(resolution.getResolvedCode().contains("branch 2 records the currency"));
    }
```

## Example: a comment both branches wrote identically

An identical comment on both sides is a duplicate, not a disagreement — the
union keeps it exactly once:

[../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java#region:drops-duplicate-comments)
```java
    @Test
    @DisplayName("drops a comment both branches wrote identically")
    void dropsDuplicateComments() {
        Conflict conflict = new Conflict(ConflictType.COMMENT_ADD, ConflictFixtures.FILE,
            "identical comments",
            "int total = 0;",
            "// shared explanation\nint total = 0;",
            "// shared explanation\nint total = 0;");

        String resolved = resolver.resolve(conflict).getResolvedCode();
        int occurrences = resolved.split("shared explanation", -1).length - 1;
        assertEquals(1, occurrences, "an identical comment must not be duplicated: " + resolved);
    }
```

## Example: the comment forms it recognises

Line comments, single-line block comments and javadoc lines are all collected,
with their markers stripped:

[../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java#region:recognises-comment-forms)
```java
    @Test
    @DisplayName("recognises line and block comments")
    void recognisesCommentForms() {
        List<String> comments = CommentAddConflictResolver.commentsIn(
            "// line comment\n/* block comment */\n* javadoc line\nint x = 1;");

        assertTrue(comments.contains("line comment"), "line comments must be found: " + comments);
        assertTrue(comments.contains("block comment"), "block comments must be found: " + comments);
        assertTrue(comments.contains("javadoc line"), "javadoc lines must be found: " + comments);
    }
```

## When it declines

Code changes with no comment text on either side are not this resolver's
conflict; it declines and the MANUAL fallback takes over (still offering fix
paths):

[../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java#region:declines-when-no-comments-present)
```java
    @Test
    @DisplayName("declines when neither branch added a comment")
    void declinesWhenNoCommentsPresent() {
        Conflict conflict = new Conflict(ConflictType.COMMENT_ADD, ConflictFixtures.FILE,
            "no comments", "int total = 0;", "int total = 1;", "int total = 2;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }
```

## What it emits

- **Kind/strategy**: `AUTO` + `KEEP_BOTH` whenever at least one side has
  comment text; the MANUAL fallback when it declines.
- **Resolved code**: the distinct comment texts, branch 1's first, one per
  line, markers stripped. The union is safe by construction — comments do not
  affect behaviour — which is why this type never needs a reviewer for the
  additive case.
- **Fix paths**: the primary path recommends keeping both sides' comments
  (naming the count), with keeping only one side as the alternative for when a
  comment is stale or wrong:

[../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/CommentAddConflictResolverTest.java#region:recommends-keeping-both)
```java
    @Test
    @DisplayName("recommends keeping both branches' documentation")
    void recommendsKeepingBoth() {
        FixPath primary = resolver.getFixPaths(ConflictFixtures.sample(ConflictType.COMMENT_ADD)).get(0);
        assertTrue(primary.hasRecommendation());
        assertTrue(primary.getRecommended().startsWith("Keep both"),
            "was " + primary.getRecommended());
    }
```

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
