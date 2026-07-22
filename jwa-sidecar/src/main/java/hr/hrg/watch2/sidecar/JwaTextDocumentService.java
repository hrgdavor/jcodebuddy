// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class JwaTextDocumentService implements TextDocumentService {
    private static final Logger log = LoggerFactory.getLogger(JwaTextDocumentService.class);

    private final JwaLanguageServer server;
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    private final JavaParser parser;

    public JwaTextDocumentService(JwaLanguageServer server) {
        this.server = server;
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(config);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        documents.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = documents.get(uri);
        if (text == null)
            return;

        for (var change : params.getContentChanges()) {
            if (change.getRange() == null) {
                text = change.getText();
            } else {
                text = applyIncrementalChange(text, change);
            }
        }
        documents.put(uri, text);
    }

    private String applyIncrementalChange(String text, TextDocumentContentChangeEvent change) {
        Range range = change.getRange();
        Position start = range.getStart();
        Position end = range.getEnd();

        int startOffset = getOffset(text, start.getLine(), start.getCharacter());
        int endOffset = getOffset(text, end.getLine(), end.getCharacter());

        StringBuilder sb = new StringBuilder(text.length() + change.getText().length() - (endOffset - startOffset));
        sb.append(text, 0, startOffset);
        sb.append(change.getText());
        sb.append(text, endOffset, text.length());
        return sb.toString();
    }

    private int getOffset(String text, int line, int character) {
        int offset = 0;
        for (int i = 0; i < line; i++) {
            int nextNewline = text.indexOf('\n', offset);
            if (nextNewline == -1) {
                return text.length();
            }
            offset = nextNewline + 1;
        }
        return Math.min(offset + character, text.length());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        documents.remove(params.getTextDocument().getUri());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = documents.get(uri);
        log.info("Document saved: {}", uri);
        if (text == null) {
            log.warn("Text not found for uri: {}", uri);
            return;
        }

        var parseResult = parser.parse(text);
        if (parseResult.isSuccessful()) {
            var cu = parseResult.getResult().get();
            cu.findAll(RecordDeclaration.class).stream()
                    .filter(r -> r.isAnnotationPresent("GenerateBuilder")
                            || r.isAnnotationPresent("hr.hrg.watch2.builder.api.GenerateBuilder"))
                    .forEach(r -> {
                        r.getRange().ifPresent(range -> {
                            log.info("Found record with @gen builder at line {}. Triggering sync...", range.begin.line);
                            syncBuilder(uri, range.begin.line);
                        });
                    });
        } else {
            log.error("Failed to parse document on save: {}", parseResult.getProblems());
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        String uri = params.getTextDocument().getUri();
        String text = documents.get(uri);
        if (text == null)
            return CompletableFuture.completedFuture(Collections.emptyList());

        int line = params.getRange().getStart().getLine() + 1;

        var parseResult = parser.parse(text);
        if (parseResult.isSuccessful()) {
            var cu = parseResult.getResult().get();
            var recordAtLine = cu.findAll(RecordDeclaration.class).stream()
                    .filter(r -> r.getName().getRange().isPresent() && r.getName().getRange().get().begin.line == line)
                    .findFirst();

            if (recordAtLine.isPresent()) {
                CodeAction action = new CodeAction("Sync Builder");
                action.setKind(CodeActionKind.Refactor);
                action.setCommand(new Command("Sync Builder", "jwa.syncBuilder", List.of(uri, line)));
                return CompletableFuture.completedFuture(List.of(Either.forRight(action)));
            }
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    public CompletableFuture<Object> syncBuilder(String uri, int line) {
        String text = documents.get(uri);
        if (text == null)
            return CompletableFuture.completedFuture(null);

        log.info("Generating builder for {} at line {}", uri, line);
        hr.hrg.watch2.builder.BuilderTransformationEngine engine = new hr.hrg.watch2.builder.BuilderTransformationEngine(
                "    ");
        hr.hrg.watch2.core.TransformationResult result = engine.generate(uri, text, line);

        if (result.edits().isEmpty()) {
            log.info("No changes needed for builder at {} line {}", uri, line);
            return CompletableFuture.completedFuture(null);
        }

        log.info("Applying {} edits to {}", result.edits().size(), uri);

        List<TextEdit> textEdits = new java.util.ArrayList<>();
        for (hr.hrg.watch2.core.CodeEdit edit : result.edits()) {
            Range range = new Range(
                    new Position(edit.startLine() - 1, edit.startCol() - 1),
                    new Position(edit.endLine() - 1, edit.endCol()));
            textEdits.add(new TextEdit(range, edit.newText()));
        }

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri, textEdits));

        ApplyWorkspaceEditParams editParams = new ApplyWorkspaceEditParams(workspaceEdit);
        server.getClient().applyEdit(editParams);

        return CompletableFuture.completedFuture(null);
    }
}
