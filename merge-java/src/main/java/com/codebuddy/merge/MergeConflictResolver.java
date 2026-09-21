// {@link com.codebuddy.merge.MergeConflictResolver} Orchestrates detection and resolution of merge conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates conflict detection and resolution for a merge.
 *
 * <p>For each detected conflict the orchestrator:
 * <ol>
 *   <li>replays a recorded sticky decision when one matches the conflict
 *       signature (this is what makes repeated base-branch updates cheap);</li>
 *   <li>otherwise asks the {@linkplain ConflictResolvers registry} for the
 *       resolver that owns the conflict type and runs it;</li>
 *   <li>otherwise records the conflict as requiring a human, with fix paths.</li>
 * </ol>
 *
 * <p>Resolvers are looked up in one place, so adding a resolver never requires
 * touching this class.
 */
public class MergeConflictResolver {

    private final String branchName;
    private final Path historyPath;
    private final List<ConflictResolver> resolvers;
    private final Map<ConflictType, ConflictResolver> resolversByType;
    private final BranchConflictStore historyStore;
    private final ConflictDetectionService detector;
    private final ResolutionVerifier verifier;
    private final TypeContext typeContext;

    /**
     * Builder for {@link MergeConflictResolver}.
     */
    public static class Builder {
        private String branchName = "unknown";
        private Path historyPath;
        private List<ConflictResolver> resolvers;
        private ConflictDetectionService detector;
        private boolean inMemoryOnly;
        private ResolutionVerifier verifier;
        private TypeContext typeContext;

        /**
         * Name of the branch whose history is being resolved, used as the
         * per-branch history directory name.
         */
        public Builder setBranchName(String branchName) {
            this.branchName = branchName;
            return this;
        }

        /**
         * Root directory for conflict history. Defaults to
         * {@code .jcodebuddy/merge-history/<branch>}.
         */
        public Builder setHistoryPath(Path historyPath) {
            this.historyPath = historyPath;
            return this;
        }

        public Builder setHistoryPath(String historyPath) {
            this.historyPath = Paths.get(historyPath);
            return this;
        }

        /**
         * Replace the resolver set. Defaults to
         * {@link ConflictResolvers#defaultResolvers()}, so new resolvers are
         * picked up without changing this builder.
         */
        public Builder setResolvers(List<ConflictResolver> resolvers) {
            this.resolvers = List.copyOf(resolvers);
            return this;
        }

        /**
         * Register a resolver, giving it ownership of its conflict type.
         *
         * <p>If a resolver for the same type is already present it is replaced,
         * because exactly one resolver may own a type. This lets a caller
         * override a built-in resolution without having to reconstruct the whole
         * list.
         */
        public Builder addResolver(ConflictResolver resolver) {
            Objects.requireNonNull(resolver, "resolver");
            List<ConflictResolver> current = resolvers == null
                ? new ArrayList<>(ConflictResolvers.defaultResolvers())
                : new ArrayList<>(resolvers);
            current.removeIf(existing -> existing.supportedType() == resolver.supportedType());
            current.add(resolver);
            this.resolvers = List.copyOf(current);
            return this;
        }

        public Builder setDetector(ConflictDetectionService detector) {
            this.detector = detector;
            return this;
        }

        /**
         * Set the verification gate. Defaults to
         * {@link ResolutionVerifier#structural()}, which checks that an automatic
         * resolution is still well formed and downgrades it to review if not.
         */
        public Builder setVerifier(ResolutionVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        /**
         * Supply the context needed to resolve types.
         *
         * <p>Only required when a registered resolver declares
         * {@link ConflictResolver#requiresTypeContext()}. A configuration whose
         * resolvers need types but has none is rejected by {@link #build} rather
         * than silently degrading to a comparison that cannot be trusted.
         */
        public Builder setTypeContext(TypeContext typeContext) {
            this.typeContext = typeContext;
            return this;
        }

        /**
         * Keep every decision in memory: nothing is read from or written to the
         * branch history. Use this for pure, single-shot resolution where the
         * result must not depend on - or pollute - recorded state.
         */
        public Builder setInMemoryOnly(boolean inMemoryOnly) {
            this.inMemoryOnly = inMemoryOnly;
            return this;
        }

        public MergeConflictResolver build() {
            List<ConflictResolver> chosen = resolvers == null
                ? ConflictResolvers.defaultResolvers()
                : resolvers;
            requireTypeContextIfNeeded(chosen);
            Path history = historyPath != null
                ? historyPath
                : Paths.get(".jcodebuddy", "merge-history", branchName);
            return new MergeConflictResolver(
                branchName,
                history,
                chosen,
                detector == null ? new ConflictDetectionService() : detector,
                inMemoryOnly,
                verifier == null ? ResolutionVerifier.structural() : verifier,
                typeContext
            );
        }

        /**
         * Refuse a configuration whose resolvers need types but cannot get them.
         *
         * <p>Checked at construction rather than at resolution: a caller can fix a
         * missing classpath immediately, whereas discovering it conflict by
         * conflict would look like the tool failing on ordinary input. Naming the
         * resolver makes the fix obvious.
         */
        private void requireTypeContextIfNeeded(List<ConflictResolver> resolvers) {
            if (typeContext != null) {
                return;
            }
            List<String> requiring = resolvers.stream()
                .filter(ConflictResolver::requiresTypeContext)
                .map(ConflictResolver::name)
                .sorted()
                .toList();
            if (!requiring.isEmpty()) {
                throw new IllegalStateException(
                    "these resolvers need a type context but none was supplied: " + requiring
                        + ". Build with setTypeContext(TypeContext.of(sourceRoot, classpath)), "
                        + "or exclude them from the resolver set.");
            }
        }
    }

    MergeConflictResolver(String branchName, Path historyPath,
                          List<ConflictResolver> resolvers,
                          ConflictDetectionService detector) {
        this(branchName, historyPath, resolvers, detector, false,
            ResolutionVerifier.structural(), null);
    }

    MergeConflictResolver(String branchName, Path historyPath,
                          List<ConflictResolver> resolvers,
                          ConflictDetectionService detector,
                          boolean inMemoryOnly) {
        this(branchName, historyPath, resolvers, detector, inMemoryOnly,
            ResolutionVerifier.structural(), null);
    }

    MergeConflictResolver(String branchName, Path historyPath,
                          List<ConflictResolver> resolvers,
                          ConflictDetectionService detector,
                          boolean inMemoryOnly,
                          ResolutionVerifier verifier) {
        this(branchName, historyPath, resolvers, detector, inMemoryOnly, verifier, null);
    }

    MergeConflictResolver(String branchName, Path historyPath,
                          List<ConflictResolver> resolvers,
                          ConflictDetectionService detector,
                          boolean inMemoryOnly,
                          ResolutionVerifier verifier,
                          TypeContext typeContext) {
        this.branchName = branchName == null ? "unknown" : branchName;
        this.historyPath = historyPath;
        this.resolvers = ConflictResolvers.sortByPriority(resolvers);
        this.resolversByType = ConflictResolvers.index(this.resolvers);
        this.historyStore = inMemoryOnly
            ? BranchConflictStore.instanceScoped()
            : new BranchConflictStore(this.branchName, historyPath);
        this.detector = detector;
        this.verifier = verifier;
        this.typeContext = typeContext;
    }

    /**
     * Create a resolver with the built-in configuration.
     *
     * <p>Deliberately in-memory: no branch was named, so there is no branch whose
     * history this resolver could legitimately read or write. It still records
     * decisions within its own lifetime, which keeps a single run self-consistent,
     * but it leaves nothing behind and cannot be influenced by an earlier run.
     * Use {@link Builder#setBranchName(String)} and
     * {@link Builder#setHistoryPath(Path)} when decisions should persist.
     *
     * @param typeContext context for the resolvers that need to resolve types;
     *                    required because the default set includes one
     */
    public static MergeConflictResolver create(TypeContext typeContext) {
        return new Builder()
            .setBranchName(BranchConflictStore.UNKNOWN_BRANCH)
            .setInMemoryOnly(true)
            .setTypeContext(typeContext)
            .build();
    }

    /**
     * Resolve a single, already-detected conflict.
     *
     * <p>Sticky replay happens first: if this branch previously recorded a
     * decision for the same conflict signature, that decision is applied without
     * asking again.
     */
    public ConflictResolution resolve(Conflict conflict) {
        Objects.requireNonNull(conflict, "conflict");

        Optional<ConflictResolution> replayed = replaySticky(conflict);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        ConflictResolver resolver = resolversByType.get(conflict.getType());
        ConflictResolution resolution = resolver == null
            ? null
            : resolver.resolve(conflict);

        if (resolution == null) {
            resolution = ConflictResolution.manual(conflict)
                .branchName(branchName)
                .explanation(resolver == null
                    ? "No resolver is registered for " + conflict.getType() + "."
                    : resolver.name() + " could not resolve this automatically.")
                .alternativePaths(manualOptions(conflict, resolver))
                .build();
        }

        // Carry the conflict's region so a report can tell whether this
        // resolution may be applied independently of the others.
        resolution = resolution.withRegion(conflict.getRegion());

        // WS3: nothing is applied without passing the verification gate.
        resolution = verifier.apply(conflict, resolution);

        if (isWorthRemembering(resolution)) {
            historyStore.record(conflict, resolution);
        }
        return resolution;
    }

    /**
     * Whether a resolution should be recorded in the branch's history.
     *
     * <p>Preference decisions are remembered: a rename or package move is a choice
     * that must stay consistent across updates, and the whole point of the store
     * is that the next update does not re-ask.
     *
     * <p>A plain automatic resolution is <b>not</b> recorded, even though it is
     * deterministic and would replay identically. Replaying it would bypass the
     * verification gate on every run after the first, so a resolution that was
     * checked once would be applied unchecked forever. Not recording it costs
     * nothing - the resolver reproduces it - and keeps the gate on the path of
     * every automatic change.
     */
    private static boolean isWorthRemembering(ConflictResolution resolution) {
        if (!resolution.isReplayable()) {
            return false;
        }
        return switch (resolution.getKind()) {
            case DEFERRED, REVIEW -> true;
            case AUTO, MANUAL -> false;
        };
    }

    /**
     * Resolve every conflict in the list.
     */
    public List<ConflictResolution> resolveAll(List<Conflict> conflicts) {
        Objects.requireNonNull(conflicts, "conflicts");
        List<ConflictResolution> resolutions = new ArrayList<>(conflicts.size());
        for (Conflict conflict : conflicts) {
            resolutions.add(resolve(conflict));
        }
        return resolutions;
    }

    /**
     * Detect and resolve everything in one call.
     */
    public MergeReport resolve(String filePath, String baseCode,
                               String branch1Code, String branch2Code) {
        List<Conflict> conflicts =
            detector.detect(filePath, baseCode, branch1Code, branch2Code, typeContext);
        return new MergeReport(filePath, conflicts, resolveAll(conflicts), branchName);
    }

    /**
     * Detect and resolve a file by reading its three versions from disk.
     */
    public MergeReport resolveFiles(Path filePath, Path basePath,
                                    Path branch1Path, Path branch2Path) {
        try {
            return resolve(
                filePath.toString().replace('\\', '/'),
                Files.readString(basePath, StandardCharsets.UTF_8),
                Files.readString(branch1Path, StandardCharsets.UTF_8),
                Files.readString(branch2Path, StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the versions of " + filePath, e);
        }
    }

    /**
     * Convenience overload taking string paths.
     */
    public MergeReport resolveFiles(String filePath, String basePath,
                                    String branch1Path, String branch2Path) {
        return resolveFiles(Paths.get(filePath), Paths.get(basePath),
            Paths.get(branch1Path), Paths.get(branch2Path));
    }

    /**
     * The fix paths offered for a conflict type, without resolving anything.
     * Useful for a UI that wants to show the options before applying them.
     */
    public List<FixPath> fixPathsFor(Conflict conflict) {
        Objects.requireNonNull(conflict, "conflict");
        ConflictResolver resolver = resolversByType.get(conflict.getType());
        if (resolver == null) {
            return manualOptions(conflict, null);
        }
        return resolver.getFixPaths(conflict);
    }

    /**
     * The fix paths offered for a conflict type using an empty conflict as the
     * probe, for callers that have no concrete conflict yet.
     */
    public List<FixPath> fixPathsFor(ConflictType type) {
        Conflict probe = new Conflict(type, "<probe>", type + " probe", "", "", "");
        return fixPathsFor(probe);
    }

    /**
     * Record a decision made by a human so it is replayed on later updates.
     */
    public void recordDecision(Conflict conflict, ConflictResolution decision) {
        Objects.requireNonNull(conflict, "conflict");
        Objects.requireNonNull(decision, "decision");
        historyStore.record(conflict, decision);
    }

    /**
     * The branch this resolver keeps history for.
     */
    public String getBranchName() {
        return branchName;
    }

    /**
     * The history directory this resolver writes to.
     */
    public Path getHistoryPath() {
        return historyPath;
    }

    /**
     * The active resolvers, in priority order.
     */
    public List<ConflictResolver> getResolvers() {
        return resolvers;
    }

    /**
     * The per-branch history store.
     */
    public BranchConflictStore getHistoryStore() {
        return historyStore;
    }

    private Optional<ConflictResolution> replaySticky(Conflict conflict) {
        return historyStore.findDecision(conflict).map(stored -> ConflictResolution.copyOf(stored)
            .filePath(conflict.getFilePath())
            // Re-label the result so the caller can tell "decided just now" from
            // "replayed from this branch's history".
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .sticky(true)
            .explanation("Reused the decision recorded for this branch on "
                + stored.getResolvedAt() + ": " + stored.getExplanation())
            .build());
    }

    private List<FixPath> manualOptions(Conflict conflict, ConflictResolver resolver) {
        if (resolver != null) {
            return resolver.getFixPaths(conflict);
        }
        return List.of(FixPath.builder()
            .conflictType(conflict.getType())
            .description("No resolver handles " + conflict.getType())
            .options("Resolve by hand", "Register a resolver for this type")
            .justification("No resolver is registered for this conflict type.")
            .impact("The conflict is left in place for a reviewer.")
            .build());
    }

    /**
     * The outcome of resolving one file: what was found, what was decided, and
     * how much of it a human still has to look at.
     */
    public static final class MergeReport {

        private final String filePath;
        private final List<Conflict> conflicts;
        private final List<ConflictResolution> resolutions;
        private final String branchName;

        MergeReport(String filePath, List<Conflict> conflicts,
                    List<ConflictResolution> resolutions, String branchName) {
            this.filePath = filePath;
            this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
            this.resolutions = Collections.unmodifiableList(new ArrayList<>(resolutions));
            this.branchName = branchName;
        }

        public String getFilePath() {
            return filePath;
        }

        public List<Conflict> getConflicts() {
            return conflicts;
        }

        public List<ConflictResolution> getResolutions() {
            return resolutions;
        }

        public String getBranchName() {
            return branchName;
        }

        /**
         * Resolutions that may be applied without asking.
         */
        public List<ConflictResolution> getAutoResolutions() {
            return byKind(ConflictResolution.ResolutionKind.AUTO);
        }

        /**
         * Resolutions that a reviewer should confirm.
         */
        public List<ConflictResolution> getReviewResolutions() {
            return byKind(ConflictResolution.ResolutionKind.REVIEW);
        }

        /**
         * Conflicts left for a human to resolve.
         */
        public List<ConflictResolution> getManualResolutions() {
            return byKind(ConflictResolution.ResolutionKind.MANUAL);
        }

        /**
         * The automatic resolutions that are safe to apply <em>on their own</em>,
         * because their region is known and does not overlap any conflict this
         * report could not resolve.
         *
         * <p>This is what stops a hand-resolvable import addition from being
         * stranded by an unrelated structural conflict elsewhere in the same
         * file: the disjoint automatic part can still be applied, and the rest is
         * left for review.
         *
         * <p>Conservative by construction. A resolution is excluded when its
         * region is unknown, or when it overlaps a review or manual conflict, so
         * the caller can never apply two changes to the same lines through this
         * method.
         */
        public List<ConflictResolution> getIndependentlyApplicable() {
            List<ConflictResolution> blocking = new ArrayList<>(getReviewResolutions());
            blocking.addAll(getManualResolutions());
            blocking.addAll(getDeferredResolutions());

            List<ConflictResolution> applicable = new ArrayList<>();
            for (ConflictResolution candidate : getAutoResolutions()) {
                if (!candidate.getRegion().isKnown()) {
                    // Without a region we cannot prove disjointness.
                    continue;
                }
                boolean clashes = blocking.stream().anyMatch(other ->
                    candidate.getRegion().overlaps(other.getRegion()));
                if (!clashes) {
                    applicable.add(candidate);
                }
            }
            return applicable;
        }

        /**
         * Resolutions replayed from this branch's recorded history.
         */
        public List<ConflictResolution> getDeferredResolutions() {
            return byKind(ConflictResolution.ResolutionKind.DEFERRED);
        }

        /**
         * Automatic resolutions that were <em>not</em> independently applicable,
         * with the reason. Useful for explaining why a file was not fully
         * resolved.
         */
        public List<ConflictResolution> getBlockedAutoResolutions() {
            List<ConflictResolution> applicable = getIndependentlyApplicable();
            List<ConflictResolution> blocked = new ArrayList<>();
            for (ConflictResolution auto : getAutoResolutions()) {
                if (!applicable.contains(auto)) {
                    blocked.add(auto);
                }
            }
            return blocked;
        }

        public boolean hasUnresolvedConflicts() {
            return !getManualResolutions().isEmpty();
        }

        /**
         * True when nothing at all was detected.
         */
        public boolean isClean() {
            return conflicts.isEmpty();
        }

        /**
         * True when there is nothing to report: no conflicts and no resolutions.
         * A report can carry resolutions without conflicts when it is assembled
         * directly, so both are consulted.
         */
        private boolean hasNothingToReport() {
            return conflicts.isEmpty() && resolutions.isEmpty();
        }

        /**
         * True when every detected conflict was resolved automatically and the
         * automatic resolutions are all independently applicable, so the file can
         * be written without any human involvement.
         */
        public boolean isFullyAutomatic() {
            return !isClean()
                && getReviewResolutions().isEmpty()
                && getManualResolutions().isEmpty()
                && getAutoResolutions().size() == getIndependentlyApplicable().size();
        }

        /**
         * A per-conflict view of the outcome, naming the region and what happened
         * there. This is the form a reviewer needs: "lines 12-14 were merged
         * automatically; lines 40-52 need a decision".
         */
        public List<String> describeRegions() {
            List<String> lines = new ArrayList<>();
            for (ConflictResolution resolution : resolutions) {
                lines.add(resolution.getRegion() + " -> " + resolution.getKind()
                    + " (" + resolution.getType() + ")"
                    + (resolution.getVerification() == ConflictResolution.Verification.FAILED
                        ? " [verification failed]" : ""));
            }
            Collections.sort(lines);
            return lines;
        }

        /**
         * A short line summarising the outcome, suitable for a merge log.
         *
         * <p>Distinguishes <em>automatic</em> from <em>independently applicable</em>
         * on purpose: a caller reading the summary must not believe it can apply
         * an automatic resolution that a manual conflict overlaps.
         */
        public String summarize() {
            if (hasNothingToReport()) {
                return filePath + ": no conflicts detected.";
            }
            StringBuilder summary = new StringBuilder(filePath)
                .append(": ").append(conflicts.size()).append(" conflict(s) - ")
                .append(getAutoResolutions().size()).append(" auto (")
                .append(getIndependentlyApplicable().size()).append(" applicable), ")
                .append(getReviewResolutions().size()).append(" for review, ")
                .append(getManualResolutions().size()).append(" manual");
            if (!getDeferredResolutions().isEmpty()) {
                summary.append(", ").append(getDeferredResolutions().size()).append(" replayed");
            }
            return summary.append('.').toString();
        }

        private List<ConflictResolution> byKind(ConflictResolution.ResolutionKind kind) {
            List<ConflictResolution> matching = new ArrayList<>();
            for (ConflictResolution resolution : resolutions) {
                if (resolution.getKind() == kind) {
                    matching.add(resolution);
                }
            }
            return matching;
        }

        @Override
        public String toString() {
            return summarize();
        }
    }
}
