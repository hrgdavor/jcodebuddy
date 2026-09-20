package hr.hrg.jetbrains.webview.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service(Service.Level.PROJECT)
public final class HttpBridgeService {
    private static final Logger LOG = Logger.getInstance(HttpBridgeService.class);
    private final Project project;
    private HttpServer server;
    private final Set<String> allowedOrigins = new HashSet<>();
    private final Deque<Long> requestTimestamps = new ArrayDeque<>();
    private static final int RATE_LIMIT_COUNT = 20;
    private static final int RATE_LIMIT_WINDOW_MS = 20000;

    public HttpBridgeService(Project project) {
        this.project = project;
        LOG.info("Initializing HttpBridgeService for project: " + project.getName());
        loadSettingsAndStart();
    }

    private void loadSettingsAndStart() {
        loadAllowedOrigins();
        startServerIfNeeded();
    }

    public synchronized void restartServer() {
        LOG.info("Restarting HTTP bridge server for project: " + project.getName());
        stopServer();
        allowedOrigins.clear();
        loadSettingsAndStart();
    }

    public synchronized void stopServer() {
        if (server != null) {
            try {
                server.stop(0);
                LOG.info("HTTP bridge server stopped.");
            } catch (Exception e) {
                LOG.error("Error stopping HTTP bridge server", e);
            }
            server = null;
        }
    }

    private void loadAllowedOrigins() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        String allowed = (state != null && state.allowedOrigins != null && !state.allowedOrigins.isEmpty())
                ? state.allowedOrigins
                : System.getProperty("webview.explorer.allowed");

        if (allowed != null && !allowed.isEmpty()) {
            for (String origin : allowed.split(",")) {
                allowedOrigins.add(origin.trim().toLowerCase());
            }
        }
    }

    private void startServerIfNeeded() {
        PluginStateService.State state = PluginStateService.getInstance(project).getState();
        String portStr = (state != null && state.port != null)
                ? state.port.toString()
                : System.getProperty("webview.explorer.port");

        if (portStr == null || portStr.isEmpty()) {
            LOG.info("webview.explorer.port is not configured for project: " + project.getName());
            return;
        }

        try {
            int port = Integer.parseInt(portStr);
            LOG.info("Starting HTTP bridge server on port: " + port);
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new GlobalHandler());
            server.setExecutor(null);
            server.start();
            LOG.info("HTTP bridge server started successfully on port " + port);
        } catch (Exception e) {
            LOG.error("Failed to start HTTP bridge server", e);
        }
    }

    private class GlobalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            boolean isAllowed = origin != null && allowedOrigins.contains(origin.toLowerCase());

            if (isAllowed) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
            }

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path != null) {
                // Normalize path: collapse // to /
                path = path.replaceAll("/+", "/");
            }

            if ("/open".equals(path)) {
                if (!isAllowed) {
                    LOG.warn("CORS block: Forbidden origin " + origin);
                    sendResponse(exchange, 403, "CORS Forbidden: Origin not allowed");
                    return;
                }

                if (!checkRateLimit()) {
                    LOG.warn("HTTP Rate limit exceeded for origin: " + origin);
                    sendResponse(exchange, 429, "Too Many Requests: Rate limit exceeded (20 per 20s)");
                    return;
                }

                new OpenFileHandler().handle(exchange);
            } else {
                sendResponse(exchange, 404, "Not Found");
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int code, String message) throws IOException {
        exchange.sendResponseHeaders(code, message.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(message.getBytes());
        }
    }

    private class OpenFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String filePath = params.get("filePath");
            String lineStr = params.get("line");
            String columnStr = params.get("column");

            if (filePath != null) {
                int line = 1;
                int column = 1;
                try {
                    if (lineStr != null)
                        line = Integer.parseInt(lineStr);
                    if (columnStr != null)
                        column = Integer.parseInt(columnStr);
                } catch (NumberFormatException ignored) {
                }

                final String finalPath = filePath;
                final int finalLine = line;
                final int finalColumn = column;
                ApplicationManager.getApplication()
                        .invokeLater(() -> navigateToFile(finalPath, finalLine, finalColumn));

                String response = "Opening " + filePath + ":" + line + ":" + column;
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } else {
                String response = "Missing filePath parameter";
                exchange.sendResponseHeaders(400, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null)
            return params;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                params.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private synchronized boolean checkRateLimit() {
        long now = System.currentTimeMillis();
        while (!requestTimestamps.isEmpty() && now - requestTimestamps.peekFirst() > RATE_LIMIT_WINDOW_MS) {
            requestTimestamps.removeFirst();
        }
        if (requestTimestamps.size() >= RATE_LIMIT_COUNT) {
            return false;
        }
        requestTimestamps.addLast(now);
        return true;
    }

    public void navigateToFile(String path, int line, int column) {
        if (!checkRateLimit()) {
            LOG.warn("JCEF Rate limit exceeded while trying to open: " + path);
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            String projectBase = project.getBasePath();
            String finalPath;
            if (projectBase != null && !new File(path).isAbsolute()) {
                finalPath = new File(projectBase, path).getAbsolutePath();
            } else {
                finalPath = path;
            }

            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(finalPath.replace("\\", "/"));
            if (file != null) {
                int lineIndex = Math.max(0, line - 1);
                int colIndex = Math.max(0, column - 1);
                OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, lineIndex, colIndex);

                com.intellij.openapi.editor.Editor editor = FileEditorManager.getInstance(project)
                        .openTextEditor(descriptor, true);
                if (editor != null) {
                    LogicalPosition logicalPos = new LogicalPosition(lineIndex, colIndex);
                    editor.getCaretModel().removeSecondaryCarets();
                    editor.getCaretModel().moveToLogicalPosition(logicalPos);
                    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                    editor.getSelectionModel().removeSelection();
                }
            }
        });
    }

    public static HttpBridgeService getInstance(@NotNull Project project) {
        return project.getService(HttpBridgeService.class);
    }
}
