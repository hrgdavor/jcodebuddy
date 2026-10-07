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
        // conflicts and resolutions are INDEX-PARALLEL: resolutions[i] answers conflicts[i], because the report's

        // resolutions come from `resolveAll`, which maps one to one. The page relies on that pairing to key a

        // decision on the CONFLICT: a resolution that replayed a recorded decision carries that decision's own

        // sides, so keying on the resolution can name a signature the incoming conflict does not have.

        json.append("      \"conflicts\": ").append(conflictsJson(report)).append(",\n");
        json.append("      \"resolutions\": ").append(resolutionsJson(report)).append('\n');
        json.append("    }");
        return json.toString();
    }

    private static String conflictsJson(MergeConflictResolver.MergeReport report) {
        List<Conflict> conflicts = report.getConflicts();
        List<ConflictState> states = report.getStates();
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < conflicts.size(); index++) {
            Conflict conflict = conflicts.get(index);
            json.append("{\"type\": ").append(quote(conflict.getType().name()))
                .append(", \"description\": ").append(quote(conflict.getDescription()))
                // What KIND of change it is, beside what it is about: "import addition, both sides inserted" is
                // two facts, and the second is what tells a reviewer whether anything needs choosing (plan 4.12).
                .append(", \"shape\": ").append(quote(conflict.getShape().name()))
                .append(", \"region\": ").append(regionJson(conflict.getRegion()))
                .append(", \"signature\": ")
                    .append(quote(ConflictSignature.of(conflict).toFileName()));
            if (index < states.size()) {
                // What became of this conflict. Written per conflict rather than per resolution because a
                // conflict another claim settled has no resolution of its own, and this key is the only
                // place it appears at all (plan step 4.20, DEC-046 clause 7). Omitted when the caller did
                // not track states, rather than inventing one.
                json.append(", \"state\": ").append(quote(states.get(index).name()));
            }
            json.append(", \"sides\": ")
                .append(sidesJson(conflict.getBaseCode(), conflict.getBranch1Code(), conflict.getBranch2Code()))
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
            // The evidence the answer rests on, as an ordered name rather than prose: the page
            // owns the wording (DEC-027), and this is what it compares when two resolutions
            // disagree about one block.
            json.append("          \"analysisLevel\": ")
                .append(quote(resolution.getAnalysisLevel().name())).append(",\n");
            json.append("          \"region\": ").append(regionJson(resolution.getRegion()))
                .append(",\n");
            json.append("          \"independentlyApplicable\": ")
                .append(applicable.contains(resolution)).append(",\n");
            json.append("          \"sticky\": ").append(resolution.isSticky()).append(",\n");
            json.append("          \"explanation\": ").append(quote(resolution.getExplanation()))
                .append(",\n");
            // Written even when empty, so a renderer distinguishes "no caveats" from a
            // key it does not know about.
            json.append("          \"warnings\": ").append(stringListJson(resolution.getWarnings()))
                .append(",\n");
            json.append("          \"resolvedCode\": ").append(quote(resolution.getResolvedCode()))
                .append(",\n");
            json.append("          \"signature\": ")
                .append(quote(ConflictSignature.of(resolution.getType(), resolution.getFilePath(),
                        resolution.getBranch1Code(), resolution.getBranch2Code()).toFileName()))
                .append(",\n");
            json.append("          \"sides\": ").append(sidesJson(resolution)).append(",\n");
            json.append("          \"suggestion\": ").append(suggestionJson(resolution)).append(",\n");
            // And ALL of them, in the order the producers offered them, because more than one producer can answer
            // one conflict (plan step 4.17): the first element is the same answer `suggestion` carries, so a
            // consumer that predates this key and one that reads it see the same primary, and only the second
            // learns that an alternative exists. Additive on purpose — replacing `suggestion` would break the page
            // and the decisions export for a capability they do not need to know about yet.
            json.append("          \"suggestions\": ")
                .append(suggestionsJson(resolution)).append(",\n");
            json.append("          \"fixPaths\": ").append(fixPathsJson(resolution)).append('\n');
            json.append("        }");
            if (index < resolutions.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        return json.append("      ]").toString();
    }

    /**
     * The suggestion a resolution carries, or {@code null} (plan step 4.16).
     *
     * <p>Written as an object rather than flattened onto the resolution, because it is a different kind of thing
     * with its own provenance: the page renders it as <em>the proposed result</em> with its basis beside it, and
     * "the word-level comparison produced this" and "a model proposed this" deserve different amounts of trust
     * from the person reading them. A suggestion whose basis is invisible invites blind acceptance, which is why
     * {@code provenance} and {@code confidence} travel with the code rather than only the code.
     *
     * <p>{@code null} when there is none, so a renderer distinguishes "no suggestion" from an empty one.
     */
    /**
     * Every suggestion a resolution carries, as a JSON array; {@code []} when there are none.
     *
     * <p>One renderer for one suggestion, used by both keys, so the two cannot describe the same answer differently:
     * {@code suggestions[0]} and {@code suggestion} are the same object by construction.
     */
    private static String suggestionsJson(ConflictResolution resolution) {
        StringBuilder json = new StringBuilder("[");
        List<Suggestion> all = resolution.getSuggestions();
        for (int index = 0; index < all.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append(suggestionJson(all.get(index)));
        }
        return json.append(']').toString();
    }

    private static String suggestionJson(ConflictResolution resolution) {
        return suggestionJson(resolution.getSuggestion());
    }

    private static String suggestionJson(Suggestion suggestion) {
        if (suggestion == null) {
            return "null";
        }
        return new StringBuilder("{")
            .append("\"code\": ").append(quote(suggestion.code()))
            .append(", \"explanation\": ").append(quote(suggestion.explanation()))
            .append(", \"provenance\": ").append(quote(suggestion.provenance()))
            .append(", \"analysisLevel\": ").append(quote(suggestion.analysisLevel().name()))
            .append(", \"confidence\": ").append(quote(suggestion.confidence().name()))
            .append(", \"warnings\": ").append(stringListJson(suggestion.warnings()))
            // Both the verdict and its detail: a reviewer deciding whether to trust an answer needs to know what
            // the verifier said about it, not only that something was said.
            .append(", \"verification\": ").append(quote(suggestion.verification().name()))
            .append(", \"verificationDetail\": ").append(quote(suggestion.verificationDetail()))
            .append('}')
            .toString();
    }

    /**
     * The three sides a reviewer compares, beside the resolved code (plan step 4.2).
     *
     * <p>A review display that shows only the answer cannot be reviewed: the question is always what the two
     * branches did to the same lines, and which of them the resolution kept. The texts are already here — the
     * resolution carries all three — so this is an omission rather than missing information.</p>
     *
     * <p><strong>Why a report may carry text where the class index may not.</strong> DEC-040 D2 says the model
     * stores pointers, never copies, because a copy can silently disagree with the file it describes. That rule
     * holds for the index, which describes <em>one</em> current file. A report describes <em>three</em> file
     * states that the merge already reconciled and that no single file still holds — there is nothing to point
     * at — and its whole purpose is to be read by a human. The pointer rule's guard is kept anyway: the sides
     * are the resolver's own input, carried verbatim.</p>
     */
    private static String sidesJson(ConflictResolution resolution) {
        return sidesJson(resolution.getBaseCode(), resolution.getBranch1Code(),
            resolution.getBranch2Code());
    }

    /**
     * The same three sides, from a {@link Conflict} - which is where the review page's decision KEY comes
     * from, because a replayed resolution no longer holds the conflict's own sides.
     */
    private static String sidesJson(String base, String branch1, String branch2) {
        return "{\"base\": " + quote(base)
            + ", \"branch1\": " + quote(branch1)
            + ", \"branch2\": " + quote(branch2) + "}";
    }

    private static String fixPathsJson(ConflictResolution resolution) {
        List<FixPath> fixPaths = resolution.getAlternativePaths();
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < fixPaths.size(); index++) {
            FixPath fixPath = fixPaths.get(index);
            json.append("{\"description\": ").append(quote(fixPath.getDescription()))
                .append(", \"options\": ").append(stringListJson(fixPath.getOptions()))
                // The code a choice would apply, when the fix path carries one: a proposer's answer does (plan
                // step 4.4), so a page can prefill its editor instead of asking a reviewer to copy the code out
                // of a justification. Empty for the fix paths that only describe a direction.
                .append(", \"suggestedCode\": ").append(quote(fixPath.getSuggested() == null
                    ? ""
                    : fixPath.getSuggested().getResolvedCode()))
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
