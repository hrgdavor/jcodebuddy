// {@link com.codebuddy.merge.ConflictResolvers} Registry of the built-in conflict resolvers.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single place where conflict resolvers are registered.
 *
 * <h2>Adding a new resolver</h2>
 * <ol>
 *   <li>Add the {@link ConflictType} constant, declaring its
 *       {@link ConflictType.Handling}.</li>
 *   <li>Write the resolver as a subclass of {@link AbstractConflictResolver}.</li>
 *   <li>Add one line to {@link #defaultResolvers()}.</li>
 * </ol>
 *
 * Everything else - orchestration, history replay, the "every type is covered"
 * test - picks the resolver up automatically, because they all read this
 * registry rather than holding their own list.
 */
public final class ConflictResolvers {

    private ConflictResolvers() {
    }

    /**
     * The resolvers shipped with the module, in priority order.
     *
     * <p>Register a new resolver here and nowhere else.
     */
    public static List<ConflictResolver> defaultResolvers() {
        List<ConflictResolver> resolvers = new ArrayList<>(List.of(
            new ImportConflictResolver(),
            new CommentAddConflictResolver(),
            new ConstantAddConflictResolver(),
            new OverloadAddConflictResolver(),
            new MethodBodyChangeConflictResolver(),
            new TypeChangeConflictResolver(),
            new RenameConflictResolver(),
            new PackageChangeConflictResolver(),
            new StructuralChangeConflictResolver(),
            new ApiIncompatibilityConflictResolver(),
            new MemberAddConflictResolver()
        ));
        return sortByPriority(resolvers);
    }

    /**
     * Order resolvers so that the highest {@link ConflictResolver#priority()}
     * runs first, keeping registration order within equal priorities.
     */
    public static List<ConflictResolver> sortByPriority(List<ConflictResolver> resolvers) {
        List<ConflictResolver> sorted = new ArrayList<>(resolvers);
        sorted.sort(Comparator.comparingInt(ConflictResolver::priority).reversed());
        return List.copyOf(sorted);
    }

    /**
     * Order resolvers by the {@link AnalysisLevel} each one <b>declares</b> it can reach, strongest
     * first, keeping the given order within a tier.
     *
     * <h2>Why the declaration orders the pass, while the record decides what a claim may do</h2>
     *
     * <p>Hierarchical resolution (plan § 4C, DEC-046) runs the strongest tier first so that a lower
     * tier can be skipped entirely once a region is settled — and a tier can only be skipped
     * <b>before</b> it runs if it is known before it runs. A level recorded on a resolution does not
     * exist until the resolver has been called, so the <em>run order</em> is necessarily built from the
     * strongest level each resolver <b>declares</b> ({@link ConflictResolver#maxAnalysisLevel()}).
     *
     * <p>The declaration is not a licence, and the two facts stay apart in the direction that matters:
     *
     * <ul>
     *   <li>a resolution may never record a level <em>above</em> its resolver's declaration, which
     *       {@code AnalysisLevelTest} already asserts — so the declaration is an upper bound, never an
     *       overclaim;</li>
     *   <li>what a claim may <b>settle</b> is decided from the level it actually recorded plus the
     *       reliability check, never from the declaration — a resolver that reaches
     *       {@link AnalysisLevel#STRUCTURE} without a classpath is asked at its declared tier and
     *       settles only what its recorded evidence explains.</li>
     * </ul>
     *
     * <p>So: the declaration decides who is asked, in what order; the record decides what the answer is
     * worth. A resolver that does not declare a level has declared {@link AnalysisLevel#TEXT_LOCAL}, the
     * weakest, and is asked last.
     */
    public static List<ConflictResolver> inTierOrder(List<ConflictResolver> resolvers) {
        List<ConflictResolver> sorted = new ArrayList<>(resolvers);
        sorted.sort(Comparator
            .comparingInt((ConflictResolver resolver) -> resolver.maxAnalysisLevel().strength())
            .reversed());
        return List.copyOf(sorted);
    }

    /**
     * Index resolvers by the conflict type they claim.
     *
     * @throws IllegalStateException when two resolvers claim the same type, so a
     *         copy-paste mistake fails loudly at construction instead of
     *         silently shadowing a resolver.
     */
    public static Map<ConflictType, ConflictResolver> index(List<ConflictResolver> resolvers) {
        Map<ConflictType, ConflictResolver> byType = new EnumMap<>(ConflictType.class);
        for (ConflictResolver resolver : resolvers) {
            ConflictResolver previous = byType.putIfAbsent(resolver.supportedType(), resolver);
            if (previous != null) {
                throw new IllegalStateException("Conflict type " + resolver.supportedType()
                    + " is claimed by both " + previous.getClass().getName()
                    + " and " + resolver.getClass().getName()
                    + "; each type must have exactly one resolver.");
            }
        }
        return byType;
    }

    /**
     * The resolver that owns a conflict type, if any.
     */
    public static Optional<ConflictResolver> find(List<ConflictResolver> resolvers, ConflictType type) {
        Objects.requireNonNull(type, "type");
        return resolvers.stream()
            .filter(resolver -> resolver.supports(type))
            .findFirst();
    }

    /**
     * The conflict types a resolver can own: every type except {@link ConflictType#UNCLASSIFIED_TEXT}.
     *
     * <p>That one is the <em>absence of a claim</em> — the case where the sides differ and no detector recognised the
     * block — so a registry that demanded a resolver for it would demand that something claim what nothing can. It is
     * the one type excluded, and deliberately not "every type a human must confirm": {@code STRUCTURAL_CHANGE} and
     * {@code API_INCOMPATIBILITY} are also handed to a human and both <em>have</em> resolvers, because a resolver can
     * produce an answer a person confirms. Reading the distinction as the handling was wrong when this was written, and
     * the count assertions in the tests said so.</p>
     */
    public static Set<ConflictType> resolvableTypes() {
        Set<ConflictType> types = new TreeSet<>(Comparator.comparing(Enum::name));
        for (ConflictType type : ConflictType.values()) {
            if (type != ConflictType.UNCLASSIFIED_TEXT) {
                types.add(type);
            }
        }
        return types;
    }

    /**
     * Conflict types with no registered resolver. Expected to be empty; a test
     * asserts it so a newly added type cannot be forgotten.
     *
     * <p>Measured over {@link #resolvableTypes()}, so the invariant stays as strong as it can be: a forgotten resolver
     * still fails it, and the one type that cannot have an owner does not pretend to.</p>
     */
    public static Set<ConflictType> unhandledTypes(List<ConflictResolver> resolvers) {
        Set<ConflictType> unhandled = new TreeSet<>(Comparator.comparing(Enum::name));
        for (ConflictType type : resolvableTypes()) {
            if (find(resolvers, type).isEmpty()) {
                unhandled.add(type);
            }
        }
        return unhandled;
    }
}
