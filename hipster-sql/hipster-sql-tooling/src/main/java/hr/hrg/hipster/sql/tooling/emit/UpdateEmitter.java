package hr.hrg.hipster.sql.tooling.emit;

import hr.hrg.hipster.sql.tooling.model.AnnotationModel;
import hr.hrg.hipster.sql.tooling.model.EntityModel;
import hr.hrg.hipster.sql.tooling.model.PropertyModel;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits {@code <Entity>Update}: the mutable, change-tracking counterpart of {@code Immutable}.
 *
 * <p>The change-set bit index is the property's ordinal, so it is the same number the ledger and
 * {@code ColumnMeta} use. The emitter does not invent a second layout.</p>
 */
public final class UpdateEmitter {

    public static final String GENERATOR = "hr.hrg.hipster.sql.tooling.HipsterSqlGenerator";

    private UpdateEmitter() {
    }

    public static String render(EntityModel entity) {
        return render(entity, false);
    }

    public static String render(EntityModel entity, boolean genJson) {
        int changeSets = (entity.properties().size() + 63) / 64;
        JavaSourceWriter out = JavaSourceWriter.defaultWriter();
        header(out, entity, "Update implementation", genJson);
        if (genJson) {
            out.line("@JsonSerialize(using = DirectSerializer.class)");
        }
        out.line("public class " + entity.simpleName() + "Update implements "
                + entity.simpleName() + ", IUpdatable"
                + (genJson ? ", IDirectSerializerReady" : "") + " {");
        out.indent();

        for (int i = 0; i < changeSets; i++) {
            out.line("protected long _changeSet" + i + ";");
        }
        if (changeSets > 0) {
            out.blank();
        }
        for (PropertyModel property : entity.properties()) {
            out.line("protected " + property.typeText() + " " + property.name()
                    + TypeTexts.initialValue(property.typeText(), property.initial()) + ";");
        }
        for (PropertyModel property : entity.skipped()) {
            out.line("protected " + property.typeText() + " " + property.name()
                    + TypeTexts.initialValue(property.typeText(), property.initial()) + ";");
        }
        out.blank();

        out.line("public " + entity.simpleName() + "Update() {");
        out.line("}");
        out.blank();

        if (genJson) {
            out.line("@JsonCreator");
        }
        out.line("public " + entity.simpleName() + "Update("
                + parameters(entity.properties(), genJson) + ") {");
        out.indent();
        for (PropertyModel property : entity.properties()) {
            out.line("this." + property.name() + " = " + property.name() + ";");
        }
        out.unindent();
        out.line("}");
        out.blank();

        out.line("public " + entity.simpleName() + "Update(" + entity.simpleName() + " v) {");
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

            String returnType = property.transient_() ? "void" : entity.simpleName() + "Update";
            out.line("public " + returnType + " " + property.setterName() + "("
                    + property.typeText() + " " + property.name() + ") {");
            out.indent();
            if (!property.transient_()) {
                out.line("this._changeSet" + (property.ordinal() / 64) + " |= "
                        + changeBit(property.ordinal()) + ";");
            }
            out.line("this." + property.name() + " = " + property.name() + ";");
            if (!property.transient_()) {
                out.line("return this;");
            }
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

        ImmutableEmitter.emitIdentity(out, entity);

        if (genJson) {
            ImmutableEmitter.emitSerialize(out, entity, property -> property.getterName() + "()");
        }

        out.line("@Override");
        out.line("public boolean isEmpty() {");
        out.indent();
        if (changeSets == 0) {
            out.line("return true;");
        } else {
            out.line("return " + changeSetsExpression(changeSets) + ";");
        }
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public final <T> boolean isChanged(Key<T> column) {");
        out.indent();
        out.line("return isChanged(column.ordinal());");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public final boolean isChanged(int _ordinal) {");
        out.indent();
        emitOrdinalDispatch(out, changeSets, "_ordinal",
                ordinal -> "return (_changeSet" + (ordinal / 64) + " & (1L << _ordinal)) != 0;",
                "return false;");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public final <T> void setChanged(Key<T> column, boolean changed) {");
        out.indent();
        out.line("setChanged(column.ordinal(), changed);");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public final void setChanged(int _ordinal, boolean changed) {");
        out.indent();
        if (changeSets == 0) {
            out.line("// no properties, nothing to change");
        } else {
            out.line("if (changed) {");
            out.indent();
            emitSetChangedDispatch(out, changeSets,
                    ordinal -> "_changeSet" + (ordinal / 64) + " |= (1L << _ordinal);");
            out.unindent();
            out.line("} else {");
            out.indent();
            emitSetChangedDispatch(out, changeSets,
                    ordinal -> "_changeSet" + (ordinal / 64) + " &= ~(1L << _ordinal);");
            out.unindent();
            out.line("}");
        }
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public final void setChanged(boolean changed) {");
        out.indent();
        if (changeSets == 0) {
            out.line("// no properties, nothing to change");
        } else {
            out.line("if (changed) {");
            out.indent();
            for (int i = 0; i < changeSets; i++) {
                int bits = Math.min(64, entity.properties().size() - i * 64);
                String mask = bits == 64 ? "-1L" : "((1L << " + bits + ") - 1L)";
                out.line("_changeSet" + i + " = " + mask + ";");
            }
            out.unindent();
            out.line("} else {");
            out.indent();
            for (int i = 0; i < changeSets; i++) {
                out.line("_changeSet" + i + " = 0;");
            }
            out.unindent();
            out.line("}");
        }
        out.unindent();
        out.line("}");
        out.blank();

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
        out.blank();

        out.line("@Override");
        out.line("public <T> void setValue(Key<T> column, T value) {");
        out.indent();
        out.line("this.setValue(column.ordinal(), value);");
        out.unindent();
        out.line("}");
        out.blank();

        out.line("@Override");
        out.line("public void setValue(int _ordinal, Object value) {");
        out.indent();
        out.line("switch (_ordinal) {");
        out.indent();
        for (PropertyModel property : entity.properties()) {
            out.line("case " + property.ordinal() + ": this." + property.name() + " = ("
                    + TypeTexts.boxed(property.typeText()) + ") value; break;");
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

    private static void header(JavaSourceWriter out, EntityModel entity, String what,
                               boolean genJson) {
        out.line("// @generated file " + GENERATOR + " \u2014 " + what + " for the "
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
        imports.add("hr.hrg.hipster.entity.IUpdatable");
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
            if (ImmutableEmitter.hasKeepRestMerge(entity)) {
                imports.add("hr.hrg.hipster.jackson.JacksonUtil");
            }
        }
        for (String imported : imports.imports()) {
            out.line("import " + imported + ";");
        }
        if (!imports.imports().isEmpty()) {
            out.blank();
        }
        out.line("/** Update implementation of {@link " + entity.simpleName() + "}. */");
    }

    private static void emitOrdinalDispatch(JavaSourceWriter out, int changeSets, String ordinalVar,
                                            java.util.function.IntFunction<String> one,
                                            String fallback) {
        if (changeSets == 0) {
            if (fallback != null) {
                out.line(fallback);
            }
            return;
        }
        if (changeSets == 1) {
            out.line(one.apply(0));
            return;
        }
        for (int i = changeSets - 1; i >= 1; i--) {
            out.line("if (" + ordinalVar + " >= " + (i * 64) + ") {");
            out.indent();
            out.line(one.apply(i * 64));
            out.unindent();
            out.line("}");
        }
        out.line(one.apply(0));
    }

    /** An if/else chain: exactly one change-set is touched for a given ordinal. */
    private static void emitSetChangedDispatch(JavaSourceWriter out, int changeSets,
                                               java.util.function.IntFunction<String> assignment) {
        if (changeSets == 1) {
            out.line(assignment.apply(0));
            return;
        }
        for (int i = changeSets - 1; i >= 1; i--) {
            out.line((i == changeSets - 1 ? "if (_ordinal >= " : "} else if (_ordinal >= ")
                    + (i * 64) + ") {");
            out.indent();
            out.line(assignment.apply(i * 64));
            out.unindent();
        }
        out.line("} else {");
        out.indent();
        out.line(assignment.apply(0));
        out.unindent();
        out.line("}");
    }

    private static String changeBit(int ordinal) {
        int bit = ordinal % 64;
        return bit == 63 ? "(1L << 63)" : (1L << bit) + "L";
    }

    private static String changeSetsExpression(int changeSets) {
        List<String> terms = new java.util.ArrayList<>();
        for (int i = 0; i < changeSets; i++) {
            terms.add("_changeSet" + i + " == 0");
        }
        return String.join(" && ", terms);
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
                continue;
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
