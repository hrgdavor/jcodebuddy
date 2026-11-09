package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;

/**
 * Project-level switch: <b>Settings | Tools | JCodeBuddy Incremental Build</b>.
 *
 * <p>The setting is stored in the project (<code>.idea/jcodebuddy-incremental.xml</code>), so two
 * projects opened in the same IDE can make different choices.</p>
 */
public final class JcodeBuddyConfigurable implements Configurable {

    private final Project project;
    private JCheckBox enabled;

    public JcodeBuddyConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "JCodeBuddy Incremental Build";
    }

    @Override
    public @Nullable JComponent createComponent() {
        enabled = new JCheckBox("Enable the fs-meta + ECJ incremental build for this project");
        enabled.setSelected(JcodeBuddySettings.getInstance(project).isEnabled());
        return enabled;
    }

    @Override
    public boolean isModified() {
        return enabled != null
                && enabled.isSelected() != JcodeBuddySettings.getInstance(project).isEnabled();
    }

    @Override
    public void apply() {
        JcodeBuddySettings settings = JcodeBuddySettings.getInstance(project);
        settings.setEnabled(enabled != null && enabled.isSelected());
        project.getService(IncrementalBuildService.class).applyEnabledState();
    }

    @Override
    public void reset() {
        if (enabled != null) {
            enabled.setSelected(JcodeBuddySettings.getInstance(project).isEnabled());
        }
    }

    @Override
    public void disposeUIResources() {
        enabled = null;
    }
}
