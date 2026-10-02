package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker.EnumLedger;

/**
 * Rule {@code EntityFieldEnumOrderRule} of plan.dsflash § 4.6/R1.3: the build-time guard for the
 * append-only ordinal ledger.
 *
 * <p>It plugs into the existing {@link EntityRulesValidator} registry rather than becoming a
 * parallel tool. In its in-place form it verifies the <em>self-consistency</em> of a single
 * revision's field enums — the marker is present, the constants are unique, and the header is
 * decodable. The cross-revision order comparison is driven by {@link #compareRevisions}, which the
 * CLI in {@code EntityMetadataGenerator}'s entry point ({@code --baseline}) calls with two revision
 * sources; it is deliberately not a git dependency of this class.</p>
 *
 * <h3>Phase 6: the tree is no longer printed back to text</h3>
 * <p>The old check re-serialised the parsed unit ({@code cu.toString()}) and re-parsed it inside
 * {@code readLedgers}. That was a round trip through the printer to recover something the caller
 * already had: the source text. It now passes the source through, which also removes the one place
 * in this rule where a printer change could silently alter the diagnostic text. The per-file
 * overload keeps the same signature as the rest of the registry; the CLI path uses
 * {@link #validateSource} directly.</p>
 */
public class EntityFieldEnumOrderRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, J.CompilationUnit cu,
                         List<EntityRulesValidator.ValidationIssue> issues) {
        // A tree cannot carry the DEC-021 header comment, so the per-file entry point
        // cannot see the marker. Recorded as a known narrowing: callers that have the
        // source (EntityRulesValidator, the --baseline CLI) use validateSource.
        EnumConstantOrderChecker.HeaderConfig header = EnumConstantOrderChecker.readHeader(cu);
        for (String diagnostic : header.diagnostics()) {
            issues.add(new EntityRulesValidator.ValidationIssue(file, diagnostic));
        }
    }

    /**
     * Checks one file from its <strong>source text</strong>.
     *
     * <p>This is the entry point the validator uses for the ledger half of R1. It parses the text once
     * and reads both the DEC-021 header and the field-enum ledgers from that one tree, so the two
     * cannot disagree about which revision they describe — the reason the old version re-serialised the
     * tree with {@code cu.toString()} and re-parsed it was to get back to a single source, and passing
     * the source through is what removes that round trip.</p>
     */
    public static void validateSource(Path file, String source,
                                      List<EntityRulesValidator.ValidationIssue> issues) {
        hr.hrg.hipster.entity.tooling.SourceReader.Read read =
                hr.hrg.hipster.entity.tooling.SourceReader.readText(source);
        if (!read.readable()) {
            // The caller has already reported an unreadable file; a second diagnostic here
            // would duplicate it. Nothing can be said about a ledger that was not read.
            return;
        }
        EnumConstantOrderChecker.HeaderConfig header =
                EnumConstantOrderChecker.readHeader(read.unit());
        for (String diagnostic : header.diagnostics()) {
            issues.add(new EntityRulesValidator.ValidationIssue(file, diagnostic));
        }

        Map<String, EnumLedger> ledgers = EnumConstantOrderChecker.readLedgers(source);
        for (EnumLedger ledger : ledgers.values()) {
            if (!ledger.guarded()) {
                continue; // opt-in by absence: the checker must not flag unmarked enums
            }
            if (ledger.constants().isEmpty()) {
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "empty_field_enum: marked enum " + ledger.qualifiedName() + " declares no constants"));
            }
            if (ledger.allowReorder()) {
                // A warning, surfaced so the escape hatch cannot be left in by accident (R1.2).
                issues.add(new EntityRulesValidator.ValidationIssue(file,
                        "enum_reorder_allowed: " + ledger.qualifiedName()
                                + " carries allowReorder:true; remove it once the layout is settled"));
            }
        }
    }

    /**
     * Whether the file declares any type at all, used by the validator to skip a file it should not
     * report on. Kept here because "does this tree declare anything" is an enum-ledger question in
     * this rule's terms, not a general one.
     */
    static boolean declaresNoTypes(J.CompilationUnit cu) {
        return TreeQueries.typeDeclarations(cu).isEmpty();
    }

    /**
     * Compares the field enums of two revisions of one file.
     *
     * @param baselineSource the source text at the baseline ref
     * @param targetSource   the source text at the target ref
     * @return the violations, empty when the ledger is append-only or unguarded
     */
    public static List<String> compareRevisions(String baselineSource, String targetSource) {
        Map<String, EnumLedger> baseline = EnumConstantOrderChecker.readLedgers(baselineSource);
        Map<String, EnumLedger> target = EnumConstantOrderChecker.readLedgers(targetSource);

        List<String> violations = new java.util.ArrayList<>();
        for (Map.Entry<String, EnumLedger> entry : baseline.entrySet()) {
            EnumLedger before = entry.getValue();
            EnumLedger after = target.get(entry.getKey());
            if (after == null || !before.guarded()) {
                continue;
            }
            violations.addAll(EnumConstantOrderChecker.compare(before.constants(), after.constants())
                    .violations().stream()
                    .map(v -> entry.getKey() + " " + v)
                    .toList());
        }
        return violations;
    }
}
