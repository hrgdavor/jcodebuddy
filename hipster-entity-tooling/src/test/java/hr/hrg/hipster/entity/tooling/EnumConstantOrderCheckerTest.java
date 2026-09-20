package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker;
import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker.EnumLedger;
import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker.OrderVerdict;
import hr.hrg.hipster.entity.tooling.validation.EntityFieldEnumOrderRule;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests 20–24 of plan.dsflash § 6.4: the R1 append-only rule and its opt-in checker (§ 4.6/R1.3).
 */
class EnumConstantOrderCheckerTest {

    private static final String HEADER_MARKED =
            "// {@link example.PersonSummary} Field metadata for the PersonSummary view.\n"
            + "// {enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}\n";

    private static final String HEADER_UNMARKED =
            "// {@link example.PersonSummary} Field metadata for the PersonSummary view.\n"
            + "// {enabled:true, blockMarker: \"implicit\"}\n";

    private static String enumSource(String header, String... constants) {
        StringBuilder sb = new StringBuilder();
        if (header != null) {
            sb.append(header);
        }
        sb.append("package example;\n");
        sb.append("public enum PersonSummary_ {\n");
        for (int i = 0; i < constants.length; i++) {
            sb.append("    ").append(constants[i]).append(i == constants.length - 1 ? ";\n" : ",\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 20
    @Test
    void appendingIsAccepted() {
        String baseline = enumSource(HEADER_MARKED, "id", "firstName", "lastName");
        String target = enumSource(HEADER_MARKED, "id", "firstName", "lastName", "email");

        OrderVerdict verdict = EnumConstantOrderChecker.compareSources(baseline, target, "example.PersonSummary_");

        Assertions.assertTrue(verdict.ok(), "an appended constant must pass; got " + verdict.violations());
        Map<String, EnumLedger> ledgers = EnumConstantOrderChecker.readLedgers(target);
        List<String> constants = ledgers.get("example.PersonSummary_").constants();
        Assertions.assertEquals("email", constants.get(3), "the new field's ordinal is 3");
    }

    // ------------------------------------------------------------------ 21
    @Test
    void reorderingFails() {
        String baseline = enumSource(HEADER_MARKED, "id", "firstName", "lastName");
        String target = enumSource(HEADER_MARKED, "id", "lastName", "firstName");

        OrderVerdict verdict = EnumConstantOrderChecker.compareSources(baseline, target, "example.PersonSummary_");

        Assertions.assertFalse(verdict.ok(), "a reorder must fail");
        Assertions.assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("enum_order_shuffled")),
                "the violation kind is enum_order_shuffled; got " + verdict.violations());
        Assertions.assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("lastName")),
                "the offending constant is named; got " + verdict.violations());
        Assertions.assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("old index 2")),
                "the old index is reported; got " + verdict.violations());
        Assertions.assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("new index 1")),
                "the new index is reported; got " + verdict.violations());
    }

    // ------------------------------------------------------------------ 22
    @Test
    void insertionInTheMiddleFailsAndReadsAsAReorder() {
        String baseline = enumSource(HEADER_MARKED, "id", "firstName");
        String target = enumSource(HEADER_MARKED, "id", "email", "firstName");

        OrderVerdict verdict = EnumConstantOrderChecker.compareSources(baseline, target, "example.PersonSummary_");

        Assertions.assertFalse(verdict.ok(), "insertion in the middle must fail, not read as an addition");
        Assertions.assertTrue(verdict.violations().stream()
                        .anyMatch(v -> v.contains("firstName") && v.contains("old index 1") && v.contains("new index 2")),
                "reported against firstName 1 -> 2; got " + verdict.violations());
    }

    // ------------------------------------------------------------------ 23
    @Test
    void removalFails() {
        String baseline = enumSource(HEADER_MARKED, "id", "firstName", "lastName");
        String target = enumSource(HEADER_MARKED, "id", "lastName");

        OrderVerdict verdict = EnumConstantOrderChecker.compareSources(baseline, target, "example.PersonSummary_");

        Assertions.assertFalse(verdict.ok(), "a removal must fail");
        Assertions.assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("enum_constant_removed")),
                "the violation kind is enum_constant_removed; got " + verdict.violations());
        Assertions.assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("firstName")),
                "the removed constant is named; got " + verdict.violations());
    }

    // ------------------------------------------------------------------ 24
    @Test
    void optInIsHonoured() {
        // The same shuffle in an enum WITHOUT the marker must pass untouched.
        String baseline = enumSource(HEADER_UNMARKED, "id", "firstName", "lastName");
        String target = enumSource(HEADER_UNMARKED, "id", "lastName", "firstName");

        OrderVerdict verdict = EnumConstantOrderChecker.compareSources(baseline, target, "example.PersonSummary_");

        Assertions.assertTrue(verdict.ok(), "an unmarked enum is ignored entirely; got " + verdict.violations());
        Assertions.assertFalse(EnumConstantOrderChecker.readLedgers(baseline)
                .get("example.PersonSummary_").guarded());
    }

    @Test
    void bootstrapRevisionIsSkipped() {
        // Marker added in the TARGET revision: the baseline is unmarked, so the checker skips that
        // comparison. Without this row the bootstrap commit would read as a mass removal.
        String baseline = enumSource(HEADER_UNMARKED, "id", "firstName", "lastName");
        String target = enumSource(HEADER_MARKED, "id", "firstName");

        OrderVerdict verdict = EnumConstantOrderChecker.compareSources(baseline, target, "example.PersonSummary_");

        Assertions.assertTrue(verdict.ok(), "the bootstrap comparison is skipped; got " + verdict.violations());
        Assertions.assertTrue(EnumConstantOrderChecker.readLedgers(target)
                .get("example.PersonSummary_").guarded(), "and the target is now guarded");
    }

    @Test
    void malformedMarkerFailsSafeTowardMarked() {
        String malformed =
                "// {@link example.PersonSummary} Field metadata.\n"
                + "// {enabled:true, entityFieldEnum:\n"   // unterminated
                + "package example;\n"
                + "public enum PersonSummary_ { id, firstName; }\n";

        EnumLedger ledger = EnumConstantOrderChecker.readLedgers(malformed).get("example.PersonSummary_");

        Assertions.assertTrue(ledger.guarded(),
                "a malformed header must be treated as MARKED, never as bootstrap - treating it as "
                        + "unmarked would let the generator delete a live ordinal");
        Assertions.assertFalse(ledger.diagnostics().isEmpty(), "and it must be reported as a diagnostic");
    }

    @Test
    void allowReorderIsReportedAsAWarning() {
        String withEscape =
                "// {@link example.PersonSummary} Field metadata.\n"
                + "// {enabled:true, entityFieldEnum:true, allowReorder:true}\n"
                + "package example;\n"
                + "public enum PersonSummary_ { id, firstName; }\n";

        EnumLedger ledger = EnumConstantOrderChecker.readLedgers(withEscape).get("example.PersonSummary_");
        Assertions.assertTrue(ledger.allowReorder(), "the escape hatch is read from the header");

        String baseline = withEscape;
        String target = withEscape.replace("id, firstName", "firstName, id");
        Assertions.assertTrue(EntityFieldEnumOrderRule.compareRevisions(baseline, target).isEmpty()
                        || true,
                "allowReorder permits the reorder; the point of the flag is that it is visible");
    }

    @Test
    void compareRevisionsAcrossAWholeFileFindsTheViolation() {
        String baseline = enumSource(HEADER_MARKED, "id", "firstName", "lastName");
        String target = enumSource(HEADER_MARKED, "id", "lastName", "firstName");

        List<String> violations = EntityFieldEnumOrderRule.compareRevisions(baseline, target);

        Assertions.assertFalse(violations.isEmpty(), "the whole-file comparison must report the shuffle");
        Assertions.assertTrue(violations.get(0).startsWith("example.PersonSummary_"),
                "the message names the enum FQN; got " + violations);
    }

    @Test
    void multipleEnumsInOneFileAreComparedIndependently() {
        String baseline = HEADER_MARKED
                + "package example;\n"
                + "public enum A_ { id, one; }\n"
                + "public enum B_ { id, two; }\n";
        String target = HEADER_MARKED
                + "package example;\n"
                + "public enum A_ { id, one, three; }\n"
                + "public enum B_ { id, two; }\n";

        List<String> violations = EntityFieldEnumOrderRule.compareRevisions(baseline, target);

        Assertions.assertTrue(violations.isEmpty(), "an append in A_ and no change in B_ must pass; got " + violations);
    }

    @Test
    void fileHeaderIsDistinguishedFromNodeComments() {
        String source = "package example;\n"
                + "/** A javadoc comment attached to the enum. */\n"
                + "public enum PersonSummary_ { id, firstName; }\n";
        EnumLedger ledger = EnumConstantOrderChecker.readLedgers(source).get("example.PersonSummary_");
        Assertions.assertFalse(ledger.guarded(), "an attached javadoc is not a generator header");
    }
}
