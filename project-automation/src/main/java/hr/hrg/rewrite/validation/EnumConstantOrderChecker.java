// {@link hr.hrg.rewrite.validation.EnumConstantOrderChecker} Checks enum constant order.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.*;

/**
 * Checks enum constant order.
 *
 * <p>Validates that enum constants are in the correct order.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class EnumConstantOrderChecker {

    /**
     * Check enum constant order in a source file.
     *
     * @param sourceFile The source file AST
     * @return Validation result
     */
    public ValidationResult check(SourceFile sourceFile) {
        if (sourceFile == null) {
            return ValidationResult.error("Source file is null");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            List<TypeTree> classes = sourceFile.getDescendantTypes();
            for (TypeTree type : classes) {
                if (type instanceof EnumDeclarationTree enumTree) {
                    ValidationResult enumResult = checkEnum(enumTree);
                    errors.addAll(enumResult.getErrors());
                    warnings.addAll(enumResult.getWarnings());
                }
            }

            if (errors.isEmpty()) {
                return ValidationResult.ok();
            } else {
                return ValidationResult.errors(errors);
            }
        } catch (Exception e) {
            return ValidationResult.error("Check failed: " + e.getMessage());
        }
    }

    /**
     * Check enum constant order.
     */
    private ValidationResult checkEnum(EnumDeclarationTree enumTree) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String enumName = enumTree.getIdentifier().getNameAsString();

        // Check enum constant order
        List<EnumConstantDeclaration> constants = enumTree.getConstants();
        if (constants.isEmpty()) {
            warnings.add("Enum " + enumName + " has no constants");
            return ValidationResult.valid(errors.isEmpty(), errors, warnings);
        }

        // Validate constant order
        for (int i = 0; i < constants.size() - 1; i++) {
            EnumConstantDeclaration current = constants.get(i);
            EnumConstantDeclaration next = constants.get(i + 1);

            // Check ordering
            if (!isInCorrectOrder(current, next)) {
                errors.add("Enum " + enumName + ": constant " + current.getNameAsString() + 
                        " should come after " + next.getNameAsString());
            }
        }

        // Check for duplicate constants
        Set<String> seen = new HashSet<>();
        for (EnumConstantDeclaration constant : constants) {
            String name = constant.getNameAsString();
            if (seen.contains(name)) {
                errors.add("Enum " + enumName + ": duplicate constant " + name);
            }
            seen.add(name);
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Check if constants are in correct order.
     */
    private boolean isInCorrectOrder(EnumConstantDeclaration current, EnumConstantDeclaration next) {
        // Simple alphabetical ordering check
        return current.getNameAsString().compareTo(next.getNameAsString()) <= 0;
    }

    /**
     * Get enum information.
     *
     * @param sourceFile The source file AST
     * @return Map of enum information
     */
    public Map<String, Object> getEnumInfo(SourceFile sourceFile) {
        if (sourceFile == null) {
            return Map.of("status", "error", "message", "Source file is null");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("status", "ok");
        info.put("enumCount", 0);
        info.put("constants", new ArrayList<>());

        try {
            List<TypeTree> classes = sourceFile.getDescendantTypes();
            for (TypeTree type : classes) {
                if (type instanceof EnumDeclarationTree enumTree) {
                    String enumName = enumTree.getIdentifier().getNameAsString();
                    List<EnumConstantDeclaration> constants = enumTree.getConstants();

                    Map<String, Object> enumInfo = new HashMap<>();
                    enumInfo.put("name", enumName);
                    enumInfo.put("constants", constants.size());
                    enumInfo.put("constantsList", constants.stream()
                            .map(c -> c.getNameAsString())
                            .toList());

                    info.put("enumCount", info.get("enumCount") + 1);
                    info.put("constants", info.get("constants") != null ? 
                            (List<Map<String, Object>>) info.get("constants") : 
                            new ArrayList<>());
                }
            }
        } catch (Exception e) {
            info.put("status", "error");
            info.put("message", e.getMessage());
        }

        return info;
    }

    /**
     * Get available enum ordering strategies.
     *
     * @return List of strategy names
     */
    public List<String> getAvailableOrderingStrategies() {
        return List.of("alphabetical", "numeric", "custom");
    }

    /**
     * Get checker information.
     *
     * @return Checker information
     */
    public Map<String, Object> getCheckerInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "EnumConstantOrderChecker");
        info.put("version", "1.0");
        info.put("description", "Checks enum constant order");
        return info;
    }
}
