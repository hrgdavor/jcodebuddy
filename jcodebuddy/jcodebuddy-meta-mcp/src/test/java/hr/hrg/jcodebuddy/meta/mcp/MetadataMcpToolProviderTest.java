package hr.hrg.jcodebuddy.meta.mcp;

import hr.hrg.jcodebuddy.meta.MetadataProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tool this module advertises, called.
 *
 * <p>An advertised tool that nothing calls is the same defect as a documented method that does not exist:
 * the surface is what an AI harness sees, and a tool that only <em>looks</em> registered fails at the
 * moment somebody depends on it. So each of the six is exercised here, together with the two shapes that
 * are easy to get wrong: a missing argument (an error result, not a thrown exception) and
 * {@code parse_file} against a provider that has no parser (DEC-W008's default refusal, which must arrive
 * as a tool error rather than as a broken session).</p>
 */
class MetadataMcpToolProviderTest {

    /** A provider with every method, including the file-scoped parser DEC-W008 adds. */
    static class FullProvider implements MetadataProvider {
        private final Map<String, CacheEntry> byHash = new LinkedHashMap<>();

        FullProvider() {
            byHash.put("hash1", new CacheEntry("hash1", "com.example.Foo", "src/Foo.java",
                    Map.of("checksum", "hash1", "kind", "class")));
            byHash.put("hash2", new CacheEntry("hash2", "com.example.Bar", "src/Bar.java",
                    Map.of("checksum", "hash2", "kind", "interface")));
        }

        @Override
        public CacheEntry get(String hash) { return byHash.get(hash); }

        @Override
        public List<CacheEntry> listEntries() { return new ArrayList<>(byHash.values()); }

        @Override
        public boolean hasChanged(String relPath, String checksum) {
            CacheEntry entry = byHash.get(checksum);
            return entry == null || !entry.relativePath().equals(relPath);
        }

        @Override
        public List<String> listClasses() {
            return byHash.values().stream().map(CacheEntry::fullClassName).toList();
        }

        @Override
        public CacheEntry parse(String relativePath, byte[] sourceBytes) {
            return new CacheEntry("parsed", relativePath, relativePath,
                    Map.of("length", sourceBytes.length, "text", new String(sourceBytes, StandardCharsets.UTF_8)));
        }
    }

    /** A provider without a parser: what a third-party provider looks like, and DEC-W008's default case. */
    static class NoParserProvider implements MetadataProvider {
        @Override
        public CacheEntry get(String hash) { return null; }

        @Override
        public List<CacheEntry> listEntries() { return List.of(); }

        @Override
        public boolean hasChanged(String relPath, String checksum) { return true; }

        @Override
        public List<String> listClasses() { return List.of(); }
    }

    private static MetadataMcpToolProvider tools(MetadataProvider provider) {
        return new MetadataMcpToolProvider(provider, JsonMapper.builder().build());
    }

    private static CallToolRequest request(String tool, Map<String, Object> arguments) {
        return new CallToolRequest(tool, arguments);
    }

    /** The one text content the handlers produce. */
    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void theAdvertisedToolsAreTheOnesThisClassServes() {
        List<String> names = tools(new FullProvider()).tools().stream().map(Tool::name).toList();

        Assertions.assertEquals(
                List.of("get_entry", "list_entries", "get_metadata", "has_changed", "list_classes", "parse_file"),
                names,
                "tools() and register() are two hand-maintained lists of the same six tools; a name in one "
                        + "and not the other is the drift this assertion exists to catch");
    }

    @Test
    void getEntryReturnsTheEntryAndRefusesWithoutAHash() {
        MetadataMcpToolProvider provider = tools(new FullProvider());

        CallToolResult found = provider.getEntry(null, request("get_entry", Map.of("hash", "hash1")));
        Assertions.assertNotEquals(Boolean.TRUE, found.isError());
        Assertions.assertTrue(text(found).contains("com.example.Foo"), text(found));

        CallToolResult missing = provider.getEntry(null, request("get_entry", Map.of()));
        Assertions.assertEquals(Boolean.TRUE, missing.isError(), "a missing argument is an error result");
        Assertions.assertTrue(text(missing).contains("hash"), text(missing));
    }

    @Test
    void listEntriesReturnsEveryEntry() {
        CallToolResult result = tools(new FullProvider()).listEntries(null, request("list_entries", Map.of()));

        Assertions.assertNotEquals(Boolean.TRUE, result.isError());
        Assertions.assertTrue(text(result).contains("src/Foo.java"), text(result));
        Assertions.assertTrue(text(result).contains("src/Bar.java"), text(result));
    }

    @Test
    void getMetadataReturnsThePayloadOrNullForAnUnknownHash() {
        MetadataMcpToolProvider provider = tools(new FullProvider());

        CallToolResult known = provider.getMetadata(null, request("get_metadata", Map.of("hash", "hash2")));
        Assertions.assertTrue(text(known).contains("interface"), text(known));

        CallToolResult unknown = provider.getMetadata(null, request("get_metadata", Map.of("hash", "nope")));
        Assertions.assertNotEquals(Boolean.TRUE, unknown.isError(),
                "an unknown hash is an empty answer, not a failure");
        Assertions.assertEquals("null", text(unknown).trim(), text(unknown));
    }

    @Test
    void hasChangedComparesTheChecksumAndRefusesHalfAnArgument() {
        MetadataMcpToolProvider provider = tools(new FullProvider());

        CallToolResult same = provider.hasChanged(null,
                request("has_changed", Map.of("relPath", "src/Foo.java", "checksum", "hash1")));
        Assertions.assertEquals("false", text(same).trim(), text(same));

        CallToolResult different = provider.hasChanged(null,
                request("has_changed", Map.of("relPath", "src/Other.java", "checksum", "hash1")));
        Assertions.assertEquals("true", text(different).trim(), text(different));

        CallToolResult incomplete = provider.hasChanged(null, request("has_changed", Map.of("relPath", "src/Foo.java")));
        Assertions.assertEquals(Boolean.TRUE, incomplete.isError(), text(incomplete));
    }

    @Test
    void listClassesReturnsTheKnownNames() {
        CallToolResult result = tools(new FullProvider()).listClasses(null, request("list_classes", Map.of()));

        Assertions.assertTrue(text(result).contains("com.example.Bar"), text(result));
    }

    @Test
    void parseFileReturnsTheEntryTheProviderParsed() {
        MetadataMcpToolProvider provider = tools(new FullProvider());
        String source = "package demo;\n\npublic class Demo {\n}\n";

        CallToolResult result = provider.parseFile(null,
                request("parse_file", Map.of("relPath", "demo/Demo.java", "source", source)));

        Assertions.assertNotEquals(Boolean.TRUE, result.isError(), text(result));
        Assertions.assertTrue(text(result).contains("demo/Demo.java"), text(result));
        Assertions.assertTrue(text(result).contains("length"), text(result));

        CallToolResult noSource = provider.parseFile(null, request("parse_file", Map.of("relPath", "demo/Demo.java")));
        Assertions.assertEquals(Boolean.TRUE, noSource.isError(), text(noSource));
    }

    /**
     * DEC-W008's default, seen from an MCP client: an error <em>result</em>, not a thrown exception.
     *
     * <p>A tool that throws gives the caller a broken session; a tool that reports an error gives it an
     * answer it can act on — here, that this provider has no source parser and that the file's metadata
     * has to come from a cache-backed route instead.</p>
     */
    @Test
    void aProviderWithNoParserReportsAToolErrorRatherThanThrowing() {
        MetadataMcpToolProvider provider = tools(new NoParserProvider());

        CallToolResult result = provider.parseFile(null,
                request("parse_file", Map.of("relPath", "demo/Demo.java", "source", "package demo;\n")));

        Assertions.assertEquals(Boolean.TRUE, result.isError());
        Assertions.assertTrue(text(result).contains("no source parser"), text(result));
        Assertions.assertTrue(text(result).contains("NoParserProvider"), text(result));

        // And the cache-backed tools are unaffected by the absence: the addition is additive.
        CallToolResult listed = provider.listEntries(null, request("list_entries", Map.of()));
        Assertions.assertNotEquals(Boolean.TRUE, listed.isError(), text(listed));
    }
}
