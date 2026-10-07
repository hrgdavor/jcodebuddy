// {@link com.codebuddy.merge.Suggestion} An answer the tool worked out and offers, but never applies itself.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * An answer the tool worked out and offers to a person, which the tool <b>never applies on its own</b>
 * (unified plan step 4.14, designed in {@code docs/SUGGESTIONS.md} § 3).
 *
 * <h2>The four properties that define it</h2>
 *
 * <p>Any one of them missing turns this into something else, so all four are part of the type rather than
 * conventions around it:
 *
 * <ol>
 *   <li>it <b>carries code</b> ({@link #code()}) — not a description of code, not an option to consider;</li>
 *   <li>it is <b>not applied</b> by the tool: a resolution carrying a suggestion is
 *       {@link ConflictResolution.ResolutionKind#SUGGESTION}, and {@link MergeFileTool} leaves the markers;</li>
 *   <li>it records <b>why</b> it exists and on what evidence ({@link #explanation()},
 *       {@link #provenance()}, {@link #analysisLevel()}, {@link #warnings()});</li>
 *   <li><b>accepting and refusing are both one action</b> — the page collects either into the same decisions
 *       file the recorded-decision path already replays.</li>
 * </ol>
 *
 * <h2>Why it is standalone rather than a field on a resolution</h2>
 *
 * <p>A producer should not have to invent a {@link ConflictResolution} in order to offer an answer: that is
 * what keeps the channel open to sources that are not resolvers at all — a project convention, a sibling
 * conflict's decision, a model. This value is what they produce, and the resolution only carries it.
 *
 * <h2>The structural half</h2>
 *
 * <p>{@link #code()} is final and this record cannot reach a resolution's {@code resolvedCode} or {@code kind}:
 * there is no method here that writes them. That is the property {@code ConflictProposer} relies on, made
 * general — a suggestion is information, and applying it is a decision somebody else makes.
 *
 * @param code          the resolved text, ready to write
 * @param explanation   why this is believed right, one or two sentences
 * @param provenance    <b>who</b> produced it: a named resolver, a text-comparison pass, a proposer
 * @param analysisLevel the strongest evidence this suggestion actually rests on, not the most it could have
 * @param warnings      what was missing from its basis
 * @param verification  what {@link ResolutionVerifier} concluded; a failure <b>labels</b> a suggestion rather
 *                      than suppressing it, because a reviewer may still want to see what was proposed
 * @param verificationDetail the verifier's own detail when the verdict is not
 *                      {@link ConflictResolution.Verification#PASSED}
 * @param confidence    whether an answer is mechanically forced or a plausible reading
 */
public record Suggestion(String code,
                         String explanation,
                         String provenance,
                         AnalysisLevel analysisLevel,
                         List<String> warnings,
                         ConflictResolution.Verification verification,
                         String verificationDetail,
                         Confidence confidence) {

    /**
     * Whether the inputs mechanically determine this answer, or it is a plausible reading of them.
     *
     * <p>Deliberately two-valued and <b>not</b> a probability: the tool can honestly say whether a proof
     * exists, and it cannot honestly say how likely a guess is. A number here would be read as a confidence
     * score and acted on as one.
     */
    public enum Confidence {

        /** The inputs determine the answer: replaying the same inputs produces the same text. */
        PROVEN,

        /** A plausible reading of the inputs — useful to look at first, and not forced. */
        PLAUSIBLE
    }

    public Suggestion {
        code = code == null ? "" : code;
        explanation = explanation == null ? "" : explanation;
        provenance = provenance == null || provenance.isBlank() ? "unknown" : provenance;
        analysisLevel = analysisLevel == null ? AnalysisLevel.TEXT_LOCAL : analysisLevel;
        warnings = warnings == null ? List.of() : List.copyOf(new ArrayList<>(warnings));
        verification = verification == null ? ConflictResolution.Verification.NOT_RUN : verification;
        verificationDetail = verificationDetail == null ? "" : verificationDetail;
        confidence = confidence == null ? Confidence.PLAUSIBLE : confidence;
    }

    /**
     * A suggestion that no verifier has seen yet.
     */
    public static Suggestion of(String code, String explanation, String provenance,
                                AnalysisLevel level, Confidence confidence) {
        return new Suggestion(code, explanation, provenance, level, List.of(),
            ConflictResolution.Verification.NOT_RUN, "", confidence);
    }

    /** True when the answer carries no code, so there is nothing for a person to accept. */
    public boolean isEmpty() {
        return code.isBlank();
    }

    /** True when a verifier ran and rejected the answer; it is still shown, with this saying so. */
    public boolean hasFailedVerification() {
        return verification == ConflictResolution.Verification.FAILED;
    }

    /**
     * The same suggestion with a verifier's verdict attached.
     *
     * <p>The one mutation a suggestion has, and it exists so that verification can <b>label</b> rather than
     * suppress: the answer a reviewer sees is exactly what was proposed, with the verdict beside it.
     */
    public Suggestion withVerification(ConflictResolution.Verification verdict, String detail) {
        return new Suggestion(code, explanation, provenance, analysisLevel, warnings,
            verdict, detail, confidence);
    }

    /** One line naming who produced this and how far it got, for a report or a page. */
    public String describe() {
        return provenance + " (" + confidence + ", " + analysisLevel + ", " + verification + ")";
    }
}
