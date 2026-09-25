package hr.hrg.webview.core;

import org.junit.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ported from {@code webview-jetbrains}' {@code UrlNormalizationTest}. The file-existence probe is a
 * parameter, so the whole decision table is exercised without touching a disk.
 */
public class UrlNormalizationTest {

    private static final String BASE = "D:/wrk/java/jcodebuddy";

    /** A file system where only the paths named here exist. */
    private static Predicate<String> only(String... existing) {
        Set<String> paths = Set.of(existing);
        return paths::contains;
    }

    @Test
    public void keepsExplicitSchemes() {
        UrlNormalizer.Normalized result = UrlNormalizer.normalize("https://example.com/page", BASE, only());

        assertEquals("https://example.com/page", result.url());
        assertNull(result.localPath());
        assertFalse(result.unresolved());

        assertEquals("http://localhost:3000/x",
                UrlNormalizer.normalize("http://localhost:3000/x", BASE, only()).url());
    }

    @Test
    public void assumesHttpsForBareHosts() {
        UrlNormalizer.Normalized result = UrlNormalizer.normalize("example.com/docs", BASE, only());

        assertEquals("https://example.com/docs", result.url());
        assertNull(result.localPath());
        assertFalse("a host is not an unresolved file", result.unresolved());

        // A host whose *last* segment has no dot is still a host: "example.com/docs" must not be read
        // as the file "docs" inside "example.com".
        assertEquals("https://localhost:3000/index",
                UrlNormalizer.normalize("localhost:3000/index", BASE, only()).url());
    }

    @Test
    public void treatsADottedLastSegmentAsAFile() {
        String resolved = BASE + "/.gitignore";
        UrlNormalizer.Normalized result = UrlNormalizer.normalize(".gitignore", BASE, only(resolved));

        assertEquals("file:///" + resolved, result.url());
        assertEquals(resolved, result.localPath());
    }

    @Test
    public void loadsAnExistingFile() {
        String path = "D:/tmp/report.html";
        UrlNormalizer.Normalized result = UrlNormalizer.normalize(path, BASE, only(path));

        assertEquals("file:///D:/tmp/report.html", result.url());
        assertEquals("D:/tmp/report.html", result.localPath());
        assertTrue(result.isLocal());
        assertFalse(result.unresolved());
    }

    @Test
    public void resolvesRelativePathsAgainstTheProject() {
        String resolved = BASE + "/hipster-entity-example/index.html";
        UrlNormalizer.Normalized result =
                UrlNormalizer.normalize("hipster-entity-example/index.html", BASE, only(resolved));

        assertEquals("file:///" + resolved, result.url());
        assertEquals(resolved, result.localPath());
    }

    @Test
    public void normalisesWindowsSeparators() {
        String expected = "D:/tmp/report.html";
        UrlNormalizer.Normalized result =
                UrlNormalizer.normalize("D:\\tmp\\report.html", BASE, only(expected));

        assertEquals("file:///" + expected, result.url());
        assertEquals(expected, result.localPath());
    }

    @Test
    public void flagsAMissingFileWithoutGuessingAScheme() {
        UrlNormalizer.Normalized result = UrlNormalizer.normalize("D:/tmp/missing.html", BASE, only());

        assertEquals("file:///D:/tmp/missing.html", result.url());
        assertTrue(result.unresolved());
        assertFalse("guessing https:// for a drive-absolute path would be worse than an unresolved file",
                result.url().startsWith("https://"));
    }

    @Test
    public void rejectsBlankInput() {
        assertNull(UrlNormalizer.normalize(null, BASE, only()));
        assertNull(UrlNormalizer.normalize("", BASE, only()));
        assertNull(UrlNormalizer.normalize("   ", BASE, only()));
    }

    @Test
    public void recoversLocalPathsFromFileUrls() {
        assertEquals("D:/tmp/a b.html", UrlNormalizer.localPathOf("file://D:/tmp/a%20b.html"));
        assertNull("only file: URLs have a local path", UrlNormalizer.localPathOf("https://example.com"));
        assertNull(UrlNormalizer.localPathOf(null));
    }
}
