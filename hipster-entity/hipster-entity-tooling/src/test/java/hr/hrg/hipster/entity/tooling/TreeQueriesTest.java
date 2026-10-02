package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeTree;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct unit tests for {@link TreeQueries} — the migration's replacement for JavaParser's
 * {@code findAll} / {@code getMethods} / {@code asString} surface (plan
 * {@code plans/rewrite-migration/07-Testing-Validation.md} § <i>Status</i> decision 1: the plan's
 * {@code NodeTraversalTests}, {@code AstPrinterTests} and {@code TypeUtilsTests} name Phase 2 sketches
 * that never compiled; this file tests the classes that actually carry that behaviour).
 *
 * <h3>Why a direct suite exists when the queries are already used everywhere</h3>
 * <p>{@link TreeQueries} has 60+ production call sites across the generators and the validation rules and
 * <strong>no test file of its own</strong> — every one of its contracts was only ever asserted through
 * whichever generator happened to consume it. That is the wrong way round for a class whose documented
 * reason for existing is that each LST quirk it absorbs is a <em>wrong answer that still compiles</em>:
 * when a rule's output changes, the reader is sent to the rule, not to the query that answered wrongly.
 * Each test below pins one documented trap at the query itself, so a regression names the query.</p>
 *
 * <p>The fixtures are written as source text and read through {@link SourceReader}, i.e. through the real
 * parser and the real language level, not through a hand-built tree: a hand-built LST would test this
 * class against an author's idea of what the parser produces, which is precisely the mistake the traps
 * describe.</p>
 */
class TreeQueriesTest {

    /** Parses a fixture and fails the test if the fixture itself is not readable. */
    private static J.CompilationUnit unit(String source) {
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse: " + source);
        return unit;
    }

    private static J.ClassDeclaration type(J.CompilationUnit cu, String simpleName) {
        for (J.ClassDeclaration declaration : TreeQueries.typeDeclarations(cu)) {
            if (declaration.getSimpleName().equals(simpleName)) {
                return declaration;
            }
        }
        throw new AssertionError("no type named " + simpleName + " in the fixture");
    }

    /** The spelling {@link TreeQueries#lineOfChained} and {@code JavaSyntaxCheck} key on. */
    private static List<String> names(List<J.ClassDeclaration> declarations) {
        return declarations.stream().map(J.ClassDeclaration::getSimpleName).toList();
    }

    // ------------------------------------------------------------------ package ---

    @Nested
    @DisplayName("packageName")
    class PackageName {

        @Test
        void readsTheDeclaredPackage() {
            Assertions.assertEquals("a.b.c",
                    TreeQueries.packageName(unit("package a.b.c;\npublic class T {}\n")));
        }

        @Test
        void defaultPackageIsAnEmptyStringNotAnError() {
            Assertions.assertEquals("", TreeQueries.packageName(unit("public class T {}\n")));
        }

        @Test
        void aNullUnitIsAnEmptyString() {
            // DEC-029 composes an FQN from this answer, so "" must mean "no package" and never throw.
            Assertions.assertEquals("", TreeQueries.packageName(null));
        }
    }

    // ------------------------------------------------------------------- kinds ---

    /**
     * The single most dangerous mapping in the migration: one {@link J.ClassDeclaration} covers five Java
     * kinds, so a kind query that drops its predicate still compiles and starts returning the wrong types.
     */
    @Test
    void kindQueriesStayApartEvenWhenEveryKindIsNestedInOneType() {
        String source = """
                package p;
                public interface Outer {
                    interface InnerInterface {}
                    class InnerClass {}
                    enum InnerEnum { A }
                    @interface InnerMarker {}
                }
                record TopRecord(Long id) {}
                """;
        J.CompilationUnit cu = unit(source);

        Assertions.assertEquals(List.of("Outer", "InnerInterface"),
                TreeQueries.interfaces(cu).stream().map(J.ClassDeclaration::getSimpleName).toList(),
                "an interface query must reach the nested interface and nothing else");
        Assertions.assertEquals(List.of("InnerClass"),
                TreeQueries.classes(cu).stream().map(J.ClassDeclaration::getSimpleName).toList());
        Assertions.assertEquals(List.of("InnerEnum"),
                TreeQueries.enums(cu).stream().map(J.ClassDeclaration::getSimpleName).toList());
        Assertions.assertEquals(List.of("InnerMarker"),
                TreeQueries.annotations(cu).stream().map(J.ClassDeclaration::getSimpleName).toList());
        Assertions.assertEquals(List.of("TopRecord"),
                TreeQueries.records(cu).stream().map(J.ClassDeclaration::getSimpleName).toList());

        Assertions.assertEquals(6, TreeQueries.typeDeclarations(cu).size(),
                "the union of the kinds is the whole set: no kind is being double-counted");
    }

    @Test
    void topLevelTypesExcludesNestedDeclarations() {
        String source = """
                package p;
                public interface Outer {
                    interface Inner {}
                }
                class Second {}
                """;
        J.CompilationUnit cu = unit(source);

        Assertions.assertEquals(List.of("Outer", "Second"),
                TreeQueries.topLevelTypes(cu).stream().map(J.ClassDeclaration::getSimpleName).toList(),
                "this is the set JavaParser's getTypes() returned");
        Assertions.assertEquals(3, TreeQueries.typeDeclarations(cu).size(),
                "and the at-any-depth query is deliberately wider");
    }

    @Test
    void isKindAnswersFromTheVariableKindAndFiltersNullSafely() {
        J.CompilationUnit cu = unit("package p;\npublic interface I {}\nenum E { A }\n");

        Assertions.assertTrue(TreeQueries.isKind(type(cu, "I"), J.ClassDeclaration.Kind.Type.Interface));
        Assertions.assertFalse(TreeQueries.isKind(type(cu, "I"), J.ClassDeclaration.Kind.Type.Enum));
        Assertions.assertTrue(TreeQueries.isKind(type(cu, "E"), J.ClassDeclaration.Kind.Type.Enum));
        Assertions.assertFalse(TreeQueries.isKind(null, J.ClassDeclaration.Kind.Type.Class));
    }

    // ----------------------------------------------------------------- findAll ---

    @Test
    void findAllReachesEveryDepthAndIncludesTheRootItMatches() {
        String source = """
                package p;
                public class Outer {
                    void one() { }
                    class Middle {
                        void two() { }
                    }
                }
                """;
        J.CompilationUnit cu = unit(source);

        Assertions.assertEquals(List.of("one", "two"),
                TreeQueries.findAll(cu, J.MethodDeclaration.class).stream()
                        .map(J.MethodDeclaration::getSimpleName).toList());
        // The root itself is in scope: the documented contract is "inclusive of itself".
        Assertions.assertEquals(2, TreeQueries.findAll(type(cu, "Outer"), J.ClassDeclaration.class).size(),
                "searching from a declaration sees that declaration <i>and</i> its nested types");
        Assertions.assertEquals(1, TreeQueries.findAll(type(cu, "Middle"), J.ClassDeclaration.class).size(),
                "and a narrower root sees only its own subtree, itself included");
    }

    @Test
    void queriesTolerateANullTree() {
        Assertions.assertTrue(TreeQueries.findAll(null, J.MethodDeclaration.class).isEmpty());
        Assertions.assertTrue(TreeQueries.typeDeclarations(null).isEmpty());
        Assertions.assertTrue(TreeQueries.topLevelTypes(null).isEmpty());
        Assertions.assertTrue(TreeQueries.typesWithEnclosing(null).isEmpty());
        Assertions.assertTrue(TreeQueries.typesOfKind(null, J.ClassDeclaration.Kind.Type.Class).isEmpty());
        Assertions.assertTrue(TreeQueries
                .typeParameterNames(java.util.Arrays.asList((J.CompilationUnit) null)).isEmpty(),
                "a null unit inside the list is skipped, not a NullPointerException");
        Assertions.assertEquals("", TreeQueries.typeText(null));
        Assertions.assertEquals("", TreeQueries.expressionText(null));
        Assertions.assertEquals("", TreeQueries.simpleTypeName(null));
        Assertions.assertEquals("", TreeQueries.annotationName(null));
        Assertions.assertNull(TreeQueries.annotationNamed((J.ClassDeclaration) null, "View"));
        Assertions.assertNull(TreeQueries.annotationNamed((J.MethodDeclaration) null, "View"));
        Assertions.assertFalse(TreeQueries.hasAnnotationArguments(null));
        Assertions.assertNull(TreeQueries.annotationArg(null, "value"));
    }

    // -------------------------------------------------------------- type params ---

    /**
     * A type parameter's {@code getTypeParameters()} is <strong>null</strong>, not empty, on a declaration
     * that has none — the LST shape that failed 191 tests when this was first ported. Walking types and
     * methods with none present must therefore be a no-op rather than a crash.
     */
    @Test
    void typeParameterCollectionSurvivesTheNullParameterList() {
        J.CompilationUnit cu = unit("package p;\npublic class Plain {\n    void m() {}\n}\n");

        Assertions.assertTrue(TreeQueries.typeParameterNames(List.of(cu)).isEmpty());
    }

    @Test
    void typeParameterNamesComeFromTypesAndMethodsAndCarryNoBounds() {
        String source = """
                package p;
                public class Holder<T extends Comparable<T>, R> {
                    <U extends Number> U pick(Class<U> kind) { return null; }
                    <V> void plain(V value) { }
                }
                """;
        Set<String> names = TreeQueries.typeParameterNames(List.of(unit(source)));

        Assertions.assertEquals(Set.of("T", "R", "U", "V"), names);
        Assertions.assertFalse(names.contains("T extends Comparable<T>"),
                "the name is the identifier alone; toString() would carry the bounds into it");
    }

    // ------------------------------------------------------------------ members ---

    /**
     * {@code getMethods()} returned the declaration's own methods and nothing else. {@code findAll}
     * reaches nested types, and every view in the example declares a nested record, so the difference is
     * not theoretical: the wrong answer adds the nested type's accessors to the view's property list.
     */
    @Test
    void methodsOfExcludesConstructorsAndTheMembersOfNestedTypes() {
        String source = """
                package p;
                public class Outer {
                    Outer(String name) { }
                    public String alpha() { return null; }
                    public void beta() { }

                    public class Nested {
                        public String nestedOnly() { return null; }
                    }
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration outer = type(cu, "Outer");

        Assertions.assertEquals(List.of("alpha", "beta"),
                TreeQueries.methodsOf(outer).stream().map(J.MethodDeclaration::getSimpleName).toList(),
                "the constructor is excluded and the nested class's method must not appear");
        Assertions.assertEquals(List.of("Outer", "alpha", "beta", "nestedOnly"),
                TreeQueries.findAll(outer, J.MethodDeclaration.class).stream()
                        .map(J.MethodDeclaration::getSimpleName).toList(),
                "which is exactly what the tempting findAll translation would have returned: the "
                        + "constructor (J.MethodDeclaration covers constructors) and the nested type's "
                        + "accessor");
        Assertions.assertTrue(TreeQueries.methodsOf(null).isEmpty());
    }

    @Test
    void anInterfaceDefaultAndStaticAreDistinguishedFromAccessors() {
        String source = """
                package p;
                public interface V {
                    String name();
                    default String greeting() { return null; }
                    static V of() { return null; }
                    void setId(String id);
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration v = type(cu, "V");

        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(v);
        Assertions.assertEquals(List.of("name", "greeting", "of", "setId"),
                methods.stream().map(J.MethodDeclaration::getSimpleName).toList());

        Assertions.assertTrue(TreeQueries.isDefaultMethod(methods.get(1)));
        Assertions.assertFalse(TreeQueries.isDefaultMethod(methods.get(0)));
        Assertions.assertTrue(TreeQueries.isStaticMethod(methods.get(2)));
        Assertions.assertFalse(TreeQueries.isStaticMethod(methods.get(3)));
        Assertions.assertFalse(TreeQueries.isDefaultMethod(null));
        Assertions.assertFalse(TreeQueries.isStaticMethod(null));

        Assertions.assertEquals(List.of("name", "of"), TreeQueries.noArgMethodNames(v),
                "measured: this query excludes <em>default</em> methods and keeps <em>static</em> ones. A "
                        + "view's `static V of()` factory therefore appears in its accessor list; callers "
                        + "that need properties filter statics separately, as EntityMetadataGenerator's "
                        + "accessor walk does. Pinned because the asymmetry is invisible at the call site.");
    }

    /**
     * An empty parameter list is a single {@link J.Empty} placeholder, so {@code size() == 1} is also true
     * of a no-argument method. A port that translated JavaParser's "one parameter" as a size test would
     * match the no-arg getter sitting next to the setter and stop reporting it.
     */
    @Test
    void parameterCountingSurvivesTheEmptyPlaceholder() {
        String source = """
                package p;
                public class Bean {
                    public String getName() { return null; }
                    public void setName(String name) { }
                    public void two(String a, int b) { }
                }
                """;
        J.CompilationUnit cu = unit(source);
        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(type(cu, "Bean"));

        J.MethodDeclaration getter = methods.get(0);
        Assertions.assertTrue(TreeQueries.hasNoParameters(getter));
        Assertions.assertFalse(TreeQueries.hasOneParameter(getter),
                "one element is the J.Empty placeholder, not a parameter");
        Assertions.assertEquals(1, getter.getParameters().size(),
                "the raw list really does report size 1 — that is the trap");

        J.MethodDeclaration setter = methods.get(1);
        Assertions.assertFalse(TreeQueries.hasNoParameters(setter));
        Assertions.assertTrue(TreeQueries.hasOneParameter(setter));

        Assertions.assertFalse(TreeQueries.hasOneParameter(methods.get(2)));
        Assertions.assertFalse(TreeQueries.hasNoParameters(methods.get(2)));
    }

    @Test
    void voidIsAPrimitiveReturnTypeAndNotAnAbsentOne() {
        String source = """
                package p;
                public class Bean {
                    public void act() { }
                    public String name() { return null; }
                }
                """;
        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(type(unit(source), "Bean"));

        Assertions.assertTrue(TreeQueries.isVoidReturn(methods.get(0)));
        Assertions.assertFalse(TreeQueries.isVoidReturn(methods.get(1)));
        Assertions.assertFalse(TreeQueries.isVoidReturn(null));
    }

    // --------------------------------------------------------------- supertypes ---

    /**
     * An interface's {@code extends} clause is held in {@code getImplements()} and {@code getExtends()} is
     * null — the most expensive trap in the migration: reading only {@code getExtends()} finds no
     * supertype for the commonest declaration in this module and emptied an entire generation pass.
     */
    @Test
    void anInterfaceExtendsClauseIsReadFromImplements() {
        String source = """
                package p;
                public interface PersonEntity extends EntityBase<Long>, Marker {
                    interface Deep extends Another<Long> {}
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration person = type(cu, "PersonEntity");

        Assertions.assertNull(person.getExtends(),
                "javac/LST put the interface's extends clause in getImplements(); this test fails loudly "
                        + "if the LST ever changes shape and TreeQueries stops compensating");

        List<TypeTree> supertypes = TreeQueries.supertypeTypes(person);
        Assertions.assertEquals(2, supertypes.size());
        Assertions.assertEquals(List.of("EntityBase", "Marker"),
                TreeQueries.supertypeNames(person), "names are simple by design");
        Assertions.assertEquals(List.of("EntityBase<Long>", "Marker"),
                TreeQueries.supertypeTexts(person), "texts keep the type argument, which is the whole point");

        Assertions.assertEquals(List.of("Another<Long>"),
                TreeQueries.supertypeTexts(type(cu, "Deep")));
        Assertions.assertTrue(TreeQueries.supertypeTypes(null).isEmpty());
        Assertions.assertTrue(TreeQueries.supertypeNames(null).isEmpty());
    }

    @Test
    void aClassKeepsExtendsBeforeImplements() {
        String source = """
                package p;
                public class Impl extends Base<String> implements Iface, Other {
                }
                """;
        J.ClassDeclaration impl = type(unit(source), "Impl");

        Assertions.assertEquals(List.of("Base<String>", "Iface", "Other"), TreeQueries.supertypeTexts(impl),
                "extends first, then implements — the order a reader expects and the order the metadata records");
    }

    // ------------------------------------------------------------------ printing ---

    /**
     * The printer difference that had to be neutralised: OpenRewrite prints
     * {@code Map<String, List<Long>>} with a space after each inner comma, and the generator recognises
     * its own previous output <em>by that text</em>, so an unnormalised comma reports every generic member
     * as a user edit (DEC-020).
     */
    @Test
    void typeTextNormalisesTheCommaInsideTypeArgumentsOnly() {
        String source = """
                package p;
                import java.util.Map;
                import java.util.List;
                public class T {
                    public Map<String, List<Long>> deep() { return null; }
                    public Map<String, Map<String, List<Long>>> deeper() { return null; }
                    public List<Long> single() { return null; }
                }
                """;
        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(type(unit(source), "T"));

        Assertions.assertEquals("Map<String,List<Long>>",
                TreeQueries.typeText(methods.get(0).getReturnTypeExpression()));
        Assertions.assertEquals("Map<String,Map<String,List<Long>>>",
                TreeQueries.typeText(methods.get(1).getReturnTypeExpression()));
        Assertions.assertEquals("List<Long>",
                TreeQueries.typeText(methods.get(2).getReturnTypeExpression()),
                "no comma, so nothing to normalise");
    }

    @Test
    void simpleTypeNameAnswersForAPrimitiveThroughItsFallback() {
        // The three named branches cover the identifier, the parameterised type and the qualified field
        // access. A primitive is none of them, so the printed-text fallback answers — and it answers with
        // what the source spelled, which is what a caller comparing a declared type wants.
        String source = """
                package p;
                public class T {
                    int count = 0;
                }
                """;
        J.ClassDeclaration t = type(unit(source), "T");
        J.VariableDeclarations count = TreeQueries.findAll(t, J.VariableDeclarations.class).get(0);

        Assertions.assertEquals("int", TreeQueries.simpleTypeName(count.getTypeExpression()));
        Assertions.assertEquals("count", count.getVariables().get(0).getSimpleName().trim(),
                "and the declared name survives the same printing rules");
    }

    @Test
    void simpleTypeNameUnwrapsEveryShapeATypeReferenceTakes() {
        String source = """
                package p;
                public class Impl extends Base<String> implements Inner.Marked {
                    static class Base<X> { }
                    interface Inner { interface Marked { } }
                }
                """;
        J.ClassDeclaration impl = type(unit(source), "Impl");

        List<String> names = TreeQueries.supertypeNames(impl);
        Assertions.assertEquals(List.of("Base", "Marked"), names,
                "a qualified type contributes its last segment and a parameterised type its raw name");
    }

    /**
     * An LST literal does not round-trip: {@code J.Literal.toString()} returns the value, so a string
     * literal loses its quotes and an annotation's emitted argument text stops being Java.
     */
    @Test
    void expressionTextKeepsQuotingForLiterals() {
        String source = """
                package p;
                public class C {
                    @Marker(text = "ab\\"cd", ch = '\\n', number = 7, flag = true, ref = Thing.class)
                    void m() { }
                }
                @interface Marker {
                    String text();
                    char ch();
                    int number();
                    boolean flag();
                    Class<?> ref();
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.MethodDeclaration m = TreeQueries.methodsOf(type(cu, "C")).get(0);
        J.Annotation marker = TreeQueries.annotationNamed(m, "Marker");
        Assertions.assertNotNull(marker, "the fixture must carry the annotation");

        Assertions.assertEquals("\"ab\\\"cd\"",
                TreeQueries.expressionText(TreeQueries.annotationArg(marker, "text")));
        Assertions.assertEquals("'\\n'",
                TreeQueries.expressionText(TreeQueries.annotationArg(marker, "ch")));
        Assertions.assertEquals("7", TreeQueries.expressionText(TreeQueries.annotationArg(marker, "number")));
        Assertions.assertEquals("true", TreeQueries.expressionText(TreeQueries.annotationArg(marker, "flag")));
        Assertions.assertEquals("Thing.class",
                TreeQueries.expressionText(TreeQueries.annotationArg(marker, "ref")),
                "a class literal must keep its `.class` suffix");
    }

    @Test
    void expressionTextReproducesALiteralTheParserLeftUnquoted() {
        // A literal with no recorded value source has to be rendered from its value, quotes included.
        String source = "package p;\npublic class C {\n    @Marker(text = \"a\\tb\")\n    void m() { }\n}\n"
                + "@interface Marker { String text(); }\n";
        J.MethodDeclaration m = TreeQueries.methodsOf(type(unit(source), "C")).get(0);
        J.Annotation marker = TreeQueries.annotationNamed(m, "Marker");

        Assertions.assertEquals("\"a\\tb\"",
                TreeQueries.expressionText(TreeQueries.annotationArg(marker, "text")));
    }

    // -------------------------------------------------------------- annotations ---

    @Test
    void annotationNamesIgnoreThePackagePrefix() {
        String source = """
                package p;
                @T.Marked
                @Other
                public class T {
                    @T.Marked
                    String x() { return null; }

                    @interface Marked { }
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration t = type(cu, "T");

        Assertions.assertTrue(TreeQueries.hasAnnotation(t, "Marked"),
                "a qualified annotation is still the annotation the caller asked for, by its last segment");
        Assertions.assertFalse(TreeQueries.hasAnnotation(t, "Missing"));

        J.MethodDeclaration x = TreeQueries.methodsOf(t).get(0);
        Assertions.assertTrue(TreeQueries.hasAnnotation(x, "Marked"));
        Assertions.assertEquals("Marked",
                TreeQueries.annotationName(TreeQueries.annotationNamed(x, "Marked")));
        Assertions.assertNull(TreeQueries.annotationNamed(x, "Other"),
                "the type's annotations are not the method's");
    }

    @Test
    void aSingleUnnamedArgumentIsReachableAsValue() {
        String source = """
                package p;
                public class T {
                    @Level("warn")
                    @Empty
                    @Named(name = "a", other = "b")
                    void m() { }
                }
                """;
        J.MethodDeclaration m = TreeQueries.methodsOf(type(unit(source), "T")).get(0);

        Assertions.assertEquals("\"warn\"",
                TreeQueries.expressionText(TreeQueries.annotationArg(TreeQueries.annotationNamed(m, "Level"),
                        "value")),
                "the LST normalises `@Foo(x)` to an assignment to `value`; a lookup for value must succeed");
        Assertions.assertNull(TreeQueries.annotationArg(TreeQueries.annotationNamed(m, "Level"), "other"));

        J.Annotation empty = TreeQueries.annotationNamed(m, "Empty");
        Assertions.assertNotNull(empty);
        Assertions.assertFalse(TreeQueries.hasAnnotationArguments(empty),
                "`@Empty` declares no attribute");
        Assertions.assertNull(TreeQueries.annotationArg(empty, "value"));

        J.Annotation named = TreeQueries.annotationNamed(m, "Named");
        Assertions.assertEquals("\"a\"",
                TreeQueries.expressionText(TreeQueries.annotationArg(named, "name")));
        Assertions.assertEquals("\"b\"",
                TreeQueries.expressionText(TreeQueries.annotationArg(named, "other")));
        Assertions.assertNull(TreeQueries.annotationArg(named, "nope"));
    }

    @Test
    void anEmptyParenthesedArgumentListNamesNoAttribute() {
        String source = """
                package p;
                @View()
                public class T {
                }
                """;
        J.ClassDeclaration t = type(unit(source), "T");
        J.Annotation view = TreeQueries.annotationNamed(t, "View");

        Assertions.assertNotNull(view);
        Assertions.assertFalse(TreeQueries.hasAnnotationArguments(view),
                "`@View()` parses to a single J.Empty argument, which must not read as an attribute");
        Assertions.assertNull(TreeQueries.annotationArg(view, "value"));
    }

    @Test
    void anArrayAttributeIsTheArrayNodeItself() {
        String source = """
                package p;
                public class T {
                    @Marker(addons = {Alpha.class, Beta.class})
                    void m() { }
                }
                """;
        J.MethodDeclaration m = TreeQueries.methodsOf(type(unit(source), "T")).get(0);
        Expression addons = TreeQueries.annotationArg(TreeQueries.annotationNamed(m, "Marker"), "addons");

        Assertions.assertInstanceOf(J.NewArray.class, addons,
                "a caller branches on the array node exactly as it branched on JavaParser's "
                        + "ArrayInitializerExpr");
        Assertions.assertEquals("addons",
                TreeQueries.assignmentName((J.Assignment) firstAssignment(m)));
    }

    private static org.openrewrite.java.tree.Statement firstAssignment(J.MethodDeclaration m) {
        for (Expression argument : TreeQueries.annotationNamed(m, "Marker").getArguments()) {
            if (argument instanceof J.Assignment assignment) {
                return assignment;
            }
        }
        throw new AssertionError("no named argument");
    }

    // ------------------------------------------------------- enclosing and lines ---

    @Test
    void typesWithEnclosingReportsOutermostFirstAndExcludesTheDeclaration() {
        String source = """
                package p;
                public interface Shape {
                    interface Nested {
                        record Deep(long x) {}
                    }
                }
                """;
        List<TreeQueries.EnclosedType> enclosed = TreeQueries.typesWithEnclosing(unit(source));

        Assertions.assertEquals(List.of("Shape", "Nested", "Deep"),
                enclosed.stream().map(e -> e.declaration().getSimpleName()).toList(), "source order");
        Assertions.assertEquals(List.of(), names(enclosed.get(0).enclosing()));
        Assertions.assertEquals(List.of("Shape"), names(enclosed.get(1).enclosing()));
        Assertions.assertEquals(List.of("Shape", "Nested"), names(enclosed.get(2).enclosing()),
                "the chain is outermost first and never contains the declaration itself");
    }

    /**
     * A name-keyed lookup is not enough: for {@code interface Shape { record Circle {} }} the chain of
     * {@code Circle} is {@code [Shape]}, and the outer declaration's own name is also {@code Shape}. The
     * matching that returns the inner line for the outer type is confidently wrong, so it is pinned here.
     */
    @Test
    void aShadowingNameResolvesToTheRightDeclaration() {
        String source = """
                package p;
                public interface Shape {          // line 2, name of the outer type
                    interface Shape {             // line 3, a nested type with the SAME simple name
                        record Circle(long r) {}  // line 4
                    }
                }
                """;
        J.CompilationUnit cu = unit(source);
        List<TreeQueries.EnclosedType> all = TreeQueries.typesWithEnclosing(cu);

        TreeQueries.EnclosedType outer = all.get(0);
        TreeQueries.EnclosedType inner = all.get(1);
        TreeQueries.EnclosedType circle = all.get(2);
        Assertions.assertEquals(outer.declaration().getSimpleName(), inner.declaration().getSimpleName(),
                "the fixture really does have two declarations of one name");

        // `lineOfChained` is keyed on the chain of *names* — the same spelling JavaSyntaxCheck reports —
        // so every caller maps the declarations first. Doing it here is part of what the test pins.
        Assertions.assertEquals(2,
                TreeQueries.lineOfChained(outer.declaration(), names(outer.enclosing()), source),
                "the outer interface's name is on line 2, not the inner one's line 3");
        Assertions.assertEquals(3,
                TreeQueries.lineOfChained(inner.declaration(), names(inner.enclosing()), source));
        Assertions.assertEquals(4,
                TreeQueries.lineOfChained(circle.declaration(), names(circle.enclosing()), source));
    }

    @Test
    void declarationLineIncludesAnnotationsAndNameLineDoesNot() {
        String source = """
                package p;

                @Deprecated
                @Marker
                public class Marked {
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration marked = type(cu, "Marked");

        Assertions.assertEquals(3, TreeQueries.declarationLineOf(marked, source),
                "the declaration begins on its first annotation — what the metadata's view line records");
        Assertions.assertEquals(5, TreeQueries.lineOf(marked, source),
                "the name is two lines lower — what a reader's editor should scroll to");
        Assertions.assertEquals(-1, TreeQueries.lineOf(marked, null));
        Assertions.assertEquals(-1, TreeQueries.declarationLineOf(marked, "  "));
        Assertions.assertEquals(-1, TreeQueries.lineOf(null, source));
    }

    @Test
    void memberAndAnnotationLinesAreSeparateLookups() {
        String source = """
                package p;
                public enum Ledger {
                    @Deprecated
                    FIRST,
                    SECOND;

                    @Marker
                    static int count = 2;

                    @Marker
                    public int size() { return 0; }
                }
                """;
        J.CompilationUnit cu = unit(source);

        Assertions.assertEquals(4, TreeQueries.memberLineOf("Ledger", "FIRST", "enum-constant", source));
        Assertions.assertEquals(5, TreeQueries.memberLineOf("Ledger", "SECOND", "enum-constant", source));
        Assertions.assertEquals(8, TreeQueries.memberLineOf("Ledger", "count", "field", source));
        Assertions.assertEquals(11, TreeQueries.methodLineOf(
                TreeQueries.methodsOf(type(cu, "Ledger")).get(0), "Ledger", source));

        Assertions.assertEquals(10, TreeQueries.annotationLineOf("Ledger", "size", "Marker", source),
                "the annotation has a line of its own, distinct from the member it sits above");
        // Measured limitation, recorded rather than papered over: JavaSyntaxCheck collects annotations on
        // types and methods only. An annotation on an enum constant or a field has no AnnotationPosition,
        // so this answers -1 — correct for a location payload that must not link a line it has not seen,
        // and the reason DEC-028's annotation lines are only ever asked for accessors.
        Assertions.assertEquals(-1, TreeQueries.annotationLineOf("Ledger", "FIRST", "Deprecated", source),
                "an annotation on an enum constant is not collected, and the query says so instead of "
                        + "guessing the constant's line");
        Assertions.assertEquals(-1, TreeQueries.memberLineOf("Ledger", "FIRST", "field", source),
                "a role the declaration does not have is no answer, not a guess");
        Assertions.assertEquals(-1, TreeQueries.memberLineOf("Nope", "FIRST", "enum-constant", source));
        Assertions.assertEquals(-1, TreeQueries.annotationLineOf("Ledger", "count", "Deprecated", source));
    }

    @Test
    void overloadsAreToldApartByArityAndOwner() {
        String source = """
                package p;
                public class O {
                    public String pick(String a) { return null; }
                    public String pick(String a, int b) { return null; }
                    static class Inner {
                        public String pick(String a) { return null; }
                    }
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration outer = type(cu, "O");
        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(outer);

        Assertions.assertEquals(3, TreeQueries.methodLineOf(methods.get(0), "O", source));
        Assertions.assertEquals(4, TreeQueries.methodLineOf(methods.get(1), "O", source));
        Assertions.assertEquals(6, TreeQueries.methodLineOf(TreeQueries.methodsOf(type(cu, "Inner")).get(0),
                "Inner", source),
                "the owner name is what separates two same-named methods in one file");
        Assertions.assertEquals(-1, TreeQueries.methodLineOf(methods.get(0), "Missing", source),
                "an owner the file does not have is no answer");
        Assertions.assertEquals(6, TreeQueries.methodLineOf(methods.get(0), "Inner", source),
                "measured, and worth pinning: the lookup is keyed on (name, arity, given owner), not on "
                        + "the node's own identity — this is how a caller holding only a name resolves the "
                        + "accessor of a nested type");
    }

    // ----------------------------------------------------------- member text ---

    /**
     * Cooperative codegen carries a developer's member through a regeneration <em>verbatim</em>
     * (DEC-020). Nothing that re-prints can do that, so the text is sliced out of the source with javac's
     * offsets — including the comment that documents it.
     */
    @Test
    void memberTextReturnsTheDeclarationVerbatimWithItsJavadoc() {
        String source = """
                package p;
                public interface V {
                    /**
                     * A documented accessor.
                     */
                    default String greet() {
                        return "hi";
                    }
                }
                """;

        String text = TreeQueries.memberText("V", "method", "greet", 0, source);
        Assertions.assertNotNull(text);
        Assertions.assertTrue(text.startsWith("    /**"),
                "the indent of the attached javadoc is part of the slice: " + text);
        Assertions.assertTrue(text.contains("A documented accessor."));
        Assertions.assertTrue(text.endsWith("return \"hi\";\n    }"),
                "and the body is carried unchanged: " + text);
    }

    @Test
    void memberTextCarriesALineCommentRunAndNotATrailingComment() {
        String commented = """
                package p;
                public class C {
                    // one
                    // two
                    int a = 1;
                }
                """;
        String text = TreeQueries.memberText("C", "field", "a", 0, commented);
        Assertions.assertTrue(text.startsWith("    // one"), "the whole `//` run belongs to the field: " + text);
        Assertions.assertTrue(text.contains("int a = 1;"));

        // The rule is "anything but whitespace between the comment and the declaration", so a blank line
        // still attaches and a line of code does not: the trailing comment documents the declaration it
        // follows, not the one below it.
        String trailing = """
                package p;
                public class C {
                    int z = 0; // about z
                    int a = 1;
                }
                """;
        Assertions.assertTrue(TreeQueries.memberText("C", "field", "a", 0, trailing).startsWith("int a"),
                "a trailing comment on the previous member must not be carried with this one, and without a "
                        + "comment the slice starts at the declaration itself: "
                        + TreeQueries.memberText("C", "field", "a", 0, trailing));
        Assertions.assertEquals("int z = 0;", TreeQueries.memberText("C", "field", "z", 0, trailing).strip(),
                "and the slice of the member the comment documents stops at its semicolon: the comment "
                        + "stays in the file, where the author wrote it");
    }

    @Test
    void memberTextSpansACommaSeparatedFieldDeclarationAsOneMember() {
        // JavaParser modelled `private int a, b;` as one FieldDeclaration with two variables; so does the
        // identity this module uses. The second declarator keeps its own line, but the slice is the
        // statement's.
        String source = """
                package p;
                public class C {
                    private int a = 1, b = 2;
                }
                """;

        Assertions.assertEquals("private int a = 1, b = 2;",
                TreeQueries.memberText("C", "field", "a", 0, source).strip(),
                "the span starts at the declaration's own first character");
        Assertions.assertEquals(3, TreeQueries.memberLineOf("C", "b", "field", source),
                "the second declarator is findable by line");
        Assertions.assertEquals("private int a = 1, b = 2;",
                TreeQueries.memberText("C", "field", "b", 0, source).strip(),
                "measured: a lookup by the second declarator's name answers with the <em>same statement</em>, "
                        + "because javac gives both declarators the declaration's start position. That is why "
                        + "CooperativeCodegen keys a member on the first name only — a slice that is the "
                        + "statement's, carried under two identities, would be written back twice.");
    }

    @Test
    void memberTextIsNullForAnUnknownMemberOrSource() {
        String source = "package p;\npublic class C {\n    int a = 1;\n}\n";

        Assertions.assertNull(TreeQueries.memberText("C", "field", "nope", 0, source));
        Assertions.assertNull(TreeQueries.memberText("Other", "field", "a", 0, source));
        Assertions.assertNull(TreeQueries.memberText("C", "field", "a", 0, null));
        Assertions.assertNull(TreeQueries.memberText(null, "field", null, 0, source));
    }

    @Test
    void aTypeMemberSliceIsTheNestedDeclaration() {
        String source = """
                package p;
                public interface V {
                    /** Nested. */
                    record Row(Long id, String name) {
                    }
                }
                """;

        String text = TreeQueries.memberText("V", "type", "Row", 0, source);
        Assertions.assertNotNull(text, "a nested type is a member like any other");
        Assertions.assertTrue(text.contains("/** Nested. */"), text);
        Assertions.assertTrue(text.contains("record Row(Long id, String name)"), text);

        Assertions.assertEquals(4, TreeQueries.memberLineOf("V.Row", "id", "record-component", source),
                "a record component is addressed through its dotted owner display name");
    }

    /**
     * The chain's <strong>order</strong>, pinned at three levels of nesting.
     *
     * <p>Two levels cannot tell the two orders apart — {@code [Shape]} is its own reverse — which is why
     * this was wrong unnoticed: the traversal pushed declarations onto a stack and copied the stack, whose
     * iterator runs head-first, so a three-deep chain came back innermost-first. {@code lineOfChained}
     * matches that chain against javac's outermost-first one and answers {@code -1} on a miss, and
     * DEC-029's FQN is {@code String.join(".", chain)}, so the reversed order produced
     * {@code p.Nested.Shape.Deep} for a type nested three deep. Phase 7 found it while writing the direct
     * test for a query that previously had none.</p>
     */
    @Test
    void aThreeDeepChainIsOutermostFirstAndResolvesItsOwnLine() {
        String source = """
                package p;
                public class Shape {
                    static class Nested {
                        record Deep(long radius) {
                        }
                    }
                }
                """;
        List<TreeQueries.EnclosedType> all = TreeQueries.typesWithEnclosing(unit(source));

        TreeQueries.EnclosedType deep = all.stream()
                .filter(e -> e.declaration().getSimpleName().equals("Deep"))
                .findFirst().orElseThrow();
        Assertions.assertEquals(List.of("Shape", "Nested"), names(deep.enclosing()),
                "outermost first, exactly the spelling JavaSyntaxCheck reports and TypeFacts joins");
        Assertions.assertEquals(4, TreeQueries.lineOfChained(deep.declaration(), names(deep.enclosing()),
                source), "a chain in the wrong order is not a miss but a confidently wrong line");
        Assertions.assertEquals(4, hr.hrg.hipster.entity.tooling.index.TypeFacts
                .of(deep.declaration(), deep.enclosing(), source).line(),
                "and the class index DEC-029 writes takes its line from that same chain");
        Assertions.assertEquals("p.Shape.Nested.Deep", hr.hrg.hipster.entity.tooling.index.TypeFacts
                .of(deep.declaration(), deep.enclosing(), source).fqn());
    }

    /**
     * The same hazard seen from the query side, because this is the pair DEC-029 records.
     *
     * <p>A type annotated with an annotation of the same simple name is not hypothetical here: the
     * marker-and-view patterns in this project put {@code @View} on views, {@code @Marker} on markers and
     * {@code @GenerateBuilder} on records, and a name that repeats its own annotation is exactly what a
     * report link must not get wrong.</p>
     */
    @Test
    void aDeclarationNamedLikeItsOwnAnnotation() {
        String source = """
                package p;

                @GenerateBuilder
                public record GenerateBuilder(Long id) {
                }
                """;
        J.CompilationUnit cu = unit(source);
        J.ClassDeclaration generated = type(cu, "GenerateBuilder");

        Assertions.assertEquals(3, TreeQueries.declarationLineOf(generated, source),
                "the declaration begins on the annotation");
        Assertions.assertEquals(4, TreeQueries.lineOf(generated, source),
                "and the name the link should open is on the line below it");
    }

    // ------------------------------------------------------------------ switch ---

    @Test
    void caseLinesAndCaseSpansPairTheLstArmsWithJavacPositions() {
        String source = """
                package p;
                public class S {
                    public int ord(String name) {
                        return switch (name) {
                            case "a" -> 1;
                            case "b" -> 2;
                            default -> 0;
                        };
                    }
                }
                """;
        J.CompilationUnit cu = unit(source);

        Map<J.Case, Integer> lines = TreeQueries.caseLines(cu, source);
        Map<J.Case, TreeQueries.SourceSpan> spans = TreeQueries.caseSpans(cu, source);
        List<J.Case> arms = TreeQueries.findAll(cu, J.Case.class);

        Assertions.assertEquals(3, arms.size(), "the fixture has three arms");
        Assertions.assertEquals(3, lines.size(), "javac and the LST agree on the count, so the zip is sound");
        Assertions.assertEquals(List.of(5, 6, 7),
                arms.stream().map(lines::get).toList());
        for (J.Case arm : arms) {
            TreeQueries.SourceSpan span = spans.get(arm);
            Assertions.assertNotNull(span);
            String text = source.substring(span.start(), span.end());
            Assertions.assertTrue(text.startsWith("case") || text.startsWith("default"),
                    "the span starts at the arm's own keyword: " + text);
        }

        Assertions.assertTrue(TreeQueries.caseLines(cu, null).isEmpty());
        Assertions.assertTrue(TreeQueries.caseSpans(null, source).isEmpty());
    }

    @Test
    void aCountMismatchBetweenTheTwoTraversalsYieldsNoAnswerRatherThanAGuess() {
        // The pairing is positional, so the honest answer when the two traversals disagree is "unknown":
        // a wrong line is a link that opens the wrong code while looking authoritative. The disagreement is
        // forced here by handing the query a tree and a text that are not the same file — which is exactly
        // the class of mistake the guard exists for.
        String threeArms = """
                package p;
                public class S {
                    public int ord(String name) {
                        return switch (name) {
                            case "a" -> 1;
                            case "b" -> 2;
                            default -> 0;
                        };
                    }
                }
                """;
        String twoArms = """
                package p;
                public class S {
                    public int ord(String name) {
                        return switch (name) {
                            case "a" -> 1;
                            default -> 0;
                        };
                    }
                }
                """;
        J.CompilationUnit cu = unit(threeArms);

        Assertions.assertEquals(3, TreeQueries.caseLines(cu, threeArms).size(), "the matching pair answers");
        Assertions.assertTrue(TreeQueries.caseLines(cu, twoArms).isEmpty(),
                "three LST arms against two javac arms is a disagreement, not a zip");
        Assertions.assertTrue(TreeQueries.caseSpans(cu, twoArms).isEmpty());
    }

    // ----------------------------------------------------------------- filter ---

    @Test
    void filterKeepsOrderAndIsIndependentOfTheInputList() {
        List<String> input = new java.util.ArrayList<>(List.of("a", "bb", "ccc"));

        Assertions.assertEquals(List.of("bb", "ccc"), TreeQueries.filter(input, s -> s.length() > 1));
        input.set(0, "z");
        Assertions.assertEquals(List.of("bb", "ccc"), TreeQueries.filter(input, s -> s.length() > 1),
                "the result is a new list, not a view of the input");
        Assertions.assertEquals(List.of("z", "bb", "ccc"), input, "and the input is left alone");
        Assertions.assertEquals(List.of(), TreeQueries.filter(List.of(), s -> true));
    }
}
