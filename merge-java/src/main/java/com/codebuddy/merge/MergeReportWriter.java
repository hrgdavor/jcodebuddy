// {@link com.codebuddy.merge.MergeReportWriter} Writes resolution metadata as JSON for a renderer.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialises resolution metadata to JSON so a renderer can present it.
 *
 * <p>Follows this repository's reporting split (DEC-027/029): Java owns the model
 * and writes the facts, a Bun script owns presentation and renders one
 * self-contained HTML file from this JSON. The Java side therefore emits data
 * only - never markup - so adding a fact to the report means adding it here, and
 * the renderer never has to parse Java.
 *
 * <p>Source text is included because a reviewer needs to see the conflicting code;
 * everything else is metadata.
 */
public final class MergeReportWriter {

    private MergeReportWriter() {
    }

    /**
     * Write the metadata for a batch and return the path written.
     */
    public static Path write(Path target, MergeBatch batch) {
        return write(target, batch.getReports(), batch.summarize());
    }

    /**
     * Write the metadata for a set of file reports.
     */
    public static Path write(Path target, List<MergeConflictResolver.MergeReport> reports,
                             MergeBatch.Summary summary) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, toJson(reports, summary), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the report to " + target, e);
        }
    }

    /**
     * Render the metadata as JSON. Exposed so a caller can embed it.
     */
    public static String toJson(List<MergeConflictResolver.MergeReport> reports,
                                MergeBatch.Summary summary) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"summary\": ").append(summaryJson(summary)).append(",\n");
        json.append("  \"files\": ").append(filesJson(reports)).append('\n');
        json.append("}\n");
        return json.toString();
    }

    private static String summaryJson(MergeBatch.Summary summary) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("    \"files\": ").append(summary.files()).append(",\n");
        json.append("    \"cleanFiles\": ").append(summary.cleanFiles()).append(",\n");
        json.append("    \"conflicts\": ").append(summary.conflicts()).append(",\n");
        json.append("    \"autoResolutions\": ").append(summary.autoResolutions()).append(",\n");
        json.append("    \"applicableResolutions\": ")
            .append(summary.applicableResolutions()).append(",\n");
        json.append("    \"reviewResolutions\": ").append(summary.reviewResolutions()).append(",\n");
        json.append("    \"manualResolutions\": ").append(summary.manualResolutions()).append(",\n");
        json.append("    \"replayedDecisions\": ").append(summary.replayedDecisions()).append(",\n");
        json.append("    \"dryRun\": ").append(summary.dryRun()).append(",\n");
        json.append("    \"fullyResolved\": ").append(summary.isFullyResolved()).append('\n');
        json.append("  }");
        return json.toString();
    }

    private static String filesJson(List<MergeConflictResolver.MergeReport> reports) {
        StringBuilder json = new StringBuilder("[\n");
        for (int index = 0; index < reports.size(); index++) {
            json.append(fileJson(reports.get(index)));
            if (index < reports.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        return json.append("  ]").toString();
    }

    private static String fileJson(MergeConflictResolver.MergeReport report) {
        StringBuilder json = new StringBuilder();
        json.append("    {\n");
        json.append("      \"filePath\": ").append(quote(report.getFilePath())).append(",\n");
        json.append("      \"branchName\": ").append(quote(report.getBranchName())).append(",\n");
        json.append("      \"clean\": ").append(report.isClean()).append(",\n");
        json.append("      \"summary\": ").append(quote(report.summarize())).append(",\n");
        json.append("      \"conflicts\": ").append(conflictsJson(report)).append(",\n");
        json.append("      \"resolutions\": ").append(resolutionsJson(report)).append('\n');
        json.append("    }");
        return json.toString();
    }

    private static String conflictsJson(MergeConflictResolver.MergeReport report) {
        List<Conflict> conflicts = report.getConflicts();
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < conflicts.size(); index++) {
            Conflict conflict = conflicts.get(index);
            json.append("{\"type\": ").append(quote(conflict.getType().name()))
                .append(", \"description\": ").append(quote(conflict.getDescription()))
                .append(", \"region\": ").append(regionJson(conflict.getRegion()))
                .append(", \"handling\": ")
                .append(quote(conflict.getType().handling().name()))
                .append('}');
            if (index < conflicts.size() - 1) {
                json.append(", ");
            }
        }
        return json.append(']').toString();
    }

    private static String resolutionsJson(MergeConflictResolver.MergeReport report) {
        List<ConflictResolution> resolutions = report.getResolutions();
        List<ConflictResolution> applicable = report.getIndependentlyApplicable();

        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < resolutions.size(); index++) {
            ConflictResolution resolution = resolutions.get(index);
            json.append("{\n");
            json.append("          \"type\": ").append(quote(resolution.getType().name()))
                .append(",\n");
            json.append("          \"kind\": ").append(quote(resolution.getKind().name()))
                .append(",\n");
            json.append("          \"strategy\": ")
                .append(quote(resolution.getResolutionStrategy().name())).append(",\n");
            json.append("          \"verification\": ")
                .append(quote(resolution.getVerification().name())).append(",\n");
            json.append("          \"region\": ").append(regionJson(resolution.getRegion()))
                .append(",\n");
            json.append("          \"independentlyApplicable\": ")
                .append(applicable.contains(resolution)).append(",\n");
            json.append("          \"sticky\": ").append(resolution.isSticky()).append(",\n");
            json.append("          \"explanation\": ").append(quote(resolution.getExplanation()))
                .append(",\n");
            json.append("          \"resolvedCode\": ").append(quote(resolution.getResolvedCode()))
                .append(",\n");
            json.append("          \"fixPaths\": ").append(fixPathsJson(resolution)).append('\n');
            json.append("        }");
            if (index < resolutions.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        return json.append("      ]").toString();
    }

    private static String fixPathsJson(ConflictResolution resolution) {
        List<FixPath> fixPaths = resolution.getAlternativePaths();
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < fixPaths.size(); index++) {
            FixPath fixPath = fixPaths.get(index);
            json.append("{\"description\": ").append(quote(fixPath.getDescription()))
                .append(", \"options\": ").append(stringListJson(fixPath.getOptions()))
                .append(", \"recommended\": ").append(quote(fixPath.getRecommended()))
                .append(", \"justification\": ").append(quote(fixPath.getJustification()))
                .append(", \"impact\": ").append(quote(fixPath.getImpact()))
                .append('}');
            if (index < fixPaths.size() - 1) {
                json.append(", ");
            }
        }
        return json.append(']').toString();
    }

    private static String regionJson(Region region) {
        if (region == null || !region.isKnown()) {
            return "null";
        }
        return "{\"startLine\": " + region.startLine()
            + ", \"endLine\": " + region.endLine() + "}";
    }

    private static String stringListJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            json.append(quote(values.get(index)));
            if (index < values.size() - 1) {
                json.append(", ");
            }
        }
        return json.append(']').toString();
    }

    /**
     * Quote a string as JSON, escaping the characters that matter and handling
     * {@code null} as a JSON null rather than the text "null".
     */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    /**
     * The default report location for a module: derived output under
     * {@code .jcodebuddy/}, which is ignored by default (DEC-026).
     */
    public static Path defaultTarget(Path moduleRoot) {
        return moduleRoot.resolve(".jcodebuddy").resolve("metadata")
            .resolve("merge-report.json");
    }

    /**
     * The metadata path for a set of reports, for callers that want a list.
     */
    public static List<String> describePaths(List<MergeConflictResolver.MergeReport> reports) {
        List<String> paths = new ArrayList<>();
        for (MergeConflictResolver.MergeReport report : reports) {
            paths.add(report.getFilePath());
        }
        return paths;
    }
}
