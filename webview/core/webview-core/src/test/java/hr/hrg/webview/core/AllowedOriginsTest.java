package hr.hrg.webview.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The allow-list is the only thing standing between a random page in the user's browser and their
 * editor, so its empty case is asserted as loudly as its matching case.
 *
 * <p>Ported from {@code webview-jetbrains}' {@code AllowedOriginsTest} when the class moved into this
 * module; the assertions are the same, because the behaviour was already right there and must not
 * change in the move.
 */
public class AllowedOriginsTest {

    @Test
    public void emptyAllowListDeniesEverything() {
        AllowedOrigins none = AllowedOrigins.of(null);
        assertTrue(none.isEmpty());
        assertFalse(none.allows("http://localhost:3000"));
        assertFalse(none.allows("file://"));
        assertFalse(none.allows(null));

        AllowedOrigins blank = AllowedOrigins.of("   ,  , ");
        assertTrue("a list of empty entries is still an empty list", blank.isEmpty());
        assertFalse(blank.allows("http://localhost:3000"));
    }

    @Test
    public void allowsConfiguredOrigins() {
        AllowedOrigins origins = AllowedOrigins.of("http://localhost:3000");

        assertTrue(origins.allows("http://localhost:3000"));
        assertTrue(origins.allows("HTTP://LocalHost:3000"));
        assertTrue(origins.allows("http://localhost:3000/"));
        assertFalse(origins.allows("http://localhost:3001"));
    }

    @Test
    public void parsesSeveralOrigins() {
        AllowedOrigins origins = AllowedOrigins.of("http://localhost:3000, file://, https://example.com");

        assertEquals(3, origins.values().size());
        assertTrue(origins.allows("http://localhost:3000"));
        assertTrue(origins.allows("file://"));
        assertTrue(origins.allows("https://example.com"));
        assertFalse(origins.allows("https://evil.com"));
    }

    @Test
    public void doesNotMatchSuffixes() {
        AllowedOrigins origins = AllowedOrigins.of("http://localhost:3000");

        assertFalse("a prefix/suffix match would hand the bridge to any host that reads the setting",
                origins.allows("http://localhost:3000.evil.com"));
        assertFalse(origins.allows("http://evil.com/http://localhost:3000"));
    }

    @Test
    public void deniesMissingOrigin() {
        AllowedOrigins origins = AllowedOrigins.of("http://localhost:3000");

        assertFalse(origins.allows(null));
        assertFalse(origins.allows(""));
        assertFalse(origins.allows("   "));
    }

    /**
     * The case that made this class worth sharing: a request with <b>no</b> {@code Origin} header. The
     * VS Code host's bridge checked the allow-list only when an origin was present, so an absent header
     * — which is what a {@code file://} page and a non-browser client send — walked straight through.
     */
    @Test
    public void anAbsentOriginIsDeniedNotIgnored() {
        AllowedOrigins configured = AllowedOrigins.of("http://localhost:3000");

        assertFalse("an absent Origin is a denial, never a skip", configured.allows(null));
    }
}
