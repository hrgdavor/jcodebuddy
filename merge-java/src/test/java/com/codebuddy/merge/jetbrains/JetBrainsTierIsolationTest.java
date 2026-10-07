// {@link com.codebuddy.merge.jetbrains.JetBrainsTierIsolationTest} Proves the ported algorithm tiers compile with no reference to this module's merge model.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge.jetbrains;

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
 * The tier rule, as an executable assertion (unified plan steps 4.7 and 4.8).
 *
 * <p>The port's whole testing story rests on one property: <b>the algorithm tiers know nothing about this
 * module's merge model.</b> Upstream's vectors are plain three-text cases, and they stay portable only
 * while {@code text} and {@code merge} compile and run with no {@code Conflict}, no
 * {@code ConflictResolution} and no {@code ConflictType} in sight. If a tier reaches into the model, the
 * vectors stop being runnable against it and every later step inherits the coupling.
 *
 * <p>The rule, exactly:
 *
 * <ul>
 *   <li>a class in {@code com.codebuddy.merge.jetbrains.text} or
 *       {@code com.codebuddy.merge.jetbrains.merge} may not import a type from {@code com.codebuddy.merge}
 *       outside its own package;</li>
 *   <li>nor may it use one by fully-qualified name in its body — an import is the usual route but not the
 *       only one;</li>
 *   <li>the adapter tier, and the {@code jetbrains} package itself, are exempt: bridging the model is
 *       their whole job.</li>
 * </ul>
 *
 * <h2>Why not an ArchUnit-style dependency rule</h2>
 *
 * <p>Because the check has to be readable by whoever breaks it. A failure here names the file, the line
 * and the type it reached for, so the fix is obvious; a generic cycle detector reports a graph.
 *
 * <h2>Why the package walk asserts it found files</h2>
 *
 * <p>A guard that silently stops guarding is the failure mode this repository keeps meeting, so a walk
 * that finds no tier sources fails rather than passing — the same reason
 * {@code JetBrainsAttributionTest} refuses an empty package.
 */
class JetBrainsTierIsolationTest {

    private static final String SOURCE_ROOT = "src/main/java/com/codebuddy/merge/jetbrains";

    /** The tiers that may not know the merge model. */
    private static final List<String> ISOLATED_TIERS = List.of("text", "merge");

    /** A reference to a type of this module outside the isolated tiers' own sub-packages. */
    private static final Pattern MODEL_REFERENCE =
        Pattern.compile("\\bcom\\.codebuddy\\.merge\\.(?!jetbrains\\.)([A-Z][A-Za-z0-9_]*)");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([A-Za-z0-9_.]+);");

    @Test
    @DisplayName("the text and merge tiers import nothing from this module's merge model")
    void tiersAreIsolatedFromTheModel() throws IOException {
        Path root = moduleRoot().resolve(SOURCE_ROOT);
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String tier : ISOLATED_TIERS) {
            Path tierDir = root.resolve(tier);
            List<Path> sources = javaSources(tierDir);
            checked += sources.size();
            for (Path source : sources) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                String where = tier + "/" + source.getFileName();

                for (String line : text.split("\n")) {
                    var importMatch = IMPORT.matcher(line);
                    if (importMatch.find()) {
                        String imported = importMatch.group(2);
                        // What is forbidden is this module's MERGE MODEL — the types directly in
                        // `com.codebuddy.merge` that a `ConflictResolution` is made of. The algorithm
                        // tiers may freely use each other: `merge` is built on `text` by design, and a
                        // rule that forbade that would forbid the tier split itself.
                        if (imported.startsWith("com.codebuddy.merge.")
                            && !imported.startsWith("com.codebuddy.merge.jetbrains")) {
                            problems.add(where + " imports " + imported
                                + ", which is this module's merge model");
                        }
                    }
                }

                var reference = MODEL_REFERENCE.matcher(text);
                while (reference.find()) {
                    // The javadoc deliberately names the model to say what is *not* used, and a
                    // {@code} span is documentation rather than a dependency. Only a reference in code
                    // counts, so occurrences inside a comment are not reported.
                    if (!insideComment(text, reference.start())) {
                        problems.add(where + " refers to " + reference.group()
                            + ", a type of this module's merge model");
                    }
                }
            }
        }

        if (checked == 0) {
            fail("no sources found under " + root + ", so the tier rule asserts nothing. A walk that"
                + " finds no file has lost the tiers rather than found them isolated.");
        }
        assertEquals(List.of(), problems, "the algorithm tiers must not depend on the merge model");
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
                    // A line comment runs to the end of the line; if the offset is on that line, it is
                    // inside the comment.
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
                // Skip a string literal so a slash inside it is not read as a comment.
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
            fail("expected to run in the merge-java module root, but " + root
                + " has no " + SOURCE_ROOT);
        }
        return root;
    }
}
