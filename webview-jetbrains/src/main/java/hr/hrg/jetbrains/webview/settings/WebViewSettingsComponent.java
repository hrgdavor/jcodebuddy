package hr.hrg.jetbrains.webview.settings;

import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * The settings form for the WebView Explorer: the HTTP bridge's port, its allow-list, its optional
 * token, and a status line saying what the bridge is doing right now.
 */
public class WebViewSettingsComponent {

    private final JPanel myMainPanel;
    private final JBTextField myPortText = new JBTextField();
    private final JBTextField myAllowedOriginsText = new JBTextField();
    private final JBTextField myTokenText = new JBTextField();
    private final JBLabel myStatusLabel = new JBLabel();

    public WebViewSettingsComponent() {
        myMainPanel = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("HTTP bridge (browser fallback)"))
                .addLabeledComponent(new JBLabel("Port: "), myPortText, 1, false)
                .addLabeledComponent(new JBLabel("Allowed origins: "), myAllowedOriginsText, 1, false)
                .addLabeledComponent(new JBLabel("Token: "), myTokenText, 1, false)
                .addComponent(new JBLabel("<html><body style='width:520px'>"
                        + "The bridge is off while the port is empty. It listens on 127.0.0.1 only, and it "
                        + "opens a file in the IDE only for a caller that presents the token or one of the "
                        + "allowed origins. With both empty every caller is refused. Pages loaded in the "
                        + "built-in webview do not need the bridge: they get <code>window.openFile</code> "
                        + "injected instead."
                        + "</body></html>"))
                .addComponent(myStatusLabel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public JPanel getPanel() {
        return myMainPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return myPortText;
    }

    @NotNull
    public String getPortText() {
        return myPortText.getText();
    }

    public void setPortText(@NotNull String newText) {
        myPortText.setText(newText);
    }

    @NotNull
    public String getAllowedOriginsText() {
        return myAllowedOriginsText.getText();
    }

    public void setAllowedOriginsText(@NotNull String newText) {
        myAllowedOriginsText.setText(newText);
    }

    @NotNull
    public String getTokenText() {
        return myTokenText.getText();
    }

    public void setTokenText(@NotNull String newText) {
        myTokenText.setText(newText);
    }

    /** Shows what the bridge is currently doing, so the port field is not the only feedback. */
    public void setStatus(@NotNull String status) {
        myStatusLabel.setText(status);
    }
}
