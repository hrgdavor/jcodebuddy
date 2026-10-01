# TypeChangeConflictResolver

Resolves `TYPE_CHANGE` conflicts — the same declaration carries a different
type on each branch. When one type is a *widening* of the other, adopting the
wider declaration cannot invalidate an existing reader, so the answer is
mechanical; unrelated types depend on call sites the resolver cannot see and go
to a reviewer. Declared handling: **REVIEW** — the resolver answers `AUTO` only
when the widening relationship is provable. Not sticky.

Widening is decided by **resolution**, not by a list. For reference types the
resolver asks javac whether a value of the narrower declaration's type is
assignable to the wider one, so `TreeSet → NavigableSet` is recognised and so is
any type in the project under merge. Only the primitive conversions keep a table,
because they are the language's own rule (JLS 5.1.2) and no class hierarchy
expresses them. This resolver therefore declares `requiresTypeContext()`: a run
without a context is refused at construction rather than resolved by a weaker rule.

[`TypeChangeConflictResolver`](../../../src/main/java/com/codebuddy/merge/TypeChangeConflictResolver.java) ·
[`TypeChangeConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java)

## What it decides

1. Parse the first typed declaration of each side (`Type name =` / `Type
   name;`), keeping type and name. Types are canonicalised for comparison:
   generic arguments are dropped and varargs are treated as the equivalent
   array.
2. If either side has no typed declaration, or both declare the same type,
   decline — an agreed change is not a conflict.
3. Decide the widening relationship in both directions. Which rule applies is a
   property of the types:
   - a pair involving a **primitive** is decided by the JLS 5.1.2 conversion
     lattice (`byte → short → int → long → float → double`, `char → int → …`),
     which is the only table left in the resolver;
   - a pair of **reference** types is decided by resolution: both declarations
     are attributed against the type context, and the question is whether the
     narrower type is assignable to the wider one.
4. Exactly one direction widens → `AUTO`, strategy `PREFER_BRANCH1` or
   `PREFER_BRANCH2`, resolved code is the *wider* side's declaration; the
   explanation names both types and why the wider one is safe.
5. Neither direction widens (unrelated types) → `REVIEW` + `MERGE_SAFE` with
   branch 1's text kept provisionally: the safe choice depends on the call
   sites.

Widening is directional, and "could not be resolved" is never read as "not
assignable": a declaration the parser cannot attribute, or a conflict with no type
context, escalates exactly as an unrelated pair does. An answered "no" and no
answer have the same consequence for the reviewer and different reasons in the
diagnostic.

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
        Conflict conflict = withTypeContext(ConflictFixtures.sample(ConflictType.TYPE_CHANGE));
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
        Conflict conflict = withTypeContext(new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "branch 2 widens", "int count = 0;", "int count = 0;", "long count = 0;"));

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("long count"));
    }
```

## Example: reference-type widening

The resolver asks javac, so widening a declaration from `List` to `Collection` is
adopted automatically — and so is a supertype the deleted table never listed:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:adopts-collection-supertype)
```java
    @Test
    @DisplayName("adopts a collection supertype when one branch widens the declaration")
    void adoptsCollectionSupertype() {
        Conflict conflict = withTypeContext(new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "widened to a supertype",
            "List<String> names = new ArrayList<>();",
            "List<String> names = new ArrayList<>();",
            "Collection<String> names = new ArrayList<>();"));

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "Collection is a supertype of List, so adopting it is safe");
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("Collection"),
            "the supertype declaration must be adopted: " + resolution.getResolvedCode());
    }
```

Generic arguments are part of the resolved type, so they now decide as well as the
raw names do: `List<String>` is assignable to `Collection<String>` and *not* to
`Collection<Integer>`. The canonical token the primitive lattice compares still
drops them, and varargs/array spellings still canonicalise to the same type:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:ignores-generic-arguments)
```java
    @Test
    @DisplayName("keeps generic arguments rather than dropping them, and still classifies")
    void ignoresGenericArguments() {
        assertEquals("List", TypeChangeConflictResolver.canonical("List<String>"));
        assertEquals("Map", TypeChangeConflictResolver.canonical("Map<String, List<String>>"),
            "the canonical token is still the raw name, which is what the lattice compares");

        // Resolution, by contrast, sees the arguments: `List<String>` is assignable to
        // `Collection<String>` but not to `Collection<Integer>`. Dropping them - as the
        // canonical token does, and as the old table had to - would have called the
        // second pair a widening too.
        assertTrue(resolvesAsWidening("java.util.Collection<String>", "java.util.List<String>"));
        assertTrue(resolvesAsWidening("java.util.Collection<String>", "java.util.ArrayList<String>"));
        assertFalse(resolvesAsWidening("java.util.Collection<Integer>", "java.util.List<String>"),
            "an unrelated type argument is not a widening, whatever the raw names say");
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
        Conflict conflict = withTypeContext(new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "unrelated types", "int count = 0;", "int count = 0;", "String count = \"0\";"));

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

And a run with no type context declines rather than falling back to a weaker rule.
The orchestrator refuses to build such a resolver set and names this resolver, so
this path is what a *direct* caller gets:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#region:escalates-without-type-context)
```java
    @Test
    @DisplayName("escalates with a reason when no type context was supplied")
    void escalatesWithoutTypeContext() {
        // requiresTypeContext() is true, so the orchestrator refuses to build a set
        // without a context and names this resolver. A direct caller gets a manual
        // resolution carrying the reason instead of a weaker answer - which is the
        // same shape OverloadAddConflictResolver uses for the same situation.
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "no context", "int count = 0;", "int count = 0;", "long count = 0;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertTrue(resolution.getExplanation().contains("no type context"),
            "the reason must say what was missing: " + resolution.getExplanation());
        assertTrue(resolver.requiresTypeContext(),
            "and the resolver must declare the requirement, not just handle its absence");
    }
```

## What changed when the table was deleted

The resolver carried a hand-written list of JDK widening chains. Replacing it with
resolution was not only a simplification — the list was wrong in one place and
incomplete in another, and both are worth knowing about:

- **Boxed siblings were called a widening.** The table read the primitive lattice
  across the wrapper classes (`Byte, Short, Integer, Long, Float, Double` in one
  ascending list), so `widens("Long", "Integer")` was *true*. javac rejects
  `Long x = anInteger` — they are siblings under `Number` — so adopting the `Long`
  declaration would have broken every reader that assigned the result to an
  `Integer`. That pair now escalates.
- **An absent chain was work handed back.** `TreeSet` was listed with `AbstractSet`,
  `Set`, `Collection`, `Iterable`, so `NavigableSet` and `SortedSet` — the interfaces
  it actually implements — were not in the table, and a `TreeSet`/`NavigableSet` pair
  went to a reviewer for no reason. Resolution answers it, and answers it for every
  type in the project under merge, not just the ones somebody remembered to list.

What did *not* change: primitives. They have no supertypes to resolve, their
conversions are fixed by JLS 5.1.2, and the lattice stays.

## What it emits

- **Kind/strategy**: `AUTO` + `PREFER_BRANCH1`/`PREFER_BRANCH2` for a provable
  widening; `REVIEW` + `MERGE_SAFE` for unrelated types (branch 1's text kept
  provisionally); `MANUAL` with the reason when there is no type context, no typed
  declaration, or both branches agree.
- **Resolved code**: the wider side's declaration text for the AUTO case.
- **Fix paths**: the primary path offers both branches' types and the base
  type ("Use 'long'", "Use 'double'", "Keep 'int'"), noting that widening is
  usually safe and narrowing usually is not; a second path offers adopting the
  wider type automatically.

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
