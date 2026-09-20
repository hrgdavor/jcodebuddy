package hr.hrg.jetbrains.webview.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import hr.hrg.jetbrains.webview.services.HttpBridgeService;
import hr.hrg.jetbrains.webview.services.PluginStateService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * Project settings for the WebView Explorer: the HTTP bridge's port, allow-list and token.
 *
 * <p>Unlike the first version, an invalid port is reported instead of being swallowed, and the form
 * shows whether the bridge is actually running.
 */
public class WebViewSettingsConfigurable implements Configurable {

    private final Project project;
    private WebViewSettingsComponent mySettingsComponent;

    public WebViewSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "WebView Explorer";
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return mySettingsComponent.getPreferredFocusedComponent();
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        mySettingsComponent = new WebViewSettingsComponent();
        refreshStatus();
        return mySettingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        if (state == null) {
            return false;
        }
        String portText = state.port != null ? state.port.toString() : "";
        return !mySettingsComponent.getPortText().equals(portText)
                || !mySettingsComponent.getAllowedOriginsText().equals(nullToEmpty(state.allowedOrigins))
                || !mySettingsComponent.getTokenText().equals(nullToEmpty(state.token));
    }

    @Override
    public void apply() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        if (state == null) {
            return;
        }

        String portText = mySettingsComponent.getPortText().trim();
        if (portText.isEmpty()) {
            state.port = null;
        } else {
            try {
                int port = Integer.parseInt(portText);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException("out of range");
                }
                state.port = port;
            } catch (NumberFormatException e) {
                // Report rather than silently keep the old value, which is what made a typo look like
                // "the bridge just does not work".
                throw new IllegalArgumentException("'" + portText + "' is not a port number between 1 and 65535");
            }
        }

        state.allowedOrigins = mySettingsComponent.getAllowedOriginsText().trim();
        state.token = mySettingsComponent.getTokenText().trim();

        HttpBridgeService.getInstance(project).restartServer();
        refreshStatus();
    }

    @Override
    public void reset() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        if (state == null) {
            return;
        }
        mySettingsComponent.setPortText(state.port != null ? state.port.toString() : "");
        mySettingsComponent.setAllowedOriginsText(nullToEmpty(state.allowedOrigins));
        mySettingsComponent.setTokenText(nullToEmpty(state.token));
        refreshStatus();
    }

    @Override
    public void disposeUIResources() {
        mySettingsComponent = null;
    }

    private void refreshStatus() {
        WebViewSettingsComponent component = mySettingsComponent;
        if (component == null) {
            return;
        }
        HttpBridgeService bridge = HttpBridgeService.getInstance(project);
        component.setStatus(bridge.describeState()
                + "; the setting is stored per project and read when the project opens.");
    }

    private static String nullToEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }
}
