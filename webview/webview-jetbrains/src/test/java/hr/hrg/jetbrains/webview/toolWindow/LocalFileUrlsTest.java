package hr.hrg.jetbrains.webview.toolWindow;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The remembered-URL rule, tested directly because it is pure.
 *
 * <p>It exists because of a real report: the plugin restored
 * {@code file:///D:/wrk/java/jcodebuddy/webview/examples/with-assets/pages/entity-reference.html} on every open, and
 * the file had moved to {@code webview/kit/examples/}. The tool window showed an error page for a path that no longer
 * existed, and looked broken rather than out of date. The first case below is that exact URL.
 */
public class LocalFileUrlsTest {

    @Test
    public void theUrlFromTheBugReportIsRecognisedAsGone() {
        assertTrue(LocalFileUrls.missing(
            "file:///D:/wrk/java/jcodebuddy/webview/examples/with-assets/pages/entity-reference.html"));
    }

    @Test
    public void aFileUrlThatExistsIsNotMissing() throws IOException {
        Path file = Files.createTempFile("webview", ".html");
        try {
            String url = "file:///" + file.toAbsolutePath().toString().replace('\\', '/').replaceFirst("^/+", "");
            assertFalse("a file that is there must be loadable: " + url, LocalFileUrls.missing(url));
            assertEquals(file.toAbsolutePath(), LocalFileUrls.pathOf(url).toAbsolutePath());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void aPercentEncodedPathIsDecoded() throws IOException {
        Path directory = Files.createTempDirectory("webview urls");
        Path file = Files.writeString(directory.resolve("a page.html"), "<html></html>");
        try {
            String url = "file:///" + file.toAbsolutePath().toString().replace('\\', '/').replace(" ", "%20")
                .replaceFirst("^/+", "");
            assertFalse("a space in a path is %20 in a URL: " + url, LocalFileUrls.missing(url));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void anythingThatIsNotALocalFileIsLeftAlone() {
        // Nothing to check, and forgetting a page we could have shown is worse than letting the browser try.
        assertFalse(LocalFileUrls.missing(null));
        assertFalse(LocalFileUrls.missing(""));
        assertFalse(LocalFileUrls.missing("   "));
        assertFalse(LocalFileUrls.missing("https://example.com/page.html"));
        assertFalse(LocalFileUrls.missing("data:text/html,<html></html>"));
        assertFalse(LocalFileUrls.missing("about:blank"));
    }

    @Test
    public void rememberForgetsOnlyWhatIsGone() throws IOException {
        Path file = Files.createTempFile("webview", ".html");
        try {
            String url = "file:///" + file.toAbsolutePath().toString().replace('\\', '/').replaceFirst("^/+", "");
            assertEquals(url, LocalFileUrls.remember(url));
        } finally {
            Files.deleteIfExists(file);
        }
        assertEquals("", LocalFileUrls.remember(null));
        assertEquals("", LocalFileUrls.remember("file:///no/such/file.html"));
        assertEquals("https://example.com/", LocalFileUrls.remember("https://example.com/"));
    }

    @Test
    public void aUrlWeCannotParseIsNotCalledMissing() {
        // The browser will report it; we will not guess. Note a bare Windows path is not a URL and is not our case.
        assertNotNull(LocalFileUrls.pathOf("file:///D:/a/b.html"));
        assertFalse(LocalFileUrls.missing("file://"));
    }
}
