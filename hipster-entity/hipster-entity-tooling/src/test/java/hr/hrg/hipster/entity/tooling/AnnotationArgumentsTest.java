package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan step 6.7: a class reference inside an annotation argument's text is resolved against the declaring
 * file's imports and emitted fully qualified, and a reference that cannot be resolved is **reported**
 * rather than passed through in silence.
 *
 * <h3>Where a class literal actually appears, which this test establishes</h3>
 * <p>Not in a project's own annotation: an annotation outside the validation namespace is not a constraint
 * this generator recognises and never reaches these artifacts (measured — a fixture using one emitted no
 * {@code annotations()} override at all). It appears in <strong>the group and payload members every Bean
 * Validation constraint declares</strong>: {@code groups()} is {@code Class<?>[]} and {@code payload()} is
 * {@code Class<? extends Payload>[]} on all of them, so {@code @Size(min = 1, groups = Create.class)} is
 * ordinary usage whose argument is a class literal the generator carries as text.</p>
 *
 * <p>So the tests below run the real generator over a tree whose view uses such a constraint and read the
 * emitted field enum, because that is where the fact has to be right: the metadata JSON, the generated
 * {@code annotations()} override and every report read the same text, so a test against
 * {@code AnnotationArguments.qualify} alone would prove the rewrite and not that it reaches an artifact.</p>
 *
 * <p>The unresolvable case is the one worth stating precisely. A simple name with no matching import is
 * not a generator failure — {@code String.class} is a {@code java.lang} type that needs no import, and a
 * nested {@code Outer.Inner.class} is a form the table cannot resolve without guessing — so the text is
 * kept exactly as written and the finding is named. Dropping it or inventing a package would both be
 * worse than the incomplete text.</p>
 */
class AnnotationArgumentsTest {

    private static final String MARKER = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {
                Long id();
            }
            """;

    /** The validation group referenced by the constraint: in another package, so it must be imported. */
    private static final String GROUP = """
            package fixture.other;
            public interface Create {
            }
            """;

    /** The view: `groups` is the member whose argument is a class literal. */
    private static final String VIEW = """
            package fixture.hr;
            import hr.hrg.hipster.entity.api.View;
            import fixture.other.Create;
            import jakarta.validation.constraints.Size;
            public interface PersonSummary extends PersonEntity {
                @Size(min = 1, max = 50, groups = Create.class)
                String firstName();
                @Size(min = 1, groups = String.class)
                String nickname();
                @Pattern(regexp = "Create.class")
                String label();
            }
            """;

    private static final String PATTERN_IMPORT = "import jakarta.validation.constraints.Pattern;\n";

    /** Generates the tree and returns the emitted field enum's text. */
    private static String generateAndReadEnum(Path tree, String view) throws Exception {
        Path sourceRoot = tree.resolve("src");
        Path outputRoot = tree.resolve("out");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.createDirectories(sourceRoot.resolve("other"));
        Files.writeString(sourceRoot.resolve("other/Create.java"), GROUP);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), view);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return Files.readString(outputRoot.resolve("fixture/hr/PersonSummary_.java"));
    }

    private static String viewWithPatternImport() {
        return VIEW.replace("import jakarta.validation.constraints.Size;\n",
                "import jakarta.validation.constraints.Size;\n" + PATTERN_IMPORT);
    }

    @Test
    void anImportedClassArgumentIsEmittedFullyQualified(@TempDir Path tree) throws Exception {
        String emitted = generateAndReadEnum(tree, viewWithPatternImport());

        Assertions.assertTrue(emitted.contains("groups = fixture.other.Create.class"),
                "the class-valued argument must carry the qualified name, so a consumer needs no import "
                        + "table to understand it: " + emitted);
        Assertions.assertFalse(emitted.contains("groups = Create.class"),
                "and it must not still be the bare spelling the author wrote");
    }

    @Test
    void aJavaLangClassArgumentIsQualifiedLikeAnyOtherImport(@TempDir Path tree) throws Exception {
        String emitted = generateAndReadEnum(tree, viewWithPatternImport());

        Assertions.assertTrue(emitted.contains("groups = java.lang.String.class"),
                "the declared type is what the AUTHOR wrote (`String`), so the argument is qualified with "
                        + "the import that makes it mean that — which is strictly more informative than the "
                        + "bare spelling, not noise: " + emitted);
    }

    @Test
    void aStringArgumentThatLooksLikeAClassLiteralIsNotRewritten(@TempDir Path tree) throws Exception {
        String emitted = generateAndReadEnum(tree, viewWithPatternImport());

        // The emitted override is Java source, so the argument's own quotes are escaped there — the
        // assertion is about the TEXT, and the escaped spelling is what that text looks like in the file.
        Assertions.assertTrue(emitted.contains("regexp = \\\"Create.class\\\""),
                "a regexp is not a type: rewriting it would corrupt the constraint the author declared. "
                        + "The first version of this resolver DID rewrite it, which is why this test exists: "
                        + emitted);
        Assertions.assertFalse(emitted.contains("regexp = \\\"fixture.other.Create.class\\\""), emitted);
    }

    @Test
    void theOtherArgumentsOfAConstraintAreUntouched(@TempDir Path tree) throws Exception {
        String emitted = generateAndReadEnum(tree, viewWithPatternImport());

        Assertions.assertTrue(emitted.contains("min = 1, max = 50"),
                "a numeric argument is carried through exactly as declared: " + emitted);
    }

    @Test
    void theQualifyRewriteHandlesEveryFormItClaimsTo() {
        Map<String, String> imports = new LinkedHashMap<>();
        imports.put("Create", "fixture.other.Create");
        imports.put("Node", "a.hr.Node");
        // What an author gets for a bare `String`: the implicit java.lang import, as an explicit entry.
        imports.put("String", "java.lang.String");

        // A single member, a named member, several arguments, and a nested list in one argument.
        Assertions.assertEquals("fixture.other.Create.class",
                AnnotationArguments.qualify("Create.class", imports, "X.y", null));
        Assertions.assertEquals("groups = fixture.other.Create.class",
                AnnotationArguments.qualify("groups = Create.class", imports, "X.y", null));
        Assertions.assertEquals("groups = {fixture.other.Create.class, a.hr.Node.class}",
                AnnotationArguments.qualify("groups = {Create.class, Node.class}", imports, "X.y", null));

        // A java.lang type IS resolved, because the table says what the author's `String` means: the
        // qualified spelling is what a consumer with no import table can read, which is the point of the
        // step. The unresolvable cases are the ones below.
        Assertions.assertEquals("groups = java.lang.String.class",
                AnnotationArguments.qualify("groups = String.class", imports, "X.y", null));

        // Left alone: already qualified, a name the table does not hold, and anything inside quotes.
        Assertions.assertEquals("a.b.Create.class",
                AnnotationArguments.qualify("a.b.Create.class", imports, "X.y", null));
        Assertions.assertEquals("Outer.Inner.class",
                AnnotationArguments.qualify("Outer.Inner.class", imports, "X.y", null));
        Assertions.assertEquals("regexp = \"Create.class\"",
                AnnotationArguments.qualify("regexp = \"Create.class\"", imports, "X.y", null));
        Assertions.assertEquals("regexp = \"a Create.class b\"",
                AnnotationArguments.qualify("regexp = \"a Create.class b\"", imports, "X.y", null));
    }

    @Test
    void anUnresolvableClassReferenceIsReportedAndKeptAsWritten(@TempDir Path tree) throws Exception {
        // `Create` is NOT imported: the argument names a type the declaring file never imported, so the
        // generator has no qualified name to write and must say so instead of guessing one.
        String view = viewWithPatternImport().replace("import fixture.other.Create;\n", "");

        Path sourceRoot = tree.resolve("src");
        Path outputRoot = tree.resolve("out");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.createDirectories(sourceRoot.resolve("other"));
        Files.writeString(sourceRoot.resolve("other/Create.java"), GROUP);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), view);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, divergences);

        List<String> reported = divergences.ofKind("annotation_class_not_resolved");
        Assertions.assertFalse(reported.isEmpty(),
                "an unresolvable class literal must be reported in DEC-022's format: " + divergences.entries());
        Assertions.assertTrue(reported.get(0).contains("Create.class"), reported.get(0));
        Assertions.assertTrue(
                Files.readString(outputRoot.resolve("fixture/hr/PersonSummary_.java"))
                        .contains("groups = Create.class"),
                "and the text stays exactly as the author wrote it: dropping it would lose the fact");
    }
}
