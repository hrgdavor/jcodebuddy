package hr.hrg.hipster.sql.tooling.emit;

import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits {@code <Entity>Builder}: the fluent layer over {@code Update} that builds an
 * {@code Immutable}.
 *
 * <p>It changes no state layout of its own; every setter delegates to the {@code Update} method, so
 * the change-set bits and ordinals have one owner.</p>
 */
public final class BuilderEmitter {

    public static final String GENERATOR = "hr.hrg.hipster.sql.tooling.HipsterSqlGenerator";

    private BuilderEmitter() {
    }

    public static String render(EntityModel entity) {
        String builder = entity.simpleName() + "Builder";
        JavaSourceWriter out = JavaSourceWriter.defaultWriter();
        out.line("// @generated file " + GENERATOR + " \u2014 Builder for the "
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
        for (String imported : imports.imports()) {
            out.line("import " + imported + ";");
        }
        if (!imports.imports().isEmpty()) {
            out.blank();
        }

        out.line("/** Fluent builder for {@link " + entity.simpleName() + "}. */");
        out.line("public class " + builder + " extends " + entity.simpleName() + "Update implements "
                + entity.simpleName() + " {");
        out.indent();

        out.line("public " + builder + "() {");
        out.indent();
        out.line("super();");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("public " + builder + "(" + entity.simpleName() + " v) {");
        out.indent();
        out.line("super(v);");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("public " + builder + "(" + parameters(entity.properties()) + ") {");
        out.indent();
        out.line("super(" + arguments(entity.properties()) + ");");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("public " + entity.simpleName() + "Immutable build() {");
        out.indent();
        out.line("return new " + entity.simpleName() + "Immutable(this);");
        out.unindent();
        out.line("}");
        out.blank();

        for (int i = 0; i < entity.properties().size(); i++) {
            PropertyModel property = entity.properties().get(i);
            out.line("public " + builder + " " + property.name() + "(" + property.typeText()
                    + " v) {");
            out.indent();
            out.line(property.setterName() + "(v); return this;");
            out.unindent();
            out.line("}");
            if (i < entity.properties().size() - 1) {
                out.blank();
            }
        }

        out.unindent();
        out.line("}");
        return out.finish();
    }

    private static String parameters(List<PropertyModel> properties) {
        return properties.stream()
                .map(property -> property.typeText() + " " + property.name())
                .collect(Collectors.joining(", "));
    }

    private static String arguments(List<PropertyModel> properties) {
        return properties.stream().map(PropertyModel::name).collect(Collectors.joining(", "));
    }
}
