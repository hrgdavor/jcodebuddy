// {@link AutomationEngine.AnalysisResult} Result of analyzing a source file.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Record representing the result of analyzing a source file.
 * 
 * {@link AutomationEngine} uses this to report analysis outcomes.
 */
public record AnalysisResult(
    Path sourceFile,
    Map<String, Object> analysis,
    List<String> issues,
    long durationMs
) {}
