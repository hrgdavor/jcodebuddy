// {@link hr.hrg.rewrite.validation.AuditableRule} Validates that entities implement Auditable interface.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates that entities implement the Auditable interface.
 *
 * <p>Checks that entity classes have the @Auditable annotation and implement
 * the necessary methods for audit trail functionality.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/AuditableRule.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class AuditableRule {

    /**
     * Validate a source file for Auditable compliance.
     *
     * @param sourceFile The source file AST
     * @return Validation result
     */
    public ValidationResult validate(SourceFile sourceFile) {
        if (sourceFile == null) {
            return ValidationResult.error("Source file is null");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            List<TypeTree> classes = sourceFile.getDescendantTypes();
            for (TypeTree type : classes) {
                if (type instanceof ClassTree classTree) {
                    ValidationResult classResult = validateClass(classTree);
                    errors.addAll(classResult.getErrors());
                    warnings.addAll(classResult.getWarnings());
                }
            }

            if (errors.isEmpty()) {
                return ValidationResult.ok();
            } else {
                return ValidationResult.errors(errors);
            }
        } catch (Exception e) {
            return ValidationResult.error("Validation failed: " + e.getMessage());
        }
    }

    /**
     * Validate a single class for Auditable compliance.
     */
    private ValidationResult validateClass(ClassTree classTree) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check for @Auditable annotation
        if (!hasAuditableAnnotation(classTree)) {
            String className = classTree.getIdentifier().getNameAsString();
            errors.add("Class " + className + " is missing @Auditable annotation");
        }

        // Check for required methods
        if (missingRequiredMethod(classTree, "createAuditEntry")) {
            String className = classTree.getIdentifier().getNameAsString();
            errors.add("Class " + className + " is missing createAuditEntry() method");
        }

        // Check for required fields
        if (missingRequiredField(classTree, "auditTrail")) {
            String className = classTree.getIdentifier().getNameAsString();
            warnings.add("Class " + className + " is missing auditTrail field (optional)");
        }

        // Check for required type parameters
        if (missingTypeParameter(classTree, "A")) {
            String className = classTree.getIdentifier().getNameAsString();
            warnings.add("Class " + className + " is missing type parameter A (optional)");
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Check if a class has the @Auditable annotation.
     */
    private boolean hasAuditableAnnotation(ClassTree classTree) {
        // Check leading annotations
        for (AnnotationTree annotation : classTree.getLeadingAnnotations()) {
            if (isAuditableAnnotation(annotation)) {
                return true;
            }
        }

        // Check trailing annotations
        for (AnnotationTree annotation : classTree.getTrailingAnnotations()) {
            if (isAuditableAnnotation(annotation)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if an annotation is the @Auditable annotation.
     */
    private boolean isAuditableAnnotation(AnnotationTree annotation) {
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            String annotationName = identifier.getQualid().getNameAsString();
            // Check for fully qualified name or simple name
            return "Auditable".equals(annotationName)
                    || "hr.hrg.hipster.annotation.Auditable".equals(annotationName);
        }
        return false;
    }

    /**
     * Check if a class is missing a required method.
     */
    private boolean missingRequiredMethod(ClassTree classTree, String methodName) {
        return classTree.getMethods().stream()
                .noneMatch(method -> methodName.equals(method.getNameAsString()));
    }

    /**
     * Check if a class is missing a required field.
     */
    private boolean missingRequiredField(ClassTree classTree, String fieldName) {
        return classTree.getFields().stream()
                .noneMatch(field -> fieldName.equals(field.getNameAsString()));
    }

    /**
     * Check if a class is missing a type parameter.
     */
    private boolean missingTypeParameter(ClassTree classTree, String typeName) {
        if (classTree.getTypeParameters() == null) {
            return true;
        }
        return classTree.getTypeParameters().stream()
                .noneMatch(typeParameter -> typeName.equals(typeParameter.getTypeNameAsString()));
    }
}
