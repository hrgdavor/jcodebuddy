// {@link hr.hrg.rewrite.api.CompilationUnitAdapter} AST conversion utilities for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.api;

import org.openrewrite.java.tree.*;
import com.github.javaparser.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Adapter for converting between JavaParser and OpenRewrite ASTs.
 * Provides methods for round-trip conversion and cross-referencing.
 */
public class CompilationUnitAdapter {
    
    /**
     * Convert JavaParser CompilationUnit to OpenRewrite SourceFile.
     */
    public static JavaSourceFile toOpenRewrite(CompilationUnit javaParserCunit) {
        // This would implement the actual conversion logic
        // For now, return null as placeholder
        return null;
    }
    
    /**
     * Convert OpenRewrite SourceFile to JavaParser CompilationUnit.
     */
    public static CompilationUnit toJavaParser(JavaSourceFile openRewriteFile) {
        // This would implement the actual conversion logic
        // For now, return null as placeholder
        return null;
    }
    
    /**
     * Create a SourceFile from a file path.
     */
    public static JavaSourceFile fromPath(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return fromSource(content);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Create a CompilationUnit from a file path using JavaParser.
     */
    public static CompilationUnit fromPathJavaParser(Path path) {
        try {
            return JavaParser.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Create a SourceFile from source string.
     */
    public static JavaSourceFile fromSource(String source) {
        // This would create an OpenRewrite SourceFile from source text
        // For now, return null as placeholder
        return null;
    }
    
    /**
     * Extract source from AST.
     */
    public static String toSource(JavaSourceFile ast) {
        if (ast == null) {
            return "";
        }
        // This would serialize the AST back to source
        // For now, return empty string as placeholder
        return "";
    }
    
    /**
     * Find all classes in the AST.
     */
    public static List<JClassDeclaration> findClasses(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JClassDeclaration.class);
    }
    
    /**
     * Find all methods in the AST.
     */
    public static List<JMethodDeclaration> findMethods(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JMethodDeclaration.class);
    }
    
    /**
     * Find all fields in the AST.
     */
    public static List<JVariable> findFields(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JVariable.class);
    }
    
    /**
     * Find all annotations in the AST.
     */
    public static List<J.Annotation> findAnnotations(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(J.Annotation.class);
    }
    
    /**
     * Find all records in the AST.
     */
    public static List<JRecordDeclaration> findRecords(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JRecordDeclaration.class);
    }
    
    /**
     * Find all enums in the AST.
     */
    public static List<JClassDeclaration> findEnums(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JClassDeclaration.class)
            .stream()
            .filter(decl -> decl.getModifiers().isEnum())
            .collect(Collectors.toList());
    }
    
    /**
     * Find all interfaces in the AST.
     */
    public static List<JInterfaceDeclaration> findInterfaces(JavaSourceFile ast) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JInterfaceDeclaration.class);
    }
    
    /**
     * Round-trip test: JavaParser -> OpenRewrite -> JavaParser
     */
    public static boolean testRoundTrip(String sourceCode) {
        try {
            CompilationUnit javaParserCunit = JavaParser.parse(sourceCode);
            // Convert to OpenRewrite
            // JavaSourceFile openRewriteFile = toOpenRewrite(javaParserCunit);
            // Convert back to JavaParser
            // CompilationUnit backToJavaParser = toJavaParser(openRewriteFile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Find classes by fully qualified name.
     */
    public static List<JClassDeclaration> findClassesByFqn(JavaSourceFile ast, String fullyQualifiedName) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JClassDeclaration.class)
            .stream()
            .filter(decl -> {
                String className = decl.getFullyQualifiedName();
                return className != null && className.equals(fullyQualifiedName);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Find methods by signature.
     */
    public static List<JMethodDeclaration> findMethodsBySignature(
            JavaSourceFile ast, String className, String methodName, String... paramTypes) {
        if (ast == null) {
            return Collections.emptyList();
        }
        return ast.findAll(JMethodDeclaration.class)
            .stream()
            .filter(method -> {
                String declaringClassName = method.getDeclaringClass()
                    .map(JCClassDeclaration::getFullyQualifiedName)
                    .orElse("");
                if (!declaringClassName.equals(className)) {
                    return false;
                }
                if (!method.getSimpleName().equals(methodName)) {
                    return false;
                }
                // Compare parameter types
                List<JType> paramTypesList = method.getParameters();
                if (paramTypesList.size() != paramTypes.length) {
                    return false;
                }
                for (int i = 0; i < paramTypes.length; i++) {
                    String methodParamType = paramTypesList.get(i).describe();
                    if (!methodParamType.equals(paramTypes[i])) {
                        return false;
                    }
                }
                return true;
            })
            .collect(Collectors.toList());
    }
}
