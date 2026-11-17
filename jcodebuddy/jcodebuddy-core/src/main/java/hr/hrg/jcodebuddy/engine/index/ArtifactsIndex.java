package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The extended artifact table: {@code .jcodebuddy/index/artifacts.json}.
 *
 * <p>Kept separate from {@code classes.json} because it is derived from the whole pass, not from one file's
 * bytes, and because not every project generates code. A reader that does not know the format must refuse it
 * rather than treat the project as having no artifacts.</p>
 */
public record ArtifactsIndex(String module, List<ArtifactRecord> artifacts) {

    public static final String FILE_NAME = "artifacts.json";
    public static final int FORMAT = 1;

    public ArtifactsIndex {
        module = module == null ? "" : module;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static ArtifactsIndex empty(String module) {
        return new ArtifactsIndex(module, List.of());
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"module\": \"").append(MetadataJson.escape(module)).append("\",\n");
        out.append("  \"artifacts\": [");
        for (int i = 0; i < artifacts.size(); i++) {
            ArtifactRecord artifact = artifacts.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"type\": \"").append(MetadataJson.escape(artifact.type()))
                    .append("\", \"path\": \"").append(MetadataJson.escape(artifact.path()))
                    .append("\", \"generator\": \"").append(MetadataJson.escape(artifact.generator()))
                    .append("\", \"version\": \"").append(MetadataJson.escape(artifact.version()))
                    .append("\", \"optionsHash\": \"").append(MetadataJson.escape(artifact.optionsHash()))
                    .append("\", \"inputs\": ");
            appendStrings(out, artifact.inputs());
            out.append(", \"options\": ");
            appendStrings(out, artifact.options());
            out.append(" }");
        }
        if (!artifacts.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n}\n");
        return out.toString();
    }

    public static ArtifactsIndex fromJson(String text) {
        JsonNode root = MetadataJson.mapper().readTree(text);
        List<ArtifactRecord> artifacts = new ArrayList<>();
        for (JsonNode node : root.path("artifacts")) {
            artifacts.add(new ArtifactRecord(node.path("type").asText(""),
                    node.path("path").asText(""), node.path("generator").asText(""),
                    node.path("version").asText(""), strings(node.path("inputs")),
                    strings(node.path("options")), node.path("optionsHash").asText("")));
        }
        return new ArtifactsIndex(root.path("module").asText(""), artifacts);
    }

    public void write(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static ArtifactsIndex read(Path indexDir) throws IOException {
        return fromJson(Files.readString(indexDir.resolve(FILE_NAME), StandardCharsets.UTF_8));
    }

    private static void appendStrings(StringBuilder out, List<String> values) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('"').append(MetadataJson.escape(values.get(i))).append('"');
        }
        out.append(']');
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asText(""));
        }
        return List.copyOf(values);
    }
}
