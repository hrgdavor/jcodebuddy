// {@link hr.hrg.rewrite.validation.AnalysisResult} Result of an analysis operation.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the result of an analysis operation.
 *
 * <p>Compliance: DEC-021 (generator class-file header), DEC-022 (refactor-sensitive naming).</p>
 */
public class AnalysisResult {

    private final Map<String, Object> analysis;

    public AnalysisResult() {
        this(new HashMap<>());
    }

    public AnalysisResult(Map<String, Object> analysis) {
        this.analysis = new HashMap<>(analysis != null ? analysis : new HashMap<>());
    }

    public Map<String, Object> getAnalysis() {
        return new HashMap<>(analysis);
    }

    public AnalysisResult add(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(analysis);
        copy.put(key, value);
        return new AnalysisResult(copy);
    }

    public static AnalysisResult ok(Map<String, Object> analysis) {
        return new AnalysisResult(analysis);
    }

    @Override
    public String toString() {
        return "AnalysisResult(analysis=" + analysis + ")";
    }
}
