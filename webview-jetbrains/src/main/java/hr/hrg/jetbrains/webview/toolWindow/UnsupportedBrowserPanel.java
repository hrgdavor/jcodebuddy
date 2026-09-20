package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;

/**
 * What the tool window shows when there is no browser to show: JCEF is unavailable in this runtime, or
 * the browser could not be created. Replaces the previous bare label so the reason is on screen.
 */
final class UnsupportedBrowserPanel {

    private final JPanel panel = new JPanel(new BorderLayout());

    UnsupportedBrowserPanel() {
        this(null);
    }

    UnsupportedBrowserPanel(@Nullable Throwable cause) {
        String message = "<html><body style='width:420px;text-align:center'>"
                + "<b>WebView Explorer is unavailable.</b><br><br>"
                + "This IDE runtime does not provide JCEF (the embedded Chromium browser), "
                + "so the embedded webview cannot be created."
                + (cause == null ? "" : "<br><br><code>" + escape(cause.toString()) + "</code>")
                + "</body></html>";

        JBLabel label = new JBLabel(message);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setForeground(UIUtil.getLabelForeground());
        label.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        panel.add(label, BorderLayout.CENTER);
    }

    @NotNull JComponent getComponent() {
        return panel;
    }

    private static @NotNull String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
