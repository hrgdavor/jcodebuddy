package hr.hrg.hipster.sql.tooling.emit;

import hr.hrg.hipster.sql.tooling.model.AnnotationModel;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits {@code <Entity>Immutable} — the first core artifact of the port.
 *
 * <p>Shape: a final class implementing the entity and {@code IEnumGetter}, private fields in ordinal
 * order, the three constructors the legacy generator emits, annotated getters, and the two
 * {@code getValue} forms. Jackson serialization and {@code equals}/{@code hashCode} wait for the
 * annotation/Id model; this slice is the class a consumer can already instantiate and read.</p>
 */
public final class ImmutableEmitter {

    public static final String GENERATOR = "hr.hrg.hipster.sql.tooling.HipsterSqlGenerator";

    private ImmutableEmitter() {
    }

    public static String render(EntityModel entity) {
        return render(entity, false);
    }

    /**
     * @param genJson when true, add the direct Jackson serializer: the class annotation, the
     *                {@code @JsonCreator} constructor and the {@code serialize} body
     */
    public static String render(EntityModel entity, boolean genJson) {
        JavaSourceWriter out = JavaSourceWriter.defaultWriter();
        out.line("// @generated file " + GENERATOR + " \u2014 Immutable implementation for the "
                + entity.simpleName() + " entity.");
        out.line("// {enabled:true, blockMarker: \"implicit\"}");
        if (!entity.packageName().isEmpty()) {
            out.line("package " + entity.packageName() + ";");
            out.blank();
        }

        ImportCollector imports = new ImportCollector(entity.packageName());
        for (PropertyModel property : entity.properties()) {
            imports.collect(property.typeText(), entity.imports());
        }
        for (PropertyModel property : entity.skipped()) {
            imports.collect(property.typeText(), entity.imports());
        }
        collectAnnotationImports(imports, entity);
        imports.add("hr.hrg.hipster.entity.IEnumGetter");
        imports.add("hr.hrg.hipster.sql.Key");
        if (entity.primary() != null) {
            imports.add("java.util.Objects");
        }
        if (genJson) {
            imports.add("com.fasterxml.jackson.annotation.JsonCreator");
            imports.add("com.fasterxml.jackson.annotation.JsonProperty");
            imports.add("com.fasterxml.jackson.core.JsonGenerationException");
            imports.add("com.fasterxml.jackson.core.JsonGenerator");
            imports.add("com.fasterxml.jackson.databind.SerializerProvider");
            imports.add("com.fasterxml.jackson.databind.annotation.JsonSerialize");
            imports.add("hr.hrg.hipster.jackson.DirectSerializer");
            imports.add("hr.hrg.hipster.jackson.IDirectSerializerReady");
            imports.add("java.io.IOException");
            if (hasKeepRestMerge(entity)) {
                imports.add("hr.hrg.hipster.jackson.JacksonUtil");
            }
        }
        for (String imported : imports.imports()) {
            out.line("import " + imported + ";");
        }
        if (!imports.imports().isEmpty()) {
            out.blank();
        }

        out.line("/** Immutable implementation of {@link " + entity.simpleName() + "}. */");
        if (genJson) {
            out.line("@JsonSerialize(using = DirectSerializer.class)");
        }
        out.line("public final class " + entity.simpleName() + "Immutable implements "
                + entity.simpleName() + ", IEnumGetter"
                + (genJson ? ", IDirectSerializerReady" : "") + " {");
        out.indent();

        for (PropertyModel property : entity.properties()) {
            out.line("private " + property.typeText() + " " + property.name()
                    + TypeTexts.initialValue(property.typeText(), property.initial()) + ";");
        }
        for (PropertyModel property : entity.skipped()) {
            out.line("private " + property.typeText() + " " + property.name()
                    + TypeTexts.initialValue(property.typeText(), property.initial()) + ";");
        }
        out.blank();

        out.line("public " + entity.simpleName() + "Immutable() {");
        out.line("}");
        out.blank();

        if (genJson) {
            out.line("@JsonCreator");
        }
        out.line("public " + entity.simpleName() + "Immutable("
                + parameters(entity.properties(), genJson) + ") {");
        out.indent();
        for (PropertyModel property : entity.properties()) {
            out.line("this." + property.name() + " = " + property.name() + ";");
        }
        out.unindent();
        out.line("}");
        out.blank();

        out.line("public " + entity.simpleName() + "Immutable(" + entity.simpleName() + " v) {");
        out.indent();
        out.line("if (v == null) {");
        out.indent();
        out.line("return;");
        out.unindent();
        out.line("}");
        for (PropertyModel property : entity.properties()) {
            out.line("this." + property.name() + " = v." + property.getterName() + "();");
        }
        for (PropertyModel property : entity.skipped()) {
            out.line("this." + property.name() + " = v." + property.getterName() + "();");
        }
        out.unindent();
        out.line("}");
        out.blank();

        for (PropertyModel property : entity.properties()) {
            for (AnnotationModel annotation : property.annotations()) {
                out.line(annotation.render());
            }
            out.line("@Override");
            out.line("public " + property.typeText() + " " + property.getterName() + "() {");
            out.indent();
            out.line("return " + property.name() + ";");
            out.unindent();
            out.line("}");
            out.blank();
        }
        for (PropertyModel property : entity.skipped()) {
            for (AnnotationModel annotation : property.annotations()) {
                out.line(annotation.render());
            }
            out.line("@Override");
            out.line("public " + property.typeText() + " " + property.getterName() + "() {");
            out.indent();
            out.line("return " + property.name() + ";");
            out.unindent();
            out.line("}");
            out.blank();
        }

        emitIdentity(out, entity);

        if (genJson) {
            emitSerialize(out, entity, property -> property.name());
        }

        out.line("@Override");
        out.line("public final <T, E extends Key<T>> T getValue(E column) {");
        out.indent();
        out.line("return (T) this.getValue(column.ordinal());");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public final Object getValue(int _ordinal) {");
        out.indent();
        out.line("switch (_ordinal) {");
        out.indent();
        for (PropertyModel property : entity.properties()) {
            if (property.transient_()) {
                continue;
            }
            out.line("case " + property.ordinal() + ": return this." + property.name() + ";");
        }
        out.line("default: throw new ArrayIndexOutOfBoundsException(_ordinal);");
        out.unindent();
        out.line("}");
        out.unindent();
        out.line("}");
        out.unindent();
        out.line("}");
        return out.finish();
    }

    /** Equality is keyed by the {@code @Id} property; no primary means no override. */
    static void emitIdentity(JavaSourceWriter out, EntityModel entity) {
        PropertyModel primary = entity.primary();
        if (primary == null) {
            return;
        }
        boolean primitive = isPrimitive(primary.typeText());
        out.line("@Override");
        out.line("public boolean equals(Object o) {");
        out.indent();
        out.line("if (this == o) {");
        out.indent();
        out.line("return true;");
        out.unindent();
        out.line("}");
        out.line("if (o == null || !(o instanceof " + entity.simpleName() + ")) {");
        out.indent();
        out.line("return false;");
        out.unindent();
        out.line("}");
        if (primitive) {
            out.line("return " + primary.name() + " == ((" + entity.simpleName() + ") o)."
                    + primary.getterName() + "();");
        } else {
            out.line("return Objects.equals(" + primary.name() + ", ((" + entity.simpleName() + ") o)."
                    + primary.getterName() + "());");
        }
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public int hashCode() {");
        out.indent();
        out.line("return Objects.hashCode(" + primary.name() + ");");
        out.unindent();
        out.line("}");
        out.blank();
    }

    static void emitSerialize(JavaSourceWriter out, EntityModel entity,
                              java.util.function.Function<PropertyModel, String> accessor) {
        out.line("@Override");
        out.line("public void serialize(JsonGenerator jgen, SerializerProvider provider) "
                + "throws IOException, JsonGenerationException {");
        out.indent();
        out.line("jgen.writeStartObject();");
        out.blank();
        for (PropertyModel property : entity.properties()) {
            if (property.transient_()) {
                continue;
            }
            String expression = accessor.apply(property);
            String type = property.typeText();
            if (property.keepRest()) {
                String raw = TypeTexts.rawType(type);
                if ("Map".equals(raw) || "java.util.Map".equals(raw)) {
                    out.line("JacksonUtil.writeKeepMap(" + expression + ", jgen, provider);");
                    out.blank();
                    continue;
                }
                if ("ObjectNode".equals(raw)
                        || "com.fasterxml.jackson.databind.node.ObjectNode".equals(raw)) {
                    out.line("JacksonUtil.writeKeepObjectNode(" + expression
                            + ", jgen, provider);");
                    out.blank();
                    continue;
                }
            }
            out.line("jgen.writeFieldName(\"" + property.name() + "\");");
            if (isNumeric(type)) {
                if (isPrimitive(type)) {
                    out.line("jgen.writeNumber(" + expression + ");");
                } else {
                    out.line("if (" + expression + " == null) {");
                    out.indent();
                    out.line("jgen.writeNull();");
                    out.unindent();
                    out.line("} else {");
                    out.indent();
                    out.line("jgen.writeNumber(" + expression + ");");
                    out.unindent();
                    out.line("}");
                }
            } else if (isBoolean(type)) {
                if (isPrimitive(type)) {
                    out.line("jgen.writeBoolean(" + expression + ");");
                } else {
                    out.line("if (" + expression + " == null) {");
                    out.indent();
                    out.line("jgen.writeNull();");
                    out.unindent();
                    out.line("} else {");
                    out.indent();
                    out.line("jgen.writeBoolean(" + expression + ");");
                    out.unindent();
                    out.line("}");
                }
            } else if (isChar(type)) {
                if (isPrimitive(type)) {
                    out.line("jgen.writeString(new char[]{" + expression + "}, 0, 1);");
                } else {
                    out.line("if (" + expression + " == null) {");
                    out.indent();
                    out.line("jgen.writeNull();");
                    out.unindent();
                    out.line("} else {");
                    out.indent();
                    out.line("jgen.writeString(new char[]{" + expression + "}, 0, 1);");
                    out.unindent();
                    out.line("}");
                }
            } else if ("String".equals(type) || "java.lang.String".equals(type)) {
                out.line("if (" + expression + " == null) {");
                out.indent();
                out.line("jgen.writeNull();");
                out.unindent();
                out.line("} else {");
                out.indent();
                out.line("jgen.writeString(" + expression + ");");
                out.unindent();
                out.line("}");
            } else {
                out.line("jgen.writeObject(" + expression + ");");
            }
            out.blank();
        }
        out.line("jgen.writeEndObject();");
        out.unindent();
        out.line("}");
        out.blank();
    }

    private static boolean isNumeric(String type) {
        return switch (type) {
            case "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte",
                 "float", "Float", "double", "Double" -> true;
            default -> false;
        };
    }

    private static boolean isBoolean(String type) {
        return "boolean".equals(type) || "Boolean".equals(type);
    }

    private static boolean isChar(String type) {
        return "char".equals(type) || "Character".equals(type);
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "float", "double", "boolean", "char" -> true;
            default -> false;
        };
    }

    static boolean hasKeepRestMerge(EntityModel entity) {
        for (PropertyModel property : entity.properties()) {
            if (!property.keepRest()) {
                continue;
            }
            String raw = TypeTexts.rawType(property.typeText());
            if ("Map".equals(raw) || "java.util.Map".equals(raw)
                    || "ObjectNode".equals(raw)
                    || "com.fasterxml.jackson.databind.node.ObjectNode".equals(raw)) {
                return true;
            }
        }
        return false;
    }

    private static void collectAnnotationImports(ImportCollector imports, EntityModel entity) {
        for (PropertyModel property : entity.properties()) {
            collectAnnotationImports(imports, entity, property);
        }
        for (PropertyModel property : entity.skipped()) {
            collectAnnotationImports(imports, entity, property);
        }
    }

    private static void collectAnnotationImports(ImportCollector imports, EntityModel entity,
                                                 PropertyModel property) {
        for (AnnotationModel annotation : property.annotations()) {
            if (annotation.name().contains(".")) {
                continue; // already fully qualified
            }
            String fqn = entity.imports().get(annotation.name());
            if (fqn != null) {
                imports.add(fqn);
            }
        }
    }

    private static String parameters(List<PropertyModel> properties, boolean genJson) {
        return properties.stream()
                .map(property -> (genJson ? "@JsonProperty(\"" + property.name() + "\") " : "")
                        + property.typeText() + " " + property.name())
                .collect(Collectors.joining(", "));
    }
}
