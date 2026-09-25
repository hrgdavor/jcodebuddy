package hr.hrg.webview.core;

import java.util.Set;

/**
 * What a host must implement to be driven by a page.
 *
 * <p>This is the seam that makes "headless mode should not be lacking any features" checkable instead of
 * aspirational. A page never talks to a host directly: it calls the sidecar's HTTP surface, the sidecar
 * calls {@link Navigator}, and {@code Navigator} calls this interface. Each host answers {@link
 * #capabilities()} for itself, and that answer is what a page reads from {@code /health} to decide
 * which rung of its fallback ladder to use.
 *
 * <p>Every method is called on whatever thread the transport happens to use (the EDT for a JetBrains
 * injected call, an executor thread for HTTP). An implementation that must run on a UI thread — every
 * IDE — is responsible for marshalling; {@link Navigator} deliberately does no thread handling, because
 * only the host knows which thread that is.
 */
public interface EditorHost {

    /** The capability key for {@link #openFileAt}. */
    String CAP_OPEN = "open";
    /** The capability key for {@link #reveal}. */
    String CAP_REVEAL = "reveal";
    /** The capability key for {@link #select}. */
    String CAP_SELECT = "select";

    /**
     * A short name for the logs and for {@code /health} — {@code "jetbrains"}, {@code "zed-cli"},
     * {@code "lsp"}, {@code "null"}.
     */
    String name();

    /**
     * True when this host can act at all right now. A ZED host whose CLI is not on the PATH answers
     * false, and a page must then fall back rather than render a link that does nothing.
     */
    boolean isAvailable();

    /** Which of {@link #CAP_OPEN}, {@link #CAP_REVEAL} and {@link #CAP_SELECT} this host implements. */
    Set<String> capabilities();

    /**
     * Opens {@code absolutePath} and puts the caret on a one-based line and column.
     *
     * <p>Named {@code openFileAt} rather than {@code open} because an IDE host already has a public
     * {@code open(path, line, column)} that takes a <em>page's</em> spelling of the path (relative or
     * absolute) and resolves it. That method and this one would have the same erasure, so a host could
     * not implement both; the resolved path is what this interface deals in, and the name says so.
     *
     * @param absolutePath an absolute, forward-slashed path that has already passed the path jail
     * @return true when an editor was actually opened; false makes the caller answer 404, so a wrong
     *         link is diagnosable instead of silent (contract § 7, item 6)
     */
    boolean openFileAt(String absolutePath, int line, int column);

    /**
     * Reveals an already-open (or openable) file in the host's own navigation UI — the project view in
     * an IDE, the file tree in an editor. Not part of the frozen contract; a host without such a UI
     * omits {@link #CAP_REVEAL}.
     */
    default boolean reveal(String absolutePath) {
        return false;
    }

    /**
     * Selects a span without moving the caret's file — used by a page that wants to highlight the code a
     * reader is looking at.
     */
    default boolean select(String absolutePath, TextRange range) {
        return false;
    }
}
