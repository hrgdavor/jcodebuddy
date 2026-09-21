// {@link com.codebuddy.merge.TypeChangeConflictResolver} Resolves type change conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.List;
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
 * <p>Widening is known for the primitive and boxed numeric chains, for the
 * common JDK collection and functional supertypes, and for arrays of a widening
 * element type. Anything else is treated as unrelated and escalated rather than
 * guessed at.
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
     * Widening chains, narrowest first. A type widens to any later member of a
     * chain it appears in, so each chain must spell out every supertype of every
     * member it lists.
     *
     * <p>The reference-type chains follow the JDK's own supertype relationships:
     * {@code String} is a {@code CharSequence} is an {@code Object}; an
     * {@code ArrayList} is a {@code List} is a {@code Collection} is an
     * {@code Iterable}.
     */
    private static final List<List<String>> WIDENING_CHAINS = List.of(
        // Primitives.
        List.of("byte", "short", "int", "long", "float", "double"),
        List.of("char", "int", "long", "float", "double"),
        List.of("boolean"),

        // Boxed types, then into Number and Object.
        List.of("Byte", "Short", "Integer", "Long", "Float", "Double", "Number", "Comparable", "Object"),
        List.of("Character", "Comparable", "Object"),
        List.of("Boolean", "Comparable", "Object"),

        // Common JDK supertypes.
        List.of("String", "CharSequence", "Comparable", "Object"),
        List.of("StringBuilder", "CharSequence", "Comparable", "Object"),
        List.of("ArrayList", "AbstractList", "List", "Collection", "Iterable", "Object"),
        List.of("LinkedList", "AbstractSequentialList", "List", "Collection", "Iterable", "Object"),
        List.of("AbstractCollection", "Collection", "Iterable", "Object"),
        List.of("HashSet", "AbstractSet", "Set", "Collection", "Iterable", "Object"),
        List.of("TreeSet", "AbstractSet", "Set", "Collection", "Iterable", "Object"),
        List.of("LinkedHashSet", "HashSet", "AbstractSet", "Set", "Collection", "Iterable", "Object"),
        List.of("TreeMap", "AbstractMap", "Map", "Object"),
        List.of("HashMap", "AbstractMap", "Map", "Object"),
        List.of("LinkedHashMap", "HashMap", "AbstractMap", "Map", "Object"),
        List.of("Iterator", "Object"),
        List.of("Optional", "Object")
    );

    @Override
    public ConflictType supportedType() {
        return ConflictType.TYPE_CHANGE;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        TypeAndName branch1 = parse(conflict.getBranch1Code());
        TypeAndName branch2 = parse(conflict.getBranch2Code());

        if (branch1 == null || branch2 == null || branch1.type().equals(branch2.type())) {
            return null;
        }

        boolean branch1Widens = widens(branch1.type(), branch2.type());
        boolean branch2Widens = widens(branch2.type(), branch1.type());

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
     * type {@code wider} under Java's widening rules - that is, when adopting
     * {@code wider} cannot invalidate an existing reader.
     *
     * <p>For example {@code widens("long", "int")} is true while
     * {@code widens("int", "long")} is false.
     */
    static boolean widens(String wider, String narrower) {
        if (wider == null || narrower == null) {
            return false;
        }
        String w = canonical(wider);
        String n = canonical(narrower);
        if (w.equals(n)) {
            return false;
        }
        for (List<String> chain : WIDENING_CHAINS) {
            int widerIndex = chain.indexOf(w);
            int narrowerIndex = chain.indexOf(n);
            // A chain only lists real supertype relationships, so any chain that
            // establishes the order is authoritative. Scanning all of them (rather
            // than the first that mentions both) is required because a type can
            // appear in several chains with different neighbours - Set appears
            // alongside both Collection and Object.
            if (widerIndex >= 0 && narrowerIndex >= 0 && widerIndex > narrowerIndex) {
                return true;
            }
        }
        return false;
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
