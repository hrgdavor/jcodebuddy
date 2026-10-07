// {@link com.codebuddy.merge.HierarchicalAcceptanceTest} The instruction's two examples, end to end (plan step 4.19).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.MergeFileTool.Outcome;
import com.codebuddy.merge.MergeFileTool.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maintainer's two examples, driven through the whole tool with the module's own resolvers — not through
 * stubs (unified plan step 4.19).
 *
 * <h2>Why this file exists separately from {@code TieredResolutionTest}</h2>
 *
 * <p>{@code TieredResolutionTest} asserts the <em>mechanism</em>: with a claim source it controls, a settled
 * conflict is never offered to the tier below. That is where "never asked" is checkable, because an
 * assertion on the outcome cannot tell it from "asked and overruled". This file asks the other question, and
 * it is the one the instruction actually makes:
 *
 * <blockquote>"structural that knows two branches added one or more whole methods in same location should
 * clear any conflicts they cover and do not need to be analyzed by text resolver, same goes for imports
 * resolver, if it has success, then there is no need for lower tier to touch that part of the file"</blockquote>
 *
 * <p>So it runs the real {@code MemberAddConflictResolver} and the real {@code ImportConflictResolver} over
 * real conflict files, and asserts that the text tier was <b>cleared</b> — named in the block's report as
 * never asked rather than outranked.
 */
class HierarchicalAcceptanceTest {

    @TempDir
    Path tempDir;

    /**
     * Two branches inserting a distinct whole method at the same place, as a {@code diff3} hunk.
     *
     * <p>The base section is <b>present and empty</b>, which is what makes this the instruction's example
     * rather than a guess: the base had no lines there, so both sides inserted, and
     * {@code detectMemberAddConflicts} accepts it. A file with no base section at all would be an unknown
     * base and the detector declines it by design.
     */
    private static final String TWO_METHODS_ADDED = """
            package com.example.demo;

            public class OrderService {

                public void audit() {
                }
            <<<<<<< ours
                public void charge() {
                }
            ||||||| base
            =======
                public void refund() {
                }
            >>>>>>> theirs
            }
            """;

    @Test
    @DisplayName("two methods added in the same place are kept, and the text tier is cleared")
    void twoAddedMethodsClearTheTextTier() throws IOException {
        Path file = write("OrderService.java", TWO_METHODS_ADDED);

        Result result = toolFor(file).applyFixes(true).run();

        assertEquals(Outcome.APPLIED_AUTO, result.outcomes().get(0).outcome(),
            result.outcomes().get(0).explanation());
        String applied = read(file);
        assertTrue(applied.contains("void charge()"), applied);
        assertTrue(applied.contains("void refund()"), applied);
        assertTrue(result.outcomes().get(0).explanation().contains("never asked"),
            "the text tier must be cleared rather than overruled: "
                + result.outcomes().get(0).explanation());
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private MergeFileTool.Builder toolFor(Path file) {
        return MergeFileTool.forFile(file)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
