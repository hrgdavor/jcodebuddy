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
 * The file's imports, in the {@code imports.json} sidecar beside the table (DEC-040 D1, plan step 3.0t).
 *
 * <p>Why a sidecar and not a field on a row: an import belongs to the <em>file</em>, and one file declares several
 * types — a row-per-type table that repeated the file's imports would be the same copy problem DEC-040 D2 rejects,
 * one level down. Why it exists at all: a consumer that emits source naming the types a declaration names has to
 * know how they were spelled, and reading the file for that is the second parse DEC-030 forbids — this is the fact
 * step 3.0e's generator rewrite needs.</p>
 *
 * <p>The distinction the last case pins is DEC-040 D4's, at the sidecar: <strong>not recorded</strong> and
 * <strong>writes none</strong> are different answers, and a caller that must name a type has to tell them
 * apart.</p>
 */
class ImportsSidecarTest {

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
    void aFilesImportsAreRecordedAsWrittenAndRoundTrip(@TempDir Path tree) throws IOException {
        String source = "package a.b;\n\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "import static java.util.Collections.emptyList;\n"
                + "import java.io.*;\n\n"
                + "public class Importing {\n"
                + "    List<String> names;\n"
                + "    Map<String, String> labels;\n"
                + "}\n";
        writeSource(tree, "a/b/Importing.java", source);
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/Importing.java", unit, source, false);

        Assertions.assertEquals(List.of("import java.util.List;", "import java.util.Map;",
                        "import static java.util.Collections.emptyList;", "import java.io.*;"),
                index.importsOf("a/b/Importing.java"),
                "every shape of import, in the order written — a normal one, a static member, a wildcard");
        index.write();

        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read);
        Assertions.assertEquals(index.importsOf("a/b/Importing.java"), read.importsOf("a/b/Importing.java"),
                "and they survive the write and the read, which is what makes them usable by a consumer");
    }

    @Test
    void aFileThatWritesNoImportsIsAFactAndAnUnknownFileIsNot(@TempDir Path tree) throws IOException {
        String source = "package a.b;\n\npublic class Plain {\n}\n";
        writeSource(tree, "a/b/Plain.java", source);
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/Plain.java", unit, source, false);
        index.write();

        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read);
        Assertions.assertEquals(List.of(), read.importsOf("a/b/Plain.java"),
                "a recorded file with no imports answers with an empty list, which is a fact");
        Assertions.assertNull(read.importsOf("a/b/NotIndexed.java"),
                "while a file this table never saw answers null — 'not recorded' rather than 'writes none'"
                        + " (DEC-040 D4)");
    }

    @Test
    void aRowBuiltFromFactsCarriesNoImports(@TempDir Path tree) throws IOException {
        // The addTypes(List<TypeFacts>) path has no compilation unit, so it records no imports: the honest answer
        // is that they were not read, not an empty list that would read as "this file writes none".
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/Facts.java", "package a.b;\n\npublic class Facts {\n}\n");
        index.addTypes("a/b/Facts.java", List.of(new TypeFacts("a.b.Facts", "class", List.of("public"), null,
                3, 0, List.of(), List.of(), List.of())), false);

        Assertions.assertNull(index.importsOf("a/b/Facts.java"),
                "no parse, no imports — and the sidecar says so rather than guessing");
    }
}
