package hr.hrg.webview.webviewd;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The real bridge: the JWA sidecar's loopback HTTP routes, called the way its own tests call them.
 *
 * <p>Two details that are not incidental:
 *
 * <ul>
 *   <li><b>Short timeouts.</b> A page's click must not hang because a sidecar process died without closing
 *       its socket. A stale port on Windows can swallow a connection silently, so the connect timeout is
 *       well under a second and a failure is reported rather than waited out.</li>
 *   <li><b>The token travels in the header, not the URL.</b> The sidecar accepts {@code ?token=} or
 *       {@code X-WebView-Token}; the header keeps the secret out of anything that logs request lines.</li>
 * </ul>
 */
public final class HttpSidecarClient implements SidecarClient {

    private final int port;
    private final String token;
    private final HttpClient http;

    public HttpSidecarClient(int port, String token) {
        this(port, token, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    HttpSidecarClient(int port, String token, HttpClient http) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("sidecar port must be 1..65535, was " + port);
        }
        this.port = port;
        this.token = token == null ? "" : token;
        this.http = http;
    }

    @Override
    public String describe() {
        return "http://127.0.0.1:" + port + " (jwa-sidecar)";
    }

    @Override
    public String health() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(base().resolve("/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public boolean jump(String absolutePath, int line, int column) {
        String uri = "file:///" + absolutePath.replace('\\', '/').replaceFirst("^/+", "");
        String query = "/jump?uri=" + URLEncoder.encode(uri, StandardCharsets.UTF_8)
                + "&line=" + Math.max(1, line)
                + "&column=" + Math.max(1, column);
        HttpRequest.Builder request = HttpRequest.newBuilder(base().resolve(query))
                .timeout(Duration.ofSeconds(3))
                .GET();
        if (!token.isEmpty()) {
            request.header("X-WebView-Token", token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean applyEdit(String absolutePath, java.util.List<hr.hrg.webview.core.TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return false;
        }
        String uri = "file:///" + absolutePath.replace('\\', '/').replaceFirst("^/+", "");
        StringBuilder json = new StringBuilder("{\"uri\":\"").append(uri).append("\",\"edits\":[");
        for (int i = 0; i < edits.size(); i++) {
            hr.hrg.webview.core.TextEdit edit = edits.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"startLine\":").append(edit.startLine())
                    .append(",\"startColumn\":").append(edit.startColumn())
                    .append(",\"endLine\":").append(edit.endLine())
                    .append(",\"endColumn\":").append(edit.endColumn())
                    .append(",\"newText\":\"").append(escape(edit.newText())).append("\"}");
        }
        json.append("]}");
        HttpRequest.Builder request = HttpRequest.newBuilder(base().resolve("/applyEdit"))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8));
        if (!token.isEmpty()) {
            request.header("X-WebView-Token", token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"applied\":true");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The smallest correct JSON string escaper for the edit text. */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + port);
    }
}
