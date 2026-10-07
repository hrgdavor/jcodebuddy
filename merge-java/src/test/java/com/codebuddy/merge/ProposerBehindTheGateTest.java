package com.codebuddy.merge;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Plan step 4.4 and its Gate: a proposal that fails the verification gate is <strong>refused</strong> and cannot be
 * applied without an explicit human accept — and, more fundamentally, no proposal ever becomes the resolution by
 * itself.
 *
 * <p>The gate holds structurally rather than by promise: a proposal is attached as one more fix path on an escalated
 * resolution, and the code path that attaches it cannot reach the resolution. A test that only checked the verdict
 * would pass even if a proposal could still be applied; these check that it cannot be.</p>
 *
 * <p>The boundary is {@code DESIGN_NEVER_AUTO_RESOLVED.md}: structural, API and overlapping-body conflicts are
 * substitutive, so resolving one means discarding somebody's intention — which is a person's decision, or nobody's.
 * The proposer exists to inform that decision, not to take it.</p>
 */
class ProposerBehindTheGateTest {

    @TempDir
    Path tempDir;

    /** A structural conflict: the one kind the boundary says must always escalate. */
    private static Conflict structural() {
        return new Conflict(ConflictType.STRUCTURAL_CHANGE, "Ledger.java",
            "The set of declared members differs",
            "class Ledger {\n    private final Set<String> entries = new TreeSet<>();\n}\n",
            "class Ledger {\n    private final TreeSet<String> entries = new TreeSet<>();\n}\n",
            "class Ledger {\n    private final NavigableSet<String> entries = new TreeSet<>();\n}\n");
    }

    private MergeConflictResolver resolverWith(ConflictProposer proposer) {
        MergeConflictResolver.Builder builder = new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(tempDir.resolve("feature"))
            .setInMemoryOnly(true)
            .setTypeContext(TestTypeContexts.jdk());
        if (proposer != null) {
            builder.setProposer(proposer);
        }
        return builder.build();
    }

    private static FixPath proposalIn(ConflictResolution resolution) {
        return resolution.getAlternativePaths().stream()
            .filter(fixPath -> fixPath.getDescription().toLowerCase().contains("proposed"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no proposed fix path among " + resolution.getAlternativePaths().stream()
                    .map(FixPath::getDescription).toList()));
    }

    @Test
    @DisplayName("a proposal is one more fix path, never the resolution")
    void aProposalIsOnlyAFixPath() {
        String proposed = "class Ledger {\n    private final NavigableSet<String> entries = new TreeSet<>();\n}\n";
        ConflictResolution resolution = resolverWith(conflict -> Optional.of(
            new ConflictProposer.Proposal(proposed, "the wider type accepts every value either side wrote")))
            .resolve(structural());

        // The escalation stands: the proposal did not replace it, and nothing about it became applicable.
        Assertions.assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "a proposal must not turn an escalation into an opinion the tool holds");
        // "Independently applicable" is the report's rule, not the resolution's, so it is asked of the report:
        // nothing here may be applied without a person, because the proposal is advice and the escalation stands.
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            "Ledger.java", java.util.List.of(structural()), java.util.List.of(resolution), "feature");
        Assertions.assertTrue(report.getIndependentlyApplicable().isEmpty(),
            "and it must not become something the tool would apply on its own");
        Assertions.assertNotEquals(proposed, resolution.getResolvedCode(),
            "the proposal must not be the resolved code");

        FixPath fixPath = proposalIn(resolution);
        Assertions.assertNotNull(fixPath.getSuggested(), "the code travels with the option, for a human to accept");
        Assertions.assertEquals(proposed, fixPath.getSuggested().getResolvedCode());
        Assertions.assertTrue(fixPath.getJustification().contains("wider type"),
            "and the proposer's reasoning is there to read: " + fixPath.getJustification());
    }

    @Test
    @DisplayName("the proposal is also a suggestion: same single call, its own provenance and verdict")
    void aProposalIsAlsoASuggestion() {
        // Plan step 4.17 and SUGGESTIONS.md § 7: the proposer becomes ONE PROVENANCE OF THE CHANNEL, and the fix path
        // it always was stays as the route to accepting it. The assertions above keep their subject; these are what
        // the subject moved TO, and the two coexist rather than replacing each other.
        String proposed = "class Ledger {\n    private final NavigableSet<String> entries = new TreeSet<>();\n}\n";
        ConflictResolution resolution = resolverWith(conflict -> Optional.of(
            new ConflictProposer.Proposal(proposed, "the wider type accepts every value either side wrote")))
            .resolve(structural());

        Assertions.assertTrue(resolution.hasSuggestion(),
            "the proposal must be on the channel, not only among the fix paths");
        Suggestion offered = resolution.getSuggestions().stream()
            .filter(suggestion -> suggestion.provenance().contains("proposer"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no proposer suggestion among "
                + resolution.getSuggestions().stream().map(Suggestion::provenance).toList()));

        Assertions.assertEquals(proposed, offered.code(), "the proposed result is the suggestion's code");
        Assertions.assertEquals(Suggestion.Confidence.PLAUSIBLE, offered.confidence(),
            "a model's reading of the inputs is plausible rather than mechanically forced");
        Assertions.assertEquals(AnalysisLevel.TEXT_LOCAL, offered.analysisLevel(),
            "the tool can vouch for nothing about what a model READ, so the level is the weakest there is - "
                + "claiming STRUCTURE because the text looks structural would assert a reading nobody verified");
        Assertions.assertEquals(ConflictResolution.Verification.PASSED, offered.verification(),
            "and the gate's verdict travels on the suggestion, which is the channel's uniform field");

        // The escalation still stands: being on the channel is not being applied (DESIGN_NEVER_AUTO_RESOLVED.md).
        Assertions.assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "a suggestion is information: the decision stays a person's");
        Assertions.assertNotEquals(proposed, resolution.getResolvedCode());
    }

    @Test
    @DisplayName("two producers, two suggestions: the tool's own answer stays primary")
    void theToolsAnswerStaysPrimary() {
        // The case the single-slot channel could not express: a resolver's composed answer AND a model's proposal for
        // one conflict. The order is the promise - the first suggestion is the one the tool worked out, and the
        // model's sits beside it as the alternative rather than displacing it.
        String proposed = "class Ledger {\n    private final NavigableSet<String> entries = new TreeSet<>();\n}\n";
        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(tempDir.resolve("feature"))
            .setInMemoryOnly(true)
            .setTypeContext(TestTypeContexts.jdk())
            .setResolvers(java.util.List.of(new MethodBodyChangeConflictResolver()))
            .setProposer(conflict -> Optional.of(
                new ConflictProposer.Proposal(proposed, "the wider type accepts every value either side wrote")))
            .build();

        // A body conflict the ported pass composes at word level, so the tool HAS an answer of its own.
        Conflict both = new Conflict(ConflictType.METHOD_BODY_CHANGE, "Ledger.java", "both edited one line",
            "int total = a + b + c;\n", "int total = b + c;\n", "int total = a + b;\n");
        ConflictResolution resolution = resolver.resolve(both);

        Assertions.assertTrue(resolution.hasSeveralSuggestions(),
            "both producers answered, so both answers must be on the channel: "
                + resolution.getSuggestions().stream().map(Suggestion::provenance).toList());
        Assertions.assertEquals(AnalysisLevel.TEXT_INTRALINE, resolution.getSuggestion().analysisLevel(),
            "the primary is the tool's own composed answer, recorded at the level it earned");
        Assertions.assertTrue(resolution.getSuggestions().get(1).provenance().contains("proposer"),
            "and the model's proposal is the alternative beside it: "
                + resolution.getSuggestions().get(1).provenance());
    }

    @Test
    @DisplayName("a proposal the gate refuses is refused, and says so where a reviewer reads it")
    void aRefusedProposalIsRefused() {
        // Unbalanced: the structural gate refuses this, which is the case the Gate names.
        String refused = "class Ledger {\n    private final NavigableSet<String> entries";
        ConflictResolution resolution = resolverWith(conflict -> Optional.of(
            new ConflictProposer.Proposal(refused, "the model was interrupted mid-answer")))
            .resolve(structural());

        Assertions.assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        Assertions.assertNotEquals(ConflictResolution.Verification.PASSED, resolution.getVerification(),
            "an escalation is never verified as if it had been applied");
        Assertions.assertNotEquals(refused, resolution.getResolvedCode(),
            "a refused proposal cannot be the resolved code");

        FixPath fixPath = proposalIn(resolution);
        Assertions.assertEquals(ConflictResolution.Verification.FAILED, fixPath.getSuggested().getVerification(),
            "the verdict travels with the proposal");
        Assertions.assertTrue(fixPath.getDescription().contains("refused"),
            "and it is labelled as refused rather than quietly dropped: " + fixPath.getDescription());
        Assertions.assertTrue(fixPath.getImpact().startsWith("NOT verified"),
            "the impact says why applying it is not a safe default: " + fixPath.getImpact());
    }

    @Test
    @DisplayName("without a proposer nothing changes")
    void withoutAProposerNothingChanges() {
        Conflict conflict = structural();
        ConflictResolution bare = resolverWith(null).resolve(conflict);
        ConflictResolution advised = resolverWith(c -> Optional.of(
            new ConflictProposer.Proposal("class Ledger {\n}\n", "a proposal"))).resolve(conflict);

        Assertions.assertEquals(bare.getKind(), advised.getKind(),
            "an advisor cannot change what the tool decides");
        Assertions.assertEquals(bare.getVerification(), advised.getVerification());
        Assertions.assertTrue(bare.getAlternativePaths().stream()
                .noneMatch(fixPath -> fixPath.getDescription().toLowerCase().contains("proposed")),
            "and with no proposer there is no proposed fix path");
        Assertions.assertEquals(bare.getAlternativePaths().size() + 1, advised.getAlternativePaths().size(),
            "with one, the proposal is exactly one more fix path");
    }

    @Test
    @DisplayName("a proposer that fails keeps the escalation and says what happened")
    void aFailingProposerKeepsTheEscalation() {
        ConflictResolution resolution = resolverWith(conflict -> {
            throw new IllegalStateException("no endpoint configured");
        }).resolve(structural());

        Assertions.assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        Assertions.assertTrue(resolution.getAlternativePaths().stream()
                .anyMatch(fixPath -> fixPath.getDescription().contains("proposer failed")),
            "a failure is visible in a fix path instead of looking like silence");
        Assertions.assertTrue(resolution.getAlternativePaths().stream()
                .anyMatch(fixPath -> fixPath.getJustification().contains("no endpoint configured")));
    }

    @Test
    @DisplayName("an automatic resolution is never second-guessed by a proposer")
    void anAutomaticResolutionIsNotOfferedAProposal() {
        // IMPORT_ADD resolves automatically; a proposer must not be asked about it at all.
        Conflict additive = new Conflict(ConflictType.IMPORT_ADD, "PaymentProcessor.java",
            "Both branches added imports",
            "class PaymentProcessor {\n}\n",
            "import java.math.BigDecimal;\n\nclass PaymentProcessor {\n}\n",
            "import java.time.Instant;\n\nclass PaymentProcessor {\n}\n");
        boolean[] asked = { false };
        ConflictResolution resolution = resolverWith(conflict -> {
            asked[0] = true;
            return Optional.of(new ConflictProposer.Proposal("nonsense", "should never be asked"));
        }).resolve(additive);

        Assertions.assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        Assertions.assertFalse(asked[0], "an automatic answer needs no advisor");
    }
}
