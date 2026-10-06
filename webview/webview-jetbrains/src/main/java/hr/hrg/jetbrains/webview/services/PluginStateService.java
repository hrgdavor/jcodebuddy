package hr.hrg.jetbrains.webview.services;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-project state for the plugin.
 *
 * <p>It mixes two concerns on purpose: {@link State#lastUrl} is view state (where the developer last
 * looked) while {@link State#port}, {@link State#allowedOrigins} and {@link State#token} are
 * configuration. Three fields do not justify two services, and both belong to the project's workspace
 * file rather than to the IDE-wide settings, so they live together.
 */
@Service(Service.Level.PROJECT)
@State(name = "hr.hrg.jetbrains.webview.services.PluginStateService", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class PluginStateService implements PersistentStateComponent<PluginStateService.State> {

    public static class State {
        /** The last URL the tool window showed. Empty means "show the splash page". */
        public String lastUrl = "";
        /** The HTTP bridge port, or null when the bridge is off. */
        public Integer port = null;
        /** Comma-separated origins allowed to call the HTTP bridge. Empty denies every caller. */
        public String allowedOrigins = "";
        /** Optional shared secret the HTTP bridge requires; empty means only the allow-list applies. */
        public String token = "";
        /**
         * Optional path to the Markdown page generator this project renders with.
         *
         * <p>Empty means "the generator embedded in the plugin", which is the page the Gradle task {@code markdownPage}
         * builds from {@code markdown-view/page.js}. Setting it points the Markdown view at something else, which is
         * useful for two different reasons: developing the renderer (point at a checkout's {@code page.js} and see your
         * changes in the IDE without rebuilding the plugin) and customising the output (point at your own generator or
         * at a page you built yourself).
         *
         * <p>Two spellings are accepted, because those are the two things a person has in hand: a {@code .js}
         * generator, which is run with Bun and asked for an inlined template; and an {@code .html} file, which is read
         * as the template directly. Either way the result must carry the view-data marker, or the setting is refused
         * with a log line rather than producing a page that shows nothing.
         *
         * <p>Resolution is the same shape as the port's, and for the same reason: this IDE setting wins, then the
         * project's committed {@code .jcodebuddy/conf/webview.json} (so a team can share a customisation), then the
         * embedded page.
         */
        public String markdownGenerator = "";
    }

    private State myState = new State();

    public static PluginStateService getInstance(@NotNull Project project) {
        return project.getService(PluginStateService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public String getLastUrl() {
        return myState.lastUrl;
    }

    public void setLastUrl(String url) {
        myState.lastUrl = url == null ? "" : url;
    }
}
