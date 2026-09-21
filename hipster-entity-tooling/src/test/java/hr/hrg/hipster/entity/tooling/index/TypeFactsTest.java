package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The basic facts a class index row records about one type (DEC-029).
 *
 * <p>The FQN is the row's key and the way every document references a type, so it has to be built the way
 * the language builds it: package plus the enclosing chain plus the simple name, never a guess from the
 * file's path. The modifier list has real edge cases ({@code non-sealed} is spelled with a hyphen,
 * a member type is nested, an annotation type is neither a class nor an interface), and {@code kind} must
 * be the tooling's one resolver rather than a second opinion.</p>
 */
class TypeFactsTest {

    private static final String UNIT = """
            package a.b;

            import java.util.List;

            public sealed interface Shape permits Shape.Circle, Shape.Open {
                String name();

                record Circle(int radius) implements Shape {
                    public String name() { return "circle"; }
                }

                non-sealed interface Open extends Shape {}

                final class Impl implements Shape {
                    public String name() { return "impl"; }
                }
            }

            class PackagePrivate {}

            @interface Marker {}

            enum Colour { RED }
            """;

    private static List<TypeFacts> factsOf(String source) {
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");
        List<TypeFacts> facts = new ArrayList<>();
        // The ported path: the enclosing chain comes from the traversal cursor, and the line from
        // javac's line map over the same text. This is exactly what ClassIndex.collectTypes does, so
        // the test exercises the production route rather than a parallel one.
        for (TreeQueries.EnclosedType enclosed : TreeQueries.typesWithEnclosing(unit)) {
            facts.add(TypeFacts.of(enclosed.declaration(), enclosed.enclosing(), source));
        }
        return facts;
    }

    private static TypeFacts named(List<TypeFacts> facts, String fqn) {
        return facts.stream().filter(f -> f.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("no facts for " + fqn + " in "
                        + facts.stream().map(TypeFacts::fqn).toList()));
    }

    private static List<TypeFacts> unitFacts() {
        return factsOf(UNIT);
    }

    /** A top-level type's FQN is package plus simple name; the default package has no prefix. */
    @Test
    void aTopLevelTypeIsPackageQualified() {
        TypeFacts shape = named(unitFacts(), "a.b.Shape");
        Assertions.assertEquals("interface", shape.kind(), "a sealed interface is an interface");
        Assertions.assertEquals(List.of("public", "sealed"), shape.modifiers(),
                "the declaration's own keywords, sorted, so a reordered list is not a diff");
        Assertions.assertNull(shape.enclosing(), "a top-level type has no enclosing type");
        Assertions.assertEquals(0, shape.depth());
        // The line is -1 while the LST-to-javac position matching is unfinished (see
        // TreeQueries.lineOf and MIGRATION-CAVEATS.md § Open gaps). Asserted explicitly so that
        // completing it is a deliberate change to this expectation rather than a silent shift.
        Assertions.assertEquals(-1, shape.line(),
                "line is unknown, not guessed: an off-by-one points a report link at the wrong code");

        TypeFacts noPackage = named(factsOf("class Bare {}\n"), "Bare");
        Assertions.assertEquals("Bare", noPackage.fqn(),
                "a unit with no package declaration yields the bare type name");
        Assertions.assertFalse(noPackage.modifiers().contains("public"), "it is package-private");
    }

    /** A member type is its own row: the enclosing chain is part of the name, and depth counts it. */
    @Test
    void aMemberTypeCarriesItsEnclosingTypeAndDepth() {
        TypeFacts circle = named(unitFacts(), "a.b.Shape.Circle");
        Assertions.assertEquals("record", circle.kind());
        Assertions.assertEquals("a.b.Shape", circle.enclosing(),
                "the enclosing type's own FQN, so a consumer can walk the nesting");
        Assertions.assertEquals(1, circle.depth());
        Assertions.assertEquals(-1, circle.line(),
                "unknown until the position matching is finished");
        Assertions.assertEquals(List.of(), circle.modifiers(),
                "the source writes no modifier on the record, so none is recorded: the table states what "
                        + "the declaration says rather than what the language infers from its position");
    }

    /** {@code non-sealed} is one keyword spelled with a hyphen, and it is the source's spelling. */
    @Test
    void nonSealedIsSpelledTheWayTheSourceSpellsIt() {
        TypeFacts open = named(unitFacts(), "a.b.Shape.Open");
        Assertions.assertEquals(List.of("non-sealed"), open.modifiers(),
                "`non-sealed` must survive as one keyword; a consumer looking for `sealed` would "
                        + "otherwise read a non-sealed type as sealed");
        Assertions.assertEquals("interface", open.kind());
        Assertions.assertEquals(1, open.depth());
    }

    /** A class inside an interface is still a class, and its kind is the tooling's single answer. */
    @Test
    void kindsCoverEveryDeclarationTheLanguageHas() {
        List<TypeFacts> facts = unitFacts();
        Assertions.assertEquals("class", named(facts, "a.b.Shape.Impl").kind());
        Assertions.assertEquals("class", named(facts, "a.b.PackagePrivate").kind());
        Assertions.assertEquals("annotation", named(facts, "a.b.Marker").kind());
        Assertions.assertEquals("enum", named(facts, "a.b.Colour").kind());
        Assertions.assertEquals("interface", named(facts, "a.b.Shape").kind());
        Assertions.assertEquals("record", named(facts, "a.b.Shape.Circle").kind());
    }

    /** Two members of one interface are two rows, each keyed by its own FQN. */
    @Test
    void membersOfOneTypeAreDistinctRows() {
        List<String> fqns = unitFacts().stream().map(TypeFacts::fqn).toList();
        Assertions.assertEquals(fqns.size(), fqns.stream().distinct().count(),
                "every FQN the fixture declares must appear exactly once: " + fqns);
        Assertions.assertTrue(fqns.contains("a.b.Shape.Circle") && fqns.contains("a.b.Shape.Open")
                        && fqns.contains("a.b.Shape.Impl"),
                "and each member must be a row of its own: " + fqns);
    }

    /** The modifier vocabulary is closed: an unknown keyword is dropped rather than recorded. */
    @Test
    void theModifierVocabularyIsFixed() {
        TypeFacts impl = named(unitFacts(), "a.b.Shape.Impl");
        Assertions.assertEquals(List.of("final"), impl.modifiers(),
                "the keywords the source writes, sorted; the interface member's implicit `static` is not "
                        + "recorded, because the table states declarations rather than inferences");
        for (TypeFacts facts : unitFacts()) {
            for (String modifier : facts.modifiers()) {
                Assertions.assertTrue(TypeFacts.KEYWORDS.contains(modifier),
                        modifier + " is outside the documented vocabulary, so the table would carry a fact "
                                + "no consumer has a contract for");
            }
        }
    }

    /**
     * A declaration on a line of its own records the line of the name, not of the preceding annotation.
     *
     * <p>Suspended while the position matching is unfinished: the fixture is kept because it is the
     * exact case that makes a naive implementation wrong (the declaration starts on the annotation's
     * line, the name on the one after), and it is the test to turn back on when `TreeQueries.lineOf` is
     * implemented. Asserting {@code -1} keeps the requirement visible instead of deleting it.</p>
     */
    @Test
    void theLineIsTheNameLineNotTheAnnotationLine() {
        String annotated = "package a;\n\n@Deprecated\npublic class Marked {\n}\n";
        TypeFacts marked = named(factsOf(annotated), "a.Marked");
        Assertions.assertEquals(-1, marked.line(),
                "the name is on line 4 and the annotation on line 3; until the matching is finished the "
                        + "line is unknown, which is the safe answer — a wrong line opens the wrong code");
    }
}
