// {@link com.codebuddy.merge.ConflictResolution} Represents a resolved merge conflict with metadata.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

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

        public ConflictResolution build() {
            return new ConflictResolution(this);
        }
    }
}
