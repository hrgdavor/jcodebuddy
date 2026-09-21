// {@link hr.hrg.rewrite.validation.MethodChecker} Utility for checking methods.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility for checking methods in OpenRewrite AST.
 *
 * <p>Provides methods to check for method presence and extract method details.</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-021 (generator class-file header).</p>
 */
public class MethodChecker {

    /**
     * Check if a class has a method with the given name.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param methodName The method name to look for
     * @return true if the method exists
     */
    public boolean hasMethod(SourceFile sourceFile, String className, String methodName) {
        if (sourceFile == null) {
            return false;
        }

        List<TypeTree> classes = findClasses(sourceFile);
        for (TypeTree classTree : classes) {
            if (classTree instanceof ClassTree classType) {
                String actualClassName = classType.getIdentifier().getNameAsString();
                if (className != null && !className.equals(actualClassName)) {
                    continue;
                }

                if (hasMethodName(classType, methodName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get a method by name from a class.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param methodName The method name to look for
     * @return The method tree if found, null otherwise
     */
    public MethodTree getMethod(SourceFile sourceFile, String className, String methodName) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = findClasses(sourceFile);
        for (TypeTree classTree : classes) {
            if (classTree instanceof ClassTree classType) {
                String actualClassName = classType.getIdentifier().getNameAsString();
                if (className != null && !className.equals(actualClassName)) {
                    continue;
                }

                return findMethodByName(classType, methodName);
            }
        }
        return null;
    }

    /**
     * Check if a class has a method with the given name.
     */
    private boolean hasMethodName(ClassTree classTree, String methodName) {
        return classTree.getMethods().stream()
                .anyMatch(method -> methodName.equals(method.getNameAsString()));
    }

    /**
     * Find a method by name.
     */
    private MethodTree findMethodByName(ClassTree classTree, String methodName) {
        return classTree.getMethods().stream()
                .filter(method -> methodName.equals(method.getNameAsString()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all methods in a class.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @return List of method trees
     */
    public List<MethodTree> getAllMethods(SourceFile sourceFile, String className) {
        if (sourceFile == null) {
            return List.of();
        }

        List<TypeTree> classes = findClasses(sourceFile);
        return classes.stream()
                .filter(type -> {
                    if (type instanceof ClassTree classTree) {
                        String actualClassName = classTree.getIdentifier().getNameAsString();
                        return className == null || className.equals(actualClassName);
                    }
                    return false;
                })
                .map(type -> {
                    if (type instanceof ClassTree classTree) {
                        return classTree.getMethods();
                    }
                    return List.of();
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Check if a method has a specific annotation.
     *
     * @param sourceFile The source file AST
     * @param className The class name containing the method
     * @param methodName The method name
     * @param annotationName The annotation to check for
     * @return true if the method has the annotation
     */
    public boolean hasAnnotationOnMethod(
            SourceFile sourceFile, String className, String methodName, String annotationName
    ) {
        MethodTree method = getMethod(sourceFile, className, methodName);
        if (method == null) {
            return false;
        }

        return method.getLeadingAnnotations().stream()
                .anyMatch(annotation -> annotationName.equals(extractAnnotationName(annotation)))
                || method.getTrailingAnnotations().stream()
                .anyMatch(annotation -> annotationName.equals(extractAnnotationName(annotation)));
    }

    /**
     * Extract the fully qualified name of an annotation.
     */
    private String extractAnnotationName(org.openrewrite.java.tree.AnnotationTree annotation) {
        TypeTree annotationType = annotation.getAnnotation();
        if (annotationType instanceof IdentifierTree identifier) {
            return identifier.getQualid().getNameAsString();
        }
        return "";
    }
}
