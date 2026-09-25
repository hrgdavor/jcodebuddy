package hr.hrg.webview.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The path jail, against a real temporary directory: the interesting cases (a sibling directory whose
 * name starts with the project's, a symlink out of the project, a target that does not exist yet) cannot
 * be faked with a string, because the whole point is what the file system actually resolves to.
 */
public class PathResolverTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void resolvesARelativePathAgainstTheProject() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve("src/main/java/A.java");

        assertTrue(resolution.confined());
        assertEquals(project.toRealPath().toString().replace('\\', '/') + "/src/main/java/A.java",
                resolution.absolute());
    }

    @Test
    public void acceptsAnAbsolutePathInsideTheProject() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.createDirectories(project.resolve("src"));
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve(project.resolve("src/A.java").toString());

        assertTrue(resolution.confined());
    }

    /**
     * The case a string-prefix test gets wrong: {@code proj-evil} starts with {@code proj}.
     */
    @Test
    public void refusesASiblingDirectoryWhoseNameStartsWithTheProjectName() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Path sibling = folder.newFolder("proj-evil").toPath();
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve(sibling.resolve("secret.txt").toString());

        assertTrue("a segment-wise test rejects what a string prefix would accept", resolution.escaped());
    }

    @Test
    public void refusesADotDotEscape() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve("../outside.txt");

        assertTrue(resolution.escaped());
    }

    @Test
    public void refusesAnAbsolutePathOutsideTheProject() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        PathResolver resolver = PathResolver.forProject(project.toString());

        // A path that certainly exists and is certainly not in the project.
        assertTrue(resolver.resolve(System.getProperty("java.io.tmpdir")).escaped());
    }

    /**
     * A symlink inside the project pointing out of it is the same escape as {@code ..}, and the only
     * honest way to see it is to resolve it.
     */
    @Test
    public void refusesAPathThatEscapesThroughASymlink() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Path outside = folder.newFolder("outside").toPath();
        Files.createFile(outside.resolve("secret.txt"));
        Path link = project.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating a symlink needs a privilege on Windows. Skipping is honest; pretending the case
            // passed would be the opposite.
            return;
        }
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve("link/secret.txt");

        assertTrue("a link out of the project is an escape", resolution.escaped());
    }

    /**
     * A write target usually does not exist yet. The link on its <em>parent</em> is what matters, so
     * resolution must not give up when the file itself is absent.
     */
    @Test
    public void resolvesANonExistentTargetThroughItsParent() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.createDirectories(project.resolve("src"));
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve("src/NotYetWritten.java");

        assertTrue(resolution.confined());
        assertTrue(resolution.absolute().endsWith("/src/NotYetWritten.java"));
    }

    @Test
    public void acceptsWindowsBackslashSpelling() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.createDirectories(project.resolve("src"));
        PathResolver resolver = PathResolver.forProject(project.toString());

        PathResolution resolution = resolver.resolve("src\\main\\A.java");

        assertTrue(resolution.confined());
        assertFalse("the result is URL-ready, so it carries forward slashes only",
                resolution.absolute().contains("\\"));
    }

    @Test
    public void rejectsTextThatIsNotAPath() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        PathResolver resolver = PathResolver.forProject(project.toString());

        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve(""));
        assertNull(resolver.resolve("   "));
        assertNull("a NUL byte cannot name a file on any platform", resolver.resolve("a\u0000b"));
    }

    /**
     * With no project there is nothing to escape from, so every path is confined. This is the headless
     * host serving a single file, and it must not accidentally refuse everything.
     */
    @Test
    public void withoutAProjectThereIsNoJail() {
        PathResolver resolver = PathResolver.forProject(null);

        assertFalse(resolver.hasRoot());
        assertTrue(resolver.resolve("/anywhere/at/all.html").confined());
        assertTrue(PathResolver.forProject("  ").resolve("relative.html").confined());
    }

    @Test
    public void keepsTheRootItWasGiven() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        PathResolver resolver = PathResolver.forProject(project.toString());

        assertTrue(resolver.hasRoot());
        assertEquals(project.toAbsolutePath().normalize(), resolver.root());
    }
}
