package hr.hrg.eclipse.webview.prefs;

import java.util.OptionalInt;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.PlatformUI;

import hr.hrg.webview.core.HostConfig;

/**
 * The preferences this host reads: whether the bridge script is injected into pages at all, whether
 * the splash page shows when the view opens, which port the HTTP bridge asks for, and an explicit
 * token. The booleans default to enabled, and a preference the reader never touched still reads as
 * its default — "default true" here means the platform's {@code isDefault} is treated as true,
 * without the reader ever calling {@code setDefault} itself.
 *
 * <p>The port and the token default to <em>empty</em>, and empty means something: the bridge stays
 * off until a port is named (E17), and the token, when nobody names one, is generated into the
 * project's own state file once and reused (E16). These two settings live here rather than beside
 * {@code conf/webview.json} because the resolution chain demands an explicit head — this checkout's
 * current port and the project's committed default are the later links, and a user-home setting is
 * not a link at all: a port names a socket for one served directory.
 */
public final class WebViewPreferences {

    /** Inject the bridge script into pages; the default is enabled. */
    public static final String BRIDGE_ENABLED_KEY = "hr.hrg.eclipse.webview.bridge.enabled";

    /** Show the splash page when the view opens; the default is enabled. */
    public static final String SPLASH_ON_OPEN_KEY = "hr.hrg.eclipse.webview.splash.onOpen";

    /** The port the HTTP bridge asks for; empty leaves the bridge off. Local state, never committed. */
    public static final String PORT_KEY = "hr.hrg.eclipse.webview.port";

    /**
     * The token a caller must present; empty generates one into
     * {@code .jcodebuddy/webview/token} and keeps it there. This is the only token setting (E16).
     */
    public static final String TOKEN_KEY = "hr.hrg.eclipse.webview.token";

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
     * The port the reader named, in the core's own preference shape: an empty setting is no choice at
     * all, and anything the bridge cannot use is a problem to log rather than a number to guess at.
     * {@code 1} is as legal here as anywhere; the "0 means ephemeral" convention is the standalone
     * host's, and this host has no ephemeral mode — it has off.
     */
    public static HostConfig.PortPreference bridgePort() {
        return parsePortSetting(store().getString(PORT_KEY));
    }

    /** The explicit token, or an empty string: the empty string is "generate one and keep it". */
    public static String tokenOverride() {
        return store().getString(TOKEN_KEY).trim();
    }

    /**
     * The port setting as a preference, pure: the same shapes {@code HostConfig} uses for the
     * project's committed default, so "the IDE named 18883" and "the project's file names 18883"
     * are validated by one rule and reported with one vocabulary.
     */
    public static HostConfig.PortPreference parsePortSetting(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return new HostConfig.PortPreference(OptionalInt.empty(), "");
        }
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return new HostConfig.PortPreference(OptionalInt.empty(),
                    "'" + trimmed + "' is not a port number; the bridge stays off");
        }
        if (value < 1 || value > 65535) {
            return new HostConfig.PortPreference(OptionalInt.empty(),
                    "the bridge port must be between 1 and 65535, was " + value + "; the bridge stays off");
        }
        return new HostConfig.PortPreference(OptionalInt.of(value), "");
    }

    /**
     * The platform's store has no read-without-side-effects default: {@code isDefault(key)} is
     * true both when the user never set the key and when they set it to the default, so
     * "default true" reads as {@code isDefault(key) || getBoolean(key)}.
     */
    private static boolean defaultTrue(String key) {
        IPreferenceStore store = store();
        return store.isDefault(key) || store.getBoolean(key);
    }

    private static IPreferenceStore store() {
        return PlatformUI.getPreferenceStore();
    }
}
