package hr.hrg.watch2.server.metadata.transport;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import hr.hrg.watch2.server.metadata.model.JsonRpcRequest;
import hr.hrg.watch2.server.metadata.model.JsonRpcError;
import hr.hrg.watch2.server.metadata.model.JsonRpcResponse;
import hr.hrg.watch2.server.metadata.rpc.MetadataRpcService;
import hr.hrg.watch2.server.metadata.rpc.RpcDispatcher;
import org.apache.fory.Fory;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.Map;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpTransport {
    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    private final int port;
    private final MetadataProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Fory fory;
    private final RpcDispatcher dispatcher;

    private HttpServer server;

    public HttpTransport(int port, MetadataProvider provider) {
        this.port = port;
        this.provider = provider;
        this.fory = Fory.builder().withNumberCompressed(true).withRefTracking(false).build();
        this.fory.register(JsonRpcRequest.class);
        this.fory.register(JsonRpcResponse.class);
        this.fory.register(JsonRpcError.class);
        this.fory.register(MetadataProvider.CacheEntry.class);
        this.dispatcher = new RpcDispatcher(provider, mapper);
        dispatcher.register(new MetadataRpcService(provider));
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/json", new JsonHandler());
        server.createContext("/api/fory", new ForyHandler());
        server.setExecutor(null);
        server.start();
        log.info("HTTP transport started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("HTTP transport stopped");
        }
    }

    private class JsonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (InputStream is = exchange.getRequestBody()) {
                JsonRpcRequest req = mapper.readValue(is, JsonRpcRequest.class);
                JsonRpcResponse res = dispatcher.dispatch(req);
                byte[] bytes = mapper.writeValueAsBytes(res);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("JSON-RPC error", e);
                String msg = mapper.writeValueAsString(Map.of("error", e.getMessage()));
                exchange.sendResponseHeaders(500, msg.getBytes().length);
                exchange.getResponseBody().write(msg.getBytes());
                exchange.close();
            }
        }
    }

    private class ForyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Object req = fory.deserialize(body);
                JsonRpcRequest jReq = mapper.convertValue(req, JsonRpcRequest.class);
                JsonRpcResponse res = dispatcher.dispatch(jReq);
                byte[] out = fory.serialize(res);
                exchange.getResponseHeaders().set("Content-Type", "application/x-fory");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                log.error("Fory-RPC error", e);
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            }
        }
    }
}
