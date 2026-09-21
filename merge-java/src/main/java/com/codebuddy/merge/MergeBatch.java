// {@link com.codebuddy.merge.MergeBatch} Resolves many files in one pass.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a set of conflicting files in one pass.
 *
 * <p>One commit's merge is a handful of files, but a long-lived branch catching
 * up after a large rebase can conflict in hundreds, and resolving them one at a
 * time hides the shape of the problem. This facade produces a per-file report plus
 * aggregate counts, and offers a dry run so a caller can see what <em>would</em>
 * happen before anything is written.
 *
 * <p>Ordering is deterministic - files are processed in the order given and the
 * aggregate output is sorted - so a CI log diffs cleanly between runs.
 */
public final class MergeBatch {

    /**
     * The resolver used to resolve files, or {@code null} for a batch assembled
     * from reports that were already produced.
     *
     * <p>A batch built by {@link #of} only aggregates; it never resolves, so it has
     * no resolver and does not need one. The resolving entry points
     * ({@link #add}) require one and say so.
     */
    private final MergeConflictResolver resolver;
    private final boolean dryRun;
    private final List<MergeConflictResolver.MergeReport> reports = new ArrayList<>();

    private MergeBatch(MergeConflictResolver resolver, boolean dryRun) {
        this.resolver = resolver;
        this.dryRun = dryRun;
    }

    /**
     * Create a batch that resolves through the given resolver.
     *
     * @param dryRun when true nothing is written; the reports are still produced
     */
    public static MergeBatch using(MergeConflictResolver resolver, boolean dryRun) {
        return new MergeBatch(Objects.requireNonNull(resolver, "resolver"), dryRun);
    }

    public static MergeBatch using(MergeConflictResolver resolver) {
        return new MergeBatch(Objects.requireNonNull(resolver, "resolver"), true);
    }

    /**
     * The resolver this batch resolves through.
     *
     * @throws IllegalStateException when the batch was assembled from existing
     *                               reports and therefore has no resolver
     */
    private MergeConflictResolver requireResolver() {
        if (resolver == null) {
            throw new IllegalStateException("this batch was assembled from existing reports, "
                + "so it can only aggregate them; use MergeBatch.using(resolver, dryRun) to "
                + "resolve files");
        }
        return resolver;
    }

    /**
     * Resolve one file whose three versions are on disk.
     *
     * <p>In a non-dry run, the independently applicable automatic resolutions are
     * written back to the file's path. Manual and review conflicts are never
     * written: the file keeps its conflict markers so a human sees them.
     */
    public MergeBatch add(Path filePath, Path basePath, Path branch1Path, Path branch2Path) {
        MergeConflictResolver.MergeReport report =
            requireResolver().resolveFiles(filePath, basePath, branch1Path, branch2Path);
        reports.add(report);
        if (!dryRun) {
            writeApplicable(report);
        }
        return this;
    }

    /**
     * Resolve one file whose three versions are already in memory. Nothing can be
     * written in this form, so it is inherently a dry run for that file.
     */
    public MergeBatch add(String filePath, String baseCode,
                          String branch1Code, String branch2Code) {
        reports.add(requireResolver().resolve(filePath, baseCode, branch1Code, branch2Code));
        return this;
    }

    /**
     * Create a batch from reports that have already been produced.
     *
     * <p>Lets a caller that did its own I/O - the JGit workflow reads through the
     * object database rather than from files - reuse the same aggregate accounting
     * and summary text instead of duplicating it.
     */
    /**
     * Create a batch from reports that have already been produced.
     *
     * <p>Lets a caller that did its own I/O - the JGit workflow reads through the
     * object database rather than from files - reuse the same aggregate accounting
     * and summary text instead of duplicating it. No resolver is needed, because
     * aggregating finished reports involves no resolution.
     */
    public static MergeBatch of(List<MergeConflictResolver.MergeReport> reports, boolean dryRun) {
        MergeBatch batch = new MergeBatch(null, dryRun);
        batch.reports.addAll(Objects.requireNonNull(reports, "reports"));
        return batch;
    }

    /**
     * Every file's report, in the order added.
     */
    public List<MergeConflictResolver.MergeReport> getReports() {
        return Collections.unmodifiableList(reports);
    }

    /**
     * True when the batch wrote nothing.
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Ask the batch to apply only the automatic subset of a report, re-deriving
     * which resolutions are independently applicable.
     */
    public void apply(MergeConflictResolver.MergeReport report) {
        writeApplicable(report);
    }

    private void writeApplicable(MergeConflictResolver.MergeReport report) {
        List<ConflictResolution> applicable = report.getIndependentlyApplicable();
        if (applicable.isEmpty()) {
            return;
        }
        Path target = Path.of(report.getFilePath());
        try {
            if (!Files.isRegularFile(target)) {
                // Nothing to rewrite: the caller passed an in-memory path.
                return;
            }
            String original = Files.readString(target, StandardCharsets.UTF_8);
            Files.writeString(target, applyTo(original, applicable), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the resolution to " + target, e);
        }
    }

    /**
     * Apply the resolutions to the file's text.
     *
     * <p>For an import conflict the resolution supersedes the file's import block;
     * for other automatic types the resolved code is appended where the original
     * region was not preserved. This is deliberately conservative: a resolution
     * whose region is unknown never reaches here, because
     * {@link MergeConflictResolver.MergeReport#getIndependentlyApplicable()}
     * excludes it.
     */
    static String applyTo(String original, List<ConflictResolution> applicable) {
        String result = original;
        for (ConflictResolution resolution : applicable) {
            String existing = findExistingCode(result, resolution.getBaseCode());
            if (existing != null && !existing.isBlank()) {
                result = result.replace(existing, resolution.getResolvedCode());
            } else if (resolution.getType() == ConflictType.IMPORT_ADD) {
                result = insertImports(result, resolution.getResolvedCode());
            }
        }
        return result;
    }

    /**
     * The base code a resolution supersedes, if it is still present.
     */
    private static String findExistingCode(String text, String baseCode) {
        if (baseCode == null || baseCode.isBlank() || !text.contains(baseCode)) {
            return null;
        }
        return baseCode;
    }

    /**
     * Insert resolved imports after the last existing import line, or at the top
     * of the file when there is none.
     */
    private static String insertImports(String text, String resolvedImports) {
        String[] lines = text.split("\n", -1);
        int lastImport = -1;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().startsWith("import ")) {
                lastImport = index;
            }
        }

        List<String> merged = new ArrayList<>();
        for (String line : resolvedImports.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && linesContain(lines, trimmed)) {
                continue;
            }
            if (!trimmed.isEmpty()) {
                merged.add(trimmed);
            }
        }
        if (merged.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        int insertAt = lastImport + 1;
        for (int index = 0; index < lines.length; index++) {
            if (index == insertAt) {
                merged.forEach(line -> result.append(line).append('\n'));
            }
            result.append(lines[index]);
            if (index < lines.length - 1) {
                result.append('\n');
            }
        }
        if (insertAt >= lines.length) {
            merged.forEach(line -> result.append(line).append('\n'));
        }
        return result.toString();
    }

    private static boolean linesContain(String[] lines, String candidate) {
        for (String line : lines) {
            if (line.trim().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aggregate counts across every file in the batch.
     */
    public Summary summarize() {
        int files = reports.size();
        int conflicts = 0;
        int auto = 0;
        int applicable = 0;
        int review = 0;
        int manual = 0;
        int replayed = 0;
        int clean = 0;

        for (MergeConflictResolver.MergeReport report : reports) {
            if (report.isClean()) {
                clean++;
                continue;
            }
            conflicts += report.getConflicts().size();
            auto += report.getAutoResolutions().size();
            applicable += report.getIndependentlyApplicable().size();
            review += report.getReviewResolutions().size();
            manual += report.getManualResolutions().size();
            replayed += report.getDeferredResolutions().size();
        }

        return new Summary(files, clean, conflicts, auto, applicable, review, manual, replayed,
            dryRun);
    }

    /**
     * Counts across a batch, plus the files that still need attention.
     */
    public record Summary(int files, int cleanFiles, int conflicts, int autoResolutions,
                          int applicableResolutions, int reviewResolutions, int manualResolutions,
                          int replayedDecisions, boolean dryRun) {

        /**
         * True when nothing is left for a human.
         */
        public boolean isFullyResolved() {
            return reviewResolutions == 0 && manualResolutions == 0;
        }

        /**
         * A multi-line report suitable for a CI log.
         */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append(files).append(" file(s) examined")
                .append(dryRun ? " [dry run, nothing written]" : "")
                .append('\n');
            text.append("  clean:                  ").append(cleanFiles).append('\n');
            text.append("  conflicts:              ").append(conflicts).append('\n');
            text.append("  auto (applicable):      ").append(autoResolutions)
                .append(" (").append(applicableResolutions).append(")\n");
            text.append("  need review:            ").append(reviewResolutions).append('\n');
            text.append("  need a human:           ").append(manualResolutions).append('\n');
            text.append("  decisions replayed:     ").append(replayedDecisions);
            return text.toString();
        }

        /**
         * The exit status a CI job should use: non-zero when a human is needed.
         */
        public int exitCode() {
            return isFullyResolved() ? 0 : 1;
        }
    }

    /**
     * The files that still have review or manual conflicts, for reporting.
     */
    public List<String> filesNeedingAttention() {
        List<String> paths = new ArrayList<>();
        for (MergeConflictResolver.MergeReport report : reports) {
            if (!report.getReviewResolutions().isEmpty()
                || !report.getManualResolutions().isEmpty()) {
                paths.add(report.getFilePath());
            }
        }
        Collections.sort(paths);
        return paths;
    }

    /**
     * The first report for a path, if present.
     */
    public Optional<MergeConflictResolver.MergeReport> reportFor(String filePath) {
        return reports.stream()
            .filter(report -> report.getFilePath().equals(filePath))
            .findFirst();
    }
}
