// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run;

/**
 * Lifecycle hook that target applications can implement to receive a graceful
 * shutdown signal before the daemon reloads a new version.
 *
 * <p>The daemon will call {@link #stop()} on any bean/object that was registered
 * via {@link StopRegistry#register(Stoppable)}.  The call happens synchronously
 * on the watcher thread, so implementations should complete quickly (close sockets,
 * flush buffers, interrupt background threads, etc.) and avoid blocking indefinitely.
 *
 * <p>Example usage inside the reloaded application:
 * <pre>{@code
 * public class MyServer implements Stoppable {
 *     private ServerSocket serverSocket;
 *
 *     public void start() throws Exception {
 *         StopRegistry.register(this);    // hook into the daemon lifecycle
 *         serverSocket = new ServerSocket(8080);
 *         // ... accept loop ...
 *     }
 *
 *     @Override
 *     public void stop() {
 *         try { serverSocket.close(); } catch (IOException ignored) {}
 *     }
 * }
 * }</pre>
 */
public interface Stoppable {
    /**
     * Called by the daemon to signal that this run is ending and a new one
     * is about to start.  Implementations must be non-blocking and should
     * complete within a few seconds.
     */
    void stop();
}
