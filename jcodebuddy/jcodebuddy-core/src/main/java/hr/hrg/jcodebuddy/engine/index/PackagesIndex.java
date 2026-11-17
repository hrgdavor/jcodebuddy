package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The package/module table: {@code .jcodebuddy/index/packages.json}. */
public record PackagesIndex(String module, List<PackageRecord> packages) {

    public static final String FILE_NAME = "packages.json";
    public static final int FORMAT = 1;

    public PackagesIndex {
        module = module == null ? "" : module;
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"format\": ").append(FORMAT).append(",\n");
        out.append("  \"module\": \"").append(MetadataJson.escape(module)).append("\",\n");
        out.append("  \"packages\": [");
        for (int i = 0; i < packages.size(); i++) {
            PackageRecord pkg = packages.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"name\": \"").append(MetadataJson.escape(pkg.name()))
                    .append("\", \"path\": \"").append(MetadataJson.escape(pkg.path()))
                    .append("\", \"checksum\": \"").append(MetadataJson.escape(pkg.checksum()))
                    .append("\", \"line\": ").append(pkg.line())
                    .append(", \"module\": ");
            if (pkg.module() == null) {
                out.append("null");
            } else {
                out.append('"').append(MetadataJson.escape(pkg.module())).append('"');
            }
            out.append(", \"annotations\": [");
            for (int j = 0; j < pkg.annotations().size(); j++) {
                if (j > 0) {
                    out.append(", ");
                }
                TypeAnnotation annotation = pkg.annotations().get(j);
                out.append("{ \"name\": \"").append(MetadataJson.escape(annotation.name()))
                        .append("\", \"arguments\": [");
                for (int k = 0; k < annotation.arguments().size(); k++) {
                    if (k > 0) {
                        out.append(", ");
                    }
                    out.append('"').append(MetadataJson.escape(annotation.arguments().get(k)))
                            .append('"');
                }
                out.append("] }");
            }
            out.append("] }");
        }
        if (!packages.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n}\n");
        return out.toString();
    }

    public static PackagesIndex fromJson(String text) {
        JsonNode root = MetadataJson.mapper().readTree(text);
        List<PackageRecord> packages = new ArrayList<>();
        for (JsonNode node : root.path("packages")) {
            List<TypeAnnotation> annotations = new ArrayList<>();
            for (JsonNode annotation : node.path("annotations")) {
                List<String> arguments = new ArrayList<>();
                for (JsonNode argument : annotation.path("arguments")) {
                    arguments.add(argument.asText(""));
                }
                annotations.add(new TypeAnnotation(annotation.path("name").asText(""), arguments, null));
            }
            String module = node.path("module").isNull() ? null : node.path("module").asText(null);
            packages.add(new PackageRecord(node.path("name").asText(""), node.path("path").asText(""),
                    node.path("checksum").asText(""), node.path("line").asInt(-1), annotations, module));
        }
        return new PackagesIndex(root.path("module").asText(""), packages);
    }

    public void write(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static PackagesIndex read(Path indexDir) throws IOException {
        return fromJson(Files.readString(indexDir.resolve(FILE_NAME), StandardCharsets.UTF_8));
    }
}
