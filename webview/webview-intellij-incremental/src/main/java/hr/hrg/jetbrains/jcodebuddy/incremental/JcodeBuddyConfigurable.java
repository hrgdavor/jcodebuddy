package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Project-level switch: <b>Settings | Tools | JCodeBuddy Incremental Build</b>.
 *
 * <p>The setting is stored in the project (<code>.idea/jcodebuddy-incremental.xml</code>), so two
 * projects opened in the same IDE can make different choices. The second checkbox controls the live tool
 * endpoint (DEC-053) independently of the build itself.</p>
 */
public final class JcodeBuddyConfigurable implements Configurable {

    private final Project project;
    private JCheckBox enabled;
    private JCheckBox socketEnabled;

    public JcodeBuddyConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "JCodeBuddy Incremental Build";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JcodeBuddySettings settings = JcodeBuddySettings.getInstance(project);
        enabled = new JCheckBox("Enable the fs-meta + ECJ incremental build for this project");
        socketEnabled = new JCheckBox("Publish the live tool socket in .jcodebuddy/ipc (DEC-053)");
        socketEnabled.setToolTipText("A Unix domain socket that a webview or standalone server proxies; "
                + "no TCP port is opened. Only this project's source roots are served.");
        socketEnabled.setBorder(BorderFactory.createEmptyBorder(0, 24, 6, 0));
        socketEnabled.setSelected(settings.isSocketEnabled());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(enabled);
        panel.add(socketEnabled);
        return panel;
    }

    @Override
    public boolean isModified() {
        JcodeBuddySettings settings = JcodeBuddySettings.getInstance(project);
        return enabled != null && enabled.isSelected() != settings.isEnabled()
                || socketEnabled != null && socketEnabled.isSelected() != settings.isSocketEnabled();
    }

    @Override
    public void apply() {
        JcodeBuddySettings settings = JcodeBuddySettings.getInstance(project);
        settings.setEnabled(enabled != null && enabled.isSelected());
        settings.setSocketEnabled(socketEnabled != null && socketEnabled.isSelected());
        project.getService(IncrementalBuildService.class).applyEnabledState();
    }

    @Override
    public void reset() {
        JcodeBuddySettings settings = JcodeBuddySettings.getInstance(project);
        if (enabled != null) {
            enabled.setSelected(settings.isEnabled());
        }
        if (socketEnabled != null) {
            socketEnabled.setSelected(settings.isSocketEnabled());
        }
    }

    @Override
    public void disposeUIResources() {
        enabled = null;
        socketEnabled = null;
    }
}
