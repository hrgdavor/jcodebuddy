// {@link hr.hrg.rewrite.validation.ValidationResult} Result of a validation operation.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of a validation operation.
 *
 * <p>Compliance: DEC-021 (generator class-file header), DEC-022 (refactor-sensitive naming).</p>
 */
public class ValidationResult {

    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;

    public ValidationResult(boolean valid) {
        this(valid, new ArrayList<>(), new ArrayList<>());
    }

    public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        this.valid = valid;
        this.errors = new ArrayList<>(errors != null ? errors : List.of());
        this.warnings = new ArrayList<>(warnings != null ? warnings : List.of());
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }

    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true);
    }

    public static ValidationResult error(String message) {
        return new ValidationResult(false, List.of(message), List.of());
    }

    public static ValidationResult warning(String message) {
        return new ValidationResult(true, List.of(), List.of(message));
    }

    public static ValidationResult errors(List<String> messages) {
        return new ValidationResult(false, messages, List.of());
    }

    public static ValidationResult warnings(List<String> messages) {
        return new ValidationResult(true, List.of(), messages);
    }

    @Override
    public String toString() {
        if (valid) {
            if (warnings.isEmpty()) {
                return "ValidationResult(ok)";
            } else {
                return "ValidationResult(ok, warnings=" + warnings + ")";
            }
        } else {
            return "ValidationResult(error, errors=" + errors + ")";
        }
    }
}
