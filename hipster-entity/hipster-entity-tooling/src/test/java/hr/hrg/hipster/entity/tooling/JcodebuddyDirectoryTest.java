package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes a {@code .jcodebuddy/} a marker, and what does not.
 *
 * <p>The case that made this a rule rather than an assumption: a page host publishes the port it bound in
 * {@code <project>/.jcodebuddy/webview/} (DEC-032, DEC-033), and a host is pointed at whatever directory the
 * user was working in. Before this, a browser pointed at an unconverted repository would have created a
 * marker, and the entity pass would have decided that a module's sources belong one level up — writing its
 * report into the served project's tree instead of the module's, in a directory that module's
 * {@code .gitignore} does not cover.
 */
class JcodebuddyDirectoryTest {

    private static Path marker(Path parent) throws IOException {
        Path directory = parent.resolve(JcodebuddyDirectory.DIR);
        Files.createDirectories(directory);
        return directory;
    }

    private static void dir(Path parent, String name) throws IOException {
        Files.createDirectories(parent.resolve(name));
    }

    @Test
    void contentMakesAMarker(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        dir(directory, "context");
        dir(directory, "metadata");
        Files.writeString(directory.resolve(".gitignore"), "metadata/**\n", StandardCharsets.UTF_8);

        assertTrue(JcodebuddyDirectory.isMarker(directory),
                "context/, metadata/ and a policy file are what a converted module looks like");
    }

    @Test
    void anEmptyMarkerIsStillAMarker(@TempDir Path root) throws IOException {
        // Creating the directory by hand is an explicit act: a module may carry its marker before its first
        // pass, and refusing to honour it would put the first pass's output in the wrong place.
        assertTrue(JcodebuddyDirectory.isMarker(marker(root)));
    }

    @Test
    void policyFilesAloneAreStillAMarker(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        Files.writeString(directory.resolve("README.md"), "this module uses JCodeBuddy\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".gitignore"), "metadata/**\n", StandardCharsets.UTF_8);

        assertTrue(JcodebuddyDirectory.isMarker(directory),
                "a hand-written marker with its policy files is a marker");
    }

    @Test
    void aHostsPublishedStateIsNotAMarker(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        dir(directory, "webview");
        Files.writeString(directory.resolve("webview").resolve("host.json"), "{}\n", StandardCharsets.UTF_8);

        assertFalse(JcodebuddyDirectory.isMarker(directory),
                "a port published by a page host does not convert a project");
    }

    @Test
    void mergeHistoryAloneIsNotAMarker(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        dir(directory, "merge-history");

        assertFalse(JcodebuddyDirectory.isMarker(directory),
                "the merge tool's state predates the hosts and has the same problem");
    }

    @Test
    void toolStateBesideRealContentIsAMarker(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        dir(directory, "webview");
        dir(directory, "context");

        assertTrue(JcodebuddyDirectory.isMarker(directory),
                "a converted module that a host also served is still a converted module");
    }

    @Test
    void anUnknownFileIsContent(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        dir(directory, "webview");
        Files.writeString(directory.resolve("notes.md"), "why this module\n", StandardCharsets.UTF_8);

        assertTrue(JcodebuddyDirectory.isMarker(directory),
                "a person put something there, which is a decision the classifier does not get to overrule");
    }

    @Test
    void anAbsentOrNonDirectoryIsNotAMarker(@TempDir Path root) throws IOException {
        assertFalse(JcodebuddyDirectory.isMarker(root.resolve("nowhere")));
        assertFalse(JcodebuddyDirectory.isMarker(null));

        Path file = root.resolve(JcodebuddyDirectory.DIR);
        Files.writeString(file, "not a directory\n", StandardCharsets.UTF_8);
        assertFalse(JcodebuddyDirectory.isMarker(file));
    }

    @Test
    void theWalkSkipsAHostOnlyDirectoryAndFindsTheRealMarkerAbove(@TempDir Path root) throws IOException {
        // root/.jcodebuddy/ holds a converted module; root/project is the directory a host was pointed at, so
        // root/project/.jcodebuddy/ exists and holds only the published port.
        Path realMarker = marker(root);
        dir(realMarker, "metadata");

        Path served = root.resolve("project");
        Path nested = served.resolve("module").resolve("src").resolve("main").resolve("java");
        Files.createDirectories(nested);
        Path hostMarker = served.resolve(JcodebuddyDirectory.DIR);
        dir(hostMarker, "webview");

        assertEquals(realMarker, JcodebuddyDirectory.nearestMarker(nested),
                "the module below the served project belongs to the real marker, not to the host's directory");
    }

    @Test
    void theWalkFindsAMarkerAtOrBelowTheStart(@TempDir Path root) throws IOException {
        Path directory = marker(root);
        dir(directory, "context");

        assertEquals(directory, JcodebuddyDirectory.nearestMarker(directory),
                "a caller that already holds the marker directory passes it directly");
        assertEquals(directory, JcodebuddyDirectory.nearestMarker(root),
                "and a caller that holds its parent finds it in one step");
    }

    @Test
    void aTreeWithOnlyHostStateHasNoMarkerAtAll(@TempDir Path root) throws IOException {
        Path served = root.resolve("served");
        dir(served.resolve(JcodebuddyDirectory.DIR), "webview");

        assertNull(JcodebuddyDirectory.nearestMarker(served),
                "no marker above it either, so resolution must fall through to pom.xml or to temp");
        assertNull(JcodebuddyDirectory.nearestMarker(null));
    }
}
