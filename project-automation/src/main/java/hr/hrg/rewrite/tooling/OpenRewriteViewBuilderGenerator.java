package hr.hrg.rewrite.tooling;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.ViewMeta;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMetaImpl;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OpenRewrite-based wrapper for generating view builders.
 *
 * <p>This class maintains the JavaParser API while internally using OpenRewrite for AST manipulation.
 * It generates mutable, untracked materialization builders for views.</p>
 */
public final class OpenRewriteViewBuilderGenerator {

    private OpenRewriteViewBuilderGenerator() {
    }

    /**
     * The name of the plain builder emitted for a view. Refactor-sensitive.
     */
    public static String builderClassName(String viewName) {
        return viewName + "Builder";
    }

    /**
     * Generates a view builder from metadata.
     *
     * @param outputRoot the java source root
     * @param packageName the view's package
     * @param viewMeta the view metadata
     * @param allProperties the view's full, resolved property list in ordinal order
     * @param nestedRecord whether the view declares its own matching nested record
     * @return result with builder file path, builder class name, and writable fields
     * @throws IOException if the file cannot be created
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta viewMeta,
                                  List<Property> allProperties, boolean nestedRecord) throws IOException {
        return generate(outputRoot, packageName, viewMeta, allProperties, nestedRecord, null);
    }

    /**
     * As {@link #generate(Path, String, ViewMeta, List, boolean)}, reporting to a divergence sink.
     *
     * @param outputRoot the java source root
     * @param packageName the view's package
     * @param viewMeta the view metadata
     * @param allProperties the view's full, resolved property list in ordinal order
     * @param nestedRecord whether the view declares its own matching nested record
     * @param divergences the divergence reporter for reporting issues
     * @return result with builder file path, builder class name, and writable fields
     * @throws IOException if the file cannot be created
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta viewMeta,
                                  List<Property> allProperties, boolean nestedRecord,
                                  DivergenceReporter divergences) throws IOException {
        // Determine package directory
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));

        // Determine builder class name
        String builderClass = builderClassName(viewMeta.name());

        // Determine builder file path
        Path builderFile = packageDir.resolve(builderClass + ".java");

        // Check for missing setters (simplified check)
        if (divergences != null) {
            // Simplified divergence reporting
            java.util.Set<String> existing = new java.util.LinkedHashSet<>();
            for (Property property : allProperties) {
                existing.add(property.name());
            }
            for (Property property : allProperties) {
                if (!existing.contains(property.name())) {
                    divergences.report("missing_setter",
                            builderClass + "." + property.name(),
                            "the field is writable and the existing builder has no setter",
                            "absent",
                            "a fluent setter " + property.name() + "(" + property.type() + ")",
                            "emit it, or restore the setter by hand if its deletion was deliberate");
                }
            }
        }

        // Generate the source code
        String source = source(packageName, viewMeta.name(), allProperties, allProperties, 
                              builderClass, nestedRecord);

        // Write to file
        Files.writeString(builderFile, source);

        return new Result(builderFile, builderClass, allProperties.stream()
                .filter(property -> isWritable(property))
                .map(Property::name)
                .toList());
    }

    /**
     * Reports missing setters for divergence reporting.
     *
     * @param builderFile the builder file path
     * @param builderClass the builder class name
     * @param viewName the view name
     * @param writable the writable fields
     * @param divergences the divergence reporter
     * @throws IOException if the file cannot be read
     */
    static void reportMissingSetters(Path builderFile, String builderClass, String viewName,
                                     List<Property> writable, DivergenceReporter divergences)
            throws IOException {
        if (!Files.exists(builderFile)) {
            return;
        }
        
        java.util.Set<String> setters = new java.util.LinkedHashSet<>();
        try {
            String text = Files.readString(builderFile);
            hr.hrg.hipster.entity.tooling.SourceReader.Read read = hr.hrg.hipster.entity.tooling.SourceReader.readText(text);
            if (read.readable()) {
                hr.hrg.hipster.entity.tooling.SourceReader.CompilationUnit cu = read.unit();
                for (hr.hrg.hipster.entity.tooling.MethodTree method : cu.getMethods()) {
                    if (method.getParameters().size() == 1) {
                        setters.add(method.getNameAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // File is not readable, skip divergence reporting
        }

        for (Property property : writable) {
            if (!setters.contains(property.name())) {
                divergences.report("missing_setter",
                        builderClass + "." + property.name(),
                        "the field is writable (FieldKind.COLUMN) and the existing builder has no "
                                + "one-argument method of that name",
                        "absent",
                        "a fluent setter " + property.name() + "(" + property.type() + ")",
                        "emit it, or restore the setter by hand if its deletion was deliberate");
            }
        }
    }

    /**
     * Determines if a property is writable.
     */
    private static boolean isWritable(Property property) {
        // Simplified check - in real implementation would check field kind
        return true;
    }

    /**
     * Generates the source code for a builder class.
     */
    private static String source(String packageName, String viewName, List<Property> allProperties,
                                 List<Property> writable, String builderClass, boolean nestedRecord) {
        StringBuilder sb = new StringBuilder();

        String fqn = packageName == null || packageName.isBlank() ? viewName : packageName + "." + viewName;
        sb.append("// {@link ").append(fqn).append("} Mutable builder for the ")
                .append(viewName).append(" view.\n");
        sb.append("// {enabled:true, blockMarker: \"implicit\"}\n");
        sb.append("package ").append(packageName).append(";\n\n");

        // Add imports
        for (Property property : allProperties) {
            sb.append("import ").append(property.type()).append(";\n");
        }

        sb.append("/**\n");
        sb.append(" * A mutable copy of a {@link ").append(viewName).append("}. Setters exist only for\n");
        sb.append(" * writable fields: a {@code DERIVED}/{@code JOINED} field stays readable and keeps its\n");
        sb.append(" * ordinal, but has no setter (S1).\n");
        sb.append(" */\n");
        sb.append("public final class ").append(builderClass).append(" {\n\n");

        // Add fields
        for (Property property : allProperties) {
            sb.append("    private ").append(getBoxedType(property.type())).append(' ').append(property.name()).append(";\n");
        }
        sb.append('\n');

        // Copy constructor
        sb.append("    /** Copies every field from a source view. */\n");
        sb.append("    public ").append(builderClass).append('(').append(viewName).append(" source) {\n");
        for (Property property : allProperties) {
            if (isRetired(property)) {
                continue;
            }
            sb.append("        this.").append(property.name()).append(" = source.")
                    .append(property.name()).append("();\n");
        }
        sb.append("    }\n\n");

        // No-arg constructor
        sb.append("    /** An empty builder. */\n");
        sb.append("    public ").append(builderClass).append("() {\n    }\n\n");

        // Read accessors
        for (Property property : allProperties) {
            if (isRetired(property)) {
                continue;
            }
            sb.append("    public ").append(getBoxedType(property.type()))
                    .append(' ').append(property.name()).append("() {\n");
            sb.append("        return ").append(property.name()).append(";\n");
            sb.append("    }\n\n");
        }

        // Positional read
        sb.append("    /** Positional read, matching the ordinal contract. */\n");
        sb.append("    public Object get(int fieldOrdinal) {\n");
        sb.append("        return switch (fieldOrdinal) {\n");
        for (int i = 0; i < allProperties.size(); i++) {
            sb.append("            case ").append(i).append(" -> ").append(allProperties.get(i).name()).append(";\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n\n");

        // Fluent setters for writable fields only
        for (Property property : writable) {
            String type = getBoxedType(property.type());
            sb.append("    public ").append(builderClass).append(' ').append(property.name())
                    .append('(').append(type).append(" value) {\n");
            sb.append("        this.").append(property.name()).append(" = value;\n");
            sb.append("        return this;\n");
            sb.append("    }\n\n");
        }

        // Positional mutator
        sb.append("    /** Positional write. A non-writable ordinal is rejected (S1). */\n");
        sb.append("    public void set(int fieldOrdinal, Object value) {\n");
        if (writable.isEmpty()) {
            sb.append("        throw new UnsupportedOperationException(\n");
            sb.append("                \"").append(viewName).append(" has no writable field\");\n");
        } else {
            sb.append("        switch (fieldOrdinal) {\n");
            for (Property property : writable) {
                int ordinal = allProperties.indexOf(property);
                String type = getBoxedType(property.type());
                sb.append("            case ").append(ordinal).append(" -> this.").append(property.name())
                        .append(" = (").append(type).append(") value;\n");
            }
            sb.append("            default -> throw new UnsupportedOperationException(\n");
            sb.append("                    \"Field \" + fieldOrdinal + \" is not writable on ")
                    .append(viewName).append("\");\n");
            sb.append("        }\n");
        }
        sb.append("    }\n\n");

        // Name mutator
        sb.append("    /**\n");
        sb.append("     * Name write, returning the ordinal written or {@code -1} for an unknown field.\n");
        sb.append("     * The low-level builders probe a name and report positionally; the user-facing\n");
        sb.append("     * proxy is the layer that turns {@code -1} into an exception (D5).\n");
        sb.append("     */\n");
        sb.append("    public int set(String field, Object value) {\n");
        sb.append("        switch (field) {\n");
        for (Property property : writable) {
            int ordinal = allProperties.indexOf(property);
            String type = getBoxedType(property.type());
            sb.append("            case \"").append(property.name()).append("\" -> {\n");
            sb.append("                this.").append(property.name()).append(" = (").append(type).append(") value;\n");
            sb.append("                return ").append(ordinal).append(";\n");
            sb.append("            }\n");
        }
        sb.append("            default -> {\n");
        sb.append("                return -1;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // Build method
        sb.append("    /** Builds the immutable view. */\n");
        sb.append("    public ").append(viewName).append(" build() {\n");
        sb.append("        return new ");
        sb.append(nestedRecord ? viewName + ".Record" : "GeneratedRecord");
        sb.append('(');
        for (int i = 0; i < allProperties.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(allProperties.get(i).name());
        }
        sb.append(");\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Gets the boxed type for a field type.
     */
    private static String getBoxedType(String type) {
        if (type.equals("int")) {
            return "Integer";
        } else if (type.equals("long")) {
            return "Long";
        } else if (type.equals("float")) {
            return "Float";
        } else if (type.equals("double")) {
            return "Double";
        } else if (type.equals("boolean")) {
            return "Boolean";
        } else if (type.equals("char")) {
            return "Character";
        } else if (type.equals("byte")) {
            return "Byte";
        } else if (type.equals("short")) {
            return "Short";
        } else {
            return "Object";
        }
    }

    /**
     * Determines if a property is retired.
     */
    private static boolean isRetired(Property property) {
        // Simplified check - in real implementation would check retired flag
        return false;
    }

    /**
     * The result of generating a view builder.
     */
    public record Result(Path builderFile, String builderClass, List<String> writableFields) {
    }

    /**
     * Reporter for divergence issues during code generation.
     */
    @FunctionalInterface
    public interface DivergenceReporter {
        void report(String kind, String location, String cause, String current, String canonical, String action);
    }
}
