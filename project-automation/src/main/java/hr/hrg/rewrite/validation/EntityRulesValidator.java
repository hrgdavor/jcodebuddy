// {@link hr.hrg.rewrite.validation.EntityRulesValidator} Validates all entity rules.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;

/**
 * Validates all entity rules together.
 *
 * <p>Runs multiple validation rules and aggregates their results.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRulesValidator.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class EntityRulesValidator {

    private final AuditableRule auditableRule;
    private final MarkerEntityRule markerEntityRule;
    private final ViewInterfaceRule viewInterfaceRule;
    private final ViewAnnotationRule viewAnnotationRule;

    public EntityRulesValidator() {
        this.auditableRule = new AuditableRule();
        this.markerEntityRule = new MarkerEntityRule();
        this.viewInterfaceRule = new ViewInterfaceRule();
        this.viewAnnotationRule = new ViewAnnotationRule();
    }

    /**
     * Validate all rules on a source file.
     *
     * @param sourceFile The source file AST
     * @return Combined validation result
     */
    public ValidationResult validateAll(SourceFile sourceFile) {
        if (sourceFile == null) {
            return ValidationResult.error("Source file is null");
        }

        // Run all validation rules
        ValidationResult auditableResult = auditableRule.validate(sourceFile);
        ValidationResult markerResult = markerEntityRule.validate(sourceFile);
        ValidationResult viewInterfaceResult = viewInterfaceRule.validate(sourceFile);
        ValidationResult viewAnnotationResult = viewAnnotationRule.validate(sourceFile);

        // Aggregate errors
        List<String> allErrors = aggregateErrors(auditableResult, markerResult, viewInterfaceResult, viewAnnotationResult);
        List<String> allWarnings = aggregateWarnings(auditableResult, markerResult, viewInterfaceResult, viewAnnotationResult);

        // Determine overall validity (false if there are any errors)
        boolean isValid = allErrors.isEmpty();

        return ValidationResult.valid(isValid, allErrors, allWarnings);
    }

    /**
     * Aggregate errors from multiple validation results.
     */
    private List<String> aggregateErrors(ValidationResult... results) {
        List<String> allErrors = new java.util.ArrayList<>();
        for (ValidationResult result : results) {
            allErrors.addAll(result.getErrors());
        }
        return allErrors;
    }

    /**
     * Aggregate warnings from multiple validation results.
     */
    private List<String> aggregateWarnings(ValidationResult... results) {
        List<String> allWarnings = new java.util.ArrayList<>();
        for (ValidationResult result : results) {
            allWarnings.addAll(result.getWarnings());
        }
        return allWarnings;
    }

    /**
     * Validate only specific rules.
     *
     * @param sourceFile The source file AST
     * @param ruleNames The names of rules to run
     * @return Validation result
     */
    public ValidationResult validate(
            SourceFile sourceFile, String... ruleNames
    ) {
        if (sourceFile == null) {
            return ValidationResult.error("Source file is null");
        }

        Map<String, AuditableRule> auditableRules = Map.of(
                "auditable", auditableRule,
                "marker", markerEntityRule,
                "view-interface", viewInterfaceRule,
                "view-annotation", viewAnnotationRule
        );

        List<String> errors = new java.util.ArrayList<>();
        List<String> warnings = new java.util.ArrayList<>();

        for (String ruleName : ruleNames) {
            AuditableRule rule = auditableRules.get(ruleName);
            if (rule != null) {
                ValidationResult result = rule.validate(sourceFile);
                errors.addAll(result.getErrors());
                warnings.addAll(result.getWarnings());
            }
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Get validation rules available.
     *
     * @return List of rule names
     */
    public List<String> getAvailableRules() {
        return List.of("auditable", "marker", "view-interface", "view-annotation");
    }
}
