package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Annotation info in the core model (asked for 2026-10-02, DEC-029's annotation field).
 *
 * <p>What these tests pin is the boundary as much as the data: the engine records the annotations a declaration
 * carries <em>as written</em>, with their arguments as text and without evaluating them — {@code @View} is not
 * special to the engine, which is what lets hipster-entity extract its own meaning from the same fact
 * (DEC-037's "the engine parses and analyses; a consumer projects").</p>
 */
class TypeAnnotationsTest {

    private static ClassIndex indexOf(Path tree) {
        return ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
    }

    private static void writeSource(Path tree, String relativePath, String source) throws IOException {
        Path file = tree.resolve("module").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    @Test
    void annotationsAreReadAsWrittenWithTheirArguments(@TempDir Path tree) {
        String source = "package a.b;\n\n"
                + "@Deprecated\n"
                + "@View(name = \"person\", level = 1)\n"
                + "public interface PersonSummary {\n    String name();\n}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/PersonSummary.java", unit, source, false);

        List<TypeAnnotation> annotations = index.row("a.b.PersonSummary").annotations();
        Assertions.assertEquals(List.of("Deprecated", "View"),
                annotations.stream().map(TypeAnnotation::name).toList(),
                "declaration order, and the name without a leading @");
        Assertions.assertTrue(annotations.get(0).arguments().isEmpty(), "no arguments written, none recorded");
        Assertions.assertEquals(List.of("name = \"person\"", "level = 1"), annotations.get(1).arguments(),
                "arguments are the source text, in order and unevaluated — the engine does not fold, resolve or"
                        + " apply defaults");
    }

    @Test
    void anAnnotationWithAnEmptyParameterListHasNoArguments(@TempDir Path tree) {
        String source = "package a.b;\n\n@View()\npublic interface PersonSummary {\n}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/PersonSummary.java", unit, source, false);

        Assertions.assertEquals(List.of(TypeAnnotation.of("View")),
                index.row("a.b.PersonSummary").annotations(),
                "() parses to a single J.Empty, which is an empty parameter list rather than one empty argument"
                        + " (DEC-030's trap for methods, the same shape here)");
    }

    @Test
    void annotationsRoundTripAndTheFieldIsAlwaysEmitted(@TempDir Path tree) throws IOException {
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/Annotated.java", "package a.b;\n\n@View(name = \"x\")\npublic interface Annotated {\n}\n");
        writeSource(tree, "a/b/Plain.java", "package a.b;\n\npublic interface Plain {\n}\n");
        index.addTypes("a/b/Annotated.java", List.of(new TypeFacts("a.b.Annotated", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of(),
                List.of(new TypeAnnotation("View", List.of("name = \"x\""))))), false);
        index.addTypes("a/b/Plain.java", List.of(new TypeFacts("a.b.Plain", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of(), List.of())), false);
        index.write();

        String written = Files.readString(index.indexFile(), StandardCharsets.UTF_8);
        Assertions.assertTrue(written.contains("\"annotations\": [{ \"name\": \"View\", \"args\": [\"name = \\\"x\\\"\"] }]"),
                "the writer emits names and arguments: " + written.substring(written.indexOf("Annotated")));
        Assertions.assertTrue(written.contains("\"annotations\": []"),
                "and emits an empty array for a type that carries none, so an older table without the field stays"
                        + " distinguishable as 'not recorded'");

        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read);
        Assertions.assertEquals(List.of(new TypeAnnotation("View", List.of("name = \"x\""))),
                read.row("a.b.Annotated").annotations());
        Assertions.assertTrue(read.row("a.b.Plain").annotations().isEmpty(), "a fact, not a gap");
    }

    @Test
    void aTableWrittenBeforeAnnotationsExistedReadsAsNotRecorded(@TempDir Path tree) throws IOException {
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/Annotated.java", "package a.b;\n\n@View\npublic interface Annotated {\n}\n");
        index.addTypes("a/b/Annotated.java", List.of(new TypeFacts("a.b.Annotated", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of(),
                List.of(TypeAnnotation.of("View")))), false);
        index.write();

        Path file = index.indexFile();
        String written = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, written.replace(", \"annotations\": [{ \"name\": \"View\", \"args\": [] }]", ""),
                StandardCharsets.UTF_8);

        ClassIndex read = ClassIndex.read(file, tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read, "an older table is still readable");
        Assertions.assertTrue(read.row("a.b.Annotated").annotations().isEmpty(),
                "and reads as empty because it was not recorded — the reason the writer always emits the field");
    }
}
