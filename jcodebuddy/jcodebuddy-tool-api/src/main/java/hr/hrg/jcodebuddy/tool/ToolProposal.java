package hr.hrg.jcodebuddy.tool;

import java.util.List;
import java.util.Map;

/** A tool's proposed work: one or more files, with the metadata a reviewer needs. */
public record ToolProposal(String toolId, String title, String version, List<FileProposal> files,
                           Map<String, Object> metadata) {

    public ToolProposal {
        files = files == null ? List.of() : List.copyOf(files);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean empty() {
        return files.isEmpty();
    }
}
