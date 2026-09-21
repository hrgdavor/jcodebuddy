// {@link hr.hrg.rewrite.validation.FieldChecker} Utility for checking fields.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility for checking fields in OpenRewrite AST.
 *
 * <p>Provides methods to check for field presence and extract field details.</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-021 (generator class-file header).</p>
 */
public class FieldChecker {

    /**
     * Check if a class has a field with the given name.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param fieldName The field name to look for
     * @return true if the field exists
     */
    public boolean hasField(SourceFile sourceFile, String className, String fieldName) {
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

                if (hasFieldName(classType, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get a field by name from a class.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param fieldName The field name to look for
     * @return The field tree if found, null otherwise
     */
    public FieldTree getField(SourceFile sourceFile, String className, String fieldName) {
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

                return findFieldByName(classType, fieldName);
            }
        }
        return null;
    }

    /**
     * Check if a class has a field with the given name.
     */
    private boolean hasFieldName(ClassTree classTree, String fieldName) {
        return classTree.getFields().stream()
                .anyMatch(field -> fieldName.equals(field.getNameAsString()));
    }

    /**
     * Find a field by name.
     */
    private FieldTree findFieldByName(ClassTree classTree, String fieldName) {
        return classTree.getFields().stream()
                .filter(field -> fieldName.equals(field.getNameAsString()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all fields in a class.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @return List of field trees
     */
    public List<FieldTree> getAllFields(SourceFile sourceFile, String className) {
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
                        return classTree.getFields();
                    }
                    return List.of();
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Get field type.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param fieldName The field name
     * @return The field type as a string
     */
    public String getFieldType(SourceFile sourceFile, String className, String fieldName) {
        FieldTree field = getField(sourceFile, className, fieldName);
        if (field != null) {
            return field.getType().describe();
        }
        return "";
    }

    /**
     * Check if a field is static.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param fieldName The field name
     * @return true if the field is static
     */
    public boolean isStatic(SourceFile sourceFile, String className, String fieldName) {
        FieldTree field = getField(sourceFile, className, fieldName);
        return field != null && field.getModifiers().contains(Modifiers.STATIC);
    }

    /**
     * Check if a field is public.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param fieldName The field name
     * @return true if the field is public
     */
    public boolean isPublic(SourceFile sourceFile, String className, String fieldName) {
        FieldTree field = getField(sourceFile, className, fieldName);
        return field != null && field.getModifiers().contains(Modifiers.PUBLIC);
    }

    /**
     * Check if a field is final.
     *
     * @param sourceFile The source file AST
     * @param className The class name to search
     * @param fieldName The field name
     * @return true if the field is final
     */
    public boolean isFinal(SourceFile sourceFile, String className, String fieldName) {
        FieldTree field = getField(sourceFile, className, fieldName);
        return field != null && field.getModifiers().contains(Modifiers.FINAL);
    }
}
