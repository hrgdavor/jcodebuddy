# MemberAddConflictResolver

Handles `MEMBER_ADD` conflicts — both branches added a **distinct** member in the same place, so the
two additions coexist and the union of the two sides is the answer.

Registered in
[`ConflictResolvers.defaultResolvers()`](../../../src/main/java/com/codebuddy/merge/ConflictResolvers.java);
declared handling `AUTO`, and its declared maximum evidence is `STRUCTURE` (see the
[resolver reference](../README.md) for what that means and why it matters).

## The canonical sample

Two branches each append a method next to the one the base already declared — `charge()` on one side,
`refund()` on the other. Git reports the block as a conflict because the insertions are adjacent, and a
line-based comparison sees "both sides replaced this region", which is true and useless:

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#member-add-sample)
```java
    static final String MEMBER_ADD_BASE = "void audit() { log.write(); }";
    static final String MEMBER_ADD_BRANCH1 = "void audit() { log.write(); }\nvoid charge() { ledger.debit(); }";
    static final String MEMBER_ADD_BRANCH2 = "void audit() { log.write(); }\nvoid refund() { ledger.credit(); }";
```

## What it decides

The two additions do not interact: nothing collides, and each call site binds to the member it names.
So the answer is `KEEP_BOTH`, at `AnalysisLevel.STRUCTURE` — a claim about declared members, which is why
a text-level claim has no business outranking it.

[../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java#keeps-both-distinct-members)
```java
    @Test
    @DisplayName("keeps both additions, and the shared member exactly once")
    void keepsBothDistinctMembers() {
        ConflictResolution resolution = resolver.resolve(
            ConflictFixtures.sample(ConflictType.MEMBER_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.KEEP_BOTH,
            resolution.getResolutionStrategy());
        assertEquals(AnalysisLevel.STRUCTURE, resolution.getAnalysisLevel(),
            "two additions colliding is a question about declared members, so the answer rests on "
                + "structure rather than on text");

        String merged = resolution.getResolvedCode();
        assertTrue(merged.contains("void charge()"), "branch 1's addition survives: " + merged);
        assertTrue(merged.contains("void refund()"), "branch 2's addition survives: " + merged);
        assertTrue(SideUnion.keeps(merged, resolution.getBranch1Code()), merged);
        assertTrue(SideUnion.keeps(merged, resolution.getBranch2Code()), merged);

        // The member both sides carried appears once, because both sides carried it: appending the
        // sides would declare it twice and the merged class would not compile.
        assertEquals(1, occurrences(merged, "void audit()"),
            "the shared member must not be repeated: " + merged);
        assertEquals(3, DeclarationScanner.declarationsIn(merged).size(),
            "audit(), charge() and refund(), each once: " + merged);
    }
```

**Keeping both sides is not concatenation,** and this sample is why: both sides carry `audit()`, the
member the base declared, so appending them declares it twice and the merged class does not compile. The
union takes the longest shared prefix once and appends only the two remainders, then verifies that no
member appears twice. The first version of this resolver concatenated, and the measured result was a
class with `method audit() is already defined` — which is how the same defect was found in
`OverloadAddConflictResolver`.

## What it refuses

A shared signature means only one member can exist — that is not additive, so it is declined and the
block becomes a human's decision rather than a guess:

[../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java#refuses-a-shared-signature)
```java
    @Test
    @DisplayName("refuses two additions with the same signature - only one member can exist")
    void refusesASharedSignature() {
        // Same name and parameter list, different return type: the signatures collide, so this is the
        // overload resolver's question (or a human's), not an additive union.
        Conflict collision = new Conflict(ConflictType.MEMBER_ADD, "C.java", "collision",
            "void audit() { }",
            "void audit() { }\nvoid charge() { }",
            "void audit() { }\nint charge() { return 0; }");

        ConflictResolution resolution = resolver.resolve(collision);

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "a collision is refused rather than guessed: " + resolution.getExplanation());
        assertTrue(resolution.requiresHumanDecision());
    }
```

A union that cannot be shown sound is refused too. If the two sides share anything *past* the common
prefix, both remainders contain it and it would be repeated — and rather than reason about which repeated
lines happen to be harmless, the construction is declined:

[../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java#union-needs-a-shared-prefix)
```java
    @Test
    @DisplayName("the union is refused when the sides share more than a prefix")
    void unionIsRefusedWhenTheSidesShareMoreThanAPrefix() {
        // The shared member sits after both additions, so it is in both remainders and the union would
        // declare it twice. Refusing is the safe answer: the block becomes a human's decision instead
        // of code that does not compile.
        assertNull(SideUnion.of(
            "void charge() { }\nvoid shared() { }",
            "void refund() { }\nvoid shared() { }"));

        // And the shape this resolver exists for is accepted, with the shared member taken once.
        String union = SideUnion.of(
            "void audit() { }\nvoid charge() { }",
            "void audit() { }\nvoid refund() { }");
        assertTrue(union != null && union.contains("void charge()") && union.contains("void refund()"),
            union);
        assertEquals(1, occurrences(union, "void audit()"), union);
    }
```

## Detection: the base must be known, and empty is not the same as absent

`ConflictDetectionService.detectMemberAddConflicts` decides whether the shape is present, and the base is what
makes that decidable. The danger is telling an addition from a deletion: keeping both sides resurrects a member
one branch deliberately removed, the same outcome `ImportConflictResolver` refuses to produce. Two kinds of "no
base" mean different things, and only the marker parser knows which is which:

- a `diff3`/`zdiff3` hunk whose base section is present but **empty** — the base had no lines in that region,
  so both branches inserted there. This is the shape a real merge produces for two adjacent additions, and it is
  recognised;
- a merge-style file with **no base section at all** — the base is unknown, so "both branches added it" cannot
  be told from "one branch added it and the other deleted it". This is declined, and the block stays a human's.

A member removed on either side is structural and stays a human's. So does a pair of additions whose method
**names** match: whether `process(List<String>)` and `process(java.util.List<java.lang.String>)` are one member or
two is a question about resolved parameter types, which is `OverloadAddConflictResolver`'s level rather than this
one's — and that resolver answers it, refusing to keep two members once they resolve to the same signature.

A member is a method (by name and parameter list) or a field (by name), and only at the declaration level. Two
guards keep a statement from being read as a member, because that mistake would be expensive — a local variable
and a field are the same text, and "keeping both" two of them would concatenate two competing method bodies and
call it a member addition:

1. **where the fragment declares a method or a type**, that declaration establishes the member level, and only
   declarations at that depth are fields — a local sits deeper, so it is not one;
2. **where it does not** — a bare insertion, which is exactly the empty-base case above — the *modifier* is the
   evidence: a local variable cannot be declared `private`, so a field spelling out `public`, `protected` or
   `private` is a member and nothing else can be concluded. A package-private field in a bare insertion is
   declined rather than guessed, and a `static final` constant is `CONSTANT_ADD`'s business in any case.

The canonical detection test, including the removal and identical-addition refusals:

[../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/MemberAddConflictResolverTest.java#detector-requires-a-known-base)
```java
    @Test
    @DisplayName("detection requires a base side, so an addition is never confused with a deletion")
    void detectionRequiresAKnownBase() {
        ConflictDetectionService detector = new ConflictDetectionService();
        String branch1 = "void audit() { }\nvoid charge() { }";
        String branch2 = "void audit() { }\nvoid refund() { }";

        assertTrue(detector.detectMemberAddConflicts("C.java", "", branch1, branch2).isEmpty(),
            "without a base, 'both branches added it' cannot be told from 'one branch added it and "
                + "the other deleted it'");

        assertEquals(1, detector.detectMemberAddConflicts("C.java", "void audit() { }",
            branch1, branch2).size(),
            "with one, the two additions are recognised");

        assertEquals(0, detector.detectMemberAddConflicts("C.java", "void audit() { }\nvoid legacy() { }",
            "void audit() { }\nvoid charge() { }",
            "void audit() { }\nvoid refund() { }").size(),
            "a member removed on one side is structural, and stays a human's");

        assertFalse(detector.detectMemberAddConflicts("C.java", "void audit() { }",
            branch1, branch1).size() > 0,
            "identical additions are not two additions");
    }
```

## Where it stops

- **Parameter types are not resolved here.** Additions whose method names match are declined so that
  `OverloadAddConflictResolver` can answer them from resolved parameter types. This resolver decides from names,
  which is why its level is `STRUCTURE` when it answers that way — and `PLATFORM_TYPES` or `PROJECT_TYPES` when a
  supplied context let it compare resolved signatures, which is what it records then.
- **A package-private field in a bare insertion is declined**, as described above, and becomes a human's
  decision.
- **A bare insertion whose lines carry no access modifier has no evidence at all**, so nothing is claimed from
  it; the block keeps its markers and its fixture.