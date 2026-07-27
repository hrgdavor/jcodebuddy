package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import hr.hrg.watch2.server.metadata.MetadataServer;
import hr.hrg.watch2.server.metadata.mcp.MetadataMcpServer;

import java.nio.file.Path;
import java.nio.file.Paths;

public class MetadataAnalysisRunner {
    public static void main(String[] args) throws Exception {
        Path projectRoot = Paths.get(".").toAbsolutePath().normalize();

        InMemoryMetadataCacheProvider provider = new InMemoryMetadataCacheProvider();
        MetadataAnalysis analysis = new MetadataAnalysis(projectRoot, provider);
        analysis.scan();

        String httpPortStr = System.getProperty("metadata.server.http.port", "7979");
        int httpPort = Integer.parseInt(httpPortStr);

        MetadataServer httpServer = MetadataServer.builder(provider)
            .httpPort(httpPort)
            .build();
        httpServer.start();

        MetadataMcpServer mcpServer = MetadataMcpServer.create(provider);
        mcpServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.stop();
            mcpServer.stop();
        }));
    }
}
