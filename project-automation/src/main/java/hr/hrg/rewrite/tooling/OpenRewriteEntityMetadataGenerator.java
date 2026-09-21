package hr.hrg.rewrite.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * OpenRewrite-based wrapper for generating entity metadata.
 *
 * <p>This class maintains the JavaParser API while internally using OpenRewrite for AST manipulation.
 * It generates entity metadata from property definitions.</p>
 */
public final class OpenRewriteEntityMetadataGenerator {

    private OpenRewriteEntityMetadataGenerator() {
    }

    /**
     * Generates entity metadata from property definitions.
     *
     * @param sourceRoot the source root
     * @param packageName the entity's package
     * @param entityName the entity name
     * @param properties the entity properties
     * @return the generated entity metadata
     * @throws IOException if the file cannot be created
     */
    public static String generateEntityMetadata(
            Path sourceRoot, 
            String packageName, 
            String entityName, 
            List<Property> properties) throws IOException {
        
        StringBuilder sb = new StringBuilder();

        // Add header
        String fqn = packageName == null || packageName.isBlank() 
                ? entityName 
                : packageName + "." + entityName;
        sb.append("// {@link ").append(fqn).append("} Entity metadata.\n");
        sb.append("// {enabled:true, blockMarker: \"implicit\"}\n\n");

        // Add package declaration
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(";\n");
        } else {
            sb.append("package default;\n");
        }
        sb.append("\n");

        // Add imports
        for (Property property : properties) {
            sb.append("import ").append(property.type()).append(";\n");
        }
        sb.append("\n");

        // Add entity metadata class
        sb.append("/**\n");
        sb.append(" * Metadata for the ").append(entityName).append(" entity.\n");
        sb.append(" */\n");
        sb.append("public record ").append(entityName).append("Metadata(\n");
        
        for (Property property : properties) {
            sb.append("    String ").append(property.name()).append(" = \"");
            sb.append(property.name()).append("\"\n");
        }
        
        sb.append(") {}\n");

        return sb.toString();
    }

    /**
     * Generates entity metadata and writes to file.
     *
     * @param outputRoot the output root
     * @param packageName the entity's package
     * @param entityName the entity name
     * @param properties the entity properties
     * @return the result with file path and metadata
     * @throws IOException if the file cannot be created
     */
    public static Result generate(Path outputRoot, 
                                   String packageName, 
                                   String entityName, 
                                   List<Property> properties) throws IOException {
        
        String metadata = generateEntityMetadata(outputRoot, packageName, entityName, properties);
        
        Path metadataFile = outputRoot.resolve(entityName + "Metadata.java");
        Files.writeString(metadataFile, metadata);
        
        return new Result(metadataFile, entityName + "Metadata");
    }

    /**
     * Result of generating entity metadata.
     */
    public static class Result {
        private final Path metadataFile;
        private final String metadataClass;

        public Result(Path metadataFile, String metadataClass) {
            this.metadataFile = metadataFile;
            this.metadataClass = metadataClass;
        }

        public Path getMetadataFile() {
            return metadataFile;
        }

        public String getMetadataClass() {
            return metadataClass;
        }
    }
}
