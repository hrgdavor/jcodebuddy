package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The splice that adds builder entry points to a developer's own interface.
 *
 * <h3>Why this test exists</h3>
 * <p>{@code ViewInterfaceGenerator} is reached only from {@code EntityMetadataGenerator}, so before
 * Phase 6 it had no direct coverage — and its most important property is not "a method appears" but
 * "nothing <em>else</em> changed". The JavaParser implementation could not guarantee that: it fell back
 * to whole-file printing when the lexical printer refused an added {@code default} modifier, which is
 * this generator's normal case. The port replaced that with a text splice, and these tests pin the
 * property the splice is for.</p>
 */
class ViewInterfaceGeneratorTest {

    /** A hand-written interface keeps every byte of its own formatting. */
    @Test
    void addingAnEntryPointLeavesTheRestOfTheFileByteIdentical() throws Exception {
        Path dir = Files.createTempDirectory("view-interface");
        // The generator resolves the package directory under the source root, so the fixture must live
        // at `<root>/p/PersonSummary.java` — a file at the root is a different (and correctly
        // un-found) case.
        Path packageDir = Files.createDirectories(dir.resolve("p"));
        String original = """
                package p;

                /** A hand-written view. */
                public interface PersonSummary {
                    String firstName();

                    // A comment the generator must not disturb.
                    String lastName();
                }
                """;
        Files.writeString(packageDir.resolve("PersonSummary.java"), original);

        ViewInterfaceGenerator.Result result = ViewInterfaceGenerator.generate(
                dir, "p", "PersonSummary",
                List.of(new ViewInterfaceGenerator.EntryPoint("toBuilder", "PersonSummaryBuilder")),
                null);

        Assertions.assertEquals(List.of("toBuilder"), result.added());
        String text = Files.readString(packageDir.resolve("PersonSummary.java"));

        // The generated member exists, with the shape the class documents.
        Assertions.assertTrue(text.contains(
                        "public default PersonSummaryBuilder toBuilder() { return new PersonSummaryBuilder(this); }"),
                "the entry point must be emitted as a default method returning a new builder: " + text);

        // Everything the developer wrote survives, including the comment and the blank line.
        for (String fragment : List.of("/** A hand-written view. */", "String firstName();",
                "// A comment the generator must not disturb.", "String lastName();", "package p;")) {
            Assertions.assertTrue(text.contains(fragment),
                    "the splice must not disturb existing content, missing: " + fragment + "\n" + text);
        }

        // And the member is indented to sit with its neighbours rather than at column zero.
        Assertions.assertTrue(text.contains("\n    public default PersonSummaryBuilder toBuilder()"),
                "the generated member must be indented with the interface's own members: " + text);
    }

    /** Running it twice adds nothing the second time — DEC-020's "leave a present member alone". */
    @Test
    void anAlreadyPresentEntryPointIsNotAddedAgain() throws Exception {
        Path dir = Files.createTempDirectory("view-interface-idempotent");
        Files.createDirectories(dir.resolve("p"));
        Files.writeString(dir.resolve("p").resolve("V.java"),
                "package p;\n\npublic interface V {\n    default VBuilder toBuilder() { return new VBuilder(this); }\n}\n");

        ViewInterfaceGenerator.Result result = ViewInterfaceGenerator.generate(
                dir, "p", "V",
                List.of(new ViewInterfaceGenerator.EntryPoint("toBuilder", "VBuilder")),
                null);

        Assertions.assertTrue(result.alreadyPresent(), "a present entry point must be recognised");
        Assertions.assertEquals(List.of(), result.added());
        Assertions.assertEquals(1,
                Files.readString(dir.resolve("p").resolve("V.java")).split("toBuilder\\(\\)", -1).length - 1,
                "the method must not be duplicated");
    }

    /**
     * The generated member goes inside the interface, not after it.
     *
     * <p>Brace depth is what decides the insertion point, so a following declaration is the case that
     * would break if the splice looked for "the last brace in the file" instead.</p>
     */
    @Test
    void theMemberIsInsertedInsideTheInterfaceNotAfterATrailingType() throws Exception {
        Path dir = Files.createTempDirectory("view-interface-trailing");
        Files.createDirectories(dir.resolve("p"));
        Files.writeString(dir.resolve("p").resolve("V.java"),
                "package p;\n\npublic interface V {\n    String name();\n}\n\nclass Helper {\n    void x() {\n    }\n}\n");

        ViewInterfaceGenerator.generate(dir, "p", "V",
                List.of(new ViewInterfaceGenerator.EntryPoint("toBuilder", "VBuilder")), null);

        String text = Files.readString(dir.resolve("p").resolve("V.java"));
        int builderAt = text.indexOf("toBuilder()");
        int vClose = text.indexOf('}', text.indexOf("String name();"));
        Assertions.assertTrue(builderAt > 0 && builderAt < vClose,
                "the entry point belongs inside the interface, before its closing brace:\n" + text);
        Assertions.assertTrue(text.contains("class Helper"), "the trailing type must survive");
    }
}
