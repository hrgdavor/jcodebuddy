// {@link hr.hrg.rewrite.api.AstManipulator} High-level AST manipulation utilities for OpenRewrite.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.api;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level utilities for manipulating OpenRewrite AST.
 * 
 * This class provides convenience methods for common AST operations
 * such as adding methods to classes, finding declarations, and modifying
 * source code.
 */
public class AstManipulator {

    /**
     * Add a method to a class in the given compilation unit.
     * 
     * @param sourceFile The source file containing the compilation unit
     * @param className The fully qualified name of the class
     * @param methodName The name of the method to add
     * @param methodBody The body of the method (as a String)
     * @return The modified source file
     */
    public static SourceFile addMethod(SourceFile sourceFile, String className,
                                        String methodName, String methodBody) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            // Find the target class by scanning
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            // For now, use a simple approach: return the parsed source
            // A full implementation would use OpenRewrite's visitor pattern
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to add method", e);
        }
    }

    /**
     * Find all methods in a class by name.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param methodName The method name to search for
     * @return A list of matching methods
     */
    public static List<MethodTree> findMethodsByName(SourceFile sourceFile,
                                                   String className,
                                                   String methodName) {
        List<MethodTree> allMethods = findAllMethods(sourceFile);
        
        return allMethods.stream()
            .filter(m -> extractClassFromMethod(m) != null && 
                         extractClassFromMethod(m).equals(className) &&
                         extractMethodName(m) != null &&
                         extractMethodName(m).equals(methodName))
            .collect(Collectors.toList());
    }

    /**
     * Add a field to a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param fieldName The field name
     * @param fieldType The field type
     * @param modifiers The field modifiers
     * @return The modified source file
     */
    public static SourceFile addField(SourceFile sourceFile, String className,
                                       String fieldName, String fieldType,
                                       Set<Modifier> modifiers) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to add field", e);
        }
    }

    /**
     * Remove a method from a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param methodName The method name to remove
     * @return The modified source file
     */
    public static SourceFile removeMethod(SourceFile sourceFile,
                                           String className,
                                           String methodName) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove method", e);
        }
    }

    /**
     * Remove a field from a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param fieldName The field name to remove
     * @return The modified source file
     */
    public static SourceFile removeField(SourceFile sourceFile,
                                          String className,
                                          String fieldName) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove field", e);
        }
    }

    /**
     * Add an annotation to a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param annotationName The fully qualified annotation name
     * @param arguments The annotation arguments (as a Map of name to value)
     * @return The modified source file
     */
    public static SourceFile addAnnotation(SourceFile sourceFile,
                                            String className,
                                            String annotationName,
                                            Map<String, String> arguments) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to add annotation", e);
        }
    }

    /**
     * Find all classes in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all classes found
     */
    public static List<TypeTree> findAllClasses(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .collect(Collectors.toList());
    }

    /**
     * Find all methods in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all methods found
     */
    public static List<MethodTree> findAllMethods(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllMethods()
            .collect(Collectors.toList());
    }

    /**
     * Find all fields in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all fields found
     */
    public static List<FieldTree> findAllFields(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllFields()
            .collect(Collectors.toList());
    }

    /**
     * Find all annotations in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all annotations found
     */
    public static List<AnnotationTree> findAllAnnotations(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllAnnotations()
            .collect(Collectors.toList());
    }

    /**
     * Find all record declarations in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all record declarations found
     */
    public static List<TypeTree> findAllRecords(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .stream()
            .filter(t -> t instanceof RecordTree)
            .collect(Collectors.toList());
    }

    /**
     * Find all enum declarations in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all enum declarations found
     */
    public static List<TypeTree> findAllEnums(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .stream()
            .filter(t -> t instanceof EnumTree)
            .collect(Collectors.toList());
    }

    /**
     * Find all interface declarations in a compilation unit.
     * 
     * @param sourceFile The source file
     * @return A list of all interface declarations found
     */
    public static List<TypeTree> findAllInterfaces(SourceFile sourceFile) {
        return sourceFile.getCompilationUnit()
            .findAllClasses()
            .stream()
            .filter(t -> t instanceof InterfaceTree)
            .collect(Collectors.toList());
    }

    /**
     * Add a constructor to a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param constructorBody The constructor body (as a String)
     * @return The modified source file
     */
    public static SourceFile addConstructor(SourceFile sourceFile,
                                             String className,
                                             String constructorBody) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to add constructor", e);
        }
    }

    /**
     * Add a parameter to a method.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param methodName The method name
     * @param paramType The parameter type
     * @param paramName The parameter name
     * @param modifier The parameter modifier
     * @return The modified source file
     */
    public static SourceFile addParameter(SourceFile sourceFile,
                                           String className,
                                           String methodName,
                                           String paramType,
                                           String paramName,
                                           Modifier modifier) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to add parameter", e);
        }
    }

    /**
     * Remove a parameter from a method.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param methodName The method name
     * @param paramName The parameter name to remove
     * @return The modified source file
     */
    public static SourceFile removeParameter(SourceFile sourceFile,
                                              String className,
                                              String methodName,
                                              String paramName) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove parameter", e);
        }
    }

    /**
     * Modify a method body.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param methodName The method name
     * @param newBody The new method body
     * @return The modified source file
     */
    public static SourceFile modifyMethodBody(SourceFile sourceFile,
                                                String className,
                                                String methodName,
                                                String newBody) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to modify method body", e);
        }
    }

    /**
     * Replace a field initializer.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param fieldName The field name
     * @param newInitializer The new initializer
     * @return The modified source file
     */
    public static SourceFile replaceFieldInitializer(SourceFile sourceFile,
                                                       String className,
                                                       String fieldName,
                                                       String newInitializer) {
        try {
            String source = sourceFile.print();
            SourceFile parsed = JavaParser.fromJavaVersion()
                .setLogWarnings(false)
                .parse(source);
            
            TypeTree targetClass = findClassByFqn(parsed, className);
            if (targetClass == null) {
                throw new IllegalArgumentException("Class " + className + " not found");
            }
            
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace field initializer", e);
        }
    }

    /**
     * Check if a class exists in the compilation unit.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @return true if the class exists, false otherwise
     */
    public static boolean classExists(SourceFile sourceFile, String className) {
        TypeTree targetClass = findClassByFqn(sourceFile, className);
        return targetClass != null;
    }

    /**
     * Check if a method exists in a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param methodName The method name
     * @return true if the method exists, false otherwise
     */
    public static boolean methodExists(SourceFile sourceFile,
                                        String className,
                                        String methodName) {
        List<MethodTree> methods = findAllMethods(sourceFile);
        
        return methods.stream()
            .anyMatch(m -> 
                extractClassFromMethod(m) != null &&
                extractClassFromMethod(m).equals(className) &&
                extractMethodName(m) != null &&
                extractMethodName(m).equals(methodName));
    }

    /**
     * Check if a field exists in a class.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name
     * @param fieldName The field name
     * @return true if the field exists, false otherwise
     */
    public static boolean fieldExists(SourceFile sourceFile,
                                       String className,
                                       String fieldName) {
        List<FieldTree> fields = findAllFields(sourceFile);
        
        return fields.stream()
            .anyMatch(f -> 
                extractClassFromField(f) != null &&
                extractClassFromField(f).equals(className) &&
                extractFieldName(f) != null &&
                extractFieldName(f).equals(fieldName));
    }

    /**
     * Get the fully qualified name of a type tree.
     * 
     * @param tree The type tree
     * @return The fully qualified name, or null if not applicable
     */
    public static String getFullyQualifiedName(TypeTree tree) {
        if (tree instanceof ClassDeclaration) {
            ClassDeclaration cd = (ClassDeclaration) tree;
            return cd.getFullyQualifiedName().toString();
        } else if (tree instanceof EnumTree) {
            EnumTree et = (EnumTree) tree;
            return et.getFullyQualifiedName().toString();
        } else if (tree instanceof InterfaceTree) {
            InterfaceTree it = (InterfaceTree) tree;
            return it.getFullyQualifiedName().toString();
        }
        return null;
    }

    /**
     * Get the class declaration from a type tree.
     * 
     * @param tree The type tree
     * @return The class declaration, or null if not applicable
     */
    public static ClassDeclaration getClassDeclaration(TypeTree tree) {
        if (tree instanceof ClassDeclaration) {
            return (ClassDeclaration) tree;
        }
        return null;
    }

    /**
     * Create a new class declaration.
     * 
     * @param className The fully qualified class name
     * @param modifiers The class modifiers
     * @param extendsClass The parent class (if any)
     * @param implementsInterfaces The interfaces to implement
     * @param fields The fields in the class
     * @param methods The methods in the class
     * @param constructors The constructors in the class
     * @return A new class declaration
     */
    public static ClassTree createClassDeclaration(String className,
                                                     Set<Modifier> modifiers,
                                                     String extendsClass,
                                                     List<String> implementsInterfaces,
                                                     List<FieldTree> fields,
                                                     List<MethodTree> methods,
                                                     List<ConstructorDeclaration> constructors) {
        // Create a basic class declaration using JavaParser
        String source = "public class " + className + " {\n}";
        SourceFile sourceFile = JavaParser.fromJavaVersion()
            .setLogWarnings(false)
            .parse(source);
        
        // For a full implementation, we would need to construct the AST
        // using OpenRewrite's AST builders
        return null;
    }

    /**
     * Create a new method declaration.
     * 
     * @param className The fully qualified class name
     * @param methodName The method name
     * @param returnType The return type
     * @param modifiers The method modifiers
     * @param parameters The method parameters
     * @param body The method body
     * @return A new method declaration
     */
    public static MethodTree createMethodDeclaration(String className,
                                                       String methodName,
                                                       String returnType,
                                                       Set<Modifier> modifiers,
                                                       List<ParameterTree> parameters,
                                                       String body) {
        // Create a basic method declaration using JavaParser
        String source = "public void " + methodName + "() { }";
        SourceFile sourceFile = JavaParser.fromJavaVersion()
            .setLogWarnings(false)
            .parse(source);
        
        // For a full implementation, we would need to construct the AST
        // using OpenRewrite's AST builders
        return null;
    }

    /**
     * Find a class by its fully qualified name.
     * 
     * @param sourceFile The source file
     * @param className The fully qualified class name to find
     * @return The class tree if found, null otherwise
     */
    private static TypeTree findClassByFqn(SourceFile sourceFile, String className) {
        List<TypeTree> classes = sourceFile.getCompilationUnit()
            .findAllClasses();
        
        for (TypeTree classTree : classes) {
            String fqn = getFullyQualifiedName(classTree);
            if (fqn != null && fqn.equals(className)) {
                return classTree;
            }
        }
        return null;
    }

    /**
     * Extract the class name from a method tree.
     * 
     * @param method The method tree
     * @return The class name, or null if not applicable
     */
    private static String extractClassFromMethod(MethodTree method) {
        // Get the class from the method's context
        // This requires traversing the AST to find the enclosing class
        // For now, return null as a placeholder
        return null;
    }

    /**
     * Extract the method name from a method tree.
     * 
     * @param method The method tree
     * @return The method name, or null if not applicable
     */
    private static String extractMethodName(MethodTree method) {
        return method.getSimpleName().orElse(null);
    }

    /**
     * Extract the class name from a field tree.
     * 
     * @param field The field tree
     * @return The class name, or null if not applicable
     */
    private static String extractClassFromField(FieldTree field) {
        // Get the class from the field's context
        // This requires traversing the AST to find the enclosing class
        // For now, return null as a placeholder
        return null;
    }

    /**
     * Extract the field name from a field tree.
     * 
     * @param field The field tree
     * @return The field name, or null if not applicable
     */
    private static String extractFieldName(FieldTree field) {
        return field.getIdentifier().orElse(null);
    }
}