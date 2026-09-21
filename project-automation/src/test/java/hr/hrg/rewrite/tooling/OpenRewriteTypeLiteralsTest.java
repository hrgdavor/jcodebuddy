package hr.hrg.rewrite.tooling;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRewriteTypeLiterals.
 */
public class OpenRewriteTypeLiteralsTest {

    @Test
    public void testClassLiteralForPrimitiveTypes() {
        assertEquals("java.lang.Integer", OpenRewriteTypeLiterals.classLiteral("int", Set.of()));
        assertEquals("java.lang.Long", OpenRewriteTypeLiterals.classLiteral("long", Set.of()));
        assertEquals("java.lang.Float", OpenRewriteTypeLiterals.classLiteral("float", Set.of()));
        assertEquals("java.lang.Double", OpenRewriteTypeLiterals.classLiteral("double", Set.of()));
        assertEquals("java.lang.Boolean", OpenRewriteTypeLiterals.classLiteral("boolean", Set.of()));
        assertEquals("java.lang.Character", OpenRewriteTypeLiterals.classLiteral("char", Set.of()));
        assertEquals("java.lang.Byte", OpenRewriteTypeLiterals.classLiteral("byte", Set.of()));
        assertEquals("java.lang.Short", OpenRewriteTypeLiterals.classLiteral("short", Set.of()));
    }

    @Test
    public void testClassLiteralForReferenceTypes() {
        assertEquals("String", OpenRewriteTypeLiterals.classLiteral("String", Set.of()));
        assertEquals("Object", OpenRewriteTypeLiterals.classLiteral("Object", Set.of()));
        assertEquals("Integer", OpenRewriteTypeLiterals.classLiteral("Integer", Set.of()));
    }

    @Test
    public void testClassLiteralForTypeParameters() {
        assertEquals("java.lang.Object", 
                OpenRewriteTypeLiterals.classLiteral("T", Set.of("T")));
        assertEquals("java.lang.Object", 
                OpenRewriteTypeLiterals.classLiteral("K", Set.of("K")));
        assertEquals("java.lang.Object", 
                OpenRewriteTypeLiterals.classLiteral("V", Set.of("V")));
    }

    @Test
    public void testSimpleTypeName() {
        assertEquals("String", OpenRewriteTypeLiterals.simpleTypeName("java.lang.String"));
        assertEquals("Person", OpenRewriteTypeLiterals.simpleTypeName("com.example.Person"));
        assertEquals("Person", OpenRewriteTypeLiterals.simpleTypeName("com.example.view.Person"));
        assertEquals("Type", OpenRewriteTypeLiterals.simpleTypeName("java.lang.reflect.Type"));
    }

    @Test
    public void testSimpleTypeNameForBareNames() {
        assertEquals("String", OpenRewriteTypeLiterals.simpleTypeName("String"));
        assertEquals("Object", OpenRewriteTypeLiterals.simpleTypeName("Object"));
    }

    @Test
    public void testNormalizeType() {
        assertEquals("String", OpenRewriteTypeLiterals.normalizeType(" java.lang.String "));
        assertEquals("Integer", OpenRewriteTypeLiterals.normalizeType(" java.util.ArrayList<Integer> "));
        assertEquals("List", OpenRewriteTypeLiterals.normalizeType("java.util.List"));
        assertEquals("Map", OpenRewriteTypeLiterals.normalizeType("java.util.Map"));
    }

    @Test
    public void testNormalizeTypeRemovesPackagePrefixes() {
        assertEquals("String", 
                OpenRewriteTypeLiterals.normalizeType("java.lang.String"));
        assertEquals("ArrayList", 
                OpenRewriteTypeLiterals.normalizeType("java.util.ArrayList"));
        assertEquals("Date", 
                OpenRewriteTypeLiterals.normalizeType("java.time.LocalDate"));
        assertEquals("BigDecimal", 
                OpenRewriteTypeLiterals.normalizeType("java.math.BigDecimal"));
    }

    @Test
    public void testNormalizeTypeForNull() {
        assertEquals("", OpenRewriteTypeLiterals.normalizeType(null));
    }

    @Test
    public void testIsArrayType() {
        assertTrue(OpenRewriteTypeLiterals.isArrayType("String[]"));
        assertTrue(OpenRewriteTypeLiterals.isArrayType("int[]"));
        assertFalse(OpenRewriteTypeLiterals.isArrayType("String"));
        assertFalse(OpenRewriteTypeLiterals.isArrayType("int"));
    }

    @Test
    public void testElementType() {
        assertEquals("String", OpenRewriteTypeLiterals.elementType("String[]"));
        assertEquals("int", OpenRewriteTypeLiterals.elementType("int[]"));
        assertEquals("List", OpenRewriteTypeLiterals.elementType("List[]"));
    }

    @Test
    public void testComponentTypeExpression() {
        assertEquals("java.lang.reflect.Array.newInstance(String, 0).getClass()", 
                OpenRewriteTypeLiterals.componentTypeExpression("String[]"));
        assertEquals("java.lang.reflect.Array.newInstance(int, 0).getClass()", 
                OpenRewriteTypeLiterals.componentTypeExpression("int[]"));
    }
}
