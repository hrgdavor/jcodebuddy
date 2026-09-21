// {@link com.codebuddy.merge.FixPath} Represents a suggested fix path for an ambiguous conflict.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a suggested fix path for an ambiguous merge conflict.
 *
 * Fix paths reduce reviewer burden by turning "there is a conflict" into
 * "here are the viable options, here is what each one costs, and here is the
 * one we recommend". A resolver produces fix paths whenever it cannot pick a
 * winner on its own.
 */
public final class FixPath {

    private final ConflictType conflictType;
    private final String description;
    private final List<String> options;
    private final ConflictResolution suggested;
    private final String justification;
    private final String impact;
    private final String recommended;

    private FixPath(Builder builder) {
        this.conflictType = builder.conflictType;
        this.description = builder.description;
        this.options = Collections.unmodifiableList(new ArrayList<>(builder.options));
        this.suggested = builder.suggested;
        this.justification = builder.justification;
        this.impact = builder.impact;
        this.recommended = builder.recommended;
    }

    /**
     * Positional constructor kept for compatibility with simple call sites.
     */
    public FixPath(ConflictType conflictType, String description, List<String> options,
                   ConflictResolution suggested, String justification, String impact) {
        this(builder()
            .conflictType(conflictType)
            .description(description)
            .options(options)
            .suggested(suggested)
            .justification(justification)
            .impact(impact));
    }

    public static Builder builder() {
        return new Builder();
    }

    public ConflictType getConflictType() {
        return conflictType;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getOptions() {
        return options;
    }

    public ConflictResolution getSuggested() {
        return suggested;
    }

    public String getJustification() {
        return justification;
    }

    public String getImpact() {
        return impact;
    }

    /**
     * The option this fix path recommends, or {@code null} when the resolver
     * deliberately leaves the choice open.
     */
    public String getRecommended() {
        return recommended;
    }

    /**
     * True when this fix path carries an explicit recommendation.
     */
    public boolean hasRecommendation() {
        return recommended != null && !recommended.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FixPath other)) {
            return false;
        }
        return conflictType == other.conflictType
            && Objects.equals(description, other.description)
            && options.equals(other.options)
            && Objects.equals(justification, other.justification)
            && Objects.equals(impact, other.impact)
            && Objects.equals(recommended, other.recommended);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conflictType, description, options, justification, impact, recommended);
    }

    @Override
    public String toString() {
        return "FixPath{" + conflictType + ": " + description
            + ", options=" + options
            + (recommended == null ? "" : ", recommended='" + recommended + '\'')
            + '}';
    }

    /**
     * Builder for {@link FixPath}. Every resolver uses this so that new
     * conflict types automatically produce uniformly shaped fix paths.
     */
    public static final class Builder {
        private ConflictType conflictType;
        private String description = "";
        private final List<String> options = new ArrayList<>();
        private ConflictResolution suggested;
        private String justification = "";
        private String impact = "";
        private String recommended;

        public Builder conflictType(ConflictType conflictType) {
            this.conflictType = conflictType;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder options(List<String> options) {
            this.options.clear();
            if (options != null) {
                this.options.addAll(options);
            }
            return this;
        }

        public Builder options(String... options) {
            return options(List.of(options));
        }

        public Builder suggested(ConflictResolution suggested) {
            this.suggested = suggested;
            return this;
        }

        public Builder justification(String justification) {
            this.justification = justification;
            return this;
        }

        public Builder impact(String impact) {
            this.impact = impact;
            return this;
        }

        public Builder recommended(String recommended) {
            this.recommended = recommended;
            return this;
        }

        public FixPath build() {
            return new FixPath(this);
        }
    }
}
