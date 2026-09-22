package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;
import java.util.List;

/**
 * What validating one source file found.
 *
 * <p>{@code errors} and {@code warnings} are separate because they lead to different actions: an error
 * means the file is not acceptable as it stands, a warning is a fact worth printing that does not by
 * itself fail a run. {@link #isValid()} is derived from {@code errors} rather than stored, so the two can
 * never disagree — the failure this repository already paid for once, when a stored verdict and a
 * reported reason drifted apart (note F-44).</p>
 *
 * @param sourceFile  the file that was validated
 * @param errors      the reasons the file is not acceptable, empty when it is
 * @param warnings    facts worth reporting that do not fail the file
 * @param durationMs  how long validation took, in milliseconds
 */
public record ValidationResult(Path sourceFile, List<String> errors, List<String> warnings, long durationMs) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Whether the file is acceptable: it has no errors. */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /** One line per finding, for a report. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(isValid() ? "valid      " : "INVALID    ").append(sourceFile)
                .append(" (").append(durationMs).append(" ms)");
        for (String error : errors) {
            sb.append(System.lineSeparator()).append("  error    ").append(error);
        }
        for (String warning : warnings) {
            sb.append(System.lineSeparator()).append("  warning  ").append(warning);
        }
        return sb.toString();
    }
}
