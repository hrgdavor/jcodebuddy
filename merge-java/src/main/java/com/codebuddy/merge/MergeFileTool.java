// {@link com.codebuddy.merge.MergeFileTool} Fixes what it can in a conflict-marked file and extracts the rest as resolver fixtures.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tool for a single file that carries git conflict markers: fix whatever
 * the existing resolvers can fix, and prepare everything they cannot as a
 * fixture for a new resolver.
 *
 * <h2>What a run does</h2>
 *
 * <ol>
 *   <li><b>Parse</b> the marked file into conflict blocks
 *       ({@link ConflictMarkerParser}), reconstructing the whole ours/theirs
 *       versions - and the base when the file uses the diff3 style or the
 *       repository's index still holds stage 1 ({@link RepositoryProbe}).</li>
 *   <li><b>Resolve</b> each block through the ordinary pipeline -
 *       {@link ConflictDetectionService} classifies it, the
 *       {@linkplain ConflictResolvers registry} resolves it, sticky decisions
 *       replay, and the {@link ResolutionVerifier} gate applies - exactly as
 *       {@link MergeConflictResolver} does for whole files.</li>
 *   <li><b>Apply</b> a block's replacement only when exactly one resolution
 *       covers it, it is {@code AUTO}, it passed the gate, and its code accounts
 *       for the whole block ({@link #coversBlock}) - a resolution that answers
 *       only part of a mixed block would silently drop the rest, so the block
 *       stays. A residual {@code STRUCTURAL_CHANGE} that another conflict on the
 *       same block subsumes is not counted here: it is still emitted and still
 *       reported, it just stops deciding ({@link #residualSubsumed}). Blocks are
 *       disjoint by construction, so the safe subset is per block. Nothing is
 *       written unless {@link Builder#applyFixes(boolean)} asked for it.</li>
 *   <li><b>Extract</b> every conflict that remains into a temporary fixture
 *       workspace ({@link ConflictFixtureWriter}): the three-way sources, the
 *       exact block, what each resolver said, and a copy of
 *       {@value FixtureAgentInstructions#FILE_NAME} - the instructions that
 *       bind an agent to anonymize the case before any of it may enter this
 *       codebase, and to re-verify the finished resolver on the original
 *       through {@link #reverify}.</li>
 * </ol>
 *
 * <h2>Privacy</h2>
 *
 * <p>The file belongs to a user, and its content is proprietary. The workspace
 * is therefore written to a temporary directory by default, carries a
 * {@code .gitignore} with {@code *} in it, and is the <em>only</em> place the
 * original conflict is copied to. Nothing from it ever enters this module's
 * sources, tests or fixtures except through the anonymization the workspace's
 * {@code AGENTS.md} prescribes.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * MergeFileTool.Result result = MergeFileTool.forFile(Path.of("src/main/java/com/example/Payment.java"))
 *     .applyFixes(true)
 *     .run();
 *
 * System.out.println(result.describe());
 * System.exit(result.exitCode());
 * }</pre>
 *
 * <p>A {@link #main} is provided for one-off runs; the class remains an
 * ordinary library type first, consistent with {@link MergeUtil}.
 */
public final class MergeFileTool {

    /** Default workspace root: a temporary directory, outside any repository. */
    public static final Path DEFAULT_FIXTURE_ROOT =
        Path.of(System.getProperty("java.io.tmpdir"), "merge-java-fixtures");

    private MergeFileTool() {
    }

    /**
     * What happened to one conflict block.
     */
    public enum Outcome {
        /** A single verified automatic resolution replaced the block. */
        APPLIED_AUTO,
        /** Both sides were identical, so either one replaced the block. */
        APPLIED_IDENTICAL_SIDES,
        /** A decision recorded on an earlier run replaced the block (opt-in). */
        APPLIED_RECORDED_DECISION,
        /** Markers kept: a resolver produced an answer a human must confirm. */
        LEFT_REVIEW,
        /** Markers kept: no resolver could decide; fix paths describe the choices. */
        LEFT_MANUAL,
        /** Markers kept: this branch already recorded a decision; re-run with
         *  {@link Builder#applyRecordedDecisions(boolean)} to write it. */
        LEFT_DEFERRED,
        /** Markers kept: several automatic resolutions claimed the block and
         *  cannot be composed safely. */
        LEFT_MULTIPLE_AUTOMATIC,
        /** Markers kept: the one automatic resolution rewrites only part of the
         *  block - applying it would silently drop the rest. */
        LEFT_PARTIAL_RESOLUTION,
        /** Markers kept: the sides differ but detection recognised no type. */
        LEFT_UNCLASSIFIED
    }

    /**
     * The per-block result of a run.
     *
     * @param fixtureCase the case directory inside the workspace, or {@code null}
     *                    when no fixture was prepared for this block
     */
    public record BlockOutcome(int blockNumber, Region markerRegion, Outcome outcome,
                               ConflictType type, String explanation, Path fixtureCase) {

        boolean applied() {
            return outcome == Outcome.APPLIED_AUTO
                || outcome == Outcome.APPLIED_IDENTICAL_SIDES
                || outcome == Outcome.APPLIED_RECORDED_DECISION;
        }

        @Override
        public String toString() {
            return "block " + blockNumber + " (" + markerRegion + "): " + outcome
                + (type == null ? "" : " " + type);
        }
    }

    /**
     * What a run did with one file.
     *
     * @param fixtureRunDir the workspace directory, or {@code null} when no
     *                      fixture was prepared (nothing left, or
     *                      {@link Builder#prepareFixtures(boolean)} off)
     */
    public record Result(Path file, String reportedPath, String branchName, String baseSource,
                         boolean hadConflicts, boolean dryRun, boolean fileWritten,
                         List<BlockOutcome> outcomes, Path fixtureRunDir) {

        public Result {
            outcomes = List.copyOf(outcomes);
        }

        public int totalBlocks() {
            return outcomes.size();
        }

        public long appliedCount() {
            return outcomes.stream().filter(BlockOutcome::applied).count();
        }

        public long leftCount() {
            return totalBlocks() - appliedCount();
        }

        /**
         * True when no conflict block remains unapplied.
         */
        public boolean fullyResolved() {
            return leftCount() == 0;
        }

        /**
         * The exit status a caller should use: non-zero while the file still
         * carries conflicts.
         */
        public int exitCode() {
            return fullyResolved() ? 0 : 1;
        }

        /**
         * A multi-line report of every block's fate, suitable for a console.
         */
        public String describe() {
            StringBuilder text = new StringBuilder();
            if (!hadConflicts) {
                return file + ": no conflict markers found; nothing to do.";
            }
            text.append(file).append(": ").append(totalBlocks()).append(" conflict block(s) - ")
                .append(appliedCount()).append(" applied, ").append(leftCount()).append(" left");
            if (dryRun && appliedCount() > 0) {
                text.append(" [dry run, file not written]");
            }
            text.append(".\n");
            for (BlockOutcome outcome : outcomes) {
                text.append("  ").append(outcome).append(" - ").append(outcome.explanation());
                if (outcome.fixtureCase() != null) {
                    text.append("\n      fixture: ").append(outcome.fixtureCase());
                }
                text.append('\n');
            }
            if (fixtureRunDir != null) {
                text.append("  fixture workspace: ").append(fixtureRunDir).append('\n')
                    .append("      read ")
                    .append(fixtureRunDir.resolve(FixtureAgentInstructions.FILE_NAME))
                    .append(" before working with it: the contents are proprietary,\n")
                    .append("      and only the anonymized fixture it prescribes may enter a repository.\n");
            }
            boolean deferred = outcomes.stream()
                .anyMatch(outcome -> outcome.outcome() == Outcome.LEFT_DEFERRED);
            if (deferred) {
                text.append("  a decision recorded on an earlier run applies to at least one")
                    .append(" block; re-run with applyRecordedDecisions(true) to write it.\n");
            }
            if (dryRun && appliedCount() > 0) {
                text.append("  re-run with applyFixes(true) to write the file.\n");
            } else if (fileWritten) {
                text.append("  file written.\n");
            }
            return text.toString();
        }
    }

    /**
     * The verdict of re-running a candidate resolver against an original
     * fixture case - the last step of the loop the workspace's
     * {@code AGENTS.md} prescribes.
     *
     * @param viable true when the resolver produced applicable code: kind
     *               {@code AUTO} (applies without asking) or {@code REVIEW}
     *               (applies once a human confirms)
     */
    public record Reverification(ConflictType conflictType,
                                 ConflictResolution.ResolutionKind kind,
                                 boolean viable, String resolvedCode, String explanation) {
    }

    /**
     * Start configuring a run for one conflict-marked file.
     */
    public static Builder forFile(Path file) {
        return new Builder(file);
    }

    /**
     * Builder for {@link MergeFileTool} runs.
     *
     * <p>Dry by default, like {@link MergeWorkflow}: a run analyses and prepares
     * fixtures, and writes to the source file only when
     * {@link #applyFixes(boolean)} says so.
     */
    public static final class Builder {

        private final Path file;
        private boolean applyFixes;
        private boolean applyRecordedDecisions;
        private boolean prepareFixtures = true;
        private Path fixtureRoot = DEFAULT_FIXTURE_ROOT;
        private String branchName;
        private Path historyPath;

    /**
     * Where to write the report the review page renders ({@code --report}). The page is the reason this
     * exists: a conflict file is the one case where a reviewer needs the three sides of each block, and
     * without this the page had no way to be pointed at a single file.
     */
    private Path reportPath;

    /**
     * The decisions a reviewer exported from the review page ({@code --decisions}), recorded into this
     * branch's store before the file is analysed, so the very run that records them also applies them.
     */
    private Path decisionsFile;
        private Boolean inMemoryOnly;
        private TypeContext typeContext;
        private List<Path> classpath = List.of();
        private MergeConflictResolver resolver;
        private ConflictDetectionService detector;
    private ComparisonPolicy whitespacePolicy = ComparisonPolicy.TRIM_WHITESPACES;

        private Builder(Path file) {
            this.file = Objects.requireNonNull(file, "file");
        }

        /**
         * Write the resolved blocks back to the file. Off by default: a dry run
         * reports what would change and still prepares the fixture workspace.
         */
        public Builder applyFixes(boolean applyFixes) {
            this.applyFixes = applyFixes;
            return this;
        }

        /**
         * Also write blocks whose resolution was replayed from this branch's
         * recorded decisions. Off by default, matching {@link MergeWorkflow}:
         * a replayed preference decision is surfaced, not silently applied.
         */
        public Builder applyRecordedDecisions(boolean applyRecordedDecisions) {
            this.applyRecordedDecisions = applyRecordedDecisions;
            return this;
        }

        /**
         * Prepare the fixture workspace for the conflicts that remain. On by
         * default; switch off for a pure report.
         */
        public Builder prepareFixtures(boolean prepareFixtures) {
            this.prepareFixtures = prepareFixtures;
            return this;
        }

        /**
         * Where fixture workspaces are created. Defaults to a temporary
         * directory ({@link #DEFAULT_FIXTURE_ROOT}), deliberately outside any
         * repository; every workspace additionally carries a {@code .gitignore}
         * with {@code *} in it.
         */
        public Builder fixtureRoot(Path fixtureRoot) {
            this.fixtureRoot = Objects.requireNonNull(fixtureRoot, "fixtureRoot");
            return this;
        }

        /**
         * The branch whose history is consulted. Defaults to the repository's
         * current branch when the file is inside one, else {@code "unknown"}.
         */
        /** Write the review page's input for this file to {@code target}. */
    public Builder reportPath(Path target) {
        this.reportPath = target;
        return this;
    }

    /**
     * Record the decisions a reviewer exported from the review page, then apply them.
     *
     * <p>Recording happens before the analysis, because the store is read while resolving: a decision that
     * arrives with this run must be visible to it. Choosing a decisions file therefore implies
     * {@link #applyRecordedDecisions(boolean)} — a reviewer who picked an answer wants it used.</p>
     */
    public Builder decisionsFile(Path decisions) {
        this.decisionsFile = decisions;
        this.applyRecordedDecisions = true;
        return this;
    }

    public Builder branchName(String branchName) {
            this.branchName = branchName;
            return this;
        }

        /**
         * Where decisions are recorded and replayed from. Defaults, for a file
         * inside a repository, to
         * {@code <repoRoot>/.jcodebuddy/merge-history/<branch>} - the same
         * history {@link MergeWorkflow} uses, so both tools share one memory.
         */
        public Builder historyPath(Path historyPath) {
            this.historyPath = historyPath;
            return this;
        }

        /**
         * Keep every decision in memory. Defaults to in-memory for a file
         * outside any repository (there is no branch whose history could
         * legitimately be written), and to persistent history inside one.
         */
        public Builder inMemoryOnly(boolean inMemoryOnly) {
            this.inMemoryOnly = inMemoryOnly;
            return this;
        }

        /**
         * Supply the context resolvers need to resolve types. Defaults to the
         * runtime classpath rooted at the file's source root, which resolves
         * JDK types - the same default {@link MergeWorkflow} uses.
         *
         * <p>That default cannot resolve the merged project's <em>own</em> types, so a
         * conflict whose declarations are project types escalates with
         * {@link TypeChangeConflictResolver#UNRESOLVED_WARNING} rather than being decided:
         * nothing is guessed from a name, and the resolution says why. Pass a classpath
         * covering those types to have such pairs decided.
         */
        public Builder typeContext(TypeContext typeContext) {
            this.typeContext = typeContext;
            return this;
        }

        /**
         * The compile classpath of the project the file belongs to, which is what lets a
         * conflict about the project's own types be decided instead of escalated.
         *
         * <p><strong>Added to</strong> the JVM's own classpath rather than replacing it
         * ({@link TypeContext#withRuntimeClasspathAnd}), because the platform lives there:
         * a parser given only these entries resolves {@code java.util} no better than it
         * resolves the project, so a replacing flag would make "resolve my types" mean
         * "stop resolving everything else".
         *
         * <p>The single-file path always resolves with a classpath — this one, or the
         * runtime default. It never uses {@code TypeChangeConflictResolver}'s degraded,
         * no-context mode: that exists for a library caller who has no classpath to give,
         * and it is not a state a tool that can be told the classpath should be in.
         *
         * <p>Ignored when an explicit {@link #typeContext(TypeContext)} is set, since that
         * carries its own.
         *
         * @param entries classpath entries — directories of compiled classes or jars
         */
        public Builder classpath(List<Path> entries) {
            this.classpath = entries == null ? List.of() : List.copyOf(entries);
            return this;
        }

        /**
         * Replace the orchestrator wholesale, for callers that need custom
         * resolvers. When set, {@link #branchName}, {@link #historyPath},
         * {@link #inMemoryOnly} and {@link #typeContext} no longer affect
         * resolution (they still label the report and fixtures).
         */
        public Builder resolver(MergeConflictResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * Replace the detection service, for callers extending detection.
         */
        public Builder detector(ConflictDetectionService detector) {
            this.detector = detector;
            return this;
        }

        /**
         * Compare the sides under a whitespace policy (unified plan step 4.10).
         *
         * <p>The choice is what decides whether a re-indented region is a conflict. Under
         * {@link ComparisonPolicy#DEFAULT} it is — the indentation is content — and under
         * {@link ComparisonPolicy#IGNORE_WHITESPACES} it is not. {@code TRIM_WHITESPACES} sits between
         * them: a line's edges are ignored, its interior is not.
         *
         * <p>Defaults to {@link ComparisonPolicy#TRIM_WHITESPACES}, the behaviour this tool had before the
         * policy was a choice, so a caller that does not ask gets what it used to.
         */
        public Builder whitespacePolicy(ComparisonPolicy whitespacePolicy) {
            this.whitespacePolicy = whitespacePolicy;
            return this;
        }

        /** The policy this run compares under; never {@code null}. */
        public ComparisonPolicy whitespacePolicy() {
            return whitespacePolicy == null ? ComparisonPolicy.TRIM_WHITESPACES : whitespacePolicy;
        }

        public MergeFileTool.Result run() {
            return execute(this);
        }
    }

    // --------------------------------------------------------------------- run

    private static Result execute(Builder builder) {
        Path file = builder.file;
        String text = read(file);
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(text);
        if (!parsed.hasConflicts()) {
            return new Result(file, reportPathOf(file, null), "unknown", "none",
                false, !builder.applyFixes, false, List.of(), null);
        }

        RepositoryProbe.Findings findings = RepositoryProbe.probe(file);
        String reportedPath = reportPathOf(file, findings);
        String branchName = builder.branchName != null
            ? builder.branchName
            : findings.branchName() != null ? findings.branchName() : "unknown";
        Optional<String> diff3Base = parsed.baseVersion();
        String baseText = diff3Base.orElse(findings.stagedBase());
        String baseSource = diff3Base.isPresent()
            ? "diff3"
            : findings.hasStagedBase() ? "git-index-stage-1" : "none";

        TypeContext typeContext = builder.typeContext != null
            ? builder.typeContext
            : builder.classpath.isEmpty()
                ? TypeContext.withRuntimeClasspath(guessSourceRoot(file))
                : TypeContext.withRuntimeClasspathAnd(guessSourceRoot(file), builder.classpath);
        ComparisonPolicy whitespace = builder.whitespacePolicy();
        ConflictDetectionService detector = builder.detector != null
            ? builder.detector
            : new ConflictDetectionService();
          // Before the resolver exists: the store is read while resolving, so a decision exported from the
          // review page has to be recorded first for this run to apply it.
          if (builder.decisionsFile != null) {
              Path history = historyPathFor(builder, findings, branchName);
              try {
                  DecisionRecorder.Result recorded =
                      DecisionRecorder.record(builder.decisionsFile, history, branchName);
                  if (recorded.recorded() == 0) {
                      System.err.println("no decisions were recorded from " + builder.decisionsFile
                          + ": " + String.join("; ", recorded.problems()));
                  throw new IllegalStateException("no decisions were recorded from "
                      + builder.decisionsFile + ": "
                      + String.join("; ", recorded.problems()));
                  }
                  System.out.println(recorded.describe());
                  recorded.problems().forEach(problem ->
                      System.out.println("  refused: " + problem));
              } catch (java.io.IOException | RuntimeException failure) {
                  System.err.println("could not read the decisions file " + builder.decisionsFile
                      + ": " + failure.getMessage());
                  throw new IllegalStateException("could not read the decisions file "
                      + builder.decisionsFile + ": " + failure.getMessage(), failure);
              }
          }

          MergeConflictResolver resolver = builder.resolver != null
              ? builder.resolver
              : defaultResolver(builder, findings, branchName, typeContext);

        List<BlockOutcome> outcomes = new ArrayList<>();


        // Everything this file produced, in block order: the report the review page renders is built from


        // these, as one MergeReport for the file.


        List<Conflict> reportedConflicts = new ArrayList<>();


        List<ConflictResolution> reportedResolutions = new ArrayList<>();
        List<ConflictState> reportedStates = new ArrayList<>();
        List<ConflictFixtureWriter.FixtureCase> cases = new ArrayList<>();
        List<Integer> fixtureBlocks = new ArrayList<>();
        Map<Integer, List<String>> replacements = new LinkedHashMap<>();

        for (ConflictMarkerParser.Block block : parsed.blocks()) {
            String baseSlice = block.hasBase() ? block.base() : "";
            List<Conflict> detected = detector.detect(
                reportedPath, baseSlice, block.ours(), block.theirs(), typeContext,
                block.hasBase(), whitespace);
            // The detector works on this block's slices, so a conflict's region is relative to the BLOCK. A reader
            // of the report is looking at the FILE, so the block's own region is stamped instead. That is also the
            // only thing that groups the conflicts sharing a block - and the page reads per block, because this
            // tool will not compose several conflicts of one block into a single answer.
            List<Conflict> conflicts = new ArrayList<>(detected.size());
            for (Conflict conflict : detected) {
                conflicts.add(conflict.withRegion(block.markerRegion()));
            }
            // Resolve in tier order over a working set, so a conflict a stronger claim settles reliably is
            // never handed to a weaker resolver at all (plan step 4.19). The pass is built from `detected`,
            // whose regions are the detector's own: `conflicts` above carries the block's region so the
            // report can name it, and that stamp would make every conflict appear to cover every other.
            TieredResolution.Result tiered = TieredResolution.resolve(
                detected, block.markerRegion(), resolver.getResolvers(),
                (index, conflict) -> reattached(resolver.resolve(detected.get(index)),
                    conflicts.get(index)));
            List<ConflictResolution> resolutions = tiered.claims();
            // A conflict another claim settled has no claim of its own - nothing was asked about it - so it
            // is absent from everything a reader or a fixture sees.
            List<ConflictResolution> present = resolutions.stream()
                .filter(resolution -> resolution != null)
                .toList();

            // A residual with nothing of its own to say must not veto a block that
            // another conflict already resolves. Read from `detected`, not from
            // `conflicts`: stamping the block's file region onto every conflict above is
            // what a report needs, and is exactly what erases the evidence this reads.
            boolean residualSubsumed = residualSubsumed(detected, resolutions, block);

            reportedConflicts.addAll(conflicts);

            // What became of each of them, in the same order. A settled conflict has no claim of its own, so
            // this is the only place in the report where it appears (plan step 4.20, DEC-046 clause 7).
            for (ResolutionPass.LiveConflict live : tiered.pass().all()) {
                reportedStates.add(live.state());
            }


            reportedResolutions.addAll(present);



            BlockDecision decision = decide(block, conflicts, detected, resolutions,
                builder.applyRecordedDecisions, residualSubsumed);
            if (decision.replacement() != null && containsMarker(decision.replacement())) {
                // A replacement still carrying markers would re-open a conflict
                // while claiming to fix it: leave the block and say why.
                decision = new BlockDecision(Outcome.LEFT_MANUAL, decision.type(),
                    "The proposed resolution still contains conflict markers, so the block "
                        + "was left for a human.", null, false, present);
            }
            if (!tiered.removals().isEmpty()) {
                // A removed conflict leaves no other trace in the report, and "this tier had nothing to
                // say" must not read like "this tier was never asked" (DEC-046 clause 7).
                decision = new BlockDecision(decision.outcome(), decision.type(),
                    decision.explanation() + " " + removalNote(tiered.removals()),
                    decision.replacement(), decision.fixtureCase(), decision.resolutions());
            }
            if (decision.replacement() != null) {
                replacements.put(block.number(), splitReplacement(decision.replacement()));
            }

            if (builder.prepareFixtures && decision.fixtureCase()) {
                cases.add(new ConflictFixtureWriter.FixtureCase(
                    caseName(block, conflicts), reportedPath, decision.type(),
                    decision.description(conflicts),
                    block.startLine(), block.endLine(), decision.outcome().name(),
                    signatureText(block, conflicts), present,
                    block.raw(), block.base(), block.ours(), block.theirs()));
                fixtureBlocks.add(block.number());
            }

            outcomes.add(new BlockOutcome(block.number(), block.markerRegion(),
                decision.outcome(), decision.type(), decision.explanation(), null));
        }

        boolean fileWritten = false;
        if (builder.applyFixes && !replacements.isEmpty()) {
            write(file, rebuild(parsed, replacements));
            fileWritten = true;
        }

        if (builder.reportPath != null) {


            MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(reportedPath,


                reportedConflicts, reportedResolutions, branchName, reportedStates);


            MergeReportWriter.write(builder.reportPath, List.of(report), summaryFor(builder, report, outcomes));


            System.out.println("report written to " + builder.reportPath


                + " - render it with merge-java/review");


        }



        Path runDir = null;
        if (builder.prepareFixtures && !cases.isEmpty()) {
            ConflictFixtureWriter.RunManifest manifest =
                new ConflictFixtureWriter.RunManifest(
                    file.toAbsolutePath(), reportedPath, branchName, baseSource,
                    findings.repositoryFound(), findings.repositoryRoot(),
                    !builder.applyFixes, fileWritten,
                    parsed.blocks().size(), replacements.size(),
                    parsed.blocks().size() - replacements.size(),
                    parsed.conflictedText(), parsed.oursVersion(), parsed.theirsVersion(),
                    baseText, Instant.now());
            runDir = ConflictFixtureWriter.writeRun(builder.fixtureRoot, manifest, cases);

            // Now that the workspace exists, point each fixture-carrying outcome
            // at its case directory.
            for (int i = 0; i < outcomes.size(); i++) {
                BlockOutcome outcome = outcomes.get(i);
                if (fixtureBlocks.contains(outcome.blockNumber())) {
                    String caseName = caseFor(outcome, cases);
                    outcomes.set(i, new BlockOutcome(outcome.blockNumber(), outcome.markerRegion(),
                        outcome.outcome(), outcome.type(), outcome.explanation(),
                        runDir.resolve("cases").resolve(caseName)));
                }
            }
        }

        return new Result(file, reportedPath, branchName, baseSource, true,
            !builder.applyFixes, fileWritten, outcomes, runDir);
    }

    /**
     * The decision for one block, derived from the resolutions covering it.
     *
     * <p>The application rule is deliberately narrow: a block is replaced only
     * when <em>exactly one</em> resolution covers it and that resolution is
     * automatic (or a recorded decision the caller opted into). Several
     * automatic answers for one block each describe a partial rewrite of the
     * same text and cannot be composed safely, so the block stays and becomes a
     * fixture - the honest report is "one thing no resolver understands yet",
     * which is exactly what the fixture loop is for.
     */
    private record BlockDecision(Outcome outcome, ConflictType type, String explanation,
                                 String replacement, boolean fixtureCase,
                                 List<ConflictResolution> resolutions) {

        String description(List<Conflict> conflicts) {
            if (!conflicts.isEmpty()) {
                StringBuilder text = new StringBuilder(conflicts.get(0).getDescription());
                for (int i = 1; i < conflicts.size(); i++) {
                    text.append(" | ").append(conflicts.get(i).getDescription());
                }
                return text.toString();
            }
            return "Detection recognised no conflict type for this block, but its sides differ.";
        }
    }

    private static BlockDecision decide(ConflictMarkerParser.Block block,
                                        List<Conflict> conflicts,
                                        List<Conflict> detected,
                                        List<ConflictResolution> resolutions,
                                        boolean applyRecordedDecisions,
                                        boolean residualSubsumed) {
        ConflictType type = conflicts.isEmpty() ? null : conflicts.get(0).getType();

        // A conflict another claim settled has no claim of its own — nothing was asked about it — so it
        // does not decide the block either. The absence is the record of a removal, not a gap in the list
        // (plan step 4.19, DEC-046 clause 9), and everything below works on what is present.
        resolutions = resolutions.stream()
            .filter(resolution -> resolution != null)
            .toList();

        // A subsumed residual is dropped from the decision - it is still emitted and
        // still reported (it reaches reportedConflicts and reportedResolutions), it just
        // stops vetoing. Whatever remains is what decides the block.
        List<ConflictResolution> deciding = residualSubsumed
            ? resolutions.stream()
                .filter(resolution -> resolution.getType() != ConflictType.STRUCTURAL_CHANGE)
                .toList()
            : resolutions;

        if (deciding.size() == 1) {
            ConflictResolution resolution = deciding.get(0);
            return switch (resolution.getKind()) {
                case AUTO -> coversBlock(resolution, block)
                    ? new BlockDecision(Outcome.APPLIED_AUTO, type,
                        resolution.getExplanation(), resolution.getResolvedCode(), false,
                        resolutions)
                    : partial(type, resolution, resolutions);
                case DEFERRED -> applyRecordedDecisions
                    // A recorded decision is the human answer to exactly this
                    // disagreement (the signature matched), so the coverage
                    // rule does not second-guess it.
                    ? new BlockDecision(Outcome.APPLIED_RECORDED_DECISION, type,
                        resolution.getExplanation(), resolution.getResolvedCode(), false,
                        resolutions)
                    : new BlockDecision(Outcome.LEFT_DEFERRED, type,
                        resolution.getExplanation(), null, false, resolutions);
                case REVIEW -> new BlockDecision(Outcome.LEFT_REVIEW, type,
                    resolution.getExplanation(), null, true, resolutions);
                case MANUAL -> new BlockDecision(Outcome.LEFT_MANUAL, type,
                    resolution.getExplanation(), null, true, resolutions);
            };
        }

        if (deciding.isEmpty()) {
            if (block.ours().equals(block.theirs())) {
                return new BlockDecision(Outcome.APPLIED_IDENTICAL_SIDES, null,
                    "Both sides of the block are identical, so either one is the answer.",
                    block.ours(), false, resolutions);
            }
            return new BlockDecision(Outcome.LEFT_UNCLASSIFIED, null,
                "The sides differ but detection recognised no conflict type; the block needs "
                    + "a resolver that understands this shape.",
                null, true, resolutions);
        }

        // Claims that disagree are not all equal: an answer reached by parsing the project's types
        // outranks one reached by comparing lines of text, and a weaker claim must not veto an answer
        // that already accounts for whatever the weaker claim was protecting. See outranking(...).
        ConflictResolution outranking = outranking(deciding, resolutions, detected, block);
        if (outranking != null) {
            return new BlockDecision(Outcome.APPLIED_AUTO, outranking.getType(),
                outranking.getExplanation() + " " + outranked(deciding, outranking),
                outranking.getResolvedCode(), false, resolutions);
        }

        boolean anyManual = deciding.stream()
            .anyMatch(r -> r.getKind() == ConflictResolution.ResolutionKind.MANUAL);
        if (anyManual) {
            return new BlockDecision(Outcome.LEFT_MANUAL, type,
                joined(deciding) + " Multiple conflicts claim this block and at least one is "
                    + "manual.",
                null, true, resolutions);
        }
        boolean anyReview = deciding.stream()
            .anyMatch(r -> r.getKind() == ConflictResolution.ResolutionKind.REVIEW);
        if (anyReview) {
            return new BlockDecision(Outcome.LEFT_REVIEW, type,
                joined(deciding) + " Multiple conflicts claim this block and at least one "
                    + "needs review.",
                null, true, resolutions);
        }
        boolean anyDeferred = deciding.stream()
            .anyMatch(r -> r.getKind() == ConflictResolution.ResolutionKind.DEFERRED);
        if (anyDeferred) {
            return new BlockDecision(Outcome.LEFT_DEFERRED, type,
                joined(deciding) + " Recorded decisions claim part of this block; re-run with "
                    + "applyRecordedDecisions(true) only after confirming they still compose.",
                null, false, resolutions);
        }
        return new BlockDecision(Outcome.LEFT_MULTIPLE_AUTOMATIC, type,
            joined(deciding) + " Several automatic resolutions claim this block; they cannot "
                + "be composed safely, so it is left for a resolver that understands the whole "
                + "shape.",
            null, true, resolutions);
    }

    /**
     * True when the block's structural residual has nothing of its own to say, so it
     * must not veto a decision another conflict already makes (unified plan step 4.5).
     *
     * <p>A residual is emitted <b>alongside</b> the recognised conflicts deliberately - a
     * residual that <em>replaced</em> them once lost a mechanical import addition - but
     * the application rule needs <em>exactly one</em> resolution to claim a block, so an
     * alongside-emitted residual carrying no evidence of its own turned every decided
     * block into {@code LEFT_MANUAL}. The residual is still reported; it stops vetoing.
     *
     * <p>Two shapes are subsumed, each decided by the strongest evidence available:
     *
     * <ul>
     *   <li><b>a base side is present</b> - the residual's region is the span of base
     *       lines that neither branch kept, so it has nothing of its own exactly when
     *       another conflict's region <em>covers</em> that span. A residual that reaches a
     *       line no other conflict places keeps its veto, which is the case the
     *       alongside-emission exists for.</li>
     *   <li><b>no base side</b> - git's default <em>merge</em> conflict style carries no
     *       base (only {@code diff3}/{@code zdiff3} do), so every claim the residual makes
     *       is relative to a base this run does not have and it can place no line at all.
     *       It is then subsumed only by one <em>complete automatic</em> answer: a single
     *       other conflict that is {@code AUTO} and whose code accounts for the whole
     *       block. Anything less - a review, a partial answer, or several automatic
     *       answers that cannot compose - leaves the veto in place.</li>
     * </ul>
     */
    static boolean residualSubsumed(List<Conflict> detected,
                                    List<ConflictResolution> resolutions,
                                    ConflictMarkerParser.Block block) {
        int residual = -1;
        for (int index = 0; index < detected.size(); index++) {
            if (detected.get(index).getType() == ConflictType.STRUCTURAL_CHANGE) {
                residual = index;
                break;
            }
        }
        if (residual < 0) {
            return false;
        }

        List<Integer> others = new ArrayList<>(detected.size());
        for (int index = 0; index < detected.size(); index++) {
            if (index != residual) {
                others.add(index);
            }
        }
        if (others.isEmpty()) {
            // Nothing else claims the block, so the residual is the only thing that knows
            // it is unresolved: it keeps its veto.
            return false;
        }

        Region residualRegion = detected.get(residual).getRegion();
        if (residualRegion.isKnown()) {
            return others.stream().anyMatch(
                index -> coversRegion(detected.get(index).getRegion(), residualRegion));
        }

        if (others.size() != 1) {
            return false;
        }
        ConflictResolution only = resolutions.get(others.get(0));
        // A null claim here is a conflict a stronger tier settled: it was never asked, so it is not an
        // automatic answer that can subsume the residual's veto.
        return only != null
            && only.getKind() == ConflictResolution.ResolutionKind.AUTO
            && coversBlock(only, block);
    }

    /**
     * A resolution whose region is restated in the file's coordinates, for the report.
     *
     * <p>The resolver is handed the <b>detector's</b> conflict, whose region is block-relative and therefore
     * the region it actually recognised — that is the region an explained span has to be, and handing it the
     * stamped conflict instead made every resolver that declares a span appear to explain its whole block.
     * Measured: with the stamp, {@code ImportConflictResolver} settled an unrelated {@code COMMENT_ADD}
     * conflict in the same block, which is the stamped-region trap {@code outranking}'s own note describes.
     *
     * <p>The report, though, is read against the file, and a block-relative line number would be wrong
     * there. So the region is restated from the conflict that carries the block's own file region — the same
     * fact the stamp exists for, applied at the boundary where it belongs rather than before resolution.
     */
    private static ConflictResolution reattached(ConflictResolution resolution, Conflict placed) {
        return resolution == null ? null : resolution.withRegion(placed.getRegion());
    }

    /**
     * The sentence naming what was settled without the weaker tiers being asked, so a reviewer can see that
     * a tier was skipped rather than that it had nothing to say (DEC-046 clause 7).
     *
     * <p>This is the only trace a removed conflict leaves: it has no claim of its own, so nothing else in
     * the report mentions it, and a block that came out applied would otherwise read as if the text tier had
     * simply found nothing.
     */
    private static String removalNote(List<TieredResolution.Removal> removals) {
        StringBuilder text = new StringBuilder(
            "Settled by stronger evidence, so the weaker tiers were never asked:");
        for (TieredResolution.Removal removal : removals) {
            text.append(" [").append(removal.type()).append(" at ").append(removal.region())
                .append(" resolved by ").append(removal.by().getType())
                .append(" at ").append(removal.tier()).append("]");
        }
        return text.toString();
    }

    /**
     * True when {@code outer} spans every line of {@code inner}.
     *
     * <p>Both must be known: an unknown region covers nothing, so a conflict that cannot
     * be located keeps the residual's veto rather than dropping it on missing
     * information.
     *
     * <p>Delegates to {@link Region#covers(Region)}, which is the one definition of the question. This
     * method remains because it reads as the rule rather than as a property of a value, and because
     * {@link #residualSubsumed} and {@link #accountsFor} are written in those terms.
     */
    static boolean coversRegion(Region outer, Region inner) {
        return outer != null && outer.covers(inner);
    }

    /**
     * The one resolution that outranks every other claim on this block, or {@code null} when no
     * single one does.
     *
     * <h2>Why claims are not all equal</h2>
     *
     * <p>Several conflicts can claim one block, and the rule above treats them alike: any manual
     * objection leaves the block, and several automatic answers that cannot be composed leave it too.
     * Treating them alike is wrong in one direction, because the claims are not equally well founded.
     * Comparing the first public method line of each side as text cannot tell "both branches added a
     * <em>different</em> member" from "both branches changed the one member", while recognising the
     * two declarations can — and the weaker claim used to veto the stronger answer. That is the same
     * defect {@link #residualSubsumed} fixes for the structural residual, generalised from "a claim
     * with no evidence of its own" to "a claim with weaker evidence than a decision that exists".
     *
     * <h2>The rule</h2>
     *
     * <p>A claim outranks the others when all of these hold:
     *
     * <ul>
     *   <li>it is {@code AUTO}, so it may be applied at all — the level never promotes a review or a
     *       manual answer, and a block whose best claim needs a human still gets one;</li>
     *   <li>its code accounts for the whole block ({@link #coversBlock()}), so applying it cannot
     *       drop part of a mixed block;</li>
     *   <li>its {@link AnalysisLevel} is <em>strictly</em> stronger than every other claim's — equal
     *       evidence decides nothing, because two analyses of the same strength disagreeing is
     *       exactly what a human has to settle;</li>
     *   <li>it {@link #accountsFor accounts for} every claim it outranks, so nothing a weaker claim
     *       was protecting is silently dropped. This is what keeps the level from becoming a licence:
     *       a widening that adopts one side outranks a residual only where its region already covered
     *       what the residual described, and a residual reaching a line no other conflict places
     *       still keeps its veto;</li>
     *   <li>no other claim is {@code DEFERRED}: a recorded human decision is a decision rather than
     *       weaker evidence, so it is never outranked.</li>
     * </ul>
     *
     * <p>Two claims can each outrank the rest only by both being strictly stronger than each other,
     * which cannot happen — so either exactly one winner exists or none does.
     */
    static ConflictResolution outranking(List<ConflictResolution> claims,
                                        List<ConflictResolution> all,
                                        List<Conflict> detected,
                                        ConflictMarkerParser.Block block) {
        ConflictResolution winner = null;
        for (ConflictResolution candidate : claims) {
            if (!outranks(candidate, claims, all, detected, block)) {
                continue;
            }
            if (winner != null) {
                return null;
            }
            winner = candidate;
        }
        return winner;
    }

    /** True when {@code candidate} outranks every other claim; see {@link #outranking}. */
    private static boolean outranks(ConflictResolution candidate,
                                    List<ConflictResolution> claims,
                                    List<ConflictResolution> all,
                                    List<Conflict> detected,
                                    ConflictMarkerParser.Block block) {
        if (candidate.getKind() != ConflictResolution.ResolutionKind.AUTO) {
            return false;
        }
        if (!coversBlock(candidate, block)) {
            return false;
        }
        Region candidateRegion = detectorRegionOf(candidate, all, detected);
        for (ConflictResolution other : claims) {
            if (other == candidate) {
                continue;
            }
            if (other.getKind() == ConflictResolution.ResolutionKind.DEFERRED) {
                return false;
            }
            if (!candidate.hasStrongerAnalysisThan(other)) {
                return false;
            }
            if (!accountsFor(candidate, candidateRegion, other,
                    detectorRegionOf(other, all, detected))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The region the detector gave the conflict this resolution answers, <em>before</em> the report
     * stamped every conflict of a block with the block's own region.
     *
     * <p>Read from {@code detected} rather than from the resolution, because the stamp makes every
     * claim's region identical — which would make "the winner's region covers the claim's" true for
     * every pair and turn the one protection this rule has into a formality. The very first version of
     * {@link #outranking} read the stamped region and applied a block the residual was guarding.
     *
     * <p>Identity is the lookup key: {@code claims} holds references into {@code all}, and
     * {@code all} is index-aligned with {@code detected} (both built in one loop over the block).
     */
    private static Region detectorRegionOf(ConflictResolution resolution,
                                           List<ConflictResolution> all,
                                           List<Conflict> detected) {
        for (int index = 0; index < all.size() && index < detected.size(); index++) {
            if (all.get(index) == resolution) {
                return detected.get(index).getRegion();
            }
        }
        return Region.unknown();
    }

    /**
     * True when applying {@code winner} leaves nothing that {@code claim} was guarding behind.
     *
     * <p>Two independent ways to be accounted for, and either is enough:
     *
     * <ul>
     *   <li><b>the winner's region covers the claim's</b> — the winner is about everything the claim
     *       is about, which is the test {@link #residualSubsumed} already applies to the structural
     *       residual. This is what carries a decided widening over a residual that describes the same
     *       declaration, and equally what <em>stops</em> it when the residual reaches a line the
     *       widening's region does not;</li>
     *   <li><b>the winner's code keeps every line of both of the claim's sides</b> — a
     *       {@code KEEP_BOTH}/{@code MERGE_SAFE} answer that includes both branches entirely cannot be
     *       dropping what the claim was about. A {@code PREFER_BRANCH1/2} answer does not pass this
     *       test by construction, because it drops the other side on purpose, which is why the region
     *       test above is the one that has to carry those.</li>
     * </ul>
     */
    private static boolean accountsFor(ConflictResolution winner, Region winnerRegion,
                                       ConflictResolution claim, Region claimRegion) {
        if (coversRegion(winnerRegion, claimRegion)) {
            return true;
        }
        Set<String> winnerLines = Reliability.normalisedLines(winner.getResolvedCode());
        return Reliability.keepsEveryLine(winnerLines, claim.getBranch1Code())
            && Reliability.keepsEveryLine(winnerLines, claim.getBranch2Code());
    }

    /**
     * The sentence naming what was outranked and why, so a reviewer can see that an objection lost
     * rather than that it was never raised — and can judge the comparison for themselves.
     */
    private static String outranked(List<ConflictResolution> claims, ConflictResolution winner) {
        StringBuilder text = new StringBuilder("Outranked on this block by stronger evidence (")
            .append(winner.getAnalysisLevel()).append("):");
        for (ConflictResolution other : claims) {
            if (other == winner) {
                continue;
            }
            text.append(" [").append(other.getType()).append('/').append(other.getKind())
                .append(" at ").append(other.getAnalysisLevel()).append("] ")
                .append(other.getExplanation());
        }
        return text.toString();
    }

    /**
     * The verdict for an automatic resolution that does not cover the whole
     * block: leave the markers and prepare a fixture, because a resolver for
     * the complete shape does not exist yet.
     */
    private static BlockDecision partial(ConflictType type, ConflictResolution resolution,
                                         List<ConflictResolution> resolutions) {
        return new BlockDecision(Outcome.LEFT_PARTIAL_RESOLUTION, type,
            "[" + resolution.getType() + "/" + resolution.getKind() + "] "
                + resolution.getExplanation()
                + " The resolution rewrites only part of the block, so applying it would drop"
                + " the rest; the block is left and prepared as a fixture.",
            null, true, resolutions);
    }

    /**
     * True when a resolution's code accounts for the entire block, so replacing
     * the block with it loses nothing.
     *
     * <p>Three shapes pass:
     *
     * <ul>
     *   <li>a resolution that <em>prefers one side</em> ({@code PREFER_BRANCH1/2}
     *       - a widening type change, a one-sided package move) whose code is
     *       that side line for line: dropping the other side is then the
     *       decision itself, not an accident;</li>
     *   <li>a resolution that <em>covers both sides</em>: every non-blank line
     *       of each side appears in the resolved code, normalised because
     *       resolvers re-render what they merge (the import union is sorted,
     *       not concatenated);</li>
     *   <li>lines the resolver <em>owns</em> may be missing: an import union
     *       legitimately drops an import the other side removed, and a comment
     *       union rewrites comment lines. What must never silently disappear is
     *       a line outside the resolution's own domain - the method edit that
     *       happened to share a block with an import conflict.</li>
     * </ul>
     *
     * <p>A resolution that fails the check leaves the block marked, and the
     * block becomes a fixture: the honest report is that no resolver understands
     * the complete shape yet.
     */
    static boolean coversBlock(ConflictResolution resolution, ConflictMarkerParser.Block block) {
        String resolved = resolution.getResolvedCode();
        if (resolved == null || resolved.isBlank()) {
            return false;
        }
        ConflictResolution.ResolutionStrategy strategy = resolution.getResolutionStrategy();
        if (strategy == ConflictResolution.ResolutionStrategy.PREFER_BRANCH1
            && sameLineSet(resolved, block.ours())) {
            return true;
        }
        if (strategy == ConflictResolution.ResolutionStrategy.PREFER_BRANCH2
            && sameLineSet(resolved, block.theirs())) {
            return true;
        }
        Set<String> resolvedLines = Reliability.normalisedLines(resolved);
        return coversSide(resolvedLines, block.ours(), resolution.getType())
            && coversSide(resolvedLines, block.theirs(), resolution.getType());
    }

    private static boolean coversSide(Set<String> resolvedLines, String side, ConflictType type) {
        for (String line : Reliability.normalisedLines(side)) {
            if (resolvedLines.contains(line) || inResolverDomain(line, type)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * True for lines the resolver of this type rewrites as part of its answer,
     * so their absence from the resolved code is a decision rather than a loss.
     */
    private static boolean inResolverDomain(String normalisedLine, ConflictType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case IMPORT_ADD -> normalisedLine.startsWith("import ")
                && normalisedLine.endsWith(";");
            case COMMENT_ADD -> normalisedLine.startsWith("//")
                || normalisedLine.startsWith("/*")
                || normalisedLine.startsWith("*")
                || normalisedLine.endsWith("*/");
            default -> false;
        };
    }

    private static boolean sameLineSet(String left, String right) {
        return Reliability.normalisedLines(left).equals(Reliability.normalisedLines(right));
    }

    private static String joined(List<ConflictResolution> resolutions) {
        StringBuilder text = new StringBuilder();
        for (ConflictResolution resolution : resolutions) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append('[').append(resolution.getType()).append("/")
                .append(resolution.getKind()).append("] ")
                .append(resolution.getExplanation());
        }
        return text.toString();
    }

    /**
     * True when a replacement text still carries conflict markers at the start
     * of a line - applying it would claim to fix a block while re-opening one.
     */
    private static boolean containsMarker(String replacement) {
        for (String line : replacement.split("\n")) {
            String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (stripped.startsWith("<<<<<<<") || stripped.startsWith(">>>>>>>")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitReplacement(String replacement) {
        String normalised = replacement.replace("\r\n", "\n");
        String[] parts = normalised.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(parts));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * The file text with every applied block replaced by its resolution,
     * keeping the file's line endings and trailing newline.
     */
    private static String rebuild(ConflictMarkerParser.ParsedFile parsed,
                                  Map<Integer, List<String>> replacements) {
        List<String> lines = new ArrayList<>(parsed.lines().size());
        int index = 0;
        int blockAt = 0;
        List<ConflictMarkerParser.Block> blocks = parsed.blocks();
        while (index < parsed.lines().size()) {
            if (blockAt < blocks.size() && index == blocks.get(blockAt).startLine() - 1) {
                ConflictMarkerParser.Block block = blocks.get(blockAt);
                List<String> replacement = replacements.get(block.number());
                if (replacement != null) {
                    lines.addAll(replacement);
                } else {
                    lines.addAll(parsed.lines().subList(index, block.endLine()));
                }
                index = block.endLine();
                blockAt++;
                continue;
            }
            lines.add(parsed.lines().get(index));
            index++;
        }
        StringBuilder text = new StringBuilder(String.join(parsed.eol(), lines));
        if (parsed.endsWithNewline()) {
            text.append(parsed.eol());
        }
        return text.toString();
    }

    // ----------------------------------------------------------------- defaults

    private static MergeConflictResolver defaultResolver(Builder builder,
                                                         RepositoryProbe.Findings findings,
                                                         String branchName,
                                                         TypeContext typeContext) {
        boolean inMemory = builder.inMemoryOnly != null
            ? builder.inMemoryOnly
            : !findings.repositoryFound() && builder.historyPath == null;
        Path history = historyPathFor(builder, findings, branchName);        return new MergeConflictResolver.Builder()
            .setBranchName(branchName)
            .setHistoryPath(history)
            .setInMemoryOnly(inMemory)
            .setTypeContext(typeContext)
            .build();
    }

    /**
     * The branch's history directory: where its decisions live, and what the resolver is given.
     *
     * <p>One method because two callers must agree on it exactly. A recording that lands one level above
     * this directory writes successfully and is never replayed — the failure mode the review flow already
     * hit once, silently, so the path is computed in one place from now on.</p>
     */
    private static Path historyPathFor(Builder builder, RepositoryProbe.Findings findings,
                                      String branchName) {
        if (builder.historyPath != null) {
            return builder.historyPath;
        }
        return findings.repositoryFound()
            ? findings.repositoryRoot().resolve(".jcodebuddy").resolve("merge-history")
                .resolve(branchName)
            : Path.of(".jcodebuddy", "merge-history", branchName);
    }

    /**
     * The counts the report carries, derived the same way the batch path derives them, so the page sees one
     * vocabulary whether the report came from a whole merge or from a single conflict file.
     */
    private static MergeBatch.Summary summaryFor(Builder builder,
                                                MergeConflictResolver.MergeReport report,
                                                List<BlockOutcome> outcomes) {
        List<Conflict> conflicts = report.getConflicts();
        List<ConflictResolution> resolutions = report.getResolutions();
        int auto = 0;
        // The rule for "independently applicable" belongs to the report, so it is asked rather than
        // re-derived here: two copies of it would drift.
        int applicable = report.getIndependentlyApplicable().size();
        int review = 0;
        int manual = 0;
        int replayed = 0;
        for (ConflictResolution resolution : resolutions) {
            switch (resolution.getKind()) {
                case AUTO -> auto++;
                case REVIEW -> review++;
                case MANUAL -> manual++;
                case DEFERRED -> {
                    /* deferred: counted by the replay below */
                }
            }
            if (resolution.getResolutionStrategy()
                    == ConflictResolution.ResolutionStrategy.STICKY_REPLAY) {
                replayed++;
            }
        }
        long applied = outcomes.stream().filter(BlockOutcome::applied).count();
        return new MergeBatch.Summary(1, conflicts.isEmpty() ? 1 : 0, conflicts.size(), auto,
            applicable, review, manual, replayed, !builder.applyFixes && applied == 0);
    }

    private static String reportPathOf(Path file, RepositoryProbe.Findings findings) {
        if (findings != null && findings.relativePath() != null) {
            return findings.relativePath();
        }
        return file.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * The source root to anchor type resolution at: the nearest
     * {@code src/main/java} or {@code src/test/java} ancestor when there is one,
     * else the file's own directory.
     */
    static Path guessSourceRoot(Path file) {
        Path directory = file.toAbsolutePath().normalize().getParent();
        for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
            if (candidate.getFileName() == null
                || !"java".equals(candidate.getFileName().toString())) {
                continue;
            }
            Path parent = candidate.getParent();
            if (parent == null || parent.getFileName() == null) {
                continue;
            }
            String parentName = parent.getFileName().toString();
            Path grand = parent.getParent();
            if (("main".equals(parentName) || "test".equals(parentName))
                && grand != null && grand.getFileName() != null
                && "src".equals(grand.getFileName().toString())) {
                return candidate;
            }
        }
        return directory == null ? Path.of(".") : directory;
    }

    // ------------------------------------------------------------ fixture cases

    /**
     * The directory name for a block's fixture case: numbered, then the
     * conflict signature (stable across reformats, per {@link ConflictSignature})
     * or an {@code unclassified} hash when detection produced nothing.
     */
    static String caseName(ConflictMarkerParser.Block block, List<Conflict> conflicts) {
        return "case-" + block.number() + "-" + signaturePart(block, conflicts);
    }

    private static String signatureText(ConflictMarkerParser.Block block,
                                        List<Conflict> conflicts) {
        return conflicts.isEmpty()
            ? signaturePart(block, conflicts)
            : ConflictSignature.of(conflicts.get(0)).toString();
    }

    private static String signaturePart(ConflictMarkerParser.Block block,
                                        List<Conflict> conflicts) {
        if (!conflicts.isEmpty()) {
            return ConflictSignature.of(conflicts.get(0)).toFileName();
        }
        String identity = ConflictSignature.normalise(block.ours()) + "\u0000"
            + ConflictSignature.normalise(block.theirs());
        return "unclassified-" + ConflictFixtureWriter.sha256Hex(identity).substring(0, 16);
    }

    private static String caseFor(BlockOutcome outcome,
                                  List<ConflictFixtureWriter.FixtureCase> cases) {
        for (ConflictFixtureWriter.FixtureCase fixtureCase : cases) {
            if (fixtureCase.startLine() == outcome.markerRegion().startLine()
                && fixtureCase.endLine() == outcome.markerRegion().endLine()) {
                return fixtureCase.caseName();
            }
        }
        throw new IllegalStateException("no fixture case recorded for " + outcome);
    }

    // -------------------------------------------------------------- reverify

    /**
     * Re-verify a candidate resolver - typically one written from the
     * anonymized fixture - against an <b>original</b> fixture case directory.
     *
     * <p>This is the closing step of the loop the workspace's
     * {@code AGENTS.md} prescribes: the anonymized fixture is what the
     * repository keeps, and the original case - which never leaves the
     * temporary workspace - is what proves the resolver handles the real shape.
     * Nothing is written; the original is only read.
     *
     * @param caseDir   a {@code cases/<case>} directory written by
     *                  {@link ConflictFixtureWriter}
     * @param candidate the resolver under construction
     * @throws IllegalStateException when the case directory is incomplete
     */
    public static Reverification reverify(Path caseDir, ConflictResolver candidate) {
        Objects.requireNonNull(caseDir, "caseDir");
        Objects.requireNonNull(candidate, "candidate");

        String manifest = read(caseDir.resolve("conflict.json"));
        String typeName = readStringField(manifest, "conflictType");
        if (typeName == null || typeName.isBlank() || "UNCLASSIFIED".equals(typeName)) {
            throw new IllegalStateException(caseDir + " does not record a usable conflict type;"
                + " re-verification needs the type the case was prepared with");
        }
        ConflictType type = ConflictType.valueOf(typeName);
        String filePath = Optional.ofNullable(readStringField(manifest, "filePath"))
            .orElse("Conflicted.java");

        Path whole = caseDir.resolve("whole");
        String base = readWholeSide(whole.resolve("base"), true);
        String ours = readWholeSide(whole.resolve("ours"), false);
        String theirs = readWholeSide(whole.resolve("theirs"), false);

        Conflict conflict = new Conflict(type, filePath,
            "re-verification against the original fixture", base, ours, theirs,
            Region.unknown(), TypeContext.withRuntimeClasspath(whole));

        ConflictResolution gated =
            ResolutionVerifier.structural().apply(conflict, candidate.resolve(conflict));
        // The gate downgrades a failed AUTO to REVIEW; a downgraded answer is
        // not viable, so the FAILED verification is what decides, not the kind.
        boolean viable = (gated.getKind() == ConflictResolution.ResolutionKind.AUTO
            || gated.getKind() == ConflictResolution.ResolutionKind.REVIEW)
            && gated.getVerification() != ConflictResolution.Verification.FAILED
            && gated.getResolvedCode() != null
            && !gated.getResolvedCode().isBlank();
        return new Reverification(type, gated.getKind(), viable,
            gated.getResolvedCode(), gated.getExplanation());
    }

    private static String readWholeSide(Path sideDirectory, boolean optional) {
        if (!Files.isDirectory(sideDirectory)) {
            if (optional) {
                return "";
            }
            throw new IllegalStateException("the fixture case is incomplete: missing "
                + sideDirectory);
        }
        try (var stream = Files.list(sideDirectory)) {
            Optional<Path> file = stream
                .filter(path -> path.getFileName().toString().endsWith(".txt"))
                .sorted()
                .findFirst();
            if (file.isEmpty()) {
                if (optional) {
                    return "";
                }
                throw new IllegalStateException("the fixture case is incomplete: no source in "
                    + sideDirectory);
            }
            return Files.readString(file.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + sideDirectory, e);
        }
    }

    /**
     * Read one top-level string field from a small manifest this module wrote.
     * The format is fixed and owned here, so a targeted read is enough - no
     * JSON library is added to the build for it.
     */
    static String readStringField(String json, String field) {
        Matcher matcher = Pattern
            .compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    // --------------------------------------------------------------------- I/O

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    // --------------------------------------------------------------------- CLI

    /**
     * Run the tool from a console.
     *
     * <pre>
     * java -cp … com.codebuddy.merge.MergeFileTool &lt;file&gt; [--apply] [--apply-recorded]
     *      [--no-fixtures] [--fixtures &lt;dir&gt;] [--branch &lt;name&gt;]
     * </pre>
     */
    public static void main(String[] args) {
        System.exit(runMain(args));
    }

    /**
     * The body of {@link #main}, returning the exit code instead of forcing one
     * - directly testable, and usable from another entry point. Options may be
     * given before or after the file argument.
     */
    public static int runMain(String[] args) {
        Path file = null;
        List<String[]> options = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    printUsage(System.out);
                    return 0;
                }
                case "--apply", "--apply-recorded", "--no-fixtures" ->
                    options.add(new String[] {arg});
                case "--fixtures", "--branch", "--classpath", "--report", "--decisions", "--whitespace" -> {
                    if (i + 1 >= args.length) {
                        System.err.println(arg + " needs a value argument");
                        return 2;
                    }
                    options.add(new String[] {arg, args[++i]});
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("unknown option: " + arg);
                        printUsage(System.err);
                        return 2;
                    }
                    if (file != null) {
                        System.err.println("only one file argument is supported, got "
                            + file + " and " + arg);
                        return 2;
                    }
                    file = Path.of(arg);
                }
            }
        }
        if (file == null) {
            System.err.println("no file given");
            printUsage(System.err);
            return 2;
        }

        Builder builder = forFile(file);
        List<Path> classpath = new ArrayList<>();
        for (String[] option : options) {
            switch (option[0]) {
                case "--apply" -> builder.applyFixes(true);
                case "--apply-recorded" -> builder.applyRecordedDecisions(true);
                case "--no-fixtures" -> builder.prepareFixtures(false);
                case "--fixtures" -> builder.fixtureRoot(Path.of(option[1]));
                case "--branch" -> builder.branchName(option[1]);

                case "--report" -> builder.reportPath(Path.of(option[1]));

                case "--decisions" -> builder.decisionsFile(Path.of(option[1]));
                case "--whitespace" -> {
                    ComparisonPolicy policy = whitespacePolicyOf(option[1]);
                    if (policy == null) {
                        System.err.println("--whitespace must be default, trim or ignore, was: "
                            + option[1]);
                        return 2;
                    }
                    builder.whitespacePolicy(policy);
                }
                case "--classpath" -> {
                    // Checked here rather than left to the resolver: a misspelled entry
                    // contributes nothing to attribution, so the conflict would escalate
                    // with "could not be resolved" and look like a limitation of the tool
                    // instead of a typo. A classpath the caller asked for is a classpath
                    // that must exist.
                    for (String entry : option[1].split(java.util.regex.Pattern.quote(
                        java.io.File.pathSeparator))) {
                        if (entry.isBlank()) {
                            continue;
                        }
                        Path path = Path.of(entry.trim());
                        if (!Files.exists(path)) {
                            System.err.println("classpath entry does not exist: " + path);
                            return 2;
                        }
                        classpath.add(path);
                    }
                }
                default -> throw new IllegalStateException("unhandled option " + option[0]);
            }
        }
        builder.classpath(classpath);

        try {
            Result result = builder.run();
            System.out.println(result.describe());
            return result.exitCode();
        } catch (RuntimeException e) {
            System.err.println(e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage());
            return 2;
        }
    }

    /**
     * The policy a {@code --whitespace} value names, or {@code null} when it names none.
     *
     * <p>Accepting the three names rather than a number or an enum ordinal: a command line is read by
     * people, and {@code --whitespace=2} would make the caller look up which policy that is. The names are
     * the constants lowercased, and the two the codebase actually distinguishes are spelled the way the
     * text tier spells them.
     */
    static ComparisonPolicy whitespacePolicyOf(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "default" -> ComparisonPolicy.DEFAULT;
            case "trim", "trim_whitespaces" -> ComparisonPolicy.TRIM_WHITESPACES;
            case "ignore", "ignore_whitespaces" -> ComparisonPolicy.IGNORE_WHITESPACES;
            default -> null;
        };
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("""
            usage: MergeFileTool <file> [--apply] [--apply-recorded] [--no-fixtures]
                                 [--fixtures <dir>] [--branch <name>]
                                 [--classpath <entries>] [--report <path>] [--decisions <file.json>]

              <file>              a file carrying git conflict markers
              --apply             write the automatically resolved blocks back to the file
                                  (default: dry run, report only)
              --apply-recorded    also write blocks whose resolution was replayed from
                                  this branch's recorded decisions
              --no-fixtures       do not prepare the temporary fixture workspace for
                                  the conflicts that remain
              --fixtures <dir>    where fixture workspaces are created
                                  (default: %s)
              --branch <name>     the branch whose decision history is consulted
                                  (default: the repository's current branch)
              --classpath <entries>
                                  the project's compile classpath, so a conflict about
                                  its own types can be resolved instead of escalated.
                                  Entries are separated by '%s' and may repeat; each must
                                  exist. They are ADDED to the JVM classpath, which is
                                  what carries the platform: without this the resolver
                                  sees only the JVM classpath, which decides JDK types
                                  and escalates the project's own - with a warning
                                  saying so.

                --report <path>     write the report the review page renders for this
                                    file, so a reviewer sees the three sides of every
                                    block instead of a count. Render it with
                                    merge-java/review (no host, no server needed).
                --decisions <file.json>
                                    the decisions a reviewer exported from that page:
                                    they are recorded into this branch's history and
                                    then applied, because a reviewer who picked an
                                    answer wants it used. Implies --apply-recorded.
                                    The file is the page's own export format.

            exit status: 0 when no conflict block remains, 1 while the file still
            carries conflicts, 2 on a usage or input error.""".formatted(
                DEFAULT_FIXTURE_ROOT, java.io.File.pathSeparator));
    }
}
