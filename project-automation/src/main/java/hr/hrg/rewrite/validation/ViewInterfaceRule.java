// {@link hr.hrg.rewrite.validation.ViewInterfaceRule} Validates view interface structure.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates view interface structure.
 *
 * <p>Checks that view interfaces have the correct structure and methods.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class ViewInterfaceRule {

    /**
     * Validate a source file for view interface compliance.
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
     * Validate a single type for view interface compliance.
     */
    private ValidationResult validateType(TypeTree typeTree) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (typeTree instanceof InterfaceTree interfaceTree) {
            String typeName = interfaceTree.getIdentifier().getNameAsString();

            // Check for @View annotation
            if (!hasViewAnnotation(interfaceTree)) {
                errors.add("Interface " + typeName + " is missing @View annotation");
            }

            // Check for required methods
            if (missingViewMethod(interfaceTree, "getFields")) {
                errors.add("Interface " + typeName + " is missing getFields() method");
            }

            if (missingViewMethod(interfaceTree, "getTypes")) {
                errors.add("Interface " + typeName + " is missing getTypes() method");
            }

            // Check for required annotations on methods
            for (MethodTree method : interfaceTree.getMethods()) {
                if (!hasViewMethodAnnotation(method)) {
                    warnings.add("Method " + method.getNameAsString() + " in " + typeName + " missing @ViewMethod annotation");
                }
            }
        } else if (typeTree instanceof ClassTree classTree) {
            // Also validate classes that implement view interfaces
            if (!hasViewAnnotation(classTree)) {
                warnings.add("Class " + classTree.getIdentifier().getNameAsString() + " may be missing @View annotation");
            }
        }

        return ValidationResult.valid(errors.isEmpty(), errors, warnings);
    }

    /**
     * Check if a type has the @View annotation.
     */
    private boolean hasViewAnnotation(TypeTree typeTree) {
        if (typeTree instanceof InterfaceTree interfaceTree) {
            // Check leading annotations
            for (AnnotationTree annotation : interfaceTree.getLeadingAnnotations()) {
                if (isViewAnnotation(annotation)) {
                    return true;
                }
            }

            // Check trailing annotations
            for (AnnotationTree annotation : interfaceTree.getTrailingAnnotations()) {
                if (isViewAnnotation(annotation)) {
                    return true;
                }
            }
        } else if (typeTree instanceof ClassTree classTree) {
            // Check leading annotations
            for (AnnotationTree annotation : classTree.getLeadingAnnotations()) {
                if (isViewAnnotation(annotation)) {
                    return true;
                }
            }

            // Check trailing annotations
            for (AnnotationTree annotation : classTree.getTrailingAnnotations()) {
                if (isViewAnnotation(annotation)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if an annotation is the @View annotation.
     */
    private boolean isViewAnnotation(AnnotationTree annotation) {
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            String annotationName = identifier.getQualid().getNameAsString();
            // Check for fully qualified name or simple name
            return "View".equals(annotationName)
                    || "hr.hrg.hipster.annotation.View".equals(annotationName);
        }
        return false;
    }

    /**
     * Check if a type is missing a required method.
     */
    private boolean missingViewMethod(TypeTree typeTree, String methodName) {
        if (typeTree instanceof InterfaceTree interfaceTree) {
            return interfaceTree.getMethods().stream()
                    .noneMatch(method -> methodName.equals(method.getNameAsString()));
        }
        return true;
    }

    /**
     * Check if a method has the @ViewMethod annotation.
     */
    private boolean hasViewMethodAnnotation(MethodTree method) {
        // Check leading annotations
        for (AnnotationTree annotation : method.getLeadingAnnotations()) {
            if (isViewMethodAnnotation(annotation)) {
                return true;
            }
        }

        // Check trailing annotations
        for (AnnotationTree annotation : method.getTrailingAnnotations()) {
            if (isViewMethodAnnotation(annotation)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if an annotation is the @ViewMethod annotation.
     */
    private boolean isViewMethodAnnotation(AnnotationTree annotation) {
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            String annotationName = identifier.getQualid().getNameAsString();
            // Check for fully qualified name or simple name
            return "ViewMethod".equals(annotationName)
                    || "hr.hrg.hipster.annotation.ViewMethod".equals(annotationName);
        }
        return false;
    }
}
