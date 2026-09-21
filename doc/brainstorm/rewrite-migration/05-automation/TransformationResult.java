// {@link AutomationEngine.TransformationResult} Result of applying a transformation to a file.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.nio.file.Path;

/**
 * Record representing the result of applying a transformation to a source file.
 * 
 * {@link AutomationEngine} uses this to report transformation outcomes.
 */
public record TransformationResult(
    Path inputFile,
    String transformationName,
    boolean succeeded,
    String output,
    String error,
    long durationMs
) {
    /**
     * Creates a new TransformationResult indicating success.
     */
    public static TransformationResult success(Path inputFile, String transformationName, String output, long durationMs) {
        return new TransformationResult(inputFile, transformationName, true, output, null, durationMs);
    }
    
    /**
     * Creates a new TransformationResult indicating failure.
     */
    public static TransformationResult failure(Path inputFile, String transformationName, String error, long durationMs) {
        return new TransformationResult(inputFile, transformationName, false, null, error, durationMs);
    }
}
