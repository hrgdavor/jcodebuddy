// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code View1Builder.merge(View2 other)} — plan step 7.3, and the proxy variant's shared core.
 *
 * <p>Asserted one rule at a time, because a merge is easy to fake: a method that copies <em>every</em> field of the
 * other view compiles whenever the two views happen to be identical, and a method that copies none of them compiles
 * always. The three rules that make it a merge rather than either are that only shared name+type fields are copied,
 * that a field absent from the other view is left alone <b>silently</b> (that is the feature, not a defect), and that
 * a name-only match is a DEC-022 diagnostic which copies nothing.
 */
class ViewMergeGeneratorTest {

    private static final String BUILDER = "PersonSummaryBuilder";

    private static List<Property> host() {
        return List.of(
                new Property("id", "Long"),
                new Property("name", "String"),
                new Property("nickname", "String"),
                new Property("score", "int"));
    }

    /** Shares {@code id} and {@code name}; has {@code nickname} with another type; lacks {@code score}. */
    private static ViewMergeGenerator.Partner partner() {
        return new ViewMergeGenerator.Partner("PersonDto", "hr.hrg.example.PersonDto", List.of(
                new Property("id", "Long"),
                new Property("name", "String"),
                new Property("nickname", "Integer"),
                new Property("extra", "String")));
    }

    @Test
    void onlyFieldsSharedByNameAndTypeAreMerged() {
        DivergenceReporter divergences = new DivergenceReporter();
        ViewMergeGenerator.Plan plan = ViewMergeGenerator.plan(BUILDER, "PersonSummary", host(), partner(),
                divergences);

        Assertions.assertEquals(List.of("id", "name"), plan.merged(),
                "a merge copies the fields the two views share by name AND type, in the host's order");
        Assertions.assertEquals(List.of("nickname"), plan.typeMismatched());
    }

    @Test
    void aFieldThePartnerDoesNotHaveIsNotReported() {
        DivergenceReporter divergences = new DivergenceReporter();
        ViewMergeGenerator.plan(BUILDER, "PersonSummary", host(), partner(), divergences);

        // `score` exists only in the host, and `extra` only in the partner. Neither is a fault: the builder keeps what
        // it has and the merge ignores the rest. The mapper reports this shape because every target field needs a
        // value; here nothing is lost, and a diagnostic would make the normal case look broken.
        Assertions.assertTrue(divergences.entries().stream().noneMatch(entry -> entry.contains("score")),
                "an unshared field must not be reported: " + divergences.entries());
        Assertions.assertTrue(divergences.entries().stream().noneMatch(entry -> entry.contains("extra")),
                divergences.entries().toString());
    }

    @Test
    void aNameOnlyMatchIsADiagnosticInDec022FormatAndCopiesNothing() {
        DivergenceReporter divergences = new DivergenceReporter();
        ViewMergeGenerator.Plan plan = ViewMergeGenerator.plan(BUILDER, "PersonSummary", host(), partner(),
                divergences);

        String entry = divergences.entries().stream()
                .filter(candidate -> candidate.contains("merge_field_type_mismatch"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mismatch diagnostic in " + divergences.entries()));
        // DEC-022's format is kind, location, cause, current, canonical, action — the reader has to be able to see
        // both types and what to do, not only that something differed.
        Assertions.assertTrue(entry.contains(BUILDER + ".merge.nickname"), entry);
        Assertions.assertTrue(entry.contains("String") && entry.contains("Integer"),
                "both declared types must be visible: " + entry);
        Assertions.assertFalse(plan.merged().contains("nickname"),
                "a mismatch must be skipped rather than copied with a cast nobody proved");
    }

    @Test
    void aMergeWithNothingInCommonSaysSoRatherThanDoingNothingQuietly() {
        DivergenceReporter divergences = new DivergenceReporter();
        ViewMergeGenerator.Partner stranger = new ViewMergeGenerator.Partner("Other", "hr.hrg.example.Other",
                List.of(new Property("alpha", "String")));

        ViewMergeGenerator.Plan plan = ViewMergeGenerator.plan(BUILDER, "PersonSummary", host(), stranger,
                divergences);

        Assertions.assertTrue(plan.merged().isEmpty());
        Assertions.assertTrue(divergences.entries().stream().anyMatch(entry -> entry.contains("merge_copies_nothing")),
                "a merge request that copies nothing is almost always a mistake in the request, and silence would "
                        + "look like the flag was ignored: " + divergences.entries());
    }

    @Test
    void theEmittedMethodIsNullSafeReturnsTheBuilderAndTouchesOnlyPlannedFields() {
        ViewMergeGenerator.Plan plan = ViewMergeGenerator.plan(BUILDER, "PersonSummary", host(), partner(), null);

        String method = ViewMergeGenerator.method(BUILDER, plan);

        Assertions.assertTrue(method.contains("public " + BUILDER + " merge(PersonDto other)"),
                "the signature the plan names, on the builder: " + method);
        Assertions.assertTrue(method.contains("if (other == null)"), method);
        Assertions.assertTrue(method.contains("return this;"), method);
        Assertions.assertTrue(method.contains("id(other.id());"), method);
        Assertions.assertTrue(method.contains("name(other.name());"), method);
        // The rule that matters most: NOTHING that is not a proven name+type match may appear, and there is no
        // build() call inside - so a builder never returns a partially built instance.
        Assertions.assertFalse(method.contains("nickname("), method);
        Assertions.assertFalse(method.contains("score("), method);
        Assertions.assertFalse(method.contains("extra("), method);
        Assertions.assertFalse(method.contains("build()"), method);
    }

    @Test
    void aRetiredTombstoneOnEitherSideIsNotMerged() {
        List<Property> withTombstone = new ArrayList<>(host());
        withTombstone.add(new Property("legacy", "String", "RETIRED", null, null, null, -1, List.of(), List.of()));
        ViewMergeGenerator.Partner partnerWithTombstone = new ViewMergeGenerator.Partner("PersonDto",
                "hr.hrg.example.PersonDto", List.of(
                        new Property("legacy", "String"),
                        new Property("id", "Long")));

        ViewMergeGenerator.Plan plan = ViewMergeGenerator.plan(BUILDER, "PersonSummary", withTombstone,
                partnerWithTombstone, null);

        // A retired host slot has no setter to write through and a retired partner slot has no accessor to read with,
        // so merging either would emit code that cannot compile (R1.4).
        Assertions.assertEquals(List.of("id"), plan.merged(), plan.merged().toString());
    }
}
