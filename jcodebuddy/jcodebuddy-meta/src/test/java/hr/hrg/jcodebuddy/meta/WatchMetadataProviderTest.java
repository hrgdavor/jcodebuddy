package hr.hrg.jcodebuddy.meta;

import hr.hrg.jcodebuddy.meta.transport.HttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata surface over a file-watch checksum cache: what a {@code MetadataCache} knows, served
 * through {@link MetadataProvider}.
 *
 * <p>The test is split on purpose between the adapter's own answers and one round trip through the real
 * transport, because those are two different claims: "the mapping is right" and "a client can reach it".
 * The watch cache is emulated with its three real facts (path, checksum, mtime) rather than with the
 * agent's class, since a library may not depend on an application module — see the class javadoc.</p>
 */
class WatchMetadataProviderTest {

    private HttpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.stop();
        }
    }

    private static WatchMetadataProvider provider() {
        Map<String, WatchMetadataProvider.WatchedFile> files = new HashMap<>();
        files.put("src/A.java", new WatchMetadataProvider.WatchedFile("src/A.java", "aaaa000000000001", 1000L));
        files.put("src/B.java", new WatchMetadataProvider.WatchedFile("src/B.java", "bbbb000000000002", 2000L));
        return WatchMetadataProvider.of(files);
    }

    @Test
    void theEntryCarriesTheThreeFactsTheCacheHasAndInventsNoFourth() {
        WatchMetadataProvider provider = provider();

        MetadataProvider.CacheEntry entry = provider.get("aaaa000000000001");
        Assertions.assertNotNull(entry, "the cache is keyed by path, the interface by checksum");
        Assertions.assertEquals("src/A.java", entry.relativePath());
        Assertions.assertEquals("aaaa000000000001", entry.hash());
        Assertions.assertEquals("", entry.fullClassName(),
                "the checksum cache has never known a class name, so none is invented");
        Assertions.assertEquals("aaaa000000000001", entry.metadata().get("checksum"));
        Assertions.assertEquals(1000L, entry.metadata().get("lastModified"));
        Assertions.assertEquals("watch-cache", entry.metadata().get("source"),
                "and where the entry came from is part of the answer");
    }

    @Test
    void listEntriesIsPathOrderedAndComplete() {
        List<MetadataProvider.CacheEntry> entries = provider().listEntries();

        Assertions.assertEquals(2, entries.size());
        Assertions.assertEquals(List.of("src/A.java", "src/B.java"),
                entries.stream().map(MetadataProvider.CacheEntry::relativePath).toList(),
                "a cache with no order of its own still answers in a deterministic one");
    }

    @Test
    void identicalContentResolvesToTheFirstPathAndStillListsBoth() {
        Map<String, WatchMetadataProvider.WatchedFile> files = new HashMap<>();
        files.put("src/Z.java", new WatchMetadataProvider.WatchedFile("src/Z.java", "same000000000001", 1L));
        files.put("src/A.java", new WatchMetadataProvider.WatchedFile("src/A.java", "same000000000001", 2L));
        WatchMetadataProvider provider = WatchMetadataProvider.of(files);

        Assertions.assertEquals("src/A.java", provider.get("same000000000001").relativePath(),
                "two files with identical content share a checksum; the first in path order answers, and "
                        + "the javadoc says so rather than the caller discovering it");
        Assertions.assertEquals(2, provider.listEntries().size(),
                "while listEntries still reports every file the cache holds");
    }

    @Test
    void hasChangedComparesTheRecordedChecksum() {
        WatchMetadataProvider provider = provider();

        Assertions.assertFalse(provider.hasChanged("src/A.java", "aaaa000000000001"),
                "the same checksum means the cache's record still holds");
        Assertions.assertTrue(provider.hasChanged("src/A.java", "cccc000000000003"),
                "a different one means it does not");
        Assertions.assertTrue(provider.hasChanged("src/New.java", "dddd000000000004"),
                "and a file the cache has never seen has certainly changed: it has no record");
    }

    @Test
    void listClassesIsEmptyRatherThanAGuess() {
        Assertions.assertEquals(List.of(), provider().listClasses(),
                "the cache holds no class names; paths that look like Java files would be a guess "
                        + "dressed as a fact");
    }

    @Test
    void parseRefusesByNameBecauseTheWatchCacheHasNoSourceParser() {
        MetadataParseUnsupportedException failure = Assertions.assertThrows(
                MetadataParseUnsupportedException.class,
                () -> provider().parse("src/A.java", "package src;\n".getBytes(StandardCharsets.UTF_8)));

        Assertions.assertTrue(failure.getMessage().contains("WatchMetadataProvider"),
                "the refusal names the provider that was asked: " + failure.getMessage());
        Assertions.assertTrue(failure.getMessage().contains("no source parser"),
                "and says what is missing: " + failure.getMessage());
    }

    @Test
    void theSnapshotDoesNotFollowTheCacheAfterConstruction() {
        Map<String, WatchMetadataProvider.WatchedFile> files = new HashMap<>();
        files.put("src/A.java", new WatchMetadataProvider.WatchedFile("src/A.java", "aaaa000000000001", 1L));
        WatchMetadataProvider provider = WatchMetadataProvider.of(files);

        files.put("src/Later.java", new WatchMetadataProvider.WatchedFile("src/Later.java", "bbbb000000000002", 2L));

        Assertions.assertEquals(1, provider.listEntries().size(),
                "the provider is a view of what the cache held when it was asked — a live view would let "
                        + "listEntries and get disagree with each other mid-answer");
    }

    /**
     * The transport half: a client reaches the watch cache through the same JSON-RPC surface as any other
     * provider, with no agent, no daemon and no second route to keep in step.
     */
    @Test
    void theWatchCacheIsReachableOverTheRealTransport() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        transport = new HttpTransport(port, provider());
        transport.start();

        ObjectMapper mapper = new ObjectMapper();

        URL url = new URL("http://localhost:" + port + "/api/json");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", "1",
                    "method", "getEntry",
                    "params", Map.of("hash", "bbbb000000000002")
            )).getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = conn.getInputStream()) {
            Map<?, ?> res = mapper.readValue(is, Map.class);
            Assertions.assertNull(res.get("error"), String.valueOf(res.get("error")));
            Map<?, ?> result = (Map<?, ?>) res.get("result");
            Assertions.assertEquals("src/B.java", result.get("relativePath"));
            Assertions.assertEquals("", result.get("fullClassName"));
        }

        URL listUrl = new URL("http://localhost:" + port + "/api/json");
        URLConnection listConn = listUrl.openConnection();
        listConn.setDoOutput(true);
        listConn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = listConn.getOutputStream()) {
            os.write(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", "2",
                    "method", "listEntries",
                    "params", Map.of()
            )).getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = listConn.getInputStream()) {
            Map<?, ?> res = mapper.readValue(is, Map.class);
            Assertions.assertEquals(2, ((List<?>) res.get("result")).size(),
                    "the whole cache is served, not just the entry that was asked for");
        }
    }
}
