// {@link com.codebuddy.merge.AbstractConflictResolver} Reusable base class for conflict resolvers.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class that makes adding a conflict resolver a small, uniform change.
 *
 * <h2>What a subclass writes</h2>
 * <ol>
 *   <li>{@link #supportedType()} - the one {@link ConflictType} it owns.</li>
 *   <li>{@link #doResolve(Conflict)} - the strategy, using the
 *       {@code resolutionFor}/{@code autoResolution} helpers.</li>
 *   <li>{@link #describeOptions(Conflict)} - the fix paths offered to a
 *       reviewer.</li>
 * </ol>
 *
 * <h2>What this class supplies</h2>
 * <ul>
 *   <li>{@link #supports(ConflictType)} derived from {@link #supportedType()},
 *       so no resolver can accidentally claim a second type.</li>
 *   <li>{@link #resolve(Conflict)} guarding against a {@code null} result and
 *       substituting the manual fallback, so reviewers always get fix paths
 *       instead of an exception.</li>
 *   <li>{@link #getFixPaths(Conflict)} guarding against a {@code null} list and
 *       guaranteeing the manual escape hatch is always present.</li>
 *   <li>{@link #newFixPath(Conflict)} pre-filled with this resolver's conflict
 *       type, so fix paths are uniformly shaped.</li>
 *   <li>{@link #stickyByDefault()} so a subclass declares replay intent in one
 *       place instead of at each build site.</li>
 * </ul>
 */
public abstract class AbstractConflictResolver implements ConflictResolver {

    /**
     * The conflict type this resolver owns. Exactly one per resolver keeps the
     * registry unambiguous.
     */
    @Override
    public abstract ConflictType supportedType();

    /**
     * Produce a resolution, or return {@code null} / throw to signal that the
     * conflict cannot be decided automatically. Both cases are turned into the
     * manual fallback by {@link #resolve(Conflict)}.
     */
    protected abstract ConflictResolution doResolve(Conflict conflict);

    /**
     * The reviewer-facing options. Return an empty list to declare the conflict
     * unambiguously auto-resolvable; the manual escape hatch from
     * {@link #manualFixPath(Conflict)} is appended when the list is empty.
     */
    protected abstract List<FixPath> describeOptions(Conflict conflict);

    @Override
    public final ConflictResolution resolve(Conflict conflict) {
        Objects.requireNonNull(conflict, "conflict");
        ConflictResolution resolution = null;
        try {
            resolution = doResolve(conflict);
        } catch (RuntimeException ex) {
            // A resolver that cannot cope must degrade to manual review rather
            // than abort the whole merge.
            resolution = null;
        }
        if (resolution == null) {
            return fallback(conflict);
        }
        if (resolution.getAlternativePaths().isEmpty()) {
            resolution = ConflictResolution.copyOf(resolution)
                .alternativePaths(optionsFor(conflict))
                .build();
        }
        return resolution;
    }

    @Override
    public final List<FixPath> getFixPaths(Conflict conflict) {
        Objects.requireNonNull(conflict, "conflict");
        return optionsFor(conflict);
    }

    private List<FixPath> optionsFor(Conflict conflict) {
        List<FixPath> described;
        try {
            described = describeOptions(conflict);
        } catch (RuntimeException ex) {
            described = null;
        }
        List<FixPath> options = new ArrayList<>();
        if (described != null) {
            for (FixPath path : described) {
                if (path != null) {
                    options.add(path);
                }
            }
        }
        if (options.isEmpty()) {
            options.add(manualFixPath(conflict));
        }
        return List.copyOf(options);
    }

    private ConflictResolution fallback(Conflict conflict) {
        return ConflictResolution.manual(conflict)
            .branchName(null)
            // The objection's strength is the resolver's own basis: what it read is what it is
            // objecting from, so a resolver that reads structure objects with more evidence than one
            // that reads a line of text.
            .analysisLevel(maxAnalysisLevel())
            .explanation(name() + " could not resolve " + conflict.getType()
                + " automatically; a reviewer must choose.")
            .alternativePaths(optionsFor(conflict))
            .build();
    }

    /**
     * Whether resolutions from this resolver should be replayed automatically
     * when the same conflict signature reappears in the same branch.
     *
     * <p>Defaults to the conflict type's policy: additive conflicts
     * ({@link ConflictType.Handling#AUTO}) and preference conflicts
     * ({@link ConflictType.Handling#STICKY}) are remembered, while
     * {@code REVIEW} and {@code MANUAL} conflicts are re-decided each time. That
     * is the desired asymmetry - a deterministic union is safe to replay, whereas
     * a judgement call deserves a second look.
     *
     * <p>Override to diverge from the type's default, for example to force a
     * heuristic resolver to always ask.
     */
    protected boolean stickyByDefault() {
        ConflictType type = supportedType();
        return type != null && (type.isAutoResolvable() || type.isStickyByDefault());
    }

    /**
     * Start building a resolution pre-filled with the conflict's three inputs
     * and this resolver's default sticky flag.
     */
    protected ConflictResolution.Builder resolutionFor(Conflict conflict,
                                                       ConflictResolution.ResolutionStrategy strategy,
                                                       ConflictResolution.ResolutionKind kind) {
        return ConflictResolution.builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .baseCode(conflict.getBaseCode())
            .branch1Code(conflict.getBranch1Code())
            .branch2Code(conflict.getBranch2Code())
            .region(conflict.getRegion())
            .resolutionStrategy(strategy)
            .kind(kind)
            .analysisLevel(maxAnalysisLevel())
            .explainedSpan(explainedSpanFor(conflict))
            .sticky(stickyByDefault());
    }

    /**
     * The part of the conflicting region this resolver's answers <b>explain</b>, defaulting to nothing.
     *
     * <p>This is the one declaration that lets an answer <em>settle</em> a region: the conflict leaves the
     * working set, no lower tier is asked about it, and no objection to it is ever constructed
     * ({@link Reliability}, plan step 4.19, DEC-046 clause 4). It is therefore <b>not</b> the same
     * declaration as {@link #maxAnalysisLevel()}, and overriding one does not imply the other:
     *
     * <ul>
     *   <li>{@code maxAnalysisLevel()} says what a resolver <em>reads</em>, and orders the pass;</li>
     *   <li>this says that the answer <em>is</em> the region's content, and settles it.</li>
     * </ul>
     *
     * <p><b>The default is {@link Region#unknown()}, which explains nothing</b>, and the default is
     * load-bearing rather than conservative politeness: a resolver that said nothing while appearing to
     * explain its whole region would settle every conflict it was asked about, which is how a confident
     * sentence becomes a licence. Override it only where the answer really is knowledge of what is in the
     * region — the declarations the resolver parsed, or a block it is authoritative over — and say why at
     * the override. An answer that keeps every line of both sides needs no override at all;
     * {@link Reliability} checks that case independently.
     */
    protected Region explainedSpanFor(Conflict conflict) {
        return Region.unknown();
    }

    /**
     * Start building a resolution that can be applied without asking.
     */
    protected ConflictResolution.Builder autoResolution(Conflict conflict,
                                                        ConflictResolution.ResolutionStrategy strategy) {
        return resolutionFor(conflict, strategy, ConflictResolution.ResolutionKind.AUTO);
    }

    /**
     * Start building a resolution that is viable but should be confirmed by a
     * reviewer before it is applied.
     */
    protected ConflictResolution.Builder reviewResolution(Conflict conflict,
                                                          ConflictResolution.ResolutionStrategy strategy) {
        return resolutionFor(conflict, strategy, ConflictResolution.ResolutionKind.REVIEW);
    }

    /**
     * Start building a fix path pre-filled with this resolver's conflict type.
     */
    protected FixPath.Builder newFixPath(Conflict conflict) {
        return FixPath.builder().conflictType(conflict.getType());
    }

    /**
     * The escape hatch every resolver offers: hand the decision to a human.
     */
    protected FixPath manualFixPath(Conflict conflict) {
        return newFixPath(conflict)
            .description("Resolve the " + conflict.getType() + " conflict in "
                + conflict.getFilePath() + " by hand")
            .options("Apply branch 1", "Apply branch 2", "Merge both", "Rewrite")
            .justification("The automated strategies available for this conflict type "
                + "cannot be applied without risking a semantic change.")
            .impact("Requires reviewer time; nothing is applied automatically.")
            .build();
    }

    @Override
    public String toString() {
        return name() + "[" + supportedType() + "]";
    }
}
