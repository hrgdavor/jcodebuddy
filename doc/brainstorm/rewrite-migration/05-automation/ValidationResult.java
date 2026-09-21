// {@link AutomationEngine.ValidationResult} Result of validating a source file.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.nio.file.Path;
import java.util.List;

/**
 * Record representing the result of validating a source file.
 * 
 * {@link AutomationEngine} uses this to report validation outcomes.
 */
public record ValidationResult(
    Path sourceFile,
    boolean isValid,
    List<String> errors,
    List<String> warnings,
    long durationMs
) {}
