// {@link com.codebuddy.merge.Conflict} Represents a detected merge conflict.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.Objects;

/**
 * Represents a detected merge conflict between code versions.
 *
 * A conflict is anchored to the file it was detected in, so that a resolution
 * can be persisted per file in the branch history store, and to the
 * {@link Region} of the base version it covers, so that an orchestrator can tell
 * whether two conflicts can be applied independently.
 */
public final class Conflict {

    private final ConflictType type;
    private final String filePath;
    private final String description;
    private final String baseCode;
    private final String branch1Code;
    private final String branch2Code;
    private final Region region;
    private final TypeContext typeContext;

    /**
     * What kind of change this conflict is, beside what it is about (plan step 4.12).
     *
     * <p>{@link ConflictType#UNKNOWN_UNSET} is not a shape: the default is {@link ConflictShape#UNKNOWN}, which
     * says the shape could not be computed — a merge-style block with no base — rather than that nobody asked.
     */
    private final ConflictShape shape;

    public Conflict(ConflictType type, String filePath, String description,
                    String baseCode, String branch1Code, String branch2Code) {
        this(type, filePath, description, baseCode, branch1Code, branch2Code, Region.unknown(),
            null);
    }

    public Conflict(ConflictType type, String filePath, String description,
                    String baseCode, String branch1Code, String branch2Code, Region region) {
        this(type, filePath, description, baseCode, branch1Code, branch2Code, region, null);
    }

    public Conflict(ConflictType type, String filePath, String description,
                    String baseCode, String branch1Code, String branch2Code, Region region,
                    TypeContext typeContext) {
        this(type, filePath, description, baseCode, branch1Code, branch2Code, region, typeContext,
            ConflictShape.UNKNOWN);
    }

    /**
     * The full constructor, carrying the {@link ConflictShape} as well.
     *
     * <p>The shape is a separate component rather than something derived here because deriving it needs the
     * comparison policy and whether the block has a base at all — facts the detection run has and a single conflict
     * does not.
     *
     * <p><b>A copy helper that dropped it would be a silent loss</b>: {@link MergeFileTool} re-stamps every
     * detected conflict with the block's file region, so a shape set during detection would be erased by the very
     * next step if {@link #withRegion} did not carry it forward.
     */
    public Conflict(ConflictType type, String filePath, String description,
                    String baseCode, String branch1Code, String branch2Code, Region region,
                    TypeContext typeContext, ConflictShape shape) {
        this.type = Objects.requireNonNull(type, "type");
        this.filePath = filePath == null ? "<unknown>" : filePath;
        this.description = description == null ? "" : description;
        this.baseCode = baseCode == null ? "" : baseCode;
        this.branch1Code = branch1Code == null ? "" : branch1Code;
        this.branch2Code = branch2Code == null ? "" : branch2Code;
        this.region = region == null ? Region.unknown() : region;
        this.typeContext = typeContext;
        this.shape = shape == null ? ConflictShape.UNKNOWN : shape;
    }

    /**
     * Convenience constructor for a conflict with a description but unknown file.
     * The file path can be supplied later via {@link #withFilePath(String)}.
     */
    public Conflict(ConflictType type, String description, String baseCode,
                    String branch1Code, String branch2Code) {
        this(type, "<unknown>", description, baseCode, branch1Code, branch2Code);
    }

    public ConflictType getType() {
        return type;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getDescription() {
        return description;
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

    /**
     * The lines of the base version this conflict covers, or
     * {@link Region#unknown()} when it could not be attributed.
     */
    public Region getRegion() {
        return region;
    }

    /**
     * The context a resolver needs to resolve types, or {@code null} when the
     * conflict does not need one.
     *
     * <p>Carried on the conflict rather than passed separately so that
     * {@link ConflictResolver#resolve(Conflict)} keeps its shape: a resolver that
     * needs types simply reads the context, and one that does not never sees it.
     */
    public TypeContext getTypeContext() {
        return typeContext;
    }

    /**
     * What kind of change this conflict is; never {@code null}.
     *
     * <p>Orthogonal to {@link #getType()}: that says what the conflict is <em>about</em> — an import addition, an
     * overload clash — and this says what <em>shape</em> the change has: both sides inserted, one side changed it,
     * both changed it differently. A domain type cannot say the second, and a shape cannot say the first.
     *
     * <p>{@link ConflictShape#UNKNOWN} means it could not be computed — a block with no base to compare against —
     * or that the conflict was built by hand rather than by detection. Both are "not known", and neither is a
     * claim about the change.
     */
    public ConflictShape getShape() {
        return shape;
    }

    /** Return a copy of this conflict carrying a computed shape. */
    public Conflict withShape(ConflictShape newShape) {
        return new Conflict(type, filePath, description, baseCode, branch1Code, branch2Code,
            region, typeContext, newShape);
    }

    /**
     * Return a copy of this conflict bound to a concrete file path.
     */
    public Conflict withFilePath(String newFilePath) {
        return new Conflict(type, newFilePath, description, baseCode, branch1Code, branch2Code,
            region, typeContext, shape);
    }

    /**
     * Return a copy of this conflict bound to a region.
     *
     * <p>Carries the shape forward, which is not incidental: {@link MergeFileTool} re-stamps every detected conflict
     * with its block's file region, so a shape computed during detection would be erased here if this copy dropped
     * it — and the report would say "unknown" for every conflict in a diff3 file.
     */
    public Conflict withRegion(Region newRegion) {
        return new Conflict(type, filePath, description, baseCode, branch1Code, branch2Code,
            newRegion, typeContext, shape);
    }

    /**
     * Return a copy of this conflict bound to a type context.
     */
    public Conflict withTypeContext(TypeContext newTypeContext) {
        return new Conflict(type, filePath, description, baseCode, branch1Code, branch2Code,
            region, newTypeContext, shape);
    }

    /**
     * True when this conflict and the other cover overlapping lines, or when
     * either region is unknown. Treating an unknown region as overlapping is the
     * conservative choice: it prevents two conflicts from being applied
     * independently when their relationship is not actually known.
     */
    public boolean conflictsWith(Conflict other) {
        if (other == null) {
            return false;
        }
        if (!region.isKnown() || !other.region.isKnown()) {
            return true;
        }
        return region.overlaps(other.region);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Conflict other)) {
            return false;
        }
        return type == other.type
            && filePath.equals(other.filePath)
            && description.equals(other.description)
            && baseCode.equals(other.baseCode)
            && branch1Code.equals(other.branch1Code)
            && branch2Code.equals(other.branch2Code)
            && region.equals(other.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, filePath, description, baseCode, branch1Code, branch2Code, region);
    }
    @Override
    public String toString() {
        return "Conflict{" + type + " @ " + filePath + " " + region + ": " + description + '}';
    }
}
