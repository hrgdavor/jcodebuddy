package hr.hrg.jetbrains.webview;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Asserts the plugin descriptor declares the JCEF module.
 *
 * <p>This guards the one-line regression that made the whole plugin unusable
 * (plan.reimplement.md finding F8): {@code platformBundledModules} in {@code gradle.properties}
 * configures the compile classpath only, and without a matching {@code <depends>} the JCEF classes are
 * missing at runtime, which the platform reports as a missing constructor.
 */
public class PluginDescriptorTest {

    /** The committed descriptor, which is the source of truth. */
    private static String sourceDescriptor() throws Exception {
        Path path = Path.of("src", "main", "resources", "META-INF", "plugin.xml");
        assertTrue("the committed descriptor must exist at " + path.toAbsolutePath(), Files.exists(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** The processed descriptor as it lands in the jar, with patchPluginXml's additions applied. */
    private static String processedDescriptor() throws Exception {
        try (InputStream stream = PluginDescriptorTest.class.getClassLoader()
                .getResourceAsStream("META-INF/plugin.xml")) {
            assertNotNull("META-INF/plugin.xml must be on the runtime classpath", stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void declaresTheJcefRuntimeDependency() throws Exception {
        assertTrue("without this, every JCEF type is unresolvable and the tool window factory reports "
                        + "'Cannot find suitable constructor'",
                sourceDescriptor().contains("<depends>com.intellij.modules.jcef</depends>"));
    }

    @Test
    public void declaresThePlatformDependency() throws Exception {
        assertTrue(sourceDescriptor().contains("<depends>com.intellij.modules.platform</depends>"));
    }

    @Test
    public void registersTheServicesThePluginLooksUp() throws Exception {
        String xml = sourceDescriptor();

        for (String service : new String[]{
                "hr.hrg.jetbrains.webview.services.PluginStateService",
                "hr.hrg.jetbrains.webview.services.HttpBridgeService",
                "hr.hrg.jetbrains.webview.bridge.NavigatorService",
                "hr.hrg.jetbrains.webview.toolWindow.WebViewService"}) {
            assertTrue(service + " must be registered as a project service",
                    xml.contains("serviceImplementation=\"" + service + "\""));
        }
    }

    @Test
    public void registersTheToolWindowAndItsActions() throws Exception {
        String xml = sourceDescriptor();

        assertTrue("the tool window id is what WebViewToolWindow.ID names",
                xml.contains("id=\"WebView Explorer\""));
        assertTrue(xml.contains("hr.hrg.jetbrains.webview.toolWindow.JcefToolWindowFactory"));
        assertTrue(xml.contains("hr.hrg.jetbrains.webview.actions.ToggleToolWindowAction"));
        assertTrue(xml.contains("hr.hrg.jetbrains.webview.actions.OpenFileInWebViewAction"));
    }

    @Test
    public void theSourceDescriptorDoesNotRepeatWhatGradlePatchesIn() throws Exception {
        String xml = sourceDescriptor();

        // id, name, vendor, description, change-notes and idea-version come from the pluginConfiguration
        // block in build.gradle.kts; repeating them here would silently fight with patchPluginXml.
        assertTrue("id is patched in by patchPluginXml", !xml.contains("<id>"));
        assertTrue("name is patched in by patchPluginXml", !xml.contains("<name>"));
        assertTrue("vendor is patched in by patchPluginXml", !xml.contains("<vendor"));
    }

    @Test
    public void patchPluginXmlActuallyAddsTheIdentityFields() throws Exception {
        String xml = processedDescriptor();

        assertTrue("the processed descriptor must carry the plugin id",
                xml.contains("<id>hr.hrg.jetbrains.webview</id>"));
        assertTrue("the processed descriptor must carry the plugin name",
                xml.contains("<name>WebView Explorer</name>"));
        assertTrue("the processed descriptor must carry a vendor, not the template's 'JetBrains'",
                xml.contains("<vendor"));
        assertTrue("since-build must come from the pluginSinceBuild property",
                xml.contains("since-build=\"262\""));
        assertTrue("until-build is deliberately left open",
                !xml.contains("until-build"));
    }
}
