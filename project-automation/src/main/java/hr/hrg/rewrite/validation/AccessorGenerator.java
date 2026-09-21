// {@link hr.hrg.rewrite.validation.AccessorGenerator} Generates accessors.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.*;

/**
 * Generates accessors for entity fields.
 *
 * <p>Generates getter and setter methods for entity fields.</p>
 *
 * <p>Original JavaParser location: java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class AccessorGenerator {

    /**
     * Generate accessors for a source file.
     *
     * @param sourceFile The source file AST
     * @return The generated compilation unit
     */
    public CompilationUnit generateAccessors(SourceFile sourceFile) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        List<String> generatedMethods = new ArrayList<>();

        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                String className = classTree.getIdentifier().getNameAsString();
                generatedMethods.addAll(generateGetters(classTree));
                generatedMethods.addAll(generateSetters(classTree));
            }
        }

        return CompilationUnit.of(
                SourceFile.build(
                        String.join("\n", generatedMethods)
                )
        );
    }

    /**
     * Generate getter methods for a class.
     */
    private List<String> generateGetters(ClassTree classTree) {
        List<String> getters = new ArrayList<>();
        String className = classTree.getIdentifier().getNameAsString();
        String packageName = extractPackageName(classTree);

        for (FieldTree field : classTree.getFields()) {
            String fieldName = field.getNameAsString();
            String fieldType = field.getType().describe();

            // Skip synthetic or special fields
            if (isSpecialField(fieldName)) {
                continue;
            }

            // Generate getter
            String getterCode = String.format(
                    "    public %s get%s() {%s return %s; }%n",
                    fieldType, capitalize(fieldName),
                    fieldType, fieldName
            );

            getters.add(getterCode);
        }

        return getters;
    }

    /**
     * Generate setter methods for a class.
     */
    private List<String> generateSetters(ClassTree classTree) {
        List<String> setters = new ArrayList<>();
        String className = classTree.getIdentifier().getNameAsString();
        String packageName = extractPackageName(classTree);

        for (FieldTree field : classTree.getFields()) {
            String fieldName = field.getNameAsString();
            String fieldType = field.getType().describe();

            // Skip synthetic or special fields
            if (isSpecialField(fieldName)) {
                continue;
            }

            // Generate setter
            String setterCode = String.format(
                    "    public void set%s(%s %s) {%s this.%s = %s; }%n",
                    capitalize(fieldName), fieldType, capitalize(fieldName),
                    fieldType, fieldName, fieldName
            );

            setters.add(setterCode);
        }

        return setters;
    }

    /**
     * Check if a field is special (should not generate accessor).
     */
    private boolean isSpecialField(String fieldName) {
        return fieldName.equals("class")
                || fieldName.equals("serialVersionUID")
                || fieldName.startsWith("_")
                || fieldName.startsWith("$");
    }

    /**
     * Extract package name from a class tree.
     */
    private String extractPackageName(ClassTree classTree) {
        // Extract from package declaration
        PackageDeclarationTree packageTree = classTree.getCompilationUnit().getPackageDeclaration();
        if (packageTree != null) {
            return packageTree.getNameAsString();
        }
        return "";
    }

    /**
     * Capitalize a string.
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Generate accessors with annotations.
     */
    public CompilationUnit generateAccessorsWithAnnotations(SourceFile sourceFile) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        List<String> generatedMethods = new ArrayList<>();

        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                String className = classTree.getIdentifier().getNameAsString();
                generatedMethods.addAll(generateGettersWithAnnotations(classTree));
                generatedMethods.addAll(generateSettersWithAnnotations(classTree));
            }
        }

        return CompilationUnit.of(
                SourceFile.build(
                        String.join("\n", generatedMethods)
                )
        );
    }

    /**
     * Generate getter methods with annotations.
     */
    private List<String> generateGettersWithAnnotations(ClassTree classTree) {
        List<String> getters = new ArrayList<>();

        for (FieldTree field : classTree.getFields()) {
            String fieldName = field.getNameAsString();
            String fieldType = field.getType().describe();

            if (isSpecialField(fieldName)) {
                continue;
            }

            // Check for @Getter annotation
            boolean hasGetter = hasGetterAnnotation(field);

            if (hasGetter) {
                getters.add(generateGetterWithAnnotation(field));
            } else {
                getters.add(generateGetterWithoutAnnotation(field));
            }
        }

        return getters;
    }

    /**
     * Generate setter methods with annotations.
     */
    private List<String> generateSettersWithAnnotations(ClassTree classTree) {
        List<String> setters = new ArrayList<>();

        for (FieldTree field : classTree.getFields()) {
            String fieldName = field.getNameAsString();

            if (isSpecialField(fieldName)) {
                continue;
            }

            // Check for @Setter annotation
            boolean hasSetter = hasSetterAnnotation(field);

            if (hasSetter) {
                setters.add(generateSetterWithAnnotation(field));
            } else {
                setters.add(generateSetterWithoutAnnotation(field));
            }
        }

        return setters;
    }

    /**
     * Check if a field has @Getter annotation.
     */
    private boolean hasGetterAnnotation(FieldTree field) {
        for (AnnotationTree annotation : field.getLeadingAnnotations()) {
            TypeTree annotationType = annotation.getAnnotation();
            if (annotationType instanceof IdentifierTree identifier) {
                String annotationName = identifier.getQualid().getNameAsString();
                if ("Getter".equals(annotationName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if a field has @Setter annotation.
     */
    private boolean hasSetterAnnotation(FieldTree field) {
        for (AnnotationTree annotation : field.getLeadingAnnotations()) {
            TypeTree annotationType = annotation.getAnnotation();
            if (annotationType instanceof IdentifierTree identifier) {
                String annotationName = identifier.getQualid().getNameAsString();
                if ("Setter".equals(annotationName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Generate getter with annotation.
     */
    private String generateGetterWithAnnotation(FieldTree field) {
        String fieldName = field.getNameAsString();
        String fieldType = field.getType().describe();

        return String.format(
                "    @Getter%n    public %s get%s() {%s return %s; }%n",
                fieldType, capitalize(fieldName),
                fieldType, fieldName
        );
    }

    /**
     * Generate getter without annotation.
     */
    private String generateGetterWithoutAnnotation(FieldTree field) {
        String fieldName = field.getNameAsString();
        String fieldType = field.getType().describe();

        return String.format(
                "    public %s get%s() {%s return %s; }%n",
                fieldType, capitalize(fieldName),
                fieldType, fieldName
        );
    }

    /**
     * Generate setter with annotation.
     */
    private String generateSetterWithAnnotation(FieldTree field) {
        String fieldName = field.getNameAsString();
        String fieldType = field.getType().describe();

        return String.format(
                "    @Setter%n    public void set%s(%s %s) {%s this.%s = %s; }%n",
                capitalize(fieldName), fieldType, capitalize(fieldName),
                fieldType, fieldName, fieldName
        );
    }

    /**
     * Generate setter without annotation.
     */
    private String generateSetterWithoutAnnotation(FieldTree field) {
        String fieldName = field.getNameAsString();
        String fieldType = field.getType().describe();

        return String.format(
                "    public void set%s(%s %s) {%s this.%s = %s; }%n",
                capitalize(fieldName), fieldType, capitalize(fieldName),
                fieldType, fieldName, fieldName
        );
    }
}
