// {@link com.codebuddy.merge.ConflictResolver} Interface for resolving specific types of conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.List;

/**
 * A resolver for one kind of merge conflict.
 *
 * <h2>Adding a new resolver</h2>
 *
 * The intended extension point is {@link AbstractConflictResolver}, which
 * supplies identity, fix-path plumbing and the standard manual fallback. A new
 * resolver normally only declares which type it handles and how it resolves:
 *
 * <pre>{@code
 * public final class MyConflictResolver extends AbstractConflictResolver {
 *
 *     @Override
 *     public ConflictType supportedType() {
 *         return ConflictType.MY_TYPE;
 *     }
 *
 *     @Override
 *     protected ConflictResolution doResolve(Conflict conflict) {
 *         return ConflictResolution.auto(conflict, ResolutionStrategy.KEEP_BOTH)
 *             .resolvedCode(conflict.getBranch1Code() + conflict.getBranch2Code())
 *             .explanation("Both sides add compatible members.")
 *             .build();
 *     }
 *
 *     @Override
 *     protected List<FixPath> describeOptions(Conflict conflict) {
 *         return List.of(
 *             newFixPath(conflict)
 *                 .description("Keep both members")
 *                 .options("Keep both", "Keep one", "Manual review")
 *                 .recommended("Keep both")
 *                 .justification("The members do not collide.")
 *                 .impact("None - both remain reachable.")
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * Then register it once in {@link ConflictResolvers#defaultResolvers()} (or pass
 * it explicitly to {@link MergeConflictResolver.Builder#addResolver}), and the
 * conflict is handled everywhere, including history replay and tests.
 *
 * Implementations must be stateless and thread-safe: the registry shares one
 * instance across resolutions.
 */
public interface ConflictResolver {

    /**
     * The single conflict type this resolver owns.
     *
     * Prefer overriding this in {@link AbstractConflictResolver} over
     * {@link #supports(ConflictType)}; the default {@code supports}
     * implementation delegates here.
     */
    ConflictType supportedType();

    /**
     * True when this resolver handles the given conflict type.
     * Defaults to comparing against {@link #supportedType()}.
     */
    default boolean supports(ConflictType type) {
        return type != null && type == supportedType();
    }

    /**
     * Attempt to resolve the conflict.
     *
     * @return a resolution, or {@code null} when this resolver declines; a
     *         declining resolver lets the orchestrator fall back to the manual
     *         path rather than guessing.
     */
    ConflictResolution resolve(Conflict conflict);

    /**
     * The options a reviewer should see when this resolver cannot decide on its
     * own. Must never return {@code null}; return an empty list when the
     * conflict is unambiguously auto-resolvable.
     */
    List<FixPath> getFixPaths(Conflict conflict);

    /**
     * Short human-readable name used in diagnostics and history records.
     *
     * <p>The conventional {@code ...ConflictResolver} or {@code ...Resolver}
     * suffix is trimmed so that messages read "Import could not resolve ..."
     * rather than repeating the word resolver. A class that carries neither
     * suffix is reported under its own name.
     */
    default String name() {
        String simple = getClass().getSimpleName();
        for (String suffix : new String[] {"ConflictResolver", "Resolver"}) {
            if (simple.endsWith(suffix) && simple.length() > suffix.length()) {
                return simple.substring(0, simple.length() - suffix.length());
            }
        }
        return simple;
    }

    /**
     * Ordering hint when several resolvers could claim a type; higher runs
     * first. Defaults to zero.
     */
    default int priority() {
        return 0;
    }

    /**
     * Whether a {@link TypeContext} is a <strong>hard requirement</strong> for this
     * resolver — that is, whether it can say anything useful at all without one.
     *
     * <p>Defaults to {@code false}. Most conflicts are decided from text alone —
     * keeping the union of two import lists, for example — and demanding type
     * information for them would burden every caller for nothing.
     *
     * <p>Declaring {@code true} makes the context mandatory: an orchestrator built
     * without one fails at construction, naming this resolver, and the caller's remedy
     * is to supply a context or **remove this resolver from the set by hand**. That is
     * the right answer only for a resolver that genuinely cannot degrade: comparing two
     * <em>resolved</em> parameter types is impossible without a classpath, so
     * {@link OverloadAddConflictResolver} declares it and always will.
     *
     * <p>A resolver that <em>can</em> still answer something — because part of its rule
     * is the language's rather than the type system's, or because it carries a built-in
     * table of the common cases — must leave this {@code false} and instead
     * <strong>degrade visibly</strong>: answer as well as it can and attach a warning
     * to the resolution ({@link ConflictResolution.Builder#warning(String)}), so the
     * reviewer can see that the basis was weaker. Silence is the failure mode both
     * options exist to prevent: a mandatory context hides a resolver's degraded answers
     * behind an exception, while an undeclared degradation hides them behind a
     * confident sentence.
     *
     * <p>{@link TypeChangeConflictResolver} is the working example of the second kind:
     * it declares {@code false}, resolves through the type system when it can, and falls
     * back to its own table — with a warning — when it cannot.
     */
    default boolean requiresTypeContext() {
        return false;
    }
}
