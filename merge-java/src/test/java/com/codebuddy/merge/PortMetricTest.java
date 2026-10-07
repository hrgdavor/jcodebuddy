// {@link com.codebuddy.merge.PortMetricTest} The port's measurable target (plan 4.13, JETBRAINS_PORT.md 10.3).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The port's measurable target</b> (unified plan step 4.13, {@code JETBRAINS_PORT.md} § 10.3), and the first
 * § 10.2 beyond-parity row measured rather than asserted.
 *
 * <h2>Why a metric rather than another assertion</h2>
 *
 * <p>§ 10.3 says a claim that the port made the tool better is an assertion until these numbers exist, and a claim
 * of <em>parity</em> without the gate is the assertion the whole document exists to prevent. The floor is the parity
 * gate's; this is the other half: <b>conflicts escalated to a human</b> and <b>blocks left for a human to decide</b>,
 * against <b>blocks where the tool had an answer to offer</b>.
 *
 * <h2>The corpus, and being honest about what it is</h2>
 *
 * <p>The census runs over {@link ConflictFixtures#sample} — <b>one sample per domain conflict type</b>, which is
 * this module's own corpus and is reproducible from the sources. It is a test corpus, not years of editor use, and
 * § 10.2 says so: <em>"upstream has years of real-editor use behind it and we have a test corpus. Where a
 * measurement disagrees with this table, the measurement wins, and the row is removed."</em> The numbers are printed
 * on every run so they can be compared across commits rather than remembered.
 *
 * <h2>What the numbers may not do</h2>
 *
 * <p>Rise for a bad reason. Every escalation the census counts is a block where the tool declined, so a fall is only
 * good news if nothing incorrect was applied — which is why the census also asserts that <b>no resolution is
 * {@code AUTO} without a verification verdict</b>: an "improvement" produced by applying something unverified is the
 * invisible regression {@code DESIGN_NEVER_AUTO_RESOLVED.md} exists to prevent, and it would show up here as a
 * better number.
 */
class PortMetricTest {

    /** One sample per domain conflict type, so the census covers the taxonomy rather than a chosen subset. */
    private static List<Conflict> corpus() {
        List<Conflict> samples = new ArrayList<>();
        for (ConflictType type : ConflictType.values()) {
            try {
                samples.add(ConflictFixtures.sample(type));
            } catch (RuntimeException noSample) {
                // A type without a sample is reported by its absence rather than invented here; the count below
                // makes the gap visible instead of silent.
            }
        }
        return samples;
    }

    @Test
    @DisplayName("the census: what the resolver decided for every sample, and how much was left to a person")
    void theCensus() {
        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName("census")
            .setInMemoryOnly(true)
            .setResolvers(ConflictResolvers.defaultResolvers())
            .setTypeContext(TestTypeContexts.jdk())
            .build();

        List<Conflict> samples = corpus();
        Map<ConflictResolution.ResolutionKind, Integer> byKind =
            new EnumMap<>(ConflictResolution.ResolutionKind.class);
        int verifiedAuto = 0;
        int unverifiedAuto = 0;
        int offered = 0;
        List<String> unverified = new ArrayList<>();

        for (Conflict conflict : samples) {
            ConflictResolution resolution = resolver.resolve(conflict);
            byKind.merge(resolution.getKind(), 1, Integer::sum);
            if (resolution.getKind() == ConflictResolution.ResolutionKind.SUGGESTION) {
                offered++;
            }
            if (resolution.getKind() == ConflictResolution.ResolutionKind.AUTO
                || resolution.getKind() == ConflictResolution.ResolutionKind.DEFERRED) {
                if (resolution.isVerifiedAuto()) {
                    verifiedAuto++;
                } else {
                    unverifiedAuto++;
                    unverified.add(conflict.getType() + " -> " + resolution.getKind()
                        + " without verification (" + resolution.getExplanation() + ")");
                }
            }
        }

        int escalated = count(byKind, ConflictResolution.ResolutionKind.MANUAL)
            + count(byKind, ConflictResolution.ResolutionKind.REVIEW)
            + count(byKind, ConflictResolution.ResolutionKind.DEFERRED);
        int manual = count(byKind, ConflictResolution.ResolutionKind.MANUAL);

        System.out.println("CENSUS-METRIC: " + samples.size() + " samples over "
            + ConflictType.values().length + " declared types"
            + "\n  escalated to a human: " + escalated + " (" + manual + " manual, "
            + count(byKind, ConflictResolution.ResolutionKind.REVIEW) + " review, "
            + count(byKind, ConflictResolution.ResolutionKind.DEFERRED) + " deferred)"
            + "\n  blocks left for a human to decide (LEFT_MANUAL): " + manual
            + "\n  blocks where the tool had an answer to offer: " + offered
            + "\n  applied or recorded, verified: " + verifiedAuto
            + "\n  by kind: " + byKind);

        assertFalse(samples.isEmpty(), "the corpus must not be empty, or the numbers mean nothing");
        assertTrue(unverified.isEmpty(),
            "the census must never improve by applying something unverified:\n  - "
                + String.join("\n  - ", unverified));
        // Every applied answer is verified: the DESIGN_NEVER_AUTO_RESOLVED.md half of the number. A census that
        // "improved" by applying something unverified would show fewer escalations and be worth nothing.
        assertEquals(count(byKind, ConflictResolution.ResolutionKind.AUTO), verifiedAuto,
            "every automatic answer must carry a verification verdict");
        assertEquals(samples.size(),
            escalated + offered + count(byKind, ConflictResolution.ResolutionKind.AUTO),
            "every sample is either escalated, offered, or answered, and one counted in none of those would be a"
                + " case the census cannot see: " + byKind);
    }

    private static int count(Map<ConflictResolution.ResolutionKind, Integer> byKind,
                             ConflictResolution.ResolutionKind kind) {
        return byKind.getOrDefault(kind, 0);
    }

    @Test
    @DisplayName("beyond parity, row 1: the structural answer is preferred, and the text-only answer is recorded beside it")
    void theStructuralAnswerBeatsTheTextOnlyOne() {
        // JETBRAINS_PORT.md § 10.2's first row, which the document names as one of the two worth arguing about: two
        // branches appended a DISTINCT method at the same point. Upstream's line pass calls it CONFLICT and recovers
        // only if its word pass can compose the two insertions; we recognise the declared members and answer at
        // STRUCTURE. The row's own measurement is "vector resolved AND analysisLevel == STRUCTURE reported; the same
        // vector's text-only answer is recorded beside it" - so both answers are produced here, from the same input.
        Conflict conflict = ConflictFixtures.sample(ConflictType.MEMBER_ADD);

        ConflictResolution structural = new MemberAddConflictResolver().resolve(conflict);
        assertNotNull(structural, "the structural resolver must answer for its own type");
        assertTrue(structural.getAnalysisLevel() != null
                && structural.getAnalysisLevel().isAtLeast(AnalysisLevel.STRUCTURE),
            "the answer is at STRUCTURE or above, which is the whole claim: " + structural.getAnalysisLevel());
        assertFalse(structural.getKind() == ConflictResolution.ResolutionKind.MANUAL,
            "and it is not left to a person: " + structural.getKind() + " - " + structural.getExplanation());

        // The same vector's TEXT-ONLY answer, from the ported pass, at the level a text comparison can reach. It is
        // allowed to refuse (two different insertions at one point is R6) and it is allowed to compose; what it may
        // NOT do is claim more than TEXT_LOCAL, because it read nothing but the block's lines.
        Optional<Suggestion> textOnly = GreedyMergeSuggestion.withWhitespaceRetry(
            conflict.getBaseCode(), conflict.getBranch1Code(), conflict.getBranch2Code(),
            ComparisonPolicy.DEFAULT);

        System.out.println("BEYOND-PARITY row 1 (distinct methods added at one point):"
            + "\n  domain type:      " + conflict.getType()
            + "\n  structural answer: " + structural.getKind() + " at "
            + (structural.getAnalysisLevel() == null ? "unknown level" : structural.getAnalysisLevel())
            + "\n  text-only answer:  " + (textOnly.isPresent()
                ? "composed at " + textOnly.get().analysisLevel() + " (confidence "
                    + textOnly.get().confidence() + ")"
                : "refused (two differing insertions at one point)"));

        if (textOnly.isPresent()) {
            assertEquals(AnalysisLevel.TEXT_LOCAL, textOnly.get().analysisLevel(),
                "the text-only answer must not claim to have read more than the block's lines");
            assertTrue(structural.getAnalysisLevel().isStrongerThan(textOnly.get().analysisLevel()),
                "and the structural claim read more, which is why arbitration prefers it: "
                    + structural.getAnalysisLevel() + " vs " + textOnly.get().analysisLevel());
        }
    }
}
