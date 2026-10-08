// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;
import hr.hrg.jcodebuddy.generated.GeneratedCodeMarkers;

/**
 * Emits {@code <View>PatchApplier} — the inverse of {@code EntityJacksonDeepChangeSerializer} (DEC-048 § 2, plan step
 * 6.2's second half).
 *
 * <p>The emitted class applies a deep change document to a view's own {@code Write} target: it walks the document's
 * field-map, dispatches on the field <b>name</b> with the direct-call shape the generated {@code forName} switch already
 * uses, and calls the <b>typed setter</b>. That is the whole reason the applier is generated rather than generic:
 * DEC-019 forbids resolving a name through a {@code Map<String, Method>}, and a view's setters are typed methods, so
 * there is no set-by-name call a generic applier could make.</p>
 *
 * <p><strong>Opt-in, like the SQL adapters, and for a module-boundary reason rather than a maturity one.</strong> The
 * document is JSON, so the emitted class imports {@code tools.jackson.databind.JsonNode}; generating it for every
 * project would put a Jackson dependency into code that a project never asked for. It is reached through
 * {@code --patch-appliers}.</p>
 *
 * <h3>What this first slice applies, and what it refuses to guess</h3>
 * <p>It applies the <b>leaf</b> operations whose type it can convert directly — {@code String}, the primitive and
 * boxed numeric types, {@code boolean} — reading {@code current} from the document. Everything else is
 * <strong>reported, never silently applied or silently dropped</strong>:</p>
 * <ul>
 *   <li>a field the view does not have — DEC-016 forbids resolving it through a hash map, so it is named in the report
 *       (the same rule the emitter's fallback note exists for);</li>
 *   <li>a nested view or collection field, whose document value is a {@code paths} array rather than a leaf — applying
 *       one of those needs the child's {@code Write}, and how a generated applier obtains it is the question this slice
 *       leaves open rather than answers wrongly;</li>
 *   <li>a field whose declared type has no direct {@code JsonNode} conversion yet;</li>
 *   <li>a value carrying {@code "fallback": true} — the emitter's statement that identity matching failed and the path
 *       is positional. It is carried into the report so a caller can tell a positional application from an identified
 *       one.</li>
 * </ul>
 */
public final class ViewPatchApplierGenerator {

    private ViewPatchApplierGenerator() {
    }

    /** The generated class's suffix, so a reader can find it from the view's name alone. */
    public static final String CLASS_SUFFIX = "PatchApplier";

    /** Writes {@code <View>PatchApplier.java}, or leaves it alone and reports when its header froze it. */
    public static void generate(Path javaOutputRoot, String packageName, ViewMeta view, List<Property> writable,
                                DivergenceReporter divergences) throws IOException {
        Path packageDir = packageName == null || packageName.isBlank()
                ? javaOutputRoot
                : javaOutputRoot.resolve(packageName.replace('.', '/'));
        String className = view.name() + CLASS_SUFFIX;
        Path file = packageDir.resolve(className + ".java");
        String canonical = source(packageName, view, className, writable);

        if (!Files.exists(file)) {
            Files.createDirectories(packageDir);
            Files.writeString(file, canonical);
            return;
        }
        ViewAdapterGenerator.writeUnlessFrozen(file, canonical, divergences);
    }

    /** The class's text, deterministic: the same view writes the same bytes. */
    private static String source(String packageName, ViewMeta view, String className, List<Property> writable) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(header(view)).append(nl);
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(";").append(nl).append(nl);
        }
        sb.append("import tools.jackson.databind.JsonNode;").append(nl).append(nl);
        sb.append("/**").append(nl);
        sb.append(" * Applies a deep change document to a {@link ").append(view.name())
                .append("} — the inverse of the deep change serializer.").append(nl);
        sb.append(" *").append(nl);
        sb.append(" * <p>Every key of the document is a field of this view. A key this view does not have, a nested")
                .append(nl);
        sb.append(" * or collection field (whose document value is a {@code paths} array), a type this applier does")
                .append(nl);
        sb.append(" * not convert, and a {@code fallback} marker are all <strong>reported</strong> in the returned")
                .append(nl);
        sb.append(" * list instead of being guessed at.").append(nl);
        sb.append(" */").append(nl);
        sb.append("public final class ").append(className).append(" {").append(nl).append(nl);
        sb.append("    private ").append(className).append("() {").append(nl);
        sb.append("    }").append(nl).append(nl);
        sb.append("    /**").append(nl);
        sb.append("     * Applies {@code document} to {@code target}, returning one line per operation it did not")
                .append(nl);
        sb.append("     * apply. An empty list means every operation landed.").append(nl);
        sb.append("     */").append(nl);
        sb.append("    public static java.util.List<String> apply(JsonNode document, ").append(view.name())
                .append(".Write target) {").append(nl);
        sb.append("        java.util.List<String> report = new java.util.ArrayList<>();").append(nl);
        sb.append("        for (java.util.Map.Entry<String, JsonNode> operation : document.properties()) {").append(nl);
        sb.append("            switch (operation.getKey()) {").append(nl);
        for (Property property : writable) {
            String conversion = conversion(property.type());
            if (conversion == null) {
                // A writable field this applier cannot convert — a nested view, a collection, a date. It gets its own
                // arm rather than falling through to `default`, because `default` would say "unknown_field … is not a
                // writable field", and that is FALSE: the field is writable, this revision simply cannot write it yet.
                // A report that is wrong about why is worse than no report, which is what writing the test showed.
                String message = "unsupported_type: " + property.name() + " (" + property.type()
                        + ") needs a conversion, or a child applier, that this revision does not emit";
                sb.append("                case \"").append(property.name()).append("\" -> report.add(")
                        .append(stringLiteral(message)).append(");").append(nl);
                continue;
            }
            sb.append("                case \"").append(property.name()).append("\" -> {").append(nl);
            sb.append("                    JsonNode delta = operation.getValue();").append(nl);
            sb.append("                    target.").append(property.name()).append("(")
                    .append(conversion.replace("{node}", "delta.path(\"current\")")).append(");").append(nl);
            sb.append("                    if (delta.path(\"fallback\").asBoolean(false)) {").append(nl);
            sb.append("                        report.add(\"positional_fallback: ").append(property.name())
                    .append(" was matched by position, not by identity\");").append(nl);
            sb.append("                    }").append(nl);
            sb.append("                }").append(nl);
        }
        sb.append("                default -> report.add(\"unknown_field: \" + operation.getKey()")
                .append(nl);
        sb.append("                        + \" is not a writable field of ").append(view.name()).append("\");")
                .append(nl);
        sb.append("            }").append(nl);
        sb.append("        }").append(nl);
        sb.append("        return report;").append(nl);
        sb.append("    }").append(nl);
        sb.append("}").append(nl);
        return sb.toString();
    }

    /**
     * The setter argument for one declared type, or {@code null} when this applier cannot convert it yet.
     *
     * <p>Direct {@code JsonNode} accessors only: no object mapper, so the emitted class needs Jackson's node type and
     * nothing else. A type that needs a mapper — a date, a nested view, a parameterized collection — is reported at
     * runtime rather than converted wrongly, and adding one is a line here.</p>
     */
    private static String conversion(String declaredType) {
        String type = declaredType == null ? "" : declaredType.trim();
        return switch (type) {
            case "String", "java.lang.String", "CharSequence" -> "{node}.asText()";
            case "Long", "long", "java.lang.Long" -> "{node}.asLong()";
            case "Integer", "int", "java.lang.Integer" -> "{node}.asInt()";
            case "Boolean", "boolean", "java.lang.Boolean" -> "{node}.asBoolean()";
            case "Double", "double", "java.lang.Double" -> "{node}.asDouble()";
            default -> null;
        };
    }

    /** The two-line file header, via the one place its spelling is written down (DEC-035). */
    private static String header(ViewMeta view) {
        return GeneratedCodeMarkers.fileHeader(ViewPatchApplierGenerator.class.getName(),
                "Applies a deep change document to the " + view.name() + " view.");
    }

    /** A Java string literal for {@code text}, so a type or field name cannot break the emitted source. */
    private static String stringLiteral(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** The writable fields of a view, in ordinal order — the set the {@code Write} interface has setters for. */
    public static List<Property> writableOf(List<Property> allProperties) {
        List<Property> writable = new ArrayList<>();
        for (Property property : allProperties) {
            if (ViewAdapterGenerator.isWritable(property)) {
                writable.add(property);
            }
        }
        return writable;
    }

    /** Whether the generator produced anything for a view, so a caller can report an empty pass honestly. */
    public static boolean appliesTo(List<Property> allProperties) {
        return !writableOf(allProperties).isEmpty();
    }
}
