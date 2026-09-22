package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;

/**
 * What running one {@link Transformation} over one file produced.
 *
 * <h3>Three outcomes, not two</h3>
 * <p>{@code succeeded} says whether the transformation ran; {@code changed} says whether it produced
 * different text. They are separate because the useful report distinguishes them: "ran and changed
 * nothing" is the normal result of a second pass (every generator in this repository is idempotent by
 * design), while "failed" is the one line a reader must act on. A single boolean would make a no-op run
 * look like a failure or a failure look like a no-op — and the migration this phase belongs to has both
 * recorded as real defects.</p>
 *
 * @param inputFile        the file that was read
 * @param transformationName the transformation that ran
 * @param succeeded        whether it completed; when false, {@code error} says why and {@code output} is
 *                         the original text
 * @param changed          whether the output differs from the input
 * @param output           the text that should replace the file; always non-null, so a caller may use it
 *                         without checking {@code succeeded} first
 * @param error            the failure message, or {@code null}
 * @param durationMs       how long the transformation took, in milliseconds
 */
public record TransformationResult(Path inputFile, String transformationName, boolean succeeded,
                                   boolean changed, String output, String error, long durationMs) {

    /**
     * A successful run.
     *
     * <p>{@code changed} is computed here rather than passed in: deriving it from the two texts is the
     * only way it cannot disagree with them.</p>
     */
    public static TransformationResult success(Path inputFile, String transformationName, String input,
                                               String output, long durationMs) {
        return new TransformationResult(inputFile, transformationName, true,
                !input.equals(output), output, null, durationMs);
    }

    /** A failed run. The output is the untouched input, so a caller that writes results blindly still writes nothing new. */
    public static TransformationResult failure(Path inputFile, String transformationName, String input,
                                              String error, long durationMs) {
        return new TransformationResult(inputFile, transformationName, false, false, input, error, durationMs);
    }

    /** The failure message and the input text, for a report line. */
    public String render() {
        if (succeeded) {
            return (changed ? "changed    " : "unchanged  ") + inputFile + " (" + transformationName
                    + ", " + durationMs + " ms)";
        }
        return "failed     " + inputFile + " (" + transformationName + "): " + error;
    }
}
