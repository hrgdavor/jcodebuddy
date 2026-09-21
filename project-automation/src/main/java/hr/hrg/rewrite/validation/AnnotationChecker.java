// {@link hr.hrg.rewrite.validation.AnnotationChecker} Utility for checking annotations.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility for checking annotations in OpenRewrite AST.
 *
 * <p>Provides methods to check for annotation presence and extract annotation details.</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-021 (generator class-file header).</p>
 */
public class AnnotationChecker {

    /**
     * Check if a class has a specific annotation.
     *
     * @param sourceFile The source file AST
     * @param fullyQualifiedName The fully qualified name of the annotation (e.g., "hr.hrg.hipster.annotation.View")
     * @return true if the annotation is present, false otherwise
     */
    public boolean hasAnnotation(SourceFile sourceFile, String fullyQualifiedName) {
        if (sourceFile == null) {
            return false;
        }

        List<TypeTree> classes = findClasses(sourceFile);
        for (TypeTree classTree : classes) {
            List<AnnotationTree> annotations = findAnnotations(classTree);
            for (AnnotationTree annotation : annotations) {
                if (isMatchingAnnotation(annotation, fullyQualifiedName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get all annotations on a class.
     *
     * @param sourceFile The source file AST
     * @return List of annotation trees
     */
    public List<AnnotationTree> getAnnotations(SourceFile sourceFile) {
        if (sourceFile == null) {
            return List.of();
        }

        List<TypeTree> classes = findClasses(sourceFile);
        List<AnnotationTree> allAnnotations = new ArrayList<>();
        for (TypeTree classTree : classes) {
            allAnnotations.addAll(findAnnotations(classTree));
        }
        return allAnnotations;
    }

    /**
     * Find all classes in the source file.
     */
    private List<TypeTree> findClasses(SourceFile sourceFile) {
        List<TypeTree> classes = new ArrayList<>();
        sourceFile.getDescendantTypes().forEach(type -> {
            if (type instanceof ClassTree) {
                classes.add(type);
            }
        });
        return classes;
    }

    /**
     * Find all annotations on a class tree.
     */
    private List<AnnotationTree> findAnnotations(TypeTree type) {
        List<AnnotationTree> annotations = new ArrayList<>();

        if (type instanceof ClassTree classTree) {
            // Find annotations on class declaration
            List<AnnotationTree> classAnnotations = classTree.getLeadingAnnotations();
            classAnnotations.addAll(classTree.getTrailingAnnotations());
            annotations.addAll(classAnnotations);

            // Find annotations on methods
            classTree.getMethods().forEach(method -> {
                annotations.addAll(method.getLeadingAnnotations());
                annotations.addAll(method.getTrailingAnnotations());
            });

            // Find annotations on fields
            classTree.getFields().forEach(field -> {
                annotations.addAll(field.getLeadingAnnotations());
                annotations.addAll(field.getTrailingAnnotations());
            });

            // Find annotations on nested types
            classTree.getTypeParameters().forEach(typeParameter -> {
                annotations.addAll(typeParameter.getLeadingAnnotations());
                annotations.addAll(typeParameter.getTrailingAnnotations());
            });
        }
        return annotations;
    }

    /**
     * Check if an annotation matches the expected fully qualified name.
     */
    private boolean isMatchingAnnotation(AnnotationTree annotation, String expectedFQN) {
        String annotationName = extractAnnotationName(annotation);
        return expectedFQN.equals(annotationName);
    }

    /**
     * Extract the fully qualified name of an annotation.
     */
    private String extractAnnotationName(AnnotationTree annotation) {
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            return identifier.getQualid().getNameAsString();
        }
        return "";
    }

    /**
     * Get annotation arguments.
     */
    public Map<String, ExpressionTree> getAnnotationArguments(AnnotationTree annotation) {
        return annotation.getArguments().stream()
                .collect(java.util.stream.Collectors.toMap(
                        arg -> extractArgumentName(arg),
                        arg -> arg.getValue()
                ));
    }

    private String extractArgumentName(AnnotationTree.Argument arg) {
        if (arg.getKey() instanceof IdentifierTree identifier) {
            return identifier.getNameAsString();
        }
        return "";
    }
}
