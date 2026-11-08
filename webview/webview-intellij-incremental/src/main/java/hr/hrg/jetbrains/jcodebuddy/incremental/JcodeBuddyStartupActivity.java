package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the project's incremental build service when the project opens. A project without Java source
 * roots simply does nothing.
 */
public final class JcodeBuddyStartupActivity implements StartupActivity {

    @Override
    public void runActivity(@NotNull Project project) {
        project.getService(IncrementalBuildService.class).start();
    }
}
