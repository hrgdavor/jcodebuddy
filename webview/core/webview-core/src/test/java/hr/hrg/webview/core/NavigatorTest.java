package hr.hrg.webview.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The funnel: rate limit → resolve → jail → host, in that order, once per request.
 *
 * <p>These are the assertions that make the three hosts comparable. The JetBrains host used to charge
 * the rate limit twice for one click (once in its JCEF path, once in its HTTP handler) and answered
 * {@code 404} both for "no such file" and for "rate limited"; the sidecar had no limit at all. Each of
 * those is a test here.
 */
public class NavigatorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** A host that records what it was asked to do, so "the request reached the host" is observable. */
    private static final class RecordingHost implements EditorHost {
        private final List<String> calls = new ArrayList<>();
        private final Set<String> capabilities;
        private boolean available = true;
        private boolean answer = true;

        RecordingHost(String... capabilities) {
            this.capabilities = Set.of(capabilities);
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Set<String> capabilities() {
            return capabilities;
        }

        @Override
        public boolean openFileAt(String absolutePath, int line, int column) {
            calls.add("open " + absolutePath + ":" + line + ":" + column);
            return answer;
        }

        @Override
        public boolean reveal(String absolutePath) {
            calls.add("reveal " + absolutePath);
            return answer;
        }

        @Override
        public boolean select(String absolutePath, TextRange range) {
            calls.add("select " + absolutePath + " " + range);
            return answer;
        }
    }

    /** A project directory plus the limit a test wants to control. */
    private record Fixture(Path project, RateLimiter limiter) {

        Navigator forHost(EditorHost host, boolean confined) {
            return new Navigator(project.toString(), host, confined, limiter);
        }
    }

    private Fixture fixture() throws IOException {
        return new Fixture(folder.newFolder("proj").toPath(),
                new RateLimiter(2, 1000, new TestClock()));
    }

    @Test
    public void passesAResolvedAbsolutePathToTheHost() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        NavigationOutcome outcome = navigator.open("src/A.java", 14, 3);

        assertEquals(NavigationOutcome.Reason.OK, outcome.reason());
        assertTrue(outcome.succeeded());
        assertEquals(1, host.calls.size());
        String call = host.calls.get(0);
        assertTrue("the host must see an absolute path: " + call,
                call.startsWith("open ") && !call.contains("/proj/../"));
        assertTrue("forward slashes only, so the same string is a usable URL: " + call,
                !call.contains("\\"));
        assertTrue("the line and column must arrive unswapped: " + call,
                call.endsWith("/proj/src/A.java:14:3"));
    }

    @Test
    public void clampsLineAndColumnToOne() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        navigator.open("src/A.java", 0, -5);

        assertTrue(host.calls.get(0).endsWith(":1:1"));
    }

    @Test
    public void refusesOutsideTheProjectOnlyWhenConfigured() throws IOException {
        // One fixture, so the permissive and the strict navigator share a project directory (and a
        // rate-limit budget); the point of the test is the policy, not the budget.
        Fixture fixture = fixture();

        RecordingHost permissiveHost = new RecordingHost(EditorHost.CAP_OPEN);
        assertTrue("an IDE host may open a path the user could open by hand",
                fixture.forHost(permissiveHost, false).open("../outside.txt", 1, 1).succeeded());
        assertEquals(1, permissiveHost.calls.size());

        RecordingHost strictHost = new RecordingHost(EditorHost.CAP_OPEN);
        NavigationOutcome refused = fixture.forHost(strictHost, true).open("../outside.txt", 1, 1);

        assertEquals(NavigationOutcome.Reason.OUTSIDE_PROJECT, refused.reason());
        assertTrue("a refused path never reaches the host", strictHost.calls.isEmpty());
    }

    /**
     * The distinction the old JetBrains handler collapsed into one {@code 404}. A page and a log must be
     * able to tell a broken link from a limit.
     */
    @Test
    public void rateLimitingIsReportedAsItsOwnReason() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        assertTrue(navigator.open("src/A.java", 1, 1).succeeded());
        assertTrue(navigator.open("src/A.java", 1, 1).succeeded());

        NavigationOutcome limited = navigator.open("src/A.java", 1, 1);

        assertEquals(NavigationOutcome.Reason.RATE_LIMITED, limited.reason());
        assertFalse(limited.succeeded());
        assertEquals("a rate-limited request is not forwarded", 2, host.calls.size());
        assertTrue(limited.detail().contains("2 per 1s"));
    }

    /**
     * The limit is acquired before the path is resolved, so a burst of malformed requests cannot be used
     * to make the host do path work for free - they spend the same budget as real clicks.
     */
    @Test
    public void invalidPathsSpendTheRateLimitBudget() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        for (int i = 0; i < 2; i++) {
            assertEquals(NavigationOutcome.Reason.INVALID_PATH, navigator.open("", 1, 1).reason());
        }
        assertEquals("the garbage already used the budget",
                NavigationOutcome.Reason.RATE_LIMITED, navigator.open("src/A.java", 1, 1).reason());
        assertTrue(host.calls.isEmpty());
    }

    @Test
    public void anUnavailableHostIsNotAnErrorInTheLimit() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);
        host.available = false;

        NavigationOutcome outcome = navigator.open("src/A.java", 1, 1);

        assertEquals(NavigationOutcome.Reason.NO_HOST, outcome.reason());
        assertTrue(outcome.detail().contains("no open host is attached"));
        assertTrue("the capabilities a page may assume are empty while no host is attached",
                navigator.capabilities().isEmpty());
    }

    @Test
    public void aHostThatCannotDoTheVerbIsReportedRatherThanCalled() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        NavigationOutcome outcome = navigator.reveal("src/A.java");

        assertEquals(NavigationOutcome.Reason.NO_HOST, outcome.reason());
        assertTrue(outcome.detail().contains("cannot reveal"));
        assertTrue("a verb the host does not implement must never be attempted", host.calls.isEmpty());
    }

    @Test
    public void aHostThatRefusesIsReportedWithItsName() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);
        host.answer = false;

        NavigationOutcome outcome = navigator.open("src/A.java", 1, 1);

        assertEquals(NavigationOutcome.Reason.HOST_REFUSED, outcome.reason());
        assertTrue(outcome.detail().contains("recording"));
    }

    @Test
    public void swappingTheHostSwapsTheCapabilities() throws IOException {
        Navigator navigator = fixture().forHost(NullHost.INSTANCE, false);

        assertTrue(navigator.capabilities().isEmpty());
        assertEquals(NavigationOutcome.Reason.NO_HOST, navigator.open("src/A.java", 1, 1).reason());

        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN, EditorHost.CAP_REVEAL);
        navigator.setHost(host);

        assertSame(host, navigator.host());
        assertEquals(Set.of(EditorHost.CAP_OPEN, EditorHost.CAP_REVEAL), navigator.capabilities());
    }

    @Test
    public void selectsAClampedRange() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_SELECT);
        Navigator navigator = fixture().forHost(host, false);

        navigator.select("src/A.java", TextRange.at(0, 0));

        String call = host.calls.get(0);
        assertTrue(call.startsWith("select "));
        assertTrue("the range is clamped before the host sees it: " + call,
                call.endsWith(TextRange.at(1, 1).toString()));
    }

    @Test
    public void readsALineFromAnLFragments() {
        assertEquals(42, Navigator.lineFromFragment("L42"));
        assertEquals(42, Navigator.lineFromFragment("l42"));
        assertEquals(7, Navigator.lineFromFragment("7"));
        assertEquals(1, Navigator.lineFromFragment(null));
        assertEquals(1, Navigator.lineFromFragment(""));
        assertEquals(1, Navigator.lineFromFragment("L"));
        assertEquals(1, Navigator.lineFromFragment("not-a-line"));
        assertEquals("line 0 is clamped to the first line", 1, Navigator.lineFromFragment("L0"));
        assertEquals(1, Navigator.lineFromFragment("L-3"));
    }

    @Test
    public void opensAUrlUsingItsLineFragment() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        navigator.openUrl("src/A.java#L42");

        assertTrue(host.calls.get(0).endsWith(":42:1"));
    }

    @Test
    public void opensThePathBehindAFileUrl() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        navigator.openUrl("file:///D:/tmp/report.html#L7");

        assertTrue(host.calls.get(0).startsWith("open D:/tmp/report.html:7:1"));
    }

    @Test
    public void rejectsAnEmptyUrl() throws IOException {
        RecordingHost host = new RecordingHost(EditorHost.CAP_OPEN);
        Navigator navigator = fixture().forHost(host, false);

        assertEquals(NavigationOutcome.Reason.INVALID_PATH, navigator.openUrl("  ").reason());
    }

    @Test
    public void theSharedPolicyIsTwentyPerTwentySeconds() {
        assertEquals(20, Navigator.RATE_LIMIT_COUNT);
        assertEquals(20_000L, Navigator.RATE_LIMIT_WINDOW_MS);
    }
}
