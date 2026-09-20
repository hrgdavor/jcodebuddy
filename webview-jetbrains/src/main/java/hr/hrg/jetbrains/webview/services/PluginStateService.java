package hr.hrg.jetbrains.webview.services;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
@State(name = "hr.hrg.jetbrains.webview.services.PluginStateService", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class PluginStateService implements PersistentStateComponent<PluginStateService.State> {

    public static class State {
        public String lastUrl = "";
        public Integer port = null;
        public String allowedOrigins = "";
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
        myState.lastUrl = url;
    }
}
