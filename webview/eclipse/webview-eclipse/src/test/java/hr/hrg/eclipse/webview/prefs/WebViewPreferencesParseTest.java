package hr.hrg.eclipse.webview.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import hr.hrg.webview.core.HostConfig;

/**
 * The port preference's pure half: what counts as "the reader named a port", said with the same
 * vocabulary {@code HostConfig} uses for the project's committed default — one rule, one reporting
 * shape, checked without a workbench.
 */
class WebViewPreferencesParseTest {

    @Test
    void emptyMeansNoChoiceAtAll() {
        HostConfig.PortPreference preference = WebViewPreferences.parsePortSetting("");
        assertFalse(preference.present());
        assertEquals("", preference.problem(), "an unset preference is not a problem");

        HostConfig.PortPreference nullish = WebViewPreferences.parsePortSetting(null);
        assertFalse(nullish.present());
        assertEquals("", nullish.problem());

        HostConfig.PortPreference spaces = WebViewPreferences.parsePortSetting("   ");
        assertFalse(spaces.present());
        assertEquals("", spaces.problem());
    }

    @Test
    void aRealPortIsARealPreference() {
        HostConfig.PortPreference preference = WebViewPreferences.parsePortSetting(" 18883 ");
        assertTrue(preference.present());
        assertEquals(18883, preference.port().getAsInt());
        assertEquals("", preference.problem());
        assertEquals(1, WebViewPreferences.parsePortSetting("1").port().getAsInt(),
                "the floor of the range is legal");
        assertTrue(WebViewPreferences.parsePortSetting("65535").present());
    }

    @Test
    void anythingElseIsAProblemThatKeepsTheBridgeOff() {
        HostConfig.PortPreference word = WebViewPreferences.parsePortSetting("http");
        assertFalse(word.present());
        assertTrue(word.problem().contains("'http' is not a port number"), word.problem());
        assertTrue(word.problem().contains("stays off"), word.problem());

        HostConfig.PortPreference tooBig = WebViewPreferences.parsePortSetting("70000");
        assertFalse(tooBig.present());
        assertTrue(tooBig.problem().contains("70000"), tooBig.problem());

        // 0 is the standalone host's "any port" and not this host's: the bridge has no ephemeral mode.
        assertFalse(WebViewPreferences.parsePortSetting("0").present());
        assertFalse(WebViewPreferences.parsePortSetting("-1").present());
    }
}
