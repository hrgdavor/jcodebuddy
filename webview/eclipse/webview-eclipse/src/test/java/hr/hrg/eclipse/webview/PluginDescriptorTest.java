package hr.hrg.eclipse.webview;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The plugin.xml descriptor, read as text: the view, the preference page, the command and its
 * handler, the key, and the menu's HTML-only visibility. No platform is needed for any of it,
 * so the test fails the build if the descriptor drifts from the classes it points at.
 */
class PluginDescriptorTest {

    private static final String VIEW_ID = "hr.hrg.eclipse.webview.views.WebView";
    private static final String VIEW_CLASS = "hr.hrg.eclipse.webview.view.WebViewPart";
    private static final String PREF_CLASS = "hr.hrg.eclipse.webview.prefs.WebViewPreferencePage";
    private static final String COMMAND_ID = "hr.hrg.eclipse.webview.commands.openInWebView";
    private static final String HANDLER_CLASS = "hr.hrg.eclipse.webview.actions.OpenInWebViewAction";
    private static final String KEY_SEQUENCE = "M1+ALT+SHIFT+W";
    private static final String MENU_LOCATION = "menu:org.eclipse.ui.navigator.resourceMenu";

    private static String descriptor() {
        try {
            return Files.readString(Path.of("src/main/resources/plugin.xml"));
        } catch (IOException e) {
            fail("plugin.xml is missing: " + e.getMessage());
            return null;
        }
    }

    @Test
    void theViewIsDeclared() {
        String xml = descriptor();
        assertTrue(xml.contains("id=\"" + VIEW_ID + "\""), "the view id is missing");
        assertTrue(xml.contains("class=\"" + VIEW_CLASS + "\""), "the view class is missing");
        assertTrue(xml.contains("allowMultiple=\"true\""), "the view must allow multiple instances");
    }

    @Test
    void thePreferencePageIsDeclared() {
        String xml = descriptor();
        assertTrue(xml.contains("point=\"org.eclipse.ui.preferencePages\""),
                "the preference page extension is missing");
        assertTrue(xml.contains("class=\"" + PREF_CLASS + "\""), "the preference page class is missing");
    }

    @Test
    void theCommandAndItsHandlerAreDeclared() {
        String xml = descriptor();
        assertTrue(xml.contains("id=\"" + COMMAND_ID + "\""), "the command id is missing");
        assertTrue(xml.contains("class=\"" + HANDLER_CLASS + "\""), "the handler class is missing");
        assertTrue(xml.contains("commandId=\"" + COMMAND_ID + "\""),
                "the handler is not bound to the command");
    }

    @Test
    void theKeyIsDeclared() {
        String xml = descriptor();
        assertTrue(xml.contains("sequence=\"" + KEY_SEQUENCE + "\""), "the key sequence is missing");
    }

    @Test
    void theMenuShowsTheCommandOnlyForHtmlFiles() {
        String xml = descriptor();
        assertTrue(xml.contains("locationID=\"" + MENU_LOCATION + "\""), "the menu location is missing");
        assertTrue(xml.contains("osgi:property=\"org.eclipse.core.resources.extension\""),
                "the extension test is missing");
        assertTrue(xml.contains("osgi:value=\"html\""), "the html extension test is missing");
        assertTrue(xml.contains("osgi:value=\"htm\""), "the htm extension test is missing");
        assertTrue(xml.contains("<visibleWhen>"), "the visibility condition is missing");
    }
}
