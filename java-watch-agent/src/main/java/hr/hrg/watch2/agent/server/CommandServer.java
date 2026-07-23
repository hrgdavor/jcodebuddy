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
import hr.hrg.watch2.agent.ToolSetAgent;
import hr.hrg.watch2.agent.ui.PendingActionManager;
import hr.hrg.watch2.agent.tools.SimpleToolContext;
import hr.hrg.watch2.agent.core.AuditManager;
import hr.hrg.watch2.agent.ui.PendingAction;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.InputStream;

/**
 * Lightweight HTTP server for IDE integration and HTML UI.
 */
public class CommandServer {

    private static final Logger log = LoggerFactory.getLogger(CommandServer.class);

    private final int port;

    private final Path projectRoot;

    private final List<ToolSetAgent> agents;

    private final PendingActionManager actionManager;

    private final String user;

    private final String password;

    private final boolean applyFirst;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;

    public CommandServer(int port, Path projectRoot, List<ToolSetAgent> agents, PendingActionManager actionManager,
            String user, String password, boolean applyFirst) {
        this.port = port;
        this.projectRoot = projectRoot;
        this.agents = agents;
        this.actionManager = actionManager;
        this.password = password;
        this.user = user;
        this.applyFirst = applyFirst;
    }

    public static record MyRecord(String name, int age) {

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder().name(this.name()).age(this.age());
        }

        public static class Builder {

            private String name;

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            private int age;

            public Builder age(int age) {
                this.age = age;
                return this;
            }

            public MyRecord build() {
                return new MyRecord(name, age);
            }
        }
    }

    public void start() throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (Exception e) {
            throw new RuntimeException("Can not setart server on port " + port, e);
        }
        BasicAuthenticator auth = new BasicAuthenticator("Java Watch Agent") {

            @Override
            public boolean checkCredentials(String u, String pwd) {
                return CommandServer.this.user.equals(u) && password.equals(pwd);
            }
        };
        createAuthenticatedContext("/status", new StatusHandler(), auth);
        createAuthenticatedContext("/trigger", new TriggerHandler(), auth);
        createAuthenticatedContext("/suggest", new SuggestHandler(), auth);
        createAuthenticatedContext("/actions", new ActionsHandler(), auth);
        createAuthenticatedContext("/accept", new AcceptHandler(), auth);
        createAuthenticatedContext("/reject", new RejectHandler(), auth);
        createAuthenticatedContext("/diff", new DiffHandler(), auth);
        createAuthenticatedContext("/", new StaticHandler(), auth);
        server.setExecutor(null);
        server.start();
        System.out.println("HTTP Server is now listening on http://localhost:" + port);
        log.info("Command Server started on port {} with Basic Auth (user: {})", port, user);
    }

    private void createAuthenticatedContext(String path, HttpHandler handler, BasicAuthenticator auth) {
        HttpContext context = server.createContext(path, handler);
        if (password != null && !password.isEmpty()) {
            context.setAuthenticator(auth);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class SuggestHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> body = mapper.readValue(exchange.getRequestBody(), Map.class);
                String filePath = body.get("file");
                String agentName = body.get("agent");
                String lineStr = body.get("line");
                int line = (lineStr != null) ? Integer.parseInt(lineStr) : 1;
                ToolSetAgent agent = agents.stream().filter(a -> agentName == null || a.getName().equals(agentName))
                        .findFirst().orElse(agents.get(0));
                Path path = Paths.get(filePath);
                if (!path.isAbsolute()) {
                    path = agent.getRoot().resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                List<String> suggestions = agent.getAnalyzer().suggestTools(path, line);
                sendResponse(exchange, 200, Map.of("suggestions", suggestions));
            } catch (Exception e) {
                log.error("Suggest error", e);
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    private class StatusHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> response = Map.of("status", "running", "root", projectRoot.toAbsolutePath().toString(),
                    "agents", agents.stream().map(ToolSetAgent::getName).toList());
            sendResponse(exchange, 200, response);
        }
    }

    private class TriggerHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, Map.of("error", "Method not allowed"));
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> body = mapper.readValue(exchange.getRequestBody(), Map.class);
                String toolName = body.get("tool");
                String filePath = body.get("file");
                String agentName = body.get("agent");
                String lineStr = body.get("line");
                int line = (lineStr != null) ? Integer.parseInt(lineStr) : 1;
                ToolSetAgent agent = agents.stream().filter(a -> agentName == null || a.getName().equals(agentName))
                        .findFirst().orElse(null);
                if (agent == null) {
                    sendResponse(exchange, 404, Map.of("error", "Agent not found"));
                    return;
                }
                Path root = agent.getRoot();
                Path path = Paths.get(filePath);
                if (!path.isAbsolute()) {
                    path = root.resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                SimpleToolContext context = new SimpleToolContext(root, path, line);
                PendingAction pa = agent.getEngine().runTool(toolName, agent, context);
                if (pa != null) {
                    if (applyFirst) {
                        try {
                            pa.doApply();
                        } catch (Exception e) {
                            log.error("Error auto-applying action", e);
                        }
                    }
                    actionManager.addAction(pa);
                }
                sendResponse(exchange, 200, Map.of("status", "pending", "action", toolName));
            } catch (Exception e) {
                log.error("Trigger error", e);
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    private class ActionsHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<PendingAction> actions = actionManager.getActions();
            List<Map<String, Object>> response = new ArrayList<>();
            for (int i = 0; i < actions.size(); i++) {
                PendingAction pa = actions.get(i);
                response.add(Map.of("id", i, "tool", pa.toolName(), "agent", pa.agent().getName(), "file",
                        pa.context().getFilePath().getFileName().toString(), "line", pa.context().getLine(), "changes",
                        pa.getChanges().size()));
            }
            sendResponse(exchange, 200, response);
        }
    }

    private class AcceptHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                int idx = Integer.parseInt(exchange.getRequestURI().getQuery().split("=")[1]);
                actionManager.accept(idx);
                sendResponse(exchange, 200, Map.of("status", "ok"));
            } catch (Exception e) {
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    private class RejectHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                int idx = Integer.parseInt(exchange.getRequestURI().getQuery().split("=")[1]);
                actionManager.reject(idx);
                sendResponse(exchange, 200, Map.of("status", "ok"));
            } catch (Exception e) {
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    private class DiffHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                int idx = Integer.parseInt(exchange.getRequestURI().getQuery().split("=")[1]);
                PendingAction pa = actionManager.getAction(idx);
                if (pa == null) {
                    sendResponse(exchange, 404, Map.of("error", "Action not found"));
                    return;
                }
                List<Map<String, Object>> diffs = new ArrayList<>();
                for (AuditManager.FileEntry entry : pa.getChanges()) {
                    Path before = pa.session().getSessionDir().resolve("before").resolve(entry.file);
                    Path after = pa.session().getSessionDir().resolve("after").resolve(entry.file);
                    String bContent = Files.exists(before) ? Files.readString(before) : "";
                    String aContent = Files.exists(after) ? Files.readString(after) : "";
                    diffs.add(Map.of("file", entry.file, "before", bContent, "after", aContent));
                }
                sendResponse(exchange, 200, diffs);
            } catch (Exception e) {
                sendResponse(exchange, 400, Map.of("error", e.getMessage()));
            }
        }
    }

    private class StaticHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/"))
                path = "/index.html";
            InputStream is = getClass().getResourceAsStream("/web" + path);
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = is.readAllBytes();
            String contentType = "text/html";
            if (path.endsWith(".js"))
                contentType = "application/javascript";
            if (path.endsWith(".css"))
                contentType = "text/css";
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
}
