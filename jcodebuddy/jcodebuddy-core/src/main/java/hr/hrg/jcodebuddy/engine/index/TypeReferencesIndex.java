package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The resolved type-reference table: {@code .jcodebuddy/index/type-refs.json}. */
public record TypeReferencesIndex(String module, List<TypeReferenceSite> sites) {

    public static final String FILE_NAME = "type-refs.json";
    public static final int FORMAT = 1;

    public TypeReferencesIndex {
        module = module == null ? "" : module;
        sites = sites == null ? List.of() : List.copyOf(sites);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"module\": \"").append(MetadataJson.escape(module)).append("\",\n");
        out.append("  \"sites\": [");
        for (int i = 0; i < sites.size(); i++) {
            TypeReferenceSite site = sites.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"owner\": \"").append(MetadataJson.escape(site.ownerFqn()))
                    .append("\", \"member\": \"").append(MetadataJson.escape(site.member()))
                    .append("\", \"role\": \"").append(MetadataJson.escape(site.role()))
                    .append("\", \"reference\": ");
            appendReference(out, site.reference());
            out.append(" }");
        }
        if (!sites.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n}\n");
        return out.toString();
    }

    public static TypeReferencesIndex fromJson(String text) {
        JsonNode root = MetadataJson.mapper().readTree(text);
        List<TypeReferenceSite> sites = new ArrayList<>();
        for (JsonNode node : root.path("sites")) {
            sites.add(new TypeReferenceSite(node.path("owner").asText(""),
                    node.path("member").asText(""), node.path("role").asText(""),
                    readReference(node.path("reference"))));
        }
        return new TypeReferencesIndex(root.path("module").asText(""), sites);
    }

    public void write(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static TypeReferencesIndex read(Path indexDir) throws IOException {
        return fromJson(Files.readString(indexDir.resolve(FILE_NAME), StandardCharsets.UTF_8));
    }

    private static void appendReference(StringBuilder out, TypeReference reference) {
        out.append("{ \"written\": \"").append(MetadataJson.escape(reference.written()))
                .append("\", \"raw\": \"").append(MetadataJson.escape(reference.raw()))
                .append("\", \"status\": \"").append(reference.status().name())
                .append("\", \"via\": \"").append(MetadataJson.escape(reference.via()))
                .append("\", \"resolved\": ");
        if (reference.resolvedFqn() == null) {
            out.append("null");
        } else {
            out.append('"').append(MetadataJson.escape(reference.resolvedFqn())).append('"');
        }
        out.append(", \"candidates\": [");
        for (int i = 0; i < reference.candidates().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('"').append(MetadataJson.escape(reference.candidates().get(i))).append('"');
        }
        out.append("], \"arguments\": [");
        for (int i = 0; i < reference.arguments().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            appendReference(out, reference.arguments().get(i));
        }
        out.append("] }");
    }

    private static TypeReference readReference(JsonNode node) {
        List<TypeReference> arguments = new ArrayList<>();
        for (JsonNode argument : node.path("arguments")) {
            arguments.add(readReference(argument));
        }
        List<String> candidates = new ArrayList<>();
        for (JsonNode candidate : node.path("candidates")) {
            candidates.add(candidate.asText(""));
        }
        String resolved = node.path("resolved").isNull() ? null : node.path("resolved").asText(null);
        return new TypeReference(node.path("written").asText(""), node.path("raw").asText(""),
                arguments, resolved, TypeReference.Resolution.valueOf(node.path("status").asText("UNRESOLVED")),
                node.path("via").asText(""), candidates, null);
    }
}
