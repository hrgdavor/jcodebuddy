package hr.hrg.jcodebuddy.tool;

import java.util.List;
import java.util.Map;

/**
 * A tool's proposed work: one or more files, with the metadata a reviewer needs.
 *
 * <p>{@code summary} is the one-line semantic explanation shown in a batch catalog; {@code details}
 * are the optional bullet lines shown in the review page. Both are tool-authored because the tool knows
 * what it changed (for example "removed 2 components, added 1") while a text diff only knows lines.</p>
 */
public record ToolProposal(String toolId, String title, String version, List<FileProposal> files,
                           Map<String, Object> metadata, String summary, List<String> details) {

    public ToolProposal {
        files = files == null ? List.of() : List.copyOf(files);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        summary = summary == null ? "" : summary;
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** Back-compatible constructor for tools that do not (yet) describe their change. */
    public ToolProposal(String toolId, String title, String version, List<FileProposal> files,
                        Map<String, Object> metadata) {
        this(toolId, title, version, files, metadata, "", List.of());
    }

    public boolean empty() {
        return files.isEmpty();
    }
}
