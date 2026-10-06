package hr.hrg.webview.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The page/file route, which used to exist only in the VS Code host and answered {@code
 * Access-Control-Allow-Origin: *} to anyone.
 */
public class PageServerTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private PageServer serverFor(Path root) {
        return new PageServer(root.toString());
    }

    @Test
    public void servesAFileFromTheProject() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.writeString(project.resolve("report.html"), "<html>hi</html>");

        PageServer.Response response = serverFor(project)
                .serve("/file/report.html", null);

        assertEquals(PageServer.Status.OK, response.status());
        assertEquals(200, response.httpStatus());
        assertEquals("text/html", response.contentType());
        assertEquals("<html>hi</html>", new String(response.body(), StandardCharsets.UTF_8));
    }

    /**
     * A page encodes each path segment with {@code encodeURIComponent}, so a space arrives as {@code %20} and
     * must come back as a space — but a {@code +} must stay a {@code +}, which is where {@code URLDecoder}
     * would have been wrong.
     */
    @Test
    public void decodesPercentEscapesWithoutTurningPlusIntoSpace() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.writeString(project.resolve("a b.html"), "spaced");
        Files.writeString(project.resolve("a+b.html"), "plus");

        assertEquals("spaced", body(serverFor(project).serve("/file/a%20b.html", null)));
        assertEquals("plus", body(serverFor(project).serve("/file/a+b.html", null)));
    }

    /** Non-ASCII file names survive the round trip, which a per-byte decode would mangle. */
    @Test
    public void decodesUtf8FileNames() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.writeString(project.resolve("izvještaj.html"), "hrvatski");

        PageServer.Response response =
                serverFor(project).serve("/file/izvje%C5%A1taj.html", null);

        assertEquals(PageServer.Status.OK, response.status());
        assertEquals("hrvatski", body(response));
    }

    @Test
    public void injectsTheBridgeIntoHtmlOnly() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.writeString(project.resolve("page.html"), "<html>x</html>");
        Files.writeString(project.resolve("data.json"), "{}");

        assertEquals("<html>x</html><script>BRIDGE</script>",
                body(serverFor(project).serve("/file/page.html", "<script>BRIDGE</script>")));
        assertEquals("{}", body(serverFor(project).serve("/file/data.json", "<script>B</script>")));
    }

    @Test
    public void stripsAQueryStringAndFragment() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.writeString(project.resolve("page.html"), "x");

        assertEquals(PageServer.Status.OK,
                serverFor(project).serve("/file/page.html?v=2#top", null).status());
    }

    /** The rule the VS Code route was missing: a page may not read outside the project. */
    /** The rule the VS Code route was missing, and the one the maintainer made unconditional. */
    @Test
    public void refusesAPathOutsideTheProject() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Path outside = folder.newFolder("outside").toPath();
        Files.writeString(outside.resolve("secret.txt"), "secret");

        PageServer server = serverFor(project);
        String escape = "/file/../outside/secret.txt";

        assertEquals(PageServer.Status.FORBIDDEN, server.serve(escape, null).status());
        assertEquals(403, server.serve(escape, null).httpStatus());
    }
    @Test
    public void reportsWhatIsNotThere() throws IOException {
        Path project = folder.newFolder("proj").toPath();
        Files.createDirectories(project.resolve("sub"));

        assertEquals(PageServer.Status.NOT_FOUND,
                serverFor(project).serve("/file/missing.html", null).status());
        assertEquals(PageServer.Status.IS_A_DIRECTORY,
                serverFor(project).serve("/file/sub", null).status());
    }

    @Test
    public void rejectsRequestsThatAreNotThisRoute() throws IOException {
        Path project = folder.newFolder("proj").toPath();

        assertEquals(PageServer.Status.BAD_REQUEST, serverFor(project).serve(null, null).status());
        assertEquals(PageServer.Status.BAD_REQUEST, serverFor(project).serve("", null).status());
        assertEquals(PageServer.Status.BAD_REQUEST,
                serverFor(project).serve("/open?filePath=x", null).status());
        assertEquals(PageServer.Status.BAD_REQUEST,
                serverFor(project).serve("/file/", null).status());
    }

    @Test
    public void knowsTheContentTypesTheHostsUsed() {
        PageServer server = new PageServer(null);

        assertEquals("text/html", server.contentTypeOf("a.HTML"));
        assertEquals("text/javascript", server.contentTypeOf("a.js"));
        assertEquals("text/css", server.contentTypeOf("a.css"));
        assertEquals("application/json", server.contentTypeOf("a.json"));
        assertEquals("image/svg+xml", server.contentTypeOf("a.svg"));
        assertEquals("application/pdf", server.contentTypeOf("a.pdf"));
        assertEquals("application/octet-stream", server.contentTypeOf("a.unknown"));
        assertEquals("application/octet-stream", server.contentTypeOf("noextension"));
        assertEquals("application/octet-stream", server.contentTypeOf(null));
    }

    /**
     * With no project root there is nothing to escape from, so confinement cannot refuse anything — the
     * resolver has no jail to apply. This is the headless case, and it must serve, not refuse.
     */
    @Test
    public void withoutAProjectEveryFileIsReachable() throws IOException {
        Path anywhere = folder.newFolder("anywhere").toPath();
        Files.writeString(anywhere.resolve("a.html"), "x");

        PageServer server = new PageServer(null);
        String absolute = anywhere.resolve("a.html").toString().replace('\\', '/');


        // The flag is gone: confinement is not a mode this class has any more.
        assertEquals(PageServer.Status.OK, server.serve("/file/" + absolute, null).status());
    }

    @Test
    public void declaresItsCapabilityKey() {
        assertTrue(PageServer.CAPABILITIES.contains("serveFile"));
    }

    private static String body(PageServer.Response response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
