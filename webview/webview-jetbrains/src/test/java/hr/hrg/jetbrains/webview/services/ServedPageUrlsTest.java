package hr.hrg.jetbrains.webview.services;

import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The URL a served HTML page is opened at, tested as a pure rule.
 *
 * <p>Two hosts can serve the page, and the difference between them is exactly the path mapping: this
 * IDE's bridge adds a one-time token segment, an existing host gets the project-relative path appended to
 * its configured base. A page outside the project has no relative path under that jail and is refused
 * instead of being served from somewhere else.</p>
 */
public class ServedPageUrlsTest {

    @Test
    public void aPageIsNamedRelativeToTheProjectRoot() {
        Path root = Path.of("work", "proj");
        assertEquals("src/main/java/A.java",
                ServedPageUrls.projectRelative(root, root.resolve("src/main/java/A.java")));
        assertEquals("src/main/java/A.java",
                ServedPageUrls.projectRelative(root, root.resolve("./src/main/java/A.java")));
        assertNull("a page outside the project cannot be served from its jail",
                ServedPageUrls.projectRelative(root, Path.of("work", "other", "A.java")));
    }

    @Test
    public void theLocalUrlCarriesTheTokenAndTheProjectRelativePath() {
        assertEquals("http://127.0.0.1:18881/page/abc123/src/main/java/A.java",
                ServedPageUrls.onLocalServer(18881, "abc123", "src/main/java/A.java"));
    }

    @Test
    public void anExistingBaseIsThePathMappingAndATrailingSlashDoesNotDouble() {
        assertEquals("http://127.0.0.1:18884/page/src/main/java/A.java",
                ServedPageUrls.onExistingServer("http://127.0.0.1:18884/page/", "src/main/java/A.java"));
        assertEquals("http://127.0.0.1:18884/page/src/main/java/A.java",
                ServedPageUrls.onExistingServer("http://127.0.0.1:18884/page", "src/main/java/A.java"));
    }

    @Test
    public void aSpaceBecomesPercent20AndAPlusIsEncoded() {
        assertEquals("a%20page.html", ServedPageUrls.encodePath("a page.html"));
        assertEquals("a%2Bb.html", ServedPageUrls.encodePath("a+b.html"));
        assertEquals("dir/a%20page.html", ServedPageUrls.encodePath("dir/a page.html"));
        assertEquals("%C3%BCmlaut.html", ServedPageUrls.encodePath("ümlaut.html"));
    }
}
