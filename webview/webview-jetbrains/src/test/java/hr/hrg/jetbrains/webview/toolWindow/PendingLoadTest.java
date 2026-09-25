package hr.hrg.jetbrains.webview.toolWindow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The first-page ordering rule.
 *
 * <p>Regression: the factory used to deliver the parked URL and then load its own fallback on top of
 * it, so "Open in WebView Explorer" on a file left the splash page on screen and never rendered the
 * file. These tests pin the rule that makes that impossible: {@link WebViewService#register} delivers a
 * parked URL when there is one and applies the fallback only when there is not.
 */
public class PendingLoadTest {

    private static final String FILE_URL = "file:///D:/wrk/page.html";
    private static final String SPLASH = "data:text/html,splash";

    private final List<String> loaded = new ArrayList<>();
    private final PendingLoad pending = new PendingLoad();

    /**
     * The service's first-page decision, without a platform: deliver the parked URL, else the fallback.
     * Mirrors {@code WebViewService.register(panel, fallbackUrl)}.
     */
    private boolean firstPage(String fallbackUrl) {
        if (pending.deliverTo(loaded::add)) {
            return true;
        }
        loaded.add(fallbackUrl);
        return false;
    }

    @Test
    public void aParkedUrlWinsOverTheFallback() {
        assertTrue(pending.request(FILE_URL));
        assertTrue("the component must be able to see that something is waiting", pending.isWaiting());
        assertEquals(FILE_URL, pending.waitingUrl());

        boolean parkedWon = firstPage(SPLASH);

        assertTrue(parkedWon);
        assertEquals("the parked file must be the only page loaded", List.of(FILE_URL), loaded);
        assertFalse("a delivered URL is not delivered twice", pending.isWaiting());
    }

    @Test
    public void theFallbackIsUsedWhenNothingWasParked() {
        boolean parkedWon = firstPage(SPLASH);

        assertFalse(parkedWon);
        assertEquals(List.of(SPLASH), loaded);
    }

    @Test
    public void blankRequestsAreIgnored() {
        assertFalse(pending.request(null));
        assertFalse(pending.request(""));
        assertFalse(pending.request("   "));
        assertNull(pending.waitingUrl());
        assertFalse("a blank request must not suppress the fallback", firstPage(SPLASH));
        assertEquals(List.of(SPLASH), loaded);
    }

    @Test
    public void aLaterRequestReplacesAnUndeliveredOne() {
        pending.request("first");
        pending.request("second");

        assertEquals("second", pending.waitingUrl());
        firstPage(SPLASH);

        assertEquals(List.of("second"), loaded);
    }

    @Test
    public void anUndeliveredUrlIsForgottenAfterDelivery() {
        // With a panel present the service loads straight into it; nothing is parked, so there is nothing
        // for a later first-page decision to pick up. Guards against a stale URL resurfacing on the next
        // tool window creation.
        pending.request("file:///D:/wrk/other.html");
        assertTrue(pending.deliverTo(loaded::add));

        assertEquals(List.of("file:///D:/wrk/other.html"), loaded);
        assertFalse("a delivered URL is gone", pending.isWaiting());
        assertNull(pending.waitingUrl());

        assertFalse("a second delivery has nothing to deliver", pending.deliverTo(loaded::add));
        assertEquals(1, loaded.size());
    }
}
