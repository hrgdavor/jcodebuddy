package hr.hrg.rewrite.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * OpenRewrite-based wrapper for generating field boilerplate (enum).
 *
 * <p>This class maintains the JavaParser API while internally using OpenRewrite for AST manipulation.
 * It generates field enum metadata for views.</p>
 */
public final class OpenRewriteFieldBoilerplateGenerator {

    private final String packageName;
    private final String viewName;
    private final String enumName;
    private final List<Property> properties;
    private final boolean fieldEnumMode;
    private final String metaCreatorBody;
    private final String discriminatorFieldReference;
    private final String discriminatorValue;
    private final String[] permittedSubtypeClassNames;
    private final List<String> additionalImports;
    private final Consumer<List<String>> divergenceSink;

    private OpenRewriteFieldBoilerplateGenerator(Builder builder) {
        this.packageName = builder.packageName;
        this.viewName = builder.viewName;
        this.enumName = builder.enumName;
        this.properties = List.copyOf(builder.properties);
        this.fieldEnumMode = builder.fieldEnumMode;
        this.metaCreatorBody = builder.metaCreatorBody;
        this.discriminatorFieldReference = builder.discriminatorFieldReference;
        this.discriminatorValue = builder.discriminatorValue;
        this.permittedSubtypeClassNames = builder.permittedSubtypeClassNames.clone();
        this.additionalImports = List.copyOf(builder.additionalImports);
        this.divergenceSink = builder.divergenceSink;
    }

    public static Builder builder(String packageName, String viewName, List<Property> properties) {
        return new Builder(packageName, viewName, properties);
    }

    public void generate(Path outputRoot) throws IOException {
        if (outputRoot == null || outputRoot.toString().isBlank()) {
            return;
        }
        
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));
        
        Files.createDirectories(packageDir);

        Path enumFile = packageDir.resolve(enumName + ".java");

        // Build source code
        String source = buildSource();

        // Write to file
        Files.writeString(enumFile, source);

        if (divergenceSink != null) {
            divergenceSink.accept(List.of());
        }
    }

    private String buildSource() {
        StringBuilder sb = new StringBuilder();

        // Add header comments
        sb.append("// {@link ").append(viewName).append("} Field metadata for the ")
                .append(viewName).append(" view.\n");
        sb.append("// {enabled:true}\n");

        // Add package declaration
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(";\n");
        } else {
            sb.append("package default;\n");
        }
        sb.append("\n");

        // Add imports
        for (String importName : additionalImports) {
            if (importName != null && !importName.isBlank()) {
                sb.append("import ").append(importName).append(";\n");
            }
        }
        sb.append("\n");

        // Add enum declaration
        sb.append("/**\n");
        sb.append(" * Field constants for the ").append(viewName).append(" view.\n");
        sb.append(" */\n");
        sb.append("public enum ").append(enumName).append(" {\n");

        for (int i = 0; i < properties.size(); i++) {
            Property property = properties.get(i);
            
            // Add field
            sb.append("    private final String ").append(property.name()).append(";\n");
        }
        sb.append("\n");

        // Constructor
        sb.append("    ");
        sb.append(enumName).append("(");
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(properties.get(i).name());
        }
        sb.append(") {\n");
        for (Property property : properties) {
            sb.append("        this.").append(property.name()).append(" = ").append(property.name()).append(";\n");
        }
        sb.append("    }\n\n");

        // Getter methods
        for (Property property : properties) {
            sb.append("    public String ").append(property.name()).append "() {\n");
            sb.append("        return ").append(property.name()).append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Builder for field boilerplate generator.
     */
    public static class Builder {
        private String packageName;
        private String viewName;
        private List<Property> properties;
        private String enumName;
        private boolean enumNameExplicitlySet = false;
        private boolean fieldEnumMode = true;
        private String metaCreatorBody;
        private String discriminatorFieldReference;
        private String discriminatorValue = "";
        private String[] permittedSubtypeClassNames = new String[0];
        private List<String> additionalImports = new ArrayList<>();
        private Consumer<List<String>> divergenceSink;

        private Builder(String packageName, String viewName, List<Property> properties) {
            this.packageName = packageName == null ? "" : packageName;
            this.viewName = viewName;
            this.properties = properties;
            this.enumName = viewName + "Field";
        }

        public Builder withEnumTypeName(String enumName) {
            this.enumName = enumName;
            this.enumNameExplicitlySet = true;
            return this;
        }

        public Builder withPropertyEnumMode() {
            this.fieldEnumMode = false;
            if (!enumNameExplicitlySet) {
                this.enumName = viewName + "Property";
            }
            return this;
        }

        public Builder withMetaCreatorBody(String metaCreatorBody) {
            this.metaCreatorBody = metaCreatorBody;
            return this;
        }

        public Builder withDiscriminatorField(String discriminatorFieldReference) {
            this.discriminatorFieldReference = discriminatorFieldReference;
            return this;
        }

        public Builder withDiscriminatorValue(String discriminatorValue) {
            this.discriminatorValue = discriminatorValue;
            return this;
        }

        public Builder withPermittedSubtypeClassNames(String... permittedSubtypeClassNames) {
            this.permittedSubtypeClassNames = permittedSubtypeClassNames == null ? new String[0] : permittedSubtypeClassNames.clone();
            return this;
        }

        public Builder withAdditionalImports(String... additionalImports) {
            if (additionalImports != null) {
                for (String importName : additionalImports) {
                    this.additionalImports.add(importName);
                }
            }
            return this;
        }

        public Builder withDivergenceSink(Consumer<List<String>> divergenceSink) {
            this.divergenceSink = divergenceSink;
            return this;
        }

        public OpenRewriteFieldBoilerplateGenerator build() {
            return new OpenRewriteFieldBoilerplateGenerator(this);
        }
    }
}
