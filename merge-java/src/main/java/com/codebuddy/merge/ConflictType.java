// {@link com.codebuddy.merge.ConflictType} Enumerates conflict types and how each is handled.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

/**
 * The kinds of merge conflict this module understands.
 *
 * Each constant declares how much trust an automatic answer deserves. That
 * classification drives {@link ConflictResolution.ResolutionKind} and lets the
 * orchestrator report honestly how much of a merge it handled versus how much a
 * reviewer still has to look at.
 */
public enum ConflictType {

    /**
     * Both branches add imports. Additive, so the union is correct.
     * Auto-resolvable.
     */
    IMPORT_ADD(Handling.AUTO),

    /**
     * Both branches add comments or documentation. Additive.
     * Auto-resolvable.
     */
    COMMENT_ADD(Handling.AUTO),

    /**
     * Both branches add constants or enum members. Additive while the names are
     * distinct; a name collision escalates to review.
     */
    CONSTANT_ADD(Handling.AUTO),

    /**
     * Both branches change the body of the same method. Only deterministic,
     * non-overlapping edits can be combined automatically.
     */
    METHOD_BODY_CHANGE(Handling.REVIEW),

    /**
     * The same declaration was renamed differently on each branch. A choice,
     * not a fact, so it needs a decision - and is worth remembering.
     */
    VARIABLE_RENAME(Handling.STICKY),

    /**
     * The declared type of the same declaration differs. Widening is safe;
     * anything else needs review.
     */
    TYPE_CHANGE(Handling.REVIEW),

    /**
     * The class was moved to a different package on each branch. A choice that
     * must stay consistent, so it is worth remembering.
     */
    PACKAGE_CHANGE(Handling.STICKY),

    /**
     * Both branches add a method with the same name. Distinct parameter lists
     * are overloads and coexist; identical ones collide.
     */
    OVERLOAD_ADD(Handling.AUTO),

    /**
     * Structural change such as adding or removing a whole member in
     * incompatible ways. Always reviewed by a human.
     */
    STRUCTURAL_CHANGE(Handling.MANUAL),

    /**
     * A change to a public contract that may break callers. Always reviewed by
     * a human.
     */
    API_INCOMPATIBILITY(Handling.MANUAL);

    /**
     * How much automation a conflict type permits.
     */
    public enum Handling {
        /** Safe to apply without asking. */
        AUTO,
        /** Can be resolved, but a reviewer should confirm. */
        REVIEW,
        /** Resolvable, but the answer is a preference worth recording. */
        STICKY,
        /** Must be handed to a human. */
        MANUAL
    }

    private final Handling handling;

    ConflictType(Handling handling) {
        this.handling = handling;
    }

    /**
     * The automation level this conflict type permits.
     */
    public Handling handling() {
        return handling;
    }

    /**
     * True when a resolution of this type may be applied without asking.
     */
    public boolean isAutoResolvable() {
        return handling == Handling.AUTO;
    }

    /**
     * True when the answer is a preference that should be recorded and replayed
     * on later updates of the same branch.
     */
    public boolean isStickyByDefault() {
        return handling == Handling.STICKY;
    }

    /**
     * True when a human must make the call.
     */
    public boolean requiresHumanDecision() {
        return handling == Handling.MANUAL;
    }
}
