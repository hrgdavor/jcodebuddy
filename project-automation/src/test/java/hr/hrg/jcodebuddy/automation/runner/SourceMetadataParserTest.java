package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * DEC-W008's {@code parse}: file-scoped metadata from source bytes, with no cache involved.
 *
 * <p>The properties asserted here are the ones that make the method usable in a fresh checkout — that it
 * is a function of its two arguments, that the checksum is stable across line endings, that a broken file
 * is reported rather than invented, and that it agrees with the cache-backed path on the same content. A
 * test that only checked "the class name came back" would pass for an implementation that quietly read the
 * filesystem or a stale entry.</p>
 */
class SourceMetadataParserTest {

    private static final String VIEW = """
            package demo.hr;

            import java.util.List;

            public interface PersonSummary {
                String firstName();
                Integer age();
                List<String> tags();
            }
            """;

    private static MetadataProvider.CacheEntry parse(String path, String text) {
        return SourceMetadataParser.parse(path, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aFileScopedEntryCarriesItsIdentityItsTypeAndItsOwnFacts() {
        MetadataProvider.CacheEntry entry = parse("demo/hr/PersonSummary.java", VIEW);

        Assertions.assertEquals("demo/hr/PersonSummary.java", entry.relativePath(),
                "the path is passed through, never resolved against a filesystem");
        Assertions.assertEquals("demo.hr.PersonSummary", entry.fullClassName(),
                "the primary type's fully qualified name, from the package plus the file's type");
        Assertions.assertEquals(16, entry.hash().length(), "16 hex characters (DEC-029 § 4)");
        Assertions.assertTrue(entry.hash().matches("[0-9a-f]{16}"), entry.hash());

        Map<String, Object> metadata = entry.metadata();
        Assertions.assertEquals(Boolean.TRUE, metadata.get("readable"));
        Assertions.assertEquals("demo.hr", metadata.get("package"));
        Assertions.assertEquals("PersonSummary", metadata.get("simpleName"));
        Assertions.assertEquals("interface", metadata.get("kind"),
                "the kind comes from the tooling's one kind map, not from a second guess here");
        Assertions.assertEquals(List.of("age", "firstName", "tags"), metadata.get("methods"),
                "declared methods, sorted: the declaration order is not part of the fact");
        Assertions.assertFalse(metadata.containsKey("references"),
                "nothing needing a second file may appear: DEC-W008 puts cross-file facts in DEC-W009's store");
    }

    @Test
    void theChecksumIsAPropertyOfTheContentAndNotOfTheCheckout() {
        String crlf = VIEW.replace("\n", "\r\n");
        MetadataProvider.CacheEntry unix = parse("demo/hr/PersonSummary.java", VIEW);
        MetadataProvider.CacheEntry windows = parse("demo/hr/PersonSummary.java", crlf);

        Assertions.assertEquals(unix.hash(), windows.hash(),
                "a CRLF checkout and an LF checkout of the same content must agree, or the checksum "
                        + "describes someone's git configuration (ContentHash, DEC-029 § 4)");
        Assertions.assertEquals(unix.fullClassName(), windows.fullClassName(),
                "and the rest of the answer does not depend on line endings either");
    }

    @Test
    void aBrokenFileIsReportedRatherThanInvented() {
        MetadataProvider.CacheEntry entry = parse("demo/hr/Broken.java", "package demo.hr;\n\npublic class Broken {\n");

        Assertions.assertEquals("", entry.fullClassName(),
                "no type can be named from an unreadable file, and an invented name would be a claim "
                        + "about source nobody parsed");
        Assertions.assertFalse(entry.hash().isEmpty(),
                "the entry still exists and still carries its checksum, so 'this file changed' and "
                        + "'this file is broken' stay distinguishable");
        Assertions.assertEquals(Boolean.FALSE, entry.metadata().get("readable"));
        Assertions.assertTrue(entry.metadata().containsKey("problems"),
                "the parser's own detail travels with the answer, so a caller can show it");
        // The list itself may legitimately be empty — the parser recovers from many files javac rejects,
        // and for those it reports no problem while the unit is still not readable. The verdict
        // (`readable`) is the fact; the detail is a hint (F-34, doc_knowledge/code.graph.md). Asserting a
        // non-empty list here would have pinned a behaviour the reader does not promise.
        Assertions.assertNotNull(entry.metadata().get("problems"));
    }

    @Test
    void thePrimaryTypeIsTheOneTheFileIsNamedAfter() {
        // Both types are legal top-level declarations; only the file-name match is the file's primary one,
        // and a parser that took the first declaration would report First.
        String twoTypes = """
                package demo.hr;

                class First {
                }

                class PersonSummary {
                }
                """;

        MetadataProvider.CacheEntry entry = parse("demo/hr/PersonSummary.java", twoTypes);

        Assertions.assertEquals("demo.hr.PersonSummary", entry.fullClassName(),
                "Java's rule: the type the file is named after");
    }

    @Test
    void aFileWithNoTypeIsNotAnError() {
        MetadataProvider.CacheEntry entry = parse("demo/hr/package-info.java", "package demo.hr;\n");

        Assertions.assertEquals("", entry.fullClassName());
        Assertions.assertEquals(Boolean.TRUE, entry.metadata().get("readable"),
                "an empty but valid file is readable; only a broken one is not");
        Assertions.assertEquals(List.of(), entry.metadata().get("methods"));
    }

    /**
     * The two paths agree: what {@code parse} derives from the bytes is what the cache then holds.
     *
     * <p>This is the property DEC-W008 exists for — a consumer must not have to know whether the entry it
     * is reading came from a scan or from one file handed in by hand — and it is also what pins the
     * provider to its contract, since {@link InMemoryMetadataCacheProvider} is the override the decision
     * names.</p>
     */
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
}
