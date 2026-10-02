package hr.hrg.watch2.server.metadata;

import hr.hrg.watch2.server.metadata.transport.HttpTransport;
import hr.hrg.watch2.server.metadata.transport.UnixSocketTransport;
import hr.hrg.watch2.server.metadata.model.JsonRpcRequest;
import hr.hrg.watch2.server.metadata.model.JsonRpcResponse;
import hr.hrg.watch2.server.metadata.model.JsonRpcError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class MetadataServerTest {
    private InMemoryProvider provider;
    private ObjectMapper mapper;
    private HttpTransport httpTransport;
    private UnixSocketTransport unixJsonTransport;
    private UnixSocketTransport unixForyTransport;

    static class InMemoryProvider implements MetadataProvider {
        private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

        @Override
        public CacheEntry get(String hash) { return entries.get(hash); }

        @Override
        public List<CacheEntry> listEntries() { return new ArrayList<>(entries.values()); }

        @Override
        public boolean hasChanged(String relPath, String checksum) {
            CacheEntry e = entries.get(relPath);
            return e == null || !Objects.equals(e.metadata().get("checksum"), checksum);
        }

        @Override
        public List<String> listClasses() { return List.of("com.example.Foo", "com.example.Bar"); }

        /**
         * A transport-level stub, and deliberately not a Java parser.
         *
         * <p>The reference implementation of DEC-W008's {@code parse} is
         * {@code project-automation}'s {@code SourceMetadataParser}, which sits in the module that has the
         * source reader (OpenRewrite's LST, DEC-030). Pulling that reader into this module's tests to
         * assert a JSON-RPC round trip would test the parser twice and the transport once. So the rule
         * here is fixed and trivial — the hash is the byte count, the metadata is the length and the text
         * — which is exactly what lets the assertions below be exact about what crossed the wire.</p>
         */
        @Override
        public CacheEntry parse(String relativePath, byte[] sourceBytes) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("length", sourceBytes.length);
            meta.put("text", new String(sourceBytes, StandardCharsets.UTF_8));
            return new CacheEntry(String.format("%016x", (long) sourceBytes.length), relativePath,
                    relativePath, meta);
        }

        public void put(String hash, String relPath, String className) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("checksum", hash);
            entries.put(hash, new CacheEntry(hash, className, relPath, meta));
        }
    }

    /**
     * A provider that implements only the four cache-backed methods: what a third-party provider looks
     * like, and the case DEC-W008's default has to answer for.
     */
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

    @BeforeEach
    void setUp() throws Exception {
        provider = new InMemoryProvider();
        mapper = new ObjectMapper();
        provider.put("hash1", "src/Foo.java", "com.example.Foo");
    }

    @AfterEach
    void tearDown() {
        if (httpTransport != null) httpTransport.stop();
        if (unixJsonTransport != null) unixJsonTransport.stop();
        if (unixForyTransport != null) unixForyTransport.stop();
    }

    @Test
    void httpJsonRoundTrip() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        httpTransport = new HttpTransport(port, provider);
        httpTransport.start();

        URL url = new URL("http://localhost:" + port + "/api/json");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            String req = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "1",
                "method", "getEntry",
                "params", Map.of("hash", "hash1")
            ));
            os.write(req.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = conn.getInputStream()) {
            Map<?,?> res = mapper.readValue(is, Map.class);
            assertEquals("2.0", res.get("jsonrpc"));
            assertEquals("1", res.get("id"));
            assertNotNull(res.get("result"));
        }
    }

    @Test
    void httpJsonUnknownMethod() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        httpTransport = new HttpTransport(port, provider);
        httpTransport.start();

        URL url = new URL("http://localhost:" + port + "/api/json");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            String req = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "1",
                "method", "noop",
                "params", Map.of()
            ));
            os.write(req.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = conn.getInputStream()) {
            Map<?,?> res = mapper.readValue(is, Map.class);
            Map<?,?> error = (Map<?,?>) res.get("error");
            assertNotNull(error);
            assertEquals(-32601, error.get("code"));
        }
    }

    /**
     * DEC-W008's additive RPC method: the file's text in, the entry out, no cache consulted.
     */
    @Test
    void parseFileJsonRoundTrip() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        httpTransport = new HttpTransport(port, provider);
        httpTransport.start();

        String source = "package demo;\n\npublic class Demo {\n}\n";
        URL url = new URL("http://localhost:" + port + "/api/json");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            String req = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "9",
                "method", "parseFile",
                "params", Map.of("relPath", "demo/Demo.java", "source", source)
            ));
            os.write(req.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = conn.getInputStream()) {
            Map<?,?> res = mapper.readValue(is, Map.class);
            assertNull(res.get("error"), String.valueOf(res.get("error")));
            Map<?,?> result = (Map<?,?>) res.get("result");
            assertNotNull(result, "parseFile answers with the entry");
            assertEquals("demo/Demo.java", result.get("relativePath"));
            int length = source.getBytes(StandardCharsets.UTF_8).length;
            assertEquals(String.format("%016x", (long) length), result.get("hash"),
                    "the hash is the one the provider's parse produced, not one the transport invented");
            Map<?,?> metadata = (Map<?,?>) result.get("metadata");
            assertEquals(length, metadata.get("length"));
            assertEquals(source, metadata.get("text"));
        }
    }

    /**
     * The same surface against a provider with no parser: a named failure for {@code parseFile}, and the
     * cache-backed methods untouched — which is what "additive" has to mean for a third-party provider.
     */
    @Test
    void aProviderWithNoParserFailsLoudlyAndOnlyForParseFile() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        httpTransport = new HttpTransport(port, new NoParserProvider());
        httpTransport.start();

        URL url = new URL("http://localhost:" + port + "/api/json");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            String req = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "10",
                "method", "parseFile",
                "params", Map.of("relPath", "demo/Demo.java", "source", "package demo;\n")
            ));
            os.write(req.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = conn.getInputStream()) {
            Map<?,?> res = mapper.readValue(is, Map.class);
            assertNull(res.get("result"), "no entry can be invented for a provider with no parser");
            Map<?,?> error = (Map<?,?>) res.get("error");
            assertNotNull(error, "the caller gets an error it can act on");
            assertEquals(-32603, error.get("code"));
            String message = String.valueOf(error.get("message"));
            assertTrue(message.contains("no source parser"),
                    "and the message names the thing to change: " + message);
            assertTrue(message.contains("NoParserProvider"),
                    "including which provider was asked: " + message);
        }

        URL listUrl = new URL("http://localhost:" + port + "/api/json");
        URLConnection listConn = listUrl.openConnection();
        listConn.setDoOutput(true);
        listConn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = listConn.getOutputStream()) {
            os.write(mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "11",
                "method", "listEntries",
                "params", Map.of()
            )).getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream is = listConn.getInputStream()) {
            Map<?,?> res = mapper.readValue(is, Map.class);
            assertTrue(((List<?>) res.get("result")).isEmpty(),
                    "listEntries is unchanged by the addition");
        }
    }

    @Test
    void httpForyRoundTrip() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        httpTransport = new HttpTransport(port, provider);
        httpTransport.start();

        // The client's codec comes from the published contract, not from `Fory.builder()`.
        //
        // This test used to build a default-configured Fory here, and it failed on every run with an
        // HTTP 500 whose server-side cause was `SerializationException: NullPointerException` inside
        // Fory's `DeferedLazySerializer`. The reason is that Fory's wire format is not self-describing:
        // the transport configures `withNumberCompressed(true)` and `withRefTracking(false)`, and a
        // client built from the defaults mis-parses the frames — the server then serialises a request
        // graph it half-understood, reaches a value whose runtime class has no serializer, and Fory's
        // deferred lookup answers `null` instead of reporting the configuration mismatch.
        //
        // So the assertion this test makes is "the documented codec round-trips over HTTP". If it is ever
        // weakened back to `Fory.builder().build()`, it fails again — which is the point: the flags are
        // part of the protocol, and `ForyCodec` is where a client learns them.
        org.apache.fory.Fory fory = hr.hrg.watch2.server.metadata.transport.ForyCodec.newFory();
        fory.register(JsonRpcRequest.class);
        fory.register(JsonRpcResponse.class);
        fory.register(JsonRpcError.class);
        Map<String, Object> reqMap = new HashMap<>();
        reqMap.put("jsonrpc", "2.0");
        reqMap.put("id", "2");
        reqMap.put("method", "listEntries");
        reqMap.put("params", new HashMap<>());
        byte[] reqBytes = fory.serialize(reqMap);

        URL url = new URL("http://localhost:" + port + "/api/fory");
        URLConnection conn = url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-fory");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(reqBytes);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            is.transferTo(baos);
        }
        Object res = fory.deserialize(baos.toByteArray());
        assertTrue(res instanceof Map);
        assertEquals("2.0", ((Map<?,?>) res).get("jsonrpc"));
    }

    @Test
    void unixJsonRoundTrip() throws Exception {
        if (!isUnixDomainSupported()) {
            System.out.println("Skipping Unix socket test on Windows");
            return;
        }
        java.nio.file.Path tempDir = Files.createTempDirectory("metadata-sock-");
        java.nio.file.Path sock = tempDir.resolve("metadata-json.sock");
        unixJsonTransport = new UnixSocketTransport(sock, UnixSocketTransport.Protocol.JSON, provider);
        unixJsonTransport.start();

        try (SocketChannel client = SocketChannel.open(unixAddress(sock))) {
            String req = mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "3",
                "method", "listClasses",
                "params", Map.of()
            )) + "\n";
            client.write(ByteBuffer.wrap(req.getBytes(StandardCharsets.UTF_8)));
            client.shutdownOutput();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = client.read(ByteBuffer.wrap(buf))) != -1) {
                baos.write(buf, 0, read);
            }
            String line = baos.toString(StandardCharsets.UTF_8.name()).trim();
            Map<?,?> res = mapper.readValue(line, Map.class);
            assertEquals("2.0", res.get("jsonrpc"));
            assertEquals("3", res.get("id"));
        } finally {
            Files.deleteIfExists(sock);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void unixForyRoundTrip() throws Exception {
        if (!isUnixDomainSupported()) {
            System.out.println("Skipping Unix socket test on Windows");
            return;
        }
        java.nio.file.Path tempDir = Files.createTempDirectory("metadata-sock-");
        java.nio.file.Path sock = tempDir.resolve("metadata-fory.sock");
        unixForyTransport = new UnixSocketTransport(sock, UnixSocketTransport.Protocol.FORY, provider);
        unixForyTransport.start();

        // The same published codec as the HTTP case above (see the comment there for why the defaults
        // cannot interoperate with a transport-configured Fory).
        org.apache.fory.Fory fory = hr.hrg.watch2.server.metadata.transport.ForyCodec.newFory();
        byte[] reqBytes = fory.serialize(Map.of(
            "jsonrpc", "2.0",
            "id", "4",
            "method", "getEntry",
            "params", Map.of("hash", "hash1")
        ));

        try (SocketChannel client = SocketChannel.open(unixAddress(sock));
             DataOutputStream dos = new DataOutputStream(Channels.newOutputStream(client))) {
            dos.writeInt(reqBytes.length);
            dos.write(reqBytes);
            dos.flush();
            client.shutdownOutput();

            DataInputStream dis = new DataInputStream(Channels.newInputStream(client));
            int len = dis.readInt();
            byte[] resp = dis.readNBytes(len);
            Object res = fory.deserialize(resp);
            assertTrue(res instanceof Map);
            assertEquals("4", ((Map<?,?>) res).get("id"));
        } finally {
            Files.deleteIfExists(sock);
            Files.deleteIfExists(tempDir);
        }
    }

    private static java.net.SocketAddress unixAddress(Path path) throws Exception {
        Class<?> addrClass = Class.forName("jdk.net.UnixDomainSocketAddress");
        return (java.net.SocketAddress) addrClass.getMethod("of", Path.class).invoke(null, path);
    }

    private static boolean isUnixDomainSupported() {
        try {
            Class.forName("jdk.net.UnixDomainSocketAddress");
            return !System.getProperty("os.name").toLowerCase().contains("win");
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
