// {@link com.codebuddy.merge.TypeChangeConflictResolver} Resolves type change conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves conflicts where the declared type of the same declaration differs
 * between branches, e.g. {@code int count} on one side and {@code long count} on
 * the other.
 *
 * Widening a type is source-compatible for readers; narrowing it is not. The
 * resolver classifies the pair and only then decides:
 *
 * <ul>
 *   <li><b>Widening on one side only</b> - the wider type accepts every value
 *       the narrower one did, so the wider type is adopted automatically.</li>
 *   <li><b>Unrelated or narrowing types</b> - reported for review, because the
 *       right answer depends on what the callers now pass.</li>
 * </ul>
 *
 * <p>Widening is decided in one of two ways, and which one applies is a property of
 * the types rather than of the caller. For <b>primitives</b> it is the language's own
 * conversion rule (JLS 5.1.2), which no class hierarchy can express. For every
 * <b>reference</b> type it is resolved through javac: the wider declaration is the one
 * a value of the narrower declaration's type is assignable to. That replaced a
 * hand-written table of JDK supertype chains as the <em>primary</em> rule, because that
 * table was both incomplete - anything absent, including every type in the project
 * under merge, was escalated - and wrong in the boxed-type entries, where it read the
 * primitive lattice across the wrapper classes and claimed {@code Integer} widens to
 * {@code Long}, which javac rejects because they are siblings under {@code Number}.
 *
 * <p><b>It degrades rather than demanding a classpath</b> (see
 * {@link #requiresTypeContext()}). Without a type context the primitive rule still
 * applies, because it is the language's, and {@link #JDK_SUPERTYPES} answers the common
 * JDK hierarchies as a best effort; every resolution produced that way carries
 * {@link #DEGRADED_WARNING}. With a context, a type that cannot be resolved — a project
 * type missing from the caller's classpath — escalates with
 * {@link #UNRESOLVED_WARNING} rather than falling back to a name match.
 *
 * <p>Anything no rule decides is treated as unrelated and escalated rather than guessed
 * at: no answer is never a "no".
 */
public final class TypeChangeConflictResolver extends AbstractConflictResolver {

    /**
     * Matches a declaration and captures its declared type and name.
     *
     * <p>The type group contains no whitespace and is not lazy, so the lazy
     * quantifier cannot backtrack and let the name group swallow part of the type
     * (which would parse {@code long count} as a type of {@code lon}).
     * Multi-word generic types are therefore matched up to their first type
     * argument, which is enough to classify widening; the full original
     * declaration is what gets adopted, never this token.
     */
    private static final Pattern TYPED_DECLARATION = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*"
            + "(?<type>[A-Za-z_$][\\w$.]*(?:\\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>)?(?:\\s*\\.\\.\\.|\\s*\\[\\s*\\])*)"
            + "\\s+"
            + "(?<name>[A-Za-z_$][\\w$]*)\\s*(?:=|;|\\))");

    /**
     * The JLS widening primitive conversions (JLS 5.1.2), narrowest first: a
     * primitive widens to any later member of the chain it appears in.
     *
     * <p>This is the one widening rule that is <b>not</b> a question about a class
     * hierarchy, and it is why a table survives here at all. Primitives are not
     * types with supertypes — {@code int} is not a subtype of {@code long} — the
     * conversions between them are fixed by the language, identical in every
     * compiler, and can never change. Resolving them would mean asking a type
     * system a question it does not answer. Everything else in this resolver used
     * to be a table too, and that table is gone: reference types are now decided by
     * {@link #widensResolved}, which asks javac.
     *
     * <p>Note that {@code long} widens to {@code float} and {@code float} to
     * {@code double}: JLS 5.1.2 permits conversions that lose precision, and the
     * rule the resolver implements is "can a narrower value be accepted by the wider
     * variable", which those satisfy.
     */
    private static final List<List<String>> PRIMITIVE_CHAINS = List.of(
        List.of("byte", "short", "int", "long", "float", "double"),
        List.of("char", "int", "long", "float", "double")
    );

    /**
     * Every primitive keyword, including {@code void} and {@code boolean}, which
     * widen to nothing.
     *
     * <p>A primitive on either side of the comparison ends the resolution path:
     * {@code int} to {@code Object} is boxing, not widening, and reporting it as
     * widening would auto-adopt a declaration that changes what readers see.
     */
    private static final Set<String> PRIMITIVES = Set.of(
        "byte", "short", "int", "long", "float", "double", "char", "boolean", "void");

    /**
     * The built-in best-effort table of common JDK hierarchies, used <strong>only when
     * there is no type context</strong>.
     *
     * <p>Not a revival of the removed {@code WIDENING_CHAINS}: that table was the
     * primary rule, this is the fallback, and the difference shows in two ways.
     *
     * <ul>
     *   <li><strong>Only true supertype relations appear.</strong> Every chain lists a
     *       type and then types it really is assignable to — {@code ArrayList →
     *       AbstractList → List → Collection → Iterable → Object}, {@code HashMap →
     *       AbstractMap → Map → Object}, {@code Integer → Number → Object} and
     *       {@code Integer → Comparable → Object} as two chains because {@code Number}
     *       is <em>not</em> a {@code Comparable}. The removed table ran the primitive
     *       lattice across the wrapper classes in one ascending list, which claimed
     *       {@code Integer} widens to {@code Long} and {@code Number} to
     *       {@code Comparable} — both false, and both would have auto-adopted a
     *       declaration javac rejects.</li>
     *   <li><strong>It is consulted only in the degraded mode</strong>, and every
     *       resolution it decides carries {@link #DEGRADED_WARNING}. With a context, a
     *       type that cannot be resolved escalates with {@link #UNRESOLVED_WARNING}
     *       instead — matching simple names against this table while a classpath was
     *       available would be exactly the name-based guess that resolution replaced.</li>
     * </ul>
     *
     * <p>Being partial is the point: a pair the table does not carry escalates to a
     * reviewer, which is the same outcome an unlisted pair had before, so the fallback
     * can only add decisions, never remove safety.
     */
    static final List<List<String>> JDK_SUPERTYPES = List.of(
        // Wrapper types into Number, and separately into Comparable: Number is not a
        // Comparable, so the two relations must not share a chain.
        List.of("Byte", "Number", "Object"),
        List.of("Byte", "Comparable", "Object"),
        List.of("Short", "Number", "Object"),
        List.of("Short", "Comparable", "Object"),
        List.of("Integer", "Number", "Object"),
        List.of("Integer", "Comparable", "Object"),
        List.of("Long", "Number", "Object"),
        List.of("Long", "Comparable", "Object"),
        List.of("Float", "Number", "Object"),
        List.of("Float", "Comparable", "Object"),
        List.of("Double", "Number", "Object"),
        List.of("Double", "Comparable", "Object"),
        List.of("Character", "Comparable", "Object"),
        List.of("Boolean", "Comparable", "Object"),

        // Text.
        List.of("String", "CharSequence", "Comparable", "Object"),
        List.of("StringBuilder", "CharSequence", "Comparable", "Object"),
        List.of("StringBuffer", "CharSequence", "Comparable", "Object"),

        // Collections. TreeSet gets both its interface chain and its class chain, which
        // is why NavigableSet and SortedSet are recognisable without a classpath.
        List.of("ArrayList", "AbstractList", "List", "Collection", "Iterable", "Object"),
        List.of("LinkedList", "AbstractSequentialList", "List", "Collection", "Iterable", "Object"),
        List.of("AbstractCollection", "Collection", "Iterable", "Object"),
        List.of("HashSet", "AbstractSet", "Set", "Collection", "Iterable", "Object"),
        List.of("TreeSet", "AbstractSet", "Set", "Collection", "Iterable", "Object"),
        List.of("TreeSet", "NavigableSet", "SortedSet", "Set", "Collection", "Iterable", "Object"),
        List.of("LinkedHashSet", "HashSet", "AbstractSet", "Set", "Collection", "Iterable", "Object"),
        List.of("TreeMap", "AbstractMap", "Map", "Object"),
        List.of("HashMap", "AbstractMap", "Map", "Object"),
        List.of("LinkedHashMap", "HashMap", "AbstractMap", "Map", "Object"),
        List.of("Iterator", "Object"),
        List.of("Optional", "Object")
    );

    /**
     * The warning every resolution carries when there was no type context.
     *
     * <p>States what was available and what was therefore not checked, because the
     * explanation beside it reads exactly as confidently as a resolved one.
     */
    static final String DEGRADED_WARNING =
        "resolved without a type context: only the JLS primitive conversions and a "
            + "built-in table of common JDK hierarchies were available, so the declared "
            + "types were not checked against compiled types";

    /**
     * The warning for a declaration that could not be resolved even though a context was
     * supplied — typically a project type absent from the caller's classpath.
     */
    static final String UNRESOLVED_WARNING =
        "a declared type could not be resolved against the supplied classpath, so this "
            + "pair was escalated rather than decided; pass a classpath that covers the "
            + "project's own types to have it decided";

    @Override
    public ConflictType supportedType() {
        return ConflictType.TYPE_CHANGE;
    }

    /**
     * {@link AnalysisLevel#PROJECT_TYPES}: with a classpath carrying the project's own entries,
     * javac decides whether one declaration's type is assignable to the other, which is the
     * strongest evidence this module can bring to bear.
     */
    @Override
    public AnalysisLevel maxAnalysisLevel() {
        return AnalysisLevel.PROJECT_TYPES;
    }

    /**
     * The level this run actually reached, which is weaker than the maximum whenever the answer came
     * from the platform alone.
     *
     * <p>The JLS primitive conversions are the language's own rule and the built-in JDK table holds
     * only true supertype relations, so both are authoritative <em>about the platform</em> — and both
     * are blind to the project under merge. A pair resolved by javac is stronger exactly when the
     * classpath carried the project's own entries, because only then is a project's type known rather
     * than {@code JavaType.Unknown}.
     */
    private static AnalysisLevel decidedLevel(Conflict conflict) {
        TypeContext context = conflict.getTypeContext();
        return context != null && context.hasProjectEntries()
            ? AnalysisLevel.PROJECT_TYPES
            : AnalysisLevel.PLATFORM_TYPES;
    }

    /**
     * A classpath is <strong>not</strong> a hard requirement for this resolver: it
     * degrades, visibly.
     *
     * <p>Two of its three rules survive without one. The primitive conversions are the
     * language's own (JLS 5.1.2) and no classpath can change them, and
     * {@link #JDK_SUPERTYPES} carries the common JDK hierarchies — {@code ArrayList →
     * List → Collection → Iterable}, {@code HashMap → Map}, the wrapper types into
     * {@code Number}/{@code Comparable}/{@code Object} — as the best effort available
     * without compiled types. So a caller who cannot supply a classpath still gets
     * primitive widening and the common collection cases decided, rather than an
     * exception or a blanket refusal.
     *
     * <p>What it must not do is pretend the answer is as good as the resolved one, so
     * every resolution it produces without a context carries
     * {@link #DEGRADED_WARNING}. A resolver that <em>cannot</em> degrade declares
     * {@code true} instead and is removed from the set by hand — see
     * {@link ConflictResolver#requiresTypeContext()} for both halves of the rule.
     */
    @Override
    public boolean requiresTypeContext() {
        return false;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        TypeAndName branch1 = parse(conflict.getBranch1Code());
        TypeAndName branch2 = parse(conflict.getBranch2Code());

        if (branch1 == null || branch2 == null || branch1.type().equals(branch2.type())) {
            return null;
        }

        TypeContext context = conflict.getTypeContext();
        List<String> warnings = new ArrayList<>();
        if (context == null) {
            warnings.add(DEGRADED_WARNING);
        }

        boolean branch1Widens = widens(conflict, context, conflict.getBranch1Code(), branch1,
            conflict.getBranch2Code(), branch2, warnings);
        boolean branch2Widens = widens(conflict, context, conflict.getBranch2Code(), branch2,
            conflict.getBranch1Code(), branch1, warnings);

        if (branch1Widens == branch2Widens) {
            // Neither widens the other (unrelated types), or both do, which
            // cannot happen for a partial order. Either way, a human decides.
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("Declared types differ ('" + branch1.type() + "' vs '"
                    + branch2.type() + "') without a widening relationship, so the safe "
                    + "choice depends on the call sites.")
                .alternativePaths(describeOptions(conflict))
                .analysisLevel(decidedLevel(conflict))
                .warnings(warnings)
                .build();
        }

        // branch1Widens true means branch 1's type is the wider one, so branch 1
        // supplies the declaration to adopt.
        String widerCode = branch1Widens ? conflict.getBranch1Code() : conflict.getBranch2Code();
        String widerType = branch1Widens ? branch1.type() : branch2.type();
        String narrowerType = branch1Widens ? branch2.type() : branch1.type();

        return autoResolution(conflict, branch1Widens
                ? ConflictResolution.ResolutionStrategy.PREFER_BRANCH1
                : ConflictResolution.ResolutionStrategy.PREFER_BRANCH2)
            .resolvedCode(widerCode)
            .explanation("'" + widerType + "' is a widening of '" + narrowerType
                + "', so the wider declaration accepts every value the narrower one did.")
            .alternativePaths(describeOptions(conflict))
            .analysisLevel(decidedLevel(conflict))
            .warnings(warnings)
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        TypeAndName branch1 = parse(conflict.getBranch1Code());
        TypeAndName branch2 = parse(conflict.getBranch2Code());
        TypeAndName base = parse(conflict.getBaseCode());
        String type1 = branch1 == null ? "<branch 1>" : branch1.type();
        String type2 = branch2 == null ? "<branch 2>" : branch2.type();
        String baseType = base == null ? "<base>" : base.type();

        return List.of(
            newFixPath(conflict)
                .description("Pick one declared type")
                .options("Use '" + type1 + "'", "Use '" + type2 + "'", "Keep '" + baseType + "'")
                .justification("Only one declaration survives; widening is usually safe, "
                    + "narrowing usually is not.")
                .impact("Narrowing may break call sites that already pass the wider type.")
                .build(),
            newFixPath(conflict)
                .description("Accept the wider type automatically")
                .options("Adopt the wider type", "Adopt the narrower type")
                .justification("Adopting the wider type keeps every existing assignment valid.")
                .impact("None for readers; writers gain the wider range.")
                .build()
        );
    }

    /**
     * True when a value of type {@code narrower} can be assigned to a variable of
     * type {@code wider} - that is, when adopting {@code wider} cannot invalidate an
     * existing reader.
     *
     * <h2>Which rule decides</h2>
     *
     * <p>A pair involving a primitive is decided by the JLS conversion lattice, and a
     * pair of reference types by resolution. That order matters: {@code int} is not a
     * subtype of {@code long}, so asking a type system would either return nothing or,
     * worse, return an answer about boxing. Canonical text equality is settled before
     * either rule, so {@code String[]} and {@code String...} - the same resolved type
     * spelled two ways - cannot report a widening.
     *
     * <p>Resolution answering "no" and resolution failing are different outcomes, and
     * this method collapses both to {@code false} on purpose: the caller's response to
     * either is to escalate to a human. What must never happen is resolution failing
     * and being read as "not assignable", so the failure paths are handled inside
     * {@link #widensResolved} rather than here.
     */
    static boolean widens(String wider, String narrower) {
        return widensPrimitive(canonical(wider), canonical(narrower));
    }

    /**
     * The JLS 5.1.2 widening primitive conversions, which are the whole of what this
     * table is for.
     *
     * <p>Kept as a named method rather than inlined so a reader can see that the only
     * surviving table is the language's, and so the tests can pin it directly. A
     * non-primitive argument appears in no chain and answers false, which is also how
     * a mixed primitive/reference pair answers.
     */
    static boolean widensPrimitive(String wider, String narrower) {
        return widensInTable(PRIMITIVE_CHAINS, canonical(wider), canonical(narrower));
    }

    /**
     * The built-in table's answer, for the degraded mode only.
     *
     * <p>Exposed so a caller can ask what the fallback knows without building a
     * conflict, and so the tests can pin the table's content rather than only its
     * effect.
     */
    static boolean widensFromBuiltInTable(String wider, String narrower) {
        return widensInTable(JDK_SUPERTYPES, canonical(wider), canonical(narrower));
    }

    /**
     * A chain scan: {@code wider} is a supertype of {@code narrower} when one chain
     * lists both and puts {@code wider} later.
     *
     * <p>Every chain must therefore contain only true relations, since one chain that
     * places two unrelated types in an order is enough to make the answer wrong. That
     * is the invariant the fallback table above is written to, and the one the removed
     * table broke.
     */
    private static boolean widensInTable(List<List<String>> table, String wider, String narrower) {
        if (wider == null || narrower == null || wider.equals(narrower)) {
            return false;
        }
        for (List<String> chain : table) {
            int widerIndex = chain.indexOf(wider);
            int narrowerIndex = chain.indexOf(narrower);
            if (widerIndex >= 0 && narrowerIndex >= 0 && widerIndex > narrowerIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code wider} is a supertype of {@code narrower}, by the best evidence
     * available — and the place where "the answer is weaker than usual" is recorded.
     *
     * <p>Three rules, in order, and each is the strongest one that can apply:
     * <ol>
     *   <li><strong>a primitive on either side</strong> is the JLS conversion lattice.
     *       No classpath changes it, so it is the same answer in both modes and needs no
     *       caveat of its own;</li>
     *   <li><strong>a context</strong> resolves both declarations and asks javac. A type
     *       that cannot be resolved — a project type absent from the caller's classpath
     *       arrives as {@code JavaType.Unknown} rather than as an absent one — escalates
     *       with {@link #UNRESOLVED_WARNING}, deliberately <em>not</em> falling through
     *       to the built-in table: matching simple names would be the name-based guess
     *       resolution exists to replace;</li>
     *   <li><strong>no context</strong> is the degraded mode: the built-in table, with
     *       {@link #DEGRADED_WARNING} already attached by the caller.</li>
     * </ol>
     *
     * <p>Every path answers {@code false} rather than throwing when it cannot decide, so
     * the caller escalates — the one outcome that is never wrong, only less helpful.
     */
    private static boolean widens(Conflict conflict, TypeContext context, String widerCode,
        TypeAndName wider, String narrowerCode, TypeAndName narrower, List<String> warnings) {

        String widerToken = canonical(wider.type());
        String narrowerToken = canonical(narrower.type());
        if (isPrimitiveToken(widerToken) || isPrimitiveToken(narrowerToken)) {
            return widensPrimitive(widerToken, narrowerToken);
        }

        if (context == null) {
            return widensFromBuiltInTable(widerToken, narrowerToken);
        }

        Optional<JavaType> widerType =
            ResolvedTypeReader.declaredType(widerCode, wider.name(), conflict.getFilePath(), context);
        Optional<JavaType> narrowerType =
            ResolvedTypeReader.declaredType(narrowerCode, narrower.name(), conflict.getFilePath(), context);
        if (!isAttributed(widerType) || !isAttributed(narrowerType)) {
            warnings.add(UNRESOLVED_WARNING);
            return false;
        }
        return widens(widerType.get(), narrowerType.get());
    }

    /**
     * Whether a declaration's type was actually attributed.
     *
     * <p>Present is not enough. A type that is off the classpath comes back as
     * {@link JavaType.Unknown} — present, and assignable to nothing — so a check on the
     * {@code Optional} alone would read "no type information" as "compared, and not a
     * widening", which is the conflation this method exists to prevent.
     */
    private static boolean isAttributed(Optional<JavaType> type) {
        return type.isPresent() && !(type.get() instanceof JavaType.Unknown);
    }

    /**
     * The resolution rule on its own: whether a value of {@code narrower}'s type is
     * assignable to {@code wider}.
     *
     * <p>Exposed because it is the whole of the reference-type answer, and a caller
     * that already holds resolved types - a report, a test, a future resolver - should
     * not have to rebuild a fragment to ask the question.
     */
    static boolean widens(JavaType wider, JavaType narrower) {
        if (wider == null || narrower == null) {
            return false;
        }
        // `isAssignableTo(target, source)`: the narrower declaration's type is
        // assignable to the wider one, which is exactly the widening question.
        return TypeUtils.isAssignableTo(wider, narrower);
    }

    /** Whether a canonical type token is a primitive keyword. */
    private static boolean isPrimitiveToken(String token) {
        return PRIMITIVES.contains(token);
    }

    /**
     * Reduce a type to the token used for widening comparison:
     * {@code String...} and {@code String[]} both become {@code String[]}, and
     * generic arguments are dropped because they do not affect assignability in
     * the direction that matters here.
     */
    static String canonical(String type) {
        if (type == null) {
            return "";
        }
        String trimmed = type.trim();
        boolean varargs = trimmed.endsWith("...");
        if (varargs) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        boolean array = trimmed.endsWith("[]");
        if (array) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        int generic = trimmed.indexOf('<');
        if (generic > 0) {
            trimmed = trimmed.substring(0, generic);
        }
        trimmed = trimmed.trim();
        return (varargs || array) ? trimmed + "[]" : trimmed;
    }

    /**
     * The declared type and name of the first declaration in a hunk, if any.
     */
    static TypeAndName parse(String code) {
        if (code == null) {
            return null;
        }
        for (String line : code.split("\n")) {
            Matcher matcher = TYPED_DECLARATION.matcher(line);
            if (matcher.find()) {
                String type = matcher.group("type").trim();
                String name = matcher.group("name").trim();
                if (!isControlKeyword(type)) {
                    return new TypeAndName(type, name);
                }
            }
        }
        return null;
    }

    private static boolean isControlKeyword(String type) {
        return switch (type) {
            case "if", "for", "while", "switch", "return", "new", "catch", "try", "else" -> true;
            default -> false;
        };
    }

    /**
     * A declared type paired with the name it was declared for.
     */
    record TypeAndName(String type, String name) {
    }
}
