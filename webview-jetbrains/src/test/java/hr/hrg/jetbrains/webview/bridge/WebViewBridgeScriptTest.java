package hr.hrg.jetbrains.webview.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The injected contract is what the generated HTML reports depend on
 * ({@code scripts/entity-html/render.js} calls {@code window.openFile}), so it is asserted as text
 * rather than only exercised through a browser.
 */
public class WebViewBridgeScriptTest {

    private static final String PLACEHOLDER = "/*__QUERY__*/";

    private final String script = WebViewBridge.injectsInto(PLACEHOLDER);

    @Test
    public void definesTheFunctionTheReportsCall() {
        assertTrue("render.js feature-detects window.openFile",
                script.contains("window.openFile = function (path, line, column)"));
    }

    @Test
    public void sendsTheOneMessageKindThePluginUnderstands() {
        assertTrue("the payload must carry kind=openFile so BridgeMessage.parse accepts it",
                script.contains("kind: 'openFile'"));
        assertTrue(script.contains("filePath: path"));
    }

    @Test
    public void defaultsLineAndColumnLikeTheParser() {
        // The Java side defaults a missing position to 1; the script must agree, or a link without a
        // line would land somewhere different depending on which fallback fired.
        assertTrue(script.contains("line: line || 1"));
        assertTrue(script.contains("column: column || 1"));
    }

    @Test
    public void invokesTheInjectedQueryPlaceholder() {
        assertTrue("the JBCefJSQuery.inject(...) result must be called for the message to reach Java",
                script.contains(PLACEHOLDER));
        assertTrue(script.contains("var msg = JSON.stringify("));
    }

    @Test
    public void stampsABridgeVersionSoAPageCanDetectIt() {
        assertTrue(script.contains("window.__jcbWebViewBridge = " + WebViewBridge.BRIDGE_VERSION));
    }

    @Test
    public void producesAPayloadTheParserAccepts() {
        // Keep the two ends of the wire in step: build the message with the same keys the script
        // builds it with, and feed it to the parser.
        String payload = "{\"kind\":\"openFile\",\"filePath\":\"C:/work/A.java\",\"line\":7,\"column\":3}";

        BridgeMessage message = BridgeMessage.parse(payload);

        assertNotNull("the script's payload shape must round-trip through BridgeMessage.parse", message);
        assertEquals("C:/work/A.java", message.filePath());
        assertEquals(7, message.line());
        assertEquals(3, message.column());
    }

    @Test
    public void isIdempotentSoAReloadCannotStackClosures() {
        // The script assigns both globals rather than wrapping them, so re-running it after a reload
        // replaces the previous bindings instead of chaining them.
        assertFalse("a wrapping definition would accumulate one closure per load",
                script.contains("var previousOpenFile"));
        assertTrue(script.startsWith("window.__jcbWebViewBridge"));
    }
}
