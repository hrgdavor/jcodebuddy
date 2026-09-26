// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.sidecar;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The JWA code action a client is offered: "Sync Builder" on a record's own name, and nothing anywhere else.
 *
 * <p>This closes the last item the support matrix in {@code doc/webview-host-api.md} listed as a gap: the action
 * was implemented and advertised, and nothing asserted it. The two halves that can rot independently are checked
 * together on purpose — the action must be offered <em>where</em> the caret is on an annotated record's name, and
 * the command it offers must be one the server actually advertises in its {@code executeCommandProvider} and
 * implements in {@link JwaWorkspaceService}. An action pointing at a command nobody handles looks identical to a
 * working one from a test that only checks the title.
 */
public class SidecarCodeActionTest {

    private static final String URI = "file:///D:/wrk/project/src/Person.java";

    /** A record the processor recognises: the annotation is fully qualified, so no import has to resolve. */
    private static final String ANNOTATED = """
            package demo;

            @hr.hrg.watch2.builder.api.GenerateBuilder
            public record Person(String name, int age) {
            }
            """;

    /** The annotation's own line; the action must NOT be offered here. */
    private static final int ANNOTATION_LINE_ZERO_BASED = 2;

    /** The record's name sits on the fourth line, which is the one the caret has to be on. */
    private static final int NAME_LINE_ZERO_BASED = 3;

    /** Records the {@code workspace/applyEdit} a generated builder is delivered through, and answers it. */
    private static final class RecordingClient implements InvocationHandler {
        private final List<Object> arguments = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("applyEdit".equals(method.getName())) {
                arguments.add(args[0]);
                return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true));
            }
            return null;
        }

        JwaLanguageClient asClient() {
            return (JwaLanguageClient) Proxy.newProxyInstance(
                    JwaLanguageClient.class.getClassLoader(), new Class<?>[] {JwaLanguageClient.class}, this);
        }

        WorkspaceEdit lastWorkspaceEdit() {
            for (Object argument : arguments) {
                if (argument instanceof ApplyWorkspaceEditParams params) {
                    return params.getEdit();
                }
            }
            return null;
        }
    }

    private static JwaLanguageServer serverWith(String text) {
        JwaLanguageServer server = new JwaLanguageServer();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(URI, "java", 1, text)));
        return server;
    }

    private static CodeActionParams at(int zeroBasedLine) {
        return new CodeActionParams(
                new TextDocumentIdentifier(URI),
                new Range(new Position(zeroBasedLine, 0), new Position(zeroBasedLine, 0)),
                new CodeActionContext(Collections.emptyList()));
    }

    @Test
    public void offersSyncBuilderOnTheRecordNameLine() throws Exception {
        JwaLanguageServer server = serverWith(ANNOTATED);

        List<Either<Command, CodeAction>> offered = server.getTextDocumentService()
                .codeAction(at(NAME_LINE_ZERO_BASED)).get();

        assertEquals("exactly one action, on the record's own name", 1, offered.size());
        CodeAction action = offered.get(0).getRight();
        assertNotNull("the offer must be a CodeAction, not a bare Command", action);
        assertEquals("Sync Builder", action.getTitle());
        assertEquals(CodeActionKind.Refactor, action.getKind());

        Command command = action.getCommand();
        assertNotNull("an action with no command does nothing when a user picks it", command);
        assertEquals("jwa.syncBuilder", command.getCommand());
        assertEquals("the command is given the document and the line, in that order",
                List.of(URI, NAME_LINE_ZERO_BASED + 1), command.getArguments());
    }

    /**
     * The whole path a user triggers by picking the action: the advertised command, the handler, the builder that
     * gets generated, and the {@code workspace/applyEdit} that puts it in the editor.
     *
     * <p>It is one test rather than four assertions in four places because the failure it exists for is a
     * <em>disconnection</em>: the offer lives in {@link JwaTextDocumentService}, the handler in
     * {@link JwaWorkspaceService}, the command name in {@link JwaLanguageServer#initialize}, and the edits in the
     * builder engine. Each can be right on its own while the chain does nothing.
     */
    @Test
    public void pickingTheActionGeneratesABuilderAndAppliesItToTheEditor() throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();
        RecordingClient recorder = new RecordingClient();
        server.connect(recorder.asClient());
        InitializeResult initialized = server.initialize(new InitializeParams()).get();

        List<String> commands = initialized.getCapabilities().getExecuteCommandProvider().getCommands();
        assertTrue("the server must advertise the command its code action offers, or a client refuses to run it: "
                + commands, commands.contains("jwa.syncBuilder"));

        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(URI, "java", 1, ANNOTATED)));

        // The action as a client would run it: the command it was given, with the arguments it carried.
        List<Either<Command, CodeAction>> offered = server.getTextDocumentService()
                .codeAction(at(NAME_LINE_ZERO_BASED)).get();
        Command command = offered.get(0).getRight().getCommand();
        server.getWorkspaceService().executeCommand(
                new ExecuteCommandParams(command.getCommand(), command.getArguments())).get();

        WorkspaceEdit edit = recorder.lastWorkspaceEdit();
        assertNotNull("picking the action must reach the editor as workspace/applyEdit", edit);
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertNotNull("and the edits must name the document that was open: " + edit.getChanges(), edits);
        assertTrue("a generated builder is the point of the command, so there must be edits", !edits.isEmpty());
    }

    @Test
    public void offersNothingOnTheAnnotationLine() throws Exception {
        JwaLanguageServer server = serverWith(ANNOTATED);

        List<Either<Command, CodeAction>> offered = server.getTextDocumentService()
                .codeAction(at(ANNOTATION_LINE_ZERO_BASED)).get();

        // The caret's line, exactly: "@GenerateBuilder" is not the record's name, and offering the action there
        // would make it appear wherever a user rested the cursor near the declaration.
        assertEquals(Collections.emptyList(), offered);
    }

    /**
     * The action is offered on an **unannotated** record's name too, and that is deliberate rather than an
     * oversight — {@code RecordBuilderProcessor.recordOnLine} says so in its own javadoc: a code action is an
     * explicit user request, so an exact-line match on a record's name is enough, while the five-line window
     * exists for a caret that is merely nearby.
     *
     * <p>The *automatic* path is the one that filters: the change handler syncs only the records
     * {@code annotatedRecords} returns, i.e. the ones carrying {@code @GenerateBuilder}. This test pins the
     * asymmetry, because a reader who assumes one rule covers both halves will be wrong about one of them.
     */
    @Test
    public void offersTheActionOnAnUnannotatedRecordTooBecauseTheUserAsked() throws Exception {
        String plain = """
                package demo;

                public record Person(String name, int age) {
                }
                """;
        // The name line of this record is the third line, zero-based 2.
        JwaLanguageServer server = serverWith(plain);

        List<Either<Command, CodeAction>> offered = server.getTextDocumentService()
                .codeAction(at(2)).get();

        assertEquals("a manual request needs no annotation; the automatic sync is what filters on one",
                1, offered.size());
        assertEquals("Sync Builder", offered.get(0).getRight().getTitle());
    }

    /** A document the sidecar has never been told about cannot be answered about; empty, not an exception. */
    @Test
    public void offersNothingForADocumentItHasNotOpened() throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();

        List<Either<Command, CodeAction>> offered = server.getTextDocumentService()
                .codeAction(at(NAME_LINE_ZERO_BASED)).get();

        assertEquals(Collections.emptyList(), offered);
    }
}
