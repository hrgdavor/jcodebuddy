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
            new ApiIncompatibilityConflictResolver()
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
     * Conflict types with no registered resolver. Expected to be empty; a test
     * asserts it so a newly added type cannot be forgotten.
     */
    public static Set<ConflictType> unhandledTypes(List<ConflictResolver> resolvers) {
        Set<ConflictType> unhandled = new TreeSet<>(Comparator.comparing(Enum::name));
        for (ConflictType type : ConflictType.values()) {
            if (find(resolvers, type).isEmpty()) {
                unhandled.add(type);
            }
        }
        return unhandled;
    }
}
