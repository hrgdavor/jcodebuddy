package hr.hrg.hipster.sql.tooling.emit;

import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits {@code <Entity>Visitor} — the smallest generated artifact, used as the first vertical slice.
 *
 * <p>The shape is the old {@code GenVisitor}'s: one {@code void visit(...)} whose parameters are every
 * non-transient, non-{@code keepRest} accessor, in declaration order. The output follows the plan's
 * baseline: the two-line generated header, a Javadoc link to the entity, sorted imports, four-space
 * indentation and LF.</p>
 */
public final class VisitorEmitter {

    public static final String GENERATOR = "hr.hrg.hipster.sql.tooling.HipsterSqlGenerator";

    private VisitorEmitter() {
    }

    public static String render(EntityModel entity) {
        JavaSourceWriter out = JavaSourceWriter.defaultWriter();
        out.line("// @generated file " + GENERATOR + " \u2014 Visitor for the "
                + entity.simpleName() + " entity.");
        out.line("// {enabled:true, blockMarker: \"implicit\"}");
        out.line("package " + entity.packageName() + ";");
        out.blank();

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

        out.line("/** Visitor for {@link " + entity.simpleName() + "}. */");
        out.line("public interface " + entity.visitorName() + " {");
        out.indent();
        out.line(visitSignature(entity.properties()));
        out.unindent();
        out.line("}");
        return out.finish();
    }

    /** One method, no defaults; empty entities still get a legal no-argument {@code visit()}. */
    private static String visitSignature(List<PropertyModel> properties) {
        String parameters = properties.stream()
                .filter(property -> !property.transient_() && !property.keepRest())
                .map(property -> property.typeText() + " " + property.name())
                .collect(Collectors.joining(", "));
        return "void visit(" + parameters + ");";
    }
}
