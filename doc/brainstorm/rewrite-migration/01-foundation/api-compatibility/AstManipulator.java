// {@link hr.hrg.rewrite.api.AstManipulator} High-level AST manipulation utilities for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.api;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * High-level utilities for common AST manipulation operations.
 * Provides methods for adding, removing, and finding AST nodes.
 */
public class AstManipulator {
    
    /**
     * Add a method declaration to a class.
     */
    public static JavaSourceFile addMethod(JavaSourceFile file, String className, String methodName, String... paramTypes) {
        // Implementation using OpenRewrite recipes would go here
        // For now, return the original file as placeholder
        return file;
    }
    
    /**
     * Add a field declaration to a class.
     */
    public static JavaSourceFile addField(JavaSourceFile file, String className, String fieldName, String fieldType) {
        // Implementation using OpenRewrite recipes would go here
        return file;
    }
    
    /**
     * Remove a method declaration from a class.
     */
    public static JavaSourceFile removeMethod(JavaSourceFile file, String className, String methodName) {
        // Implementation using OpenRewrite recipes would go here
        return file;
    }
    
    /**
     * Remove a field declaration from a class.
     */
    public static JavaSourceFile removeField(JavaSourceFile file, String className, String fieldName) {
        // Implementation using OpenRewrite recipes would go here
        return file;
    }
    
    /**
     * Add an annotation to a class, method, or field.
     */
    public static JavaSourceFile addAnnotation(JavaSourceFile file, String targetLocation, String annotationName) {
        // Implementation using OpenRewrite recipes would go here
        return file;
    }
    
    /**
     * Find all class declarations in the AST.
     */
    public static List<JClassDeclaration> findAllClasses(JavaSourceFile tree) {
        return tree.findAll(JClassDeclaration.class);
    }
    
    /**
     * Find all method declarations in the AST.
     */
    public static List<JMethodDeclaration> findAllMethods(JavaSourceFile tree) {
        return tree.findAll(JMethodDeclaration.class);
    }
    
    /**
     * Find all field declarations in the AST.
     */
    public static List<JVariable> findAllFields(JavaSourceFile tree) {
        return tree.findAll(JVariable.class);
    }
    
    /**
     * Find all annotations in the AST.
     */
    public static List<J.Annotation> findAllAnnotations(JavaSourceFile tree) {
        return tree.findAll(J.Annotation.class);
    }
    
    /**
     * Find all record declarations in the AST.
     */
    public static List<JRecordDeclaration> findAllRecords(JavaSourceFile tree) {
        return tree.findAll(JRecordDeclaration.class);
    }
    
    /**
     * Find all enum declarations in the AST.
     */
    public static List<JClassDeclaration> findAllEnums(JavaSourceFile tree) {
        return tree.findAll(JClassDeclaration.class)
            .stream()
            .filter(decl -> decl.getModifiers().isEnum())
            .collect(Collectors.toList());
    }
    
    /**
     * Find all interface declarations in the AST.
     */
    public static List<JInterfaceDeclaration> findAllInterfaces(JavaSourceFile tree) {
        return tree.findAll(JInterfaceDeclaration.class);
    }
    
    /**
     * Find all constructors in the AST.
     */
    public static List<JMethodDeclaration> findAllConstructors(JavaSourceFile tree) {
        return tree.findAll(JMethodDeclaration.class)
            .stream()
            .filter(method -> {
                JClassDeclaration declaringClass = method.getDeclaringClass();
                if (declaringClass == null) {
                    return false;
                }
                String declaringClassName = declaringClass.getSimpleName();
                String methodName = method.getSimpleName();
                return declaringClassName.equals(methodName);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Create a class declaration.
     */
    public static JClassDeclaration addClassDeclaration(String className) {
        // Implementation would create a new JClassDeclaration
        return null;
    }
    
    /**
     * Create a method declaration.
     */
    public static JMethodDeclaration createMethodDeclaration(String methodName, String... paramTypes) {
        // Implementation would create a new JMethodDeclaration
        return null;
    }
    
    /**
     * Get type information from a node.
     */
    public static JCType getType(JCNode node) {
        if (node instanceof JCMethodDeclaration) {
            return ((JCMethodDeclaration) node).getReturnType();
        } else if (node instanceof JCVariable) {
            return ((JCVariable) node).getType();
        } else if (node instanceof JCClassDecl) {
            return ((JCClassDecl) node).getTypeParameters() == null 
                ? JCType.TYPETOKEN.get() 
                : ((JCClassDecl) node).getTypeParameters().get(0).getType();
        }
        return null;
    }
    
    /**
     * Get type arguments from a node.
     */
    public static List<JCType> getTypeArguments(JCNode node) {
        if (node instanceof JCParameterizedType) {
            JCParameterizedType type = (JCParameterizedType) node;
            return type.getTypeArguments();
        }
        return Collections.emptyList();
    }
    
    /**
     * Get type parameters from a node.
     */
    public static List<JCTypeParameter> getTypeParameters(JCNode node) {
        if (node instanceof JCTypeParameter) {
            return Collections.singletonList((JCTypeParameter) node);
        } else if (node instanceof JCClassDecl) {
            JCClassDecl classDecl = (JCClassDecl) node;
            return classDecl.getTypeParameters();
        }
        return Collections.emptyList();
    }
    
    /**
     * Get modifiers from a node.
     */
    public static JCModifiers getModifiers(JCNode node) {
        return node.getModifiers();
    }
    
    /**
     * Check if a modifier is present.
     */
    public static boolean hasModifier(JCNode node, Modifier modifier) {
        JCModifiers modifiers = node.getModifiers();
        if (modifiers == null) {
            return false;
        }
        // Implementation would check modifier flags
        return false;
    }
    
    /**
     * Convert modifiers to string.
     */
    public static String getModifiersAsString(JCNode node) {
        JCModifiers modifiers = node.getModifiers();
        if (modifiers == null) {
            return "";
        }
        // Implementation would build string from modifier flags
        return "";
    }
}
