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
