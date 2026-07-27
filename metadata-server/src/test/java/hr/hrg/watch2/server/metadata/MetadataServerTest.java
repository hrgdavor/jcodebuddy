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

        public void put(String hash, String relPath, String className) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("checksum", hash);
            entries.put(hash, new CacheEntry(hash, className, relPath, meta));
        }
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

    @Test
    void httpForyRoundTrip() throws Exception {
        int port = 18080 + (int) (Math.random() * 1000);
        httpTransport = new HttpTransport(port, provider);
        httpTransport.start();

        org.apache.fory.Fory fory = org.apache.fory.Fory.builder().build();
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

        org.apache.fory.Fory fory = org.apache.fory.Fory.builder().build();
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
