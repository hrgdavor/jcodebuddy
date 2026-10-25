package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which files a run covers, and in what order.
 *
 * <p>Three properties, all of which the plan's sketch got wrong and all of which are invisible on a small
 * example: the order is stable, build output and metadata are skipped, and patterns are git's
 * {@code wildmatch} (DEC-051). The pattern tests keep the rules that decide what matches visible: a
 * pattern with no separator is a basename at any depth, a leading {@code /} anchors it to the root, a
 * {@code *} does not cross a {@code /}, and {@code **&#47;} covers zero or more directories.</p>
 */
class SourceFilesTest {

    @TempDir
    Path root;

    @Test
    void findsEveryJavaFileSortedByPath() throws IOException {
        write("z/Last.java", "class Z {}\n");
        write("a/First.java", "class A {}\n");
        write("Middle.java", "class M {}\n");
        write("notes.txt", "not java\n");

        List<Path> files = SourceFiles.findJavaFiles(root);

        assertEquals(List.of("Middle.java", "a/First.java", "z/Last.java"), relativeNames(files));
        // Sorted by path string, so an upper-case name sorts before a lower-case directory: the point is
        // that it is a total order that does not change between runs, not which order it is.
        assertEquals(files, SourceFiles.findJavaFiles(root));
    }

    @Test
    void skipsBuildOutputMetadataAndGit() throws IOException {
        write("src/Kept.java", "class Kept {}\n");
        write("target/Generated.java", "class Generated {}\n");
        write(".jcodebuddy/metadata/Cached.java", "class Cached {}\n");
        write(".git/hooks/Hook.java", "class Hook {}\n");

        assertEquals(List.of("src/Kept.java"), relativeNames(SourceFiles.findJavaFiles(root)));
    }

    @Test
    void aFileIsNotASourceRoot() throws IOException {
        Path file = write("Sample.java", "class Sample {}\n");

        // Returning an empty list would hide the mistake behind "nothing to do".
        assertThrows(IllegalArgumentException.class, () -> SourceFiles.findJavaFiles(file));
        assertThrows(IllegalArgumentException.class, () -> SourceFiles.findJavaFiles(null));
        assertThrows(IllegalArgumentException.class, () -> SourceFiles.findJavaFiles(root.resolve("missing")));
    }

    @Test
    void aStarStopsAtASeparatorButAnUnanchoredPatternTriesEveryDepth() throws IOException {
        write("Root.java", "class Root {}\n");
        write("a/Nested.java", "class Nested {}\n");

        // A pattern with no separator is a basename at any depth in wildmatch; the leading slash is
        // what restricts it to the root. `a/*.java` keeps the star inside one directory.
        assertEquals(List.of("Root.java", "a/Nested.java"),
                relativeNames(SourceFiles.findJavaFilesMatching(root, "*.java")));
        assertEquals(List.of("Root.java"),
                relativeNames(SourceFiles.findJavaFilesMatching(root, "/*.java")));
        assertEquals(List.of("a/Nested.java"),
                relativeNames(SourceFiles.findJavaFilesMatching(root, "a/*.java")));
    }

    @Test
    void aLeadingDoubleStarAlsoCoversTheRoot() throws IOException {
        write("RootController.java", "class RootController {}\n");
        write("a/NestedController.java", "class NestedController {}\n");
        write("a/Service.java", "class Service {}\n");

        // `**/` is zero or more directories, so it covers the root too. A bare `*Controller.java` is
        // also both, because a pattern with no separator is tried at every depth.
        assertEquals(List.of("RootController.java", "a/NestedController.java"),
                relativeNames(SourceFiles.findJavaFilesMatching(root, "*Controller.java")));
        assertEquals(List.of("RootController.java"),
                relativeNames(SourceFiles.findJavaFilesMatching(root, "/*Controller.java")));
        assertEquals(List.of("RootController.java", "a/NestedController.java"),
                relativeNames(SourceFiles.findJavaFilesMatching(root, "**/*Controller.java")));
    }

    @Test
    void refusesAnEmptyPattern() {
        assertThrows(IllegalArgumentException.class, () -> SourceFiles.findJavaFilesMatching(root, null));
        assertThrows(IllegalArgumentException.class, () -> SourceFiles.findJavaFilesMatching(root, ""));
    }

    @Test
    void recognisesJavaFilesByName() {
        assertTrue(SourceFiles.isJavaFile(root.resolve("A.java")));
        assertFalse(SourceFiles.isJavaFile(root.resolve("A.JAVA")));
        assertFalse(SourceFiles.isJavaFile(root.resolve("A.java.txt")));
        assertFalse(SourceFiles.isJavaFile(null));
    }

    private List<String> relativeNames(List<Path> files) {
        return files.stream().map(file -> root.relativize(file).toString().replace('\\', '/')).toList();
    }

    private Path write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
