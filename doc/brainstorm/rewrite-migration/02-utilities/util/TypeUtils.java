// {@link hr.hrg.rewrite.util.TypeUtils} Type utility methods for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.util;

import org.openrewrite.java.tree.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.ast.expr.*;

import java.util.*;

/**
 * Utility class for type-related operations.
 * Provides methods for type inspection, resolution, and manipulation.
 */
public class TypeUtils {
    
    /**
     * Convert type to string representation.
     * 
     * @param type The type to convert
     * @return String representation of the type
     */
    public static String getTypeAsString(TypeTree type) {
        if (type == null) {
            return "null";
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            return ((JCType) type).describe();
        }
        
        // Handle JavaParser type
        if (type instanceof JType) {
            return type.asString();
        }
        
        // Fallback for other type types
        return type.toString();
    }
    
    /**
     * Get fully qualified name of a type.
     * 
     * @param type The type to get FQN for
     * @return The fully qualified name, or empty string if not available
     */
    public static String getTypeAsFullyQualifiedName(TypeTree type) {
        if (type == null) {
            return "";
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            JCType jcType = (JCType) type;
            if (jcType instanceof JCIdent) {
                JCIdent ident = (JCIdent) jcType;
                return ident.name;
            }
        }
        
        // Handle JavaParser type
        if (type instanceof JTypeReference) {
            return ((JTypeReference) type).asString();
        }
        
        // Fallback
        return "";
    }
    
    /**
     * Check if a type is a primitive type.
     * 
     * @param type The type to check
     * @return true if the type is primitive
     */
    public static boolean isPrimitive(TypeTree type) {
        if (type == null) {
            return false;
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            JCType jcType = (JCType) type;
            if (jcType instanceof JCPrimitiveType) {
                return true;
            }
        }
        
        // Handle JavaParser type
        if (type instanceof JPrimitiveType) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if a type is a reference type.
     * 
     * @param type The type to check
     * @return true if the type is a reference type
     */
    public static boolean isReference(TypeTree type) {
        if (type == null) {
            return false;
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            JCType jcType = (JCType) type;
            if (jcType instanceof JCReferenceType) {
                return true;
            }
        }
        
        // Handle JavaParser type
        if (type instanceof JReferenceType) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if a type is generic.
     * 
     * @param type The type to check
     * @return true if the type is generic
     */
    public static boolean isGeneric(TypeTree type) {
        if (type == null) {
            return false;
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            JCType jcType = (JCType) type;
            if (jcType instanceof JCTypeParameterizedType) {
                return true;
            }
        }
        
        // Handle JavaParser type
        if (type instanceof JParameterizedType) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Get type parameters from a generic type.
     * 
     * @param type The type to get type parameters from
     * @return List of type parameters
     */
    public static List<JTypeParameter> getTypeParameters(TypeTree type) {
        if (type == null) {
            return Collections.emptyList();
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            JCType jcType = (JCType) type;
            if (jcType instanceof JCTypeParameterizedType) {
                JCTypeParameterizedType paramType = (JCTypeParameterizedType) jcType;
                return paramType.getTypeParameters();
            }
        }
        
        // Handle JavaParser type
        if (type instanceof JTypeParameter) {
            return Collections.singletonList((JTypeParameter) type);
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Get type arguments from a generic type.
     * 
     * @param type The type to get type arguments from
     * @return List of type arguments
     */
    public static List<JType> getTypeArguments(TypeTree type) {
        if (type == null) {
            return Collections.emptyList();
        }
        
        // Handle OpenRewrite type
        if (type instanceof JCType) {
            JCType jcType = (JCType) type;
            if (jcType instanceof JCTypeParameterizedType) {
                JCTypeParameterizedType paramType = (JCTypeParameterizedType) jcType;
                return paramType.getTypeArguments();
            }
        }
        
        // Handle JavaParser type
        if (type instanceof JTypeParameterizedType) {
            JTypeParameterizedType paramType = (JTypeParameterizedType) type;
            return paramType.getTypeArguments();
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Resolve a type in a given context.
     * 
     * @param type The type to resolve
     * @param context The type context for resolution
     * @return The resolved type
     */
    public static TypeTree resolveType(TypeTree type, JavaType context) {
        if (type == null) {
            return null;
        }
        
        // This would implement type resolution logic
        // For now, return the original type
        return type;
    }
    
    /**
     * Merge two types together.
     * 
     * @param type1 The first type
     * @param type2 The second type
     * @return The merged type, or null if merging is not possible
     */
    public static TypeTree mergeTypes(TypeTree type1, TypeTree type2) {
        if (type1 == null) {
            return type2;
        }
        if (type2 == null) {
            return type1;
        }
        
        // This would implement type merging logic
        // For now, return null as placeholder
        return null;
    }
    
    /**
     * Check if two types are equal.
     * 
     * @param type1 The first type
     * @param type2 The second type
     * @return true if the types are equal
     */
    public static boolean areTypesEqual(TypeTree type1, TypeTree type2) {
        if (type1 == null && type2 == null) {
            return true;
        }
        if (type1 == null || type2 == null) {
            return false;
        }
        
        // Simple equality check
        return type1.toString().equals(type2.toString());
    }
    
    /**
     * Check if a type is assignable from another type.
     * 
     * @param fromType The type to check assignability from
     * @param toType The type to check assignability to
     * @return true if fromType is assignable to toType
     */
    public static boolean isAssignable(TypeTree fromType, TypeTree toType) {
        if (fromType == null || toType == null) {
            return false;
        }
        
        // This would implement type assignability checking
        // For now, return false as placeholder
        return false;
    }
    
    /**
     * Get primitive counterpart of a type.
     * 
     * @param boxedType The boxed type (e.g., Integer)
     * @return The primitive type (e.g., int), or the same type if already primitive
     */
    public static TypeTree getPrimitive(TypeTree boxedType) {
        if (boxedType == null) {
            return null;
        }
        
        // This would implement primitive type mapping
        // For now, return the same type as placeholder
        return boxedType;
    }
    
    /**
     * Get boxed counterpart of a type.
     * 
     * @param primitiveType The primitive type (e.g., int)
     * @return The boxed type (e.g., Integer), or the same type if already boxed
     */
    public static TypeTree getBoxed(TypeTree primitiveType) {
        if (primitiveType == null) {
            return null;
        }
        
        // This would implement boxed type mapping
        // For now, return the same type as placeholder
        return primitiveType;
    }
}
