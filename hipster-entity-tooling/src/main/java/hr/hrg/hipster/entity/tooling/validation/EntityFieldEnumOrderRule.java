package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.ast.CompilationUnit;

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
 */
public class EntityFieldEnumOrderRule implements EntityRule {

    @Override
    public void validate(Path file, String pkg, CompilationUnit cu, List<EntityRulesValidator.ValidationIssue> issues) {
        // Per-file checks only; the cross-revision comparison needs the baseline text.
        EnumConstantOrderChecker.HeaderConfig header = EnumConstantOrderChecker.readHeader(cu);
        for (String diagnostic : header.diagnostics()) {
            issues.add(new EntityRulesValidator.ValidationIssue(file, diagnostic));
        }

        String source = cu.toString();
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
