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
expresses them.

It **degrades** rather than demanding a classpath, so it declares
`requiresTypeContext() == false`: without a context it still decides the primitive
conversions and the common JDK hierarchies from a built-in table, and every
resolution it reaches that way carries a **warning** saying the declared types were
not checked against compiled types. A resolver that cannot degrade at all — overload
comparison, which has no weaker answer worth giving — declares `true` instead, and
whoever builds a resolver set must then supply a context or **remove that resolver by
hand**, because the set refuses to build without one.

[`TypeChangeConflictResolver`](../../../src/main/java/com/codebuddy/merge/TypeChangeConflictResolver.java) ·
[`TypeChangeConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java)

## What it decides

1. Parse the first typed declaration of each side (`Type name =` / `Type
   name;`), keeping type and name. Types are canonicalised for comparison:
   generic arguments are dropped and varargs are treated as the equivalent
   array.
2. If either side has no typed declaration, or both declare the same type,
   decline — an agreed change is not a conflict.
3. Decide the widening relationship in both directions, by the strongest rule that
   can apply:
   - a pair involving a **primitive** is decided by the JLS 5.1.2 conversion
     lattice (`byte → short → int → long → float → double`, `char → int → …`).
     No classpath can change that answer, so it is the same in both modes;
   - a pair of **reference** types with a type context is decided by resolution:
     both declarations are attributed, and the question is whether the narrower
     type is assignable to the wider one;
   - a pair of reference types **without** a context is decided by a built-in
     best-effort table of common JDK hierarchies (`ArrayList → List → Collection →
     Iterable`, `HashMap → Map`, the wrapper types into `Number`/`Comparable`/
     `Object`) — and the resolution carries a warning that it was reached this way.
     A type that cannot be resolved *although* a context was supplied does **not**
     fall back to that table: matching simple names while a classpath is available
     would be the name-based guess resolution exists to replace, so it escalates with
     its own warning instead.
4. Exactly one direction widens → `AUTO`, strategy `PREFER_BRANCH1` or
   `PREFER_BRANCH2`, resolved code is the *wider* side's declaration; the
   explanation names both types and why the wider one is safe.
5. Neither direction widens (unrelated types) → `REVIEW` + `MERGE_SAFE` with
   branch 1's text kept provisionally: the safe choice depends on the call
   sites.

Widening is directional, and "could not be resolved" is never read as "not
assignable": an unresolvable declaration escalates exactly as an unrelated pair does.
Both reach the reviewer as an escalation, and the **warning** is what tells them
apart — one says the comparison ran without a type context, the other that a declared
type was not on the supplied classpath.

## The canonical sample

Base `int count`, ours `long count`, theirs `double count`. Both sides widen
the base, but `double` is wider than `long`, so branch 2 supplies the
declaration that is adopted:

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#type-change-sample)
```java
    static final String TYPE_CHANGE_BASE = "int count = 0;";
    static final String TYPE_CHANGE_BRANCH1 = "long count = 0;";
    static final String TYPE_CHANGE_BRANCH2 = "double count = 0;";
```

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#adopts-wider-type)
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

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#adopts-one-sided-widening)
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

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#adopts-collection-supertype)
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

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#ignores-generic-arguments)
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

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#widening-is-directional)
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

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#escalates-unrelated-types)
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

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#declines-when-types-agree)
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

## When it degrades

A run with **no type context** is not refused: the resolver decides what needs no
classpath — the primitive conversions and the built-in JDK table — and says so on
every resolution it produces that way:

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#degrades-without-a-context)
```java
    @Test
    @DisplayName("degrades without a type context: decides the common cases and warns")
    void degradesWithoutAContext() {
        // A classpath is not a hard requirement for this resolver. The orchestrator
        // therefore builds a set containing it with no context at all, and the resolver
        // answers from what needs no classpath: the language's primitive rule and its own
        // best-effort table.
        assertFalse(resolver.requiresTypeContext(),
            "it degrades, so it must not make the context mandatory for the whole set");

        ConflictResolution primitive = resolver.resolve(new Conflict(ConflictType.TYPE_CHANGE,
            ConflictFixtures.FILE, "primitive widening, no context",
            "int count = 0;", "int count = 0;", "long count = 0;"));
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            primitive.getResolutionStrategy(),
            "the JLS conversion lattice needs no classpath");
        assertTrue(primitive.getWarnings().contains(TypeChangeConflictResolver.DEGRADED_WARNING),
            "and even that answer says it was reached without a context: " + primitive.getWarnings());

        ConflictResolution collection = resolver.resolve(new Conflict(ConflictType.TYPE_CHANGE,
            ConflictFixtures.FILE, "collection widening, no context",
            "HashMap<String, String> index = new HashMap<>();",
            "HashMap<String, String> index = new HashMap<>();",
            "Map<String, String> index = new HashMap<>();"));
        assertEquals(ConflictResolution.ResolutionKind.AUTO, collection.getKind(),
            "Map is a supertype of HashMap, which the built-in table knows");
        assertTrue(collection.getWarnings().contains(TypeChangeConflictResolver.DEGRADED_WARNING),
            "the basis is weaker, so the resolution says so: " + collection.getWarnings());
    }
```

What the table does not carry still escalates, and the warning is the difference
between "no widening relationship" and "no widening relationship this resolver can
check":

[../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/TypeChangeConflictResolverTest.java#degraded-escalation)
```java
    @Test
    @DisplayName("escalates what the built-in table does not carry, and says why")
    void degradesToReviewForWhatTheTableDoesNotCarry() {
        ConflictResolution resolution = resolver.resolve(new Conflict(ConflictType.TYPE_CHANGE,
            ConflictFixtures.FILE, "project types, no context",
            "com.example.Foo value = null;", "com.example.Foo value = null;",
            "com.example.Bar value = null;"));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "a partial table can only add decisions, never remove safety");
        assertTrue(resolution.getWarnings().contains(TypeChangeConflictResolver.DEGRADED_WARNING),
            resolution.getWarnings().toString());
    }

    @Test
    @DisplayName("warns when a type cannot be resolved even though a context was supplied")
    void warnsWhenATypeCannotBeResolved() {
        // The project-type case with a context: the declarations resolve to Unknown, so
        // the resolver must not fall back to matching their simple names - that would be
        // the name-based guess resolution exists to replace - and must tell the reviewer
        // that this is why a human is being asked.
        ConflictResolution resolution = resolver.resolve(withTypeContext(new Conflict(
            ConflictType.TYPE_CHANGE, ConflictFixtures.FILE, "off classpath",
            "com.example.Foo value = null;", "com.example.Foo value = null;",
            "com.example.Bar value = null;")));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind());
        assertTrue(resolution.getWarnings().contains(TypeChangeConflictResolver.UNRESOLVED_WARNING),
            "the reviewer must see that the classpath, not the types, is the problem: "
                + resolution.getWarnings());
    }

    @Test
    @DisplayName("does not warn when the decision came from resolved types")
    void resolvedDecisionsCarryNoWarnings() {
        ConflictResolution resolution = resolver.resolve(
            withTypeContext(ConflictFixtures.sample(ConflictType.TYPE_CHANGE)));

        assertFalse(resolution.hasWarnings(),
            "a decision javac made needs no caveat: " + resolution.getWarnings());
    }

    @Test
    @DisplayName("keeps boxed siblings apart in the degraded table too")
    void degradedTableKeepsBoxedSiblingsApart() {
        assertFalse(TypeChangeConflictResolver.widensFromBuiltInTable("Long", "Integer"),
            "the wrapper classes are not in each other's chains, in either mode");
        assertTrue(TypeChangeConflictResolver.widensFromBuiltInTable("Number", "Integer"));
        assertTrue(TypeChangeConflictResolver.widensFromBuiltInTable("Comparable", "Integer"));
        assertFalse(TypeChangeConflictResolver.widensFromBuiltInTable("Comparable", "Number"),
            "Number is not a Comparable: the two relations need separate chains");
        assertTrue(TypeChangeConflictResolver.widensFromBuiltInTable("NavigableSet", "TreeSet"),
            "and the fallback still knows a real supertype the removed table never listed");
    }
```

## What changed when the table was demoted

The resolver carried a hand-written list of JDK widening chains as its **primary**
rule. Resolution replaced it there, and what is left is a fallback for the degraded
mode — which is why the old list's two defects are worth knowing about:

- **Boxed siblings were called a widening.** The table read the primitive lattice
  across the wrapper classes (`Byte, Short, Integer, Long, Float, Double` in one
  ascending list), so `widens("Long", "Integer")` was *true*. javac rejects
  `Long x = anInteger` — they are siblings under `Number` — so adopting the `Long`
  declaration would have broken every reader that assigned the result to an
  `Integer`. That pair escalates now, in both modes.
- **An absent chain was work handed back.** `TreeSet` was listed with `AbstractSet`,
  `Set`, `Collection`, `Iterable`, so `NavigableSet` and `SortedSet` — the interfaces
  it actually implements — were not in the table, and a `TreeSet`/`NavigableSet` pair
  went to a reviewer for no reason. Resolution answers it, and answers it for every
  type in the project under merge, not just the ones somebody remembered to list.

The fallback table is written to the invariant the old one broke: **every chain
contains only true relations**, and where a type has two unrelated supertypes they get
two chains — `Integer → Number → Object` and `Integer → Comparable → Object`, because
`Number` is not a `Comparable`. A partial table can only add decisions; a wrong chain
removes safety, which is what the sibling entries did.

What did *not* change: primitives. They have no supertypes to resolve, their
conversions are fixed by JLS 5.1.2, and the lattice stays.

## What it emits

- **Kind/strategy**: `AUTO` + `PREFER_BRANCH1`/`PREFER_BRANCH2` for a provable
  widening; `REVIEW` + `MERGE_SAFE` for unrelated types (branch 1's text kept
  provisionally); `MANUAL` when there is no typed declaration or both branches agree.
- **Warnings** (new): `DEGRADED_WARNING` on any resolution reached without a type
  context, `UNRESOLVED_WARNING` when a declared type could not be resolved against the
  supplied classpath. Empty for a decision javac made. Written to the report JSON, so a
  renderer can show them beside the explanation.
- **Resolved code**: the wider side's declaration text for the AUTO case.
- **Fix paths**: the primary path offers both branches' types and the base
  type ("Use 'long'", "Use 'double'", "Keep 'int'"), noting that widening is
  usually safe and narrowing usually is not; a second path offers adopting the
  wider type automatically.

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
