package hr.hrg.hipster.entity.tooling;

import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * The single reader for the {@code @View} annotation (plan.dsflash § 4.5/G9, § 4.7/DR-9).
 *
 * <p>This is the <strong>only</strong> place in the codebase that inspects the annotation's
 * syntactic shape, and it is called by both the validator ({@code ViewAnnotationRule}) and the
 * generator ({@code EntityMetadataGenerator}) — the same one-owner discipline as
 * {@link GenLevelResolver}.</p>
 *
 * <h3>Why shape-blind matters</h3>
 * <p>The source carries four syntactic forms of the annotation, and live views depend on the form a
 * {@code NormalAnnotationExpr}-restricted parser drops:</p>
 * <table>
 *   <caption>Shape census</caption>
 *   <tr><th>Source form</th><th>Live witness</th></tr>
 *   <tr><td>bare {@code @View} — no parentheses</td>
 *       <td>{@code person/entity/PersonDto.java}, {@code person/entity/PersonUpdateForm.java}</td></tr>
 *   <tr><td>{@code @View()} — parentheses, no attributes</td>
 *       <td>{@code person/entity/PersonAuditable.java}, {@code PersonCreateForm.java}, the four
 *           {@code paymentMethod} subclasses</td></tr>
 *   <tr><td>{@code @View(gen = …, addons = {…})} — attributes</td>
 *       <td>{@code person/entity/PersonSummary.java}, {@code person/iface/Person.java}</td></tr>
 *   <tr><td>{@code @View(true)} — a single unnamed member</td>
 *       <td>no live site; the real annotation has no {@code value()} member, so no such source can
 *           compile. Reported as {@code unsupported_view_annotation_form}.</td></tr>
 * </table>
 *
 * <p>Discovery must never use this class: it is shape-blind by construction
 * ({@code TreeQueries.hasAnnotation(decl, "View")}).</p>
 *
 * <h3>Phase 6: how the shapes are told apart now</h3>
 * <p>JavaParser had four distinct node types for the four forms, so the old code branched on
 * {@code isMarkerAnnotationExpr()} / {@code isSingleMemberAnnotationExpr()} /
 * {@code isNormalAnnotationExpr()}. The LST has <strong>one</strong> type, {@link J.Annotation}, and
 * distinguishes the forms by its argument list:</p>
 * <ul>
 *   <li>bare {@code @View} &rarr; {@code getArguments() == null};</li>
 *   <li>{@code @View()} &rarr; a single {@link J.Empty} argument;</li>
 *   <li>{@code @View(x)} &rarr; a single argument that is not an {@link J.Assignment};</li>
 *   <li>{@code @View(gen = x)} &rarr; {@link J.Assignment} arguments.</li>
 * </ul>
 * <p>That mapping is exact — the four source forms remain distinguishable — so the shape-blindness
 * the class depends on is preserved rather than narrowed.</p>
 */
public final class ViewAnnotationReader {

    private ViewAnnotationReader() {
    }

    /** A parse result plus the diagnostics the reader produced while parsing. */
    public record Parsed(ViewAttributes attributes, List<String> diagnostics) {

        public Parsed {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * Parses any syntactic form of {@code @View}.
     *
     * <p>Never returns {@code null} and never throws: an unsupported or unreadable form falls back
     * to {@link GenLevel#DEFAULT} with a diagnostic, so a malformed annotation can never crash a
     * generation pass ("never a crash, and never a silent {@code META}" — § 4.5/G9 rule 3).</p>
     */
    public static Parsed parse(J.Annotation view) {
        List<String> diagnostics = new ArrayList<>();
        ViewAttributes defaults = new ViewAttributes(GenLevel.DEFAULT, "", List.of());
        if (view == null) {
            return new Parsed(defaults, diagnostics);
        }

        // Marker form (no parentheses) and empty-pairs form (`@View()`) both mean
        // "all defaults". The LST tells them apart from the attribute forms by the
        // argument list: null, or a lone J.Empty placeholder.
        if (!TreeQueries.hasAnnotationArguments(view)) {
            return new Parsed(defaults, diagnostics);
        }

        // A single member that names no attribute: `@View(true)`. The real annotation
        // declares no value() member, so this cannot come from compiling source; it
        // survives only in old parser comments. Resolve to DEFAULT with a diagnostic
        // rather than guessing a level.
        if (!hasNamedArgument(view)) {
            diagnostics.add("unsupported_view_annotation_form: @View("
                    + view.getArguments().get(0)
                    + ") has no matching annotation member; treating gen as DEFAULT");
            return new Parsed(defaults, diagnostics);
        }

        GenLevel gen = GenLevel.DEFAULT;
        String discriminatorField = "";
        List<String> addons = new ArrayList<>();

        for (Expression argument : view.getArguments()) {
            if (argument instanceof J.Empty) {
                continue;
            }
            if (!(argument instanceof J.Assignment assignment)) {
                continue;
            }
            String name = TreeQueries.assignmentName(assignment);
            Expression value = assignment.getAssignment();
            switch (name) {
                case "gen" -> gen = parseGenLevel(value, diagnostics);
                case "discriminatorField" -> discriminatorField = parseStringLiteral(value);
                case "addons" -> addons.addAll(parseAddonNames(value, diagnostics));
                default -> diagnostics.add("unknown_view_attribute: @" + name
                        + " is not a member of @View and is ignored");
            }
        }

        return new Parsed(new ViewAttributes(gen, discriminatorField, addons), diagnostics);
    }

    /** Whether the annotation's arguments are named attributes rather than one bare member. */
    private static boolean hasNamedArgument(J.Annotation view) {
        if (view.getArguments() == null) {
            return false;
        }
        for (Expression argument : view.getArguments()) {
            if (argument instanceof J.Assignment) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a {@code gen} value by its <strong>last identifier segment</strong>, so both
     * {@code GenLevel.META} and {@code hr.hrg.hipster.entity.api.GenLevel.META} work with no class
     * loading and no import-table lookup (§ 4.5/G9 rule 3).
     *
     * <p>An unknown constant is the {@code unknown_gen_level} diagnostic and falls back to
     * {@code DEFAULT}.</p>
     */
    private static GenLevel parseGenLevel(Expression value, List<String> diagnostics) {
        String last = lastSegment(value);
        for (GenLevel level : GenLevel.values()) {
            if (level.name().equals(last)) {
                return level;
            }
        }
        diagnostics.add("unknown_gen_level: '" + value + "' is not a GenLevel constant; falling back to DEFAULT");
        return GenLevel.DEFAULT;
    }

    /**
     * The last identifier of a possibly qualified name expression.
     *
     * <p>{@code GenLevel.META} is an {@link J.FieldAccess} in the LST, so its final segment is the
     * constant; a bare {@code META} is a {@link J.Identifier}.</p>
     */
    private static String lastSegment(Expression value) {
        if (value instanceof J.Identifier identifier) {
            return identifier.getSimpleName();
        }
        if (value instanceof J.FieldAccess access) {
            return access.getName().getSimpleName();
        }
        String text = value.toString();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    /**
     * The text of a string literal.
     *
     * <p>The LST holds every literal in one {@link J.Literal} node discriminated by its
     * {@code JavaType.Primitive}, so a string is recognised by its primitive rather than by a
     * dedicated node type. A non-literal value is returned as its printed source, because a
     * discriminator that cannot be resolved statically is reported through the divergence path
     * rather than being a crash here.</p>
     */
    private static String parseStringLiteral(Expression value) {
        if (value instanceof J.Literal literal && literal.getValue() instanceof String text) {
            return text;
        }
        return value.toString();
    }

    /**
     * Reads {@code addons = {A.class, B.class}}, {@code addons = {}} or an absent/odd form.
     * A trailing {@code .class} and any package prefix are stripped, yielding simple names that the
     * generator resolves against the package's {@code interfaceMap} (§ 4.5/G9 rule 4).
     */
    private static List<String> parseAddonNames(Expression value, List<String> diagnostics) {
        List<String> names = new ArrayList<>();
        List<Expression> entries = new ArrayList<>();
        if (value instanceof J.NewArray array && array.getInitializer() != null) {
            // The LST collapses JavaParser's ArrayCreationExpr and ArrayInitializerExpr
            // onto one J.NewArray, whose initializer is the element list itself — there
            // is no separate ArrayInitializer node to unwrap.
            entries.addAll(array.getInitializer());
        } else if (value != null) {
            entries.add(value);
        }
        for (Expression entry : entries) {
            // An empty initialiser is held as a single `J.Empty` placeholder rather than as an empty
            // list — the same shape the LST uses for an empty parameter list, and the reason
            // `@View(addons = {})` read as one addon named `Empty`. It is "no entry", not "an entry
            // whose name could not be read", so it is skipped silently rather than diagnosed.
            if (entry instanceof J.Empty) {
                continue;
            }
            String simple = simpleClassName(entry);
            if (simple == null || simple.isEmpty()) {
                diagnostics.add("unresolved_addon: cannot read an addon class from '" + entry + "'");
                continue;
            }
            names.add(simple);
        }
        return names;
    }

    /**
     * {@code com.example.Foo.class} &rarr; {@code Foo}.
     *
     * <p>The LST has no class-literal node: {@code Foo.class} is a {@link J.FieldAccess} on the type
     * name whose member is the identifier {@code class}. Reading the target of that access is what
     * recovers the class, and it is checked before falling back to the printed text so the common
     * form does not depend on string parsing.</p>
     */
    private static String simpleClassName(Expression entry) {
        String text;
        if (entry instanceof J.FieldAccess access && "class".equals(access.getName().getSimpleName())) {
            text = access.getTarget().toString();
        } else {
            text = entry.toString();
        }
        int generic = text.indexOf('<');
        if (generic >= 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1).trim() : text.trim();
    }

    /** Convenience for callers that only need the attributes. */
    public static ViewAttributes read(J.Annotation view) {
        return parse(view).attributes();
    }

    // --------------------------------------------- shared attribute decoding ---
    //
    // These three run on a *rendered* attribute value, so the LST path and the
    // JavaParser bridge path cannot disagree about what an attribute means. The LST path
    // below predicates on node types first and only falls back to text, so it keeps its
    // structural precision where it matters (a class literal, a string literal).

    /** Resolves a {@code gen} value from its source text, by last segment. */
    private static GenLevel genLevelFromText(String rendered, List<String> diagnostics) {
        String text = rendered.trim();
        int dot = text.lastIndexOf('.');
        String last = dot >= 0 ? text.substring(dot + 1) : text;
        for (GenLevel level : GenLevel.values()) {
            if (level.name().equals(last)) {
                return level;
            }
        }
        diagnostics.add("unknown_gen_level: '" + rendered + "' is not a GenLevel constant; falling back to DEFAULT");
        return GenLevel.DEFAULT;
    }

    /** The content of a string literal written in source. */
    private static String stringLiteralFromText(String rendered) {
        String text = rendered.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return rendered;
    }

    /** Simple class names from a rendered {@code {A.class, B.class}}, or from a single class literal. */
    private static List<String> addonNamesFromText(String rendered, List<String> diagnostics) {
        String body = rendered.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> names = new ArrayList<>();
        for (String raw : body.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String text = entry.endsWith(".class")
                    ? entry.substring(0, entry.length() - ".class".length())
                    : entry;
            int generic = text.indexOf('<');
            if (generic >= 0) {
                text = text.substring(0, generic);
            }
            int dot = text.lastIndexOf('.');
            String simple = (dot >= 0 ? text.substring(dot + 1) : text).trim();
            if (simple.isEmpty()) {
                diagnostics.add("unresolved_addon: cannot read an addon class from '" + raw.trim() + "'");
                continue;
            }
            names.add(simple);
        }
        return names;
    }
}
