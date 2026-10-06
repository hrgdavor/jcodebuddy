package hr.hrg.jetbrains.webview.toolWindow;

import hr.hrg.webview.core.PathResolution;
import hr.hrg.webview.core.PathResolver;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Markdown view's contract, tested where it is pure and where it can actually be wrong.
 *
 * <p>Three things are worth a test here rather than a paragraph:
 *
 * <ol>
 *   <li><b>The page ships inside the plugin.</b> The template is built by the Gradle task {@code markdownPage} from
 *       {@code scripts/markdown-view/page.js} at build time. If that step is ever dropped or renamed, the tool
 *       window would open a blank page and nothing would say why — so this fails instead.</li>
 *   <li><b>The substitution is honest.</b> A template without the marker produces null, not a page that silently
 *       shows nothing; a document containing {@code </script>} cannot end the tag it is written into.</li>
 *   <li><b>The jail applies to this route too.</b> A Markdown file outside the project is refused by the same
 *       {@code PathResolver} every other route uses, which is the rule of 2026-10-04.</li>
 * </ol>
 */
public class MarkdownViewTest {

    @Test
    public void theGeneratedPageIsInThePlugin() throws IOException {
        try (InputStream stream = MarkdownView.class.getResourceAsStream("/markdown-page.html")) {
            assertNotNull("the Gradle task 'markdownPage' must put the page in the plugin's resources", stream);
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("it is a whole document", template.startsWith("<!DOCTYPE html>"));
            assertTrue("it carries the marker the tool window substitutes",
                    template.contains(MarkdownView.VIEW_DATA_MARKER));
            assertTrue("the renderer is inlined, so nothing is fetched at view time",
                    template.contains("__renderMarkdown"));
            assertTrue("the highlighter is inlined too", template.contains("microlighter"));
            assertFalse("and nothing is loaded from a server", template.contains("<script src="));
        }
    }

    @Test
    public void theSubstitutionIsHonestAboutAMissingTemplate() {
        assertNull("no template means no page, not an empty one", MarkdownView.substitute(null, "{}"));
        assertNull("a template without the marker is a build error, not a page that shows nothing",
                MarkdownView.substitute("<html><body>nothing here</body></html>", "{}"));
    }

    @Test
    public void theSubstitutionPutsTheDocumentInThePage() {
        String template = "<html>" + MarkdownView.VIEW_DATA_MARKER + "</html>";
        String page = MarkdownView.substitute(template, "{\"markdown\":\"# Hi\"}");
        assertNotNull(page);
        assertFalse("the marker is gone, not left for the browser to read", page.contains(MarkdownView.VIEW_DATA_MARKER));
        assertTrue(page.contains("# Hi"));
    }

    @Test
    public void aDocumentCannotEndTheScriptTagItIsWrittenInto() {
        // A Markdown file is a document, not an injection: the JSON writer does not escape '<', this does.
        String json = MarkdownView.viewJson("before </script><script>alert(1)</script> after",
                Path.of("D:/proj/docs/README.md"), null);
        assertFalse(json.contains("</script>"));
        assertTrue(json.contains("\\u003c/script"));
    }

    @Test
    public void theViewCarriesPathsTheHostCanResolve() {
        String json = MarkdownView.viewJson("# Title", Path.of("D:\\proj\\docs\\README.md"), null);
        // Forward slashes: every editor API and every URL in this product takes them, and a page builds URLs.
        assertTrue(json.contains("D:/proj/docs/README.md"));
        assertTrue(json.contains("D:/proj/docs"));
        assertTrue(json.contains("README.md"));
        assertTrue(json.contains("\"markdown\""));
    }

    @Test
    public void onlyMarkdownIsRoutedToThisView() {
        assertTrue(MarkdownView.isMarkdown("docs/README.md"));
        assertTrue(MarkdownView.isMarkdown("D:/proj/NOTES.MD"));
        assertTrue(MarkdownView.isMarkdown("a/b/file.markdown"));
        assertFalse("an HTML page is a page, and the host loads it", MarkdownView.isMarkdown("page.html"));
        assertFalse(MarkdownView.isMarkdown("src/A.java"));
        assertFalse(MarkdownView.isMarkdown(null));
    }

    @Test
    public void theModuleClassIndexIsFoundByWalkingUp() throws IOException {
        Path module = Files.createTempDirectory("md-module");
        try {
            Path docs = Files.createDirectories(module.resolve("docs/nested"));
            Path indexDir = Files.createDirectories(module.resolve(".jcodebuddy/index"));
            Path index = Files.writeString(indexDir.resolve("classes.json"), "{\"types\":[]}");
            Files.writeString(docs.resolve("README.md"), "# x");

            assertEquals(index, MarkdownView.classIndexFor(docs.resolve("README.md")));
            assertEquals("the module directory names the module",
                    module.getFileName().toString(), MarkdownView.moduleNameOf(index));
        } finally {
            deleteTree(module);
        }
    }

    @Test
    public void aDocumentWithNoModuleHasNoIndex() throws IOException {
        Path outside = Files.createTempDirectory("md-outside");
        try {
            Files.writeString(outside.resolve("README.md"), "# x");
            // Nothing above it has a .jcodebuddy, and the walk is capped, so there is no index rather than a guess.
            assertNull(MarkdownView.classIndexFor(outside.resolve("README.md")));
        } finally {
            deleteTree(outside);
        }
    }

    @Test
    public void theJailRefusesAMarkdownFileOutsideTheProject() throws IOException {
        Path project = Files.createTempDirectory("md-project");
        Path outside = Files.createTempDirectory("md-elsewhere");
        try {
            Files.writeString(outside.resolve("secret.md"), "# secret");
            PathResolver resolver = PathResolver.forProject(project.toString());

            // The same resolver the view uses: relative escapes, absolute paths and symlinks are its business, and
            // this asserts the view would refuse what it refuses rather than trusting the caller.
            PathResolution escaped = resolver.resolve(outside.resolve("secret.md").toString());
            assertNotNull(escaped);
            assertTrue("a Markdown file outside the project is refused", escaped.escaped());
        } finally {
            deleteTree(project);
            deleteTree(outside);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach((path) -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // a temp file left behind is not a test failure
                }
            });
        }
    }
}
