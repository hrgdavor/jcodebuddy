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
     * The sides differ and no detector claimed the block — the case that used to carry a {@code null} type.
     *
     * <p>Naming it is the maintainer's decision of 2026-10-09 (plan step 4.19), and the reason is what a null could not
     * do: it meant "unknown", "not asked" and "no tier reached it" at once, so nothing could select these blocks and no
     * report could print what they were. The block is still handed to a human ({@link Handling#MANUAL}) — naming it
     * promotes it into the classification, it does not make it resolvable — and the name says which tier was reached
     * last and declined, so a reader learns what is left rather than being told that nothing happened.</p>
     */
    UNCLASSIFIED_TEXT(Handling.MANUAL),

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
    API_INCOMPATIBILITY(Handling.MANUAL),

    /**
     * Both branches added a <em>distinct</em> member in the same place: a member the base did not
     * declare, which the other branch's addition does not collide with. Additive, so the union of the
     * two sides is the answer.
     *
     * <p>Distinct from {@link #OVERLOAD_ADD}, which is two branches adding a method with the
     * <em>same</em> name — there the parameter lists are the question and identical ones collide.
     * Here nothing collides, so the union is safe, and the only thing that could go wrong is
     * resurrecting a member one branch deliberately removed. That is why the detector requires a base
     * side: without one, "both branches added it" cannot be told from "one branch added it and the
     * other deleted it".
     *
     * <p>The members recognised today are those
     * {@link DeclarationScanner#memberSignatures(String)} sees — methods, by name and parameter list.
     * Two branches adding distinct <em>fields</em> in the same place is still the structural
     * residual's business, and stays a human's.
     */
    MEMBER_ADD(Handling.AUTO);

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
