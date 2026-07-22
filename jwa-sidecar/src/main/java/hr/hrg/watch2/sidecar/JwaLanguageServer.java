// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;

import java.util.concurrent.CompletableFuture;

public class JwaLanguageServer implements LanguageServer, LanguageClientAware {

    private final JwaTextDocumentService textDocumentService;
    private final JwaWorkspaceService workspaceService;
    private JwaLanguageClient client;

    public JwaLanguageServer() {
        this.textDocumentService = new JwaTextDocumentService(this);
        this.workspaceService = new JwaWorkspaceService(this);
    }

    public void jump(String uri, int line, int column) {
        if (client != null) {
            // 1. Custom notification for specialized clients
            client.jump(new JumpParams(uri, line, column));

            // 2. Standard LSP "showDocument" request for native IDE support
            ShowDocumentParams params = new ShowDocumentParams(uri);
            params.setTakeFocus(true);
            params.setSelection(new Range(new Position(line - 1, column - 1), new Position(line - 1, column - 1)));
            client.showDocument(params);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();

        // Define what this sidecar can do
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setCodeActionProvider(true);
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(java.util.List.of("jwa.syncBuilder")));

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
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
    }

    public JwaLanguageClient getClient() {
        return client;
    }
}
