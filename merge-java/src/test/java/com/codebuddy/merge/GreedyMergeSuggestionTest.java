// {@link com.codebuddy.merge.GreedyMergeSuggestionTest} The greedy pass, offered and never applied (plan step 4.17).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The greedy word-level pass as a suggestion producer (unified plan step 4.17, {@code JETBRAINS_PORT.md} § 6.4).
 *
 * <h2>What is asserted, and the two assertions that matter most</h2>
 *
 * <p>The pass's own table — one side inserted, both inserted equal, both inserted differently — and then the two
 * properties that make it a <em>suggestion</em> rather than an answer:
 *
 * <ul>
 *   <li><b>the trade is recorded rather than hidden</b>: a deletion neither branch asked for is applied by the
 *       pass, and that fact is in the suggestion's warnings, because it is what makes the confidence
 *       {@code PLAUSIBLE} instead of {@code PROVEN};</li>
 *   <li><b>the whitespace retry is a second suggestion with its own provenance</b>, so refusing the strict
 *       attempt does not refuse the lenient one. Upstream retries and records nothing, which is the difference
 *       between a suggestion a reviewer can judge and a formatting difference swallowed silently.</li>
 * </ul>
 */
class GreedyMergeSuggestionTest {

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    @Test
    @DisplayName("both sides inserting the same change yields one copy of it, and the shorter when they differ in form")
    void equalInsertionsCollapse() {
        Optional<Suggestion> same = GreedyMergeSuggestion.greedy("a\nc\n", "a\nb\nc\n", "a\nb\nc\n", POLICY);

        assertTrue(same.isPresent());
        assertEquals("a\nb\nc\n", same.get().code());
        assertEquals(1, occurrences(same.get().code(), "b"), same.get().code());

        // Policy-equal but not byte-equal: the shorter is taken, deterministically, which is the rule upstream uses
        // and the reason the result is a composition rather than a choice between two renderings. The policy is what
        // makes them equal — under DEFAULT the indentation is a difference, and the pass refuses it.
        assertTrue(GreedyMergeSuggestion.greedy("a\nc\n", "a\n  b  \nc\n", "a\nb\nc\n", POLICY).isEmpty(),
            "under DEFAULT those are two different insertions");
        Optional<Suggestion> shorter =
            GreedyMergeSuggestion.greedy("a\nc\n", "a\n  b  \nc\n", "a\nb\nc\n", ComparisonPolicy.TRIM_WHITESPACES);

        assertTrue(shorter.isPresent());
        assertEquals("a\nb\nc\n", shorter.get().code(), "the shorter of two policy-equal insertions");
    }

    @Test
    @DisplayName("the composed text is not reformatted: no doubled breaks and no trailing blank line")
    void theComposedTextIsNotReformatted() {
        // Two conventions of TextLines, both of which have caught this module: lines carry their terminators, and a
        // text ending with one leaves an empty final line so that splitting and joining are inverses. Emitting
        // either wrongly adds newlines the inputs never had, which no assertion on "the insertion is there" sees.
        Optional<Suggestion> suggested = GreedyMergeSuggestion.greedy("a\n\nc\n", "a\n\nB\nc\n", "a\n\nc\n", POLICY);

        assertTrue(suggested.isPresent());
        assertEquals("a\n\nB\nc\n", suggested.get().code());
        assertFalse(suggested.get().code().endsWith("\n\n"),
            "the trailing empty line is the absence of a line, not a blank one: "
                + suggested.get().code().replace("\n", "\\n"));
    }

    @Test
    @DisplayName("one side inserting takes that side's insertion, and the untouched lines survive")
    void oneSidedInsertionIsTaken() {
        Optional<Suggestion> ours = GreedyMergeSuggestion.greedy("a\nc\n", "a\nB\nc\n", "a\nc\n", POLICY);

        assertTrue(ours.isPresent());
        assertEquals("a\nB\nc\n", ours.get().code(),
            "the line neither branch touched is kept, and the insertion is applied");
    }

    @Test
    @DisplayName("two different insertions at one point are refused, because the order is a person's decision")
    void differingInsertionsRefuse() {
        // R6: this refusal is what makes the pass honest, and it is the one rule the port takes first.
        Optional<Suggestion> refused = GreedyMergeSuggestion.greedy("a\nc\n", "a\nOURS\nc\n", "a\nTHEIRS\nc\n", POLICY);

        assertTrue(refused.isEmpty(), "no order is more correct, so none is guessed");
    }

    @Test
    @DisplayName("the result is offered as PLAUSIBLE, and the deletion trade is recorded in its warnings")
    void theTradeIsRecordedRatherThanHidden() {
        Optional<Suggestion> suggested = GreedyMergeSuggestion.greedy("a\nc\n", "a\nB\nc\n", "a\nc\n", POLICY);

        assertTrue(suggested.isPresent());
        Suggestion suggestion = suggested.get();
        assertEquals(Suggestion.Confidence.PLAUSIBLE, suggestion.confidence(),
            "the composition is mechanical, its scope is a trade");
        assertEquals(AnalysisLevel.TEXT_LOCAL, suggestion.analysisLevel(),
            "this walk compares whole LINES under a policy-aware equality, so it cannot claim the intra-line level"
                + " - the record must say what the answer was checked against, not what it hoped to be");
        assertFalse(suggestion.analysisLevel() == AnalysisLevel.TEXT_INTRALINE,
            "claiming TEXT_INTRALINE here would be the overclaim step 4.11 exists to make inexpressible");
        assertFalse(suggestion.warnings().isEmpty(),
            "a reviewer deciding whether to trust this needs to know deletions are applied unconditionally");
        assertTrue(suggestion.warnings().get(0).contains("deletions are applied unconditionally"),
            suggestion.warnings().toString());
        assertTrue(suggestion.warnings().stream().anyMatch(warning -> warning.contains("not word by word")),
            "and the granularity it did read is named too: " + suggestion.warnings());
    }

    @Test
    @DisplayName("a deletion is applied, which is the trade the confidence exists for")
    void aDeletionIsApplied() {
        // Neither side's intent is knowable here, and the pass applies the deletion anyway. That is R4, and it is
        // why this may never become an AUTO answer: DESIGN_NEVER_AUTO_RESOLVED.md § 5.3's invisible regression.
        Optional<Suggestion> suggested = GreedyMergeSuggestion.greedy("a\ngone\nc\n", "a\nc\n", "a\nc\n", POLICY);

        assertTrue(suggested.isPresent());
        assertEquals("a\nc\n", suggested.get().code());
        assertFalse(suggested.get().code().contains("gone"));
    }

    @Test
    @DisplayName("the whitespace retry is a second suggestion, with a provenance of its own")
    void theWhitespaceRetryRecordsItsPolicy() {
        // Under DEFAULT the two insertions differ, so the strict attempt refuses. Under IGNORE_WHITESPACES they are
        // the same change, and the retry offers it - with the policy in the provenance, so a refusal can be
        // specific to the attempt that made the claim about whitespace.
        String ours = "a\n  b  \nc\n";
        String theirs = "a\nb  \nc\n";

        assertTrue(GreedyMergeSuggestion.greedy("a\nc\n", ours, theirs, POLICY).isEmpty(),
            "under DEFAULT the two insertions differ, so the strict attempt refuses");
        assertTrue(GreedyMergeSuggestion.greedy("a\nc\n", ours,
            "a\nb different\nc\n", POLICY).isEmpty(), "a real difference refuses under every policy");

        Optional<Suggestion> retried = GreedyMergeSuggestion.withWhitespaceRetry("a\nc\n", ours, theirs, POLICY);

        assertTrue(retried.isPresent(), "the retry is what finds the answer when the strict attempt refuses");
        assertTrue(retried.get().provenance().contains("greedy word-level merge"),
            retried.get().provenance());
        assertTrue(retried.get().provenance().contains(ComparisonPolicy.IGNORE_WHITESPACES.toString()),
            "the policy it ran under is part of who produced it: " + retried.get().provenance());
    }

    @Test
    @DisplayName("the provenance names the pass and the policy, so a refusal can be specific to both")
    void theProvenanceIsComplete() {
        String provenance = GreedyMergeSuggestion.provenanceFor(ComparisonPolicy.TRIM_WHITESPACES);

        assertTrue(provenance.contains("greedy word-level merge"), provenance);
        assertTrue(provenance.contains("TRIM_WHITESPACES"), provenance);
        assertFalse(provenance.contains("IGNORE_WHITESPACES"), provenance);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
