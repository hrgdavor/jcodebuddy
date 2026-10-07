// {@link com.codebuddy.merge.ConflictState} The state one detected conflict holds in a resolution pass.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

/**
 * The state a {@link Conflict} holds while a merge block is being resolved, as an ordered pass over
 * analysis tiers.
 *
 * <h2>Why a state, rather than a resolution that lost</h2>
 *
 * <p>Several conflicts can claim one block, and the module used to treat every claim alike and then
 * arbitrate: the strongest answer won and the others were reported as
 * {@link MergeFileTool outranked}. An outranked claim still <b>exists</b> — the lower tier was asked
 * about it, spent its time on it, and put something into the arbitration and into the report. The
 * requirement this state exists for is the stronger one: a conflict a higher tier has settled
 * <em>reliably</em> must be <b>removed</b>, so the lower tier is never asked and no objection to it is
 * ever constructed.
 *
 * <p>See
 * <a href="../../../../../../docs/HIERARCHICAL_RESOLUTION.md">HIERARCHICAL_RESOLUTION.md</a> § 3.1 and
 * <a href="../../../../../../../../doc-hipster-entity/architecture/decisions/DEC-046.md">DEC-046</a>
 * clause 9 (and its amendment), with the plan steps in {@code plans/unified-plan.md} § 4C.
 */
public enum ConflictState {

    /**
     * Nothing has settled this conflict. It is offered to each analysis tier in turn, strongest first,
     * and every tier is entitled to answer it.
     */
    OPEN,

    /**
     * A reliable resolution spans the <b>whole</b> of this conflict's region: the answer is applied,
     * the conflict is marked resolved, and it is <b>removed from the working set</b> so no lower tier
     * is offered it.
     *
     * <p>This is a statement about <em>who was not asked</em>, and nothing more: the applied answer
     * still passes {@link ResolutionVerifier}, and a resolution that fails verification is downgraded
     * exactly as it is without this state.
     */
    RESOLVED,

    /**
     * A reliable resolution spans <b>part</b> of this conflict's region. That part is applied, and
     * only the <b>open remainder</b> is offered to the tiers below.
     *
     * <p>Today this shape becomes {@link MergeFileTool.Outcome#LEFT_PARTIAL_RESOLUTION} and the whole
     * block — including the part the tool got right — goes to a human.
     */
    PARTIAL
}
