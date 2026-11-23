// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorNotifierTest {

    @Test
    void postsTheWrittenPathToTheHostRefreshRoute() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/refresh", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-WebView-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            EditorNotifier notifier = new EditorNotifier(server.getAddress().getPort(), "test-token");
            Path file = Path.of("src", "A.java").toAbsolutePath().normalize();
            notifier.changed(file);
            assertTrue(received.await(3, TimeUnit.SECONDS), "the host refresh route was not called");
            assertEquals("test-token", token.get());
            assertTrue(body.get().contains(file.toString().replace('\\', '/')),
                    "the refresh body must name the written file: " + body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loopbackRefreshWorksWithoutAHostToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/refresh", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            new EditorNotifier(server.getAddress().getPort(), "").changed(Path.of("A.java"));
            assertTrue(received.await(3, TimeUnit.SECONDS),
                    "the agent must notify a loopback host even when the host published no token");
            assertTrue(body.get().contains("A.java"));
        } finally {
            server.stop(0);
        }
    }
}
