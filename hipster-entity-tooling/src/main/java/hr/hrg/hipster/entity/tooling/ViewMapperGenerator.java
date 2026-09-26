package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.generated.GeneratedCodeMarkers;

import hr.hrg.hipster.entity.tooling.meta.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a statically-dispatched mapper between two views (plan.dsflash § 12.2/7.5–7.8).
 *
 * <h3>Shape</h3>
 * <pre>{@code
 * // {@link shop.dto.PersonDto} Mapper from the declared source view to PersonDto.
 * public final class PersonSummaryToPersonDtoMapper {
 *     private PersonSummaryToPersonDtoMapper() { }
 *
 *     public static PersonDto toPersonDto(PersonSummary src) {
 *         return new PersonDto.Record(
 *                 src.id(),
 *                 src.firstName(),
 *                 src.age() == null ? null : src.age().longValue(),
 *                 null);
 *     }
 * }
 * }</pre>
 *
 * <p>Every argument is a <strong>direct method call on the source view</strong> or a literal
 * {@code null}. There is no reflection, no {@code Map<String, Method>}, no bean-copy library — the
 * emitted class is ordinary committed Java a stock IDE can navigate from the mapper to both views
 * (AGENTS.md § 1, DEC-019). That is the whole point of generating it rather than reaching for a
 * runtime bean copier.</p>
 *
 * <h3>Type policy — "map what is provably safe, report the rest"</h3>
 * <p>The generator never emits a lossy cast. A field is mapped when the two declared types are the
 * same, when the target provably accepts everything the source can hold (a widening numeric
 * conversion, or an upcast to {@code Object}), or when the source is a primitive that widens
 * implicitly. Everything else produces a DEC-022 diagnostic and a literal {@code null} in that
 * position, so the emitted code still compiles and the omission is visible in the regeneration
 * report rather than in production.</p>
 *
 * <p>A divergent-type converter registry is deliberately out of scope: it would be a second place to
 * configure the mapping, and the plan's rule is that the generator maps what is provably safe and
 * reports the rest.</p>
 *
 * <h3>Missing fields are a report, not a silent omission</h3>
 * <p>A field present in one view and absent from the other is exactly the kind of defect no compiler
 * catches, so it is reported in <em>both</em> directions: a target field with no source (mapped to
 * {@code null}) and a source field the target cannot receive (emitted nowhere, but named). Neither is
 * an error — two views are allowed to differ — but both are visible.</p>
 */
public final class ViewMapperGenerator {

    private ViewMapperGenerator() {
    }

    /**
     * One end of a mapping request.
     *
     * @param qualifiedName      the view's fully-qualified name, used for imports and diagnostics
     * @param packageName        the view's package
     * @param simpleName         the view's simple name
     * @param ordinalProperties  the view's fields in <strong>ledger order</strong>, tombstones included
     * @param recordConstruction the constructor that builds the view, or {@code null} when the view has
     *                           no record at its level and must be built through
     *                           {@code <View>_.META.create(Object[])}
     */
    public record ViewRef(String qualifiedName, String packageName, String simpleName,
                          List<Property> ordinalProperties, String recordConstruction) {

        public ViewRef {
            ordinalProperties = List.copyOf(ordinalProperties);
        }
    }

    /** What the emitted mapper ended up containing, for diagnostics and tests. */
    public record Result(Path file, String className, String methodName,
                         List<String> mapped, List<String> unmapped) {
    }

    /** The class name a request produces when it does not name one. */
    public static String defaultClassName(String sourceSimpleName, String targetSimpleName) {
        return sourceSimpleName + "To" + targetSimpleName + "Mapper";
    }

    /** The method name a request produces when it does not name one: {@code to<Target>}. */
    public static String defaultMethodName(String targetSimpleName) {
        return "to" + targetSimpleName;
    }

    /**
     * Emits the mapper into the <strong>target</strong> view's package.
     *
     * <p>The target's package is the right home for two reasons: the mapper's return type and the
     * record it constructs are both the target, so neither needs an import, and a mapper exists
     * because someone wants <em>that</em> type. The source is named by a fully-qualified reference
     * when it lives in another package, which is always correct and needs no import bookkeeping.</p>
     */
    public static Result generate(Path outputRoot, String className, String methodName,
                                  ViewRef source, ViewRef target, DivergenceReporter divergences)
            throws IOException {
        Path packageDir = target.packageName() == null || target.packageName().isBlank()
                ? outputRoot
                : outputRoot.resolve(target.packageName().replace('.', '/'));
        Files.createDirectories(packageDir);
        Path file = packageDir.resolve(className + ".java");

        Body body = buildBody(className, methodName, source, target, divergences);
        // Cooperative re-emission (DEC-020, the follow-up plan's § 1.1): a developer who added a helper
        // method or nested type to this generated mapper keeps it, and an edit to the generated mapping
        // method is reported rather than silently replaced. This emitter and ValidationGenerator were the
        // two whole-file emitters still overwriting outright.
        CooperativeCodegen.Reconciled reconciled = CooperativeCodegen.reconcileMembers(
                file, className, source(source, target, className, methodName, body));
        Files.writeString(file, reconciled.source());
        if (divergences != null) {
            divergences.addAll(reconciled.divergences());
        }
        return new Result(file, className, methodName, body.mapped(), body.unmapped());
    }

    private record Body(List<String> arguments, List<String> mapped, List<String> unmapped) {
    }

    /**
     * Decides the argument for every target component, in ledger order, and records what could not be
     * mapped.
     */
    private static Body buildBody(String className, String methodName, ViewRef source, ViewRef target,
                                  DivergenceReporter divergences) {
        List<String> arguments = new ArrayList<>();
        List<String> mapped = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        String location = className + "." + methodName;

        for (Property component : target.ordinalProperties()) {
            if (ViewAdapterGenerator.isRetired(component)) {
                // An R1.4 tombstone keeps its slot and nothing else: no accessor to read, no column to
                // write, so the position is a literal null in the record.
                arguments.add("null");
                continue;
            }
            Property sourceField = findByName(source.ordinalProperties(), component.name());
            if (sourceField == null) {
                arguments.add("null");
                unmapped.add(component.name());
                divergences.report("mapper_field_missing_in_source", location + "." + component.name(),
                        "the target view declares the accessor and the source view does not",
                        "absent", "a literal null",
                        "add the accessor to " + source.simpleName() + ", or accept the default");
                continue;
            }
            if (ViewAdapterGenerator.isRetired(sourceField)) {
                // The source's slot exists but has no accessor, so it cannot be read either.
                arguments.add("null");
                unmapped.add(component.name());
                divergences.report("mapper_field_missing_in_source", location + "." + component.name(),
                        "the source view's slot for this field is a retired tombstone with no accessor",
                        "tombstone", "a literal null",
                        "nothing: a retired field carries no value any more");
                continue;
            }

            String conversion = conversion(sourceField.type(), component.type());
            if (conversion == null) {
                arguments.add("null");
                unmapped.add(component.name());
                divergences.report("mapper_type_incompatible", location + "." + component.name(),
                        "no provably safe conversion from the source type to the target type",
                        sourceField.type(), component.type(),
                        "the field is left unmapped (a literal null), never a lossy cast");
                continue;
            }
            arguments.add(conversion.replace("$", "src." + component.name() + "()"));
            mapped.add(component.name());
        }

        // The other direction: a source field the target cannot receive. Nothing is emitted — there is
        // nowhere to put it — but a silent discard is a data-loss bug no compiler catches (§ 12.2/7.7).
        for (Property sourceField : source.ordinalProperties()) {
            if (ViewAdapterGenerator.isRetired(sourceField)) {
                continue;
            }
            if (findByName(target.ordinalProperties(), sourceField.name()) == null) {
                divergences.report("mapper_field_missing_in_target", location + "." + sourceField.name(),
                        "the source view declares the accessor and the target view does not",
                        sourceField.type(), "no target component",
                        "nothing is emitted: " + sourceField.name() + " is discarded by this mapping");
            }
        }
        return new Body(arguments, mapped, unmapped);
    }

    /**
     * The argument expression for one field, or {@code null} when no safe conversion exists.
     *
     * <p>{@code $} stands for the source accessor call, so the caller substitutes the field name once
     * and the widening wrappers stay readable.</p>
     *
     * <p>The conversions are <strong>widening only</strong>. A narrowing — say {@code Long} to
     * {@code Integer} — is rejected even though a cast would compile, because it silently truncates on
     * overflow. A reference-to-primitive target is rejected for the same reason in the other
     * direction: the source may legitimately hold {@code null} (S4 says absent is a real state) and a
     * primitive target cannot represent it, so an implicit unboxing would turn a nullable field into
     * an NPE at a place the compiler cannot flag.</p>
     */
    static String conversion(String sourceDeclaredType, String targetDeclaredType) {
        String sourceErased = erase(sourceDeclaredType);
        String targetErased = erase(targetDeclaredType);
        boolean sourcePrimitive = isPrimitive(sourceErased);
        boolean targetPrimitive = isPrimitive(targetErased);

        if (sourceErased.equals(targetErased)) {
            return "$";
        }
        // Everything can be read as Object without losing anything.
        if ("Object".equals(targetErased)) {
            return "$";
        }
        if (targetPrimitive) {
            return null;
        }
        if (sourcePrimitive) {
            // A primitive widens implicitly into a wider reference parameter (Java boxes and widens in
            // one step for int/long/double). Restricted to the widenings that are genuinely implicit,
            // so the emitted file does not depend on a subtlety of overload resolution.
            return switch (sourceErased + "->" + targetErased) {
                case "int->Long", "int->Double", "long->Long", "long->Double", "float->Double",
                     "double->Double", "short->Long", "short->Integer", "byte->Long", "byte->Integer",
                     "boolean->Boolean", "char->Character" -> "$";
                default -> null;
            };
        }
        // Reference to reference: a widening numeric conversion, guarded so a null source stays null
        // (S4: an absent value must not become a boxed zero).
        String accessor = switch (sourceErased + "->" + targetErased) {
            case "Integer->Long", "Short->Long", "Byte->Long" -> "longValue()";
            case "Integer->Double", "Long->Double", "Float->Double", "Short->Double", "Byte->Double" -> "doubleValue()";
            case "Short->Integer", "Byte->Integer" -> "intValue()";
            default -> null;
        };
        if (accessor == null) {
            return null;
        }
        return "$ == null ? null : $" + "." + accessor;
    }

    /** The erased simple name of a declared type: generics and package qualifiers removed. */
    static String erase(String declaredType) {
        if (declaredType == null) {
            return "Object";
        }
        String type = declaredType.trim();
        int generic = type.indexOf('<');
        if (generic >= 0) {
            type = type.substring(0, generic);
        }
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static boolean isPrimitive(String erased) {
        return switch (erased) {
            case "int", "long", "double", "float", "short", "byte", "boolean", "char" -> true;
            default -> false;
        };
    }

    private static Property findByName(List<Property> properties, String name) {
        for (Property property : properties) {
            if (property.name().equals(name)) {
                return property;
            }
        }
        return null;
    }

    private static String source(ViewRef source, ViewRef target, String className, String methodName,
                                 Body body) {
        StringBuilder sb = new StringBuilder();
        String targetFqn = target.packageName() == null || target.packageName().isBlank()
                ? target.simpleName()
                : target.packageName() + "." + target.simpleName();
        sb.append(GeneratedCodeMarkers.fileHeader(ViewMapperGenerator.class.getName(),                 "Mapper from the declared source view to " + target.simpleName() + "."));
        if (target.packageName() != null && !target.packageName().isBlank()) {
            sb.append("package ").append(target.packageName()).append(";\n\n");
        }

        Set<String> imports = new LinkedHashSet<>(JdkImportSupport.importsFor(target.ordinalProperties()));
        boolean viaMeta = target.recordConstruction() == null;
        if (viaMeta) {
            imports.add("hr.hrg.hipster.entity.api.ViewMeta");
        }
        for (String importName : imports) {
            sb.append("import ").append(importName).append(";\n");
        }
        sb.append('\n');

        String sourceReference = samePackage(source, target) ? source.simpleName() : source.qualifiedName();

        sb.append("/**\n");
        sb.append(" * Maps a {@link ").append(source.qualifiedName()).append("} to a {@link ")
                .append(target.simpleName()).append("} by direct field access.\n");
        sb.append(" *\n");
        sb.append(" * <p>Generated: the mapping is a real method body, not a reflective bean copy, so a\n");
        sb.append(" * rename of either view is a compile error here instead of a silently dropped field.\n");
        sb.append(" * A field the generator could not map safely is a literal {@code null} and is named\n");
        sb.append(" * in the regeneration report.</p>\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private ").append(className).append("() {\n    }\n\n");

        sb.append("    /** One-way mapping. A null source is not accepted; null fields map to null. */\n");
        sb.append("    public static ").append(target.simpleName()).append(' ').append(methodName)
                .append('(').append(sourceReference).append(" src) {\n");
        sb.append("        return ").append(construction(target, body)).append(";\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean samePackage(ViewRef source, ViewRef target) {
        String left = source.packageName() == null ? "" : source.packageName();
        String right = target.packageName() == null ? "" : target.packageName();
        return left.equals(right);
    }

    private static String construction(ViewRef target, Body body) {
        if (target.recordConstruction() != null) {
            return "new " + target.recordConstruction() + "(" + String.join(", ", body.arguments()) + ")";
        }
        // No record at this level: build through the view's own metadata, which is the same positional
        // contract create() has always used, so the mapper stays independent of the target's GenLevel.
        return target.simpleName() + "_.META.create(new Object[] { "
                + String.join(", ", body.arguments()) + " })";
    }
}
