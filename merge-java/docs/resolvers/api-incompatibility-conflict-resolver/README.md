# ApiIncompatibilityConflictResolver

Handles `API_INCOMPATIBILITY` conflicts — the two branches disagree on a
*public contract*: visibility, modifiers, return type, method name, parameter
list or declared exceptions. Every one of these is potentially breaking for
callers that live outside the merge, which the resolver cannot see. It
therefore never applies a change; it classifies the breaking edit and reports
precisely **which part of the contract moved**, so a reviewer knows what to
check instead of reconstructing it from conflict markers. Declared handling:
**MANUAL**. Never replayable.

[`ApiIncompatibilityConflictResolver`](../../../src/main/java/com/codebuddy/merge/ApiIncompatibilityConflictResolver.java) ·
[`ApiIncompatibilityConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java)

## What it decides

Nothing automatically — `doResolve` always returns `null`, routing to the
MANUAL fallback (manual marker as resolved code). The resolver's contribution
is `describeBreakingEdits`: it parses each side's declaration with a signature
pattern (visibility, other modifiers, generics, return type, name, parameters,
`throws` clause) and names every contract element that differs, phrased for a
reviewer:

- a narrowed or widened **visibility** (`public` → `protected` breaks external
  callers; the direction is named),
- changed **modifiers**,
- a changed **return type**,
- a changed **name** (which breaks every caller),
- changed **parameters**,
- **exceptions** added or dropped (a new checked exception breaks callers that
  do not catch it),
- and, when a side cannot be parsed unambiguously, exactly that — a reviewer
  is never told "nothing".

## The canonical sample

Base `public void process() throws IOException`; ours changed the return type
to `int` (and dropped the throws clause), theirs to `long` (`\n` separates the
lines of a side):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:api-incompatibility-sample)
```java
    static final String API_INCOMPATIBILITY_BASE = "public void process() throws IOException { }";
    static final String API_INCOMPATIBILITY_BRANCH1 = "public int process() { }";
    static final String API_INCOMPATIBILITY_BRANCH2 = "public long process() { }";
```

No automatic answer, nothing applied, nothing replayed:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:never-automatically-resolves)
```java
    @Test
    @DisplayName("never changes a public contract automatically")
    void neverAutomaticallyResolves() {
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode());
        assertFalse(resolution.isReplayable());
    }
```

The return-type break is named explicitly:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:names-return-type-difference)
```java
    @Test
    @DisplayName("names the return type difference")
    void namesReturnTypeDifference() {
        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(
            ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY));

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("return type")),
            "the return type change must be named: " + edits);
    }
```

## Example: each contract element is classified

A narrowed visibility:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:names-narrowed-visibility)
```java
    @Test
    @DisplayName("names a narrowed visibility")
    void namesNarrowedVisibility() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "visibility narrowed",
            "public void process() { }",
            "protected void process() { }",
            "public void process() { }");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("visibility")),
            "the visibility change must be named: " + edits);
    }
```

A dropped checked exception:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:names-dropped-exception)
```java
    @Test
    @DisplayName("names a dropped checked exception")
    void namesDroppedException() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "exception dropped",
            "public void process() throws IOException { }",
            "public void process() { }",
            "public void process() throws IOException { }");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("exception")),
            "the dropped exception must be named: " + edits);
    }
```

A changed parameter list:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:names-parameter-change)
```java
    @Test
    @DisplayName("names a parameter list change")
    void namesParameterChange() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "parameters changed",
            "public void process(String id) { }",
            "public void process(String id, boolean force) { }",
            "public void process(String id) { }");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertTrue(edits.stream().anyMatch(edit -> edit.contains("parameters")),
            "the parameter change must be named: " + edits);
    }
```

And when nothing parses, the reviewer is still told something — never handed an
empty report:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:always-reports-something)
```java
    @Test
    @DisplayName("always reports something, even for an unparsable declaration")
    void alwaysReportsSomething() {
        Conflict conflict = new Conflict(ConflictType.API_INCOMPATIBILITY, ConflictFixtures.FILE,
            "unparsable", "int x = 1;", "int x = 2;", "int x = 3;");

        Set<String> edits = ApiIncompatibilityConflictResolver.describeBreakingEdits(conflict);

        assertFalse(edits.isEmpty(), "a reviewer must never be told nothing");
    }
```

## What it emits

- **Kind/strategy**: always `MANUAL` (strategy `MANUAL`), resolved code is the
  manual marker, and the resolution is not replayable — each API break is a
  one-off judgement.
- **Fix paths**: the primary path ("Resolve a change to the public API
  surface") offers keeping branch 1's signature, branch 2's, the base
  signature, or introducing a compatibility overload, with the detected
  breaking edits as its justification and the warning that callers outside the
  merge may stop compiling or silently bind to different behaviour:

[../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ApiIncompatibilityConflictResolverTest.java#region:warns-about-callers)
```java
    @Test
    @DisplayName("warns that callers outside the merge may break")
    void warnsAboutCallers() {
        FixPath primary = resolver
            .getFixPaths(ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY)).get(0);

        assertTrue(primary.getImpact().contains("Callers"),
            "the impact must warn about external callers: " + primary.getImpact());
    }
```

  A second path proposes preserving compatibility with a bridge — an overload
  delegating to the new signature, or deprecating the old one and keeping both
  — noting the usual cost: API surface that must later be removed through a
  deprecation cycle. The manual escape hatch is always present.

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../structural-change-conflict-resolver/README.md`](../structural-change-conflict-resolver/README.md)
  — the other always-manual resolver
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
