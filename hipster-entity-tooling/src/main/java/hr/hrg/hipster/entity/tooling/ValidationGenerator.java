package hr.hrg.hipster.entity.tooling;

import org.openrewrite.java.tree.J;

import hr.hrg.hipster.entity.tooling.meta.FieldConstraint;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bean Validation generation (plan.dsflash § 12.3/7.9–7.12).
 *
 * <h3>Source of truth</h3>
 * <p>The constraint annotation on the <strong>view accessor itself</strong>, read with JavaParser
 * exactly as {@code @FieldSource} already is. Nothing is duplicated into {@code @FieldSource} and no
 * second annotation style is invented: the author writes the constraint where a Bean Validation
 * provider would already look for it, and the generator carries it to the generated artifacts.</p>
 *
 * <h3>Two ways a constraint reaches the caller, and why both exist</h3>
 * <ul>
 *   <li><strong>Annotations</strong> on the generated record's components and the builders' fields,
 *       so a provider at the boundary (REST, JPA, a validator injected into a service) sees exactly
 *       what the author declared. This is the default for every constraint whose annotation is
 *       applicable to the field's type.</li>
 *   <li><strong>A generated {@code <View>Validator}</strong> with a concrete
 *       {@code List<String> validate(View)} body, for callers who want explicit violation messages
 *       without a provider. It is emitted for every view that carries at least one mechanically
 *       checkable constraint, and it is <em>not</em> a fallback for constraints the annotations could
 *       not express: an annotation that does not apply to its field's type is a modelling error, and
 *       the generator reports it rather than inventing a check.</li>
 * </ul>
 *
 * <p>The division is deliberate and documented so a later pass does not "simplify" it: the
 * annotations are the contract, the validator is the message. Neither replaces the other, and the
 * validator's checks are exactly the ones that can be written without re-implementing Bean
 * Validation — {@code @Email}, {@code @Past}/{@code @Future} and the {@code Decimal*} comparisons are
 * annotation-only, and the generated validator says so in its own javadoc rather than pretending.</p>
 *
 * <h3>What it refuses</h3>
 * <p>A recognised constraint on a type its annotation cannot apply to (say {@code @Size} on an
 * {@code Integer}) produces a {@code validation_constraint_type_mismatch} divergence and is
 * <strong>not</strong> emitted. A {@code jakarta.validation} annotation outside the recognised set
 * produces {@code validation_constraint_unsupported}. Both are reported rather than silently dropped,
 * because a constraint that quietly disappears is worse than one that fails loudly at the boundary.
 * An arbitrary annotation from another namespace is ignored — the generator has no way to know its
 * semantics, and pretending to check it would be worse than leaving it alone.</p>
 */
public final class ValidationGenerator {

    /**
     * The recognised constraint annotations, by simple name (plan.dsflash § 12.3/7.9).
     *
     * <p>Recognition is by simple name, because JavaParser resolves nothing: an author writing
     * {@code @NotNull} after importing {@code jakarta.validation.constraints.NotNull} and an author
     * writing the fully-qualified form produce the same AST. The set is the Bean Validation 3.0
     * built-in constraint list; it is published in {@code hipster-entity-tooling/README.md} so an
     * author can see what the generator understands without reading this file.</p>
     */
    public static final List<String> RECOGNISED = List.of(
            "NotNull", "Null", "NotEmpty", "NotBlank", "Size",
            "Min", "Max", "DecimalMin", "DecimalMax", "Digits",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "Pattern", "Email", "Past", "PastOrPresent", "Future", "FutureOrPresent",
            "AssertTrue", "AssertFalse", "Valid");

    /**
     * The constraints the generated {@code <View>Validator} can check mechanically, with an explicit
     * message. Everything else is annotation-only, and the generated validator's javadoc names the
     * ones it left to the provider.
     */
    private static final List<String> MECHANICAL = List.of(
            "NotNull", "Null", "NotEmpty", "NotBlank", "Size",
            "Min", "Max", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "Pattern", "AssertTrue", "AssertFalse");

    private ValidationGenerator() {
    }

    /**
     * The constraint annotations on one accessor, in declaration order.
     *
     * <p>Called from the property reader, so a constraint is captured wherever a {@code @FieldSource}
     * would be — the two annotations are read the same way, in the same place.</p>
     *
     * <h3>Phase 6</h3>
     * <p>Two API differences, neither of which changes the output:</p>
     * <ul>
     *   <li>The method is a {@link J.MethodDeclaration}. Nothing here needs a method's body, so the only
     *       consequence is that a caller must not hand in a constructor — and
     *       {@code TreeQueries.methodsOf} already excludes those.</li>
     *   <li>The annotation is a {@link J.Annotation} carrying the method name and the rendered
     *       arguments. Both the {@code @FieldSource} and validation namespaces are what is read, not
     *       JavaParser's four annotation node types.</li>
     * </ul>
     * <p>Recognition is still by simple name and namespace prefix, because no parser resolves
     * anything: an author writing {@code @jakarta.validation.constraints.NotNull} and one writing
     * {@code @NotNull} must both be recognised, and neither is checked against a classpath.</p>
     */
    public static List<FieldConstraint> constraintsOn(J.MethodDeclaration method) {
        List<FieldConstraint> constraints = new ArrayList<>();
        if (method == null) {
            return constraints;
        }
        for (J.Annotation annotation : method.getLeadingAnnotations()) {
            String name = renderedAnnotationName(annotation);
            FieldConstraint constraint = constraintOf(name, annotationArguments(annotation));
            if (constraint != null) {
                constraints.add(constraint);
            }
        }
        return constraints;
    }

    /**
     * The constraint an annotation describes, or {@code null} when it is not one this generator knows.
     *
     * <p>The whole recognition rule, taking the annotation's name as the source spells it. It lived
     * here so that "is this a constraint?" had one answer while two parsers were in flight, and it stays
     * here as the single owner of that answer now that there is one.</p>
     */
    private static FieldConstraint constraintOf(String name, String arguments) {
        int dot = name.lastIndexOf('.');
        String simpleName = dot >= 0 ? name.substring(dot + 1) : name;
        boolean validationNamespace = name.startsWith("jakarta.validation")
                || name.startsWith("javax.validation");
        if (!validationNamespace && !RECOGNISED.contains(simpleName)) {
            // Not a validation annotation at all. The generator has no way to know another
            // annotation's semantics, so it is left alone rather than guessed at.
            return null;
        }
        return new FieldConstraint(simpleName, arguments);
    }

    /**
     * The annotation's name as the source spells it — bare or qualified.
     *
     * <p>Read from the annotation type rather than from a printed node, because the namespace prefix is
     * what decides whether an unrecognised simple name is still a validation annotation:
     * {@code @jakarta.validation.constraints.Pattern} must be recognised on the prefix even though
     * {@code Pattern} could plausibly be anything.</p>
     */
    private static String renderedAnnotationName(J.Annotation annotation) {
        return annotation.getAnnotationType() == null ? "" : annotation.getAnnotationType().toString().trim();
    }

    /**
     * The annotation's argument text without the parentheses, or {@code ""} for a marker form.
     *
     * <p>The LST has one annotation node where JavaParser had four, and the forms are told apart by the
     * argument list — the same discrimination {@code ViewAnnotationReader} documents:</p>
     * <ul>
     *   <li>{@code getArguments() == null} &rarr; the bare marker {@code @NotNull}, so no text;</li>
     *   <li>a single {@link J.Empty} &rarr; {@code @NotNull()}, which is also no text;</li>
     *   <li>{@link J.Assignment} arguments &rarr; {@code name = value} pairs, joined with
     *       {@code ", "} in declaration order;</li>
     *   <li>any other single argument &rarr; the unnamed member form, rendered bare.</li>
     * </ul>
     */
    private static String annotationArguments(J.Annotation annotation) {
        List<org.openrewrite.java.tree.Expression> arguments = annotation.getArguments();
        if (arguments == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (org.openrewrite.java.tree.Expression argument : arguments) {
            if (argument instanceof J.Empty) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (argument instanceof J.Assignment assignment) {
                sb.append(TreeQueries.expressionText(assignment.getVariable())).append(" = ")
                        .append(TreeQueries.expressionText(assignment.getAssignment()));
            } else {
                sb.append(TreeQueries.expressionText(argument));
            }
        }
        return sb.toString();
    }

    /**
     * Whether the constraint's annotation is applicable to the declared type, which is what decides
     * between emitting the annotation and reporting a modelling error.
     *
     * <p>The rules are Bean Validation's own applicability rules, not a preference: {@code @Size}
     * supports {@code CharSequence}, {@code Collection}, {@code Map} and arrays; the numeric
     * constraints support the numeric types and {@code CharSequence}; {@code @Pattern} and
     * {@code @Email} support {@code CharSequence}; the temporal constraints support the
     * {@code java.time} types and the legacy date types; {@code @AssertTrue}/{@code @AssertFalse}
     * support {@code boolean}/{@code Boolean}; and {@code @NotNull}/{@code @Null}/{@code @Valid}
     * support anything.</p>
     */
    static boolean isApplicable(FieldConstraint constraint, String declaredType) {
        String erased = erase(declaredType);
        return switch (constraint.simpleName()) {
            case "NotNull", "Null", "Valid" -> true;
            case "NotEmpty", "Size" -> isCharSequence(erased) || isCollection(erased) || isMap(erased)
                    || erased.endsWith("[]");
            case "NotBlank", "Pattern", "Email" -> isCharSequence(erased);
            case "Min", "Max", "DecimalMin", "DecimalMax", "Digits" -> isNumeric(erased) || isCharSequence(erased);
            case "Positive", "PositiveOrZero", "Negative", "NegativeOrZero" -> isNumeric(erased);
            case "Past", "PastOrPresent", "Future", "FutureOrPresent" -> isTemporal(erased);
            case "AssertTrue", "AssertFalse" -> "boolean".equals(erased) || "Boolean".equals(erased);
            default -> false;
        };
    }

    private static boolean isCharSequence(String erased) {
        return "String".equals(erased) || "CharSequence".equals(erased)
                || "StringBuilder".equals(erased) || "StringBuffer".equals(erased);
    }

    private static boolean isCollection(String erased) {
        return "Collection".equals(erased) || "List".equals(erased) || "Set".equals(erased)
                || "Iterable".equals(erased) || "SortedSet".equals(erased) || "Queue".equals(erased)
                || "Deque".equals(erased);
    }

    private static boolean isMap(String erased) {
        return "Map".equals(erased) || "SortedMap".equals(erased) || "NavigableMap".equals(erased)
                || "ConcurrentMap".equals(erased);
    }

    private static boolean isNumeric(String erased) {
        return switch (erased) {
            case "int", "long", "short", "byte", "double", "float",
                 "Integer", "Long", "Short", "Byte", "Double", "Float",
                 "BigDecimal", "BigInteger", "Number" -> true;
            default -> false;
        };
    }

    private static boolean isTemporal(String erased) {
        return switch (erased) {
            case "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime",
                 "OffsetTime", "ZonedDateTime", "Year", "YearMonth", "MonthDay",
                 "Date", "Calendar", "HijrahDate", "JapaneseDate", "MinguoDate", "ThaiBuddhistDate" -> true;
            default -> false;
        };
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

    /** Every constraint of one view that will be emitted as an annotation, in field order. */
    static List<FieldConstraint> emittedConstraints(List<Property> properties) {
        List<FieldConstraint> emitted = new ArrayList<>();
        for (Property property : properties) {
            for (FieldConstraint constraint : property.constraints()) {
                if (RECOGNISED.contains(constraint.simpleName())
                        && isApplicable(constraint, property.type())) {
                    emitted.add(constraint);
                }
            }
        }
        return emitted;
    }

    /** The import lines a generated file needs for its constraints. */
    static Set<String> importsFor(List<Property> properties) {
        Set<String> imports = new LinkedHashSet<>();
        for (FieldConstraint constraint : emittedConstraints(properties)) {
            imports.add(constraint.qualifiedName());
        }
        return imports;
    }

    /**
     * Reports the constraints that cannot be honoured, and tells the caller which ones to emit.
     *
     * @param location a stable name for the diagnostics, e.g. {@code PersonSummary.firstName}
     * @return the constraints that may be annotated onto the generated artifacts
     */
    static List<Property> reportAndFilter(String viewName, List<Property> properties,
                                          DivergenceReporter divergences) {
        List<Property> kept = new ArrayList<>();
        for (Property property : properties) {
            List<FieldConstraint> honoured = new ArrayList<>();
            for (FieldConstraint constraint : property.constraints()) {
                if (!RECOGNISED.contains(constraint.simpleName())) {
                    divergences.report("validation_constraint_unsupported",
                            viewName + "." + property.name(),
                            "the annotation is not in the generator's recognised constraint set",
                            constraint.annotation(), "no generated code",
                            "annotate by hand, or add the constraint to the tooling README's list");
                    continue;
                }
                if (!isApplicable(constraint, property.type())) {
                    divergences.report("validation_constraint_type_mismatch",
                            viewName + "." + property.name(),
                            "the constraint does not apply to the field's declared type"
                                    + " (Bean Validation would throw at validation time)",
                            constraint.annotation() + " on " + property.type(),
                            "no annotation, no validator check",
                            "change the annotation or the field's type");
                    continue;
                }
                honoured.add(constraint);
            }
            // Every component is carried through, including the recorded locations: this rebuilds the
            // property to narrow its constraint list, and a component left out here would silently
            // disappear from the metadata's field map downstream (there is no compiler check for
            // "the generator dropped a fact", only a missing line in a document).
            kept.add(new Property(property.name(), property.type(), property.fieldKind(),
                    property.column(), property.relation(), property.expression(),
                    property.lineNumber(), honoured, property.typeImports(), property.sourcePath(),
                    property.locations()));
        }
        return kept;
    }

    /** What one generated validator contains, for diagnostics and tests. */
    public record Result(Path file, String className, List<String> checked, List<String> annotationOnly) {
    }

    /**
     * Emits {@code <View>Validator} for a view that carries at least one mechanically checkable
     * constraint; returns {@code null} when there is nothing to check.
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> properties) throws IOException {
        return generate(outputRoot, packageName, view, properties, null);
    }

    /**
     * As {@link #generate(Path, String, ViewMeta, List)}, reporting an edit to a generated member
     * (the follow-up plan's § 1.1).
     */
    public static Result generate(Path outputRoot, String packageName, ViewMeta view,
                                  List<Property> properties, DivergenceReporter divergences)
            throws IOException {
        List<String> checked = new ArrayList<>();
        List<String> annotationOnly = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (Property property : properties) {
            for (FieldConstraint constraint : property.constraints()) {
                String check = checkFor(view, property, constraint);
                if (check != null) {
                    body.append(check);
                    checked.add(property.name() + ":" + constraint.simpleName());
                } else if (RECOGNISED.contains(constraint.simpleName())
                        && isApplicable(constraint, property.type())) {
                    annotationOnly.add(property.name() + ":" + constraint.simpleName());
                }
            }
        }
        if (checked.isEmpty()) {
            return null;
        }

        String className = view.name() + "Validator";
        Path packageDir = packageName == null || packageName.isBlank()
                ? outputRoot
                : outputRoot.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Path file = packageDir.resolve(className + ".java");

        StringBuilder sb = new StringBuilder();
        String viewFqn = packageName == null || packageName.isBlank()
                ? view.name()
                : packageName + "." + view.name();
        sb.append(GeneratedCodeMarkers.fileHeader(ValidationGenerator.class.getName(),                 "Imperative constraint checks for the " + view.name() + " view."));
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/**\n");
        sb.append(" * Explicit violation messages for a {@link ").append(view.name())
                .append("}, without a Bean Validation provider.\n");
        sb.append(" *\n");
        sb.append(" * <p>The same constraints are also emitted as annotations on the generated record and\n");
        sb.append(" * builders, so a provider at the boundary sees them. This class is the other half: a\n");
        sb.append(" * caller that wants a message rather than a {@code ConstraintViolation} can call it\n");
        sb.append(" * directly, and the message text is committed source rather than a bundle lookup.</p>\n");
        sb.append(" *\n");
        if (!annotationOnly.isEmpty()) {
            sb.append(" * <p>Left to the provider, because a mechanical check would mean re-implementing\n");
            sb.append(" * Bean Validation rather than carrying the author's declaration through: ")
                    .append(String.join(", ", annotationOnly)).append(".</p>\n");
        }
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private ").append(className).append("() {\n    }\n\n");
        sb.append("    /** Every violation, in field order. An empty list means the view is valid. */\n");
        sb.append("    public static List<String> validate(").append(view.name()).append(" view) {\n");
        sb.append("        List<String> violations = new ArrayList<>();\n");
        sb.append("        if (view == null) {\n");
        sb.append("            violations.add(\"view: must not be null\");\n");
        sb.append("            return violations;\n");
        sb.append("        }\n");
        sb.append(body);
        sb.append("        return violations;\n");
        sb.append("    }\n");
        sb.append("}\n");

        // Cooperative re-emission (DEC-020, the follow-up plan's § 1.1): a developer who added a helper
        // method, a nested type or a field to this generated class keeps it, and an edit to the
        // generated `validate` method is reported rather than silently replaced. The same reconciliation
        // every other whole-file emitter runs — this one and the mapper were the two that were missing
        // it, which is why a hand-written member inside them used to disappear on the next pass.
        CooperativeCodegen.Reconciled reconciled =
                CooperativeCodegen.reconcileMembers(file, className, sb.toString());
        Files.writeString(file, reconciled.source());
        if (divergences != null) {
            divergences.addAll(reconciled.divergences());
        }
        return new Result(file, className, checked, annotationOnly);
    }

    /**
     * The imperative check for one constraint, or {@code null} when it is annotation-only.
     *
     * <p>A null field is skipped by every check except the null ones: Bean Validation treats a
     * {@code null} value as valid for {@code @Size}, {@code @Min} and the rest, so a hand-written
     * checker that threw on a null name would be stricter than the annotations it mirrors — and a
     * checker that disagrees with the contract it duplicates is worse than no checker.</p>
     */
    private static String checkFor(ViewMeta view, Property property, FieldConstraint constraint) {
        if (!MECHANICAL.contains(constraint.simpleName())
                || !isApplicable(constraint, property.type())) {
            return null;
        }
        String accessor = "view." + property.name() + "()";
        String label = property.name();
        String erased = erase(property.type());
        String condition = switch (constraint.simpleName()) {
            case "NotNull" -> accessor + " == null";
            case "Null" -> accessor + " != null";
            case "NotEmpty" -> accessor + " != null && " + accessor + ".isEmpty()";
            case "NotBlank" -> accessor + " != null && " + accessor + ".isBlank()";
            case "Size" -> accessor + " != null && !(" + sizeExpression(constraint, accessor, erased) + ")";
            case "Min" -> accessor + " != null && " + numeric(accessor, erased)
                    + ".compareTo(" + decimal(constraint, "value", "0") + ") < 0";
            case "Max" -> accessor + " != null && " + numeric(accessor, erased)
                    + ".compareTo(" + decimal(constraint, "value", "0") + ") > 0";
            case "Positive" -> accessor + " != null && " + numeric(accessor, erased)
                    + ".signum() <= 0";
            case "PositiveOrZero" -> accessor + " != null && " + numeric(accessor, erased)
                    + ".signum() < 0";
            case "Negative" -> accessor + " != null && " + numeric(accessor, erased)
                    + ".signum() >= 0";
            case "NegativeOrZero" -> accessor + " != null && " + numeric(accessor, erased)
                    + ".signum() > 0";
            case "Pattern" -> accessor + " != null && !" + accessor + ".matches("
                    + stringMember(constraint, "regexp") + ")";
            case "AssertTrue" -> accessor + " != null && !" + accessor;
            case "AssertFalse" -> accessor + " != null && " + accessor;
            default -> null;
        };
        if (condition == null) {
            return null;
        }
        String message = messageFor(constraint, label);
        return "        if (" + condition + ") {\n"
                + "            violations.add(" + message + ");\n"
                + "        }\n";
    }

    /**
     * {@code @Size}'s check, whose receiver differs by type: {@code length} for an array,
     * {@code length()} for a {@code CharSequence}, {@code size()} for a {@code Collection} or
     * {@code Map}. Getting this wrong is not cosmetic — {@code String.size()} does not compile.
     */
    private static String sizeExpression(FieldConstraint constraint, String accessor, String erased) {
        String size;
        if (erased.endsWith("[]")) {
            size = accessor + ".length";
        } else if (isCharSequence(erased)) {
            size = accessor + ".length()";
        } else {
            size = accessor + ".size()";
        }
        String min = constraint.arguments().isEmpty() ? null : memberValue(constraint, "min", null);
        String max = constraint.arguments().isEmpty() ? null : memberValue(constraint, "max", null);
        if (min == null && max == null && !constraint.arguments().isEmpty()
                && !constraint.arguments().contains("=")) {
            // The single-member form `@Size(5)` means exactly 5.
            return size + " == " + constraint.arguments();
        }
        StringBuilder sb = new StringBuilder();
        if (min != null) {
            sb.append(size).append(" >= ").append(min);
        }
        if (max != null && !"Integer.MAX_VALUE".equals(max) && !"2147483647".equals(max)) {
            if (sb.length() > 0) {
                sb.append(" && ");
            }
            sb.append(size).append(" <= ").append(max);
        }
        return sb.length() == 0 ? "true" : sb.toString();
    }

    /**
     * A numeric receiver for the comparison constraints.
     *
     * <p>{@code CharSequence} is supported by {@code @Min}/{@code @Max} in Bean Validation, so a
     * {@code String} field is coerced to {@code new java.math.BigDecimal(...)}; that is the one place
     * the generated validator does more than read a value, and it is the documented behaviour of the
     * annotation it mirrors.</p>
     */
    private static String numeric(String accessor, String erased) {
        if (isCharSequence(erased)) {
            return "new java.math.BigDecimal(" + accessor + ")";
        }
        if (erased.endsWith("[]") || isCollection(erased) || isMap(erased)) {
            return "java.math.BigDecimal.valueOf(" + accessor + ")";
        }
        return "java.math.BigDecimal.valueOf(" + accessor + ")";
    }

    private static String decimal(FieldConstraint constraint, String member, String fallback) {
        String value = memberValue(constraint, member, fallback);
        return "new java.math.BigDecimal(" + quoteIfNotNumeric(value) + ")";
    }

    private static String stringMember(FieldConstraint constraint, String member) {
        String value = memberValue(constraint, member, null);
        if (value == null) {
            return "\"\"";
        }
        return value.startsWith("\"") ? value : "\"" + value + "\"";
    }

    private static String quoteIfNotNumeric(String value) {
        if (value == null) {
            return "0";
        }
        try {
            new java.math.BigDecimal(value.trim().replace("L", "").replace("l", ""));
            return value;
        } catch (NumberFormatException notANumber) {
            return value.startsWith("\"") ? value : "\"" + value + "\"";
        }
    }

    /**
     * The value of one constraint member from the annotation's source text, or {@code fallback}.
     *
     * <p>Read from the text the author wrote rather than from a parsed model, for the same reason
     * {@link FieldConstraint} keeps the text: the generator carries the declaration through.</p>
     */
    static String memberValue(FieldConstraint constraint, String member, String fallback) {
        String arguments = constraint.arguments();
        if (arguments == null || arguments.isEmpty()) {
            return fallback;
        }
        if (!arguments.contains("=")) {
            // The single-member form: @Min(1), @Pattern("[a-z]+")
            return arguments;
        }
        for (String part : arguments.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals(member)) {
                return pair[1].trim();
            }
        }
        return fallback;
    }

    /** A member value with its surrounding source quotes removed, for a message. */
    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "?" : value;
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String messageFor(FieldConstraint constraint, String label) {
        String text = switch (constraint.simpleName()) {
            case "NotNull" -> "must not be null";
            case "Null" -> "must be null";
            case "NotEmpty" -> "must not be empty";
            case "NotBlank" -> "must not be blank";
            case "Size" -> "size is out of range (" + constraint.arguments() + ")";
            case "Min" -> "must be >= " + memberValue(constraint, "value", "?");
            case "Max" -> "must be <= " + memberValue(constraint, "value", "?");
            case "Positive" -> "must be positive";
            case "PositiveOrZero" -> "must be positive or zero";
            case "Negative" -> "must be negative";
            case "NegativeOrZero" -> "must be negative or zero";
            case "Pattern" -> "must match " + unquote(memberValue(constraint, "regexp", "?"));
            case "AssertTrue" -> "must be true";
            case "AssertFalse" -> "must be false";
            default -> "is " + constraint.simpleName().toLowerCase(Locale.ROOT);
        };
        return "\"" + label + ": " + text.replace("\"", "'") + "\"";
    }
}
