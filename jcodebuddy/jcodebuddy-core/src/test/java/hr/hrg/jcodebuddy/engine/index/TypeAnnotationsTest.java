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

        TypeAnnotation view = index.row("a.b.PersonSummary").annotations().get(0);
        Assertions.assertEquals("View", view.name());
        Assertions.assertTrue(view.arguments().isEmpty(),
                "() parses to a single J.Empty, which is an empty parameter list rather than one empty argument"
                        + " (DEC-030's trap for methods, the same shape here)");
        Assertions.assertNotNull(view.span(),
                "and the annotation carries the range it is written at, so its source text is recoverable"
                        + " without being stored (DEC-040 D2/D6)");
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
        Assertions.assertTrue(written.contains(
                        "\"annotations\": [{ \"name\": \"View\", \"args\": [\"name = \\\"x\\\"\"], \"span\": null }]"),
                "the writer emits names, arguments and the annotation's range (`null` here, because this row was"
                        + " built from facts rather than read from a source): "
                        + written.substring(written.indexOf("Annotated")));
        Assertions.assertTrue(written.contains("\"annotations\": []"),
                "and emits an empty array for a type that carries none, so an older table without the field stays"
                        + " distinguishable as 'not recorded'");

        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read);
        Assertions.assertEquals(List.of("View"),
                read.row("a.b.Annotated").annotations().stream().map(TypeAnnotation::name).toList());
        Assertions.assertEquals(List.of("name = \"x\""),
                read.row("a.b.Annotated").annotations().get(0).arguments());
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
        // Dropped through the JSON mapper, not by matching the serialisation: this fixture's string surgery has
        // now broken once per field the annotation record grew, which is a test of the writer's spelling rather
        // than of the reader's contract.
        tools.jackson.databind.JsonNode root = hr.hrg.jcodebuddy.engine.MetadataJson.mapper()
                .readTree(Files.readString(file, StandardCharsets.UTF_8));
        ((tools.jackson.databind.node.ObjectNode) root.path("classes").path("a.b.Annotated")).remove("annotations");
        Files.writeString(file, hr.hrg.jcodebuddy.engine.MetadataJson.mapper().writeValueAsString(root),
                StandardCharsets.UTF_8);

        ClassIndex read = ClassIndex.read(file, tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read, "an older table is still readable");
        Assertions.assertTrue(read.row("a.b.Annotated").annotations().isEmpty(),
                "and reads as empty because it was not recorded — the reason the writer always emits the field");
    }
}
