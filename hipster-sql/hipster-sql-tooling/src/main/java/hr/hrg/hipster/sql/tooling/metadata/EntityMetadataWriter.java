package hr.hrg.hipster.sql.tooling.metadata;

import hr.hrg.hipster.sql.tooling.model.AnnotationModel;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;
import hr.hrg.jcodebuddy.engine.MetadataJson;
import hr.hrg.jcodebuddy.engine.index.ContentHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes the entity metadata document: the model a project-understanding UI or report reads.
 *
 * <p>This is the first slice of the schema in the plan. It already contains the parts a renderer
 * needs to start — identity, source, source hash, table, fields with ordinals, and the artifact
 * inventory with relative file names — and it deliberately contains no source text. Options,
 * supertypes, locations and the full artifact set grow here as the emitters do.</p>
 */
public final class EntityMetadataWriter {

    private EntityMetadataWriter() {
    }

    /** Writes {@code <fqn-path>.metadata.json} atomically. */
    public static void write(EntityModel entity, Path sourceRoot, Path metadataDir, Path javaOut,
                             boolean genUpdate, boolean genBuilder, boolean genMeta,
                             boolean genJson, boolean genMongo, boolean genAnnotations)
            throws IOException {
        Path file = fileFor(metadataDir, entity.fqn());
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, render(entity, sourceRoot, javaOut, genUpdate, genBuilder, genMeta,
                genJson, genMongo, genAnnotations), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The manifest: the entry point a UI reads to find every entity document. */
    public static void writeManifest(List<EntityModel> entities, Path metadataDir, Path javaOut,
                                     boolean genUpdate, boolean genBuilder, boolean genMeta)
            throws IOException {
        Files.createDirectories(metadataDir);
        Path file = metadataDir.resolve("manifest.json");
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, renderManifest(entities, javaOut, genUpdate, genBuilder, genMeta),
                StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stable per-entity JSON text, used by both the writer and {@code --check}. */
    public static String render(EntityModel entity, Path sourceRoot, Path javaOut,
                                boolean genUpdate, boolean genBuilder, boolean genMeta,
                                boolean genJson, boolean genMongo, boolean genAnnotations)
            throws IOException {
        String hash = ContentHash.of(sourceRoot.resolve(entity.sourcePath()));
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": 1,\n");
        out.append("  \"entity\": ").append(quote(entity.fqn())).append(",\n");
        out.append("  \"package\": ").append(quote(entity.packageName())).append(",\n");
        out.append("  \"kind\": \"interface\",\n");
        out.append("  \"source\": ").append(quote(entity.sourcePath())).append(",\n");
        out.append("  \"sourceHash\": { \"algo\": \"").append(ContentHash.ALGO)
                .append("\", \"normalize\": \"").append(ContentHash.NORMALIZE)
                .append("\", \"value\": ").append(quote(hash)).append(" },\n");
        out.append("  \"table\": ").append(quote(entity.tableName())).append(",\n");
        PropertyModel primary = entity.primary();
        out.append("  \"primary\": ")
                .append(primary == null ? "null" : quote(primary.name())).append(",\n");
        out.append("  \"location\": ");
        appendLocation(out, sourceRoot, entity, entity.simpleName());
        out.append(",\n");
        out.append("  \"options\": { \"genVisitor\": true")
                .append(", \"genUpdate\": ").append(genUpdate)
                .append(", \"genBuilder\": ").append(genBuilder)
                .append(", \"genMeta\": ").append(genMeta)
                .append(", \"genJson\": ").append(genJson)
                .append(", \"genMongo\": ").append(genMongo)
                .append(", \"genAnnotations\": ").append(genAnnotations)
                .append(" },\n");
        out.append("  \"supertypes\": ");
        appendArray(out, entity.supertypes());
        out.append(",\n");
        out.append("  \"inputs\": ");
        List<String> inputs = new ArrayList<>();
        inputs.add(entity.fqn());
        inputs.addAll(entity.supertypes());
        appendArray(out, inputs);
        out.append(",\n");
        out.append("  \"properties\": [");
        List<PropertyModel> ordered = new ArrayList<>(entity.properties());
        ordered.sort(Comparator.comparingInt(PropertyModel::ordinal));
        for (int i = 0; i < ordered.size(); i++) {
            PropertyModel property = ordered.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"name\": ").append(quote(property.name()))
                    .append(", \"ordinal\": ").append(property.ordinal())
                    .append(", \"type\": ").append(quote(property.typeText()))
                    .append(", \"getter\": ").append(quote(property.getterName()))
                    .append(", \"transient\": ").append(property.transient_())
                    .append(", \"keepRest\": ").append(property.keepRest())
                    .append(", \"primary\": ").append(property.primary())
                    .append(", \"initial\": ").append(quote(property.initial()))
                    .append(", \"location\": ");
            appendLocation(out, sourceRoot, entity, property.getterName());
            out.append(", \"annotations\": [");
            for (int j = 0; j < property.annotations().size(); j++) {
                if (j > 0) {
                    out.append(", ");
                }
                AnnotationModel annotation = property.annotations().get(j);
                out.append("{ \"name\": ").append(quote(annotation.name()))
                        .append(", \"arguments\": [");
                for (int k = 0; k < annotation.arguments().size(); k++) {
                    if (k > 0) {
                        out.append(", ");
                    }
                    out.append(quote(annotation.arguments().get(k)));
                }
                out.append("] }");
            }
            out.append("] }");
        }
        if (!ordered.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("],\n");
        out.append("  \"skippedAccessors\": [");
        for (int i = 0; i < entity.skipped().size(); i++) {
            PropertyModel skipped = entity.skipped().get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"name\": ").append(quote(skipped.name()))
                    .append(", \"type\": ").append(quote(skipped.typeText()))
                    .append(", \"getter\": ").append(quote(skipped.getterName()))
                    .append(" }");
        }
        if (!entity.skipped().isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("],\n");
        out.append("  \"artifacts\": [");
        List<String> artifacts = artifactNames(entity, genUpdate, genBuilder, genMeta);
        for (int i = 0; i < artifacts.size(); i++) {
            String name = artifacts.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"name\": ").append(quote(name))
                    .append(", \"kind\": \"").append(name.endsWith("Visitor") ? "interface" : "class")
                    .append("\", \"file\": ")
                    .append(quote(artifactFile(entity, name, javaOut)))
                    .append(", \"generated\": true }");
        }
        if (!artifacts.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n");
        out.append("}\n");
        return out.toString();
    }

    /** The stable manifest text. */
    public static String renderManifest(List<EntityModel> entities, Path javaOut,
                                        boolean genUpdate, boolean genBuilder, boolean genMeta) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": 1,\n");
        out.append("  \"entities\": [");
        for (int i = 0; i < entities.size(); i++) {
            EntityModel entity = entities.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    { \"fqn\": ").append(quote(entity.fqn()))
                    .append(", \"metadata\": ").append(quote(metadataPath(entity.fqn())))
                    .append(", \"source\": ").append(quote(entity.sourcePath()))
                    .append(", \"artifacts\": [");
            List<String> artifacts = artifactNames(entity, genUpdate, genBuilder, genMeta);
            for (int j = 0; j < artifacts.size(); j++) {
                if (j > 0) {
                    out.append(", ");
                }
                out.append(quote(artifacts.get(j)));
            }
            out.append("] }");
        }
        if (!entities.isEmpty()) {
            out.append('\n').append("  ");
        }
        out.append("]\n");
        out.append("}\n");
        return out.toString();
    }

    private record Location(String file, int line, int column) {
    }

    /** The first source line containing the member, as a UI link target. */
    private static void appendLocation(StringBuilder out, Path sourceRoot, EntityModel entity,
                                       String member) throws IOException {
        Path source = sourceRoot.resolve(entity.sourcePath());
        if (!Files.isRegularFile(source)) {
            out.append("null");
            return;
        }
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        Location location = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int index = line.contains(member + "(")
                    ? line.indexOf(member + "(")
                    : line.contains(member) ? line.indexOf(member) : -1;
            if (index >= 0) {
                location = new Location(entity.sourcePath(), i + 1, index + 1);
                break;
            }
        }
        if (location == null) {
            out.append("null");
            return;
        }
        out.append("{ \"file\": ").append(quote(location.file()))
                .append(", \"line\": ").append(location.line())
                .append(", \"column\": ").append(location.column()).append(" }");
    }

    /** The artifacts this pass emitted, in a stable order. */
    private static List<String> artifactNames(EntityModel entity, boolean genUpdate,
                                              boolean genBuilder, boolean genMeta) {
        List<String> names = new ArrayList<>();
        names.add(entity.visitorName());
        names.add(entity.simpleName() + "Immutable");
        if (genBuilder) {
            names.add(entity.simpleName() + "Builder");
            names.add(entity.simpleName() + "Update");
        } else if (genUpdate) {
            names.add(entity.simpleName() + "Update");
        }
        if (genMeta) {
            names.add(entity.simpleName() + "Meta");
        }
        return names;
    }

    public static Path fileFor(Path metadataDir, String fqn) {
        return metadataDir.resolve(fqn.replace('.', '/') + ".metadata.json");
    }

    private static String metadataPath(String fqn) {
        return fqn.replace('.', '/') + ".metadata.json";
    }

    private static String artifactFile(EntityModel entity, String artifactName, Path javaOut) {
        Path target = javaOut
                .resolve(entity.packageName().replace('.', '/'))
                .resolve(artifactName + ".java");
        return javaOut.relativize(target).toString().replace('\\', '/');
    }

    private static void appendArray(StringBuilder out, List<String> values) {
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(quote(values.get(i)));
        }
        out.append(']');
    }

    private static String quote(String value) {
        return "\"" + MetadataJson.escape(value) + "\"";
    }
}
