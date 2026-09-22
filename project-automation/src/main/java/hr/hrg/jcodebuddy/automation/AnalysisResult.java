package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Facts read out of one source file, and anything about it worth flagging.
 *
 * <p>The counts are <strong>read from the tree, not counted by pattern-matching the text</strong>. That
 * distinction is the whole reason this class takes an OpenRewrite unit rather than a {@code String}:
 * the plan this phase implements sketched {@code countMethods} as a regular expression over the source,
 * and that is the approach Phase 6 spent a whole migration removing — the same technique reported three
 * correct interfaces as broken in {@code AddonAndInheritanceTest}, and the same one that cannot tell a
 * method from a control-flow statement, a class from a comment, or an annotation from an annotation-like
 * string literal.</p>
 *
 * @param sourceFile  the file that was analysed
 * @param analysis    the facts, keyed by a stable name ({@code package}, {@code typeCount},
 *                    {@code methodCount}, {@code totalLines}, …)
 * @param issues      anything worth a reader's attention; advisory, so it never fails a run
 * @param durationMs  how long analysis took, in milliseconds
 */
public record AnalysisResult(Path sourceFile, Map<String, Object> analysis, List<String> issues,
                             long durationMs) {

    /** The key names {@link #analysis} uses, so a consumer never has to repeat a string literal. */
    public static final String PACKAGE = "package";
    public static final String TYPE_COUNT = "typeCount";
    public static final String CLASS_COUNT = "classCount";
    public static final String INTERFACE_COUNT = "interfaceCount";
    public static final String ENUM_COUNT = "enumCount";
    public static final String RECORD_COUNT = "recordCount";
    public static final String ANNOTATION_COUNT = "annotationCount";
    public static final String METHOD_COUNT = "methodCount";
    public static final String IMPORT_COUNT = "importCount";
    public static final String TOTAL_LINES = "totalLines";
    public static final String CODE_LINES = "codeLines";
    public static final String COMMENT_LINES = "commentLines";
    public static final String BLANK_LINES = "blankLines";

    public AnalysisResult {
        analysis = analysis == null ? Map.of() : Map.copyOf(analysis);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** One comparable fact, or {@code null} when the analysis did not record it. */
    public Object get(String key) {
        return analysis.get(key);
    }

    /** One comparable fact as an {@code int}, or {@code -1} when it is absent. */
    public int count(String key) {
        Object value = analysis.get(key);
        return value instanceof Number number ? number.intValue() : -1;
    }

    /** One line per fact, then one per issue, for a report. */
    public String render() {
        StringBuilder sb = new StringBuilder("analysed   ").append(sourceFile)
                .append(" (").append(durationMs).append(" ms)");
        analysis.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(System.lineSeparator())
                        .append("  ").append(entry.getKey()).append(": ").append(entry.getValue()));
        for (String issue : issues) {
            sb.append(System.lineSeparator()).append("  issue    ").append(issue);
        }
        return sb.toString();
    }
}
