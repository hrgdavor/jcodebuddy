// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;
import hr.hrg.jcodebuddy.generated.GeneratedCodeMarkers;

/**
 * Emits {@code <View>PatchApplier} — the inverse of {@code EntityJacksonDeepChangeSerializer} (DEC-048 § 2,
 * plan step 6.8).
 *
 * <p>The emitted class applies a deep change document to a view. It walks the document's field map,
 * dispatches on the field <b>name</b> with the direct-call shape the generated {@code forName} switch
 * already uses, and calls the <b>typed setter</b> through the view's own {@code Write}. That is the whole
 * reason the applier is generated rather than generic: DEC-019 forbids resolving a name through a
 * {@code Map<String, Method>}, and a view's setters are typed methods, so there is no set-by-name call a
 * generic applier could make.</p>
 *
 * <h3>The route into a child: the builder (DEC-048's 2026-10-09 amendment)</h3>
 * <p>A generated builder implements its view's {@code Write}, so {@code view.toBuilder()} is the route
 * from a view instance to a writer. A nested field's operation is a leaf <em>inside</em> the child, so the
 * applier reads the child from the source view, gets {@code child.toBuilder()}, recurses through the
 * child's own applier, and writes the rebuilt child back through the parent's typed setter. Collection
 * fields are element writes for the same reason: the document names an element by index, and an index
 * that no longer exists is a <b>named finding</b>, never a silent skip (DEC-016's "never a guessed
 * position" and the amendment's "an applier may be stricter than its emitter").</p>
 *
 * <h3>What it refuses to guess</h3>
 * <ul>
 *   <li>a field the view does not have — named in the report (DEC-016);</li>
 *   <li>a writable field the developer's {@code Write} omits — reported, not called;</li>
 *   <li>a field whose declared type has no direct {@code JsonNode} conversion — reported;</li>
 *   <li>a value carrying {@code "fallback": true} — carried into the report so a caller can tell a
 *       positional application from an identified one.</li>
 * </ul>
 *
 * <p><strong>Opt-in</strong> like the SQL adapters: the document is JSON, so the emitted class imports
 * {@code tools.jackson.databind.JsonNode}. It is reached through {@code --patch-appliers}.</p>
 */
public final class ViewPatchApplierGenerator {

    private ViewPatchApplierGenerator() {
    }

    /** The generated class's suffix, so a reader can find it from the view's name alone. */
    public static final String CLASS_SUFFIX = "PatchApplier";

    /** A polymorphic root and the concrete views a patch addressed to the root must dispatch to. */
    public record Family(String packageName, String rootName, String rootFqn, String discriminatorField,
                         List<Member> members) {
        public Family {
            members = List.copyOf(members);
        }
    }

    /** One concrete view of a {@link Family}; it must have its own generated applier. */
    public record Member(String packageName, String name, String fqn, String discriminatorValue) {
    }

    /**
     * Emits {@code <Root>PatchApplier} — the dispatcher DEC-048's 2026-10-09 amendment asks for.
     *
     * <p>It reads the value's discriminator through the root's accessor, matches it against each concrete
     * view's declared value, and calls that view's own generated applier. The arms are direct calls, so an
     * IDE can follow a discriminator to the applier and then to the typed setter — the same property
     * {@code PaymentMethodController} hand-writes today.</p>
     */
    public static void generateDispatcher(Path javaOutputRoot, Family family, DivergenceReporter divergences)
            throws IOException {
        Path packageDir = family.packageName() == null || family.packageName().isBlank()
                ? javaOutputRoot
                : javaOutputRoot.resolve(family.packageName().replace('.', '/'));
        String className = family.rootName() + CLASS_SUFFIX;
        Path file = packageDir.resolve(className + ".java");
        String canonical = dispatcherSource(family, className);
        if (!Files.exists(file)) {
            Files.createDirectories(packageDir);
            Files.writeString(file, canonical);
            return;
        }
        ViewAdapterGenerator.writeUnlessFrozen(file, canonical, divergences);
    }

    private static String dispatcherSource(Family family, String className) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(GeneratedCodeMarkers.fileHeader(ViewPatchApplierGenerator.class.getName(),
                "Dispatches a deep change document to the concrete " + family.rootName() + " view.")).append(nl);
        if (family.packageName() != null && !family.packageName().isBlank()) {
            sb.append("package ").append(family.packageName()).append(";").append(nl).append(nl);
        }
        sb.append("import tools.jackson.databind.JsonNode;").append(nl).append(nl);
        sb.append("/**").append(nl);
        sb.append(" * Applies a deep change document to a {@link ").append(family.rootName()).append("} by")
                .append(nl);
        sb.append(" * dispatching on its discriminator to the concrete view's own applier.").append(nl);
        sb.append(" */").append(nl);
        sb.append("public final class ").append(className).append(" {").append(nl).append(nl);
        sb.append("    /** The patched view and the operations that could not be applied. */").append(nl);
        sb.append("    public record Result(").append(family.rootFqn())
                .append(" value, java.util.List<String> report) {").append(nl);
        sb.append("    }").append(nl).append(nl);
        sb.append("    private ").append(className).append("() {").append(nl);
        sb.append("    }").append(nl).append(nl);
        sb.append("    public static Result apply(JsonNode document, ").append(family.rootFqn())
                .append(" view) {").append(nl);
        sb.append("        String discriminator = String.valueOf(view.").append(family.discriminatorField())
                .append("());").append(nl);
        sb.append("        return switch (discriminator) {").append(nl);
        for (Member member : family.members()) {
            sb.append("            case \"").append(member.discriminatorValue()).append("\" -> {").append(nl);
            sb.append("                ").append(member.fqn()).append(" concrete = (")
                    .append(member.fqn()).append(") view;").append(nl);
            sb.append("                ").append(member.fqn()).append(CLASS_SUFFIX).append(".Result result = ")
                    .append(member.fqn()).append(CLASS_SUFFIX).append(".apply(document, concrete);").append(nl);
            sb.append("                yield new Result(result.value(), result.report());").append(nl);
            sb.append("            }").append(nl);
        }
        sb.append("            default -> new Result(view, java.util.List.of(")
                .append(stringLiteral("unknown_discriminator: "))
                .append(" + discriminator));").append(nl);
        sb.append("        };").append(nl);
        sb.append("    }").append(nl).append(nl);
        sb.append("}").append(nl);
        return sb.toString();
    }

    /** Writes {@code <View>PatchApplier.java}, or leaves it alone and reports when its header froze it. */
    public static void generate(Path javaOutputRoot, String packageName, ViewMeta view, Path viewSource,
                                List<Property> writable, Set<String> viewNames,
                                DivergenceReporter divergences) throws IOException {
        ViewWriteInterface write = ViewWriteInterface.readOrNull(viewSource);
        if (write == null || write.setters().isEmpty()) {
            if (divergences != null) {
                divergences.report("patch_applier_without_write", view.name(),
                        "the view declares no Write interface, so there is no typed writer for an applier to call",
                        "no nested Write", "a Write interface with one-parameter setters",
                        "add the view's Write interface, or turn --patch-appliers off");
            }
            return;
        }
        Path packageDir = packageName == null || packageName.isBlank()
                ? javaOutputRoot
                : javaOutputRoot.resolve(packageName.replace('.', '/'));
        String className = view.name() + CLASS_SUFFIX;
        Path file = packageDir.resolve(className + ".java");

        String canonical = source(packageName, view, className, writable, write, viewNames);
        if (!Files.exists(file)) {
            Files.createDirectories(packageDir);
            Files.writeString(file, canonical);
            return;
        }
        ViewAdapterGenerator.writeUnlessFrozen(file, canonical, divergences);
    }

    /** The class's text, deterministic: the same view writes the same bytes. */
    private static String source(String packageName, ViewMeta view, String className, List<Property> writable,
                                 ViewWriteInterface write, Set<String> viewNames) {
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
        sb.append(" * <p>Every key of the document is a field of this view. A key this view does not have, a")
                .append(nl);
        sb.append(" * type this applier does not convert, and a {@code fallback} marker are all")
                .append(nl);
        sb.append(" * <strong>reported</strong> in the returned list instead of being guessed at.")
                .append(nl);
        sb.append(" */").append(nl);
        sb.append("public final class ").append(className).append(" {").append(nl).append(nl);
        sb.append("    /** The patched view and the operations that could not be applied. */").append(nl);
        sb.append("    public record Result(").append(view.name())
                .append(" value, java.util.List<String> report) {").append(nl);
        sb.append("    }").append(nl).append(nl);
        sb.append("    private ").append(className).append("() {").append(nl);
        sb.append("    }").append(nl).append(nl);

        // The view-to-writer route: the builder implements the view's Write (DEC-048's amendment).
        sb.append("    /**").append(nl);
        sb.append("     * Applies {@code document} to a copy of {@code view}, returning the patched value and")
                .append(nl);
        sb.append("     * one line per operation it did not apply.").append(nl);
        sb.append("     */").append(nl);
        sb.append("    public static Result apply(JsonNode document, ").append(view.name())
                .append(" view) {").append(nl);
        sb.append("        ").append(view.name()).append("Builder target = view.toBuilder();").append(nl);
        sb.append("        java.util.List<String> report = apply(document, view, target);").append(nl);
        sb.append("        return new Result(target.build(), report);").append(nl);
        sb.append("    }").append(nl).append(nl);

        sb.append("    /**").append(nl);
        sb.append("     * Applies {@code document} to {@code target}, returning one line per operation it did not")
                .append(nl);
        sb.append("     * apply. An empty list means every operation landed.").append(nl);
        sb.append("     */").append(nl);
        sb.append("    public static java.util.List<String> apply(JsonNode document, ").append(view.name())
                .append(" view, ").append(view.name()).append(".Write target) {").append(nl);
        sb.append("        java.util.List<String> report = new java.util.ArrayList<>();").append(nl);
        sb.append("        for (java.util.Map.Entry<String, JsonNode> operation : document.properties()) {").append(nl);
        sb.append("            switch (operation.getKey()) {").append(nl);
        boolean nested = false;
        for (Property property : writable) {
            if (!declares(write, property.name())) {
                sb.append("                case \"").append(property.name()).append("\" -> report.add(")
                        .append(stringLiteral("missing_setter: " + view.name() + ".Write declares no setter for "
                                + property.name() + ", so this operation cannot be applied"))
                        .append(");").append(nl);
                continue;
            }
            String childView = childViewOf(property, viewNames);
            if (childView != null) {
                nested = true;
                appendNestedArm(sb, view, property, childView, viewNames);
                continue;
            }
            String conversion = conversion(property.type());
            if (conversion == null) {
                String message = "unsupported_type: " + property.name() + " (" + property.type()
                        + ") needs a conversion that this revision does not emit";
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
        sb.append("                default -> report.add(\"unknown_field: \" + operation.getKey()").append(nl);
        sb.append("                        + \" is not a writable field of ").append(view.name()).append("\");").append(nl);
        sb.append("            }").append(nl);
        sb.append("        }").append(nl);
        sb.append("        return report;").append(nl);
        sb.append("    }").append(nl).append(nl);
        if (nested) {
            sb.append("    /**").append(nl);
            sb.append("     * Rebuilds a child document from a nested field's {@code paths} array: each entry's path")
                    .append(nl);
            sb.append("     * names this view's field first (and, for a collection, the element index second), so")
                    .append(nl);
            sb.append("     * the child's own field name follows that prefix.").append(nl);
            sb.append("     */").append(nl);
            sb.append("    private static JsonNode childDocument(JsonNode delta, int prefixLength) {").append(nl);
            sb.append("        tools.jackson.databind.node.ObjectNode child =").append(nl);
            sb.append("                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();").append(nl);
            sb.append("        for (JsonNode entry : delta.path(\"paths\")) {").append(nl);
            sb.append("            JsonNode path = entry.path(\"path\");").append(nl);
            sb.append("            if (path.size() > prefixLength) {").append(nl);
            sb.append("                child.set(path.path(prefixLength).asText(), entry);").append(nl);
            sb.append("            }").append(nl);
            sb.append("        }").append(nl);
            sb.append("        return child;").append(nl);
            sb.append("    }").append(nl).append(nl);
            sb.append("    /**").append(nl);
            sb.append("     * Rebuilds a child document from one collection element's operation entry: the path")
                    .append(nl);
            sb.append("     * names the collection field, the element index and then the child's own field, so")
                    .append(nl);
            sb.append("     * the child's field name is at {@code prefixLength}.").append(nl);
            sb.append("     */").append(nl);
            sb.append("    private static JsonNode childEntryDocument(JsonNode entry, int prefixLength) {").append(nl);
            sb.append("        tools.jackson.databind.node.ObjectNode child =").append(nl);
            sb.append("                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();").append(nl);
            sb.append("        JsonNode path = entry.path(\"path\");").append(nl);
            sb.append("        if (path.size() > prefixLength) {").append(nl);
            sb.append("            child.set(path.path(prefixLength).asText(), entry);").append(nl);
            sb.append("        }").append(nl);
            sb.append("        return child;").append(nl);
            sb.append("    }").append(nl);
        }
        sb.append("}").append(nl);
        return sb.toString();
    }

    /** One nested-field arm: recurse through the child's applier and write the rebuilt child back. */
    private static void appendNestedArm(StringBuilder sb, ViewMeta view, Property property, String childView,
                                        Set<String> viewNames) {
        String childQualified = qualifiedViewName(property, childView);
        String childApplier = childQualified + CLASS_SUFFIX;
        String childBuilder = childQualified + "Builder";
        boolean collection = collectionOf(property);
        sb.append("                case \"").append(property.name()).append("\" -> {").append(nl());
        sb.append("                    JsonNode delta = operation.getValue();").append(nl());
        if (collection) {
            sb.append("                    java.util.List<").append(childQualified).append("> current = view.")
                    .append(property.name()).append("();").append(nl());
            sb.append("                    if (current == null) {").append(nl());
            sb.append("                        report.add(\"missing_child_value: ").append(property.name())
                    .append("\");").append(nl());
            sb.append("                    } else {").append(nl());
            sb.append("                        java.util.List<").append(childQualified)
                    .append("> updated = new java.util.ArrayList<>(current);").append(nl());
            sb.append("                        for (JsonNode entry : delta.path(\"paths\")) {").append(nl());
            sb.append("                            JsonNode path = entry.path(\"path\");").append(nl());
            sb.append("                            int index = path.size() > 1 ? path.path(1).asInt() : -1;")
                    .append(nl());
            sb.append("                            if (index < 0 || index >= updated.size()) {").append(nl());
            sb.append("                                report.add(\"stale_index: ").append(property.name())
                    .append("[\" + index + \"] has no element (size \" + updated.size() + \")\");").append(nl());
            sb.append("                                continue;").append(nl());
            sb.append("                            }").append(nl());
            sb.append("                            ").append(childQualified).append(" child = updated.get(index);")
                    .append(nl());
            sb.append("                            if (child == null) {").append(nl());
            sb.append("                                report.add(\"missing_child_value: ").append(property.name())
                    .append("[\" + index + \"]\");").append(nl());
            sb.append("                                continue;").append(nl());
            sb.append("                            }").append(nl());
            sb.append("                            ").append(childBuilder).append(" childTarget = child.toBuilder();")
                    .append(nl());
            sb.append("                            report.addAll(").append(childApplier)
                    .append(".apply(childEntryDocument(entry, 2), child, childTarget));").append(nl());
            sb.append("                            updated.set(index, childTarget.build());").append(nl());
            sb.append("                        }").append(nl());
            sb.append("                        target.").append(property.name()).append("(updated);").append(nl());
            sb.append("                    }").append(nl());
        } else {
            sb.append("                    ").append(childQualified).append(" child = view.").append(property.name())
                    .append("();").append(nl());
            sb.append("                    if (child == null) {").append(nl());
            sb.append("                        report.add(\"missing_child_value: ").append(property.name())
                    .append("\");").append(nl());
            sb.append("                    } else {").append(nl());
            sb.append("                        ").append(childBuilder).append(" childTarget = child.toBuilder();")
                    .append(nl());
            sb.append("                        report.addAll(").append(childApplier)
                    .append(".apply(childDocument(delta, 1), child, childTarget));").append(nl());
            sb.append("                        target.").append(property.name()).append("(childTarget.build());")
                    .append(nl());
            sb.append("                    }").append(nl());
        }
        sb.append("                    if (delta.path(\"fallback\").asBoolean(false)) {").append(nl());
        sb.append("                        report.add(\"positional_fallback: ").append(property.name())
                .append(" was matched by position, not by identity\");").append(nl());
        sb.append("                    }").append(nl());
        sb.append("                }").append(nl());
    }

    /** The child view a field nests, or {@code null} when it nests none. */
    private static String childViewOf(Property property, Set<String> viewNames) {
        String erased = ValidationGenerator.erase(property.type());
        if (viewNames.contains(erased)) {
            return erased;
        }
        List<String> arguments = typeArguments(property.type());
        if (arguments.size() == 1 && viewNames.contains(ValidationGenerator.erase(arguments.get(0)))) {
            return ValidationGenerator.erase(arguments.get(0));
        }
        return null;
    }

    private static boolean collectionOf(Property property) {
        return typeArguments(property.type()).size() == 1;
    }

    /** Whether the developer's Write declares a setter for {@code name}. */
    private static boolean declares(ViewWriteInterface write, String name) {
        return write.setters().stream().anyMatch(setter -> setter.name().equals(name));
    }

    /** The child view's fully-qualified name, from the property's own type imports when it carries one. */
    private static String qualifiedViewName(Property property, String childView) {
        for (String importName : property.typeImports()) {
            String name = importName.startsWith("import ") ? importName.substring("import ".length()).trim() : importName;
            if (name.endsWith("." + childView)) {
                return name;
            }
        }
        return childView;
    }

    /** The single type argument of a parameterized declared type, or an empty list. */
    private static List<String> typeArguments(String declaredType) {
        String type = declaredType == null ? "" : declaredType.trim();
        int open = type.indexOf('<');
        int close = type.lastIndexOf('>');
        if (open < 0 || close <= open) {
            return List.of();
        }
        String inside = type.substring(open + 1, close);
        if (inside.indexOf('<') >= 0) {
            return List.of(inside);
        }
        return List.of(inside.trim());
    }

    /**
     * The setter argument for one declared type, or {@code null} when this applier cannot convert it yet.
     *
     * <p>Direct {@code JsonNode} accessors, plus the JDK types whose Jackson form is fixed by Jackson's own rules
     * rather than by a mapper configuration this generator cannot see. A date is deliberately absent: its
     * form depends on the caller's mapper (ISO text with the JavaTime module, something else without), and a
     * wrong guess fails at runtime rather than at compile time.</p>
     */
    private static String conversion(String declaredType) {
        String type = declaredType == null ? "" : declaredType.trim();
        return switch (type) {
            case "String", "java.lang.String", "CharSequence" -> "{node}.asText()";
            case "Long", "long", "java.lang.Long" -> "{node}.asLong()";
            case "Integer", "int", "java.lang.Integer" -> "{node}.asInt()";
            case "Boolean", "boolean", "java.lang.Boolean" -> "{node}.asBoolean()";
            case "Double", "double", "java.lang.Double" -> "{node}.asDouble()";
            case "Float", "float", "java.lang.Float" -> "{node}.asFloat()";
            case "Short", "short", "java.lang.Short" -> "{node}.asShort()";
            case "Byte", "byte", "java.lang.Byte" -> "{node}.asByte()";
            case "BigDecimal", "java.math.BigDecimal" -> "new java.math.BigDecimal({node}.asText())";
            case "BigInteger", "java.math.BigInteger" -> "new java.math.BigInteger({node}.asText())";
            case "UUID", "java.util.UUID" -> "java.util.UUID.fromString({node}.asText())";
            default -> null;
        };
    }

    private static String nl() {
        return System.lineSeparator();
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
