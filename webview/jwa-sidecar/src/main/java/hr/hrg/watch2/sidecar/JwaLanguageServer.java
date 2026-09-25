// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.NavigationOutcome;
import hr.hrg.webview.core.Navigator;
import hr.hrg.webview.core.RateLimiter;
import hr.hrg.webview.core.TextRange;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The sidecar's LSP server.
 *
 * <p>Navigation is delegated to {@code webview-core}'s {@link Navigator}, so a jump from a page is
 * rate-limited, path-resolved and confined by exactly the same code that serves every other host. What
 * this class adds is the LSP wiring: two ways to tell a capable client where to go.
 *
 * <ul>
 *   <li>{@code window/showDocument} — the standard, with {@code takeFocus} and a {@code selection} range.
 *       Zed implements it (confirmed in {@code crates/project/src/lsp_store.rs}), which is what lets a
 *       browser window beside Zed move Zed's caret with no Zed-side plugin at all.
 *   <li>{@code mytool/jump} — a custom notification kept for clients that predate the standard route, or
 *       that want the jump without moving focus.
 * </ul>
 */
public class JwaLanguageServer implements LanguageServer, LanguageClientAware {

    private final JwaTextDocumentService textDocumentService;
    private final JwaWorkspaceService workspaceService;
    private JwaLanguageClient client;

    /** Where the client's project is, from {@code initialize}; the path jail is built from it. */
    private String projectRoot;

    private Navigator navigator;

    public JwaLanguageServer() {
        this.textDocumentService = new JwaTextDocumentService(this);
        this.workspaceService = new JwaWorkspaceService(this);
    }

    /**
     * Moves the client's editor to a file and a one-based position.
     *
     * <p>Rate limit, path resolution and confinement all happen in {@link Navigator}; this method reports
     * what happened instead of assuming success, which is what lets the HTTP caller answer 404 for a file
     * that does not exist rather than a cheerful "ok".
     */
    public NavigationOutcome jump(String uri, int line, int column) {
        if (navigator == null) {
            return NavigationOutcome.refused(NavigationOutcome.Reason.NO_HOST, uri,
                    "the client has not completed initialize yet");
        }
        return navigator.openUrl(uri);
    }

    /** The host half of the LSP transport: what "opening a file" means for an LSP client. */
    private final class LspClientHost implements EditorHost {

        @Override
        public String name() {
            return "lsp";
        }

        @Override
        public boolean isAvailable() {
            return client != null;
        }

        @Override
        public Set<String> capabilities() {
            // CAP_REVEAL is omitted: nothing here has been verified to honour it, and a capability that
            // is advertised but not implemented turns a page's fallback into a dead link.
            return Set.of(CAP_OPEN, CAP_SELECT);
        }

        @Override
        public boolean openFileAt(String absolutePath, int line, int column) {
            JwaLanguageClient current = client;
            if (current == null) {
                return false;
            }
            String fileUri = toFileUri(absolutePath);

            // 1. Custom notification for specialized clients
            current.jump(new JumpParams(fileUri, line, column));

            // 2. Standard LSP "showDocument" request for native IDE support
            ShowDocumentParams params = new ShowDocumentParams(fileUri);
            params.setTakeFocus(true);
            params.setSelection(new Range(
                    new Position(line - 1, column - 1), new Position(line - 1, column - 1)));
            current.showDocument(params);
            return true;
        }

        @Override
        public boolean select(String absolutePath, TextRange range) {
            JwaLanguageClient current = client;
            if (current == null) {
                return false;
            }
            ShowDocumentParams params = new ShowDocumentParams(toFileUri(absolutePath));
            params.setTakeFocus(false);
            params.setSelection(new Range(
                    new Position(range.startLine() - 1, range.startColumn() - 1),
                    new Position(range.endLine() - 1, range.endColumn() - 1)));
            current.showDocument(params);
            return true;
        }

        /** A {@code file:} URI for an absolute path that already carries forward slashes. */
        private String toFileUri(String absolutePath) {
            return URI.create("file:///" + absolutePath.replace("\\", "/").replaceFirst("^/+", ""))
                    .toString();
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        projectRoot = rootPathOf(params);

        ServerCapabilities capabilities = new ServerCapabilities();

        // Define what this sidecar can do
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setCodeActionProvider(true);
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(java.util.List.of("jwa.syncBuilder")));

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    /**
     * The workspace root, from whichever of the fields the client filled in: {@code workspaceFolders} is
     * the modern one, {@code rootUri}/{@code rootPath} the older pair. A client that sends none of them
     * leaves the sidecar without a jail, in which case every path is allowed — the same rule as any host
     * with no project (see {@code PathResolver}).
     */
    private static String rootPathOf(InitializeParams params) {
        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            return pathOf(params.getWorkspaceFolders().get(0).getUri());
        }
        if (params.getRootUri() != null) {
            return pathOf(params.getRootUri());
        }
        return params.getRootPath();
    }

    private static String pathOf(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            return java.nio.file.Paths.get(URI.create(uri)).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public void setTrace(SetTraceParams params) {
        // No-op to avoid UnsupportedOperationException from default implementation
    }

    @Override
    public JwaTextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = (JwaLanguageClient) client;
        // true: this process also serves pages over HTTP, so a caller can be any page in the user's
        // browser, and the contract's rule for that surface is to refuse anything outside the project.
        this.navigator = new Navigator(projectRoot, new LspClientHost(), true,
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS,
                        hr.hrg.webview.core.Clock.SYSTEM));
    }

    public JwaLanguageClient getClient() {
        return client;
    }

    /** The project root the path jail was built from, or null when the client sent none. */
    public String getProjectRoot() {
        return projectRoot;
    }
}
