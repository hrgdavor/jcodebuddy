package hr.hrg.jetbrains.webview.toolWindow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.Disposable;
import hr.hrg.webview.core.PathResolution;
import hr.hrg.webview.core.PathResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A Markdown file, rendered in the tool window by the IDE itself, and kept fresh while it is edited.
 *
 * <p>The maintainer's requirement, kept as given: "plugin must allow opening .md files on right click and in
 * addressbar but render clickable html, is sohuld not require manual rendering, also it must resfresh wehn markdown
 * is saved in editor".
 *
 * <p><b>How it renders without a build step at view time.</b> The page is produced by the package that owns the
 * renderer, {@code scripts/markdown-view}, at <i>plugin build</i> time - the Gradle task {@code markdownPage} runs
 * {@code page.js --template} and puts one self-contained HTML in the plugin's resources. This class substitutes the
 * document into that template's view-data marker and writes the result into the project's
 * {@code .jcodebuddy/webview/markdown-view/}, which is derived, ignored state (DEC-026). The tool window then loads
 * that file. At view time there is no bundler, no {@code node_modules}, no CDN and no server, which is what DEC-027
 * actually requires - and it is also why nothing is fetched: an ES-module page loaded from {@code file://} is blocked
 * by the browser's own origin rules, which this page never has to care about because it has no imports.
 *
 * <p><b>Why a save re-renders instead of reloading.</b> A page reload would lose the reader's scroll position and
 * re-fetch everything to show a sentence that changed. The page exposes {@code window.__renderMarkdown()}, so the
 * new text is pushed into the live page and the browser is not asked to load anything (DEC-043; plan step 9.7 for
 * the links its content carries).
 *
 * <p><b>Confinement.</b> Every path is resolved through the core's {@code PathResolver}, which resolves symbolic
 * links and refuses anything outside the project root - the maintainer's rule of 2026-10-04 applies to this route
 * like every other one. A file outside the project is refused with a log line, never rendered.
 */
@Service(Service.Level.PROJECT)
public final class MarkdownView implements Disposable {

    private static final Logger LOG = Logger.getInstance(MarkdownView.class);

    /** Where the rendered page lives: derived state inside the served project, ignored by git. */
    private static final String STATE_DIR = ".jcodebuddy/webview/markdown-view";
    private static final String PAGE_FILE = "view.html";

    /** The template the Gradle {@code markdownPage} task writes into the plugin's resources. */
    private static final String TEMPLATE_RESOURCE = "/markdown-page.html";

    /** The marker the template carries where the document goes. The same constant exists in {@code page.js}. */
    static final String VIEW_DATA_MARKER = "__MARKDOWN_VIEW_DATA__";

    /** How far up from a document a module's class index is looked for. */
    private static final int INDEX_SEARCH_DEPTH = 8;

    private static final Gson GSON = new Gson();

    private final Project project;

    /** The absolute path of the document on screen, or null. Written and read on the EDT and the VFS thread. */
    private volatile @Nullable String shownPath;

    public MarkdownView(@NotNull Project project) {
        this.project = project;
        // A save is the event that matters, so listen to the filesystem rather than to a document: VFileEvent
        // knows whether a change came from a save, and a page must not flicker while somebody is still typing.
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        onVfsChanges(events);
                    }
                });
    }

    public static MarkdownView getInstance(@NotNull Project project) {
        return project.getService(MarkdownView.class);
    }

    /** True when this path is a document this view renders. The caller decides whether to route it here. */
    public static boolean isMarkdown(@Nullable String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /**
     * Render a Markdown file in the tool window.
     *
     * @return true when a page was written and shown; false when the path is unusable, outside the project, or not
     *         readable - each with a log line saying which
     */
    public boolean show(@NotNull String filePath) {
        PathResolver resolver = PathResolver.forProject(project.getBasePath());
        PathResolution resolution = resolver.resolve(filePath);
        if (resolution == null) {
            LOG.info("Markdown view: not a usable path: " + filePath);
            return false;
        }
        if (resolution.escaped()) {
            // The rule of 2026-10-04: no webview plugin reaches a file outside the project root, by any route.
            LOG.info("Markdown view: refusing a path outside the project: " + filePath);
            return false;
        }

        Path file = Path.of(resolution.absolute());
        String markdown;
        try {
            markdown = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            LOG.info("Markdown view: could not read " + file + ": " + unreadable);
            return false;
        }

        String page = buildPage(markdown, file);
        if (page == null) {
            return false;
        }
        Path written = writePage(page);
        if (written == null) {
            return false;
        }

        shownPath = resolution.absolute();
        // The tool window owns the browser; it decides the first page (see PendingLoad) and this is a request like
        // any other, which is why nothing here touches a browser directly.
        return WebViewService.getInstance(project).openInPanel("file:///" + slash(written));
    }

    /** The document currently on screen, or null. Exposed for the plugin's own test. */
    public @Nullable String shownPath() {
        return shownPath;
    }

    /**
     * The template with this document in it.
     *
     * <p>Package-visible and pure so the substitution can be asserted without an IDE: {@code Project} is the only
     * thing this class needs from the platform for it, and the test passes null.
     */
    static @Nullable String substitute(@Nullable String template, @NotNull String viewJson) {
        if (template == null || !template.contains(VIEW_DATA_MARKER)) {
            return null;
        }
        return template.replace(VIEW_DATA_MARKER, viewJson);
    }

    /** The view JSON a page reads: the document, where it lives, and its module's class index when there is one. */
    static @NotNull String viewJson(@NotNull String markdown, @NotNull Path file, @Nullable Path classIndex) {
        JsonObject view = new JsonObject();
        view.addProperty("markdown", markdown);
        view.addProperty("docPath", slash(file));
        Path parent = file.getParent();
        view.addProperty("docDir", parent == null ? "" : slash(parent));
        view.addProperty("title", String.valueOf(file.getFileName()));
        view.addProperty("moduleName", moduleNameOf(classIndex));
        if (classIndex != null) {
            try {
                view.add("indexJson", JsonParser.parseString(Files.readString(classIndex, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException unreadable) {
                LOG.info("Markdown view: ignoring an unreadable class index " + classIndex + ": " + unreadable);
            }
        }
        return escapeForScriptTag(GSON.toJson(view));
    }

    private @Nullable String buildPage(@NotNull String markdown, @NotNull Path file) {
        String template = readTemplate();
        String page = substitute(template, viewJson(markdown, file, classIndexFor(file)));
        if (page == null) {
            LOG.warn("Markdown view: the page template is missing or has no view-data marker. "
                    + "It is generated by the Gradle task 'markdownPage' from scripts/markdown-view/page.js.");
        }
        return page;
    }

    private @Nullable String readTemplate() {
        try (InputStream stream = MarkdownView.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            LOG.warn("Markdown view: could not read " + TEMPLATE_RESOURCE, unreadable);
            return null;
        }
    }

    private @Nullable Path writePage(@NotNull String page) {
        String base = project.getBasePath();
        if (base == null) {
            return null;
        }
        Path target = Path.of(base, STATE_DIR, PAGE_FILE);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, page, StandardCharsets.UTF_8);
            return target;
        } catch (IOException unwritable) {
            LOG.warn("Markdown view: could not write " + target, unwritable);
            return null;
        }
    }

    /**
     * A save of the document on screen: push the new text into the live page.
     *
     * <p>A reload would be simpler and worse - the reader loses their place, and the whole page is re-fetched to
     * show one changed line. Only a save counts: {@link VFileEvent#isFromSave()} is false while somebody types.
     */
    private void onVfsChanges(@NotNull List<? extends VFileEvent> events) {
        String shown = shownPath;
        if (shown == null) {
            return;
        }
        for (VFileEvent event : events) {
            if (!event.isFromSave()) {
                continue;
            }
            VirtualFile file = event.getFile();
            if (file == null || file.isDirectory() || !samePath(file.getPath(), shown)) {
                continue;
            }
            String fresh = viewJson(readText(file), Path.of(shown), classIndexFor(Path.of(shown)));
            WebViewPanel panel = WebViewService.getInstance(project).getPanel();
            if (panel == null) {
                return;
            }
            LOG.info("Markdown view: " + shown + " was saved, re-rendering the page in place");
            panel.pushMarkdownView(fresh);
            return;
        }
    }

    private static @NotNull String readText(@NotNull VirtualFile file) {
        try {
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            LOG.info("Markdown view: could not re-read " + file.getPath() + ": " + unreadable);
            return "";
        }
    }

    /**
     * The class index of the module this document is in, when there is one.
     *
     * <p>{@code .jcodebuddy/index/classes.json} is what lets a page turn a bare type name into a link (DEC-029). A
     * Markdown file outside any module simply has none, and the page then links only what carries a path.
     */
    static @Nullable Path classIndexFor(@NotNull Path file) {
        Path current = Files.isDirectory(file) ? file : file.getParent();
        for (int depth = 0; current != null && depth < INDEX_SEARCH_DEPTH; depth++) {
            Path candidate = current.resolve(".jcodebuddy").resolve("index").resolve("classes.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    /** Package-private so the test can assert it without widening the public surface. */
    static @NotNull String moduleNameOf(@Nullable Path classIndex) {
        if (classIndex == null) {
            return "";
        }
        // .jcodebuddy/index/classes.json -> the module directory's name
        Path moduleDir = classIndex.getParent() == null ? null : classIndex.getParent().getParent();
        Path name = moduleDir == null ? null : moduleDir.getParent();
        return name == null ? "" : String.valueOf(name.getFileName());
    }

    private static boolean samePath(@NotNull String left, @NotNull String right) {
        return slash(left).equalsIgnoreCase(slash(right));
    }

    private static @NotNull String slash(@NotNull Path path) {
        return path.toString().replace('\\', '/');
    }

    private static @NotNull String slash(@NotNull String path) {
        return path.replace('\\', '/');
    }

    /**
     * A JSON string that cannot end the {@code <script>} tag it is written into.
     *
     * <p>The same rule the package applies in {@code jsonInScriptTag}: a document containing {@code </script>} is a
     * document, not an injection, and a raw JSON writer does not escape it.
     */
    static @NotNull String escapeForScriptTag(@NotNull String json) {
        return json.replace("<", "\\u003c");
    }

    @Override
    public void dispose() {
        shownPath = null;
    }
}
