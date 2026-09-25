// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JwaWorkspaceService implements WorkspaceService {

    private final JwaLanguageServer server;

    public JwaWorkspaceService(JwaLanguageServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        if ("jwa.syncBuilder".equals(params.getCommand())) {
            List<Object> args = params.getArguments();
            String uri = extractString(args.get(0));
            int line = extractInt(args.get(1));

            return server.getTextDocumentService().syncBuilder(uri, line);
        }
        return CompletableFuture.completedFuture(null);
    }

    private String extractString(Object obj) {
        if (obj instanceof String)
            return (String) obj;
        if (obj instanceof com.google.gson.JsonPrimitive) {
            return ((com.google.gson.JsonPrimitive) obj).getAsString();
        }
        return String.valueOf(obj);
    }

    private int extractInt(Object obj) {
        if (obj instanceof Number)
            return ((Number) obj).intValue();
        if (obj instanceof com.google.gson.JsonPrimitive) {
            return ((com.google.gson.JsonPrimitive) obj).getAsInt();
        }
        return 0;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }
}
