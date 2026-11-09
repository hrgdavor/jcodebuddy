package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Per-project opt-in for the incremental build.
 *
 * <p>IntelliJ plugins are installed globally. This project-level state is what makes the compiler run
 * only for projects that explicitly enable it; the service itself is still registered for every project,
 * but it does nothing until {@link IncrementalBuildService#start()} sees this value as true.</p>
 */
@Service(Service.Level.PROJECT)
@State(name = "JCodeBuddyIncrementalBuild", storages = @Storage("jcodebuddy-incremental.xml"))
public final class JcodeBuddySettings implements PersistentStateComponent<JcodeBuddySettings.State> {

    private State state = new State();

    public static final class State {
        public boolean enabled;
    }

    public boolean isEnabled() {
        return state.enabled;
    }

    public void setEnabled(boolean enabled) {
        state.enabled = enabled;
    }

    public static JcodeBuddySettings getInstance(Project project) {
        return project.getService(JcodeBuddySettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
