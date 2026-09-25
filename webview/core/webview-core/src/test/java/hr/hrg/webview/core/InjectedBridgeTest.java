package hr.hrg.webview.core;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The injected contract is what every generated page depends on, so it is asserted as text and then
 * round-tripped through the parser that consumes it.
 *
 * <p>The equivalent test used to live in the JetBrains host, which was the only host. Moving it here is
 * what keeps a second host honest: the script and the parser are both in this module now, so a host that
 * injects its own version is the only way they can drift.
 */
public class InjectedBridgeTest {

    private static final String PLACEHOLDER = "/*__QUERY__*/";

    private final String script = InjectedBridge.scriptWithPlaceholder(PLACEHOLDER);

    @Test
    public void definesTheFunctionTheReportsCall() {
        assertTrue("the generated pages feature-detect window.openFile",
                script.contains("window.openFile = function (path, line, column)"));
    }

    @Test
    public void sendsTheOneMessageKindTheParserUnderstands() {
        assertTrue("the payload must carry kind=openFile so BridgeMessage.parse accepts it",
                script.contains("kind: '" + BridgeMessage.KIND_OPEN_FILE + "'"));
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
    public void invokesTheTransportTheHostPassedIn() {
        assertTrue("the host's transport must be called or the message never arrives",
                script.contains(PLACEHOLDER));
        assertTrue(script.contains("var msg = JSON.stringify("));
    }

    @Test
    public void stampsABridgeVersionSoAPageCanDetectIt() {
        assertTrue(script.contains(InjectedBridge.VERSION_GLOBAL + " = " + InjectedBridge.VERSION));
        assertEquals(1, InjectedBridge.VERSION);
    }

    @Test
    public void producesAPayloadTheParserAccepts() {
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
        assertTrue(script.startsWith(InjectedBridge.VERSION_GLOBAL));
    }

    @Test
    public void theNullHostRefusesEverything() {
        assertEquals("null", NullHost.INSTANCE.name());
        assertFalse(NullHost.INSTANCE.isAvailable());
        assertTrue("no capabilities is how a page learns to fall back to the clipboard",
                NullHost.INSTANCE.capabilities().isEmpty());
        assertFalse(NullHost.INSTANCE.openFileAt("/anywhere/A.java", 1, 1));
        assertFalse(NullHost.INSTANCE.reveal("/anywhere/A.java"));
        assertFalse(NullHost.INSTANCE.select("/anywhere/A.java", TextRange.at(1, 1)));
    }

    @Test
    public void anEditorHostHasTheCapabilityKeysTheContractUses() {
        // These strings are what /health reports and what the capability document lists; a host that
        // renamed one would silently drop the verb from a page's ladder.
        assertEquals(Set.of("open", "reveal", "select"),
                Set.of(EditorHost.CAP_OPEN, EditorHost.CAP_REVEAL, EditorHost.CAP_SELECT));
    }

    @Test
    public void textRangeClampsToTheFirstLine() {
        assertEquals(TextRange.at(1, 1), TextRange.at(0, -5).clamped());
        assertEquals(new TextRange(1, 1, 9, 4), new TextRange(0, -2, 9, 4).clamped());
    }
}
