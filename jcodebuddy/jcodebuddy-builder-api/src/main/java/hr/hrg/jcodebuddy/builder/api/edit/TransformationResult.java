package hr.hrg.jcodebuddy.builder.api.edit;

import java.util.List;
import java.util.Map;

/**
 * The builder engine's result: surgical edits plus the relationship metadata a caller may use to explain them.
 *
 * <p>JCodeBuddy's copy, moved out of the frozen {@code java-watch-core}; see {@link CodeEdit}. The result is part of
 * the public engine signature used by the watch tools and the LSP sidecar, so it lives beside the edit value rather
 * than in the watcher.</p>
 */
public record TransformationResult(
        List<CodeEdit> edits,
        Map<String, String> metadata) {

    public static TransformationResult empty() {
        return new TransformationResult(List.of(), Map.of());
    }
}
