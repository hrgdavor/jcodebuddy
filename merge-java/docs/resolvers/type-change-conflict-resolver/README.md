# TypeChangeConflictResolver

Resolves `TYPE_CHANGE` conflicts — the same declaration carries a different
type on each branch. When one type is a *widening* of the other, adopting the
wider declaration cannot invalidate an existing reader, so the answer is
mechanical; unrelated types depend on call sites the resolver cannot see and go
to a reviewer. Declared handling: **REVIEW** — the resolver answers `AUTO` only
when the widening relationship is provable. Not sticky.

[`TypeChangeConflictResolver`](../../../src/main/java/com/codebuddy/merge/TypeChangeConflictResolver.java) ·
[`TypeChangeConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java)

## What it decides

1. Parse the first typed declaration of each side (`Type name =` / `Type
   name;`), keeping type and name. Types are canonicalised for comparison:
   generic arguments are dropped and varargs are treated as the equivalent
   array.
2. If either side has no typed declaration, or both declare the same type,
   decline — an agreed change is not a conflict.
3. Test the widening relationship in both directions against the built-in
   widening chains: the primitive chains (`byte → short → int → long → float →
   double`, `char → int → …`), the boxed chains into `Number`, `Comparable`
   and `Object`, and the common JDK supertype chains (`ArrayList → List →
   Collection → Iterable → Object`, `String → CharSequence → …`, and so on).
4. Exactly one direction widens → `AUTO`, strategy `PREFER_BRANCH1` or
   `PREFER_BRANCH2`, resolved code is the *wider* side's declaration; the
   explanation names both types and why the wider one is safe.
5. Neither direction widens (unrelated types) → `REVIEW` + `MERGE_SAFE` with
   branch 1's text kept provisionally: the safe choice depends on the call
   sites.

Widening is directional and only real supertype relationships count — a chain
establishes an order, and the classifier scans every chain that mentions both
types.

## The canonical sample

Base `int count`, ours `long count`, theirs `double count`. Both sides widen
the base, but `double` is wider than `long`, so branch 2 supplies the
declaration that is adopted:

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:type-change-sample)
```java
    static final String TYPE_CHANGE_BASE = "int count = 0;";
    static final String TYPE_CHANGE_BRANCH1 = "long count = 0;";
    static final String TYPE_CHANGE_BRANCH2 = "double count = 0;";
```

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:adopts-wider-type)
```java
    @Test
    @DisplayName("adopts the wider type declared on branch 2")
    void adoptsWiderType() {
        // Fixture: base int, branch 1 long, branch 2 double. double is the widest,
        // so branch 2 supplies the declaration that is adopted.
        Conflict conflict = ConflictFixtures.sample(ConflictType.TYPE_CHANGE);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy(),
            "double is wider than long, so branch 2's declaration wins");
        assertTrue(resolution.getResolvedCode().contains("double count"),
            "the widened declaration must be adopted: " + resolution.getResolvedCode());
        assertTrue(resolution.getExplanation().contains("double"),
            "the explanation must name the adopted type: " + resolution.getExplanation());
    }
```

The direction is whatever the evidence says — when branch 2 holds the wider
type, branch 2 wins:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:adopts-one-sided-widening)
```java
    @Test
    @DisplayName("adopts the wider type when branch 2 is the wider one")
    void adoptsWiderTypeFromBranch2() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "branch 2 widens", "int count = 0;", "int count = 0;", "long count = 0;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("long count"));
    }
```

## Example: reference-type widening

The chains cover JDK supertypes, so widening a declaration from `List` to
`Collection` is adopted automatically:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:adopts-collection-supertype)
```java
    @Test
    @DisplayName("adopts a collection supertype when one branch widens the declaration")
    void adoptsCollectionSupertype() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "widened to a supertype",
            "List<String> names = new ArrayList<>();",
            "List<String> names = new ArrayList<>();",
            "Collection<String> names = new ArrayList<>();");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "Collection is a supertype of List, so adopting it is safe");
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("Collection"),
            "the supertype declaration must be adopted: " + resolution.getResolvedCode());
    }
```

Generic arguments never disturb the classification, and varargs/array
spellings canonicalise to the same type:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:ignores-generic-arguments)
```java
    @Test
    @DisplayName("ignores generic arguments when classifying widening")
    void ignoresGenericArguments() {
        assertEquals("List", TypeChangeConflictResolver.canonical("List<String>"));
        assertEquals("Map", TypeChangeConflictResolver.canonical("Map<String, List<String>>"));
        assertTrue(TypeChangeConflictResolver.widens("Collection<String>", "List<String>"));
        assertTrue(TypeChangeConflictResolver.widens("Collection", "List<String>"));
    }
```

And widening is never assumed in both directions:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:widening-is-directional)
```java
    @Test
    @DisplayName("widening is directional")
    void wideningIsDirectional() {
        assertTrue(TypeChangeConflictResolver.widens("long", "int"));
        assertFalse(TypeChangeConflictResolver.widens("int", "long"));
        assertTrue(TypeChangeConflictResolver.widens("double", "long"));
        assertFalse(TypeChangeConflictResolver.widens("long", "double"));
    }
```

## Example: unrelated types escalate

`int` and `String` have no widening relationship — which one is safe depends on
every call site, so a reviewer decides:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:escalates-unrelated-types)
```java
    @Test
    @DisplayName("escalates unrelated types to a reviewer")
    void escalatesUnrelatedTypes() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "unrelated types", "int count = 0;", "int count = 0;", "String count = \"0\";");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "int and String have no widening relationship");
        assertFalse(resolution.getAlternativePaths().isEmpty());
    }
```

## When it declines

Both branches agreeing on the new type is an agreed change, not a conflict:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:declines-when-types-agree)
```java
    @Test
    @DisplayName("declines when both branches agree on the type")
    void declinesWhenTypesAgree() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "same type", "int count = 0;", "long count = 0;", "long count = 0;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "an agreed type change is not this resolver's conflict");
    }
```

## What it emits

- **Kind/strategy**: `AUTO` + `PREFER_BRANCH1`/`PREFER_BRANCH2` for a provable
  widening; `REVIEW` + `MERGE_SAFE` for unrelated types (branch 1's text kept
  provisionally); the MANUAL fallback when it declines.
- **Resolved code**: the wider side's declaration text for the AUTO case.
- **Fix paths**: the primary path offers both branches' types and the base
  type ("Use 'long'", "Use 'double'", "Keep 'int'"), noting that widening is
  usually safe and narrowing usually is not; a second path offers adopting the
  wider type automatically.

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
