// {@link com.codebuddy.merge.ConflictFixtureWriter} Writes an unresolved conflict into a temporary fixture workspace.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepares the temporary fixture workspace {@link MergeFileTool} leaves behind
 * for every conflict the existing resolvers could not fix.
 *
 * <h2>The workspace is proprietary by nature</h2>
 *
 * <p>It is written from a user's real conflict, so it contains the user's real
 * code. Three properties keep that safe:
 *
 * <ul>
 *   <li>it lives in a temporary directory - outside the tool's codebase and
 *       outside version control by default;</li>
 *   <li>it carries a {@code .gitignore} with {@code *} in it, so even a
 *       workspace deliberately placed inside a repository cannot be committed
 *       by accident;</li>
 *   <li>it carries {@value FixtureAgentInstructions#FILE_NAME}, the
 *       instructions that bind any agent working in it: anonymize first, build
 *       the resolver only from the anonymized fixture, re-verify on the original
 *       through {@link MergeFileTool#reverify}, and never let the original
 *       content reach the repository.</li>
 * </ul>
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * &lt;fixtureRoot&gt;/merge-java-&lt;timestamp&gt;-&lt;hash&gt;/
 *   AGENTS.md                  the copied instructions
 *   .gitignore                 '*'
 *   run.json                   the facts of the run
 *   source/
 *     conflicted.java.txt      the file as received, markers included
 *     ours.java.txt            whole ours version, reconstructed
 *     theirs.java.txt          whole theirs version, reconstructed
 *     base.java.txt            whole base version - only when a real base was known
 *     ours.diff / theirs.diff  unified diffs against base - only when base was known
 *   cases/&lt;case&gt;/
 *     conflict.json            type, handling, marker lines, resolver verdicts, fix paths
 *     block/                   the exact conflict block and its sides
 *     whole/                   ThreeWayFixture-shaped copies: base/, ours/, theirs/
 * </pre>
 *
 * <p>The {@code whole/} copies follow {@code docs/THREE_WAY_FIXTURES.md} - the
 * same layout the module's test fixtures use - so an anonymized case can be
 * dropped into {@code src/test/resources/fixtures/} without restructuring.
 *
 * <p>All JSON here is written by hand, matching {@link MergeReportWriter}: the
 * module owns its model, a renderer (or an agent) owns presentation, and no
 * JSON library is added to the build for two small manifests.
 */
public final class ConflictFixtureWriter {

    /** The ignore file written into every workspace, so it can never be committed. */
    static final String GITIGNORE_CONTENT = "*\n";

    /**
     * Diffs are documentation, never input (THREE_WAY_FIXTURES.md §2), and the
     * quadratic LCS table is not worth its memory on huge files: past this many
     * table cells the diff is omitted rather than computed.
     */
    static final long MAX_DIFF_CELLS = 4_000_000L;

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private ConflictFixtureWriter() {
    }

    /**
     * The facts of one tool run, written as {@code run.json} and as the
     * {@code source/} files.
     *
     * @param baseSource where the whole-file base came from: {@code "diff3"},
     *                   {@code "git-index-stage-1"} or {@code "none"}
     * @param baseText   the whole base version, or {@code null} when unknown
     */
    public record RunManifest(Path sourceFile, String reportedPath, String branchName,
                              String baseSource, boolean repositoryFound, Path repositoryRoot,
                              boolean dryRun, boolean fixesApplied,
                              int totalBlocks, int appliedBlocks, int leftBlocks,
                              String conflictedText, String oursText, String theirsText,
                              String baseText, Instant generatedAt) {

        boolean hasBase() {
            return baseText != null;
        }
    }

    /**
     * One unresolved conflict, written as one {@code cases/<caseName>/} folder.
     *
     * @param conflictType the detected type, or {@code null} when detection
     *                     produced nothing for the block
     * @param blockBase    the block's diff3 base side, or {@code null}
     */
    public record FixtureCase(String caseName, String filePath, ConflictType conflictType,
                              String description, int startLine, int endLine, String outcome,
                              String signature, List<ConflictResolution> resolutions,
                              String blockRaw, String blockBase, String blockOurs,
                              String blockTheirs) {

        public FixtureCase {
            Objects.requireNonNull(caseName, "caseName");
            resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
        }
    }

    /**
     * Write a complete workspace and return its root directory.
     *
     * <p>Creates the run directory under {@code fixtureRoot}; the name carries a
     * timestamp and a short hash so repeated runs on the same file accumulate
     * side by side instead of overwriting each other.
     */
    public static Path writeRun(Path fixtureRoot, RunManifest run, List<FixtureCase> cases) {
        Objects.requireNonNull(fixtureRoot, "fixtureRoot");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(cases, "cases");

        Path runDir = fixtureRoot.resolve(runDirName(run));
        try {
            Files.createDirectories(runDir);
            write(runDir.resolve(".gitignore"), GITIGNORE_CONTENT);
            FixtureAgentInstructions.writeTo(runDir);
            writeSource(runDir, run);
            for (FixtureCase fixtureCase : cases) {
                writeCase(runDir, run, fixtureCase);
            }
            write(runDir.resolve("run.json"), runJson(run, cases));
            return runDir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the fixture workspace under "
                + runDir, e);
        }
    }

    /**
     * The directory name for a run: timestamped, with a short hash of the path
     * and the instant so two runs never collide.
     */
    static String runDirName(RunManifest run) {
        String identity = run.reportedPath() + "|" + run.generatedAt().toEpochMilli()
            + "|" + run.generatedAt().getNano();
        return "merge-java-" + STAMP.format(run.generatedAt())
            + "-" + sha256Hex(identity).substring(0, 8);
    }

    // ------------------------------------------------------------------ source

    private static void writeSource(Path runDir, RunManifest run) throws IOException {
        Path source = runDir.resolve("source");
        Files.createDirectories(source);
        write(source.resolve("conflicted.java.txt"), run.conflictedText());
        write(source.resolve("ours.java.txt"), run.oursText());
        write(source.resolve("theirs.java.txt"), run.theirsText());
        if (run.hasBase()) {
            write(source.resolve("base.java.txt"), run.baseText());
            writeDiffs(source, run.baseText(), run.oursText(), run.theirsText(),
                "base.java.txt", "ours.java.txt", "theirs.java.txt");
        }
    }

    // ------------------------------------------------------------------- cases

    private static void writeCase(Path runDir, RunManifest run, FixtureCase fixtureCase)
        throws IOException {
        Path caseDir = runDir.resolve("cases").resolve(fixtureCase.caseName());
        Files.createDirectories(caseDir);

        Path block = caseDir.resolve("block");
        Files.createDirectories(block);
        write(block.resolve("conflicted.txt"), fixtureCase.blockRaw());
        write(block.resolve("ours.java.txt"), fixtureCase.blockOurs());
        write(block.resolve("theirs.java.txt"), fixtureCase.blockTheirs());
        if (fixtureCase.blockBase() != null) {
            write(block.resolve("base.java.txt"), fixtureCase.blockBase());
        }

        String fileName = simpleFileName(fixtureCase.filePath());
        Path whole = caseDir.resolve("whole");
        Files.createDirectories(whole.resolve("ours"));
        Files.createDirectories(whole.resolve("theirs"));
        write(whole.resolve("ours").resolve(fileName), run.oursText());
        write(whole.resolve("theirs").resolve(fileName), run.theirsText());
        if (run.hasBase()) {
            Files.createDirectories(whole.resolve("base"));
            write(whole.resolve("base").resolve(fileName), run.baseText());
            writeDiffs(whole, run.baseText(), run.oursText(), run.theirsText(),
                "base/" + fileName, "ours/" + fileName, "theirs/" + fileName);
        }

        write(caseDir.resolve("conflict.json"), caseJson(run, fixtureCase));
    }

    private static void writeDiffs(Path directory, String base, String ours, String theirs,
                                   String baseLabel, String oursLabel, String theirsLabel)
        throws IOException {
        Optional<String> oursDiff = unifiedDiff(base, ours, baseLabel, oursLabel);
        if (oursDiff.isPresent()) {
            write(directory.resolve("ours.diff"), oursDiff.get());
        }
        Optional<String> theirsDiff = unifiedDiff(base, theirs, baseLabel, theirsLabel);
        if (theirsDiff.isPresent()) {
            write(directory.resolve("theirs.diff"), theirsDiff.get());
        }
    }

    /**
     * The last segment of a reported path, with the {@code .txt} suffix the
     * fixture layout uses to keep sources out of compilation.
     */
    static String simpleFileName(String filePath) {
        String normalised = filePath == null ? "Conflicted.java" : filePath.replace('\\', '/');
        int slash = normalised.lastIndexOf('/');
        String name = slash < 0 ? normalised : normalised.substring(slash + 1);
        return name.isEmpty() ? "Conflicted.java.txt" : name + ".txt";
    }

    // --------------------------------------------------------------- run.json

    private static String runJson(RunManifest run, List<FixtureCase> cases) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"tool\": \"merge-java MergeFileTool\",\n");
        json.append("  \"generatedAt\": ").append(quote(run.generatedAt().toString())).append(",\n");
        json.append("  \"sourceFile\": ").append(quote(String.valueOf(run.sourceFile()))).append(",\n");
        json.append("  \"reportedPath\": ").append(quote(run.reportedPath())).append(",\n");
        json.append("  \"branchName\": ").append(quote(run.branchName())).append(",\n");
        json.append("  \"repository\": {\"found\": ").append(run.repositoryFound());
        if (run.repositoryRoot() != null) {
            json.append(", \"root\": ").append(quote(String.valueOf(run.repositoryRoot())));
        }
        json.append("},\n");
        json.append("  \"baseSource\": ").append(quote(run.baseSource())).append(",\n");
        json.append("  \"dryRun\": ").append(run.dryRun()).append(",\n");
        json.append("  \"fixesApplied\": ").append(run.fixesApplied()).append(",\n");
        json.append("  \"counts\": {\"blocks\": ").append(run.totalBlocks())
            .append(", \"applied\": ").append(run.appliedBlocks())
            .append(", \"left\": ").append(run.leftBlocks())
            .append(", \"cases\": ").append(cases.size()).append("},\n");
        json.append("  \"cases\": [");
        for (int i = 0; i < cases.size(); i++) {
            FixtureCase fixtureCase = cases.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    {\"case\": ").append(quote(fixtureCase.caseName()))
                .append(", \"conflictType\": ").append(quote(typeName(fixtureCase)))
                .append(", \"markerLines\": ")
                .append(fixtureCase.startLine()).append('-').append(fixtureCase.endLine())
                .append(", \"outcome\": ").append(quote(fixtureCase.outcome())).append('}');
        }
        json.append(cases.isEmpty() ? "]\n" : "\n  ],\n");
        json.append("  \"privacy\": {\n");
        json.append("    \"original\": ").append(quote(
            "source/ and cases/ hold proprietary user code; they must never enter a "
                + "repository - see AGENTS.md")).append(",\n");
        json.append("    \"anonymized\": ").append(quote(
            "anonymized/ (created by the agent following AGENTS.md) is the only part "
                + "that may be copied into merge-java")).append("\n");
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static String typeName(FixtureCase fixtureCase) {
        return fixtureCase.conflictType() == null
            ? "UNCLASSIFIED"
            : fixtureCase.conflictType().name();
    }

    // ----------------------------------------------------------- conflict.json

    private static String caseJson(RunManifest run, FixtureCase fixtureCase) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"case\": ").append(quote(fixtureCase.caseName())).append(",\n");
        json.append("  \"filePath\": ").append(quote(fixtureCase.filePath())).append(",\n");
        json.append("  \"conflictType\": ").append(quote(typeName(fixtureCase))).append(",\n");
        if (fixtureCase.conflictType() != null) {
            json.append("  \"handling\": ")
                .append(quote(fixtureCase.conflictType().handling().name())).append(",\n");
        }
        json.append("  \"description\": ").append(quote(fixtureCase.description())).append(",\n");
        json.append("  \"markerLines\": {\"start\": ").append(fixtureCase.startLine())
            .append(", \"end\": ").append(fixtureCase.endLine()).append("},\n");
        json.append("  \"signature\": ").append(quote(fixtureCase.signature())).append(",\n");
        json.append("  \"outcome\": ").append(quote(fixtureCase.outcome())).append(",\n");
        json.append("  \"blockBase\": ").append(quote(fixtureCase.blockBase() == null
            ? "none" : "diff3")).append(",\n");
        json.append("  \"runBaseSource\": ").append(quote(run.baseSource())).append(",\n");
        json.append("  \"branchName\": ").append(quote(run.branchName())).append(",\n");
        json.append("  \"resolutions\": [");
        List<ConflictResolution> resolutions = fixtureCase.resolutions();
        for (int i = 0; i < resolutions.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('\n').append(resolutionJson(resolutions.get(i)));
        }
        json.append(resolutions.isEmpty() ? "],\n" : "\n  ],\n");
        json.append("  \"nextStep\": ").append(quote("Read ../../AGENTS.md. Anonymize this case "
            + "into ../../anonymized/<shape-name>/ first; only the anonymized fixture may "
            + "enter the merge-java repository.")).append('\n');
        json.append("}\n");
        return json.toString();
    }

    private static String resolutionJson(ConflictResolution resolution) {
        StringBuilder json = new StringBuilder();
        json.append("    {\n");
        json.append("      \"type\": ").append(quote(String.valueOf(resolution.getType()))).append(",\n");
        json.append("      \"kind\": ").append(quote(String.valueOf(resolution.getKind()))).append(",\n");
        json.append("      \"strategy\": ")
            .append(quote(String.valueOf(resolution.getResolutionStrategy()))).append(",\n");
        json.append("      \"verification\": ")
            .append(quote(String.valueOf(resolution.getVerification()))).append(",\n");
        json.append("      \"sticky\": ").append(resolution.isSticky()).append(",\n");
        json.append("      \"explanation\": ").append(quote(resolution.getExplanation())).append(",\n");
        if (resolution.getResolvedCode() != null) {
            json.append("      \"resolvedCode\": ").append(quote(resolution.getResolvedCode()))
                .append(",\n");
        }
        json.append("      \"fixPaths\": [");
        List<FixPath> paths = resolution.getAlternativePaths();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('\n').append(fixPathJson(paths.get(i)));
        }
        json.append(paths.isEmpty() ? "]\n" : "\n      ]\n");
        json.append("    }");
        return json.toString();
    }

    private static String fixPathJson(FixPath path) {
        StringBuilder json = new StringBuilder();
        json.append("        {\"description\": ").append(quote(path.getDescription()))
            .append(", \"options\": [");
        List<String> options = path.getOptions();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append(quote(options.get(i)));
        }
        json.append("], \"recommended\": ").append(quote(path.getRecommended()))
            .append(", \"justification\": ").append(quote(path.getJustification()))
            .append(", \"impact\": ").append(quote(path.getImpact())).append('}');
        return json.toString();
    }

    // -------------------------------------------------------------- unified diff

    /**
     * A real unified diff between two texts, for the human-readable
     * {@code ours.diff}/{@code theirs.diff} documentation the fixture layout
     * prescribes. Diffs are documentation, never input - nothing in the module
     * parses them back.
     *
     * @return the diff, or empty when the texts are equal or too large for the
     *         quadratic LCS table ({@link #MAX_DIFF_CELLS})
     */
    public static Optional<String> unifiedDiff(String baseText, String modifiedText,
                                               String baseLabel, String modifiedLabel) {
        List<String> a = diffLines(baseText);
        List<String> b = diffLines(modifiedText);
        if (a.equals(b)) {
            return Optional.empty();
        }
        if ((long) (a.size() + 1) * (b.size() + 1) > MAX_DIFF_CELLS) {
            return Optional.empty();
        }

        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                    ? lcs[i + 1][j + 1] + 1
                    : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        // Walk the table into an edit script: 'E' equal, 'D' delete from base,
        // 'I' insert from modified.
        List<Character> kinds = new ArrayList<>();
        List<Integer> aIndex = new ArrayList<>();
        List<Integer> bIndex = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                kinds.add('E');
                aIndex.add(i);
                bIndex.add(j);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                kinds.add('D');
                aIndex.add(i);
                bIndex.add(j);
                i++;
            } else {
                kinds.add('I');
                aIndex.add(i);
                bIndex.add(j);
                j++;
            }
        }
        while (i < a.size()) {
            kinds.add('D');
            aIndex.add(i);
            bIndex.add(j);
            i++;
        }
        while (j < b.size()) {
            kinds.add('I');
            aIndex.add(i);
            bIndex.add(j);
            j++;
        }

        String hunks = renderHunks(a, b, kinds, aIndex, bIndex);
        if (hunks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("--- " + baseLabel + "\n+++ " + modifiedLabel + "\n" + hunks);
    }

    private static final int CONTEXT = 3;

    private static String renderHunks(List<String> a, List<String> b, List<Character> kinds,
                                      List<Integer> aIndex, List<Integer> bIndex) {
        StringBuilder hunks = new StringBuilder();
        int position = 0;
        while (position < kinds.size()) {
            if (kinds.get(position) == 'E') {
                position++;
                continue;
            }
            int start = Math.max(0, position - CONTEXT);
            int lastChange = position;
            int scan = position;
            while (scan < kinds.size()) {
                if (kinds.get(scan) != 'E') {
                    lastChange = scan;
                } else if (scan - lastChange > 2 * CONTEXT) {
                    break;
                }
                scan++;
            }
            int end = Math.min(kinds.size() - 1, lastChange + CONTEXT);

            // Unified-diff range semantics: a side counts only 'E'/'D' ops and
            // starts at the first of them; a hunk of pure inserts gets a
            // zero-length a range anchored at the line before the insertion.
            int aStart = -1;
            int bStart = -1;
            int aCount = 0;
            int bCount = 0;
            StringBuilder body = new StringBuilder();
            for (int k = start; k <= end; k++) {
                char kind = kinds.get(k);
                if (kind == 'E' || kind == 'D') {
                    if (aStart < 0) {
                        aStart = aIndex.get(k) + 1;
                    }
                    aCount++;
                } else if (aStart < 0) {
                    aStart = aIndex.get(k);
                }
                if (kind == 'E' || kind == 'I') {
                    if (bStart < 0) {
                        bStart = bIndex.get(k) + 1;
                    }
                    bCount++;
                } else if (bStart < 0) {
                    bStart = bIndex.get(k);
                }
                switch (kind) {
                    case 'E' -> body.append(' ').append(a.get(aIndex.get(k))).append('\n');
                    case 'D' -> body.append('-').append(a.get(aIndex.get(k))).append('\n');
                    case 'I' -> body.append('+').append(b.get(bIndex.get(k))).append('\n');
                    default -> throw new IllegalStateException("unknown edit kind " + kind);
                }
            }
            hunks.append("@@ -").append(Math.max(aStart, 0)).append(',').append(aCount)
                .append(" +").append(Math.max(bStart, 1)).append(',').append(bCount)
                .append(" @@\n")
                .append(body);
            position = end + 1;
        }
        return hunks.toString();
    }

    private static List<String> diffLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String[] parts = text.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    // ------------------------------------------------------------------ plumbing

    private static void write(Path target, String content) throws IOException {
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    /**
     * JSON string quoting with the escapes the manifests need. Matches the
     * hand-rolled style of {@link MergeReportWriter}.
     */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
