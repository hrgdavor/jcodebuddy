// {@link com.codebuddy.merge.TypeChangeConflictResolverTest} Tests for the type change conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the widening rule: adopting the wider of two types is automatic,
 * anything else needs a reviewer.
 */
class TypeChangeConflictResolverTest extends AbstractResolverTest {

    private final TypeChangeConflictResolver resolver = new TypeChangeConflictResolver();

    @Override
    protected ConflictResolver resolverUnderTest() {
        return resolver;
    }

    @Override
    protected Conflict conflictFor(ConflictType type) {
        return ConflictFixtures.sample(type);
    }

    @Override
    protected List<ConflictType> unsupportedTypes() {
        return List.of(ConflictType.IMPORT_ADD, ConflictType.PACKAGE_CHANGE);
    }

    @Test
    @DisplayName("adopts the wider type declared on branch 2")
    void adoptsWiderType() {
        // Fixture: base int, branch 1 long, branch 2 double. double is the widest,
        // so branch 2 supplies the declaration that is adopted.
        Conflict conflict = ConflictFixtures.sample(ConflictType.TYPE_CHANGE);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy(),
            "double is wider than long, so branch 2's declaration wins");
        assertTrue(resolution.getResolvedCode().contains("double count"),
            "the widened declaration must be adopted: " + resolution.getResolvedCode());
        assertTrue(resolution.getExplanation().contains("double"),
            "the explanation must name the adopted type: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("adopts the wider type when branch 2 is the wider one")
    void adoptsWiderTypeFromBranch2() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "branch 2 widens", "int count = 0;", "int count = 0;", "long count = 0;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("long count"));
    }

    @Test
    @DisplayName("escalates unrelated types to a reviewer")
    void escalatesUnrelatedTypes() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "unrelated types", "int count = 0;", "int count = 0;", "String count = \"0\";");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "int and String have no widening relationship");
        assertFalse(resolution.getAlternativePaths().isEmpty());
    }

    @Test
    @DisplayName("declines when both branches agree on the type")
    void declinesWhenTypesAgree() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "same type", "int count = 0;", "long count = 0;", "long count = 0;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "an agreed type change is not this resolver's conflict");
    }

    @ParameterizedTest
    @CsvSource({
        "long, int, true",
        "double, float, true",
        "Object, String, true",
        "Long, Integer, true",
        "int, long, false",
        "String, Integer, false",
        "int, int, false"
    })
    @DisplayName("classifies widening relationships correctly")
    void classifiesWidening(String wider, String narrower, boolean expected) {
        // widens(wider, narrower) answers: can a narrower value be widened to wider?
        assertEquals(expected, TypeChangeConflictResolver.widens(wider.trim(), narrower.trim()),
            "widens(" + wider + ", " + narrower + ") should be " + expected);
    }

    @Test
    @DisplayName("widening is directional")
    void wideningIsDirectional() {
        assertTrue(TypeChangeConflictResolver.widens("long", "int"));
        assertFalse(TypeChangeConflictResolver.widens("int", "long"));
        assertTrue(TypeChangeConflictResolver.widens("double", "long"));
        assertFalse(TypeChangeConflictResolver.widens("long", "double"));
    }

    @Test
    @DisplayName("declines when no typed declaration is present")
    void declinesWithoutTypedDeclaration() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "no declaration", "return;", "return;", "return;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("offers the wider type as a fix path option")
    void offersTypeOptions() {
        FixPath primary =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.TYPE_CHANGE)).get(0);
        assertTrue(primary.getOptions().stream().anyMatch(option -> option.contains("long")),
            "branch 1's type must be offered: " + primary.getOptions());
        assertTrue(primary.getOptions().stream().anyMatch(option -> option.contains("double")),
            "branch 2's type must be offered: " + primary.getOptions());
    }

    // ---------------------------------------------------------------- WS8 gaps

    @ParameterizedTest
    @CsvSource({
        // wider, narrower, expected -- the first argument must be the wider type.
        "Collection, List, true",
        "Iterable, List, true",
        "Object, List, true",
        "Collection, ArrayList, true",
        "Iterable, ArrayList, true",
        "Collection, Set, true",
        "Set, HashSet, true",
        "Iterable, TreeSet, true",
        "Map, TreeMap, true",
        "Map, HashMap, true",
        "CharSequence, String, true",
        "Number, Integer, true",
        "Comparable, String, true",
        "Object, String, true",
        "List, Collection, false",
        "List, Iterable, false",
        "Set, Collection, false",
        "HashSet, Set, false",
        "String, CharSequence, false",
        "String, Long, false"
    })
    @DisplayName("knows the common JDK supertype relationships")
    void knowsJdkSuperTypes(String wider, String narrower, boolean expected) {
        // widens(wider, narrower) answers: can a narrower value be widened to wider?
        assertEquals(expected, TypeChangeConflictResolver.widens(wider.trim(), narrower.trim()),
            "widens(" + wider + ", " + narrower + ") should be " + expected);
    }

    @Test
    @DisplayName("adopts a collection supertype when one branch widens the declaration")
    void adoptsCollectionSupertype() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "widened to a supertype",
            "List<String> names = new ArrayList<>();",
            "List<String> names = new ArrayList<>();",
            "Collection<String> names = new ArrayList<>();");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "Collection is a supertype of List, so adopting it is safe");
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("Collection"),
            "the supertype declaration must be adopted: " + resolution.getResolvedCode());
    }

    @Test
    @DisplayName("treats varargs and an array parameter as the same type")
    void treatsVarargsAndArrayAsEquivalent() {
        assertEquals(TypeChangeConflictResolver.canonical("String..."),
            TypeChangeConflictResolver.canonical("String[]"));
        assertFalse(TypeChangeConflictResolver.widens("String...", "String[]"),
            "equivalent types must not report a widening");
        assertFalse(TypeChangeConflictResolver.widens("String[]", "String..."));
    }

    @Test
    @DisplayName("ignores generic arguments when classifying widening")
    void ignoresGenericArguments() {
        assertEquals("List", TypeChangeConflictResolver.canonical("List<String>"));
        assertEquals("Map", TypeChangeConflictResolver.canonical("Map<String, List<String>>"));
        assertTrue(TypeChangeConflictResolver.widens("Collection<String>", "List<String>"));
        assertTrue(TypeChangeConflictResolver.widens("Collection", "List<String>"));
    }

    @Test
    @DisplayName("declines when the two branches agree on a generic type")
    void declinesWhenGenericTypesAgree() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "same generic type",
            "List<String> names = new ArrayList<>();",
            "List<String> names = new ArrayList<>();",
            "List<String> names = new ArrayList<>();");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "an agreed type change is not this resolver's conflict");
    }

    @Test
    @DisplayName("parses a multi-line generic declaration without mangling the type")
    void parsesGenericDeclaration() {
        TypeChangeConflictResolver.TypeAndName parsed = TypeChangeConflictResolver.parse(
            "Map<String, List<String>> index = new HashMap<>();");

        assertNotNull(parsed);
        assertEquals("Map", TypeChangeConflictResolver.canonical(parsed.type()),
            "the type must not have swallowed the variable name");
        assertEquals("index", parsed.name());
    }

    @Test
    @DisplayName("canonicalises varargs, arrays and generics consistently")
    void canonicalisesTypes() {
        assertEquals("String[]", TypeChangeConflictResolver.canonical("String..."));
        assertEquals("String[]", TypeChangeConflictResolver.canonical("String []"));
        assertEquals("List", TypeChangeConflictResolver.canonical("  List <String> "));
        assertEquals("", TypeChangeConflictResolver.canonical(null));
    }
}
