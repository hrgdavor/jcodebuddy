// {@link com.codebuddy.merge.ConflictResolution} Represents a resolved merge conflict with metadata.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of attempting to resolve one conflicting hunk.
 *
 * A resolution records the three inputs it was derived from, the code it
 * produced, how it was produced, and whether it is safe to replay. The
 * {@link ResolutionKind} is what callers branch on:
 *
 * <ul>
 *   <li>{@link ResolutionKind#AUTO} - applied without asking.</li>
 *   <li>{@link ResolutionKind#REVIEW} - viable, but a human should confirm;
 *       always accompanied by {@link FixPath}s.</li>
 *   <li>{@link ResolutionKind#MANUAL} - no safe automatic answer; the fix
 *       paths describe the options.</li>
 *   <li>{@link ResolutionKind#DEFERRED} - left to a previously recorded
 *       sticky decision for the same conflict signature.</li>
 * </ul>
 *
 * <p>Beside that classification a resolution records <em>how strong its basis was</em>: the
 * {@link AnalysisLevel} it reached, and {@link #getWarnings() warnings} naming what was missing.
 * The kind says what may be done with the answer; the level says how much the answer was checked
 * against, which is what decides between two answers that disagree about one block.
 */
public final class ConflictResolution {

    /**
     * How the resolution was reached.
     */
    public enum ResolutionStrategy {
        /** Keep the changes from both branches. */
        KEEP_BOTH,
        /** Take the base version as-is. */
        PREFER_BASE,
        /** Take branch 1's version. */
        PREFER_BRANCH1,
        /** Take branch 2's version. */
        PREFER_BRANCH2,
        /** Deterministically combine compatible changes from both sides. */
        MERGE_SAFE,
        /** A human must resolve this. */
        MANUAL,
        /** The user rejected the offered resolution. */
        REJECTED,
        /** Resolved by replaying a previously recorded sticky decision. */
        STICKY_REPLAY
    }

    /**
     * The classification callers act on.
     */
    public enum ResolutionKind {
        AUTO,
        REVIEW,
        MANUAL,
        DEFERRED
    }

    private final String filePath;
    private final ConflictType type;
    private final String baseCode;
    private final String branch1Code;
    private final String branch2Code;
    private final String resolvedCode;
    private final ResolutionStrategy resolutionStrategy;
    private final ResolutionKind kind;
    private final String explanation;
    private final List<FixPath> alternativePaths;
    private final boolean sticky;
    private final Instant resolvedAt;
    private final String conflictId;
    private final String branchName;
    private final Region region;
    private final Verification verification;

    /**
     * Advisory notes about <em>how</em> the decision was reached, as opposed to what
     * was decided.
     *
     * <p>The distinction is what makes them worth carrying separately: the explanation
     * states the decision and its justification, while a warning says something about
     * the resolver's own basis — that it ran without a type context, that a declared
     * type could not be resolved against the supplied classpath, or that it answered
     * from a built-in table rather than from evidence. A reviewer reading only the
     * explanation would see a confident sentence and no hint that the reasoning behind
     * it was weaker than usual, which is exactly the state a warning exists to expose.
     */
    private final List<String> warnings;

    /**
     * The strongest evidence this particular resolution rests on.
     *
     * <p>Distinct from {@link #warnings}, which are prose about what was <em>missing</em>: this is
     * the position on an ordered scale ({@link AnalysisLevel}) that lets two resolutions claiming one
     * block be compared. It records what was actually used, not what the resolver could have used —
     * {@link TypeChangeConflictResolver} is at {@link AnalysisLevel#PLATFORM_TYPES} without a
     * classpath and at {@link AnalysisLevel#PROJECT_TYPES} with one, and it is the weaker run whose
     * answer must not outrank an objection.
     *
     * <p>Defaults to {@link AnalysisLevel#TEXT_LOCAL}: the weakest level is the only honest default,
     * because a resolution that never says which evidence it read has not claimed any.
     */
    private final AnalysisLevel analysisLevel;

    /**
     * The whitespace policy this resolution's comparison ran under (unified plan step 4.10).
     *
     * <p>A resolution whose basis a reviewer cannot see is the failure mode {@link #getWarnings() warnings}
     * and {@link #getAnalysisLevel() the analysis level} already exist to prevent, and this is the third
     * facet of the same thing: <b>the same decision reached under a different policy is a different
     * decision</b>. "Both sides changed this line" is true under {@code DEFAULT} and false under
     * {@code IGNORE_WHITESPACES}, so a recorded decision replayed under another policy would be answering
     * a question nobody asked — which is why this is recorded rather than assumed.
     *
     * <p>Defaults to {@link ComparisonPolicy#TRIM_WHITESPACES}, the policy the module's detection used
     * before the policy became a choice, so a resolution built by a caller that does not set it behaves
     * exactly as it did.
     */
    private final ComparisonPolicy whitespacePolicy;

    private ConflictResolution(Builder builder) {
        this.filePath = builder.filePath == null ? "<unknown>" : builder.filePath;
        this.type = Objects.requireNonNull(builder.type, "type");
        this.baseCode = nullToEmpty(builder.baseCode);
        this.branch1Code = nullToEmpty(builder.branch1Code);
        this.branch2Code = nullToEmpty(builder.branch2Code);
        this.resolvedCode = nullToEmpty(builder.resolvedCode);
        this.resolutionStrategy = Objects.requireNonNull(builder.resolutionStrategy, "resolutionStrategy");
        this.kind = builder.kind == null
            ? defaultKind(builder.resolutionStrategy)
            : builder.kind;
        this.explanation = nullToEmpty(builder.explanation);
        this.alternativePaths = Collections.unmodifiableList(new ArrayList<>(builder.alternativePaths));
        this.sticky = builder.sticky;
        this.resolvedAt = builder.resolvedAt == null ? Instant.now() : builder.resolvedAt;
        this.conflictId = builder.conflictId == null || builder.conflictId.isBlank()
            ? UUID.randomUUID().toString()
            : builder.conflictId;
        this.branchName = builder.branchName == null ? "unknown" : builder.branchName;
        this.region = builder.region == null ? Region.unknown() : builder.region;
        this.verification = builder.verification == null ? Verification.NOT_RUN : builder.verification;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(builder.warnings));
        this.analysisLevel = builder.analysisLevel == null
            ? AnalysisLevel.TEXT_LOCAL
            : builder.analysisLevel;
        this.whitespacePolicy = builder.whitespacePolicy == null
            ? ComparisonPolicy.TRIM_WHITESPACES
            : builder.whitespacePolicy;
    }

    /**
     * The strongest evidence behind this resolution; never {@code null}.
     *
     * <p>See the field's own note: this is the comparable half of {@link #getWarnings()}, and it is
     * what decides which claim survives when several conflicts claim one block.
     */
    public AnalysisLevel getAnalysisLevel() {
        return analysisLevel;
    }

    /**
     * The whitespace policy this resolution's comparison ran under; never {@code null}.
     *
     * <p>See the field's own note: the same decision under a different policy is a different decision.
     */
    public ComparisonPolicy getWhitespacePolicy() {
        return whitespacePolicy;
    }

    /**
     * True when this resolution rests on strictly stronger evidence than {@code other}.
     *
     * <p>Null is never stronger: an absent resolution cannot outrank a present one.
     */
    public boolean hasStrongerAnalysisThan(ConflictResolution other) {
        return other != null && analysisLevel.isStrongerThan(other.analysisLevel);
    }

    /**
     * Advisory notes about how this decision was reached; empty when there are none.
     *
     * <p>Not part of the explanation: these describe the resolver's basis, not the
     * decision — see the class's own note on why the two are kept apart.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /** Whether this resolution was reached with a caveat worth showing a reviewer. */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * What a {@link ResolutionVerifier} concluded about this resolution.
     */
    public enum Verification {
        /** No verifier was configured. */
        NOT_RUN,
        /** The resolved code verified cleanly. */
        PASSED,
        /** Verification failed and the resolution was downgraded to review. */
        FAILED,
        /** A verifier was configured but could not run for this input. */
        SKIPPED
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder pre-filled with the contents of an existing resolution.
     */
    public static Builder copyOf(ConflictResolution source) {
        return new Builder(source);
    }

    /**
     * A resolution that can be applied without asking.
     */
    public static Builder auto(Conflict conflict, ResolutionStrategy strategy) {
        return builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .baseCode(conflict.getBaseCode())
            .branch1Code(conflict.getBranch1Code())
            .branch2Code(conflict.getBranch2Code())
            .region(conflict.getRegion())
            .resolutionStrategy(strategy)
            .kind(ResolutionKind.AUTO);
    }

    /**
     * A resolution that needs a human decision, described by fix paths.
     */
    public static Builder manual(Conflict conflict) {
        return builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .baseCode(conflict.getBaseCode())
            .branch1Code(conflict.getBranch1Code())
            .branch2Code(conflict.getBranch2Code())
            .region(conflict.getRegion())
            .resolutionStrategy(ResolutionStrategy.MANUAL)
            .kind(ResolutionKind.MANUAL)
            .resolvedCode(MANUAL_MARKER);
    }

    /**
     * Placeholder emitted when no automatic resolution is possible.
     */
    public static final String MANUAL_MARKER = "<<< MERGE-JAVA: MANUAL RESOLUTION REQUIRED >>>";

    private static ResolutionKind defaultKind(ResolutionStrategy strategy) {
        return switch (strategy) {
            case MANUAL, REJECTED -> ResolutionKind.MANUAL;
            case STICKY_REPLAY -> ResolutionKind.DEFERRED;
            case KEEP_BOTH, PREFER_BASE, MERGE_SAFE, PREFER_BRANCH1, PREFER_BRANCH2 -> ResolutionKind.AUTO;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public String getFilePath() {
        return filePath;
    }

    public ConflictType getType() {
        return type;
    }

    public String getBaseCode() {
        return baseCode;
    }

    public String getBranch1Code() {
        return branch1Code;
    }

    public String getBranch2Code() {
        return branch2Code;
    }

    public String getResolvedCode() {
        return resolvedCode;
    }

    public ResolutionStrategy getResolutionStrategy() {
        return resolutionStrategy;
    }

    public ResolutionKind getKind() {
        return kind;
    }

    /**
     * A human-readable statement of what was done and why.
     */
    public String getExplanation() {
        return explanation;
    }

    public List<FixPath> getAlternativePaths() {
        return alternativePaths;
    }

    public boolean isSticky() {
        return sticky;
    }

    /**
     * True when this resolution may be recorded and replayed automatically the
     * next time the same conflict signature appears in this branch.
     *
     * <p>{@code MANUAL} is excluded because a one-off hand resolution is not a
     * policy. {@code DEFERRED} is included so that a decision which has already
     * been replayed once stays recorded for the update after that; without it a
     * remembered choice would be forgotten on first reuse.
     */
    public boolean isReplayable() {
        if (!sticky) {
            return false;
        }
        return kind == ResolutionKind.AUTO
            || kind == ResolutionKind.REVIEW
            || kind == ResolutionKind.DEFERRED;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getConflictId() {
        return conflictId;
    }

    public String getBranchName() {
        return branchName;
    }

    /**
     * The lines of the base version this resolution covers.
     */
    public Region getRegion() {
        return region;
    }

    /**
     * What verification concluded about this resolution.
     */
    public Verification getVerification() {
        return verification;
    }

    /**
     * True when an automatic resolution was independently verified. Only such a
     * resolution may be applied without any further check.
     */
    public boolean isVerifiedAuto() {
        return kind == ResolutionKind.AUTO && verification == Verification.PASSED;
    }

    /**
     * True when the resolver could not decide and a human must act.
     */
    public boolean requiresHumanDecision() {
        return kind == ResolutionKind.MANUAL;
    }

    /**
     * Return a copy of this resolution bound to a different file path.
     */
    public ConflictResolution withFilePath(String newFilePath) {
        return new Builder(this).filePath(newFilePath).build();
    }

    /**
     * Return a copy of this resolution bound to a region.
     */
    public ConflictResolution withRegion(Region newRegion) {
        return new Builder(this).region(newRegion).build();
    }
    @Override
    public String toString() {
        return "ConflictResolution{" + type + " @ " + filePath
            + ", strategy=" + resolutionStrategy
            + ", kind=" + kind
            + ", analysis=" + analysisLevel
            + ", sticky=" + sticky
            + '}';
    }

    /**
     * Builder for {@link ConflictResolution}.
     */
    public static final class Builder {
        private String filePath;
        private ConflictType type;
        private String baseCode = "";
        private String branch1Code = "";
        private String branch2Code = "";
        private String resolvedCode = "";
        private ResolutionStrategy resolutionStrategy = ResolutionStrategy.MANUAL;
        private ResolutionKind kind;
        private String explanation = "";
        private final List<FixPath> alternativePaths = new ArrayList<>();
        private boolean sticky;
        private Instant resolvedAt;
        private String conflictId;
        private String branchName;
        private Region region;
        private Verification verification;
        private AnalysisLevel analysisLevel;
        private ComparisonPolicy whitespacePolicy;
        private final List<String> warnings = new ArrayList<>();

        Builder() {
        }

        /**
         * Copy constructor used by {@link ConflictResolution#withFilePath(String)}
         * and by callers that need to derive a variant of an existing resolution.
         */
        public Builder(ConflictResolution source) {
            this.filePath = source.filePath;
            this.type = source.type;
            this.baseCode = source.baseCode;
            this.branch1Code = source.branch1Code;
            this.branch2Code = source.branch2Code;
            this.resolvedCode = source.resolvedCode;
            this.resolutionStrategy = source.resolutionStrategy;
            this.kind = source.kind;
            this.explanation = source.explanation;
            this.alternativePaths.addAll(source.alternativePaths);
            this.sticky = source.sticky;
            this.resolvedAt = source.resolvedAt;
            this.conflictId = source.conflictId;
            this.branchName = source.branchName;
            this.region = source.region;
            this.verification = source.verification;
            this.analysisLevel = source.analysisLevel;
            this.whitespacePolicy = source.whitespacePolicy;
            this.warnings.addAll(source.warnings);
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder type(ConflictType type) {
            this.type = type;
            return this;
        }

        public Builder baseCode(String baseCode) {
            this.baseCode = baseCode;
            return this;
        }

        public Builder branch1Code(String branch1Code) {
            this.branch1Code = branch1Code;
            return this;
        }

        public Builder branch2Code(String branch2Code) {
            this.branch2Code = branch2Code;
            return this;
        }

        public Builder resolvedCode(String resolvedCode) {
            this.resolvedCode = resolvedCode;
            return this;
        }

        public Builder resolutionStrategy(ResolutionStrategy resolutionStrategy) {
            this.resolutionStrategy = resolutionStrategy;
            return this;
        }

        public Builder kind(ResolutionKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder explanation(String explanation) {
            this.explanation = explanation;
            return this;
        }

        public Builder alternativePaths(List<FixPath> alternativePaths) {
            this.alternativePaths.clear();
            if (alternativePaths != null) {
                this.alternativePaths.addAll(alternativePaths);
            }
            return this;
        }

        public Builder addAlternativePath(FixPath fixPath) {
            if (fixPath != null) {
                this.alternativePaths.add(fixPath);
            }
            return this;
        }

        public Builder addAlternativePaths(List<FixPath> fixPaths) {
            if (fixPaths != null) {
                for (FixPath fixPath : fixPaths) {
                    if (fixPath != null) {
                        this.alternativePaths.add(fixPath);
                    }
                }
            }
            return this;
        }

        public Builder sticky(boolean sticky) {
            this.sticky = sticky;
            return this;
        }

        public Builder resolvedAt(Instant resolvedAt) {
            this.resolvedAt = resolvedAt;
            return this;
        }

        public Builder conflictId(String conflictId) {
            this.conflictId = conflictId;
            return this;
        }

        public Builder branchName(String branchName) {
            this.branchName = branchName;
            return this;
        }

        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        public Builder verification(Verification verification) {
            this.verification = verification;
            return this;
        }

        /**
         * Record the strongest evidence this resolution actually rests on.
         *
         * <p>A resolver normally leaves this unset and inherits
         * {@link ConflictResolver#maxAnalysisLevel()} — the strongest evidence it can bring to bear
         * for the one type it owns — and <em>lowers</em> it on the paths where it had to answer from
         * less, which is the honest direction: a resolution may never claim more than its resolver
         * can reach.
         */
        public Builder analysisLevel(AnalysisLevel analysisLevel) {
            this.analysisLevel = analysisLevel;
            return this;
        }

        /**
         * Record the whitespace policy this resolution's comparison ran under.
         *
         * <p>A resolver that does not set it inherits {@link ComparisonPolicy#TRIM_WHITESPACES}, which is
         * what this module used before the policy was a choice — so this is a widening with no behaviour
         * change for a caller that never asks.
         */
        public Builder whitespacePolicy(ComparisonPolicy whitespacePolicy) {
            this.whitespacePolicy = whitespacePolicy;
            return this;
        }

        /**
         * Add an advisory note about how the decision was reached.
         *
         * <p>Additive rather than a setter: a resolution can carry more than one
         * caveat (a degraded comparison <em>and</em> an unresolvable type, for
         * instance), and a setter would silently drop the first.
         */
        public Builder warning(String warning) {
            if (warning != null && !warning.isBlank()) {
                this.warnings.add(warning);
            }
            return this;
        }

        public Builder warnings(List<String> warnings) {
            if (warnings != null) {
                warnings.stream().filter(w -> w != null && !w.isBlank()).forEach(this.warnings::add);
            }
            return this;
        }

        public ConflictResolution build() {
            return new ConflictResolution(this);
        }
    }
}
