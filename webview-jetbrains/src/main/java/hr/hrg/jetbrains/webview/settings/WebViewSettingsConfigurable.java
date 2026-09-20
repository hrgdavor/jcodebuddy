package hr.hrg.jetbrains.webview.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import hr.hrg.jetbrains.webview.services.HttpBridgeService;
import hr.hrg.jetbrains.webview.services.PluginStateService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

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
        return mySettingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        if (state == null)
            return false;

        String portStr = state.port != null ? state.port.toString() : "";
        boolean modified = !mySettingsComponent.getPortText().equals(portStr);
        modified |= !mySettingsComponent.getAllowedOriginsText().equals(state.allowedOrigins);
        return modified;
    }

    @Override
    public void apply() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        if (state == null)
            return;

        try {
            String portText = mySettingsComponent.getPortText();
            state.port = portText.isEmpty() ? null : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            // Optional: Show error message
        }
        state.allowedOrigins = mySettingsComponent.getAllowedOriginsText();

        // Restart server with new settings
        HttpBridgeService.getInstance(project).restartServer();
    }

    @Override
    public void reset() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        if (state == null)
            return;

        mySettingsComponent.setPortText(state.port != null ? state.port.toString() : "");
        mySettingsComponent.setAllowedOriginsText(state.allowedOrigins != null ? state.allowedOrigins : "");
    }

    @Override
    public void disposeUIResources() {
        mySettingsComponent = null;
    }
}
