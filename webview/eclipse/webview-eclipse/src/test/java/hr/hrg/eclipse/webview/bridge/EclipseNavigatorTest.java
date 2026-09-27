package hr.hrg.eclipse.webview.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The navigator's rules, exercised without the platform: a fake editor host records what it is
 * asked to open, and a frozen clock keeps the rate-limit window closed for the whole test. The
 * root supplier is mutable, which is what lets the no-host refusal be tested without the
 * platform's active page.
 */
class EclipseNavigatorTest {

    @TempDir
    Path root;

    /** A clock that never advances, so every recorded event falls in the same window. */
    private final Clock frozen = () -> 0L;

    private final RecordingHost host = new RecordingHost();

    private EclipseNavigator navigatorWithRoot(String projectRoot) {
        return new EclipseNavigator(() -> projectRoot, host,
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, frozen));
    }

    @Test
    void aRequestWithinBudgetOpens() {
        EclipseNavigator navigator = navigatorWithRoot(forwardSlashes(root));
        NavigationOutcome outcome = navigator.open(pathUnder("a.html"), 1, 1);
        assertTrue(outcome.succeeded());
        assertEquals(1, host.opened.size());
    }

    @Test
    void theTwentyFirstRequestIsRateLimited() {
        EclipseNavigator navigator = navigatorWithRoot(forwardSlashes(root));
        for (int i = 0; i < Navigator.RATE_LIMIT_COUNT; i++) {
            NavigationOutcome outcome = navigator.open(pathUnder("a.html"), 1, 1);
            assertTrue(outcome.succeeded(), "request " + (i + 1) + " should have succeeded");
        }
        NavigationOutcome refused = navigator.open(pathUnder("a.html"), 1, 1);
        assertEquals(NavigationOutcome.Reason.RATE_LIMITED, refused.reason());
        assertEquals(Navigator.RATE_LIMIT_COUNT, host.opened.size());
    }

    @Test
    void aNoHostRefusalSpendsNoBudget() {
        AtomicReference<String> rootRef = new AtomicReference<>(null);
        EclipseNavigator navigator = new EclipseNavigator(rootRef::get, host,
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, frozen));
        NavigationOutcome refused = navigator.open(pathUnder("a.html"), 1, 1);
        assertEquals(NavigationOutcome.Reason.NO_HOST, refused.reason());
        assertTrue(refused.detail().contains("no project is open"));
        assertEquals(0, host.opened.size());

        rootRef.set(forwardSlashes(root));
        NavigationOutcome ok = navigator.open(pathUnder("a.html"), 1, 1);
        assertTrue(ok.succeeded(), "the no-host refusal must not have consumed the budget");
    }

    @Test
    void aPathThatNamesNoFileStillResolves() {
        // The resolver is deliberately lenient about existence: the host decides the outcome,
        // not the resolver, so a path that names no file still reaches the host.
        EclipseNavigator navigator = navigatorWithRoot(forwardSlashes(root));
        NavigationOutcome outcome = navigator.open(pathUnder("no-such-file.html"), 3, 4);
        assertTrue(outcome.succeeded());
    }

    private String forwardSlashes(Path path) {
        return path.toAbsolutePath().toString().replace(File.separatorChar, '/');
    }

    private String pathUnder(String name) {
        return forwardSlashes(root) + "/" + name;
    }

    /** Records each open it is asked to do, and always succeeds, so the navigator's own
     *  rules — not the host — are what the assertions observe. */
    private static final class RecordingHost implements EditorHost {
        final List<String> opened = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<String> capabilities() {
            return Set.of(CAP_OPEN, CAP_SELECT);
        }

        @Override
        public boolean openFileAt(String absolutePath, int line, int column) {
            opened.add(absolutePath + ":" + line + ":" + column);
            return true;
        }
    }
}
