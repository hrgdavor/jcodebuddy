// {@link com.codebuddy.merge.jetbrains.merge.MergeTierScopeTest} Proves the SUGGESTION-class passes are absent from this tier, not merely unused.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge.jetbrains.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The merge tier holds the <b>SAFE</b> half only, and this is the assertion that keeps it that way
 * (unified plan step 4.9).
 *
 * <h2>Why an absence needs asserting</h2>
 *
 * <p>The gate asks for a test that the greedy pass and the {@code IGNORE_WHITESPACES} retry are
 * <b>not reachable</b> from this tier. The absence is true today — the code was never written — but
 * "nothing in the file" is not a property a reader can check and not one a future commit cannot quietly
 * change. The port's whole discipline is that upstream's steps were <em>classified</em> before any of them
 * was ported: {@code JETBRAINS_PORT.md} § 5.1 marks the greedy pass (R3), its unconditional deletion
 * application (R4) and the whitespace retry (R2) as <b>SUGGESTION</b>-class, because their result is
 * useful but not mechanically forced.
 *
 * <p>A refusal nobody asserts is a refusal waiting to be quietly upgraded. This test is that assertion, and
 * it is a <b>source</b> check rather than a behavioural one on purpose: a behavioural check can only ask
 * about the entry points it knows, while this asks whether the concept is present at all.
 *
 * <h2>What each forbidden name means</h2>
 *
 * <ul>
 *   <li>{@code greedy} — upstream's {@code tryGreedyResolve}/{@code GreedyHelper}, which applies deletions
 *       without asking and whose own comment says its results are "explicitly verified by user and can be
 *       safely undone". We may not assume that.</li>
 *   <li>{@code DiffConfig} — upstream's mutable global switch that chooses between the passes. A library
 *       must not have process-global behaviour, which {@code JETBRAINS_PORT.md} § 8 records.</li>
 *   <li>a retry under {@code IGNORE_WHITESPACES} — R2. The policy itself is legitimate and used for
 *       comparison; what is forbidden is running the resolve a <em>second time</em> under it and keeping
 *       the answer, which is what turns a formatting difference into resolved text nobody agreed to.</li>
 * </ul>
 */
class MergeTierScopeTest {

    private static final String SOURCE_ROOT = "src/main/java/com/codebuddy/merge/jetbrains/merge";

    /** The names that must not appear in this tier at all. */
    private static final List<Pattern> FORBIDDEN = List.of(
        Pattern.compile("\\bgreedy\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bGreedyHelper\\b"),
        Pattern.compile("\\bDiffConfig\\b"),
        Pattern.compile("\\btryGreedyResolve\\b"));

    @Test
    @DisplayName("the greedy pass, the global config switch and the whitespace retry are absent from this tier")
    void suggestionClassPassesAreAbsent() throws IOException {
        Path root = moduleRoot().resolve(SOURCE_ROOT);
        List<Path> sources = javaSources(root);
        if (sources.isEmpty()) {
            // A walk that finds nothing is how a guard stops guarding, so it fails rather than passes.
            fail("no sources under " + root + ", so this scope assertion checked nothing");
        }

        List<String> problems = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            String where = source.getFileName().toString();
            for (Pattern forbidden : FORBIDDEN) {
                var matcher = forbidden.matcher(text);
                while (matcher.find()) {
                    // A mention inside a comment is documentation, and this tier's own javadoc names the
                    // greedy pass to say it is NOT here. Only a use in code counts.
                    if (!insideComment(text, matcher.start())) {
                        problems.add(where + " uses '" + matcher.group() + "', which is "
                            + "SUGGESTION-class (JETBRAINS_PORT.md section 5.1) and belongs on the "
                            + "suggestion channel, not in this tier");
                    }
                }
            }

            // The retry is the one that needs a shape rather than a name: the policy enum is used for
            // comparison legitimately, so what is refused is a SECOND resolve attempt under it.
            int resolveCalls = countOccurrences(text, "MergeResolve.resolve(");
            int ignorePolicyMentions = countOccurrences(text, "IGNORE_WHITESPACES");
            if (resolveCalls > 1 && ignorePolicyMentions > 0) {
                problems.add(where + " calls the resolve " + resolveCalls + " times and mentions "
                    + "IGNORE_WHITESPACES, which is the shape of the retry (R2): the policy may be used "
                    + "to COMPARE, not to retry a resolve and keep the answer");
            }
        }

        assertEquals(List.of(), problems,
            "the merge tier holds the SAFE half only; these are SUGGESTION-class and belong to 4.14-4.15");
    }

    @Test
    @DisplayName("the check can fail: a forbidden name in code is caught, and in a comment is not")
    void theCheckCanFail() {
        Path sample = Path.of("Sample.java");
        String inCode = "class Sample { String s = \"greedy\"; }";
        String inComment = "// the greedy pass is deliberately not here\nclass Sample { }";

        assertTrueFound(inCode, "a forbidden name in code must be caught");
        assertFalseFound(inComment, "a forbidden name in a comment is documentation, not a use");
        assertFalseFound("class Sample { }", "a clean file must pass");

        // And the retry shape: two resolve calls plus the policy is the retry; one alone is not.
        assertTrueFound("a = MergeResolve.resolve(x, y, z); b = MergeResolve.resolve(p, q, r, IGNORE_WHITESPACES);",
            "two resolves with the policy is the retry shape");
        assertFalseFound("a = MergeResolve.resolve(x, y, z);", "one resolve is not the retry");
    }

    // ------------------------------------------------------------------ the check, callable on a string

    private static List<String> problemsIn(String text) {
        List<String> problems = new ArrayList<>();
        for (Pattern forbidden : FORBIDDEN) {
            var matcher = forbidden.matcher(text);
            while (matcher.find()) {
                if (!insideComment(text, matcher.start())) {
                    problems.add("uses '" + matcher.group() + "'");
                }
            }
        }
        if (countOccurrences(text, "MergeResolve.resolve(") > 1
            && countOccurrences(text, "IGNORE_WHITESPACES") > 0) {
            problems.add("looks like the retry");
        }
        return problems;
    }

    private static void assertTrueFound(String text, String what) {
        if (problemsIn(text).isEmpty()) {
            fail(what);
        }
    }

    private static void assertFalseFound(String text, String what) {
        List<String> problems = problemsIn(text);
        if (!problems.isEmpty()) {
            fail(what + ", but the check reported " + problems);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** True when the character at {@code offset} lies inside a line or block comment. */
    private static boolean insideComment(String text, int offset) {
        boolean inBlock = false;
        for (int i = 0; i < offset; i++) {
            char c = text.charAt(i);
            if (inBlock) {
                if (c == '*' && i + 1 < offset && text.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < offset) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    int newline = text.indexOf('\n', i);
                    if (newline < 0 || offset < newline) {
                        return true;
                    }
                    i = newline < 0 ? text.length() : newline;
                } else if (next == '*') {
                    inBlock = true;
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < offset && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
            }
        }
        return inBlock;
    }

    private static List<Path> javaSources(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    private static Path moduleRoot() {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve(SOURCE_ROOT))) {
            fail("expected to run in the merge-java module root, but " + root + " has no " + SOURCE_ROOT);
        }
        return root;
    }
}
