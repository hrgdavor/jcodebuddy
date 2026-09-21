// {@link com.codebuddy.merge.OverloadAddConflictResolver} Resolves overload addition conflicts using resolved types.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves conflicts where both branches add a method with the same name.
 *
 * <p>Two new methods with different parameter lists are overloads and coexist
 * happily - the same additive case as imports, which should never be reported as a
 * conflict. Two new methods with the <em>same</em> parameter list are a real
 * collision and are escalated for review.
 *
 * <h2>Comparison is by resolved type, not by spelling</h2>
 *
 * <p>This is the one conflict type whose decision is a question about the language
 * rather than about text, so it is the one that declares
 * {@link #requiresTypeContext()}. The same parameter compiles as
 * {@code List<String>} on one branch and {@code java.util.List<java.lang.String>}
 * on the other; a text comparison calls those different and reports a collision
 * that does not exist. Reading the resolved types through
 * {@link ResolvedTypeReader} makes them one signature.
 *
 * <p>That decision also means there is <b>one comparison path</b>. A token-based
 * fallback would be a second opinion that could disagree with this one, which is a
 * worse failure than having none.
 *
 * <h2>Failure is escalated, not guessed</h2>
 *
 * <p>When a version cannot be parsed, this conflict becomes {@code MANUAL} and the
 * parser's own message is attached as the reason. The rest of the file still
 * resolves: a limitation in one method must not force a human to re-review an
 * unrelated import merge.
 */
public final class OverloadAddConflictResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.OVERLOAD_ADD;
    }

    /**
     * Deciding whether two parameter lists are the same is a question about
     * resolved types, so this resolver cannot work from text alone.
     */
    @Override
    public boolean requiresTypeContext() {
        return true;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        Set<String> names1 = ResolvedTypeReader.methodNamesOnly(conflict.getBranch1Code());
        Set<String> names2 = ResolvedTypeReader.methodNamesOnly(conflict.getBranch2Code());
        Set<String> baseNames = ResolvedTypeReader.methodNamesOnly(conflict.getBaseCode());

        Set<String> sharedNames = new LinkedHashSet<>(names1);
        sharedNames.retainAll(names2);
        if (sharedNames.isEmpty()) {
            // No method was touched on both sides: not an overload conflict.
            return null;
        }

        TypeContext context = conflict.getTypeContext();
        if (context == null) {
            // The orchestrator refuses to build a resolver set that needs a context
            // without one, so reaching here means a resolver was used directly.
            return declined(conflict, "no type context was supplied, so parameter "
                + "types cannot be resolved");
        }

        ResolvedTypeReader.Reading base = ResolvedTypeReader.read(
            conflict.getBaseCode(), conflict.getFilePath(), context);
        ResolvedTypeReader.Reading branch1 = ResolvedTypeReader.read(
            conflict.getBranch1Code(), conflict.getFilePath(), context);
        ResolvedTypeReader.Reading branch2 = ResolvedTypeReader.read(
            conflict.getBranch2Code(), conflict.getFilePath(), context);

        String failure = firstFailure(base, branch1, branch2);
        if (failure != null) {
            // Fail loud: without resolved types the comparison could report a
            // collision that does not exist, or miss one that does.
            return declined(conflict, "one side of this conflict could not be parsed, so "
                + "parameter types cannot be resolved: " + failure);
        }

        Map<String, Set<String>> added1 = addedParameters(branch1, base);
        Map<String, Set<String>> added2 = addedParameters(branch2, base);

        Set<String> contested = new LinkedHashSet<>(added1.keySet());
        contested.retainAll(added2.keySet());

        // The method to arbitrate for: one that both branches added parameters to,
        // and that the base does not already have with those parameters.
        List<String> candidates = new ArrayList<>();
        for (String name : contested) {
            Set<String> fromBase = base.parametersOf(name);
            Set<String> withoutBase1 = without(added1.get(name), fromBase);
            Set<String> withoutBase2 = without(added2.get(name), fromBase);
            if (withoutBase1.isEmpty() || withoutBase2.isEmpty()) {
                // One side only repeated what base already declared.
                continue;
            }
            candidates.add(name);
        }

        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            // More than one method competes; deciding which the reviewer meant
            // would be a guess, so the whole set goes to a human.
            return declined(conflict, "several methods were changed on both branches ("
                + candidates + "), so the intended overload cannot be identified");
        }

        String methodName = candidates.get(0);
        Set<String> baseParameters = base.parametersOf(methodName);
        Set<String> parameters1 = without(added1.get(methodName), baseParameters);
        Set<String> parameters2 = without(added2.get(methodName), baseParameters);

        Set<String> collisions = new LinkedHashSet<>(parameters1);
        collisions.retainAll(parameters2);

        if (!collisions.isEmpty()) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("Both branches added '" + methodName + "' with the same "
                    + "parameter types " + collisions + "; only one can exist.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(conflict.getBranch1Code() + "\n" + conflict.getBranch2Code())
            .explanation("The added overloads of '" + methodName + "' have different resolved "
                + "parameter types (" + parameters1 + " vs " + parameters2
                + "), so both are kept.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        return List.of(
            newFixPath(conflict)
                .description("Keep both added overloads")
                .options("Keep both overloads", "Keep branch 1's method", "Keep branch 2's method")
                .recommended("Keep both overloads")
                .justification("Overloads are additive when their resolved parameter types "
                    + "differ: each call site binds to the matching method.")
                .impact("None - both methods remain reachable.")
                .build(),
            newFixPath(conflict)
                .description("Resolve two methods with identical parameter types")
                .options("Merge bodies", "Keep one body", "Delegate one to the other")
                .justification("Identical parameter types must collapse to a single declaration, "
                    + "so keeping both would not compile.")
                .impact("One implementation must be discarded or merged; callers may need "
                    + "adjusting depending on which behaviour is kept.")
                .build()
        );
    }

    /**
     * A manual resolution carrying the reason the comparison could not be made.
     */
    private ConflictResolution declined(Conflict conflict, String reason) {
        return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MANUAL)
            .kind(ConflictResolution.ResolutionKind.MANUAL)
            .resolvedCode(ConflictResolution.MANUAL_MARKER)
            .explanation("Overload resolution could not decide this conflict: " + reason)
            .alternativePaths(List.of(
                newFixPath(conflict)
                    .description("Resolve the overload conflict by hand")
                    .options("Keep both", "Keep branch 1", "Keep branch 2", "Rewrite")
                    .justification(reason)
                    .impact("Requires reviewer time; nothing is applied automatically.")
                    .build(),
                newFixPath(conflict)
                    .description("Provide a type context so the comparison can be made")
                    .options("Build the resolver with a classpath covering these types")
                    .recommended("Build the resolver with a classpath covering these types")
                    .justification("Parameter comparison needs the resolved types, which "
                        + "requires a classpath to resolve against.")
                    .impact("Without it this conflict always needs a human.")
                    .build()))
            .build();
    }

    /**
     * For each method name, the parameter signatures a version declares.
     */
    private static Map<String, Set<String>> addedParameters(ResolvedTypeReader.Reading reading,
                                                            ResolvedTypeReader.Reading base) {
        Map<String, Set<String>> added = new LinkedHashMap<>();
        for (String name : reading.methodNames()) {
            Set<String> extra = without(reading.parametersOf(name), base.parametersOf(name));
            if (!extra.isEmpty()) {
                added.put(name, extra);
            }
        }
        return added;
    }

    private static Set<String> without(Set<String> left, Set<String> right) {
        if (left == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right == null ? Set.of() : right);
        return result;
    }

    private static String firstFailure(ResolvedTypeReader.Reading... readings) {
        for (ResolvedTypeReader.Reading reading : readings) {
            if (!reading.succeeded()) {
                return reading.failure();
            }
        }
        return null;
    }
}
