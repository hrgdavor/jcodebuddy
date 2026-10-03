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
 * The last four facts DEC-040 D1 asks the model to keep — a sealed type's {@code permits}, a callable's
 * {@code throws}, enum constants, and a field's initialiser plus whether a callable has a body (plan step 3.0t).
 *
 * <p>Each is a fact the compiler erases or hides: {@code permits} is a class-file attribute reflection exposes
 * unevenly, a {@code throws} clause is an attribute rather than a runtime type list, an enum constant's arguments
 * exist only as the constant's own construction, and "has a body" is the difference between an abstract method
 * and a default one — all of which a source-only reader sees plainly and a runtime reader does not. They are
 * pinned here as a round trip <em>and</em> as an extraction, because a fact that round-trips but is never read
 * out of a declaration is a field nobody fills.</p>
 */
class MemberFactsTest {

    private static ClassIndex indexOf(Path tree) {
        return ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
    }

    private static void writeSource(Path tree, String relativePath, String source) throws IOException {
        Path file = tree.resolve("module").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    /** Index a fixture and read the table back, so the assertions see what a consumer sees. */
    private static ClassIndex indexed(Path tree, String relativePath, String source) throws IOException {
        writeSource(tree, relativePath, source);
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");
        ClassIndex index = indexOf(tree);
        index.addTypes(relativePath, unit, source, false);
        index.write();
        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read, "the fixture's table must be readable");
        return read;
    }

    @Test
    void aSealedTypesPermittedSubtypesAreRecordedAsWritten(@TempDir Path tree) throws IOException {
        ClassIndex read = indexed(tree, "a/b/Shape.java", "package a.b;\n\n"
                + "public sealed interface Shape permits Circle, Square {\n"
                + "}\n");

        Assertions.assertEquals(List.of("Circle", "Square"),
                read.row("a.b.Shape").permits(),
                "a sealed type's permitted subtypes, as written and in order (DEC-040 D1)");
        Assertions.assertTrue(read.row("a.b.Shape").modifiers().contains("sealed"),
                "and the modifier that makes the list meaningful is recorded with it");
    }

    @Test
    void aTypeThatIsNotSealedRecordsAnEmptyListRatherThanNothing(@TempDir Path tree) throws IOException {
        ClassIndex read = indexed(tree, "a/b/Plain.java", "package a.b;\n\npublic class Plain {\n}\n");

        Assertions.assertEquals(List.of(), read.row("a.b.Plain").permits(),
                "empty is the fact \"not sealed\", not a gap — and the writer always emits the field so a table "
                        + "written before it stays distinguishable (DEC-040 D4)");
    }

    @Test
    void enumConstantsAreMembersInDeclarationOrderWithTheirArguments(@TempDir Path tree) throws IOException {
        ClassIndex read = indexed(tree, "a/b/Status.java", "package a.b;\n\n"
                + "public enum Status implements java.io.Serializable {\n"
                + "    ACTIVE(\"a\", 1),\n"
                + "    INACTIVE,\n"
                + "    DELETED(\"d\");\n"
                + "\n"
                + "    private final String code;\n"
                + "\n"
                + "    Status(String code, int weight) {\n"
                + "        this.code = code;\n"
                + "    }\n"
                + "\n"
                + "    Status(String code) {\n"
                + "        this(code, 0);\n"
                + "    }\n"
                + "\n"
                + "    Status() {\n"
                + "        this(\"\");\n"
                + "    }\n"
                + "}\n");

        List<MemberRecord> members = read.row("a.b.Status").members();
        List<MemberRecord> constants = members.stream()
                .filter(member -> member.kind() == MemberRecord.Kind.ENUM_CONSTANT).toList();

        Assertions.assertEquals(List.of("ACTIVE", "INACTIVE", "DELETED"),
                constants.stream().map(MemberRecord::name).toList(),
                "enum constants are members of the enum, in declaration order — before this they were recorded "
                        + "nowhere, so a consumer could only work around the omission by parsing the file");
        Assertions.assertTrue(constants.stream().allMatch(constant -> constant.modifiers().isEmpty()),
                "and no modifiers: Java gives a constant public static final whether or not anyone writes it, and "
                        + "recording keywords the source does not say is what this model refuses to do");
        Assertions.assertTrue(constants.stream().allMatch(constant -> constant.line() > 0),
                "with the line each is written on, so a consumer can point at one (DEC-040 D6)");
        Assertions.assertTrue(constants.stream().allMatch(constant -> !constant.hasInitializer()),
                "and a constant is not a field with an initialiser: its arguments are its own fact, read from "
                        + "its span below rather than flagged here");

        // The arguments are recovered by SLICING, not stored — the correction that came out of storing one as
        // text: an arbitrary expression has no faithful text form, and the LST's fallback was a debug dump with a
        // fresh UUID in it, which is non-determinism in a table that must be byte-identical across passes.
        for (MemberRecord constant : constants) {
            SourceSlice.Slice slice = SourceSlice.read(read, read.row("a.b.Status"), constant);
            Assertions.assertTrue(slice.usable(), slice.problem());
            switch (constant.name()) {
                case "ACTIVE" -> Assertions.assertEquals("ACTIVE(\"a\", 1)", slice.text(),
                        "a constant's arguments come back from its own span, as written");
                case "INACTIVE" -> Assertions.assertEquals("INACTIVE", slice.text(),
                        "and a bare constant slices to just its name — no empty argument list invented");
                default -> Assertions.assertEquals("DELETED(\"d\")", slice.text());
            }
        }

        // The methods and fields are still there beside them, in source order: constants come first in the file.
        Assertions.assertEquals(List.of("ACTIVE", "INACTIVE", "DELETED", "code", "Status", "Status", "Status"),
                members.stream().map(MemberRecord::name).toList(),
                "the constants are members of a declaration that also has fields and constructors");
    }

    @Test
    void aFieldCarriesItsInitialiserAndACallableCarriesItsThrowsClauseAndBodyQuestion(@TempDir Path tree)
            throws IOException {
        ClassIndex read = indexed(tree, "a/b/Service.java", "package a.b;\n\n"
                + "public abstract class Service {\n"
                + "    private static final String NAME = \"svc\";\n"
                + "    private int count = 0, limit = 10;\n"
                + "    private String noValue;\n"
                + "\n"
                + "    abstract void run() throws java.io.IOException, InterruptedException;\n"
                + "\n"
                + "    void stop() {\n"
                + "        count = 0;\n"
                + "    }\n"
                + "}\n");

        List<MemberRecord> members = read.row("a.b.Service").members();
        MemberRecord name = members.get(0);
        Assertions.assertTrue(name.hasInitializer(),
                "a field that declares an initialiser says so — the expression itself is inside the member's span "
                        + "and recovered by slicing it, because an arbitrary expression has no faithful text form "
                        + "(DEC-040 D2, and the reason a first version of this fact was non-deterministic)");
        Assertions.assertTrue(members.get(1).hasInitializer() && members.get(2).hasInitializer(),
                "and per declarator, not per statement: `private int count = 0, limit = 10;` gives both fields one");
        Assertions.assertFalse(members.get(3).hasInitializer(),
                "while a field that declares none says so, and the writer always emits the field");

        // The written form is reachable without being stored, which is the whole point of the flag.
        SourceSlice.Slice nameSlice = SourceSlice.read(read, read.row("a.b.Service"), name);
        Assertions.assertTrue(nameSlice.text().contains("= \"svc\""),
                "the initialiser as written comes back from the member's own span: " + nameSlice.text());
        SourceSlice.Slice countSlice = SourceSlice.read(read, read.row("a.b.Service"), members.get(1));
        Assertions.assertTrue(countSlice.text().contains("= 0"),
                "and for the first declarator of a multi-variable field: " + countSlice.text());

        MemberRecord run = members.get(4);
        Assertions.assertEquals(List.of("java.io.IOException", "InterruptedException"), run.throwsClause(),
                "a callable's throws clause as written, in order — a fact a generator that emits a call must "
                        + "declare (DEC-040 D1)");
        Assertions.assertFalse(run.hasBody(),
                "and an abstract method has no body, which is what tells a generator it must override rather "
                        + "than call");

        MemberRecord stop = members.get(5);
        Assertions.assertTrue(stop.hasBody(), "while a method that declares a body has one");
        Assertions.assertEquals(List.of(), stop.throwsClause(), "and declares no throws clause");
    }

    @Test
    void theKindSpecificFactsSurviveAWriteAndAReadForEveryMemberKind(@TempDir Path tree) throws IOException {
        // Round trip over a declaration whose members exercise all five kinds at once, compared field by field
        // rather than as whole records: the record's equals is not what this test is about, the format is.
        ClassIndex read = indexed(tree, "a/b/Everything.java", "package a.b;\n\n"
                + "public sealed class Everything permits Nothing {\n"
                + "    static final String FIELD = \"v\";\n"
                + "    enum Kind { ONE(1), TWO; Kind() {} Kind(int i) {} }\n"
                + "    interface Nested {}\n"
                + "    Everything() throws java.io.IOException {}\n"
                + "    void call() throws java.io.IOException {}\n"
                + "}\n");

        ClassRecord row = read.row("a.b.Everything");
        Assertions.assertEquals(List.of("Nothing"), row.permits(), "the row's sealed list survived");

        MemberRecord field = row.members().stream()
                .filter(member -> member.kind() == MemberRecord.Kind.FIELD).findFirst().orElseThrow();
        Assertions.assertTrue(field.hasInitializer(), "the field's initialiser flag came back");
        Assertions.assertTrue(SourceSlice.read(read, row, field).text().contains("\"v\""),
                "and its written value is reachable by slicing the member's span");

        // The constants belong to the NESTED enum's own row, not to the declaring type's — which is the whole
        // reason a nested type gets a row of its own.
        ClassRecord nested = read.row("a.b.Everything.Kind");
        Assertions.assertNotNull(nested, "the nested enum is its own row");
        List<MemberRecord> nestedConstants = nested.members().stream()
                .filter(member -> member.kind() == MemberRecord.Kind.ENUM_CONSTANT).toList();
        Assertions.assertEquals(List.of("ONE", "TWO"), nestedConstants.stream().map(MemberRecord::name).toList(),
                "both constants survived the round trip");
        Assertions.assertEquals(List.of("ONE(1)", "TWO"),
                nestedConstants.stream()
                        .map(constant -> SourceSlice.read(read, nested, constant).text())
                        .toList(),
                "and each one's span came back, which is what carries its arguments: a bare constant slices to "
                        + "its name and a constructed one to the call");
        Assertions.assertEquals(List.of("Kind", "Nested"),
                row.members().stream().filter(member -> member.kind() == MemberRecord.Kind.NESTED)
                        .map(MemberRecord::name).toList(),
                "while the declaring type records the nested types it contains, as it always did (including the "
                        + "enum whose constants belong to its own row, not to this one)");
    }
}
