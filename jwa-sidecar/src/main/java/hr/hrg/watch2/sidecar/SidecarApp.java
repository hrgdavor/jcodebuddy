// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.lsp4j.launch.LSPLauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;

/**
 * Entry point for the JWA Sidecar Language Server.
 */
public class SidecarApp {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        try {
            startServer(System.in, System.out);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static void startServer(InputStream in, OutputStream out)
            throws InterruptedException, ExecutionException, IOException {
        JwaLanguageServer server = new JwaLanguageServer();

        // Start the Jump HTTP Service (non-fatal)
        try {
            startJumpService(server);
        } catch (Exception e) {
            System.err.println("Failed to start Jump service: " + e.getMessage());
        }

        // Use LSPLauncher.Builder to support LSP-specific types and custom client
        // interface
        var launcher = new LSPLauncher.Builder<JwaLanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(JwaLanguageClient.class)
                .setInput(in)
                .setOutput(out)
                .create();

        JwaLanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        System.err.println("JWA Sidecar starting...");

        launcher.startListening().get();
    }

    private static void startJumpService(final JwaLanguageServer server) throws IOException {
        // Port 7979 as a default.
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(7979), 0);
        httpServer.createContext("/jump", exchange -> {
            // Add CORS support
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String uri = null;
                int line = 1;
                int column = 1;

                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length == 2) {
                            try {
                                if ("uri".equals(pair[0]))
                                    uri = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                                else if ("line".equals(pair[0]))
                                    line = Integer.parseInt(pair[1]);
                                else if ("column".equals(pair[0]))
                                    column = Integer.parseInt(pair[1]);
                            } catch (Exception e) {
                                // ignore parse errors for individual params
                            }
                        }
                    }
                }

                if (uri != null) {
                    server.jump(uri, line, column);
                    byte[] response = MAPPER.writeValueAsBytes(java.util.Map.of("status", "ok", "uri", uri));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } else {
                    byte[] response = MAPPER.writeValueAsBytes(java.util.Map.of("error", "Missing uri parameter"));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        httpServer.setExecutor(null);
        httpServer.start();
        // Use err because out is for LSP
        System.err.println("Jump service started on http://localhost:7979/jump");
    }
}
