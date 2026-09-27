package hr.hrg.eclipse.webview.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;

/**
 * Attempts the one engine this host can use: SWT's Edge browser, which is Microsoft's WebView2
 * runtime. The result is an {@link Engine}: the browser when it is usable, and — when it is not —
 * the problem the platform reported, which the view shows instead of a dead pane.
 */
public final class BrowserFactory {

    /**
     * The outcome of the attempt: the browser or null, the engine's self-reported type, and the
     * problem that makes it unusable, or null when there is none.
     */
    public record Engine(Browser browser, String browserType, String problem) {
        /** Usable when the browser exists, reports no problem, and is the Edge engine. */
        public boolean supported() {
            return browser != null && problem == null && "edge".equals(browserType);
        }
    }

    private BrowserFactory() {
    }

    /** Creates the browser in {@code parent}, or reports why it could not. */
    public static Engine create(Composite parent) {
        try {
            Browser browser = new Browser(parent, SWT.EDGE);
            return new Engine(browser, browser.getBrowserType(), null);
        } catch (SWTException e) {
            // A missing WebView2 runtime (or the engine being unavailable) surfaces as an
            // SWTException from the constructor — the only reliable signal SWT gives.
            return new Engine(null, "edge", e.getMessage());
        }
    }
}
