// {@link hr.hrg.rewrite.validation.MarkerEntityRule} Validates marker entity rules.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates marker entity rules.
 *
 * <p>Checks that marker entities have the correct structure and annotations.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class MarkerEntityRule {

    /**
     * Validate a source file for marker entity compliance.
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
     * Validate a single class for marker entity compliance.
     */
    private ValidationResult validateClass(ClassTree classTree) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check for @Marker annotation
        if (!hasMarkerAnnotation(classTree)) {
            String className = classTree.getIdentifier().getNameAsString();
            errors.add("Class " + className + " is missing @Marker annotation");
        }

        // Check that it's an abstract class
        if (!isAbstract(classTree)) {
            String className = classTree.getIdentifier().getNameAsString();
            warnings.add("Class " + className + " should be abstract");
        }

        // Check for marker-specific methods
        if (missingMarkerMethod(classTree, "getMarkerType")) {
            String className = classTree.getIdentifier().getNameAsString();
            warnings.add("Class " + className + " is missing getMarkerType() method");
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Check if a class has the @Marker annotation.
     */
    private boolean hasMarkerAnnotation(ClassTree classTree) {
        // Check leading annotations
        for (AnnotationTree annotation : classTree.getLeadingAnnotations()) {
            if (isMarkerAnnotation(annotation)) {
                return true;
            }
        }

        // Check trailing annotations
        for (AnnotationTree annotation : classTree.getTrailingAnnotations()) {
            if (isMarkerAnnotation(annotation)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if an annotation is the @Marker annotation.
     */
    private boolean isMarkerAnnotation(AnnotationTree annotation) {
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            String annotationName = identifier.getQualid().getNameAsString();
            // Check for fully qualified name or simple name
            return "Marker".equals(annotationName)
                    || "hr.hrg.hipster.annotation.Marker".equals(annotationName);
        }
        return false;
    }

    /**
     * Check if a class is abstract.
     */
    private boolean isAbstract(ClassTree classTree) {
        return classTree.getModifiers().contains(Modifiers.ABSTRACT);
    }

    /**
     * Check if a class is missing a required method.
     */
    private boolean missingMarkerMethod(ClassTree classTree, String methodName) {
        return classTree.getMethods().stream()
                .noneMatch(method -> methodName.equals(method.getNameAsString()));
    }
}
