// {@link hr.hrg.rewrite.validation.ViewAnnotationRule} Validates view annotations.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates view annotations.
 *
 * <p>Checks that view annotations are correctly applied and have valid arguments.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewAnnotationRule.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class ViewAnnotationRule {

    /**
     * Validate a source file for view annotation compliance.
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
                if (type instanceof TypeTree typeTree) {
                    ValidationResult typeResult = validateType(typeTree);
                    errors.addAll(typeResult.getErrors());
                    warnings.addAll(typeResult.getWarnings());
                }
            }

            // Also check annotations used elsewhere
            checkGlobalAnnotations(sourceFile, errors, warnings);

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
     * Validate a single type for view annotation compliance.
     */
    private ValidationResult validateType(TypeTree typeTree) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (typeTree instanceof ClassTree classTree) {
            String className = classTree.getIdentifier().getNameAsString();

            // Check @View annotation
            List<AnnotationTree> annotations = getAllAnnotations(classTree);
            for (AnnotationTree annotation : annotations) {
                ValidationResult annotationResult = validateAnnotation(annotation, className);
                errors.addAll(annotationResult.getErrors());
                warnings.addAll(annotationResult.getWarnings());
            }
        } else if (typeTree instanceof InterfaceTree interfaceTree) {
            String interfaceName = interfaceTree.getIdentifier().getNameAsString();

            // Check @View annotation
            List<AnnotationTree> annotations = getAllAnnotations(interfaceTree);
            for (AnnotationTree annotation : annotations) {
                ValidationResult annotationResult = validateAnnotation(annotation, interfaceName);
                errors.addAll(annotationResult.getErrors());
                warnings.addAll(annotationResult.getWarnings());
            }

            // Check method annotations
            for (MethodTree method : interfaceTree.getMethods()) {
                List<AnnotationTree> methodAnnotations = getAllAnnotations(method);
                for (AnnotationTree annotation : methodAnnotations) {
                    ValidationResult annotationResult = validateAnnotation(annotation, method.getNameAsString());
                    errors.addAll(annotationResult.getErrors());
                    warnings.addAll(annotationResult.getWarnings());
                }
            }
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Get all annotations from a type tree.
     */
    private List<AnnotationTree> getAllAnnotations(TypeTree typeTree) {
        List<AnnotationTree> annotations = new ArrayList<>();

        if (typeTree instanceof ClassTree classTree) {
            annotations.addAll(classTree.getLeadingAnnotations());
            annotations.addAll(classTree.getTrailingAnnotations());

            for (MethodTree method : classTree.getMethods()) {
                annotations.addAll(method.getLeadingAnnotations());
                annotations.addAll(method.getTrailingAnnotations());
            }

            for (FieldTree field : classTree.getFields()) {
                annotations.addAll(field.getLeadingAnnotations());
                annotations.addAll(field.getTrailingAnnotations());
            }
        } else if (typeTree instanceof InterfaceTree interfaceTree) {
            annotations.addAll(interfaceTree.getLeadingAnnotations());
            annotations.addAll(interfaceTree.getTrailingAnnotations());

            for (MethodTree method : interfaceTree.getMethods()) {
                annotations.addAll(method.getLeadingAnnotations());
                annotations.addAll(method.getTrailingAnnotations());
            }
        }

        return annotations;
    }

    /**
     * Validate a single annotation.
     */
    private ValidationResult validateAnnotation(AnnotationTree annotation, String ownerName) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check annotation type
        TypeTree annotationType = annotation.getAnnotation();
        if (!(annotationType instanceof IdentifierTree)) {
            errors.add("Annotation on " + ownerName + " has invalid type: " + annotationType);
            return ValidationResult.valid(errors.isEmpty(), errors, warnings);
        }

        String annotationName = extractAnnotationName(annotationType);
        if (annotationName == null || annotationName.isEmpty()) {
            errors.add("Could not determine annotation name on " + ownerName);
            return ValidationResult.valid(errors.isEmpty(), errors, warnings);
        }

        // Validate annotation arguments if present
        if (!annotation.getArguments().isEmpty()) {
            for (AnnotationTree.Argument arg : annotation.getArguments()) {
                ValidationResult argResult = validateAnnotationArgument(arg, ownerName);
                errors.addAll(argResult.getErrors());
                warnings.addAll(argResult.getWarnings());
            }
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Extract annotation name.
     */
    private String extractAnnotationName(TypeTree annotationType) {
        if (annotationType instanceof IdentifierTree identifier) {
            return identifier.getQualid().getNameAsString();
        }
        return null;
    }

    /**
     * Validate an annotation argument.
     */
    private ValidationResult validateAnnotationArgument(AnnotationTree.Argument arg, String ownerName) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        ExpressionTree value = arg.getValue();
        if (value == null) {
            errors.add("Annotation argument on " + ownerName + " has no value");
        } else {
            // Validate expression type
            if (!(value instanceof LiteralTree)) {
                warnings.add("Annotation argument on " + ownerName + " should use literal values");
            }
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Check global annotations used in the file.
     */
    private void checkGlobalAnnotations(SourceFile sourceFile, List<String> errors, List<String> warnings) {
        // Check for known annotation types
        List<String> knownAnnotations = List.of(
                "hr.hrg.hipster.annotation.View",
                "hr.hrg.hipster.annotation.ViewMethod",
                "hr.hrg.hipster.annotation.ViewField",
                "hr.hrg.hipster.annotation.Auditable",
                "hr.hrg.hipster.annotation.Marker"
        );

        // Check for unknown annotations
        for (TypeTree type : sourceFile.getDescendantTypes()) {
            if (type instanceof TypeTree typeTree) {
                List<AnnotationTree> annotations = getAllAnnotations(typeTree);
                for (AnnotationTree annotation : annotations) {
                    String annotationName = extractAnnotationName(annotation.getAnnotation());
                    if (annotationName != null && !knownAnnotations.contains(annotationName)) {
                        warnings.add("Unknown annotation used: " + annotationName);
                    }
                }
            }
        }
    }

    /**
     * Get all annotations from a class tree.
     */
    private List<AnnotationTree> getAllAnnotations(ClassTree classTree) {
        List<AnnotationTree> annotations = new ArrayList<>();
        annotations.addAll(classTree.getLeadingAnnotations());
        annotations.addAll(classTree.getTrailingAnnotations());

        for (MethodTree method : classTree.getMethods()) {
            annotations.addAll(method.getLeadingAnnotations());
            annotations.addAll(method.getTrailingAnnotations());
        }

        for (FieldTree field : classTree.getFields()) {
            annotations.addAll(field.getLeadingAnnotations());
            annotations.addAll(field.getTrailingAnnotations());
        }

        return annotations;
    }
}
