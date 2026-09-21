// {@link hr.hrg.rewrite.util.NodeTraversal} AST traversal utilities for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.util;

import org.openrewrite.java.tree.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for traversing and finding nodes in Java ASTs.
 * Provides methods for finding classes, methods, fields, annotations, records, enums, and interfaces.
 */
public class NodeTraversal {
    
    /**
     * Find all class declarations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all class declarations
     */
    public static List<JClassDeclaration> findAllClasses(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JClassDeclaration.class);
    }
    
    /**
     * Find all method declarations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all method declarations
     */
    public static List<JMethodDeclaration> findAllMethods(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JMethodDeclaration.class);
    }
    
    /**
     * Find all field declarations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all field declarations
     */
    public static List<JVariable> findAllFields(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JVariable.class);
    }
    
    /**
     * Find all annotations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all annotations
     */
    public static List<J.Annotation> findAllAnnotations(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(J.Annotation.class);
    }
    
    /**
     * Find all record declarations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all record declarations
     */
    public static List<JRecordDeclaration> findAllRecords(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JRecordDeclaration.class);
    }
    
    /**
     * Find all enum declarations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all enum declarations
     */
    public static List<JClassDeclaration> findAllEnums(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JClassDeclaration.class)
            .stream()
            .filter(decl -> decl.getModifiers().isEnum())
            .collect(Collectors.toList());
    }
    
    /**
     * Find all interface declarations in the AST.
     * 
     * @param tree The AST tree to search
     * @return List of all interface declarations
     */
    public static List<JInterfaceDeclaration> findAllInterfaces(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JInterfaceDeclaration.class);
    }
    
    /**
     * Find all constructors in the AST.
     * Constructors have the same name as their declaring class.
     * 
     * @param tree The AST tree to search
     * @return List of all constructor declarations
     */
    public static List<JMethodDeclaration> findAllConstructors(JavaSourceFile tree) {
        if (tree == null) {
            return Collections.emptyList();
        }
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
     * Find classes by fully qualified name.
     * 
     * @param tree The AST tree to search
     * @param fullyQualifiedName The FQN to search for
     * @return List of classes matching the FQN
     */
    public static List<JClassDeclaration> findAllClassesByType(JavaSourceFile tree, String fullyQualifiedName) {
        if (tree == null || fullyQualifiedName == null) {
            return Collections.emptyList();
        }
        return tree.findAll(JClassDeclaration.class)
            .stream()
            .filter(decl -> {
                String className = decl.getFullyQualifiedName();
                return className != null && className.equals(fullyQualifiedName);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Find methods by signature (class name, method name, and parameter types).
     * 
     * @param tree The AST tree to search
     * @param className The declaring class name
     * @param methodName The method name
     * @param paramTypes The parameter types as type strings
     * @return List of methods matching the signature
     */
    public static List<JMethodDeclaration> findMethodsBySignature(
            JavaSourceFile tree, String className, String methodName, String... paramTypes) {
        if (tree == null || className == null || methodName == null) {
            return Collections.emptyList();
        }
        
        if (paramTypes.length == 0) {
            // Find all methods with the given name in the given class
            return tree.findAll(JMethodDeclaration.class)
                .stream()
                .filter(method -> {
                    JClassDeclaration declaringClass = method.getDeclaringClass();
                    if (declaringClass == null) {
                        return false;
                    }
                    String declaringClassName = declaringClass.getFullyQualifiedName();
                    if (!declaringClassName.equals(className)) {
                        return false;
                    }
                    return method.getSimpleName().equals(methodName);
                })
                .collect(Collectors.toList());
        }
        
        // Find methods with exact signature match
        return tree.findAll(JMethodDeclaration.class)
            .stream()
            .filter(method -> {
                JClassDeclaration declaringClass = method.getDeclaringClass();
                if (declaringClass == null) {
                    return false;
                }
                
                String declaringClassName = declaringClass.getFullyQualifiedName();
                if (!declaringClassName.equals(className)) {
                    return false;
                }
                
                if (!method.getSimpleName().equals(methodName)) {
                    return false;
                }
                
                // Compare parameter types
                List<JType> methodParams = method.getParameters();
                if (methodParams.size() != paramTypes.length) {
                    return false;
                }
                
                for (int i = 0; i < paramTypes.length; i++) {
                    String methodParamType = methodParams.get(i).describe();
                    if (!methodParamType.equals(paramTypes[i])) {
                        return false;
                    }
                }
                
                return true;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Get type information from a node.
     * 
     * @param node The AST node
     * @return The type of the node, or null if not applicable
     */
    public static JCType getType(JCNode node) {
        if (node instanceof JCMethodDeclaration) {
            return ((JCMethodDeclaration) node).getReturnType();
        } else if (node instanceof JCVariable) {
            return ((JCVariable) node).getType();
        } else if (node instanceof JCClassDecl) {
            JCClassDecl classDecl = (JCClassDecl) node;
            return classDecl.getTypeParameters() == null 
                ? JCType.TYPETOKEN.get() 
                : classDecl.getTypeParameters().get(0).getType();
        } else if (node instanceof JClassDeclaration) {
            JClassDeclaration classDecl = (JClassDeclaration) node;
            return classDecl.getType() != null ? classDecl.getType() : null;
        }
        return null;
    }
    
    /**
     * Get type arguments from a node.
     * 
     * @param node The AST node
     * @return List of type arguments, or empty list if not applicable
     */
    public static List<JCType> getTypeArguments(JCNode node) {
        if (node instanceof JCParameterizedType) {
            JCParameterizedType type = (JCParameterizedType) node;
            return type.getTypeArguments();
        }
        if (node instanceof JTypeParameter) {
            JTypeParameter typeParam = (JTypeParameter) node;
            return typeParam.getTypeArguments();
        }
        return Collections.emptyList();
    }
    
    /**
     * Get type parameters from a node.
     * 
     * @param node The AST node
     * @return List of type parameters, or empty list if not applicable
     */
    public static List<JTypeParameter> getTypeParameters(JCNode node) {
        if (node instanceof JTypeParameter) {
            return Collections.singletonList((JTypeParameter) node);
        } else if (node instanceof JClassDeclaration) {
            JClassDeclaration classDecl = (JClassDeclaration) node;
            return classDecl.getTypeParameters();
        } else if (node instanceof JCClassDecl) {
            JCClassDecl classDecl = (JCClassDecl) node;
            return classDecl.getTypeParameters();
        }
        return Collections.emptyList();
    }
    
    /**
     * Get modifiers from a node.
     * 
     * @param node The AST node
     * @return The modifiers, or null if not applicable
     */
    public static JCModifiers getModifiers(JCNode node) {
        return node.getModifiers();
    }
    
    /**
     * Check if a modifier is present on a node.
     * 
     * @param node The AST node
     * @param modifier The modifier to check (PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, etc.)
     * @return true if the modifier is present
     */
    public static boolean hasModifier(JCNode node, Modifier modifier) {
        JCModifiers modifiers = node.getModifiers();
        if (modifiers == null) {
            return false;
        }
        
        switch (modifier) {
            case PUBLIC:
                return modifiers.isPublic();
            case PRIVATE:
                return modifiers.isPrivate();
            case PROTECTED:
                return modifiers.isProtected();
            case STATIC:
                return modifiers.isStatic();
            case FINAL:
                return modifiers.isFinal();
            case ABSTRACT:
                return modifiers.isAbstract();
            case ANNOTATED:
                return !modifiers.getAnnotations().isEmpty();
            case INTERFACE:
                return modifiers.isInterface();
            case ENUM:
                return modifiers.isEnum();
            case RECORD:
                return modifiers.isRecord();
            default:
                return false;
        }
    }
    
    /**
     * Convert modifiers to string representation.
     * 
     * @param node The AST node
     * @return String representation of modifiers
     */
    public static String getModifiersAsString(JCNode node) {
        JCModifiers modifiers = node.getModifiers();
        if (modifiers == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        if (modifiers.isPublic()) {
            sb.append("public ");
        }
        if (modifiers.isPrivate()) {
            sb.append("private ");
        }
        if (modifiers.isProtected()) {
            sb.append("protected ");
        }
        if (modifiers.isStatic()) {
            sb.append("static ");
        }
        if (modifiers.isFinal()) {
            sb.append("final ");
        }
        if (modifiers.isAbstract()) {
            sb.append("abstract ");
        }
        if (modifiers.isTransient()) {
            sb.append("transient ");
        }
        if (modifiers.isVolatile()) {
            sb.append("volatile ");
        }
        if (modifiers.isStrictfp()) {
            sb.append("strictfp ");
        }
        
        if (!sb.toString().isEmpty()) {
            sb.setLength(sb.length() - 2); // Remove trailing space
        }
        
        return sb.toString();
    }
}
