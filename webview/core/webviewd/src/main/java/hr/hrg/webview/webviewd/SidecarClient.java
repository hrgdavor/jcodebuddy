package hr.hrg.webview.webviewd;

/**
 * The seam between {@code webviewd} and the process that holds the editor's LSP connection.
 *
 * <p>Why a hop exists at all: on Zed the caret can only be moved by an LSP <em>server</em>, because
 * {@code window/showDocument} is a server-to-client request. The process Zed spawns is the sidecar, so
 * navigation ordered by a page has to reach that process. Today it does so over the sidecar's loopback HTTP
 * route; the plan's § 6.3 and question 4 would fold both faces into one process, and this interface is what
 * makes that a wiring change rather than a rewrite.
 *
 * <p>Both methods are deliberately dumb: {@link #health()} returns the sidecar's own {@code /health}
 * document (or null), and {@link #jump} performs one navigation. Deciding what that document means — whether
 * an editor is attached, whether this host may advertise {@code open} — belongs to {@link LspHost}, and is
 * tested without a socket.
 */
public interface SidecarClient {

    /** Where this client points, for logs and for the descriptor. Never null. */
    String describe();

    /**
     * The sidecar's {@code /health} body, or null when it does not answer. The body is the shared
     * {@code HostHealth} document, so its {@code capabilities} array is what decides whether navigation may
     * be advertised at all.
     */
    String health();

    /**
     * Asks the sidecar to move the editor to a file and a one-based position.
     *
     * @return true when the sidecar accepted the request, which is not the same as the editor having moved:
     *         the LSP round trip that follows is described in {@link LspHost#lineNavigationNote()}
     */
    boolean jump(String absolutePath, int line, int column);

    /**
     * Asks the sidecar to have an edit applied in the editor's <b>buffer</b> over LSP, so the reader sees it in
     * the editor's own undo stack and the file on disk is untouched until they save.
     *
     * @return true only when the editor reported that it applied the edit; anything else means the caller should
     *         write the file itself
     */
    boolean applyEdit(String absolutePath, java.util.List<hr.hrg.webview.core.TextEdit> edits);
}
