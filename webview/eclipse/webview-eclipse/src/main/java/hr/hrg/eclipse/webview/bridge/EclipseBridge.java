package hr.hrg.eclipse.webview.bridge;

import hr.hrg.webview.core.BridgeMessage;
import hr.hrg.webview.core.InjectedBridge;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;

/**
 * The page-to-IDE bridge for the Eclipse host. It evaluates the core's frozen script into the
 * {@link Browser} with the transport this host uses; a message the script sends is parsed with the
 * core's parser and handed to the navigator, whose rules — not this class — decide the outcome.
 *
 * <p>The script is re-injected on every page modification, because a navigation that replaces the
 * page's document clears whatever the page held before it, bridge script included.
 */
public class EclipseBridge {

    /** The function the page script calls: one string argument. */
    public static final String FUNCTION = "jcbBridge";

    /** The transport statement: the script sends its argument verbatim to that function. */
    public static final String TRANSPORT = "jcbBridge(msg)";

    private final Browser browser;
    private final EclipseNavigator navigator;
    private final BrowserFunction function;

    public EclipseBridge(Browser browser, EclipseNavigator navigator) {
        this.browser = browser;
        this.navigator = navigator;
        // SWT 3.135 has no addBrowserFactory-style registration: the BrowserFunction constructor
        // registers the function with the browser itself.
        this.function = new BrowserFunction(browser, FUNCTION) {
            @Override
            public Object function(Object[] args) {
                return handle(args == null || args.length == 0 ? null : args[0].toString());
            }
        };
    }

    /**
     * The script to evaluate into the page: the core's frozen script for this host's transport.
     * Kept static and pure so the tests can compare it byte-for-byte with the core's output.
     */
    public static String script() {
        return InjectedBridge.script(TRANSPORT);
    }

    /** Evaluates the script into the browser, if it still exists. */
    public void inject() {
        if (!browser.isDisposed()) {
            browser.evaluate(script());
        }
    }

    /**
     * Handles one message from the page. Returns whether the navigation succeeded, which the
     * function reports back to the page as the result of the call.
     */
    boolean handle(String message) {
        BridgeMessage parsed = BridgeMessage.parse(message);
        if (parsed == null || !BridgeMessage.KIND_OPEN_FILE.equals(parsed.kind())) {
            return false;
        }
        return navigator.open(parsed.filePath(), parsed.safeLine(), parsed.safeColumn()).succeeded();
    }

    /** Unregisters the function; called from the view when it disposes. */
    public void dispose() {
        function.dispose();
    }
}
