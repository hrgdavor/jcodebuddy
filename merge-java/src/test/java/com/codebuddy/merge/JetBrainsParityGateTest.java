// {@link com.codebuddy.merge.JetBrainsParityGateTest} The benchmark as a build gate (plan step 4.13).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.merge.MergeRangeBuilder;
import com.codebuddy.merge.jetbrains.merge.MergeRangeUtil;
import com.codebuddy.merge.jetbrains.merge.MergeResolve;
import com.codebuddy.merge.jetbrains.merge.MergeType;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The parity gate: JetBrains is the floor, and the floor is a test result.</b> (Unified plan step 4.13, the
 * maintainer's rule of 2026-10-07 — <em>"this tool must be better than JetBrains, JetBrains is the benchmark of
 * minimum that has to be achieved where we overlap"</em>, and {@code JETBRAINS_PORT.md} § 5.5–5.6.)
 *
 * <h2>The failure this exists to catch is invisible</h2>
 *
 * <p>A port that resolves <em>more</em> cases than before but still escalates one that upstream resolves looks like
 * an improvement in every commit message and every dashboard. Only a gate catches it, and only if it grades the
 * <b>outcome per vector</b> rather than counting tests that pass.
 *
 * <h2>What is graded, and in which direction</h2>
 *
 * <ul>
 *   <li>upstream resolves to text X → we must produce the <b>same text</b> ("resolved something" is not parity);</li>
 *   <li>upstream refuses → we must refuse;</li>
 *   <li>upstream's invalidating edit makes a result unresolvable → we must become unresolvable too.</li>
 * </ul>
 *
 * <p>Both directions fail: <b>upstream resolves and we escalate</b> means we are below the floor, and
 * <b>upstream refuses and we apply automatically</b> is the more serious kind — a
 * {@code DESIGN_NEVER_AUTO_RESOLVED.md} breach wearing a port's clothes. The only permitted difference is one our
 * own arbitration or verifier deliberately makes, and it must be <b>listed here by name with its argument</b>, so
 * it is a recorded decision rather than a silent regression.
 *
 * <h2>The vectors are the benchmark's own</h2>
 *
 * <p>Transcribed from {@code MergeTest.kt} via {@code JETBRAINS_PORT.md} § 11.1 (change types) and § 11.2
 * (word-level resolves), where the upstream {@code _} is a line separator. They are held as data so the gate can
 * report a ratio and name every vector it had to excuse — a gate that could only say "some test failed" would be
 * the dashboards this step distrusts.
 */
class JetBrainsParityGateTest {

    /**
     * One upstream vector and what the benchmark does with it.
     *
     * @param name      the upstream test it came from, so a failure is traceable to the benchmark's own case
     * @param citation  where in {@code JETBRAINS_PORT.md} it is transcribed
     * @param left      ours, {@code _} being a line separator as upstream writes it
     * @param base      the base
     * @param right     theirs
     * @param expected  the ranges the benchmark expects, or {@code null} when it refuses the change outright
     */
    private record ChangeVector(String name, String citation, String left, String base, String right,
                                List<MergeType> expected) {
    }

    /** A word-level resolve vector: the benchmark produces content, or refuses. */
    private record ResolveVector(String name, String citation, String left, String base, String right,
                                 String expectedContent) {
    }

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    /**
     * Transcribe an upstream vector's text.
     *
     * <p>{@code JETBRAINS_PORT.md} § 11 says "`_` is a line separator", and that is the whole rule: {@code x_y} is
     * two lines and {@code x} is one, with <b>no trailing newline</b> unless the vector writes one ({@code x_}), and
     * an empty field is empty text.
     *
     * <p>This helper used to append {@code "\n"} to every vector, which invented a line on all three sides and — on
     * the vectors where the three sides end differently — put an insertion one base line later than the benchmark
     * does. Two insertions that are at the same point in the benchmark's model are then at different points in ours,
     * so R6's refusal is bypassed and a text neither side has is composed. The vectors were wrong, not the code; the
     * measurement is only worth something if the fixture says what the benchmark says.
     */
    private static String lines(String text) {
        return text.replace("_", "\n");
    }

    // ------------------------------------------------------------------ the vectors, read from disk

    /**
     * The benchmark's vectors, read from {@code src/test/resources/parity/}.
     *
     * <p>They live on disk rather than in this file because a vector set is data: it is transcribed from upstream,
     * it is meant to be extended as more of upstream's cases are taken, and a reviewer should be able to see the
     * table without reading Java. The gate is the only consumer, so the disk copy is the single source of truth
     * rather than a second copy of a table that also exists in code — which is the failure mode a fixture set
     * invites.
     *
     * <p><b>Why these are not {@code THREE_WAY_FIXTURES} cases,</b> since the plan said they would be: that layout
     * holds three <b>complete, compilable</b> Java files, because a fragment "cannot express a change" and type
     * attribution needs a plausible source path. These vectors are text-fragment ranges with expected <em>kinds</em>
     * — they exercise the ported text machinery rather than a {@link ConflictType}, and a whole-file fixture cannot
     * state {@code y z | x y z | x y}. Forcing them in would break the rule that makes those fixtures trustworthy.
     */
    private static List<String> vectorLines(String resource) {
        try (java.io.InputStream stream = JetBrainsParityGateTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing vector file on the classpath: " + resource);
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank() && !line.trim().startsWith("#"))
                .map(String::strip)
                .toList();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read " + resource, failure);
        }
    }

    /** Split a vector line into its fields, keeping empty ones (an empty field is empty text). */
    private static String[] fields(String line) {
        String[] parts = line.split("\\|", -1);
        for (int index = 0; index < parts.length; index++) {
            parts[index] = parts[index].trim();
        }
        return parts;
    }

    /** The expected shapes: `NONE`, or `KIND:leftChanged:rightChanged` joined by `;`. */
    private static List<MergeType> parseTypes(String encoded) {
        if (encoded.equals("NONE")) {
            return List.of();
        }
        List<MergeType> types = new ArrayList<>();
        for (String one : encoded.split(";")) {
            String[] parts = one.split(":");
            boolean left = parts[1].equals("1");
            boolean right = parts[2].equals("1");
            types.add(switch (parts[0]) {
                case "INSERTED" -> MergeType.inserted(left, right);
                case "DELETED" -> MergeType.deleted(left, right);
                case "MODIFIED" -> MergeType.modified(left, right);
                case "CONFLICT" -> {
                    // The benchmark's conflict rows all say "both", and a conflict with one side changed would be a
                    // different statement; refusing to guess keeps this parser honest.
                    if (!left || !right) {
                        throw new IllegalArgumentException("a CONFLICT vector must be both sides: " + one);
                    }
                    yield MergeType.conflict(false);
                }
                default -> throw new IllegalArgumentException("unknown kind in " + one);
            });
        }
        return List.copyOf(types);
    }

    private static final List<ChangeVector> CHANGE_VECTORS = loadChangeVectors();

    private static final List<ResolveVector> RESOLVE_VECTORS = loadResolveVectors();

    private static List<ChangeVector> loadChangeVectors() {
        List<ChangeVector> vectors = new ArrayList<>();
        for (String line : vectorLines("/parity/jetbrains-change-types.txt")) {
            String[] parts = fields(line);
            if (parts.length != 6) {
                throw new IllegalStateException("a change-type vector needs 6 fields: " + line);
            }
            vectors.add(new ChangeVector(parts[0], parts[1], parts[2], parts[3], parts[4],
                parseTypes(parts[5])));
        }
        return List.copyOf(vectors);
    }

    private static List<ResolveVector> loadResolveVectors() {
        List<ResolveVector> vectors = new ArrayList<>();
        for (String line : vectorLines("/parity/jetbrains-resolve.txt")) {
            String[] parts = fields(line);
            if (parts.length != 6) {
                throw new IllegalStateException("a resolve vector needs 6 fields: " + line);
            }
            vectors.add(new ResolveVector(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return List.copyOf(vectors);
    }

    /**
     * Vectors where we deliberately differ, each with its argument in one sentence.
     *
     * <p>The step allows an exception only when our {@code ResolutionVerifier} or {@code AnalysisLevel} arbitration
     * <em>deliberately</em> declines what upstream's editor-and-undo model accepts — and an exception that cannot be
     * argued in one sentence is a bug in the port rather than an exception. The two below are the rows whose answer
     * needs composition <b>inside</b> a conflict region (word-level), which our line-level pass refuses by design;
     * the answer is offered as a suggestion instead, which is neither an escalation nor an application.
     */
    private static final List<String> NAMED_EXCEPTIONS = List.of(
        "testResolve: two independent conflicts",
        "testResolve: left applied by hand, then right resolved",
        "testResolve: both sides deleted around y");

    /**
     * <b>Measured defects, not permitted exceptions.</b> Each is a place where we are <em>below</em> the benchmark,
     * written down with what we currently do, so the gate can hold the line at today's behaviour while the fix is
     * outstanding. The distinction from {@link #NAMED_EXCEPTIONS} is the whole point: an exception is a difference we
     * chose, and these are differences we owe.
     *
     * <p>The ratchet is deliberate. A gate that failed outright on the day it was written would be deleted or
     * ignored; a gate that <b>records</b> the gap and fails on anything worse can only tighten, and the entries below
     * disappear when the work lands rather than being renegotiated.
     *
     * <p><b>The serious one is the first, and it is the kind this step exists to catch.</b> For the vector
     * {@code x_Y | x_z_Y | z_Y} the benchmark expects one conflict — a person decides — and we <b>applied</b>
     * {@code "Y\n"}: the line each branch kept ({@code x} on the left, {@code z} on the right) was silently dropped,
     * and the result matched neither side. The cause was in the <b>range's own coordinates</b>: a side that deleted
     * only part of a base extent was recorded with extent length 0, so a range in which each side deleted a
     * <em>different</em> line read as "both sides deleted the same lines" and composed to nothing. That is fixed in
     * {@code MergeRangeBuilder}, and <b>this entry was removed from the list in the same commit</b> — a ratchet whose
     * entries are not removed when the work lands is a permanent excuse, and moving the vector into the strict set is
     * what proves the fix is real rather than the baseline being widened.
     */
    private static final List<String> KNOWN_DEFECTS = List.of();

    @Test
    @DisplayName("the benchmark's change-type vectors: our classifier reports what upstream reports")
    void changeTypeParity() {
        List<String> mismatches = new ArrayList<>();
        List<String> defects = new ArrayList<>();
        for (ChangeVector vector : CHANGE_VECTORS) {
            List<MergeType> actual = ConflictShape.typesOf(
                lines(vector.base()), lines(vector.left()), lines(vector.right()), POLICY);
            if (actual.equals(vector.expected())) {
                continue;
            }
            String detail = vector.name() + " (" + vector.citation() + ")"
                + "\n    benchmark: " + vector.expected()
                + "\n    ours:      " + actual;
            if (KNOWN_DEFECTS.contains(vector.name())) {
                defects.add(detail);
            } else {
                // Not a recorded defect and still disagreeing: a regression against the recorded baseline, which is
                // the one thing the ratchet refuses to allow.
                mismatches.add(detail);
            }
        }
        report("change types", CHANGE_VECTORS.size(), mismatches.size(), defects.size());
        assertTrue(mismatches.isEmpty(),
            mismatches.size() + " change-type vectors disagree beyond the recorded baseline:\n  - "
                + String.join("\n  - ", mismatches)
                + "\n  recorded defects (a debt, not a licence):\n    - " + String.join("\n    - ", defects));
    }

    @Test
    @DisplayName("the benchmark's resolve vectors: same text, or a named exception")
    void resolveParity() {
        List<String> mismatches = new ArrayList<>();
        int excepted = 0;
        for (ResolveVector vector : RESOLVE_VECTORS) {
            String base = lines(vector.base());
            String ours = lines(vector.left());
            String theirs = lines(vector.right());
            MergeResolve.Result result = MergeResolve.resolve(ours, base, theirs, POLICY);
            String expected = lines(vector.expectedContent());

            if (result.refused()) {
                // Upstream resolves and we refuse. For the three vectors needing word-level composition this is the
                // recorded exception; anything else would be a regression below the floor.
                if (NAMED_EXCEPTIONS.contains(vector.name())) {
                    excepted++;
                } else {
                    mismatches.add(vector.name() + " (" + vector.citation()
                        + "): the benchmark resolves this and we escalate it");
                }
                continue;
            }
            if (!result.mergedText().equals(expected)) {
                // Applied, but not to the benchmark's text: the serious direction, because a wrong answer that is
                // applied silently is worse than a refusal a person sees.
                mismatches.add(vector.name() + " (" + vector.citation() + ") — APPLIED THE WRONG TEXT"
                    + "\n    benchmark: " + expected.replace("\n", "\\n")
                    + "\n    ours:      " + result.mergedText().replace("\n", "\\n"));
            }
        }
        // A refused vector is NOT parity, and saying "3/3 agree" because three refusals were expected is exactly the
        // dashboard arithmetic this step exists to distrust. The two counts are printed apart.
        System.out.println("PARITY-METRIC: resolve vectors "
            + (RESOLVE_VECTORS.size() - mismatches.size() - excepted) + "/" + RESOLVE_VECTORS.size()
            + " resolved to the benchmark's text, " + excepted + " declined under a recorded exception"
            + (mismatches.isEmpty() ? ", 0 REGRESSION(S)" : ", " + mismatches.size() + " REGRESSION(S)"));
        assertTrue(mismatches.isEmpty(),
            mismatches.size() + " resolve vectors disagree with the benchmark:\n  - "
                + String.join("\n  - ", mismatches));
    }

    @Test
    @DisplayName("the benchmark refuses these, and so do we: an application here would be the serious failure")
    void refusalParity() {
        // Upstream refuses two different insertions at one point, and so must we: an automatic application where the
        // benchmark refuses is a DESIGN_NEVER_AUTO_RESOLVED.md breach wearing a port's clothes.
        assertTrue(MergeResolve.resolve(lines("a"), lines("x"), lines("b"), POLICY).refused(),
            "two different insertions at one point: the benchmark refuses and so must we");
        assertTrue(MergeResolve.resolve(lines("k_x_y"), lines("x_y"), lines("z_x_y"), POLICY).refused(),
            "differing insertions around a kept line: likewise");
        assertEquals(List.of(MergeType.conflict(false)),
            ConflictShape.typesOf(lines("y"), lines("x"), lines("z"), POLICY),
            "and the shape says why: the change is a conflict, not an insertion");
    }

    @Test
    @DisplayName("every change the benchmark calls a conflict, we refuse rather than apply")
    void noApplicationWhereTheBenchmarkNeedsAPerson() {
        // THE assertion this step needed, and the one that would have caught the defect as an APPLICATION rather
        // than as a type mismatch: the change-type vectors only compare our classifier's naming, so a range that is
        // NAMED `deleted both` and then composed to nothing passes them - which is exactly how `x_Y | x_z_Y | z_Y`
        // came to be applied as `Y`, dropping the line each branch kept. This checks the outcome instead: where the
        // benchmark says a person decides, we must not produce text at all.
        List<String> applied = new ArrayList<>();
        for (ChangeVector vector : CHANGE_VECTORS) {
            boolean needsAPerson = vector.expected().stream()
                .anyMatch(type -> type.kind() == MergeType.Kind.CONFLICT);
            if (!needsAPerson) {
                continue;
            }
            MergeResolve.Result result = MergeResolve.resolve(
                lines(vector.left()), lines(vector.base()), lines(vector.right()), POLICY);
            if (!result.refused()) {
                applied.add(vector.name() + " (" + vector.citation() + ") — applied "
                    + result.mergedText().replace("\n", "\\n"));
            }
        }
        assertTrue(applied.isEmpty(),
            "the benchmark needs a person here and we applied an answer:\n  - "
                + String.join("\n  - ", applied));
    }

    @Test
    @DisplayName("the refusal vectors AND their control: the type refuses, not the text")
    void refusalVectorsWithTheirControl() {
        // Section 11.4, and the control is the point: a port that took only the two refusal rows would pass while
        // being wrong, because it could be refusing the TEXT. The control has the same shape of asymmetry — one side
        // changed, the other did not — and must auto-resolve, which is only true if the refusal comes from the
        // modify/delete TYPE.
        String base = lines("A_B_C");
        String edited = lines("A_X_C");

        // Rows 1 and 2 are modify/delete: one branch removed the lines, the other EDITED them. Nothing decides it.
        assertTrue(MergeResolve.resolve("", base, edited, POLICY).refused(),
            "DELETED/MODIFIED: the pass must refuse it");
        assertTrue(MergeResolve.resolve(edited, base, "", POLICY).refused(),
            "MODIFIED/DELETED: the mirror image, likewise");
        // WHERE the refusal comes from is asserted too, because "it refused" is not the same fact as "the rule we
        // claim refused it" — and here the route is not the one the port document describes. The modify/delete
        // guard reads a range's extents, and an EMPTY TEXT is one empty line to `TextLines.of("")`, not nothing, so
        // a whole-side deletion arrives as "replaced everything with a blank line" and the guard sees a modifica-
        // tion on both sides. The refusal is carried by the shape instead: both sides have content over the range and
        // they disagree, which is a CONFLICT, and a conflict is never resolved. The rule holds; the guard is a second
        // statement of it that a built range cannot reach — it is exercisable only on a hand-built range, which is
        // exactly how `MergeResolveTest` tests it.
        assertEquals(List.of(MergeType.conflict(false)), ConflictShape.typesOf(base, "", edited, POLICY),
            "the refusal's route is the shape, not the guard: a conflict is unresolvable");
        MergeRangeBuilder.MergeChange deletedAgainstEdit =
            MergeRangeBuilder.build(base, "", edited, POLICY).get(0);
        assertFalse(deletedAgainstEdit.range().leftIsEmpty(),
            "an empty text is one empty line to TextLines, so the guard cannot see this as a deletion");
        assertNull(MergeRangeUtil.modifyDeleteShape(deletedAgainstEdit.range()),
            "and that is why the guard is not what refuses it");
        assertEquals(MergeRangeUtil.DeletedSide.LEFT,
            MergeRangeUtil.modifyDeleteShape(new com.codebuddy.merge.jetbrains.merge.MergeRange(0, 0, 0, 3, 0, 3)),
            "the guard's rule is intact — a range that HAS an empty side still names it, as its own test asserts");

        // THE CONTROL: the same asymmetry — the left changed, the right did not — and it resolves, because no side
        // deleted. Same shape of asymmetry, same kind of texts, different TYPE.
        MergeResolve.Result control = MergeResolve.resolve(base, base, edited, POLICY);
        assertFalse(control.refused(),
            "the control must auto-resolve, or the two rows above prove nothing about the type");
        assertEquals(edited, control.mergedText(), "and to the changed side's text");
        assertEquals(List.of(MergeType.modified(false, true)),
            ConflictShape.typesOf(base, base, edited, POLICY),
            "the control's shape is a modification, not a deletion: that difference IS the refusal's cause");
        assertNull(MergeRangeUtil.modifyDeleteShape(
                MergeRangeBuilder.build(base, base, edited, POLICY).get(0).range()),
            "and the guard has no opinion about it, which is why it resolves");

        // A DELIBERATE DIFFERENCE, recorded with its argument. Upstream writes its two rows with the other side
        // UNCHANGED (theirs == base), which is their FILE-level `DELETED_MODIFIED` conflict: one branch deleted the
        // file and the other left it alone. At range level we apply that one-sided deletion, because a change only
        // one side made is exactly what non-conflicting auto-apply is for — § 11.3's own "remove-right" vector
        // requires it, and refusing here would make the two vector sets contradict each other. So the refusal is
        // ported at the level where our model has it: delete against EDIT, the rule the guard and the pass implement
        // twice on purpose.
        MergeResolve.Result oneSidedDeletion = MergeResolve.resolve("", base, base, POLICY);
        assertFalse(oneSidedDeletion.refused(),
            "a deletion only one side made is a non-conflicting change, and § 11.3 requires us to apply it");
        assertEquals("", oneSidedDeletion.mergedText());
    }

    @Test
    @DisplayName("the invalidating edit: an edit to the result makes the recorded answer inapplicable")
    void theInvalidatingEdit() {
        // Section 11.2's SECOND table, and the row the port document says matters most for us: upstream resolves
        // `y z | x y z | x y` to `y`, and then replacing result line 2 with `U` makes `canResolveConflict()` false.
        // The rule is that **resolvability is a property of the current output, not of the original inputs** — an
        // answer computed against text a person has since edited is an answer to a question nobody is asking.
        //
        // Our expression of it is the signature: the conflict's sides are part of it, so an edit to a block changes
        // the signature and a decision recorded under the old one cannot match. That is what makes a replayed
        // decision safe, and asserting the mechanism is the honest test of the rule — it is about the identity of the
        // thing the answer was computed for, not about a file.
        Conflict original = ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE);

        assertEquals(ConflictSignature.of(original),
            ConflictSignature.of(ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE)),
            "the same conflict has the same signature: a decision recorded for it can be found again");

        // The edit: one line of OUR side changed, which is exactly upstream's "replace result line 2 with U".
        Conflict edited = new Conflict(original.getType(), original.getFilePath(), original.getDescription(),
            original.getBaseCode(), original.getBranch1Code().replace("\n", "\n// edited by a person\n"),
            original.getBranch2Code());
        assertNotEquals(ConflictSignature.of(original), ConflictSignature.of(edited),
            "an edited block is a different conflict, so the recorded answer no longer applies");
        assertNotEquals(ConflictSignature.of(original).toFileName(),
            ConflictSignature.of(edited).toFileName(),
            "and the difference reaches the name a decision is filed under, which is what a replay looks up");

        // THE GAP, found by writing this test and pinned rather than papered over: the BASE is NOT part of the
        // signature, so a conflict whose upstream side moved — a rebase, a different merge base — keeps the same
        // signature and a recorded answer still matches it.
        //
        // Why it matters: the sides decide the *text*, but the base decides how the change is READ (`ConflictShape`,
        // the modify/delete rule, whether "both sides inserted" is even true). An answer recorded before a rebase can
        // therefore be replayed under a description that no longer holds.
        //
        // Why it is NOT silently "fixed" here: adding the base would invalidate every recorded decision whenever the
        // base moves, which is a policy about when a person's past decision stops counting — the maintainer's call,
        // with a real trade on both sides (a rebase of an unrelated part of the file should not throw away an answer;
        // a base that genuinely changed under the conflict probably should). This assertion is the pin: if the
        // decision goes the other way it flips to `assertNotEquals`, and this comment becomes the record.
        Conflict rebased = new Conflict(original.getType(), original.getFilePath(), original.getDescription(),
            original.getBaseCode() + "\n    // upstream moved on\n", original.getBranch1Code(),
            original.getBranch2Code());
        assertEquals(ConflictSignature.of(original), ConflictSignature.of(rebased),
            "OPEN QUESTION for the maintainer: the base is not in the signature, so a rebase does not invalidate a"
                + " recorded answer. Flipping this to assertNotEquals is the change, and it wants its own commit"
                + " with that decision recorded");
    }

    /**
     * The gate's own output: the ratio, the recorded defects and any regression.
     *
     * <p>A claim of parity without these numbers is the assertion this step exists to prevent, so they are printed on
     * every run rather than only on failure.
     */
    private static void report(String family, int total, int mismatches, int defects) {
        System.out.println("PARITY-METRIC: " + family + " " + (total - mismatches - defects) + "/" + total
            + " vectors agree with the benchmark"
            + (defects > 0 ? ", " + defects + " recorded defect(s)" : "")
            + (mismatches > 0 ? ", " + mismatches + " REGRESSION(S)" : ""));
    }
}
