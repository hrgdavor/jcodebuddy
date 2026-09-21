package hr.hrg.rewrite.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.util.List;

/**
 * OpenRewrite-based wrapper for generating validation rules.
 *
 * <p>This class maintains the JavaParser API while internally using OpenRewrite for AST manipulation.
 * It generates validation rules for entities.</p>
 */
public final class OpenRewriteValidationGenerator {

    private OpenRewriteValidationGenerator() {
    }

    /**
     * Generates validation rules for an entity.
     *
     * @param sourceRoot the source root
     * @param packageName the entity's package
     * @param entityName the entity name
     * @param properties the entity properties
     * @return the generated validation rules
     */
    public static String generateValidationRules(
            Path sourceRoot, 
            String packageName, 
            String entityName, 
            List<Property> properties) {
        
        StringBuilder sb = new StringBuilder();

        // Add header
        String fqn = packageName == null || packageName.isBlank() 
                ? entityName 
                : packageName + "." + entityName;
        sb.append("// {@link ").append(fqn).append("} Validation rules.\n");
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

        // Add validation methods
        sb.append("/**\n");
        sb.append(" * Validates the entity fields.\n");
        sb.append(" */\n");
        sb.append("public static void validate(").append(entityName).append(" entity) {\n");
        
        for (Property property : properties) {
            if (property.constraints() != null && !property.constraints().isEmpty()) {
                sb.append("    // Validate ").append(property.name()).append("\n");
                for (Property.Constraint constraint : property.constraints()) {
                    sb.append("    if (entity.").append(property.name()).append(" == null) {\n");
                    sb.append("        throw new IllegalArgumentException(\n");
                    sb.append("            \"").append(property.name()).append(" cannot be null\");\n");
                    sb.append("    }\n");
                }
            }
        }
        
        sb.append("}\n\n");

        // Add field constraint annotations
        sb.append("/**\n");
        sb.append(" * Field constraint annotations.\n");
        sb.append(" */\n");
        sb.append("@interface FieldConstraint {\n");
        sb.append("    String field();\n");
        sb.append("    String message();\n");
        sb.append("}")\n\n");

        return sb.toString();
    }

    /**
     * Gets the imports needed for validation.
     */
    public static List<String> importsFor(List<Property> properties) {
        return properties.stream()
                .map(p -> "hr.hrg.hipster.entity.tooling.validation." + p.name())
                .toList();
    }

    /**
     * Constraint type for field validation.
     */
    public interface Constraint {
        String getField();
        String getMessage();
    }

    /**
     * Property with constraint information.
     */
    public static class PropertyWithConstraints extends Property {
        private List<Constraint> constraints;

        public PropertyWithConstraints(String name, String type, List<Constraint> constraints) {
            super(name, type);
            this.constraints = constraints;
        }

        public List<Constraint> getConstraints() {
            return constraints;
        }
    }
}
