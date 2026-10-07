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
import org.eclipse.lsp4j.DidChangeConfigurationParams;
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

            @hr.hrg.jcodebuddy.builder.api.GenerateBuilder
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

    /**
     * Plan step 7.1's gate: a two-space client produces two-space output.
     *
     * <p>It is asserted as a <b>relationship between two runs</b> rather than against a golden string, because the
     * defect this step removes was a hard-coded indent: a test that pinned one output would pass again the moment
     * somebody hard-coded the other. Two configurations are run over the same record, and every line the engine
     * emitted must have the SAME text with HALF the indentation — which is what "the client's settings decided it"
     * means, and it cannot hold if the indent is a constant.
     */
    @Test
    public void aTwoSpaceClientProducesTwoSpaceOutput() throws Exception {
        String fourSpace = generatedBuilderWith(ClientFormatting.defaults());
        String twoSpace = generatedBuilderWith(new ClientFormatting(2, true));

        List<String> wide = fourSpace.lines().toList();
        List<String> narrow = twoSpace.lines().toList();
        assertEquals("both runs generate the same lines", wide.size(), narrow.size());

        int compared = 0;
        for (int index = 0; index < wide.size(); index++) {
            String wideLine = wide.get(index);
            String narrowLine = narrow.get(index);
            assertEquals("line " + index + " differs beyond its indentation",
                    wideLine.stripLeading(), narrowLine.stripLeading());
            assertEquals("line " + index + " is not indented at half the width: [" + wideLine + "] vs ["
                            + narrowLine + "]",
                    indentOf(wideLine), indentOf(narrowLine) + indentOf(narrowLine));
            compared++;
        }
        assertEquals("the generated text must have lines to compare", wide.size(), compared);
        assertTrue("a four-space run must actually be indented, or the comparison proves nothing:\n" + fourSpace,
                indentOf(fourSpace).length() == 4);
        assertTrue("and a two-space run must be indented by two:\n" + twoSpace, indentOf(twoSpace).length() == 2);
    }

    /** Tabs are the other half of {@code insertSpaces}, and the engine must be given one tab, not one space. */
    @Test
    public void aTabClientProducesTabOutput() throws Exception {
        String tabs = generatedBuilderWith(new ClientFormatting(4, false));
        assertTrue("a tab-configured client must get a tab, not spaces:\n" + tabs, indentOf(tabs).startsWith("\t"));
    }

    /** The leading whitespace of the first line that has any — the engine's own indent step, measured. */
    private static String indentOf(String text) {
        for (String line : text.lines().toList()) {
            if (!line.isBlank() && (line.startsWith(" ") || line.startsWith("\t"))) {
                return line.substring(0, line.length() - line.stripLeading().length());
            }
        }
        return "";
    }

    /** Run the Sync Builder command over {@link #ANNOTATED} with one client formatting configuration. */
    private static String generatedBuilderWith(ClientFormatting formatting) throws Exception {
        JwaLanguageServer server = new JwaLanguageServer();
        RecordingClient recorder = new RecordingClient();
        server.connect(recorder.asClient());
        server.initialize(new InitializeParams()).get();
        // The client's settings arrive the way a real one sends them, through the workspace verb, and BEFORE the
        // document is open - which is the order both VS Code and IntelliJ use at startup.
        server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(
                settingsFor(formatting)));
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(URI, "java", 1, ANNOTATED)));

        List<Either<Command, CodeAction>> offered = server.getTextDocumentService()
                .codeAction(at(NAME_LINE_ZERO_BASED)).get();
        Command command = offered.get(0).getRight().getCommand();
        server.getWorkspaceService().executeCommand(
                new ExecuteCommandParams(command.getCommand(), command.getArguments())).get();

        WorkspaceEdit edit = recorder.lastWorkspaceEdit();
        assertNotNull("the command must deliver an edit for there to be any output to measure", edit);
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertNotNull(edits);
        StringBuilder generated = new StringBuilder();
        for (TextEdit textEdit : edits) {
            generated.append(textEdit.getNewText());
        }
        assertTrue("a generated builder is the point, so it cannot be empty", generated.length() > 0);
        return generated.toString();
    }

    /** The settings object a real client sends: flat, as the LSP specification's example is. */
    private static com.google.gson.JsonObject settingsFor(ClientFormatting formatting) {
        com.google.gson.JsonObject settings = new com.google.gson.JsonObject();
        settings.addProperty("tabSize", formatting.tabSize());
        settings.addProperty("insertSpaces", formatting.insertSpaces());
        return settings;
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
