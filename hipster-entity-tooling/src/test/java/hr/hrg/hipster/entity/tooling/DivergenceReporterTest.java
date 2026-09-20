package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The {@link DivergenceReporter}'s own contract: one entry per fact, no fact silently lost, and a
 * kind lookup that cannot miss.
 *
 * <p>This class exists because the reporter's previous, string-only form had two defects that no
 * generator test could see (plan.dsflash § 8.7/3.20's known gap, and F-43's "a guard that cannot
 * fire is worse than no guard"):</p>
 * <ul>
 *   <li>a view claimed by two markers reported the same divergence once per marker; and</li>
 *   <li>{@code ofKind} matched a literal text prefix, so an entry whose text did not begin that way
 *       was invisible to it — the report looked empty rather than wrong.</li>
 * </ul>
 *
 * <p>Both are asserted here as properties, because the generator-level test
 * ({@code MarkerClaimedViewReportingTest}) can only show the symptom, not the rule.</p>
 */
class DivergenceReporterTest {

    @Test
    void theIdenticalEntryIsCollapsedAndCounted() {
        DivergenceReporter reporter = new DivergenceReporter();
        reporter.report("nested_record_reused", "p.PersonSummary", "the view declares a nested record",
                "[id, firstName]", "no top-level record is emitted", "keep it in sync");
        reporter.report("nested_record_reused", "p.PersonSummary", "the view declares a nested record",
                "[id, firstName]", "no top-level record is emitted", "keep it in sync");

        Assertions.assertEquals(1, reporter.size(), reporter.render());
        Assertions.assertEquals(1, reporter.duplicatesRemoved(),
                "the collapse is the fix, so it is asserted rather than inferred from a smaller report");
    }

    @Test
    void theSameKindAtTheSameLocationWithADifferentCurrentIsTwoFacts() {
        DivergenceReporter reporter = new DivergenceReporter();
        reporter.report("type_mismatch", "p.PersonSummary_.age", "the declared type changed",
                "java.lang.String.class", "java.lang.Integer.class", "restore the type");
        reporter.report("type_mismatch", "p.PersonSummary_.age", "the declared type changed",
                "java.lang.Long.class", "java.lang.Integer.class", "restore the type");

        Assertions.assertEquals(2, reporter.size(),
                "two different on-disk types are two findings; collapsing them would delete one");
        Assertions.assertEquals(0, reporter.duplicatesRemoved());
    }

    @Test
    void aReporterAddedLineIsFoundByOfKind() {
        DivergenceReporter reporter = new DivergenceReporter();
        // The ledger's entries arrive pre-formatted through addAll — the path the old prefix match
        // happened to fit but did not guarantee.
        reporter.addAll(List.of(
                "kind=field_retired, location=PersonSummary_.lastName, cause=the accessor is gone,"
                        + " current=@Deprecated tombstone, canonical=kept in place,"
                        + " action=keep the ordinal, restore the accessor if it was a mistake"));

        Assertions.assertEquals(1, reporter.ofKind("field_retired").size(),
                "an entry that reached the reporter pre-formatted is found by its kind: "
                        + reporter.render());
        Assertions.assertTrue(reporter.ofKind("field_retired").get(0).contains("PersonSummary_.lastName"));
        Assertions.assertTrue(reporter.ofKind("enum_constant_removed").isEmpty());
    }

    @Test
    void aValueThatContainsACommaSurvivesTheRoundTrip() {
        DivergenceReporter reporter = new DivergenceReporter();
        reporter.report("type_ambiguous", "p.PersonDetails_.thing",
                "the declaring package owns the name twice",
                "[a.LocalThing, b.LocalThing]", "unresolved, reported rather than guessed",
                "rename one, or import the intended type");

        String entry = reporter.ofKind("type_ambiguous").get(0);
        Assertions.assertTrue(entry.contains("current=[a.LocalThing, b.LocalThing]"),
                "a comma inside a value is not a field boundary: " + entry);
        Assertions.assertTrue(entry.contains("action=rename one, or import the intended type"),
                entry);
    }

    @Test
    void aLineThatIsNotInTheDec022ShapeIsKeptVerbatimRatherThanDropped() {
        DivergenceReporter reporter = new DivergenceReporter();
        reporter.add("something the generator never writes");

        Assertions.assertEquals(1, reporter.size(),
                "an unparsable diagnostic is still a diagnostic: it is reported, not swallowed");
        Assertions.assertTrue(reporter.render().contains("something the generator never writes"));
        Assertions.assertTrue(reporter.ofKind("something").isEmpty(),
                "and it has no kind to be found by, which is honest rather than a guess");
    }

    @Test
    void theFieldOrderIsTheDec022OrderWithNullsOmitted() {
        DivergenceReporter reporter = new DivergenceReporter();
        reporter.report("deep_tracking_type_not_enabled", "p.PersonDetails_.child", null, null, null,
                "add @View(gen = GenLevel.BUILDER_TRACKED) to the nested view");

        String entry = reporter.entries().get(0);
        Assertions.assertTrue(entry.startsWith("kind=deep_tracking_type_not_enabled, location="),
                "kind comes first, location second: " + entry);
        Assertions.assertFalse(entry.contains("cause="),
                "a null field is omitted rather than rendered as cause=null: " + entry);
        Assertions.assertTrue(entry.endsWith("action=add @View(gen = GenLevel.BUILDER_TRACKED)"
                + " to the nested view"), entry);
    }

    @Test
    void everyKindInTheRecognizedListCanBeReportedAndFound() {
        // The list is documentation plus tests (its own javadoc says so); this closes the loop by
        // reporting each one and finding it again, so a name that cannot round-trip is caught here.
        DivergenceReporter reporter = new DivergenceReporter();
        for (String kind : DivergenceReporter.KINDS) {
            reporter.report(kind, "location." + kind, "cause", "current", "canonical", "action");
        }

        Assertions.assertEquals(DivergenceReporter.KINDS.size(), reporter.size(), reporter.render());
        for (String kind : DivergenceReporter.KINDS) {
            Assertions.assertEquals(1, reporter.ofKind(kind).size(), kind + " in " + reporter.render());
        }
    }
}
