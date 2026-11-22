package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.openapi.wm.ToolWindowFactory;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the two halves of the bug that stopped the plugin from starting at all
 * (plan.reimplement.md finding F8).
 *
 * <p>The failure looked like a missing constructor, so the obvious test asserts that the constructor
 * exists — but the deeper point is that asking for it must not fail. JVM reflective member resolution
 * has to resolve every type in the class's method descriptors, so
 * {@code JcefToolWindowFactory.class.getConstructors()} throws {@code NoClassDefFoundError} when
 * {@code com.intellij.modules.jcef} is on the compile classpath but absent from the runtime one. That
 * is what the platform then reports as "Cannot find suitable constructor".
 *
 * <p>This test therefore fails exactly when the runtime dependency regresses, because the test runtime
 * is assembled with the same {@code platformBundledModules} configuration as the plugin.
 */
public class JcefToolWindowFactoryTest {

    @Test
    public void theConstructorThePlatformLooksForExists() {
        Constructor<?>[] constructors = JcefToolWindowFactory.class.getConstructors();

        assertTrue("the platform instantiates a tool window factory through a no-arg constructor",
                Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0));
        assertEquals("one public constructor is enough; the platform also accepts (CoroutineScope) and "
                        + "(Application) shapes, but a second one would only be dead weight", 1,
                constructors.length);
        assertTrue("it must be public", Modifier.isPublic(constructors[0].getModifiers()));
    }

    @Test
    public void resolvingTheClassDoesNotRequireAMissingPlatformModule() {
        // No try/catch on purpose: a NoClassDefFoundError here is the failure being guarded against.
        Class<?>[] parameterTypes = JcefToolWindowFactory.class.getConstructors()[0].getParameterTypes();

        assertEquals("a no-arg constructor takes no parameter types to resolve", 0, parameterTypes.length);
        assertTrue("the class must still implement the factory interface",
                ToolWindowFactory.class.isAssignableFrom(JcefToolWindowFactory.class));
    }

    /**
     * JCEF reports a failed load of its own initial {@code about:blank} on every browser creation;
     * treating that as a real error would greet the first open with a failure card.
     */
    @Test
    public void jcefInternalUrlsAreNotReportedAsErrors() {
        assertTrue(WebViewPanel.isInternalBrowserUrl(null));
        assertTrue(WebViewPanel.isInternalBrowserUrl(""));
        assertTrue(WebViewPanel.isInternalBrowserUrl("about:blank"));
        assertTrue(WebViewPanel.isInternalBrowserUrl("file:///jbcefbrowser/1851099605#url=about:blank"));

        assertFalse("a real page must still be able to report a load failure",
                WebViewPanel.isInternalBrowserUrl("file:///D:/wrk/page.html"));
        assertFalse(WebViewPanel.isInternalBrowserUrl("https://example.com"));
    }

    @Test
    public void rawSourceUrlsAreNotRestorable() {
        assertTrue(WebViewPanel.isSourceUrl(
                "http://127.0.0.1:18886/source/chat-app/src/main/java/demo/chat/chatapp/ChatAppDb.java?token=x"));
        assertTrue(WebViewPanel.isSourceUrl("http://127.0.0.1:18886/source/README.md"));
        assertFalse(WebViewPanel.isSourceUrl("http://127.0.0.1:18886/app/?openFile=README.md"));
        assertFalse(WebViewPanel.isSourceUrl("http://127.0.0.1:18886/page/report.html"));
        assertFalse(WebViewPanel.isSourceUrl("file:///D:/wrk/page.html"));
    }

    @Test
    public void homeUsesTheBoundBridgePortNoMatterWhichPageIsShowing() {
        assertEquals("http://127.0.0.1:18887/app/index.html",
                WebViewPanel.homeUrl("http://127.0.0.1:18886/app/hipster-ioc.html", 18887));
        assertEquals("http://127.0.0.1:18887/app/index.html",
                WebViewPanel.homeUrl("file:///D:/wrk/README.md", 18887));
        assertEquals("http://127.0.0.1:19000/app/index.html",
                WebViewPanel.homeUrl("http://127.0.0.1:19000/page/report.html", -1));
    }

    @Test
    public void openFileIsReadFromQueryOrHashRouting() {
        assertEquals("README.md", WebViewPanel.openFileOf("http://127.0.0.1:18886/app/?openFile=README.md"));
        assertEquals("src/main/java/A.java",
                WebViewPanel.openFileOf("http://127.0.0.1:18886/app/#openFile=src%2Fmain%2Fjava%2FA.java"));
        assertNull(WebViewPanel.openFileOf("http://127.0.0.1:18886/app/hipster-ioc.html"));
        assertTrue(WebViewPanel.isAppNavigationUrl("http://127.0.0.1:18886/app/#openFile=README.md"));
        assertFalse(WebViewPanel.isAppNavigationUrl("http://127.0.0.1:18886/source/README.md"));
    }
}
