package hr.hrg.eclipse.webview.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import hr.hrg.webview.core.BridgeMessage;
import hr.hrg.webview.core.InjectedBridge;

import org.junit.jupiter.api.Test;

/**
 * The bridge's script, compared against the core: the same frozen script the other hosts get,
 * bound to this host's transport, carrying the version global a page reads. This test is the
 * record that this host's script and the core's do not drift — if the core's script changes,
 * the equality here fails the build.
 */
class EclipseBridgeScriptTest {

    @Test
    void theScriptIsTheCoresScriptForThisTransport() {
        assertEquals(InjectedBridge.script(EclipseBridge.TRANSPORT), EclipseBridge.script());
    }

    @Test
    void theScriptBindsTheTransportToTheMessage() {
        Matcher matcher = Pattern.compile("jcbBridge\\(([^)]*)\\)").matcher(EclipseBridge.script());
        assertTrue(matcher.find(), "the transport call is missing from the script");
        assertEquals("msg", matcher.group(1));
    }

    @Test
    void theScriptCarriesTheVersionGlobal() {
        assertTrue(EclipseBridge.script().contains("window.__jcbWebViewBridge = 1"),
                "the version global is missing from the script");
    }

    @Test
    void aMessageWithATrickyPathParsesAndRoundTrips() {
        // The path itself carries the old regex's poison: a comma, a quoted "line" key and
        // digits. The JSON parser must not mistake any of it for the message's own fields.
        String payload = "{\"kind\":\"openFile\",\"filePath\":\"C:/proj/a,\\\"line\\\": 99/b.html\",\"line\":42,\"column\":7}";
        BridgeMessage message = BridgeMessage.parse(payload);
        assertEquals("C:/proj/a,\"line\": 99/b.html", message.filePath());
        assertEquals(42, message.line());
        assertEquals(7, message.column());

        JsonObject roundTrip = new Gson().toJsonTree(message).getAsJsonObject();
        assertEquals(BridgeMessage.KIND_OPEN_FILE, roundTrip.get("kind").getAsString());
        assertEquals("C:/proj/a,\"line\": 99/b.html", roundTrip.get("filePath").getAsString());
        assertEquals(42, roundTrip.get("line").getAsInt());
        assertEquals(7, roundTrip.get("column").getAsInt());
    }
}
