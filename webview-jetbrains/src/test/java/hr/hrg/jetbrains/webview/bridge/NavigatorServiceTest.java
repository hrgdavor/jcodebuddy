package hr.hrg.jetbrains.webview.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The {@code #L42} fragment a report link may carry. */
public class NavigatorServiceTest {

    @Test
    public void readsALineFromAnLFragments() {
        assertEquals(42, NavigatorService.lineFromFragment("L42"));
        assertEquals(42, NavigatorService.lineFromFragment("l42"));
    }

    @Test
    public void readsAPlainNumber() {
        assertEquals(7, NavigatorService.lineFromFragment("7"));
    }

    @Test
    public void fallsBackToOneForAnythingElse() {
        assertEquals(1, NavigatorService.lineFromFragment(null));
        assertEquals(1, NavigatorService.lineFromFragment(""));
        assertEquals(1, NavigatorService.lineFromFragment("L"));
        assertEquals(1, NavigatorService.lineFromFragment("not-a-line"));
        assertEquals("line 0 is clamped to the first line", 1, NavigatorService.lineFromFragment("L0"));
        assertEquals(1, NavigatorService.lineFromFragment("L-3"));
    }
}
