// {@link com.codebuddy.merge.TypeChangeConflictResolver} Resolves type change conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

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
 * hand-written table of JDK supertype chains, which was both incomplete - anything
 * absent, including every type in the project under merge, was escalated - and wrong
 * in the boxed-type entries, where it read the primitive lattice across the wrapper
 * classes and claimed {@code Integer} widens to {@code Long}, which javac rejects
 * because they are siblings under {@code Number}.
 *
 * <p>Anything neither rule decides is treated as unrelated and escalated rather than
 * guessed at, and so is anything that cannot be resolved at all - a missing
 * declaration, an unattributable type or no type context: no answer is never a "no".
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

    @Override
    public ConflictType supportedType() {
        return ConflictType.TYPE_CHANGE;
    }

    /**
     * This resolver decides by resolved types, so it needs a context.
     *
     * <p>Declaring it is what makes a context-less run fail at construction with a
     * message naming this resolver, rather than resolving conflicts with a weaker
     * rule and no indication that it did (see
     * {@code MergeConflictResolver.Builder.requireTypeContextIfNeeded}). The
     * primitive lattice would work without a classpath, and deliberately does not
     * get a context-free path: one resolver that answers differently depending on
     * how it was built is worse than one that insists on being built properly.
     */
    @Override
    public boolean requiresTypeContext() {
        return true;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        TypeAndName branch1 = parse(conflict.getBranch1Code());
        TypeAndName branch2 = parse(conflict.getBranch2Code());

        if (branch1 == null || branch2 == null || branch1.type().equals(branch2.type())) {
            return null;
        }

        TypeContext context = conflict.getTypeContext();
        if (context == null) {
            // requiresTypeContext() is true, so the orchestrator refuses to build a
            // set without a context; reaching here means this resolver was used
            // directly.
            return declined(conflict, "no type context was supplied, so the declared "
                + "types cannot be resolved");
        }

        boolean branch1Widens = widensResolved(context, conflict.getFilePath(),
            conflict.getBranch1Code(), branch1, conflict.getBranch2Code(), branch2);
        boolean branch2Widens = widensResolved(context, conflict.getFilePath(),
            conflict.getBranch2Code(), branch2, conflict.getBranch1Code(), branch1);

        if (branch1Widens == branch2Widens) {
            // Neither widens the other (unrelated types), or both do, which
            // cannot happen for a partial order. Either way, a human decides.
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("Declared types differ ('" + branch1.type() + "' vs '"
                    + branch2.type() + "') without a widening relationship, so the safe "
                    + "choice depends on the call sites.")
                .alternativePaths(describeOptions(conflict))
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
        if (wider == null || narrower == null) {
            return false;
        }
        String w = canonical(wider);
        String n = canonical(narrower);
        if (w.equals(n)) {
            return false;
        }
        for (List<String> chain : PRIMITIVE_CHAINS) {
            int widerIndex = chain.indexOf(w);
            int narrowerIndex = chain.indexOf(n);
            if (widerIndex >= 0 && narrowerIndex >= 0 && widerIndex > narrowerIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code wider} is a supertype of {@code narrower} according to javac.
     *
     * <p>Resolution is attempted only for a pair of reference types that are not the
     * same canonical text, and only when both declarations actually name their type in
     * the fragments given. Every other combination falls back to the lattice, which
     * answers false - and false means "escalate", the same outcome this resolver gave
     * for an unknown pair before, so a type that cannot be attributed loses no safety.
     */
    private static boolean widensResolved(TypeContext context, String filePath,
        String widerCode, TypeAndName wider, String narrowerCode, TypeAndName narrower) {

        String widerToken = canonical(wider.type());
        String narrowerToken = canonical(narrower.type());
        if (isPrimitiveToken(widerToken) || isPrimitiveToken(narrowerToken)) {
            return widensPrimitive(widerToken, narrowerToken);
        }

        Optional<JavaType> widerType =
            ResolvedTypeReader.declaredType(widerCode, wider.name(), filePath, context);
        Optional<JavaType> narrowerType =
            ResolvedTypeReader.declaredType(narrowerCode, narrower.name(), filePath, context);
        if (widerType.isEmpty() || narrowerType.isEmpty()) {
            // Unresolvable: the table could not have answered either, and a guess here
            // would adopt a declaration on the strength of a name match.
            return false;
        }
        return widens(widerType.get(), narrowerType.get());
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
     * A manual resolution carrying the reason the comparison could not be made.
     *
     * <p>The same shape {@code OverloadAddConflictResolver} uses, because a resolver
     * that cannot decide must say so in the same terms as the resolver beside it: the
     * reviewer sees one vocabulary whether the missing piece was a parameter type or a
     * declaration type.
     */
    private ConflictResolution declined(Conflict conflict, String reason) {
        return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MANUAL)
            .kind(ConflictResolution.ResolutionKind.MANUAL)
            .resolvedCode(ConflictResolution.MANUAL_MARKER)
            .explanation("Type-change resolution could not decide this conflict: " + reason)
            .alternativePaths(List.of(
                newFixPath(conflict)
                    .description("Resolve the type change by hand")
                    .options("Keep branch 1", "Keep branch 2", "Keep the base declaration")
                    .justification(reason)
                    .impact("Requires reviewer time; nothing is applied automatically.")
                    .build(),
                newFixPath(conflict)
                    .description("Provide a type context so the types can be resolved")
                    .options("Build the resolver with a classpath covering these types")
                    .recommended("Build the resolver with a classpath covering these types")
                    .justification("A type context is what lets the wider declaration be "
                        + "identified from the compiled types rather than from their spelling.")
                    .impact("Automatic resolution becomes possible again.")
                    .build()))
            .build();
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
