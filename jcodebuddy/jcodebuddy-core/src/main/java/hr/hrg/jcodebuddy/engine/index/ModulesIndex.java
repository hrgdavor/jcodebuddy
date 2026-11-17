package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The module table: {@code .jcodebuddy/index/modules.json}. */
public record ModulesIndex(String module, List<ModuleRecord> modules) {

    public static final String FILE_NAME = "modules.json";
    public static final int FORMAT = 1;

    public ModulesIndex {
        module = module == null ? "" : module;
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"module\": \"").append(MetadataJson.escape(module)).append("\",\n");
        out.append("  \"modules\": [");
        for (int i = 0; i < modules.size(); i++) {
            ModuleRecord mod = modules.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"name\": \"").append(MetadataJson.escape(mod.name()))
                    .append("\", \"path\": \"").append(MetadataJson.escape(mod.path()))
                    .append("\", \"checksum\": \"").append(MetadataJson.escape(mod.checksum()))
                    .append("\", \"requires\": ");
            appendStrings(out, mod.requires());
            out.append(", \"exports\": ");
            appendStrings(out, mod.exports());
            out.append(", \"opens\": ");
            appendStrings(out, mod.opens());
            out.append(", \"uses\": ");
            appendStrings(out, mod.uses());
            out.append(", \"provides\": ");
            appendStrings(out, mod.provides());
            out.append(" }");
        }
        if (!modules.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n}\n");
        return out.toString();
    }

    public static ModulesIndex fromJson(String text) {
        JsonNode root = MetadataJson.mapper().readTree(text);
        List<ModuleRecord> modules = new ArrayList<>();
        for (JsonNode node : root.path("modules")) {
            modules.add(new ModuleRecord(node.path("name").asText(""), node.path("path").asText(""),
                    node.path("checksum").asText(""), strings(node.path("requires")),
                    strings(node.path("exports")), strings(node.path("opens")),
                    strings(node.path("uses")), strings(node.path("provides"))));
        }
        return new ModulesIndex(root.path("module").asText(""), modules);
    }

    public void write(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static ModulesIndex read(Path indexDir) throws IOException {
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
