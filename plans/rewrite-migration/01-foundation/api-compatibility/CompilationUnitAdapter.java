// {@link hr.hrg.rewrite.api.CompilationUnitAdapter} Adapter between JavaParser and OpenRewrite AST representations.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.api;

import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import com.github.javaparser.JavaParser as JavaParserParser;
import com.github.javaparser.ast.CompilationUnit as JavaParserCompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.InterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

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
    public static SourceFile toOpenRewrite(JavaParserCompilationUnit unit) {
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
    public static JavaParserCompilationUnit toJavaParser(SourceFile sourceFile) {
        // Extract the source code
        String source = sourceFile.print();
        
        // Parse as JavaParser
        JavaParserCompilationUnit unit;
        try {
            unit = new JavaParserParser()
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
    public static JavaParserCompilationUnit fromPathJavaParser(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return new JavaParserParser()
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
    public static String toSource(JavaParserCompilationUnit unit) {
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
    public static List<ClassOrInterfaceDeclaration>
        findClasses(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof ClassOrInterfaceDeclaration)
            .map(t -> (ClassOrInterfaceDeclaration) t)
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
    public static List<MethodDeclaration>
        findMethods(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .map(t -> (ClassOrInterfaceDeclaration) t)
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
    public static List<FieldDeclaration>
        findFields(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .map(t -> (ClassOrInterfaceDeclaration) t)
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
    public static List<AnnotationExpr>
        findAnnotations(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .map(t -> (ClassOrInterfaceDeclaration) t)
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
    public static List<RecordDeclaration>
        findRecords(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof RecordDeclaration)
            .map(t -> (RecordDeclaration) t)
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
    public static List<EnumDeclaration>
        findEnums(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof EnumDeclaration)
            .map(t -> (EnumDeclaration) t)
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
    public static List<InterfaceDeclaration>
        findInterfaces(JavaParserCompilationUnit unit) {
        return unit.getTypes()
            .stream()
            .filter(t -> t instanceof InterfaceDeclaration)
            .map(t -> (InterfaceDeclaration) t)
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
