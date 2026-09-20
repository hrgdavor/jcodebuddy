package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single reader for the {@code @View} annotation (plan.dsflash § 4.5/G9, § 4.7/DR-9).
 *
 * <p>This is the <strong>only</strong> place in the codebase that inspects the annotation's
 * syntactic shape, and it is called by both the validator ({@code ViewAnnotationRule}) and the
 * generator ({@code EntityMetadataGenerator}) — the same one-owner discipline as
 * {@link GenLevelResolver}.</p>
 *
 * <h3>Why shape-blind matters</h3>
 * <p>The tree carries four syntactic forms of the annotation, and two live views depend on the one
 * form a {@code NormalAnnotationExpr}-restricted parser drops:</p>
 * <table>
 *   <caption>Shape census</caption>
 *   <tr><th>JavaParser shape</th><th>Live witness</th></tr>
 *   <tr><td>{@code MarkerAnnotationExpr} — bare {@code @View}</td>
 *       <td>{@code person/entity/PersonDto.java}, {@code person/entity/PersonUpdateForm.java}</td></tr>
 *   <tr><td>{@code NormalAnnotationExpr}, no pairs — {@code @View()}</td>
 *       <td>{@code person/entity/PersonAuditable.java}, {@code PersonCreateForm.java}, the four
 *           {@code paymentMethod} subclasses</td></tr>
 *   <tr><td>{@code NormalAnnotationExpr}, with pairs — {@code @View(gen = …, addons = {…})}</td>
 *       <td>{@code person/entity/PersonSummary.java}, {@code person/iface/Person.java}</td></tr>
 *   <tr><td>{@code SingleMemberAnnotationExpr} — {@code @View(true)}</td>
 *       <td>no live site; the real annotation has no {@code value()} member, so no such source can
 *           compile. Reported as {@code unsupported_view_annotation_form}.</td></tr>
 * </table>
 *
 * <p>Discovery must never use this class: it is shape-blind by construction
 * ({@code decl.getAnnotationByName("View").isPresent()}).</p>
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
    public static Parsed parse(AnnotationExpr view) {
        List<String> diagnostics = new ArrayList<>();
        if (view == null) {
            return new Parsed(new ViewAttributes(GenLevel.DEFAULT, "", List.of()), diagnostics);
        }

        // Marker form and empty-pairs form both mean "all defaults".
        if (view.isMarkerAnnotationExpr()) {
            return new Parsed(new ViewAttributes(GenLevel.DEFAULT, "", List.of()), diagnostics);
        }

        if (view.isSingleMemberAnnotationExpr()) {
            // @View(true) / @View(false): the retired form. The real annotation declares no value()
            // member, so this cannot come from compiling source; it survives only in old parser
            // comments. Resolve to DEFAULT with a diagnostic rather than guessing a level.
            diagnostics.add("unsupported_view_annotation_form: @View("
                    + view.asSingleMemberAnnotationExpr().getMemberValue()
                    + ") has no matching annotation member; treating gen as DEFAULT");
            return new Parsed(new ViewAttributes(GenLevel.DEFAULT, "", List.of()), diagnostics);
        }

        if (!view.isNormalAnnotationExpr()) {
            diagnostics.add("unsupported_view_annotation_form: unrecognized @View form " + view);
            return new Parsed(new ViewAttributes(GenLevel.DEFAULT, "", List.of()), diagnostics);
        }

        GenLevel gen = GenLevel.DEFAULT;
        String discriminatorField = "";
        List<String> addons = new ArrayList<>();

        for (MemberValuePair pair : view.asNormalAnnotationExpr().getPairs()) {
            switch (pair.getName().asString()) {
                case "gen" -> gen = parseGenLevel(pair.getValue(), diagnostics);
                case "discriminatorField" -> discriminatorField = parseStringLiteral(pair.getValue());
                case "addons" -> addons.addAll(parseAddonNames(pair.getValue(), diagnostics));
                default -> diagnostics.add("unknown_view_attribute: @" + pair.getName().asString()
                        + " is not a member of @View and is ignored");
            }
        }

        return new Parsed(new ViewAttributes(gen, discriminatorField, addons), diagnostics);
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

    /** The last identifier of a possibly qualified name expression. */
    private static String lastSegment(Expression value) {
        if (value instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (value instanceof FieldAccessExpr access) {
            return access.getNameAsString();
        }
        String text = value.toString();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    private static String parseStringLiteral(Expression value) {
        if (value instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        // A non-literal discriminator cannot be resolved statically; the generator reports it
        // through the divergence path rather than crashing here.
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
        if (value instanceof ArrayInitializerExpr array) {
            entries.addAll(array.getValues());
        } else {
            entries.add(value);
        }
        for (Expression entry : entries) {
            String simple = simpleClassName(entry);
            if (simple == null || simple.isEmpty()) {
                diagnostics.add("unresolved_addon: cannot read an addon class from '" + entry + "'");
                continue;
            }
            names.add(simple);
        }
        return names;
    }

    /** {@code com.example.Foo.class} &rarr; {@code Foo}. */
    private static String simpleClassName(Expression entry) {
        String text;
        if (entry instanceof ClassExpr classExpr) {
            text = classExpr.getType().toString();
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
    public static ViewAttributes read(AnnotationExpr view) {
        return parse(view).attributes();
    }

    /** Convenience for callers holding the optional annotation lookup. */
    public static ViewAttributes read(Optional<AnnotationExpr> view) {
        return view.map(ViewAnnotationReader::read).orElse(null);
    }
}
