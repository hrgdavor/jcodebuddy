package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The body-level type-use table: {@code .jcodebuddy/index/type-uses.json}. */
public record TypeUsesIndex(String module, List<TypeUseRecord> uses) {

    public static final String FILE_NAME = "type-uses.json";
    public static final int FORMAT = 1;

    public TypeUsesIndex {
        module = module == null ? "" : module;
        uses = uses == null ? List.of() : List.copyOf(uses);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"module\": \"").append(MetadataJson.escape(module)).append("\",\n");
        out.append("  \"uses\": [");
        for (int i = 0; i < uses.size(); i++) {
            TypeUseRecord use = uses.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"owner\": \"").append(MetadataJson.escape(use.ownerFqn()))
                    .append("\", \"member\": \"").append(MetadataJson.escape(use.member()))
                    .append("\", \"target\": \"").append(MetadataJson.escape(use.target()))
                    .append("\", \"kind\": \"").append(MetadataJson.escape(use.kind()))
                    .append("\", \"line\": ").append(use.line()).append(" }");
        }
        if (!uses.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n}\n");
        return out.toString();
    }

    public static TypeUsesIndex fromJson(String text) {
        JsonNode root = MetadataJson.mapper().readTree(text);
        List<TypeUseRecord> uses = new ArrayList<>();
        for (JsonNode node : root.path("uses")) {
            uses.add(new TypeUseRecord(node.path("owner").asText(""), node.path("member").asText(""),
                    node.path("target").asText(""), node.path("kind").asText(""),
                    node.path("line").asInt(-1)));
        }
        return new TypeUsesIndex(root.path("module").asText(""), uses);
    }

    public void write(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static TypeUsesIndex read(Path indexDir) throws IOException {
        return fromJson(Files.readString(indexDir.resolve(FILE_NAME), StandardCharsets.UTF_8));
    }
}
