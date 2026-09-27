package hr.hrg.eclipse.webview.prefs;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.PlatformUI;

/**
 * The two preferences this host reads: whether the bridge script is injected into pages at all,
 * and whether the splash page shows when the view opens. Both default to enabled, and a
 * preference the user never touched still reads as its default — so "enabled by default" here
 * means the platform's {@code isDefault} is treated as true, without the reader ever calling
 * {@code setDefault} itself.
 */
public final class WebViewPreferences {

    /** Inject the bridge script into pages; the default is enabled. */
    public static final String BRIDGE_ENABLED_KEY = "hr.hrg.eclipse.webview.bridge.enabled";

    /** Show the splash page when the view opens; the default is enabled. */
    public static final String SPLASH_ON_OPEN_KEY = "hr.hrg.eclipse.webview.splash.onOpen";

    private WebViewPreferences() {
    }

    /** True when the bridge script is injected into pages; the default is true. */
    public static boolean bridgeEnabled() {
        return defaultTrue(BRIDGE_ENABLED_KEY);
    }

    /** True when the splash page shows when the view opens; the default is true. */
    public static boolean splashOnOpen() {
        return defaultTrue(SPLASH_ON_OPEN_KEY);
    }

    /**
     * The platform's store has no read-without-side-effects default: {@code isDefault(key)} is
     * true both when the user never set the key and when they set it to the default, so
     * "default true" reads as {@code isDefault(key) || getBoolean(key)}.
     */
    private static boolean defaultTrue(String key) {
        IPreferenceStore store = PlatformUI.getPreferenceStore();
        return store.isDefault(key) || store.getBoolean(key);
    }
}
