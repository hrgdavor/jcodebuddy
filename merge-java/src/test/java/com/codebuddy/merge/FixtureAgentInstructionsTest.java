// {@link com.codebuddy.merge.FixtureAgentInstructionsTest} Tests that the bundled fixture instructions load and carry their key rules.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instructions are the contract every fixture agent works under, and the
 * resource is bundled in the jar - a packaging mistake that drops it must fail
 * loudly, and the rules that keep proprietary code out of the repository must
 * survive edits to the text.
 */
class FixtureAgentInstructionsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the bundled resource loads and is not blank")
    void contentLoads() {
        String content = FixtureAgentInstructions.content();
        assertFalse(content.isBlank());
        assertEquals(content, FixtureAgentInstructions.content(), "the text is cached verbatim");
    }

    @Test
    @DisplayName("the confidentiality and loop rules an agent must not miss are present")
    void contentCarriesTheKeyRules() {
        String content = FixtureAgentInstructions.content();
        String lower = content.toLowerCase();

        assertTrue(lower.contains("anonymize"), "the anonymization step is the core rule");
        assertTrue(lower.contains("proprietary"), "the original is called proprietary");
        assertTrue(lower.contains("never `git add`"), "the workspace must never be committed");
        assertTrue(content.contains(".gitignore"), "the guard file is explained");
        assertTrue(content.contains("ADDING_A_RESOLVER.md"),
            "resolver building points at the module's guide");
        assertTrue(content.contains("MergeFileTool.reverify"),
            "re-verification names the entry point to use");
        assertTrue(lower.contains("re-verify"), "the loop closes on the original case");
        assertTrue(content.contains("VERDICT.md"),
            "shapes no resolver should force have an honest exit");
    }

    @Test
    @DisplayName("writeTo creates the directory and writes the file verbatim")
    void writeToWritesTheFile() throws IOException {
        Path target = FixtureAgentInstructions.writeTo(tempDir.resolve("nested/workspace"));

        assertEquals("AGENTS.md", target.getFileName().toString());
        assertEquals(FixtureAgentInstructions.content(),
            Files.readString(target, StandardCharsets.UTF_8));
    }
}
