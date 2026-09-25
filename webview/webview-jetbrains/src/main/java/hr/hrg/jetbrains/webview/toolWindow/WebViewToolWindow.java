package hr.hrg.jetbrains.webview.toolWindow;

/**
 * The identifiers that tie the plugin's pieces together.
 *
 * <p>{@link #ID} must match the {@code id} attribute of the {@code toolWindow} extension in
 * {@code META-INF/plugin.xml}; it lives here so the action, the service and the factory all name it
 * once instead of repeating a string literal.
 */
public final class WebViewToolWindow {

    public static final String ID = "WebView Explorer";

    private WebViewToolWindow() {
    }
}
