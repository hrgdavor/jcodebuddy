package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.HostConfig;
import hr.hrg.webview.core.HostDescriptor;
import hr.hrg.webview.core.HostPortClaim;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * The entry point: parse the command line, claim a port for this project, publish the descriptor, serve, and
 * clean the descriptor up on the way out.
 *
 * <p>Exit codes are part of the interface here, because a second instance is a normal thing to attempt from a
 * script: {@code 0} success, {@code 1} I/O or bind failure (including "every port in the range was taken"),
 * {@code 2} a usage error or the already-served case. The already-served case deliberately is not a crash —
 * the caller most often wants the port of the host that is already there, and the message says where to read
 * it.
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
        // Resolved once, before anything can change what the files say: the manifest a caller prints and the
        // port this run asks for must be the same decision, or `--print-manifest` would lie about it.
        HostConfig.RequestedPort requested = resolveRequestedPort(config);
        if (config.printManifestOnly()) {
            System.out.println(WebviewServer.manifestJson(config.project(), host, requested.port(), tokenFile));
            return;
        }

        // A `--sticky` / `--no-sticky` on the command line is a decision about the *project's port*, not about
        // this process, so it is written down even when this run ends up not serving: the next start must see
        // it, and a user who asked for a pin should not have to keep a host running to keep it.
        HostDescriptor existing = HostDescriptor.read(config.project());
        if (config.sticky() != null) {
            if (existing == null) {
                System.err.println("webviewd: nothing is published for this project yet, so "
                        + (config.sticky() ? "the port this run binds will be pinned" : "there is no pin to clear"));
            } else if (existing.sticky() != config.sticky()) {
                try {
                    existing.withSticky(config.sticky()).write(config.project());
                    System.err.println("webviewd: " + (config.sticky() ? "pinned" : "unpinned")
                            + " port " + existing.port() + " for this project ("
                            + HostDescriptor.fileOf(config.project()) + ")");
                } catch (IOException e) {
                    System.err.println("webviewd: could not " + (config.sticky() ? "pin" : "unpin")
                            + " the port: " + e.getMessage());
                }
                existing = HostDescriptor.read(config.project());
            }
        }

        // Which port this host serves on is decided here, once, and never re-read from the config: the
        // requested port may be taken by an unrelated application (then the next free one is used), by another
        // host already serving this project (then there is nothing left to start), or pinned by the project
        // (then nothing else is acceptable, and a taken port is an error).
        boolean sticky = config.sticky() != null
                ? config.sticky()
                : existing != null && existing.sticky();
        Integer stickyPort = sticky && existing != null && existing.port() > 0 ? existing.port() : null;

        HostPortClaim.Decision claim;
        try {
            claim = HostPortClaim.claim(config.project(), requested.port(), stickyPort,
                    HostPortClaim.DEFAULT_ATTEMPTS, HostPortClaim.systemAttempt(), HostPortClaim::occupantOf);
        } catch (RuntimeException e) {
            System.err.println("webviewd: could not decide which port to use: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (claim.failed()) {
            // The instructions cannot be honoured, and moving would be a silent lie about a port somebody
            // chose on purpose. `2` is the same code a usage error gets: this run did not do what it was told.
            System.err.println("webviewd: " + claim.reason());
            System.exit(2);
            return;
        }
        if (claim.skipped()) {
            System.err.println("webviewd: " + claim.reason());
            HostDescriptor current = HostDescriptor.read(config.project());
            if (current != null && current.isLive()) {
                // The documented already-running case: not a crash, and the message says where the port is.
                System.err.println("webviewd: " + current.conflictMessage());
                System.exit(2);
            }
            System.exit(1);
            return;
        }
        if (claim.moved() || claim.sticky()) {
            System.err.println("webviewd: " + claim.reason());
        }

        // No shutdown hook, and no cleanup of the descriptor: it outlives this process on purpose. It is the
        // project's port rather than this process's record — what the next start asks for, and where a
        // `sticky` pin lives — so removing it on the way out would make an ephemeral port change on every
        // restart and would silently drop the pin. "Is a host there?" is answered by probing the port, never
        // by this file (HostDescriptor, HostPortClaim).
        try (WebviewServer server = WebviewServer.start(config, host, claim.port())) {
            server.descriptor(sticky).write(config.project());
            System.out.println("webviewd: serving " + server.baseUrl() + " for " + config.project());
            System.out.println("webviewd: adapter " + host.name()
                    + ", line navigation " + server.lineNavigation()
                    + ", capabilities " + (server.capabilities().isEmpty()
                            ? "none" : String.join(", ", server.capabilities())));
            System.out.println("webviewd: descriptor " + HostDescriptor.fileOf(config.project())
                    + " (kept when this host stops: it is the port this checkout is on)");
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

    /**
     * The port this run asks for, resolved by the shared rule: what the command line named, otherwise the port
     * this <b>checkout</b> is currently on ({@code .jcodebuddy/webview/host.json} — local, never in git),
     * otherwise the project's committed <b>default</b> ({@code .jcodebuddy/conf/webview.json} — optional), and
     * otherwise port 0, "pick a free one and publish it".
     *
     * <p>The current port beats the committed default on purpose: a checkout that had to take the next free
     * port (a second worktree, an unrelated application on the conventional one) keeps it, instead of
     * colliding with whatever holds the default on every start. Delete {@code host.json} to go back to the
     * default.
     */
    private static HostConfig.RequestedPort resolveRequestedPort(WebviewdConfig config) {
        HostConfig.RequestedPort requested = HostConfig.requestedPort(config.project(),
                config.portSpecified() ? config.port() : null, 0);
        if (!requested.problem().isEmpty()) {
            System.err.println("webviewd: " + requested.problem());
        }
        String note = requested.describe(config.project());
        if (!note.isEmpty()) {
            System.err.println("webviewd: " + note);
        }
        return requested;
    }
}
