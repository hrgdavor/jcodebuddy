package hr.hrg.eclipse.webview.view;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The page the view shows before it has anything better to show: a small, self-contained page
 * that says the host is up and waiting, loaded as a data URL so the view has content from the
 * moment it opens, with no file and no server involved.
 */
public final class SplashPage {

    private SplashPage() {
    }

    /** The data URL the view loads when it opens and when it is asked to reset. */
    public static String url() {
        return "data:text/html;charset=utf-8," + URLEncoder.encode(html(), StandardCharsets.UTF_8);
    }

    private static String html() {
        return "<!DOCTYPE html>\n"
                + "<html><head><meta charset=\"utf-8\"><title>JCodeBuddy WebView</title></head>\n"
                + "<body style=\"font-family: system-ui; margin: 3rem; color: #222;\">\n"
                + "<h1>JCodeBuddy WebView</h1>\n"
                + "<p>The host is up. Open an HTML file from the project — right-click it and\n"
                + "choose \u201cOpen in WebView\u201d — or send a page to the view from a page's\n"
                + "bridge call.</p>\n"
                + "</body></html>\n";
    }
}
