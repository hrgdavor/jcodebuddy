package com.codebuddy.merge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records the decisions a reviewer accepted in the review page, so the next merge replays them as ordinary sticky
 * choices (plan step 4.3).
 *
 * <h3>Why a file and a command, rather than a button that saves</h3>
 * <p>The review page is one self-contained HTML file opened from {@code file://}, which cannot write anywhere. The
 * maintainer's answer on 2026-10-03 was to keep that separation: the page <em>exports</em> what was accepted, and
 * this command records it. It is also the smaller boundary — the page needs no host and no dependency, and the
 * exporter could be driven by one later without changing the format.</p>
 *
 * <h3>The format is the page's, and it is checked rather than trusted</h3>
 * <p>Each decision carries the conflict it answers (type, path, description and the three sides) plus what the
 * reviewer chose ({@code resolvedCode} and an {@code explanation}), and the {@code signature} the page displayed.
 * The signature is <strong>verified</strong> against the signature this side computes from the same sides: a
 * payload naming a key that does not match the conflict is refused with a problem rather than recorded against the
 * wrong conflict — the one failure that would make a recorded decision silently wrong.</p>
 *
 * <p>A recorded decision is always a sticky replay ({@link ConflictResolution.ResolutionStrategy#STICKY_REPLAY},
 * kind {@code DEFERRED}, {@code sticky(true)}): that is what "remember this choice" means in this module, and the
 * resolver's own replay path then answers the same conflict with it on the next update.</p>
 */
public final class DecisionRecorder {

    /** The exported format's version, so a future change can be refused rather than misread. */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionRecorder() {
    }

    /**
     * What a recording run did.
     *
     * @param recorded how many decisions reached the store
     * @param problems what could not be recorded, each naming the reason (never silently dropped)
     */
    public record Result(int recorded, int rejected, List<String> problems) {

        public Result {
            problems = List.copyOf(problems);
        }

        public boolean isEmpty() {
            return recorded == 0 && rejected == 0;
        }

        public String describe() {
            return recorded + " decision(s) recorded"
                + (problems.isEmpty() ? "" : ", " + problems.size() + " refused: " + String.join("; ", problems));
        }
    }

    /**
     * Record every decision in an exported file, into the store for {@code branchName}.
     *
     * <p><strong>{@code historyRoot} is the BRANCH's directory</strong>, not the root above it: decisions land in
     * {@code <historyRoot>/decisions/}, and the same path is what {@link MergeConflictResolver.Builder#setHistoryPath}
     * is given, so a decision recorded here is found by the next resolve. Passing the parent by mistake is silent —
     * the write succeeds and the replay never finds it — which is why the CLI prints the absolute path it used.</p>
     *
     * @throws IOException if the decisions file cannot be read, or is not the expected JSON
     */
    public static Result record(Path decisionsFile, Path historyRoot, String branchName) throws IOException {
        Objects.requireNonNull(decisionsFile, "decisionsFile");
        Objects.requireNonNull(historyRoot, "historyRoot");
        Objects.requireNonNull(branchName, "branchName");

        JsonNode document = MAPPER.readTree(Files.readString(decisionsFile, StandardCharsets.UTF_8));
        int schemaVersion = document.path("schemaVersion").asInt(0);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException("unsupported decisions schemaVersion " + schemaVersion
                + " (this build understands " + SCHEMA_VERSION + ")");
        }
        // The file's own branch is a cross-check, not the authority: the caller names the branch to record into,
        // because a reviewer may export from one checkout and record into another.
        String declaredBranch = document.path("branchName").asString("");
        List<String> problems = new ArrayList<>();
        if (!declaredBranch.isEmpty() && !declaredBranch.equals(branchName)) {
            problems.add("the file was exported for branch '" + declaredBranch + "' but recording into '"
                + branchName + "'");
        }

        BranchConflictStore store = new BranchConflictStore(branchName, historyRoot);
        int recorded = 0;
        for (JsonNode decision : document.path("decisions")) {
            String problem = recordOne(store, decision);
            if (problem == null) {
                recorded++;
            } else {
                problems.add(problem);
            }
        }
        int refused = 0;
        for (JsonNode rejection : document.path("rejected")) {
            String problem = recordRejection(store, rejection);
            if (problem == null) {
                refused++;
            } else {
                problems.add(problem);
            }
        }
        return new Result(recorded, refused, problems);
    }

    /**
     * Record one refusal, so the next run does not offer that answer again (plan step 4.17).
     *
     * <p>The provenance is part of the key and must be matchable, which is why an empty one is refused rather
     * than stored: a refusal that cannot say <em>which</em> answer was refused would suppress the next producer's
     * answer for the same conflict, which is the nagging problem inverted.
     */
    private static String recordRejection(BranchConflictStore store, JsonNode rejection) {
        String signature = rejection.path("signature").asString("");
        String filePath = rejection.path("filePath").asString("<unknown>");
        String provenance = rejection.path("provenance").asString("");
        if (signature.isBlank()) {
            return "a refusal with no signature cannot say which conflict it is about" + at(filePath);
        }
        if (provenance.isBlank()) {
            return "a refusal with no provenance cannot say which answer was refused" + at(filePath);
        }
        // Keyed by the signature the page showed, and the sides are not needed: a refusal is about an answer
        // rather than about reproducing a conflict, so rebuilding the key from sides the entry does not carry
        // would file it under a name the resolver never computes.
        store.recordRejection(signature, provenance);
        return null;
    }

    /** @return a problem description, or {@code null} when the decision was recorded */
    private static String recordOne(BranchConflictStore store, JsonNode decision) {
        String signature = decision.path("signature").asString("");
        String typeText = decision.path("type").asString("");
        ConflictType type;
        try {
            type = ConflictType.valueOf(typeText);
        } catch (IllegalArgumentException unknown) {
            return "unknown conflict type '" + typeText + "'" + at(signature);
        }
        String filePath = decision.path("filePath").asString("<unknown>");
        String base = decision.path("base").asString("");
        String branch1 = decision.path("branch1").asString("");
        String branch2 = decision.path("branch2").asString("");
        String resolvedCode = decision.path("resolvedCode").asString("");

        Conflict conflict = new Conflict(type, filePath, decision.path("description").asString(""),
            base, branch1, branch2);
        String ownSignature = ConflictSignature.of(conflict).toFileName();
        if (!signature.isEmpty() && !signature.equals(ownSignature)) {
            return "the key the page showed (" + signature + ") is not this conflict's key (" + ownSignature
                + ") for " + filePath;
        }
        ConflictResolution recorded = ConflictResolution.builder()
            .filePath(filePath)
            .type(type)
            .baseCode(base)
            .branch1Code(branch1)
            .branch2Code(branch2)
            .resolvedCode(resolvedCode)
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .sticky(true)
            .explanation(decision.path("explanation").asString("accepted in the review page"))
            .branchName(store.getBranchName())
            .build();
        store.record(conflict, recorded);
        return null;
    }

    private static String at(String signature) {
        return signature.isEmpty() ? "" : " (" + signature + ")";
    }

    /**
     * The command line, for the flow the review page documents.
     *
     * <pre>
     *   --decisions &lt;file.json&gt;   what the page exported (required)
     *   --history &lt;dir&gt;           the branch's history root, holding its decisions (required)
     *   --branch &lt;name&gt;           the branch to record into (required)
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        Path decisions = null;
        Path history = null;
        String branch = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--decisions" -> decisions = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--history" -> history = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--branch" -> branch = i + 1 < args.length ? args[++i] : null;
                default -> System.err.println("[record-decisions] unknown argument: " + args[i]);
            }
        }
        if (decisions == null || history == null || branch == null) {
            System.err.println("Usage: record-decisions --decisions <file.json> --history <branchDir> --branch <name>");
            System.exit(2);
            return;
        }
        try {
            Result result = record(decisions, history, branch);
            // The absolute path is printed because the branch directory is the part a caller can get wrong
            // silently: recording one level up writes fine and is never replayed.
            System.out.println("[record-decisions] " + result.describe()
                + " into " + history.toAbsolutePath() + "/" + BranchConflictStore.DECISIONS_DIR);
            result.problems().forEach(problem -> System.out.println("  refused: " + problem));
            System.exit(result.isEmpty() ? 1 : 0);
        } catch (IOException | RuntimeException failure) {
            System.err.println("[record-decisions] " + failure.getMessage());
            System.exit(1);
        }
    }

    /** Read a decisions file without recording it, for a caller that wants to validate a page's export first. */
    public static JsonNode read(Path decisionsFile) {
        try {
            return MAPPER.readTree(Files.readString(decisionsFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + decisionsFile, e);
        }
    }
}
