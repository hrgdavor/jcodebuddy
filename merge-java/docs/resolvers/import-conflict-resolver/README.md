# ImportConflictResolver

Resolves `IMPORT_ADD` conflicts — the case ordinary merge tools handle worst.
Two branches each add an import in the same neighbourhood, and a text merge
reports a clash even though the correct answer is simply both imports. Declared
handling: **AUTO**. Not sticky. Registered for exactly this type in
[`ConflictResolvers`](../../../src/main/java/com/codebuddy/merge/ConflictResolvers.java).

[`ImportConflictResolver`](../../../src/main/java/com/codebuddy/merge/ImportConflictResolver.java) ·
[`ImportConflictResolverTest`](../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java) ·
[`ThreeWayFixtureTest`](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java)

## What it decides

Adding a distinct import is commutative and additive, so the union is the right
answer for everything that does not collide. The interesting part is what
*does* collide — a removal. The resolver therefore never compares the two
branches to each other directly; it reads **each side as a change against the
base**:

1. Extract the imports of the base, of branch 1 (ours) and of branch 2 (theirs)
   as [`ImportChange`](../../../src/main/java/com/codebuddy/merge/ImportChange.java)
   values. An import reference keeps its `static` modifier, so
   `import static a.B.c;` and `import a.B;` are distinct symbols.
2. If neither side changed anything relative to the base, decline — this is not
   an import conflict.
3. If one side removed an import the other side did not remove, that removal is
   *disputed* (a branch that kept the file unchanged still takes a position: it
   kept the import). The configured
   [`ImportClashPolicy`](../../../src/main/java/com/codebuddy/merge/ImportClashPolicy.java)
   settles it: the default `ADDITION_WINS` keeps the import (an unused import is
   harmless; a dropped one can break a use on the other branch) and stays
   **AUTO**; `REMOVAL_WINS` honours the removal and escalates to **REVIEW**,
   because dropping an import can break compilation elsewhere.
4. Otherwise the changes compose: additions from both sides are unioned, a
   removal both sides agree on is honoured, and the surviving set is rendered
   as complete `import …;` declarations, deduplicated.
5. When there is no base to compare against, or a side cannot be read, it falls
   back to the plain union of whatever imports are present — still correct for
   the additive case, the only case a base-less comparison can establish.

The explanation always names the policy or the composed changes, so a reviewer
can see *why* the answer is what it is.

## Example: an addition and a removal of different imports

The whole-file three-way fixture
[`import-add-both`](../../../src/test/resources/fixtures/import-add-both)
(written per [`THREE_WAY_FIXTURES.md`](../../THREE_WAY_FIXTURES.md)): ours adds
`java.time.Instant`, theirs removes `java.util.Set`. Compared with each other
the import blocks differ, so a two-way diff sees a clash over the block; only
the base reveals two independent changes.

The base — the last-synced state both branches started from:

[../../../src/test/resources/fixtures/import-add-both/base/PaymentProcessor.java.txt](../../../src/test/resources/fixtures/import-add-both/base/PaymentProcessor.java.txt)
```java
package com.example.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base version. Both branches start from exactly this file.
 */
public class PaymentProcessor {

    public static final int MAX_RETRIES = 3;

    private final Map<String, List<BigDecimal>> ledger;

    public PaymentProcessor(Map<String, List<BigDecimal>> ledger) {
        this.ledger = ledger;
    }

    public void process(String account) {
        audit(account);
    }

    public void audit(String account) {
        // record the account
    }
}
```

Ours — adds one import, changes nothing else:

[../../../src/test/resources/fixtures/import-add-both/ours/PaymentProcessor.java.txt](../../../src/test/resources/fixtures/import-add-both/ours/PaymentProcessor.java.txt)
```java
package com.example.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ours: adds one import, changes nothing else.
 */
public class PaymentProcessor {

    public static final int MAX_RETRIES = 3;

    private final Map<String, List<BigDecimal>> ledger;

    public PaymentProcessor(Map<String, List<BigDecimal>> ledger) {
        this.ledger = ledger;
    }

    public void process(String account) {
        audit(account);
    }

    public void audit(String account) {
        // record the account
    }
}
```

Theirs — removes one import the base had:

[../../../src/test/resources/fixtures/import-add-both/theirs/PaymentProcessor.java.txt](../../../src/test/resources/fixtures/import-add-both/theirs/PaymentProcessor.java.txt)
```java
package com.example.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Theirs: removes one import the base had, adds nothing.
 *
 * The removal is the part a text comparison cannot see. Comparing ours against
 * theirs alone shows `java.util.Set` on one side and `java.time.Instant` on the
 * other, which reads as a genuine clash over the import block. Only the base
 * reveals that ours *added* and theirs *removed*: an addition and a removal of
 * different imports do not conflict at all.
 */
public class PaymentProcessor {

    public static final int MAX_RETRIES = 3;

    private final Map<String, List<BigDecimal>> ledger;

    public PaymentProcessor(Map<String, List<BigDecimal>> ledger) {
        this.ledger = ledger;
    }

    public void process(String account) {
        audit(account);
    }

    public void audit(String account) {
        // record the account
    }
}
```

The two human-readable diffs that ship with the fixture:

[../../../src/test/resources/fixtures/import-add-both/ours.diff](../../../src/test/resources/fixtures/import-add-both/ours.diff)
```diff
--- base/PaymentProcessor.java
+++ ours/PaymentProcessor.java
@@ -2,7 +2,8 @@
 
 import java.math.BigDecimal;
+import java.time.Instant;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;
 
```

[../../../src/test/resources/fixtures/import-add-both/theirs.diff](../../../src/test/resources/fixtures/import-add-both/theirs.diff)
```diff
--- base/PaymentProcessor.java
+++ theirs/PaymentProcessor.java
@@ -4,7 +4,6 @@
 import java.math.BigDecimal;
 import java.util.List;
 import java.util.Map;
-import java.util.Set;
 
 /**
  * Base version. Both branches start from exactly this file.
```

The diffs are documentation, never input — a test asserts the structurally
computed change agrees with them, so a fixture whose diff lies fails:

[../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java#region:computed-change-agrees-with-the-diff)
```java
    @Test
    @DisplayName("the computed change agrees with the diff a human would read")
    void computedChangeAgreesWithTheDiff() {
        Set<String> oursAddedInDiff = ThreeWayFixture.addedImportsInDiff(fixture.oursDiff());
        Set<String> theirsRemovedInDiff = ThreeWayFixture.removedImportsInDiff(fixture.theirsDiff());

        assertFalse(oursAddedInDiff.isEmpty(), "the fixture diff must state what ours added");
        assertFalse(theirsRemovedInDiff.isEmpty(), "the fixture diff must state what theirs removed");

        Set<String> oursAddedComputed = fixture.oursImportChange().orElseThrow()
            .added().stream().map(ref -> ref.rendered()).collect(Collectors.toSet());
        Set<String> theirsRemovedComputed = fixture.theirsImportChange().orElseThrow()
            .removed().stream().map(ref -> ref.rendered()).collect(Collectors.toSet());

        assertEquals(oursAddedInDiff, oursAddedComputed,
            "the structurally computed addition must match the documented one");
        assertEquals(theirsRemovedInDiff, theirsRemovedComputed,
            "and so must the documented removal");
    }
```

And the resolver composes the two changes into one import block instead of
reporting a conflict:

[../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java#region:composes-independent-changes)
```java
    @Test
    @DisplayName("composes the two independent changes instead of reporting a conflict")
    void composesIndependentChanges() {
        ConflictResolution resolution = resolver.resolve(fixture.conflict(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "an addition and a removal of different imports compose: "
                + resolution.getExplanation());

        String resolved = resolution.getResolvedCode();
        assertTrue(resolved.contains("import java.time.Instant;"),
            "ours' addition must survive: " + resolved);
        assertTrue(resolved.contains("import java.util.List;"),
            "the imports neither side touched must survive: " + resolved);
        assertTrue(resolved.contains("import java.util.Map;"), resolved);
        assertTrue(resolved.contains("import java.util.Set;"),
            "the default policy keeps it: theirs only dropped an unused import, and "
                + "dropping it could break a use on our branch: " + resolved);
        assertNotEquals(fixture.base(), resolved, "something must actually change");
    }
```

The outcome is `AUTO` with strategy `KEEP_BOTH`: ours' addition survives, the
imports nobody touched survive, and theirs' removal of `java.util.Set` is
*not* applied — under the default `ADDITION_WINS` policy an unused import is
harmless while a dropped one could break a use on our branch.

## Example: the hunk-level union

Resolver tests work at hunk level. The canonical sample every
`ImportConflictResolver` test starts from — base, branch 1 (ours), branch 2
(theirs), sides separated by `\n` — lives in
[`ConflictFixtures`](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java):

[../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#region:import-add-sample)
```java
    static final String IMPORT_ADD_BASE = "import java.util.List;";
    static final String IMPORT_ADD_BRANCH1 = "import java.util.List;\nimport java.math.BigDecimal;";
    static final String IMPORT_ADD_BRANCH2 = "import java.util.List;\nimport java.time.Instant;";
```

Both branches added a different import next to the same line; the union is the
answer:

[../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java#region:keeps-both-branches-imports)
```java
    @Test
    @DisplayName("keeps the imports added by both branches instead of reporting a conflict")
    void keepsBothBranchesImports() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.KEEP_BOTH,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("import java.math.BigDecimal;"),
            "branch 1's import must survive");
        assertTrue(resolution.getResolvedCode().contains("import java.time.Instant;"),
            "branch 2's import must survive");
        assertTrue(resolution.getResolvedCode().contains("import java.util.List;"),
            "the shared import must survive exactly once");
    }
```

The shared import appears exactly once in the rendered block:

[../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java#region:does-not-duplicate-shared-imports)
```java
    @Test
    @DisplayName("does not duplicate a shared import")
    void doesNotDuplicateSharedImports() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);
        String resolved = resolver.resolve(conflict).getResolvedCode();

        int occurrences = resolved.split("import java.util.List;", -1).length - 1;
        assertEquals(1, occurrences, "a shared import must appear once, was " + resolved);
    }
```

A static import is a distinct symbol from the plain import of the same type,
and keeps its modifier in the output:

[../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java#region:distinguishes-static-imports)
```java
    @Test
    @DisplayName("treats static imports as distinct from ordinary imports")
    void distinguishesStaticImports() {
        Conflict conflict = new Conflict(ConflictType.IMPORT_ADD, ConflictFixtures.FILE,
            "static import added",
            "import java.util.List;",
            "import java.util.List;\nimport static java.util.Objects.requireNonNull;",
            "import java.util.List;\nimport java.util.Objects;");

        String resolved = resolver.resolve(conflict).getResolvedCode();
        assertTrue(resolved.contains("static java.util.Objects.requireNonNull"),
            "the static import must be preserved with its modifier, was " + resolved);
        assertTrue(resolved.contains("java.util.Objects"), "the plain import must be preserved");
    }
```

## Example: a disputed removal, both policies

The fixture
[`import-add-remove-same`](../../../src/test/resources/fixtures/import-add-remove-same)
is the only import situation where the clash policy has anything to decide:
theirs removes `java.util.Set` while ours keeps the file exactly as the base
had it.

[../../../src/test/resources/fixtures/import-add-remove-same/base/PaymentProcessor.java.txt](../../../src/test/resources/fixtures/import-add-remove-same/base/PaymentProcessor.java.txt)
```java
package com.example.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * Payment processing. The body is identical in all three versions; this fixture is
 * only about the import block.
 */
public class PaymentProcessor {

    private final java.util.Map<String, java.util.List<java.math.BigDecimal>> ledger;

    public PaymentProcessor(java.util.Map<String, java.util.List<java.math.BigDecimal>> ledger) {
        this.ledger = ledger;
    }

    public void process(String account) {
        ledger.computeIfAbsent(account, key -> new java.util.ArrayList<>());
    }
}
```

[../../../src/test/resources/fixtures/import-add-remove-same/ours/PaymentProcessor.java.txt](../../../src/test/resources/fixtures/import-add-remove-same/ours/PaymentProcessor.java.txt)
```java
package com.example.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * Payment processing. The body is identical in all three versions; this fixture is
 * only about the import block.
 */
public class PaymentProcessor {

    private final java.util.Map<String, java.util.List<java.math.BigDecimal>> ledger;

    public PaymentProcessor(java.util.Map<String, java.util.List<java.math.BigDecimal>> ledger) {
        this.ledger = ledger;
    }

    public void process(String account) {
        ledger.computeIfAbsent(account, key -> new java.util.ArrayList<>());
    }
}
```

[../../../src/test/resources/fixtures/import-add-remove-same/theirs/PaymentProcessor.java.txt](../../../src/test/resources/fixtures/import-add-remove-same/theirs/PaymentProcessor.java.txt)
```java
package com.example.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
/**
 * Payment processing. The body is identical in all three versions; this fixture is
 * only about the import block.
 */
public class PaymentProcessor {

    private final java.util.Map<String, java.util.List<java.math.BigDecimal>> ledger;

    public PaymentProcessor(java.util.Map<String, java.util.List<java.math.BigDecimal>> ledger) {
        this.ledger = ledger;
    }

    public void process(String account) {
        ledger.computeIfAbsent(account, key -> new java.util.ArrayList<>());
    }
}
```

With the default policy the removal loses and the decision stays mechanical:

[../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java#region:default-policy-keeps-the-import)
```java
    @Test
    @DisplayName("the default policy keeps an import one side removed")
    void defaultPolicyKeepsTheImport() {
        assertEquals(ImportClashPolicy.ADDITION_WINS, ImportClashPolicy.defaultPolicy());

        ThreeWayFixture disputed = ThreeWayFixture.load(DISPUTED_CASE);
        ConflictResolution resolution =
            resolver.resolve(disputed.conflict(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "the default makes this mechanical, not a judgement call for a human");
        assertTrue(resolution.getResolvedCode().contains("import java.util.Set;"),
            "an unused import is harmless; a dropped one breaks uses of it");
        assertTrue(resolution.getExplanation().contains("ADDITION_WINS"),
            "the explanation must name the policy that decided it: "
                + resolution.getExplanation());
    }
```

Configured with `REMOVAL_WINS`, the removal is honoured — and because dropping
an import can break a use of it, the outcome is escalated to `REVIEW` rather
than applied silently:

[../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java#region:removal-policy-honours-the-removal)
```java
    @Test
    @DisplayName("configuring REMOVAL_WINS honours the removal and escalates it")
    void removalPolicyHonoursTheRemoval() {
        ImportConflictResolver removalWins =
            new ImportConflictResolver(ImportClashPolicy.REMOVAL_WINS);

        ThreeWayFixture disputed = ThreeWayFixture.load(DISPUTED_CASE);
        ConflictResolution resolution =
            removalWins.resolve(disputed.conflict(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "dropping an import can break a use of it, so it is not applied silently: "
                + resolution.getExplanation());
        assertFalse(resolution.getResolvedCode().contains("import java.util.Set;"),
            "the removal is honoured: " + resolution.getResolvedCode());
        assertTrue(resolution.getResolvedCode().contains("import java.util.List;"),
            "imports nobody disputed are untouched: " + resolution.getResolvedCode());
        assertTrue(resolution.getExplanation().contains("REMOVAL_WINS"),
            "the explanation must name the policy: " + resolution.getExplanation());
    }
```

The policy is the *only* difference between the two outcomes — the rest of the
block is identical:

[../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java#region:policy-is-the-only-difference)
```java
    @Test
    @DisplayName("the policy is the only difference between the two outcomes")
    void policyIsTheOnlyDifference() {
        Conflict conflict = ThreeWayFixture.load(DISPUTED_CASE)
            .conflict(ConflictType.IMPORT_ADD);

        String kept = new ImportConflictResolver(ImportClashPolicy.ADDITION_WINS)
            .resolve(conflict).getResolvedCode();
        String dropped = new ImportConflictResolver(ImportClashPolicy.REMOVAL_WINS)
            .resolve(conflict).getResolvedCode();

        String keptWithout = kept.replace("import java.util.Set;\n", "");
        assertEquals(keptWithout, dropped,
            "the policies must differ only in whether the clashing import is present");
    }
```

## When it declines

With nothing import-shaped on either side there is no union to compute, and the
resolver declines — the conflict routes to the MANUAL fallback, which still
carries fix paths for the reviewer:

[../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java](../../../src/test/java/com/codebuddy/merge/ImportConflictResolverTest.java#region:declines-when-no-imports-present)
```java
    @Test
    @DisplayName("declines when neither side contributes an import")
    void declinesWhenNoImportsPresent() {
        Conflict conflict = new Conflict(ConflictType.IMPORT_ADD, ConflictFixtures.FILE,
            "no imports", "int total = 0;", "int total = 1;", "int total = 2;");

        ConflictResolution resolution = resolver.resolve(conflict);
        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "with nothing import-shaped to merge, a human must decide");
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "the manual fallback must still offer options");
    }
```

## What it emits

- **Kind/strategy**: `AUTO` + `KEEP_BOTH` for composed additions and for a
  disputed removal under `ADDITION_WINS`; `REVIEW` + `KEEP_BOTH` for a disputed
  removal under `REMOVAL_WINS`; the MANUAL fallback when it declines.
- **Resolved code**: the surviving imports rendered as complete `import …;`
  lines, deduplicated.
- **Fix paths**: "Keep both" is recommended for the additive case; the options
  always include keeping one side's imports.

Every emitted line is a full declaration and the merged block is balanced,
asserted for the whole-file fixture:

[../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java](../../../src/test/java/com/codebuddy/merge/ThreeWayFixtureTest.java#region:merged-imports-remain-valid)
```java
    @Test
    @DisplayName("the merged import block is still syntactically valid")
    void mergedImportsRemainValid() {
        String resolved = resolver.resolve(fixture.conflict(ConflictType.IMPORT_ADD))
            .getResolvedCode();

        assertTrue(ResolutionVerifier.wasBalanced(resolved), "the merged block must be balanced");
        for (String line : resolved.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(line.startsWith("import ") && line.endsWith(";"),
                    "every emitted line must be a complete import declaration, was: " + line);
            }
        }
    }
```

This is also the one resolution the whole-file flow can splice mechanically:
[`MergeBatch.applyTo`](../../../src/main/java/com/codebuddy/merge/MergeBatch.java)
inserts missing resolved imports after the file's last import line, and the
conflict-file tool applies a single AUTO import resolution that covers a whole
marker block (see [`CONFLICT_FILE_TOOL.md`](../../CONFLICT_FILE_TOOL.md)).

## See also

- [`../README.md`](../README.md) — the resolver index and the include rules
- [`../../THREE_WAY_FIXTURES.md`](../../THREE_WAY_FIXTURES.md) — how the whole-file fixtures are written
- [`../../WHAT_IS_BASE.md`](../../WHAT_IS_BASE.md) — why "base" means last-synced, not merge-base
- [`../../../ADDING_A_RESOLVER.md`](../../../ADDING_A_RESOLVER.md) — adding a resolver of your own
