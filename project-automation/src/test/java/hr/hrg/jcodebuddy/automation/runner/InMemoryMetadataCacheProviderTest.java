package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.jcodebuddy.meta.MetadataProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * The in-memory provider's two paths agree, and its {@code parse} is the interface's engine-backed default
 * (plan step 3.0j).
 *
 * <p>This is the property DEC-W008 exists for — a consumer must not have to know whether the entry it is reading
 * came from a scan or from one file handed in by hand. The parse itself lives in {@code jcodebuddy-meta} now, over
 * the engine; what this test pins is that this provider does not grow a second one.</p>
 */
class InMemoryMetadataCacheProviderTest {

    private static final String VIEW = """
            package demo.hr;

            import java.util.List;

            public interface PersonSummary {
                String firstName();
                Integer age();
                List<String> tags();
            }
            """;

    @Test
    void whatParseDerivesFromTheBytesIsWhatTheCacheThenHolds() {
        InMemoryMetadataCacheProvider provider = new InMemoryMetadataCacheProvider();
        MetadataProvider.CacheEntry parsed = provider.parse("demo/hr/PersonSummary.java",
                VIEW.getBytes(StandardCharsets.UTF_8));

        provider.put(parsed.hash(), parsed);

        MetadataProvider.CacheEntry fromCache = provider.get(parsed.hash());
        Assertions.assertEquals(parsed, fromCache, "the cache returns the entry parse produced");
        Assertions.assertEquals(parsed, provider.parse("demo/hr/PersonSummary.java",
                        VIEW.getBytes(StandardCharsets.UTF_8)),
                "and parse is pure: the same bytes produce the same entry whether or not a cache exists");
    }

    @Test
    void theParserIsTheInterfacesOwnAndNotASecondOne() {
        // The provider inherits the default rather than overriding it with its own assembly: the facts are the
        // engine's, and this module no longer owns a parser to drift from them.
        InMemoryMetadataCacheProvider provider = new InMemoryMetadataCacheProvider();
        MetadataProvider.CacheEntry inherited = provider.parse("demo/hr/PersonSummary.java",
                VIEW.getBytes(StandardCharsets.UTF_8));
        MetadataProvider.CacheEntry direct =
                hr.hrg.jcodebuddy.meta.IndexMetadataProvider.parseSource("demo/hr/PersonSummary.java",
                        VIEW.getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals(direct, inherited,
                "the provider's parse is the engine-backed one, byte for byte");
    }
}
