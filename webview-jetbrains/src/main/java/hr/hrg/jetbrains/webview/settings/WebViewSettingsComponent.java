package hr.hrg.jetbrains.webview.settings;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class WebViewSettingsComponent {
    private final JPanel myMainPanel;
    private final JBTextField myPortText = new JBTextField();
    private final JBTextArea myAllowedOriginsText = new JBTextArea(3, 20);

    public WebViewSettingsComponent() {
        myMainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("HTTP Bridge Port: "), myPortText, 1, false)
                .addLabeledComponent(new JBLabel("Allowed Origins (comma-separated): "), myAllowedOriginsText, 1, true)
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
}
