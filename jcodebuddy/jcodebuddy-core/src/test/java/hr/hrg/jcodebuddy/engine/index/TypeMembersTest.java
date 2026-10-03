package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
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
 * Member info in the core model (plan step 3.0r, DEC-029's member field).
 *
 * <p>What these tests pin is the boundary as much as the data: a row records the members a declaration has
 * <em>as written</em> — names, kinds, the types the source spelled — and never a body, never an initialiser and
 * never a resolved name. That is what makes "does this type have a field {@code id}, and what type is it"
 * answerable from the index alone, which is the question the generator seam asks (step 3.0d).</p>
 *
 * <p>The last test is the one that would have caught the mistake this step could make: a table written before
 * members were recorded must read as <em>not recorded</em> rather than as "declares none", because the second
 * is a fact and the first is a gap.</p>
 */
class TypeMembersTest {

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
    void membersAreReadAsWrittenWithTheirKindsTypesModifiersAndAnnotations(@TempDir Path tree) {
        String source = "package a.b;\n\n"
                + "public class Person {\n"
                + "    private static final int MAX = 3;\n"
                + "    @Deprecated\n"
                + "    String name;\n"
                + "\n"
                + "    public Person(int age) {\n    }\n"
                + "\n"
                + "    public final List<String> names(int count, String prefix) {\n        return null;\n    }\n"
                + "\n"
                + "    interface Nested {\n    }\n"
                + "}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/Person.java", unit, source, false);

        List<MemberRecord> members = index.row("a.b.Person").members();
        Assertions.assertEquals(List.of("MAX", "name", "Person", "names", "Nested"),
                members.stream().map(MemberRecord::name).toList(),
                "source order, and a declaration's members are all here — including the nested type");
        Assertions.assertEquals(List.of(MemberRecord.Kind.FIELD, MemberRecord.Kind.FIELD,
                        MemberRecord.Kind.CONSTRUCTOR, MemberRecord.Kind.METHOD, MemberRecord.Kind.NESTED),
                members.stream().map(MemberRecord::kind).toList());

        MemberRecord max = members.get(0);
        Assertions.assertEquals("int", max.type(), "a field records the type the source wrote");
        Assertions.assertEquals(List.of("final", "private", "static"), max.modifiers(),
                "modifiers are the same vocabulary as a type's, filtered and sorted");

        MemberRecord name = members.get(1);
        Assertions.assertEquals(List.of(TypeAnnotation.of("Deprecated")), name.annotations(),
                "a member's annotations are read the same way a declaration's are");

        MemberRecord constructor = members.get(2);
        Assertions.assertEquals("", constructor.type(),
                "a constructor declares no return type, so nothing is invented for it");
        Assertions.assertEquals(List.of("int"), constructor.parameterTypes());

        MemberRecord method = members.get(3);
        Assertions.assertEquals("List<String>", method.type(), "a method records its return type as written");
        Assertions.assertEquals(List.of("int", "String"), method.parameterTypes());
        Assertions.assertEquals("names(int, String)", method.signature());

        Assertions.assertEquals("Nested", members.get(4).type(),
                "a nested member carries its simple name; its own row is keyed by the full name");
        Assertions.assertEquals("a.b.Person.Nested", index.row("a.b.Person.Nested").fqn(),
                "and that row exists independently of the member record");
    }

    @Test
    void aParameterlessMethodHasNoParametersRatherThanOneEmptyOne(@TempDir Path tree) {
        String source = "package a.b;\n\npublic interface PersonSummary {\n    String name();\n}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/PersonSummary.java", unit, source, false);

        MemberRecord member = index.row("a.b.PersonSummary").members().get(0);
        Assertions.assertEquals(MemberRecord.Kind.METHOD, member.kind(),
                "an annotation or interface member is a method, as Java makes it");
        Assertions.assertEquals("String", member.type());
        Assertions.assertTrue(member.parameterTypes().isEmpty(),
                "() parses to a single J.Empty, which is 'no parameters' rather than one parameter whose type"
                        + " text is empty (DEC-030's trap for methods, the same shape here)");
        Assertions.assertEquals("name", member.signature());
    }

    @Test
    void aMultiVariableDeclarationIsOneMemberEach(@TempDir Path tree) {
        String source = "package a.b;\n\npublic class Pair {\n    public int left, right;\n}\n";
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/Pair.java", unit, source, false);

        Assertions.assertEquals(List.of("left", "right"),
                index.row("a.b.Pair").members().stream().map(MemberRecord::name).toList(),
                "`int left, right;` declares two fields, and a consumer asks about one of them by name");
    }

    @Test
    void membersRoundTripAndTheFieldIsAlwaysEmitted(@TempDir Path tree) throws IOException {
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/WithMembers.java", "package a.b;\n\npublic class WithMembers {\n    int id;\n}\n");
        writeSource(tree, "a/b/Empty.java", "package a.b;\n\npublic class Empty {\n}\n");
        index.addTypes("a/b/WithMembers.java", List.of(new TypeFacts("a.b.WithMembers", "class",
                List.of("public"), null, 3, 0, List.of(), List.of(),
                List.of(new MemberRecord("id", MemberRecord.Kind.FIELD, "int", List.of(),
                        List.of("public"), List.of())))), false);
        index.addTypes("a/b/Empty.java", List.of(new TypeFacts("a.b.Empty", "class", List.of("public"), null,
                3, 0, List.of(), List.of(), List.of())), false);
        index.write();

        String written = Files.readString(index.indexFile(), StandardCharsets.UTF_8);
        Assertions.assertTrue(written.contains("\"members\": [{ \"name\": \"id\", \"kind\": \"field\","
                        + " \"type\": \"int\", \"parameters\": [], \"modifiers\": [\"public\"],"
                        + " \"annotations\": [], \"line\": -1, \"span\": null }]"),
                "the writer emits the member's name, kind, type, parameters, modifiers and annotations, exactly"
                        + " as the record holds them (the keyword filter is the extractor's, not the writer's) —"
                        + " and its position fields, unknown here because this row was built from facts rather"
                        + " than read from a source (DEC-040 D6, and D4: unknown is not absent): "
                        + written.substring(written.indexOf("WithMembers")));
        Assertions.assertTrue(written.contains("\"members\": []"),
                "and an empty array for a type that declares none, so a table written before this field stays"
                        + " distinguishable as 'not recorded'");

        ClassIndex read = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read);
        Assertions.assertEquals(1, read.row("a.b.WithMembers").members().size());
        Assertions.assertEquals(MemberRecord.Kind.FIELD, read.row("a.b.WithMembers").members().get(0).kind());
        Assertions.assertEquals("int", read.row("a.b.WithMembers").members().get(0).type());
        Assertions.assertTrue(read.row("a.b.Empty").members().isEmpty(), "a fact, not a gap");
    }

    @Test
    void aFactoryParameterCarriesItsNameAndItsAnnotations(@TempDir Path tree) throws IOException {
        String source = "package a.b;\n\n"
                + "public interface CtxMainModule {\n"
                + "    default ObjectMapper buildMapper(@Circular CtxMain ctx, String name) {\n"
                + "        return null;\n"
                + "    }\n"
                + "    String accessor(String onlyType);\n"
                + "}\n";
        writeSource(tree, "a/b/CtxMainModule.java", source);
        SourceReader.Read read = SourceReader.read(tree.resolve("module/a/b/CtxMainModule.java"));
        Assertions.assertTrue(read.readable(), "the fixture must parse");

        ClassIndex index = indexOf(tree);
        index.addTypes("a/b/CtxMainModule.java", read.unit(), source, false);
        List<MemberRecord> members = index.row("a.b.CtxMainModule").members();

        MemberRecord factory = members.get(0);
        Assertions.assertEquals(List.of("default"), factory.modifiers(),
                "`default` is recorded, and that is what separates a factory from an accessor: before step"
                        + " 3.0e both recorded an empty modifier list, so the index could not tell the two"
                        + " apart — the distinction hipster-ioc's model is made of");
        Assertions.assertEquals(List.of("CtxMain", "String"), factory.parameterTypes());
        Assertions.assertEquals(List.of("ctx", "name"),
                factory.parameters().stream().map(MemberParameter::name).toList(),
                "parameter names are recorded: they are what a generator writes into the code it emits");
        Assertions.assertTrue(factory.parameters().get(0).hasAnnotation("Circular"),
                "@Circular is on the parameter, and it is what decides how that bean is wired");
        Assertions.assertFalse(factory.parameters().get(1).hasAnnotation("Circular"),
                "and a parameter without it says so rather than carrying an empty annotation");

        Assertions.assertTrue(members.get(1).modifiers().isEmpty(),
                "an interface accessor written without modifiers records none — the other half of the"
                        + " discrimination");

        index.write();
        ClassIndex back = ClassIndex.read(index.indexFile(), tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(back);
        Assertions.assertTrue(back.row("a.b.CtxMainModule").members().get(0).parameters().get(0)
                        .hasAnnotation("Circular"),
                "and the parameter and its annotation survive the write and the read, which is the shape a"
                        + " consumer actually receives");
    }

    @Test
    void aTableWrittenBeforeMembersExistedReadsAsNotRecorded(@TempDir Path tree) throws IOException {
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/WithMembers.java", "package a.b;\n\npublic class WithMembers {\n    int id;\n}\n");
        index.addTypes("a/b/WithMembers.java", List.of(new TypeFacts("a.b.WithMembers", "class", List.of("public"),
                null, 3, 0, List.of(), List.of(), List.of(MemberRecord.field("id", "int", List.of())))), false);
        index.write();

        Path file = index.indexFile();
        // Drop the key through the JSON mapper rather than by matching the serialisation byte for byte: this
        // fixture broke twice by doing that, once per field the format grew, and a test that has to be edited
        // every time the writer changes is testing the writer's spelling rather than the reader's contract.
        tools.jackson.databind.JsonNode root = MetadataJson.mapper().readTree(Files.readString(file));
        ((tools.jackson.databind.node.ObjectNode) root.path("classes").path("a.b.WithMembers")).remove("members");
        Files.writeString(file, MetadataJson.mapper().writeValueAsString(root), StandardCharsets.UTF_8);

        ClassIndex read = ClassIndex.read(file, tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        Assertions.assertNotNull(read, "an older table is still readable");
        Assertions.assertTrue(read.row("a.b.WithMembers").members().isEmpty(),
                "and reads as empty because it was not recorded — the reason the writer always emits the field");
    }

    @Test
    void anUnknownMemberKindMakesTheReaderRefuseTheTable(@TempDir Path tree) throws IOException {
        ClassIndex index = indexOf(tree);
        writeSource(tree, "a/b/WithMembers.java", "package a.b;\n\npublic class WithMembers {\n    int id;\n}\n");
        index.addTypes("a/b/WithMembers.java", List.of(new TypeFacts("a.b.WithMembers", "class", List.of("public"),
                null, 3, 0, List.of(), List.of(), List.of(MemberRecord.field("id", "int", List.of())))), false);
        index.write();

        Path file = index.indexFile();
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"kind\": \"field\"", "\"kind\": \"property\""), StandardCharsets.UTF_8);

        List<String> problems = new java.util.ArrayList<>();
        Assertions.assertNull(ClassIndex.read(file, tree.resolve("report"), tree.resolve("module"),
                        tree.resolve("module/src/main/java"), problems),
                "a member kind this contract has no case for makes the reader refuse the table rather than treat"
                        + " the member as absent");
        Assertions.assertTrue(problems.stream().anyMatch(problem -> problem.contains("property")),
                "and says which kind it could not describe: " + problems);
    }
}
