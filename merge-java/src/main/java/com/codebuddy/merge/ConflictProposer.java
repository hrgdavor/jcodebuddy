package com.codebuddy.merge;

import java.util.Optional;

/**
 * Somebody — a model, a script, a person with a hunch — offering an answer to a conflict this module refuses to
 * decide (plan step 4.4).
 *
 * <h3>The boundary this exists behind</h3>
 * <p>Structural, API and overlapping-body conflicts are <strong>substitutive</strong>: resolving one means discarding
 * one author's intention, or inventing a third that neither wrote. The module will not do that automatically, and
 * neither will this — see {@code DESIGN_NEVER_AUTO_RESOLVED.md}. A proposal therefore arrives as <strong>one more fix
 * path</strong> on an escalated resolution, never as the resolution, so it reaches a file only through the same human
 * decision every other fix path needs.</p>
 *
 * <h3>The gate applies to a proposal exactly as it does to an automatic answer</h3>
 * <p>{@link ResolutionVerifier} only verifies automatic resolutions — "review, manual and replayed resolutions are
 * already surfaced to a human, so verifying them would add nothing" — so a proposal is verified <em>as if</em> it were
 * automatic, and the verdict travels with it into the fix path the reviewer reads. A proposal that fails is still
 * shown (a reviewer may want to see what was suggested and why it was refused) but it is labelled as refused, and it
 * is no more applicable than before: nothing becomes a resolution without the human decision that a fix path
 * requires.</p>
 *
 * <h3>Implementations</h3>
 * <p>An implementation that talks to a model endpoint is a deployment concern, not a library one: it needs an
 * endpoint, credentials and a timeout, none of which this repository can decide. What is fixed here is the shape, the
 * gate and the fact that a proposer is <strong>optional</strong> — a resolver without one behaves exactly as it did
 * before.</p>
 */
@FunctionalInterface
public interface ConflictProposer {

    /**
     * Propose an answer for one conflict.
     *
     * @return the proposal, or empty when this proposer has nothing to say about the conflict
     * @throws RuntimeException when the proposer itself fails; the resolver catches it, keeps the escalation and says
     *                          so in a fix path, because an advisor's failure must not change a decision
     */
    Optional<Proposal> propose(Conflict conflict);

    /**
     * What a proposer offers: code, and the reasoning a reviewer needs to judge it.
     *
     * @param resolvedCode the code to use if the proposal is accepted
     * @param explanation  why the proposer believes this is right, in one or two sentences
     */
    record Proposal(String resolvedCode, String explanation) {

        public Proposal {
            resolvedCode = resolvedCode == null ? "" : resolvedCode;
            explanation = explanation == null ? "" : explanation;
        }
    }
}
