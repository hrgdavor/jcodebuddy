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
 *
 * <p>{@code socketEnabled} is the second, narrower switch: the live tool endpoint of DEC-053
 * ({@code .jcodebuddy/ipc/}) is on while the build is on, and can be turned off without turning the build
 * off. It defaults to true because enabling the incremental build for a project is already the explicit
 * opt-in, and the endpoint is a filesystem socket rather than a listening network port.</p>
 */
@Service(Service.Level.PROJECT)
@State(name = "JCodeBuddyIncrementalBuild", storages = @Storage("jcodebuddy-incremental.xml"))
public final class JcodeBuddySettings implements PersistentStateComponent<JcodeBuddySettings.State> {

    private State state = new State();

    public static final class State {
        public boolean enabled;
        public boolean socketEnabled = true;
    }

    public boolean isEnabled() {
        return state.enabled;
    }

    public void setEnabled(boolean enabled) {
        state.enabled = enabled;
    }

    /** Whether this project publishes the live tool socket while the build is enabled. */
    public boolean isSocketEnabled() {
        return state.socketEnabled;
    }

    public void setSocketEnabled(boolean socketEnabled) {
        state.socketEnabled = socketEnabled;
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
