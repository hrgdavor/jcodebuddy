package hr.hrg.jetbrains.webview.toolWindow;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
import hr.hrg.jetbrains.webview.services.PluginStateService;
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
 * renderer, {@code markdown-view}, at <i>plugin build</i> time - the Gradle task {@code markdownPage} runs
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

    /** The key both the IDE setting and the project's committed configuration use for the generator path. */
    private static final String GENERATOR_KEY = "markdownGenerator";

    /** The marker the template carries where the document goes. The same constant exists in {@code page.js}. */
    static final String VIEW_DATA_MARKER = "__MARKDOWN_VIEW_DATA__";

    /** How far up from a document a module's class index is looked for. */
    private static final int INDEX_SEARCH_DEPTH = 8;

    private static final Gson GSON = new Gson();

    private final Project project;

    /** The absolute path of the document on screen, or null. Written and read on the EDT and the VFS thread. */
    private volatile @Nullable String shownPath;

    /** The generator setting the cached template was produced from, and the template itself. */
    private volatile @Nullable String cachedGenerator;
    private volatile @Nullable String cachedTemplate;

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

        String json = viewJson(markdown, file, classIndexFor(file));
        String page = substitute(readTemplate(), json);
        if (page == null) {
            return false;
        }
        Path written = writePage(page);
        if (written == null) {
            return false;
        }

        shownPath = resolution.absolute();
        String pageUrl = "file:///" + slash(written);
        WebViewPanel panel = WebViewService.getInstance(project).getPanel();
        // The page is always the same file, so its URL never changes - and a browser does not reload a URL it
        // already has. Asking for a load here is what made the second right-click (and the address bar after it)
        // do nothing at all: the file on disk was new and the page on screen was the old document. Pushing the
        // new view data re-renders in place, which is the same road the save refresh takes.
        if (panel != null && canPushInsteadOfLoad(panel.currentUrl(), pageUrl)) {
            panel.pushMarkdownView(json);
            return true;
        }
        // No page of ours on screen: the tool window owns the browser and decides the first page (see PendingLoad).
        return WebViewService.getInstance(project).openInPanel(pageUrl);
    }

    /**
     * Whether fresh view data can be pushed instead of asking the browser to load the page again.
     *
     * <p>Pure, and tested: a browser does not reload a URL it already has, so "the page on screen is the page I
     * just wrote" is the whole question. Equality is by normalised text - forward slashes and case, because JCEF
     * and the code that produced the URL spell Windows paths differently without meaning different files.
     */
    static boolean canPushInsteadOfLoad(@Nullable String currentUrl, @NotNull String pageUrl) {
        String current = normaliseUrl(currentUrl);
        return !current.isEmpty() && current.equals(normaliseUrl(pageUrl));
    }

    /**
     * A URL reduced to what names the file: no scheme, no percent-escapes, forward slashes, and case folded only
     * where the filesystem folds it - on Linux two paths that differ in case are two files, and treating them as
     * one would push a page into the wrong document.
     */
    static @NotNull String normaliseUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String text = url.trim();
        if (text.regionMatches(true, 0, "file:///", 0, "file:///".length())) {
            text = text.substring("file:///".length());
        } else if (text.regionMatches(true, 0, "file://", 0, "file://".length())) {
            text = text.substring("file://".length());
        }
        try {
            // A space in a project path is %20 in the URL JCEF reports and a literal space in the path this class
            // wrote, so comparing the raw text would miss and ask for a load of a URL the browser already has.
            text = java.net.URLDecoder.decode(text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException malformed) {
            // Not decodable: compare it as it stands rather than refusing to recognise our own page.
        }
        String slashed = slash(text);
        String os = System.getProperty("os.name", "");
        boolean foldsCase = os.startsWith("Windows") || os.startsWith("Mac");
        return foldsCase ? slashed.toLowerCase(java.util.Locale.ROOT) : slashed;
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
                view.add("indexJson", absoluteIndex(classIndex));
            } catch (IOException | RuntimeException unreadable) {
                LOG.info("Markdown view: ignoring an unreadable class index " + classIndex + ": " + unreadable);
            }
        }
        return escapeForScriptTag(GSON.toJson(view));
    }

    /**
     * The module's class index with every row's path made absolute.
     *
     * <p>The index stores paths relative to the module (DEC-029), and a page resolves a link against its
     * document's directory - which for a Markdown file in `docs/nested/` is not the module root. Handing over the
     * rows as written would therefore point at a file that is not there, and the page cannot tell: a wrong link
     * is worse than no link, so the host absolves the page of the guess by making the paths absolute here.
     */
    private static @NotNull JsonObject absoluteIndex(@NotNull Path classIndex) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(classIndex, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonElement classes = root.get("classes");
        if (classes == null || !classes.isJsonObject()) {
            return root;
        }
        Path moduleRoot = classIndex.getParent() == null ? null
                : classIndex.getParent().getParent() == null ? null : classIndex.getParent().getParent().getParent();
        if (moduleRoot == null) {
            return root;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : classes.getAsJsonObject().entrySet()) {
            JsonElement row = entry.getValue();
            if (row != null && row.isJsonObject() && row.getAsJsonObject().has("path")) {
                String relative = row.getAsJsonObject().get("path").getAsString();
                row.getAsJsonObject().addProperty("path", slash(moduleRoot.resolve(relative)));
            }
        }
        return root;
    }

    private @Nullable String buildPage(@NotNull String markdown, @NotNull Path file) {
        String template = readTemplate();
        String page = substitute(template, viewJson(markdown, file, classIndexFor(file)));
        if (page == null) {
            LOG.warn("Markdown view: the page template is missing or has no view-data marker. "
                    + "It is generated by the Gradle task 'markdownPage' from markdown-view/page.js.");
        }
        return page;
    }

    /**
     * The page template: the configured generator's, or the embedded one.
     *
     * <p>The configured path is resolved once per change rather than per document, because asking Bun for a template
     * is a process, and a cache keyed on the setting plus the file's modification time is what keeps a rebuild of the
     * renderer visible without paying for it on every keystroke.
     */
    private @Nullable String readTemplate() {
        String configured = configuredGenerator();
        if (!configured.isBlank()) {
            String cached = cachedTemplate;
            if (cached != null && configured.equals(cachedGenerator)) {
                return cached;
            }
            String external = templateFromConfigured(configured);
            if (external != null) {
                cachedGenerator = configured;
                cachedTemplate = external;
                LOG.info("Markdown view: using the configured generator " + configured);
                return external;
            }
            LOG.warn("Markdown view: the configured Markdown generator '" + configured + "' produced no usable "
                    + "template (it must be a .js generator or an .html page carrying " + VIEW_DATA_MARKER
                    + "). Falling back to the page embedded in the plugin.");
        }
        return embeddedTemplate();
    }

    /** The page the plugin ships, built by the Gradle task {@code markdownPage}. */
    private @Nullable String embeddedTemplate() {
        try (InputStream stream = MarkdownView.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            LOG.warn("Markdown view: could not read " + TEMPLATE_RESOURCE, unreadable);
            return null;
        }
    }

    /**
     * The generator this project configured: this IDE's setting first, then the project's committed
     * {@code .jcodebuddy/conf/webview.json}, and empty when neither says anything — which means "the embedded page".
     */
    private @NotNull String configuredGenerator() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        String fromIde = state == null ? null : state.markdownGenerator;
        return resolveGeneratorSetting(fromIde, projectGeneratorSetting());
    }

    /** The {@code markdownGenerator} key of the project's committed webview configuration, or null. */
    private @Nullable String projectGeneratorSetting() {
        String base = project.getBasePath();
        if (base == null) {
            return null;
        }
        Path conf = Path.of(base, ".jcodebuddy", "conf", "webview.json");
        if (!Files.isRegularFile(conf)) {
            return null;
        }
        try {
            return jsonStringKey(Files.readString(conf, StandardCharsets.UTF_8), GENERATOR_KEY);
        } catch (IOException | RuntimeException unreadable) {
            LOG.info("Markdown view: ignoring an unreadable " + conf + ": " + unreadable);
            return null;
        }
    }

    /**
     * The settings as they resolve: an explicit IDE setting wins, the project's committed one is the fallback, and
     * empty means the embedded page.
     *
     * <p>Pure and package-private so the precedence is asserted rather than assumed — this is the kind of rule that
     * looks obvious and gets quietly reversed by a later edit.
     */
    static @NotNull String resolveGeneratorSetting(@Nullable String fromIde, @Nullable String fromProject) {
        for (String candidate : new String[]{fromIde, fromProject}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    /** One string key of a JSON object, or null when the text is not JSON or the key is not a non-blank string. */
    static @Nullable String jsonStringKey(@Nullable String json, @NotNull String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement value = root.getAsJsonObject().get(key);
            if (value == null || !value.isJsonPrimitive()) {
                return null;
            }
            String text = value.getAsString();
            return text == null || text.isBlank() ? null : text.trim();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** A configured generator's template: an {@code .html} is read, a {@code .js} is run with Bun. */
    private @Nullable String templateFromConfigured(@NotNull String configured) {
        PathResolver resolver = PathResolver.forProject(project.getBasePath());
        PathResolution resolution = resolver.resolve(configured);
        if (resolution == null || resolution.escaped()) {
            // The 2026-10-04 rule applies to this path like every other one a project can set.
            LOG.warn("Markdown view: the configured generator is outside the project or not a path: " + configured);
            return null;
        }
        Path generator = Path.of(resolution.absolute());
        if (!Files.isRegularFile(generator)) {
            LOG.warn("Markdown view: no generator at " + generator);
            return null;
        }
        String name = generator.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            try {
                String template = Files.readString(generator, StandardCharsets.UTF_8);
                return template.contains(VIEW_DATA_MARKER) ? template : null;
            } catch (IOException unreadable) {
                LOG.warn("Markdown view: could not read " + generator, unreadable);
                return null;
            }
        }
        if (name.endsWith(".js") || name.endsWith(".mjs")) {
            return generateWithBun(generator);
        }
        LOG.warn("Markdown view: a Markdown generator must be a .js generator or an .html page, not " + name);
        return null;
    }

    /**
     * Ask a JavaScript generator for an inlined template, with Bun.
     *
     * <p>This is the path that makes "develop the renderer" practical: point the setting at
     * {@code markdown-view/page.js} in a checkout, edit the renderer, and the next document shows the change without
     * rebuilding the plugin. Bun is this repository's tooling, so a machine that has the checkout has it; a machine
     * that does not gets the embedded page and a log line saying why.
     */
    private @Nullable String generateWithBun(@NotNull Path generator) {
        Path output;
        try {
            output = Files.createTempFile("markdown-page-", ".html");
        } catch (IOException unwritable) {
            LOG.warn("Markdown view: could not create a temporary file for " + generator, unwritable);
            return null;
        }
        try {
            Process process = new ProcessBuilder("bun", "run", generator.toString(), output.toString(), "inlined",
                    "--template")
                    .redirectErrorStream(true)
                    .start();
            String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                LOG.warn("Markdown view: " + generator + " failed: " + said.trim());
                return null;
            }
            String template = Files.readString(output, StandardCharsets.UTF_8);
            return template.contains(VIEW_DATA_MARKER) ? template : null;
        } catch (IOException | InterruptedException | RuntimeException failed) {
            LOG.warn("Markdown view: could not run " + generator + " (is Bun on the PATH?): " + failed);
            Thread.currentThread().interrupt();
            return null;
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // A leftover temporary file is not worth failing a render over.
            }
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
