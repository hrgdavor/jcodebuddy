package hr.hrg.rewrite.tooling;

import java.util.Set;

/**
 * Type literals utility class.
 *
 * <p>This class maintains the JavaParser API while internally using OpenRewrite for type manipulation.
 * It provides utilities for getting type literals and class names.</p>
 */
public final class OpenRewriteTypeLiterals {

    private OpenRewriteTypeLiterals() {
    }

    /**
     * Gets the class literal expression for a type name.
     *
     * @param typeName the type name
     * @param typeParameterNames the set of type parameter names to exclude
     * @return the class literal expression
     */
    public static String classLiteral(String typeName, Set<String> typeParameterNames) {
        typeName = typeName.trim();
        
        // Check if it's a primitive type
        if (isPrimitiveType(typeName)) {
            return boxedPrimitiveClass(typeName);
        }
        
        // Check if it's a type parameter (e.g., T, K, V)
        if (typeParameterNames != null && typeParameterNames.contains(typeName)) {
            return "java.lang.Object";
        }
        
        // Otherwise, return the type name as-is
        return typeName;
    }

    /**
     * Checks if a type name represents a primitive type.
     *
     * @param typeName the type name to check
     * @return true if the type is primitive
     */
    private static boolean isPrimitiveType(String typeName) {
        return switch (typeName.toLowerCase()) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char" -> true;
            default -> false;
        };
    }

    /**
     * Gets the boxed primitive class name.
     *
     * @param typeName the primitive type name
     * @return the boxed primitive class name
     */
    private static String boxedPrimitiveClass(String typeName) {
        return switch (typeName.toLowerCase()) {
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            case "boolean" -> "java.lang.Boolean";
            case "char" -> "java.lang.Character";
            default -> typeName;
        };
    }

    /**
     * Gets the simple name of a type (removes package qualifiers).
     *
     * @param typeName the fully qualified type name
     * @return the simple type name
     */
    public static String simpleTypeName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        if (lastDot >= 0) {
            return typeName.substring(lastDot + 1);
        }
        return typeName;
    }

    /**
     * Normalizes a type expression by removing whitespace and common package prefixes.
     *
     * @param expression the type expression to normalize
     * @return the normalized expression
     */
    public static String normalizeType(String expression) {
        if (expression == null) {
            return "";
        }
        
        String normalized = expression.replaceAll("\\s+", "");
        for (String prefix : commonPackagePrefixes()) {
            normalized = normalized.replace(prefix, "");
        }
        return normalized;
    }

    /**
     * Gets common package prefixes to strip from type names.
     */
    private static Set<String> commonPackagePrefixes() {
        return Set.of(
            "java.lang.",
            "java.util.",
            "java.time.",
            "java.math.",
            "java.sql.",
            "java.lang.reflect.",
            "hr.hrg.hipster.entity.api."
        );
    }

    /**
     * Checks if a type name is an array type.
     *
     * @param typeName the type name
     * @return true if the type is an array
     */
    public static boolean isArrayType(String typeName) {
        return typeName != null && typeName.endsWith("[]");
    }

    /**
     * Gets the element type of an array type.
     *
     * @param typeName the array type name
     * @return the element type name
     */
    public static String elementType(String typeName) {
        if (isArrayType(typeName)) {
            return typeName.substring(0, typeName.length() - 2);
        }
        return typeName;
    }

    /**
     * Gets the component type for array types in enum constants.
     *
     * @param typeName the type name
     * @return the component type expression
     */
    public static String componentTypeExpression(String typeName) {
        if (isArrayType(typeName)) {
            return "java.lang.reflect.Array.newInstance(" + 
                   componentTypeExpression(elementType(typeName)) + ", 0).getClass()";
        }
        return "classLiteral(" + normalizeType(typeName) + ")";
    }
}
