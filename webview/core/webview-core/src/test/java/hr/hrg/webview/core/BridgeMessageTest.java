package hr.hrg.webview.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ported from {@code webview-jetbrains}' {@code BridgeMessageTest}. The hostile-payload cases are the
 * reason this parser exists: the first implementation read {@code line} out of the raw text with a
 * regular expression, so a path containing {@code ","line": 99} changed which line opened.
 */
public class BridgeMessageTest {

    @Test
    public void parsesTheInjectedShape() {
        BridgeMessage message = BridgeMessage.parse(
                "{\"kind\":\"openFile\",\"filePath\":\"C:/work/A.java\",\"line\":42,\"column\":7}");

        assertNotNull(message);
        assertEquals(BridgeMessage.KIND_OPEN_FILE, message.kind());
        assertEquals("C:/work/A.java", message.filePath());
        assertEquals(42, message.line());
        assertEquals(7, message.column());
    }

    @Test
    public void survivesAPathThatLookedLikeTheOldWireFormat() {
        String hostile = "{\"kind\":\"openFile\",\"filePath\":\"C:/tmp/a\\\", \\\"line\\\": 99, \\\"b.java\",\"line\":1}";

        BridgeMessage message = BridgeMessage.parse(hostile);

        assertNotNull("a quote and a fake line field inside the path must not break parsing", message);
        assertEquals("C:/tmp/a\", \"line\": 99, \"b.java", message.filePath());
        assertEquals(1, message.line());
    }

    @Test
    public void preservesAwkwardPaths() {
        String path = "D:\\wrk\\java\\jcodebuddy\\hipster-entity-example\\.jcodebuddy\\metadata\\entity\\index.html";
        BridgeMessage message = BridgeMessage.parse(
                "{\"kind\":\"openFile\",\"filePath\":\"" + path.replace("\\", "\\\\") + "\",\"line\":12,\"column\":1}");

        assertNotNull(message);
        assertEquals(path, message.filePath());
    }

    @Test
    public void defaultsMissingPosition() {
        BridgeMessage message = BridgeMessage.parse("{\"kind\":\"openFile\",\"filePath\":\"A.java\"}");

        assertNotNull(message);
        assertEquals(1, message.line());
        assertEquals(1, message.column());
    }

    @Test
    public void clampsBadPositions() {
        BridgeMessage message = BridgeMessage.parse(
                "{\"kind\":\"openFile\",\"filePath\":\"A.java\",\"line\":\"not-a-number\",\"column\":-5}");

        assertNotNull(message);
        assertEquals(1, message.line());
        assertEquals("a negative column is clamped to 1", 1, message.safeColumn());
    }

    @Test
    public void clampsZeroAndNegativeLines() {
        BridgeMessage message = BridgeMessage.parse(
                "{\"kind\":\"openFile\",\"filePath\":\"A.java\",\"line\":0,\"column\":0}");

        assertNotNull(message);
        assertEquals(1, message.safeLine());
        assertEquals(1, message.safeColumn());
    }

    @Test
    public void rejectsGarbage() {
        assertNull(BridgeMessage.parse(null));
        assertNull(BridgeMessage.parse(""));
        assertNull(BridgeMessage.parse("   "));
        assertNull(BridgeMessage.parse("not json at all"));
        assertNull(BridgeMessage.parse("{\"kind\":\"openFile\",\"filePath\":\"A.java\""));
        assertNull(BridgeMessage.parse("[1,2,3]"));
        assertNull(BridgeMessage.parse("\"just a string\""));
    }

    @Test
    public void rejectsIncompleteMessages() {
        assertNull(BridgeMessage.parse("{\"filePath\":\"A.java\"}"));
        assertNull(BridgeMessage.parse("{\"kind\":\"\",\"filePath\":\"A.java\"}"));
        assertNull(BridgeMessage.parse("{\"kind\":\"openFile\"}"));
        assertNull(BridgeMessage.parse("{\"kind\":\"openFile\",\"filePath\":\"\"}"));
        assertNull(BridgeMessage.parse("{\"kind\":\"openFile\",\"filePath\":\"   \"}"));
    }

    @Test
    public void ignoresUnknownKinds() {
        String future = "{\"kind\":\"revealInProjectView\",\"filePath\":\"A.java\"}";

        assertNull("only openFile is actionable today", BridgeMessage.parse(future));
        assertTrue("the caller must be able to tell 'unknown kind' from 'malformed'",
                BridgeMessage.looksLikeAMessage(future));
        assertFalse(BridgeMessage.looksLikeAMessage("not json"));
    }
}
