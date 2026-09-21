// {@link hr.hrg.rewrite.validation.ConstructorGenerator} Generates constructors.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.*;

/**
 * Generates constructors for entity classes.
 *
 * <p>Generates canonical and alternative constructors for entity classes.</p>
 *
 * <p>Original JavaParser location: java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class ConstructorGenerator {

    /**
     * Generate constructors for a source file.
     *
     * @param sourceFile The source file AST
     * @return The generated compilation unit
     */
    public CompilationUnit generateConstructors(SourceFile sourceFile) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        List<String> generatedConstructors = new ArrayList<>();

        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                String className = classTree.getIdentifier().getNameAsString();
                generatedConstructors.add(generateConstructors(classTree));
            }
        }

        return CompilationUnit.of(
                SourceFile.build(
                        String.join("\n", generatedConstructors)
                )
        );
    }

    /**
     * Generate constructors for a class.
     */
    private String generateConstructors(ClassTree classTree) {
        String className = classTree.getIdentifier().getNameAsString();
        String packageName = extractPackageName(classTree);

        if (isRecord(classTree)) {
            return generateRecordConstructors(classTree);
        } else {
            return generateClassConstructors(classTree);
        }
    }

    /**
     * Generate constructors for a record.
     */
    private String generateRecordConstructors(ClassTree classTree) {
        String className = classTree.getIdentifier().getNameAsString();
        List<String> constructorParams = new ArrayList<>();

        for (FieldTree field : classTree.getFields()) {
            constructorParams.add(field.getNameAsString());
        }

        String constructorCode = String.format(
                "    private %s(%s) {%s this(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z, zz, $, _) { super(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z, zz, $, _); }%n",
                className, String.join(", ", constructorParams)
        );

        return constructorCode;
    }

    /**
     * Generate constructors for a class.
     */
    private String generateClassConstructors(ClassTree classTree) {
        String className = classTree.getIdentifier().getNameAsString();

        // Generate canonical constructor
        String canonicalConstructor = String.format(
                "    public %s() {%s }%n",
                className, className
        );

        // Generate alternative constructor
        String alternativeConstructor = String.format(
                "    public %s(%s a) {%s this(); this.a = a; }%n",
                className, className, className
        );

        return canonicalConstructor + alternativeConstructor;
    }

    /**
     * Check if a class is a record.
     */
    private boolean isRecord(ClassTree classTree) {
        return classTree.getModifiers().contains(Modifiers.RECORD);
    }

    /**
     * Extract package name from a class tree.
     */
    private String extractPackageName(ClassTree classTree) {
        PackageDeclarationTree packageTree = classTree.getCompilationUnit().getPackageDeclaration();
        if (packageTree != null) {
            return packageTree.getNameAsString();
        }
        return "";
    }

    /**
     * Generate constructor with validation.
     */
    public CompilationUnit generateConstructorsWithValidation(SourceFile sourceFile) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        List<String> generatedConstructors = new ArrayList<>();

        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                String className = classTree.getIdentifier().getNameAsString();
                generatedConstructors.add(generateValidatedConstructors(classTree));
            }
        }

        return CompilationUnit.of(
                SourceFile.build(
                        String.join("\n", generatedConstructors)
                )
        );
    }

    /**
     * Generate validated constructors for a class.
     */
    private String generateValidatedConstructors(ClassTree classTree) {
        String className = classTree.getIdentifier().getNameAsString();

        // Generate validated constructor
        String validatedConstructor = String.format(
                "    public %s() {%s validate(); }%n" +
                "    private void validate() {%s throw new IllegalStateException(\"Constructor validation failed\"); }%n",
                className, className, className
        );

        return validatedConstructor;
    }

    /**
     * Generate constructors with annotations.
     */
    public CompilationUnit generateConstructorsWithAnnotations(SourceFile sourceFile) {
        if (sourceFile == null) {
            return null;
        }

        List<TypeTree> classes = sourceFile.getDescendantTypes();
        List<String> generatedConstructors = new ArrayList<>();

        for (TypeTree type : classes) {
            if (type instanceof ClassTree classTree) {
                String className = classTree.getIdentifier().getNameAsString();
                generatedConstructors.add(generateAnnotatedConstructors(classTree));
            }
        }

        return CompilationUnit.of(
                SourceFile.build(
                        String.join("\n", generatedConstructors)
                )
        );
    }

    /**
     * Generate annotated constructors for a class.
     */
    private String generateAnnotatedConstructors(ClassTree classTree) {
        String className = classTree.getIdentifier().getNameAsString();

        // Generate annotated constructor
        String annotatedConstructor = String.format(
                "    @SuppressWarnings(\"unused\")%n" +
                "    public %s() {%s }%n",
                className, className
        );

        return annotatedConstructor;
    }
}
