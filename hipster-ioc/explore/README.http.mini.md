# HTTP and SSE with builtin Java classes

TODO: test, produced by AI (looks legit on first glance)

You can set a custom Executor on the HttpServer to an executor that creates virtual threads

Here is a minimal example demonstrating how to implement a Server-Sent Events (SSE) server using Java's built-in HttpServer:

```java
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class SimpleSseServer {

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/sse", new SseHandler());

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("SSE server started at http://localhost:8080/sse");
    }

    static class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Set response headers for SSE
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");

            // Send HTTP 200 response headers, no content length for streaming
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()){
                // Send 5 SSE messages with 1 second interval
                for (int i = 1; i <= 5; i++) {
                    String msg = "data: Message " + i + "\n\n";
                    os.write(msg.getBytes());
                    os.flush();
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
```

## Explanation

- The server listens on port 8080 and exposes an endpoint at `/sse`.
- The `SseHandler` sets the required SSE headers including `Content-Type: text/event-stream`.
- The response is sent with no fixed content length to keep the connection open for streaming.
- The server sends 5 SSE messages spaced by 1 second each.
- Each message is prefixed with `data:` and ends with two newlines as required by the SSE protocol.
- The connection is closed after sending the messages.

This minimal example demonstrates the basic technique to implement SSE using Java's built-in HttpServer without dependencies or frameworks.