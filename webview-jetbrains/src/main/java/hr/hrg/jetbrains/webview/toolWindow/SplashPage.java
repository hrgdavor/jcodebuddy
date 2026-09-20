package hr.hrg.jetbrains.webview.toolWindow;

import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The page shown the first time the tool window is opened, before any file has been visited.
 *
 * <p>It is served as a {@code data:} URL so it needs no resource file and no request handler, and so the
 * address bar shows something recognisable. A data URL cannot carry a {@code #} fragment for the caret,
 * but it is not a source file, so no report link ever targets it.
 */
final class SplashPage {

    /** A URL that loads {@link #html()}. */
    static final String URL =
            "data:text/html;charset=utf-8," + URLEncoder.encode(html(), StandardCharsets.UTF_8).replace("+", "%20");

    private SplashPage() {
    }

    static @NotNull String html() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <title>WebView Explorer</title>
                  <style>
                    body { font-family: sans-serif; padding: 24px; line-height: 1.5; }
                    h2 { margin-top: 0; }
                    code { background: rgba(127,127,127,0.15); padding: 1px 4px; border-radius: 3px; }
                    ul { padding-left: 20px; }
                  </style>
                </head>
                <body>
                  <h2>WebView Explorer</h2>
                  <p>Type an address or a file path above, or open a page from the IDE:</p>
                  <ul>
                    <li>Right-click an <code>.html</code> file and choose <b>Open in WebView Explorer</b>.</li>
                    <li>Press <b>Ctrl+Alt+Shift+W</b> to toggle this tool window.</li>
                    <li>Open the generated entity index at
                        <code>.jcodebuddy/metadata/entity/index.html</code> and click a field to jump to
                        its source line.</li>
                  </ul>
                  <p>Pages opened here get <code>window.openFile(path, line, column)</code> injected, which
                  is how a report tells the IDE where to put the caret.</p>
                </body>
                </html>
                """;
    }
}
