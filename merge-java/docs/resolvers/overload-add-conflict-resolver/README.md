# OverloadAddConflictResolver

Resolves `OVERLOAD_ADD` conflicts — both branches added a method with a name
the other side also touched. Distinct parameter lists are overloads and
coexist; *identical* parameter lists cannot both exist and need a human. This
is the one resolver that refuses to work from text alone: whether two parameter
lists are the same is a question about **resolved types**, so it declares
`requiresTypeContext()`. Declared handling: **AUTO**. Not sticky.

[`OverloadAddConflictResolver`](../../../src/main/java/com/codebuddy/merge/OverloadAddConflictResolver.java) ·
[`OverloadAddConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java)

## What it decides

1. Read the method names of both sides. No name appears on both sides →
   decline; this is not an overload conflict.
2. Demand a [`TypeContext`](../../../src/main/java/com/codebuddy/merge/TypeContext.java).
   Without one the comparison cannot be made, and guessing is worse than
   refusing: the answer is `MANUAL` with the marker as resolved code and a fix
   path telling the reviewer to build the resolver with a classpath covering
   the types.
3. Parse all three sides with resolved type attribution
   ([`ResolvedTypeReader`](../../../src/main/java/com/codebuddy/merge/ResolvedTypeReader.java),
   OpenRewrite-backed). A side that fails to parse is reported loudly — without
   resolved types the comparison could invent a collision or miss a real one.
4. Per method name, compute the parameter types each branch *added* relative to
   the base. The method to arbitrate is one both branches added parameters to,
   beyond what the base already declared. No such method → decline; several →
   `MANUAL`, because picking one would be a guess.
5. For the single candidate, intersect the two sides' added parameter types
   (canonicalised by resolved type). Any intersection is a collision: `REVIEW`
   + `MERGE_SAFE`, branch 1's text kept provisionally, the explanation naming
   the method and the colliding types. An empty intersection means genuine
   overloads: `AUTO` + `KEEP_BOTH`, resolved code keeps both sides' text.

## The canonical sample

The base declares `process()`; ours adds `process(String id)`, theirs adds
`process(String id, boolean force)` — same name, different parameter lists
(`\n` separates the lines of a side):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:overload-add-sample)
```java
    static final String OVERLOAD_ADD_BASE = "void process() { }";
    static final String OVERLOAD_ADD_BRANCH1 = "void process() { }\nvoid process(String id) { }";
    static final String OVERLOAD_ADD_BRANCH2 = "void process() { }\nvoid process(String id, boolean force) { }";
```

Resolved with a type context attached (the tests use the JDK context from
[`TestTypeContexts`](../../../src/test/java/com/codebuddy/merge/TestTypeContexts.java)),
both overloads are kept:

[../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java#region:keeps-distinct-overloads)
```java
    @Test
    @DisplayName("keeps both methods when the parameter lists differ")
    void keepsDistinctOverloads() {
        ConflictResolution resolution = resolveWithTypes(
            ConflictFixtures.sample(ConflictType.OVERLOAD_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "distinct parameter lists are overloads, not a conflict: "
                + resolution.getExplanation());
        assertEquals(ConflictResolution.ResolutionStrategy.KEEP_BOTH,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("process(String id)"),
            "branch 1's overload must survive");
        assertTrue(resolution.getResolvedCode().contains("process(String id, boolean force)"),
            "branch 2's overload must survive");
    }
```

## Example: the defect that motivated type-aware comparison

Both branches add the *same* overload, spelled differently — `List<String>`
versus `java.util.List<java.lang.String>`. A text comparison calls them
distinct and keeps both, which does not compile. Resolved types make them one
signature, so the resolver escalates:

[../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java#region:resolves-equivalent-parameter-spellings)
```java
    @Test
    @DisplayName("resolves the same parameter written differently")
    void resolvesEquivalentParameterSpellings() {
        // The defect that motivated type-aware comparison: these two branches add
        // the same overload, spelled differently, so it is a collision - and the
        // text comparison called them distinct.
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "same parameter, different spelling",
            "void process() { }",
            "import java.util.List;\nvoid process() { }\nvoid process(List<String> id) { }",
            "void process() { }\n"
                + "void process(java.util.List<java.lang.String> id) { }");

        ConflictResolution resolution = resolveWithTypes(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "resolved types make these one signature, so keeping both would not compile: "
                + resolution.getExplanation());
    }
```

The converse holds just as firmly — same raw name, genuinely different resolved
types, kept automatically:

[../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java#region:keeps-overloads-with-different-resolved-types)
```java
    @Test
    @DisplayName("keeps overloads whose resolved parameter types genuinely differ")
    void keepsOverloadsWithDifferentResolvedTypes() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "different generic arguments",
            "void process() { }",
            "import java.util.List;\nvoid process() { }\nvoid process(List<String> id) { }",
            "import java.util.List;\nvoid process() { }\nvoid process(List<Integer> id) { }");

        ConflictResolution resolution = resolveWithTypes(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "List<String> and List<Integer> are different parameter types: "
                + resolution.getExplanation());
    }
```

## Example: literally identical signatures

[../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java#region:escalates-on-identical-signatures)
```java
    @Test
    @DisplayName("escalates when both branches add the same parameter list")
    void escalatesOnIdenticalSignatures() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "same parameter list",
            "void process() { }",
            "void process() { }\nvoid process(String id) { }",
            "void process() { }\nvoid process(String id) { }");

        ConflictResolution resolution = resolveWithTypes(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "identical signatures cannot both exist: " + resolution.getExplanation());
        assertTrue(resolution.getExplanation().contains("same"),
            "the explanation must say why: " + resolution.getExplanation());
    }
```

## Example: no type context, no answer

The same canonical sample, resolved *without* a type context: the resolver
refuses rather than compares text, nothing may be applied, and the reviewer is
told how to fix the setup:

[../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java#region:escalates-without-type-context)
```java
    @Test
    @DisplayName("escalates to manual when no type context is supplied")
    void escalatesWithoutTypeContext() {
        // The comparison cannot be made, so the conflict must not be guessed at.
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.OVERLOAD_ADD));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "without resolved types the comparison cannot be trusted: "
                + resolution.getExplanation());
        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode(),
            "nothing may be applied");
        assertTrue(resolution.getAlternativePaths().stream()
                .anyMatch(path -> path.getJustification().contains("type context")),
            "the reviewer must be told how to fix it: " + resolution.getAlternativePaths());
    }
```

## When it declines

Two branches adding *differently named* methods is not an overload conflict —
there is no shared name to arbitrate:

[../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/OverloadAddConflictResolverTest.java#region:declines-for-different-method-names)
```java
    @Test
    @DisplayName("declines when it is not the same method name")
    void declinesForDifferentMethodNames() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "different methods",
            "void process() { }",
            "void process() { }\nvoid charge(String id) { }",
            "void process() { }\nvoid refund(String id) { }");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "two different method names are not an overload conflict");
    }
```

## What it emits

- **Kind/strategy**: `AUTO` + `KEEP_BOTH` for genuinely distinct overloads;
  `REVIEW` + `MERGE_SAFE` when the resolved parameter types collide (branch 1's
  text kept provisionally); `MANUAL` with the manual marker when the comparison
  cannot be made (no type context, a parse failure, or several contested
  methods); the MANUAL fallback when it declines.
- **Resolved code**: for the AUTO case, both sides' text — the shared base
  declaration appears once per side; the decision the resolution carries is
  "both overloads are kept", and the explanation names both added parameter
  sets.
- **Fix paths**: "Keep both overloads" is recommended for the additive case;
  the collision path offers merging bodies, keeping one, or delegating one to
  the other; the no-context path recommends building the resolver with a
  covering classpath.

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
