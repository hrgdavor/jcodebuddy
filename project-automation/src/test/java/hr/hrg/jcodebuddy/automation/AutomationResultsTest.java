package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The result records: what each one derives rather than stores, and what a report line says.
 *
 * <p>Both derivations are deliberate. {@link TransformationResult#changed()} is computed from the two
 * texts, and {@link ValidationResult#isValid()} from the error list, so neither can drift away from the
 * facts it summarises — this repository has already paid once for a stored verdict disagreeing with the
 * reason printed next to it (note F-44).</p>
 */
class AutomationResultsTest {

    private static final Path FILE = Path.of("Sample.java");

    @Test
    void successDerivesChangedFromTheTwoTexts() {
        TransformationResult changed = TransformationResult.success(FILE, "step", "a", "b", 3L);
        TransformationResult same = TransformationResult.success(FILE, "step", "a", "a", 3L);

        assertTrue(changed.succeeded());
        assertTrue(changed.changed());
        assertNull(changed.error());
        assertFalse(same.changed());
        assertTrue(changed.render().contains("changed"));
        assertTrue(same.render().contains("unchanged"));
    }

    @Test
    void failureKeepsTheInputAsItsOutputAndNeverClaimsAChange() {
        TransformationResult failure = TransformationResult.failure(FILE, "step", "a", "nope", 3L);

        assertFalse(failure.succeeded());
        assertFalse(failure.changed());
        assertEquals("a", failure.output());
        assertEquals("nope", failure.error());
        assertTrue(failure.render().contains("failed"));
        assertTrue(failure.render().contains("nope"));
    }

    @Test
    void validationIsValidExactlyWhenItHasNoErrors() {
        ValidationResult valid = new ValidationResult(FILE, List.of(), List.of("a warning"), 1L);
        ValidationResult invalid = new ValidationResult(FILE, List.of("an error"), List.of(), 1L);

        assertTrue(valid.isValid());
        assertFalse(invalid.isValid());
        assertTrue(valid.render().contains("warning"));
        assertFalse(valid.render().lines().findFirst().orElseThrow().contains("INVALID"));
        assertTrue(invalid.render().contains("INVALID"));
    }

    @Test
    void analysisExposesCountsAndRendersEveryFact() {
        AnalysisResult result = new AnalysisResult(FILE,
                Map.of(AnalysisResult.CODE_LINES, 12, AnalysisResult.PACKAGE, "a.b"),
                List.of("something to look at"), 2L);

        assertEquals(12, result.count(AnalysisResult.CODE_LINES));
        assertEquals(-1, result.count("notRecorded"));
        assertEquals("a.b", result.get(AnalysisResult.PACKAGE));
        assertTrue(result.render().contains("codeLines: 12"));
        assertTrue(result.render().contains("something to look at"));
    }

    @Test
    void resultsCopyTheirCollections() {
        // The records copy their collections, so a caller cannot change a result after it was reported.
        List<String> errors = new java.util.ArrayList<>(List.of("one error"));
        ValidationResult result = new ValidationResult(FILE, errors, List.of(), 1L);
        errors.add("another error");

        assertEquals(List.of("one error"), result.errors());
    }

    @Test
    void aReportWithNoResultsIsEmptyRatherThanBroken() {
        BatchProcessor.BatchReport report = new BatchProcessor.BatchReport("step", null);

        assertEquals(0, report.total());
        assertEquals(0, report.changed());
        assertEquals(0, report.failed());
        assertTrue(report.ok());
        assertEquals(List.of(), report.results());
    }
}
