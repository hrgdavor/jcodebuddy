// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.io.InputStream;

/** The standalone HTTP host: static dashboard assets, the shared RPC verbs and the editor jump relay. */
public class CommandServer {

    private static final Logger log = LoggerFactory.getLogger(CommandServer.class);

    private final int port;
    private final Path projectRoot;
    private final WebviewHandshake handshake;
    private final String user;
    private final String password;
    private final AgentMethods methods;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;

    public CommandServer(int port, Path projectRoot, String user, String password, AgentMethods methods) {
        this.port = port;
        this.projectRoot = projectRoot;
        this.handshake = new WebviewHandshake(projectRoot);
        this.user = user;
        this.password = password;
        this.methods = methods;
    }

    public AgentMethods methods() {
        return methods;
    }

    public void start() throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (Exception failed) {
            throw new RuntimeException("Cannot start server on port " + port, failed);
        }
        BasicAuthenticator auth = new BasicAuthenticator("JCodeBuddy Agent") {
            @Override
            public boolean checkCredentials(String candidateUser, String candidatePassword) {
                return CommandServer.this.user.equals(candidateUser) && password.equals(candidatePassword);
            }
        };
        createAuthenticatedContext("/status", new StatusHandler(), auth);
        createAuthenticatedContext("/jump", new JumpHandler(), auth);
        createAuthenticatedContext("/rpc", new RpcHandler(), auth);
        createAuthenticatedContext("/", new StaticHandler(), auth);
        server.setExecutor(null);
        server.start();
        System.out.println("HTTP Server listening on http://localhost:" + port);
        log.info("Command Server started on port {} with Basic Auth (user: {})", port, user);
    }

    private void createAuthenticatedContext(String path, HttpHandler handler, BasicAuthenticator auth) {
        HttpContext context = server.createContext(path, handler);
        if (password != null && !password.isEmpty()) context.setAuthenticator(auth);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, Map.of("status", "running", "root",
                    projectRoot.toAbsolutePath().toString().replace('\\', '/')));
        }
    }

    private class RpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            if (methods == null) {
                sendResponse(exchange, 503, Map.of("error", "agent methods are unavailable"));
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), Map.class);
                String method = String.valueOf(request.get("method"));
                Object rawParams = request.get("params");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = rawParams instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.of();
                Object result = methods.call(method, params);
                sendResponse(exchange, 200, Map.of("result", result));
            } catch (hr.hrg.jcodebuddy.ipc.ToolException refused) {
                sendResponse(exchange, 400, Map.of("error", refused.getMessage(), "code", refused.code()));
            } catch (Exception failed) {
                log.error("Agent RPC failed", failed);
                sendResponse(exchange, 500, Map.of("error", String.valueOf(failed.getMessage())));
            }
        }
    }

    private class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            InputStream is = getClass().getResourceAsStream("/web" + path);
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = is.readAllBytes();
            String contentType = "text/html";
            if (path.endsWith(".js")) contentType = "application/javascript";
            if (path.endsWith(".css")) contentType = "text/css";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json == null ? new byte[0] : json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Forward a jump request to the webview sidecar, presenting the host token on the page's behalf. */
    private class JumpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            int sidecarPort = handshake.jumpPort();
            String token = handshake.token();
            log.debug("remote jump: {} ({}), token {}", sidecarPort, handshake.describe(),
                    token.isEmpty() ? "none" : "present");
            try {
                Map<?, ?> request = mapper.readValue(exchange.getRequestBody(), Map.class);
                java.net.http.HttpRequest forward = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://127.0.0.1:" + sidecarPort + "/jump"))
                        .header("Content-Type", "application/json")
                        .header("X-WebView-Token", token)
                        .timeout(java.time.Duration.ofSeconds(5))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                        .build();
                java.net.http.HttpResponse<String> answer = java.net.http.HttpClient.newHttpClient()
                        .send(forward, java.net.http.HttpResponse.BodyHandlers.ofString());
                sendJson(exchange, answer.statusCode(), answer.body());
            } catch (java.net.ConnectException | java.net.http.HttpTimeoutException unreachable) {
                log.warn("remote jump: no sidecar answered on port {}: {}", sidecarPort, unreachable.getMessage());
                sendResponse(exchange, 502, Map.of("error", "sidecar unreachable", "port", sidecarPort,
                        "detail", String.valueOf(unreachable.getMessage())));
            } catch (Exception failure) {
                log.error("Remote jump failed", failure);
                sendResponse(exchange, 502, Map.of("error", "remote jump failed",
                        "detail", String.valueOf(failure.getMessage())));
            }
        }
    }
}
