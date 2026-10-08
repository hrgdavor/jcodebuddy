// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The rename recipe of plan step 7.5, asserted on samples — the gate's "one real tool … runs from the agent's menu
 * against a sample".
 *
 * <p>The interesting half of this test is not that a rename renames. It is that the tool <b>refuses</b> in the three
 * cases where a textual rename silently produces a file that compiles and means something else: a name that also
 * occurs inside a longer identifier, a name that also occurs in a string literal, and a rename onto a name the file
 * already uses. An agent applies these edits without anybody reading the diff, so refusals are the feature.
 */
class RenameMemberToolTest {

    @TempDir
    Path tempDir;

    /** A field, a constructor parameter, `this.name`, an accessor and a use from another method. */
    private static final String SAMPLE = """
            class Sample {
                private final String name;

                Sample(String name) {
                    this.name = name;
                }

                String name() {
                    return name;
                }

                String nameValue() {
                    return name + "-value";
                }
            }
            """;

    private Path sample(String text) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static ActionTool.ToolContext contextFor(Path file) {
        return new SimpleToolContext(file.getParent(), file, 1);
    }

    @Test
    void aRenameRewritesTheDeclarationAndEveryUse() throws Exception {
        Path file = sample(SAMPLE);
        RenameMemberTool tool = new RenameMemberTool("name", "title");
        Assertions.assertTrue(tool.isApplicable(contextFor(file)),
                "the tree carries the identifier, so the tool applies");

        List<ActionTool.FileChange> changes = tool.execute(contextFor(file));

        Assertions.assertEquals(1, changes.size());
        ActionTool.FileChange change = changes.get(0);
        Assertions.assertEquals(file, change.path());
        Assertions.assertEquals(ActionTool.ChangeType.CHANGE, change.type());
        String renamed = change.content();
        Assertions.assertTrue(renamed.contains("private final String title;"), renamed);
        Assertions.assertTrue(renamed.contains("String title() {"), renamed);
        Assertions.assertTrue(renamed.contains("this.title = title;"), renamed);
        Assertions.assertTrue(renamed.contains("return title;"), renamed);
        Assertions.assertTrue(renamed.contains("return title + \"-value\";"), renamed);
        // The longer identifier is the case a naive `String.replace` gets wrong, and the reason the splice uses
        // identifier boundaries rather than substring matching.
        Assertions.assertTrue(renamed.contains("String nameValue() {"),
                "a longer identifier containing the old name must be left alone: " + renamed);
        Assertions.assertFalse(renamed.contains("titleValue"), renamed);
    }

    @Test
    void aNameThatAlsoOccursInAStringLiteralIsRefusedRatherThanRenamed() throws Exception {
        // The tree counts identifiers; the text counts occurrences. Here the text has one more (the literal), and that
        // difference is exactly the case a text-only rename would silently change.
        Path file = sample("""
                class Sample {
                    private final String name;

                    String name() {
                        return name;
                    }

                    String label() {
                        return "name";
                    }
                }
                """);
        RenameMemberTool tool = new RenameMemberTool("name", "title");

        IllegalStateException refusal = Assertions.assertThrows(IllegalStateException.class,
                () -> tool.execute(contextFor(file)));

        Assertions.assertTrue(refusal.getMessage().contains("string literal"),
                "the refusal must name what the difference is: " + refusal.getMessage());
        Assertions.assertTrue(refusal.getMessage().contains("'name'"), refusal.getMessage());
    }

    @Test
    void aRenameOntoANameTheFileAlreadyUsesIsRefused() throws Exception {
        Path file = sample("""
                class Sample {
                    private final String name;
                    private final String title;

                    String name() {
                        return name;
                    }
                }
                """);
        RenameMemberTool tool = new RenameMemberTool("name", "title");

        IllegalStateException refusal = Assertions.assertThrows(IllegalStateException.class,
                () -> tool.execute(contextFor(file)));

        Assertions.assertTrue(refusal.getMessage().contains("already declares or uses"),
                "a collision is refused instead of emitting code the compiler would reject: " + refusal.getMessage());
    }

    @Test
    void anUnconfiguredOrUnparseableFileIsSimpyNotApplicable() throws Exception {
        Path file = sample(SAMPLE);

        Assertions.assertFalse(new RenameMemberTool().isApplicable(contextFor(file)),
                "a tool registered without a configured rename does nothing, which is its honest state");
        Assertions.assertFalse(new RenameMemberTool("name", "name").isApplicable(contextFor(file)),
                "a rename to the same name is not an action");

        Path broken = sample("class Sample { void name( }");
        Assertions.assertFalse(new RenameMemberTool("name", "title").isApplicable(contextFor(broken)),
                "a half-written file is normal while editing, so it is not applicable rather than a failure");

        Path absent = sample(SAMPLE);
        Assertions.assertFalse(new RenameMemberTool("missing", "title").isApplicable(contextFor(absent)),
                "an identifier the file does not use gives the tool nothing to do");
    }
}
