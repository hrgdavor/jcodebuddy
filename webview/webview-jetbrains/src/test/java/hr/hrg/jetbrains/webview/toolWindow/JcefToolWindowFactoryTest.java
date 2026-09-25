package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.openapi.wm.ToolWindowFactory;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
