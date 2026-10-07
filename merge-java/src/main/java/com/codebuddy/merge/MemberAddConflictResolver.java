// {@link com.codebuddy.merge.MemberAddConflictResolver} Keeps the distinct members both branches added.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the case where both branches added a <em>distinct</em> member in the same place.
 *
 * <h2>Why this is a conflict at all</h2>
 *
 * <p>Two branches each appending a method — or a field — at the same point in a class produce adjacent
 * insertions, which is a conflict to every line-based merge tool, including this one, whose detection
 * reported the block as a structural residual and left it for a human. The two additions do not
 * interact: nothing collides, and each call site binds to the member it names.
 *
 * <h2>Why text cannot decide it, and structure can</h2>
 *
 * <p>Comparing the two sides as text sees "both branches replaced this region", which is true and
 * useless. Comparing the <em>declared members</em> sees that the base's members survive on both sides
 * and that the two additions are different declarations — and that is the whole answer. This is the
 * level where a structural comparison beats a textual one, which is why the resolver declares at least
 * {@link AnalysisLevel#STRUCTURE}: a claim that read one line of text has no business outranking it.
 *
 * <h2>Its level follows what it actually did</h2>
 *
 * <p>Two members collide when their <em>signatures</em> are the same, and a signature is a question
 * about resolved types: {@code process(List<String>)} on one side and
 * {@code process(java.util.List<java.lang.String>)} on the other are one member, not two. So when the
 * conflict carries a type context the comparison is made on resolved signatures and the resolution
 * records {@link AnalysisLevel#PLATFORM_TYPES} — or {@link AnalysisLevel#PROJECT_TYPES} when the
 * classpath carried the project's own entries. Without a context, two parameter lists cannot be
 * compared as types at all, so only additions whose method <em>names</em> are disjoint are accepted,
 * and the resolution records {@link AnalysisLevel#STRUCTURE}. That is strictly weaker and never
 * guessed at: names that match refuse the block rather than assume they are different members.
 *
 * <h2>What it refuses</h2>
 *
 * <p>It declines whenever the additions could interact — a shared name or signature, no addition on
 * one side, or sides that cannot be parsed while a classpath was available to parse them. Declining
 * routes to the manual fallback, so an unusual shape is a human's decision rather than a guess.
 */
public final class MemberAddConflictResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.MEMBER_ADD;
    }

    /**
     * {@link AnalysisLevel#PROJECT_TYPES}: with the project's own classpath the comparison is made on
     * resolved signatures, which is the strongest evidence this question can rest on.
     */
    @Override
    public AnalysisLevel maxAnalysisLevel() {
        return AnalysisLevel.PROJECT_TYPES;
    }

    /**
     * The region this resolver was asked about, and it is justified rather than assumed.
     *
     * <p>{@link #doResolve} reaches a resolution only by <em>recognising</em> the declarations on both
     * sides and proving they do not collide: a shared name or signature returns {@code null} above, and so
     * does a union that cannot be built. The region's content is therefore known rather than inferred from
     * a line comparison, which is exactly what makes this answer able to settle the region — the conflict
     * leaves the working set and a text-level objection to those lines is never constructed. This is the
     * instruction's own first example (plan step 4.19, DEC-046 clause 4).
     */
    @Override
    protected Region explainedSpanFor(Conflict conflict) {
        return conflict.getRegion();
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        // A blank base reaches only a conflict detection already established a *known* base for
        // (ConflictType.MEMBER_ADD is emitted only then), so here it means "the base had nothing in
        // this region" — which is what makes a modifier provable evidence about the inserted lines.
        boolean insertedAtAnEmptyBase = conflict.getBaseCode().isBlank();
        Set<String> added1 = addedMembers(conflict.getBaseCode(), conflict.getBranch1Code(),
            insertedAtAnEmptyBase);
        Set<String> added2 = addedMembers(conflict.getBaseCode(), conflict.getBranch2Code(),
            insertedAtAnEmptyBase);

        if (added1.isEmpty() || added2.isEmpty()) {
            // Nothing was added on one side, so this is not two additions.
            return null;
        }

        AnalysisLevel level = null;
        TypeContext context = conflict.getTypeContext();
        if (context != null) {
            Set<String> resolved1 = resolvedMembers(conflict, context, conflict.getBranch1Code(),
                insertedAtAnEmptyBase);
            Set<String> resolved2 = resolvedMembers(conflict, context, conflict.getBranch2Code(),
                insertedAtAnEmptyBase);
            Set<String> resolvedBase = resolvedMembers(conflict, context, conflict.getBaseCode(),
                insertedAtAnEmptyBase);
            if (resolved1 != null && resolved2 != null && resolvedBase != null) {
                resolved1.removeAll(resolvedBase);
                resolved2.removeAll(resolvedBase);
                if (resolved1.isEmpty() || resolved2.isEmpty()
                    || !intersection(resolved1, resolved2).isEmpty()) {
                    return null;
                }
                level = context.hasProjectEntries()
                    ? AnalysisLevel.PROJECT_TYPES
                    : AnalysisLevel.PLATFORM_TYPES;
            }
        }

        if (level == null) {
            // Resolution was unavailable or the fragment could not be parsed. Comparing method names is
            // strictly conservative — matching names refuse the block rather than assume two parameter
            // lists differ — so it can only decide less, never decide wrongly. That is why it is an
            // acceptable fallback here where matching simple type names is not: a name that matches
            // proves nothing, so nothing is concluded from it.
            if (!intersection(namesOf(added1), namesOf(added2)).isEmpty()) {
                return null;
            }
            level = AnalysisLevel.STRUCTURE;
        }

        String merged = SideUnion.of(conflict.getBranch1Code(), conflict.getBranch2Code());
        if (merged == null) {
            // The two sides are not "what they share, then one addition each", so a union could
            // repeat a member. A human decides rather than a guess.
            return null;
        }

        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(merged)
            .analysisLevel(level)
            .explanation("Both branches added distinct members (" + added1 + " and " + added2
                + "), which do not collide, so both are kept.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    /**
     * The members present in {@code code} that {@code baseCode} does not declare, as declared text.
     *
     * <p>Re-derived here rather than taken from the detector, because a resolver that trusted a
     * detection verdict would be unable to refuse an unusual shape — and refusing is what keeps this
     * one safe.
     */
    static Set<String> addedMembers(String baseCode, String code) {
        return addedMembers(baseCode, code, baseCode == null || baseCode.isBlank());
    }

    /** The same, told whether a field may be recognised from its access modifier alone. */
    static Set<String> addedMembers(String baseCode, String code, boolean allowAccessibleFields) {
        Set<String> added = new LinkedHashSet<>(DeclarationScanner.membersOf(code, allowAccessibleFields));
        added.removeAll(DeclarationScanner.membersOf(baseCode, allowAccessibleFields));
        return added;
    }

    /**
     * The members of {@code code} with their parameter types <em>resolved</em>, or {@code null} when
     * the code could not be parsed.
     *
     * <p>Fields are included by name: a field's identity is its name, so nothing about it needs
     * resolving. A method's is its name and its resolved parameter types, which is what makes
     * {@code List<String>} and {@code java.util.List<java.lang.String>} one member rather than two.
     */
    private static Set<String> resolvedMembers(Conflict conflict, TypeContext context, String code,
                                              boolean allowAccessibleFields) {
        ResolvedTypeReader.Reading reading =
            ResolvedTypeReader.read(code, conflict.getFilePath(), context);
        if (!reading.succeeded()) {
            return null;
        }

        Set<String> members = new LinkedHashSet<>();
        for (String name : reading.methodNames()) {
            for (String parameters : reading.parametersOf(name)) {
                members.add(name + "(" + parameters + ")");
            }
        }
        // The field evidence has to be the same evidence the additions were computed from, or a bare
        // insertion reads as "nothing added" on the resolved path and the block is declined for no
        // reason. That is exactly what happened the first time this was written.
        members.addAll(DeclarationScanner.fieldNames(code));
        if (allowAccessibleFields) {
            members.addAll(DeclarationScanner.accessibleFieldNames(code));
        }
        return members;
    }

    /** The names of a set of member signatures, which is all a field has. */
    private static Set<String> namesOf(Set<String> members) {
        Set<String> names = new LinkedHashSet<>();
        for (String member : members) {
            int parenthesis = member.indexOf('(');
            names.add(parenthesis < 0 ? member : member.substring(0, parenthesis));
        }
        return names;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> shared = new LinkedHashSet<>(left);
        shared.retainAll(right);
        return shared;
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        Set<String> added1 = addedMembers(conflict.getBaseCode(), conflict.getBranch1Code());
        Set<String> added2 = addedMembers(conflict.getBaseCode(), conflict.getBranch2Code());

        return List.of(
            newFixPath(conflict)
                .description("Keep both added members")
                .options("Keep both", "Keep branch 1's member", "Keep branch 2's member")
                .recommended("Keep both")
                .justification("The additions are " + added1 + " and " + added2
                    + ", which do not collide: each call site binds to the member it names.")
                .impact("None - both members remain reachable, and neither branch's work is lost.")
                .build(),
            manualFixPath(conflict)
        );
    }
}