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
        this.type = Objects.requireNonNull(type, "type");
        this.filePath = filePath == null ? "<unknown>" : filePath;
        this.description = description == null ? "" : description;
        this.baseCode = baseCode == null ? "" : baseCode;
        this.branch1Code = branch1Code == null ? "" : branch1Code;
        this.branch2Code = branch2Code == null ? "" : branch2Code;
        this.region = region == null ? Region.unknown() : region;
        this.typeContext = typeContext;
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
     * Return a copy of this conflict bound to a concrete file path.
     */
    public Conflict withFilePath(String newFilePath) {
        return new Conflict(type, newFilePath, description, baseCode, branch1Code, branch2Code,
            region, typeContext);
    }

    /**
     * Return a copy of this conflict bound to a region.
     */
    public Conflict withRegion(Region newRegion) {
        return new Conflict(type, filePath, description, baseCode, branch1Code, branch2Code,
            newRegion, typeContext);
    }

    /**
     * Return a copy of this conflict bound to a type context.
     */
    public Conflict withTypeContext(TypeContext newTypeContext) {
        return new Conflict(type, filePath, description, baseCode, branch1Code, branch2Code,
            region, newTypeContext);
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
