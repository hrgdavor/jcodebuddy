package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * The entry point: parse the command line, refuse to start twice for the same project, publish the
 * descriptor, serve, and clean the descriptor up on the way out.
 *
 * <p>Exit codes are part of the interface here, because a second instance is a normal thing to attempt from a
 * script: {@code 0} success, {@code 1} I/O or bind failure, {@code 2} a usage error or the
 * already-running case. The already-running case deliberately is not a crash — the caller most often wants the
 * port of the host that is already there, and the message says where to read it.
 */
public final class WebviewdMain {

    private WebviewdMain() {
    }

    public static void main(String[] args) {
        WebviewdConfig config;
        try {
            config = WebviewdConfig.parse(args);
        } catch (WebviewdConfig.HelpRequested help) {
            System.out.println(WebviewdConfig.USAGE);
            return;
        } catch (IllegalArgumentException e) {
            System.err.println("webviewd: " + e.getMessage());
            System.err.println();
            System.err.println(WebviewdConfig.USAGE);
            System.exit(2);
            return;
        }

        EditorHost host;
        try {
            host = WebviewServer.selectHost(config);
        } catch (IllegalStateException e) {
            System.err.println("webviewd: " + e.getMessage());
            System.exit(2);
            return;
        }

        Path tokenFile = HostDescriptor.directoryOf(config.project()).resolve("token");
        if (config.printManifestOnly()) {
            System.out.println(WebviewServer.manifestJson(config.project(), host, config.port(), tokenFile));
            return;
        }

        HostDescriptor existing = HostDescriptor.read(config.project());
        if (HostDescriptor.blocksStart(existing)) {
            System.err.println("webviewd: " + existing.conflictMessage());
            System.exit(2);
            return;
        }

        Thread cleanup = new Thread(() -> {
            try {
                HostDescriptor.delete(config.project());
            } catch (IOException e) {
                System.err.println("webviewd: could not remove the descriptor: " + e.getMessage());
            }
        }, "webviewd-descriptor-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);

        try (WebviewServer server = WebviewServer.start(config, host)) {
            server.descriptor().write(config.project());
            System.out.println("webviewd: serving " + server.baseUrl() + " for " + config.project());
            System.out.println("webviewd: adapter " + host.name()
                    + ", line navigation " + server.lineNavigation()
                    + ", capabilities " + (server.capabilities().isEmpty()
                            ? "none" : String.join(", ", server.capabilities())));
            System.out.println("webviewd: descriptor " + HostDescriptor.fileOf(config.project()));
            System.out.println("webviewd: token file " + server.tokenFile());
            if (config.open()) {
                openInBrowser(server.baseUrl());
            }
            new CountDownLatch(1).await();
        } catch (IOException e) {
            System.err.println("webviewd: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException ignored) {
                // Already shutting down: the hook is running or has run, which is what we wanted anyway.
            }
            cleanup.run();
        }
    }

    /** Opens the host's index page, and says where to look when the desktop cannot do it for us. */
    static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            System.err.println("webviewd: could not open a browser (" + e.getMessage() + ")");
        }
        System.out.println("webviewd: open " + url + " in a browser");
    }
}
