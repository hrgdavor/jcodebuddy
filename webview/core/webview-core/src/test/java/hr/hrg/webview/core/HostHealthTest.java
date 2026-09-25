package hr.hrg.webview.core;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The document a page asks a host "who are you and what can you do" with.
 *
 * <p>Before this class existed each host built its own JSON inline, and they differed: three keys in the
 * sidecar, four in the two IDE hosts, no way for a page to tell an old bridge from a new one, and no way to
 * learn what the host could actually do without probing each verb and watching it fail.
 */
public class HostHealthTest {

    @Test
    public void carriesEveryKeyTheContractNames() {
        HostHealth health = HostHealth.of(HostHealth.PLUGIN_JETBRAINS, 18881, 1, false,
                Set.of(EditorHost.CAP_OPEN));

        String json = health.toJson();

        for (String key : HostHealth.REQUIRED_KEYS) {
            assertTrue("missing key " + key + " in " + json, json.contains("\"" + key + "\":"));
        }
        assertTrue(health.isComplete());
    }

    /** The four original keys keep their names and types: a page may already be reading them. */
    @Test
    public void keepsTheOriginalKeysAndTheirTypes() {
        HostHealth health = HostHealth.of(HostHealth.PLUGIN_SIDECAR, 7979, 0, true, Set.of());

        String json = health.toJson();

        assertTrue(json, json.contains("\"plugin\":\"hr.hrg.watch2.sidecar\""));
        assertTrue(json, json.contains("\"port\":7979"));
        assertTrue(json, json.contains("\"allowedOrigins\":0"));
        assertTrue(json, json.contains("\"tokenRequired\":true"));
    }

    @Test
    public void keysAreInAContractOrderSoTwoHostsAreComparable() {
        String json = HostHealth.of("x", 1, 0, false, Set.of()).toJson();

        assertEquals(HostHealth.REQUIRED_KEYS, HostHealth.keysOf(json));
    }

    @Test
    public void capabilitiesAreSortedSoTheSameAbilitiesProduceTheSameBytes() {
        String one = HostHealth.of("x", 1, 0, false,
                Set.of(EditorHost.CAP_SELECT, EditorHost.CAP_OPEN)).toJson();
        String other = HostHealth.of("x", 1, 0, false,
                Set.of(EditorHost.CAP_OPEN, EditorHost.CAP_SELECT)).toJson();

        assertEquals(one, other);
        assertTrue(one, one.contains("\"capabilities\":[\"open\",\"select\"]"));
    }

    /** An empty capability list is the honest answer for a host with no editor attached. */
    @Test
    public void noCapabilitiesSerialisesAsAnEmptyArray() {
        assertTrue(HostHealth.of("x", 1, 0, false, Set.of()).toJson()
                .contains("\"capabilities\":[]"));
        assertTrue(HostHealth.of("x", 1, 0, false, null).toJson().contains("\"capabilities\":[]"));
    }

    @Test
    public void refusesNonsenseRatherThanReportingIt() {
        rejects(() -> HostHealth.of(null, 1, 0, false, Set.of()));
        rejects(() -> HostHealth.of("  ", 1, 0, false, Set.of()));
        rejects(() -> HostHealth.of("x", -1, 0, false, Set.of()));
        rejects(() -> HostHealth.of("x", 1, -1, false, Set.of()));
    }

    private static void rejects(Runnable build) {
        try {
            build.run();
            fail("a health document that cannot be true must be refused, not published");
        } catch (IllegalArgumentException expected) {
            // as intended
        }
    }

    @Test
    public void escapesAValueThatWouldBreakTheJson() {
        // Not a plugin name anyone would write, but the escape is what keeps the document parseable if one
        // ever is: a quote in a value would otherwise end the string early.
        String json = HostHealth.of("we\"ird\\name", 1, 0, false, Set.of()).toJson();

        assertTrue(json, json.contains("\"plugin\":\"we\\\"ird\\\\name\""));
        assertTrue(json, HostHealth.keysOf(json).contains("plugin"));
    }

    @Test
    public void keysOfIgnoresValuesThatLookLikeKeys() {
        // The capability strings are values; a naive scan would report them as keys.
        String json = HostHealth.of("x", 1, 0, false, Set.of("open")).toJson();

        assertEquals("the capability value must not be reported as a key",
                HostHealth.REQUIRED_KEYS, HostHealth.keysOf(json));
    }

    @Test
    public void sortedKeysMakeTwoHostsDirectlyComparable() {
        String jetbrains = HostHealth.of(HostHealth.PLUGIN_JETBRAINS, 18881, 1, false,
                Set.of(EditorHost.CAP_OPEN, EditorHost.CAP_SELECT)).toJson();
        String vscode = HostHealth.of(HostHealth.PLUGIN_VSCODE, 18882, 1, false,
                Set.of(EditorHost.CAP_OPEN)).toJson();

        assertEquals("two hosts must answer /health with the same shape, whatever their abilities",
                HostHealth.sortedKeysOf(jetbrains), HostHealth.sortedKeysOf(vscode));
        // The serialised order is the contract's order (the four original keys first, then the additive
        // ones), which is deliberately NOT alphabetical - so sortedKeysOf exists to compare documents whose
        // field order is fixed but whose contract order is not sorted.
        assertNotEquals("the contract order is deliberately not alphabetical, which is why sortedKeysOf"
                        + " is a separate step",
                HostHealth.REQUIRED_KEYS, HostHealth.sortedKeysOf(jetbrains));
        assertEquals("comparing sorted keys must still produce the same set as the contract list",
                new java.util.TreeSet<>(HostHealth.REQUIRED_KEYS), new java.util.TreeSet<>(HostHealth.keysOf(jetbrains)));
    }

    @Test
    public void theThreePluginNamesAreDistinctSoAPageCanTellTheHostsApart() {
        assertNotEquals(HostHealth.PLUGIN_JETBRAINS, HostHealth.PLUGIN_VSCODE);
        assertNotEquals(HostHealth.PLUGIN_JETBRAINS, HostHealth.PLUGIN_SIDECAR);
        assertNotEquals(HostHealth.PLUGIN_VSCODE, HostHealth.PLUGIN_SIDECAR);
    }

    @Test
    public void anEmptyOriginListIsReportedAsZeroAndMeansNobodyIsAllowed() {
        HostHealth health = HostHealth.of("x", 18881, AllowedOrigins.of(null).values().size(), false,
                Set.of());

        assertTrue(health.toJson(), health.toJson().contains("\"allowedOrigins\":0"));
        assertTrue("zero allowed origins is the closed default a page must be able to see",
                AllowedOrigins.of(null).isEmpty());
    }
}
