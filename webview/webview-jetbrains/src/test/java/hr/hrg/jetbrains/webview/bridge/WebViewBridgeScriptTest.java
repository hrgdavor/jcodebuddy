package hr.hrg.jetbrains.webview.bridge;

import hr.hrg.webview.core.BridgeMessage;
import hr.hrg.webview.core.InjectedBridge;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The host half of the injected contract: what this plugin contributes is the <em>transport</em>.
 *
 * <p>The contract itself — the function, the message kind, the defaults, the version stamp — lives in
 * {@code webview-core}'s {@code InjectedBridge} and is asserted there, by tests that every host shares.
 * What is left to check here is that this host injects that exact script with its own
 * {@code JBCefJSQuery} invocation as the transport, because a host that quietly kept a private copy of
 * the script is precisely the drift the move to core was meant to make impossible.
 */
public class WebViewBridgeScriptTest {

    private static final String PLACEHOLDER = "/*__QUERY__*/";

    private final String script = WebViewBridge.injectsInto(PLACEHOLDER);

    @Test
    public void injectsTheSharedContractVerbatim() {
        assertEquals("the plugin must inject webview-core's script, byte for byte",
                InjectedBridge.script(PLACEHOLDER), script);
    }

    @Test
    public void definesTheFunctionTheReportsCall() {
        assertTrue("the generated pages feature-detect window.openFile",
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
        assertEquals("the plugin's constant is the core's, so a page cannot see two versions",
                InjectedBridge.VERSION, WebViewBridge.BRIDGE_VERSION);
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
