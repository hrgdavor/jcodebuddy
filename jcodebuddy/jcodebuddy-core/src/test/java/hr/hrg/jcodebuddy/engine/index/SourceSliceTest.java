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
 * DEC-040 D6: **a row can point at the code**, and D2: **the written form is read from the file at that range
 * rather than copied into the table.**
 *
 * <p>Two things are pinned here, and they are two halves of one rule. A member carries the line its name sits on
 * and its character span, so a navigation diagram, a review page or an IDE jump can point at it —
 * {@code SourcePositions}' line and span, taken from the one javac parse of the file. And the span is what makes
 * the source's own text reachable without storing it: {@link SourceSlice} reads the declaring file, verifies it
 * against the row's own checksum, and returns the slice — or says why it will not.</p>
 */
class SourceSliceTest {

    private static ClassIndex indexOf(Path tree) {
        return ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
    }

    private static void writeSource(Path tree, String relativePath, String source) throws IOException {
        Path file = tree.resolve("module").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    /** A file whose members have positions, indexed through the real extraction path. */
    private static ClassIndex indexed(Path tree) throws IOException {
        String source = "package a.b;\n\n"
                + "public class Person {\n"
                + "    private final List<String> names;\n"
                + "\n"
                + "    public Person(List<String> names) {\n"
                + "        this.names = names;\n"
                + "    }\n"
                + "\n"
                + "    public List<String> names() {\n"
                + "        return names;\n"
                + "    }\n"
                + "}\n";
        writeSource(tree, "a/b/Person.java", source);
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");
        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/Person.java", unit, source, false);
        index.write();
        // Read the table back: a row's checksum is a fact about the file, and it is filled in when the pass
        // writes the table — so a slice verified against an in-memory row would be verifying against "".
        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read, "the fixture's table must be readable");
        return read;
    }

    @Test
    void everyMemberCarriesItsLineAndItsSpan(@TempDir Path tree) throws IOException {
        ClassIndex index = indexed(tree);
        List<MemberRecord> members = index.row("a.b.Person").members();

        MemberRecord field = members.get(0);
        Assertions.assertEquals(4, field.line(),
                "a field records the line its NAME sits on, which is the line a jump opens: " + field);
        Assertions.assertNotNull(field.span(), "and a span, so the declaration can be highlighted or sliced");

        MemberRecord constructor = members.get(1);
        Assertions.assertEquals(6, constructor.line(), "a constructor's name line: " + constructor);
        Assertions.assertNotNull(constructor.span());

        MemberRecord method = members.get(2);
        Assertions.assertEquals(10, method.line(), "and a method's: " + method);
        Assertions.assertNotNull(method.span());

        // The span is what recovers the written form — generic arguments included — without the table storing it.
        MemberRecord again = index.row("a.b.Person").members().get(2);
        SourceSlice.Slice slice = SourceSlice.read(index, index.row("a.b.Person"), again);
        Assertions.assertTrue(slice.usable(), slice.problem());
        Assertions.assertTrue(slice.text().contains("public List<String> names()"),
                "the slice is the member's own source as written, generic arguments and all: " + slice.text());
    }

    @Test
    void aSliceIsVerifiedAgainstTheRowsOwnRevision(@TempDir Path tree) throws IOException {
        ClassIndex index = indexed(tree);
        ClassRecord row = index.row("a.b.Person");
        MemberRecord field = row.members().get(0);

        SourceSlice.Slice current = SourceSlice.read(index, row, field);
        Assertions.assertTrue(current.usable(), current.problem());
        Assertions.assertTrue(current.text().contains("List<String> names"),
                "the written form, read from the file: " + current.text());

        // The file moves on. The range still points somewhere, and that is exactly why the slice reports rather
        // than hands back text: a stored copy could not tell anyone that it had gone stale, and a verified range
        // cannot hide it.
        Path file = tree.resolve("module/a/b/Person.java");
        Files.writeString(file, Files.readString(file).replace("private final List<String> names;",
                "private final List<String> names; // edited"), StandardCharsets.UTF_8);

        SourceSlice.Slice stale = SourceSlice.read(index, row, field);
        Assertions.assertNotNull(stale.text(), "the text at the range is still readable");
        Assertions.assertFalse(stale.current(),
                "but it is not the revision this row describes, and the slice says so");
        Assertions.assertTrue(stale.problem().contains("changed since this row was written"), stale.problem());
        Assertions.assertFalse(stale.usable(), "so a caller that must be right does not use it");
    }

    @Test
    void aMissingFileOrAnUnrecordedSpanIsReportedRatherThanGuessed(@TempDir Path tree) throws IOException {
        ClassIndex index = indexed(tree);
        ClassRecord row = index.row("a.b.Person");

        SourceSlice.Slice noSpan = SourceSlice.read(index, row, (hr.hrg.jcodebuddy.engine.source.TreeQueries.SourceSpan) null);
        Assertions.assertNull(noSpan.text());
        Assertions.assertTrue(noSpan.problem().contains("records no span"), noSpan.problem());

        SourceSlice.Slice outside = SourceSlice.read(index, row,
                new hr.hrg.jcodebuddy.engine.source.TreeQueries.SourceSpan(10, 10_000_000));
        Assertions.assertNull(outside.text());
        Assertions.assertTrue(outside.problem().contains("outside the file"), outside.problem());

        Files.delete(tree.resolve("module/a/b/Person.java"));
        SourceSlice.Slice gone = SourceSlice.read(index, row, row.members().get(0));
        Assertions.assertNull(gone.text());
        Assertions.assertTrue(gone.problem().contains("does not exist"), gone.problem());
    }

    @Test
    void aMemberThatCannotBeLocatedRecordsUnknownRatherThanAGuess(@TempDir Path tree) throws IOException {
        // A row built from facts has no positions: -1 and null, which a reader can tell from a line (D4).
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/Fact.java", "package a.b;\n\npublic class Fact {\n}\n");
        index.addTypes("a/b/Fact.java", List.of(new TypeFacts("a.b.Fact", "class", List.of("public"), null, 3, 0,
                List.of(), List.of(), List.of(MemberRecord.field("id", "int", List.of())))), false);

        MemberRecord member = index.row("a.b.Fact").members().get(0);
        Assertions.assertEquals(hr.hrg.jcodebuddy.engine.source.SourcePositions.UNKNOWN_LINE, member.line());
        Assertions.assertNull(member.span());
        SourceSlice.Slice slice = SourceSlice.read(index, index.row("a.b.Fact"), member);
        Assertions.assertFalse(slice.usable());
        Assertions.assertTrue(slice.problem().contains("records no span"), slice.problem());
    }
}
