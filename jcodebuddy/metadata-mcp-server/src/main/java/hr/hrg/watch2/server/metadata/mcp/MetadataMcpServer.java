package hr.hrg.watch2.server.metadata.mcp;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CountDownLatch;

public class MetadataMcpServer {
    private final MetadataProvider provider;
    private McpSyncServer server;
    private Thread serverThread;

    public MetadataMcpServer(MetadataProvider provider) {
        this.provider = provider;
    }

    public static MetadataMcpServer create(MetadataProvider provider) {
        return new MetadataMcpServer(provider);
    }

    public void start() throws Exception {
        JsonMapper mapper = new JsonMapper();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
            new JacksonMcpJsonMapper(mapper), System.in, System.out);

        MetadataMcpToolProvider toolProvider = new MetadataMcpToolProvider(provider, mapper);

        McpServer.SingleSessionSyncSpecification spec = (McpServer.SingleSessionSyncSpecification) McpServer.sync(transport)
            .serverInfo("jcodebuddy-metadata", "1.0.0")
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build());

        toolProvider.register(spec);

        server = spec.build();
        CountDownLatch latch = new CountDownLatch(1);
        serverThread = new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "mcp-server-main");
        serverThread.start();
    }

    public void stop() {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }
}
