package hr.hrg.watch2.server.metadata.transport;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import hr.hrg.watch2.server.metadata.rpc.MetadataRpcService;
import hr.hrg.watch2.server.metadata.rpc.RpcDispatcher;
import hr.hrg.watch2.server.metadata.model.JsonRpcRequest;
import hr.hrg.watch2.server.metadata.model.JsonRpcResponse;
import hr.hrg.watch2.server.metadata.model.JsonRpcError;
import org.apache.fory.Fory;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnixSocketTransport {
    private static final Logger log = LoggerFactory.getLogger(UnixSocketTransport.class);

    public enum Protocol { JSON, FORY }

    private final Path socketPath;
    private final Protocol protocol;
    private final MetadataProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Fory fory;
    private final RpcDispatcher dispatcher;

    private ServerSocketChannel server;
    private Thread acceptorThread;

    public UnixSocketTransport(Path socketPath, Protocol protocol, MetadataProvider provider) {
        this.socketPath = socketPath;
        this.protocol = protocol;
        this.provider = provider;
        // The same codec as the HTTP transport, from the same factory: the two transports share a wire
        // format, so they must share its configuration (see ForyCodec).
        this.fory = ForyCodec.newFory();
        this.fory.register(JsonRpcRequest.class);
        this.fory.register(JsonRpcResponse.class);
        this.fory.register(JsonRpcError.class);
        this.fory.register(MetadataProvider.CacheEntry.class);
        this.dispatcher = new RpcDispatcher(provider, mapper);
        dispatcher.register(new MetadataRpcService(provider));
    }

    public void start() throws IOException {
        if (!isUnixDomainSupported()) {
            log.warn("Unix domain socket not supported on this OS, skipping {}", socketPath);
            return;
        }
        server = ServerSocketChannel.open();
        server.setOption(StandardSocketOptions.SO_REUSEADDR, false);
        SocketAddress address = unixAddress(socketPath);
        server.bind(address, 0);
        acceptorThread = new Thread(this::acceptLoop, "unix-socket-" + protocol.name().toLowerCase());
        acceptorThread.setDaemon(true);
        acceptorThread.start();
        log.info("Unix socket transport started at {} ({})", socketPath, protocol);
    }

    public void stop() {
        try {
            if (server != null && server.isOpen()) {
                server.close();
            }
        } catch (IOException e) {
            log.warn("Error closing Unix socket", e);
        }
        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }
        try {
            java.nio.file.Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            log.warn("Could not delete socket file {}", socketPath, e);
        }
        log.info("Unix socket transport stopped");
    }

    private void acceptLoop() {
        try {
            while (server.isOpen()) {
                SocketChannel client = server.accept();
                if (client == null) continue;
                new Thread(() -> handleClient(client), "unix-socket-client").start();
            }
        } catch (IOException e) {
            if (server.isOpen()) {
                log.error("Unix socket accept error", e);
            }
        }
    }

    private void handleClient(SocketChannel client) {
        try {
            client.configureBlocking(true);
            if (protocol == Protocol.JSON) {
                handleJson(client);
            } else {
                handleFory(client);
            }
        } catch (IOException e) {
            log.debug("Unix socket client error", e);
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void handleJson(SocketChannel client) throws IOException {
        BufferedReader in = new BufferedReader(Channels.newReader(client, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(Channels.newWriter(client, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JsonRpcRequest req;
                try {
                    req = mapper.readValue(line, JsonRpcRequest.class);
                } catch (Exception e) {
                    JsonRpcResponse err = JsonRpcResponse.err("null", -32700, "Parse error");
                    out.write(mapper.writeValueAsString(err));
                    out.newLine();
                    out.flush();
                    continue;
                }
                JsonRpcResponse res = dispatcher.dispatch(req);
                out.write(mapper.writeValueAsString(res));
                out.newLine();
                out.flush();
            }
        } finally {
            in.close();
            out.close();
        }
    }

    private void handleFory(SocketChannel client) throws IOException {
        DataInputStream dis = new DataInputStream(Channels.newInputStream(client));
        DataOutputStream dos = new DataOutputStream(Channels.newOutputStream(client));
        while (true) {
            int len;
            try {
                len = dis.readInt();
            } catch (EOFException e) {
                break;
            }
            byte[] payload = dis.readNBytes(len);
            // The wire carries a map, not the model object — see JsonRpcResponse.toWireMap for the
            // measured reason (Fory 1.3.0 cannot write a null into an object field).
            Object wireRequest = fory.deserialize(payload);
            JsonRpcRequest jReq = mapper.convertValue(wireRequest, JsonRpcRequest.class);
            JsonRpcResponse res = dispatcher.dispatch(jReq);
            byte[] outBytes = fory.serialize(res.toWireMap());
            dos.writeInt(outBytes.length);
            dos.write(outBytes);
            dos.flush();
        }
    }

    private static SocketAddress unixAddress(Path path) throws IOException {
        try {
            Class<?> addrClass = Class.forName("jdk.net.UnixDomainSocketAddress");
            Method ofMethod = addrClass.getMethod("of", Path.class);
            return (SocketAddress) ofMethod.invoke(null, path);
        } catch (Exception e) {
            throw new IOException("Unix domain sockets not supported", e);
        }
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
