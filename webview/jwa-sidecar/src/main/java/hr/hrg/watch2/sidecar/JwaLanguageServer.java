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
import java.util.concurrent.TimeUnit;

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
        // Corrected 2026-09-25 by the LSP navigation spike: this delegated to Navigator.openUrl(uri)
        // unconditionally, which takes the line from a "#L42" fragment and otherwise defaults to 1. The
        // sidecar's own /jump handler parsed ?line=&column= and passed them here, where they were dropped - so
        // a page asking for line 7 watched the editor open the file at line 1. The explicit position wins; the
        // fragment stays the fallback for a caller that only had a link to send.
        if (line > 1 || column > 1) {
            String withoutFragment = uri;
            int hash = uri.indexOf('#');
            if (hash >= 0) {
                withoutFragment = uri.substring(0, hash);
            }
            String localPath = hr.hrg.webview.core.UrlNormalizer.localPathOf(withoutFragment);
            return navigator.open(localPath != null ? localPath : withoutFragment, line, column);
        }
        return navigator.openUrl(uri);
    }

    /**
     * Applies edits to the client's <b>buffer</b> over LSP: {@code workspace/applyEdit}.
     *
     * <p>This is the counterpart of {@link #jump} for the write contract, and the reason the plan says Zed needs
     * no plugin: the change arrives in the editor's own undo stack and the file on disk is untouched until the
     * reader saves it. Phase 0 measured exactly that on the installed 1.21.0 — {@code applied: true}, a
     * {@code textDocument/didChange} back from the client, and unchanged bytes on disk.
     *
     * <p>The edits are sent as {@code documentChanges} with a {@code null} version, which asks the client to
     * apply them without checking its own version: the page verified the <em>file</em> with a digest, but the
     * buffer may legitimately differ from the file (unsaved edits of the reader's own), and refusing to apply
     * because of a version we cannot see would make the feature unusable. The positions are LSP ranges, so
     * one-based line and column become zero-based line and character — and a Java string index and an LSP
     * {@code character} are both UTF-16 code units, so no re-encoding is involved.
     *
     * @return what the client answered, false when there is no client or it refused
     */
    public boolean applyEdit(String uri, java.util.List<hr.hrg.webview.core.TextEdit> edits) {
        JwaLanguageClient current = client;
        if (current == null || edits == null || edits.isEmpty()) {
            return false;
        }
        java.util.List<TextEdit> lspEdits = new java.util.ArrayList<>(edits.size());
        for (hr.hrg.webview.core.TextEdit edit : edits) {
            lspEdits.add(new TextEdit(
                    new Range(new Position(edit.startLine() - 1, edit.startColumn() - 1),
                            new Position(edit.endLine() - 1, edit.endColumn() - 1)),
                    edit.newText()));
        }
        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        // documentChanges only: lsp4j would otherwise serialise an empty "changes":{}, and a client that prefers
        // that field over documentChanges would apply nothing while answering "applied": true.
        workspaceEdit.setChanges(null);
        workspaceEdit.setDocumentChanges(java.util.List.of(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextDocumentEdit(
                        new VersionedTextDocumentIdentifier(uri, (Integer) null), lspEdits))));
        ApplyWorkspaceEditParams params = new ApplyWorkspaceEditParams(workspaceEdit, "webview");
        try {
            ApplyWorkspaceEditResponse response = current.applyEdit(params).get(5, TimeUnit.SECONDS);
            if (response == null) {
                return false;
            }
            if (!response.isApplied()) {
                System.err.println("applyEdit refused by the client: " + response.getFailureReason());
            }
            return response.isApplied();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // A client that does not implement workspace/applyEdit answers with an error, or not at all. Both
            // mean "write it yourself", which is what the caller does with a false.
            System.err.println("applyEdit failed: " + e.getMessage());
            return false;
        }
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
            return Set.of(CAP_OPEN, CAP_SELECT, CAP_EDIT);
        }

        @Override
        public boolean applyEdit(String absolutePath, java.util.List<hr.hrg.webview.core.TextEdit> edits) {
            return JwaLanguageServer.this.applyEdit(toFileUri(absolutePath), edits);
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

    /**
     * True when a jump can actually land: a client is connected <em>and</em> it has completed
     * {@code initialize}, which is where the project root comes from.
     *
     * <p>The distinction matters to a page. Before this existed, the sidecar's {@code /health} advertised its
     * capabilities unconditionally, so a page that trusted it would offer navigation at a moment when
     * {@code /jump} could only answer {@code NO_HOST} — the same "capability that is advertised but not
     * implemented turns a fallback into a dead link" problem the contract names in section 4.
     */
    public boolean isEditorAttached() {
        return client != null && projectRoot != null;
    }

    /** The project root the path jail was built from, or null when the client sent none. */
    public String getProjectRoot() {
        return projectRoot;
    }
}
