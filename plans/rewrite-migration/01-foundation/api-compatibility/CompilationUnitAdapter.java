// {@link hr.hrg.rewrite.api.CompilationUnitAdapter} Adapter between JavaParser and OpenRewrite AST representations.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.api;

import org.openrewrite.java.tree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter for converting between JavaParser CompilationUnit and OpenRewrite SourceFile.
 * 
 * This class provides conversion utilities between the AST representations
 * used by JavaParser and OpenRewrite, allowing gradual migration of code.
 */
public class CompilationUnitAdapter {

    /**
     * Convert a JavaParser CompilationUnit to OpenRewrite SourceFile.
     * 
     * @param unit The JavaParser compilation unit
     * @return The OpenRewrite source file
     */
    public static SourceFile toOpenRewrite(com.github.javaparser.ast.CompilationUnit unit) {
        // Read the source code
        String source = unit.toString();
        
        // Parse as OpenRewrite
        try {
            return JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse source as OpenRewrite", e);
        }
    }

    /**
     * Convert an OpenRewrite SourceFile to JavaParser CompilationUnit.
     * 
     * @param sourceFile The OpenRewrite source file
     * @return The JavaParser compilation unit
     */
    public static com.github.javaparser.ast.CompilationUnit toJavaParser(SourceFile sourceFile) {
        // Extract the source code
        String source = sourceFile.print();
        
        // Parse as JavaParser
        com.github.javaparser.ast.CompilationUnit unit;
        try {
            unit = new com.github.javaparser.JavaParser()
                .parse(source).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse source as JavaParser", e);
        }
        
        return unit;
    }

    /**
     * Create an OpenRewrite SourceFile from a file path.
     * 
     * @param path The file path
     * @return The OpenRewrite source file
     */
    public static SourceFile fromPath(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + path, e);
        }
    }

    /**
     * Create a JavaParser CompilationUnit from a file path.
     * 
     * @param path The file path
     * @return The JavaParser compilation unit
     */
    public static com.github.javaparser.ast.CompilationUnit fromPathJavaParser(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return new com.github.javaparser.JavaParser()
                .parse(source).get();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + path, e);
        }
    }

    /**
     * Create an OpenRewrite SourceFile from source code string.
     * 
     * @param source The source code
     * @return The OpenRewrite source file
     */
    public static SourceFile fromSource(String source) {
        return JavaParser.fromJavaVersion()
            .setLogWarnings(false)
            .parse(source);
    }

    /**
     * Extract the source code from a JavaParser CompilationUnit.
     * 
     * @param unit The compilation unit
     * @return The source code as a string
     */
    public static String toSource(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.toString();
    }

    /**
     * Extract the source code from an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return The source code as a string
     */
    public static String toSource(SourceFile sourceFile) {
        return sourceFile.print();
    }

    /**
     * Find all classes in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of class declarations
     */
    public static List<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration>
        findClasses(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)
            .map(t -> (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) t)
            .collect(Collectors.toList());
    }

    /**
     * Find all classes in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of class trees
     */
    public static List<TypeTree> findClasses(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .collect(Collectors.toList());
    }

    /**
     * Find all methods in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of method declarations
     */
    public static List<com.github.javaparser.ast.body.MethodDeclaration>
        findMethods(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .map(t -> (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) t)
            .flatMap(c -> c.getMethods().stream())
            .collect(Collectors.toList());
    }

    /**
     * Find all methods in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of method trees
     */
    public static List<MethodTree> findMethods(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllMethods()
            .collect(Collectors.toList());
    }

    /**
     * Find all fields in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of field declarations
     */
    public static List<com.github.javaparser.ast.body.FieldDeclaration>
        findFields(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .map(t -> (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) t)
            .flatMap(c -> c.getFields().stream())
            .collect(Collectors.toList());
    }

    /**
     * Find all fields in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of field trees
     */
    public static List<FieldTree> findFields(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllFields()
            .collect(Collectors.toList());
    }

    /**
     * Find all annotations in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of annotation expressions
     */
    public static List<com.github.javaparser.ast.expr.AnnotationExpr>
        findAnnotations(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .map(t -> (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) t)
            .flatMap(c -> c.getAnnotations().stream())
            .collect(Collectors.toList());
    }

    /**
     * Find all annotations in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of annotation trees
     */
    public static List<AnnotationTree> findAnnotations(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllAnnotations()
            .collect(Collectors.toList());
    }

    /**
     * Find all record declarations in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of record declarations
     */
    public static List<com.github.javaparser.ast.body.RecordDeclaration>
        findRecords(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof com.github.javaparser.ast.body.RecordDeclaration)
            .map(t -> (com.github.javaparser.ast.body.RecordDeclaration) t)
            .collect(Collectors.toList());
    }

    /**
     * Find all record declarations in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of type trees representing records
     */
    public static List<TypeTree> findRecords(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .stream()
            .filter(t -> t instanceof RecordTree)
            .collect(Collectors.toList());
    }

    /**
     * Find all enum declarations in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of enum declarations
     */
    public static List<com.github.javaparser.ast.body.EnumDeclaration>
        findEnums(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof com.github.javaparser.ast.body.EnumDeclaration)
            .map(t -> (com.github.javaparser.ast.body.EnumDeclaration) t)
            .collect(Collectors.toList());
    }

    /**
     * Find all enum declarations in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of type trees representing enums
     */
    public static List<TypeTree> findEnums(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .stream()
            .filter(t -> t instanceof EnumTree)
            .collect(Collectors.toList());
    }

    /**
     * Find all interface declarations in a JavaParser compilation unit.
     * 
     * @param unit The compilation unit
     * @return A list of interface declarations
     */
    public static List<com.github.javaparser.ast.body.InterfaceDeclaration>
        findInterfaces(com.github.javaparser.ast.CompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof com.github.javaparser.ast.body.InterfaceDeclaration)
            .map(t -> (com.github.javaparser.ast.body.InterfaceDeclaration) t)
            .collect(Collectors.toList());
    }

    /**
     * Find all interface declarations in an OpenRewrite SourceFile.
     * 
     * @param sourceFile The source file
     * @return A list of type trees representing interfaces
     */
    public static List<TypeTree> findInterfaces(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .stream()
            .filter(t -> t instanceof InterfaceTree)
            .collect(Collectors.toList());
    }
}
