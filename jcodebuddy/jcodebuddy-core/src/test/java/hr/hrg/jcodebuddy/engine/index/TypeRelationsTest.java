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
 * Class relations in the index (plan step 3.0b): the forward facts, the reverse direction, and the two cases
 * that make the difference between a recorded name and a phantom.
 *
 * <p>The gate names three of these in advance — a relation round-trips, a relation to a type outside the
 * module's sources is recorded as the name it is rather than dropped, and an unresolvable supertype is visible
 * as such rather than absent — and this test adds the fourth that the LST forces on anyone who touches
 * supertypes: <strong>an interface's {@code extends} clause lives in {@code getImplements()}</strong>, so
 * reading {@code getExtends()} alone finds nothing for the commonest declaration in this project (DEC-030's
 * measured trap).</p>
 */
class TypeRelationsTest {

    private static ClassIndex emptyIndex(Path tree) {
        return ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
    }

    /**
     * The declaring file a row points at, because {@link ClassIndex#write()} hashes every row's file and
     * refuses a row whose file nobody read ("a row whose size and checksum describe a file nobody read is worse
     * than no row"). A test that writes a table therefore needs the sources it describes.
     */
    private static void writeSource(Path tree, String relativePath, String source) throws IOException {
        Path file = tree.resolve("module").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static ClassIndex reread(ClassIndex index, Path tree) {
        return ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
    }

    @Test
    void relationsRoundTripThroughTheTable(@TempDir Path tree) throws IOException {
        ClassIndex index = emptyIndex(tree);
        writeSource(tree, "a/b/Person.java", "package a.b;\n\npublic interface Person extends a.b.Base {\n}\n");
        writeSource(tree, "a/b/Employee.java",
                "package a.b;\n\npublic class Employee extends Person implements java.io.Serializable {\n}\n");
        index.addTypes("a/b/Person.java", List.of(new TypeFacts("a.b.Person", "interface", List.of("public"),
                null, 3, 0, List.of(TypeRelation.extendsType("a.b.Base")))), false);
        index.addTypes("a/b/Employee.java", List.of(new TypeFacts("a.b.Employee", "class", List.of("public"),
                null, 3, 0, List.of(TypeRelation.extendsType("Person"),
                        TypeRelation.implementsType("java.io.Serializable")))), false);
        index.write();

        ClassIndex read = reread(index, tree);

        Assertions.assertNotNull(read, "the table this writer just wrote must be readable");
        Assertions.assertEquals(List.of(TypeRelation.extendsType("Person"),
                        TypeRelation.implementsType("java.io.Serializable")),
                read.row("a.b.Employee").relations(),
                "the names and the clause kinds survive the round trip, in source order");
        Assertions.assertEquals(List.of(TypeRelation.extendsType("a.b.Base")),
                read.row("a.b.Person").relations());
    }

    @Test
    void aRelationToATypeOutsideTheModulesSourcesIsRecordedAsWritten(@TempDir Path tree) throws IOException {
        ClassIndex index = emptyIndex(tree);
        writeSource(tree, "a/b/Employee.java",
                "package a.b;\n\npublic class Employee implements java.io.Serializable {\n}\n");
        index.addTypes("a/b/Employee.java", List.of(new TypeFacts("a.b.Employee", "class", List.of("public"),
                null, 3, 0, List.of(TypeRelation.implementsType("java.io.Serializable")))), false);
        index.write();

        ClassIndex read = reread(index, tree);

        Assertions.assertEquals(List.of(TypeRelation.implementsType("java.io.Serializable")),
                read.row("a.b.Employee").relations(), "an outside type is not dropped for being outside");
        Assertions.assertNull(read.row("java.io.Serializable"),
                "and it is honestly not a row of this module's table: absence of a row is not absence of the"
                        + " relation, which is the distinction this step and 3.0f-3 keep apart");
    }

    @Test
    void anInterfacesExtendsClauseIsAnExtendsNotAnImplements(@TempDir Path tree) {
        String source = "package a.b;\n\npublic interface PersonSummary extends a.b.Person {\n    String name();\n}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = emptyIndex(tree);
        index.addTypes("a/b/PersonSummary.java", unit, source, false);

        Assertions.assertEquals(List.of(TypeRelation.extendsType("a.b.Person")),
                index.row("a.b.PersonSummary").relations(),
                "the LST holds an interface's extends clause in getImplements(), so a reader of getExtends()"
                        + " alone finds no supertype at all; the kind must come from the declaration");
    }

    @Test
    void aClassKeepsExtendsAndImplementsApart(@TempDir Path tree) {
        String source = "package a.b;\n\npublic class Employee extends Person implements java.io.Serializable {\n}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = emptyIndex(tree);
        index.addTypes("a/b/Employee.java", unit, source, false);

        Assertions.assertEquals(List.of(TypeRelation.extendsType("Person"),
                        TypeRelation.implementsType("java.io.Serializable")),
                index.row("a.b.Employee").relations(),
                "extends comes first and keeps its kind, so a relation diagram cannot draw the two edges alike");
    }

    @Test
    void theReverseDirectionAnswersWhoImplements(@TempDir Path tree) {
        ClassIndex index = emptyIndex(tree);
        index.addTypes("a/b/Employee.java", List.of(new TypeFacts("a.b.Employee", "class", List.of("public"),
                null, 3, 0, List.of(TypeRelation.implementsType("CtxModule")))), false);
        index.addTypes("a/b/Other.java", List.of(new TypeFacts("a.b.Other", "class", List.of("public"),
                null, 3, 0, List.of(TypeRelation.extendsType("Person")))), false);

        Assertions.assertEquals(List.of("a.b.Employee"),
                index.subtypesOf("CtxModule").stream().map(ClassRecord::fqn).toList(),
                "who implements CtxModule, answered from the table without parsing a file");
        Assertions.assertEquals(List.of("a.b.Other"),
                index.subtypesOf("Person").stream().map(ClassRecord::fqn).toList(),
                "and an extends edge counts as a subtype edge: this question does not care which clause did it");
        Assertions.assertTrue(index.subtypesOf("a.b.CtxModule").isEmpty(),
                "the match is the spelling the source used, and this limit is documented rather than guessed"
                        + " around: closing it is search's work (3.0h)");
        Assertions.assertTrue(index.subtypesOf("a.b.Ghost").isEmpty(),
                "an unresolvable name is visible as a name in the row and finds no subtypes \u2014 which is not the"
                        + " same as the relation being absent");
    }

    @Test
    void aTableWrittenBeforeRelationsExistedReadsAsNotRecorded(@TempDir Path tree) throws IOException {
        ClassIndex index = emptyIndex(tree);
        writeSource(tree, "a/b/Employee.java", "package a.b;\n\npublic class Employee implements CtxModule {\n}\n");
        index.addTypes("a/b/Employee.java", List.of(new TypeFacts("a.b.Employee", "class", List.of("public"),
                null, 3, 0, List.of(TypeRelation.implementsType("CtxModule")))), false);
        index.write();

        // A pre-3.0b table: the same table, with the field the writer now always emits taken back out.
        Path file = index.indexFile();
        String written = Files.readString(file, StandardCharsets.UTF_8);
        Assertions.assertTrue(written.contains("\"relations\": [{ \"name\": \"CtxModule\", \"kind\": \"implements\" }]"),
                "the writer always emits the field, so an older table is distinguishable from an empty one");
        Files.writeString(file, written.replace(", \"relations\": [{ \"name\": \"CtxModule\", \"kind\": \"implements\" }]", ""),
                StandardCharsets.UTF_8);

        ClassIndex read = reread(index, tree);

        Assertions.assertNotNull(read, "an older table is still a table this reader accepts");
        Assertions.assertTrue(read.row("a.b.Employee").relations().isEmpty(),
                "it reads as empty because it was not recorded \u2014 which the writer's always-present field is"
                        + " what makes distinguishable for tables written from now on");
    }
}
