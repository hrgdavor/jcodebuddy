// {@link com.codebuddy.merge.TypeChangeConflictResolverTest} Tests for the type change conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.java.tree.JavaType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the widening rule: adopting the wider of two types is automatic,
 * anything else needs a reviewer.
 *
 * <p>Two rules answer the question, so the tests are split the same way. Primitives
 * are the JLS conversion lattice and are pinned as such. Reference types are resolved
 * through javac, so those expectations are expressed by resolving a real declaration
 * and asking the resolver about the resulting types - which is why the JDK-supertype
 * table is now a test of {@code TypeUtils.isAssignableTo} rather than of a name list.
 */
class TypeChangeConflictResolverTest extends AbstractResolverTest {

    private final TypeChangeConflictResolver resolver = new TypeChangeConflictResolver();

    /** A conflict carrying the type context this resolver now requires. */
    private static Conflict withTypeContext(Conflict conflict) {
        return conflict.withTypeContext(TestTypeContexts.jdk());
    }

    /**
     * The resolved type of a field declared with {@code type} in a throwaway class.
     *
     * <p>A field rather than a bare declaration because the fragment has to be a
     * compilation unit for the parser to attribute it, and the wildcard import keeps
     * the CSV rows readable as {@code Collection} rather than
     * {@code java.util.Collection}.
     */
    private static JavaType typeOf(String type) {
        String code = "import java.util.*;\nclass A { " + type + " value = null; }";
        return ResolvedTypeReader.declaredType(code, "value", "A.java", TestTypeContexts.jdk())
            .orElseThrow(() -> new AssertionError("could not resolve a declaration of " + type));
    }

    /** Whether javac says a {@code narrower} value is assignable to {@code wider}. */
    private static boolean resolvesAsWidening(String wider, String narrower) {
        return TypeChangeConflictResolver.widens(typeOf(wider), typeOf(narrower));
    }

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

    //#region adopts-wider-type
    @Test
    @DisplayName("adopts the wider type declared on branch 2")
    void adoptsWiderType() {
        // Fixture: base int, branch 1 long, branch 2 double. double is the widest,
        // so branch 2 supplies the declaration that is adopted.
        Conflict conflict = withTypeContext(ConflictFixtures.sample(ConflictType.TYPE_CHANGE));
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
    //#endregion

    //#region adopts-one-sided-widening
    @Test
    @DisplayName("adopts the wider type when branch 2 is the wider one")
    void adoptsWiderTypeFromBranch2() {
        Conflict conflict = withTypeContext(new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "branch 2 widens", "int count = 0;", "int count = 0;", "long count = 0;"));

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("long count"));
    }
    //#endregion

    //#region escalates-unrelated-types
    @Test
    @DisplayName("escalates unrelated types to a reviewer")
    void escalatesUnrelatedTypes() {
        Conflict conflict = withTypeContext(new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "unrelated types", "int count = 0;", "int count = 0;", "String count = \"0\";"));

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "int and String have no widening relationship");
        assertFalse(resolution.getAlternativePaths().isEmpty());
    }
    //#endregion

    //#region declines-when-types-agree
    @Test
    @DisplayName("declines when both branches agree on the type")
    void declinesWhenTypesAgree() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "same type", "int count = 0;", "long count = 0;", "long count = 0;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "an agreed type change is not this resolver's conflict");
    }
    //#endregion

    @ParameterizedTest
    @CsvSource({
        "long, int, true",
        "double, float, true",
        "float, long, true",
        "int, long, false",
        "long, double, false",
        "boolean, boolean, false"
    })
    @DisplayName("classifies the JLS widening primitive conversions")
    void classifiesWidening(String wider, String narrower, boolean expected) {
        // widens(wider, narrower) answers: can a narrower value be widened to wider?
        // These pairs are the language's own rule (JLS 5.1.2), which is the only
        // table still in the resolver. Reference types were rows here too until they
        // were resolved instead; `knowsJdkSuperTypes` covers those.
        assertEquals(expected, TypeChangeConflictResolver.widens(wider.trim(), narrower.trim()),
            "widens(" + wider + ", " + narrower + ") should be " + expected);
    }

    //#region widening-is-directional
    @Test
    @DisplayName("widening is directional")
    void wideningIsDirectional() {
        assertTrue(TypeChangeConflictResolver.widens("long", "int"));
        assertFalse(TypeChangeConflictResolver.widens("int", "long"));
        assertTrue(TypeChangeConflictResolver.widens("double", "long"));
        assertFalse(TypeChangeConflictResolver.widens("long", "double"));
    }
    //#endregion

    @Test
    @DisplayName("declines when no typed declaration is present")
    void declinesWithoutTypedDeclaration() {
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "no declaration", "return;", "return;", "return;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    //#region escalates-without-type-context
    @Test
    @DisplayName("escalates with a reason when no type context was supplied")
    void escalatesWithoutTypeContext() {
        // requiresTypeContext() is true, so the orchestrator refuses to build a set
        // without a context and names this resolver. A direct caller gets a manual
        // resolution carrying the reason instead of a weaker answer - which is the
        // same shape OverloadAddConflictResolver uses for the same situation.
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "no context", "int count = 0;", "int count = 0;", "long count = 0;");

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertTrue(resolution.getExplanation().contains("no type context"),
            "the reason must say what was missing: " + resolution.getExplanation());
        assertTrue(resolver.requiresTypeContext(),
            "and the resolver must declare the requirement, not just handle its absence");
    }
    //#endregion

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
    @DisplayName("knows the common JDK supertype relationships by resolving them")
    void knowsJdkSuperTypes(String wider, String narrower, boolean expected) {
        // Both declarations are resolved through javac and the question is asked of
        // the resolved types, so this is the same expectation list as before without
        // being a list the resolver has to maintain: it is now a property of the JDK.
        assertEquals(expected, resolvesAsWidening(wider.trim(), narrower.trim()),
            "widens(" + wider + ", " + narrower + ") should be " + expected);
    }

    @Test
    @DisplayName("resolves a supertype the deleted table never listed")
    void resolvesASupertypeTheTableNeverListed() {
        // The table named ArrayList, List, Collection and Iterable (and their
        // relatives), but not NavigableSet or SortedSet: TreeSet was in a chain that
        // jumped straight to AbstractSet. A pair it could not answer was escalated to
        // a reviewer, so an incomplete table did not merely answer coarsely - it
        // handed back work it could have done.
        assertTrue(resolvesAsWidening("java.util.NavigableSet", "java.util.TreeSet"),
            "TreeSet implements NavigableSet; nothing in the table said so");
        assertTrue(resolvesAsWidening("java.util.SortedSet", "java.util.TreeSet"));
        assertFalse(resolvesAsWidening("java.util.TreeSet", "java.util.NavigableSet"),
            "and the relationship is still directional");
    }

    @Test
    @DisplayName("escalates boxed siblings the deleted table called a widening")
    void escalatesBoxedSiblingsTheTableCalledWidening() {
        // The table's boxed chain read the primitive lattice across the wrapper
        // classes: Byte, Short, Integer, Long, Float, Double in one ascending list, so
        // widens("Long", "Integer") was TRUE. javac rejects `Long x = anInteger`, so
        // auto-adopting the Long declaration would have broken every reader that
        // assigned the result to an Integer. The table was not just incomplete.
        assertFalse(resolvesAsWidening("Long", "Integer"),
            "an Integer is not assignable to a Long; they are siblings under Number");
        assertFalse(resolvesAsWidening("Integer", "Long"));
        assertTrue(resolvesAsWidening("Number", "Integer"),
            "while the real supertype relationship still holds");

        ConflictResolution resolution = resolver.resolve(withTypeContext(
            new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE, "boxed siblings",
                "Integer count = 0;", "Integer count = 0;", "Long count = 0L;")));
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "neither boxed type is wider, so a human decides");
    }

    //#region adopts-collection-supertype
    @Test
    @DisplayName("adopts a collection supertype when one branch widens the declaration")
    void adoptsCollectionSupertype() {
        Conflict conflict = withTypeContext(new Conflict(ConflictType.TYPE_CHANGE, ConflictFixtures.FILE,
            "widened to a supertype",
            "List<String> names = new ArrayList<>();",
            "List<String> names = new ArrayList<>();",
            "Collection<String> names = new ArrayList<>();"));

        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "Collection is a supertype of List, so adopting it is safe");
        assertEquals(ConflictResolution.ResolutionStrategy.PREFER_BRANCH2,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("Collection"),
            "the supertype declaration must be adopted: " + resolution.getResolvedCode());
    }
    //#endregion

    @Test
    @DisplayName("treats varargs and an array parameter as the same type")
    void treatsVarargsAndArrayAsEquivalent() {
        assertEquals(TypeChangeConflictResolver.canonical("String..."),
            TypeChangeConflictResolver.canonical("String[]"));
        assertFalse(TypeChangeConflictResolver.widens("String...", "String[]"),
            "equivalent types must not report a widening");
        assertFalse(TypeChangeConflictResolver.widens("String[]", "String..."));
    }

    //#region ignores-generic-arguments
    @Test
    @DisplayName("keeps generic arguments rather than dropping them, and still classifies")
    void ignoresGenericArguments() {
        assertEquals("List", TypeChangeConflictResolver.canonical("List<String>"));
        assertEquals("Map", TypeChangeConflictResolver.canonical("Map<String, List<String>>"),
            "the canonical token is still the raw name, which is what the lattice compares");

        // Resolution, by contrast, sees the arguments: `List<String>` is assignable to
        // `Collection<String>` but not to `Collection<Integer>`. Dropping them - as the
        // canonical token does, and as the old table had to - would have called the
        // second pair a widening too.
        assertTrue(resolvesAsWidening("java.util.Collection<String>", "java.util.List<String>"));
        assertTrue(resolvesAsWidening("java.util.Collection<String>", "java.util.ArrayList<String>"));
        assertFalse(resolvesAsWidening("java.util.Collection<Integer>", "java.util.List<String>"),
            "an unrelated type argument is not a widening, whatever the raw names say");
    }
    //#endregion

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
