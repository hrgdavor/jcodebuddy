import {
    createConnection,
    TextDocuments,
    DiagnosticSeverity,
    ProposedFeatures,
    DidChangeConfigurationNotification,
    TextDocumentSyncKind,
} from 'vscode-languageserver/node';

import type {
    Diagnostic,
    InitializeParams,
    InitializeResult
} from 'vscode-languageserver/node';

import { TextDocument } from 'vscode-languageserver-textdocument';

// Create a connection for the server, using Node's IPC as a transport.
const connection = createConnection(ProposedFeatures.all);

// Create a simple text document manager.
const documents: TextDocuments<TextDocument> = new TextDocuments(TextDocument);

connection.onInitialize((params: InitializeParams) => {
    const result: InitializeResult = {
        capabilities: {
            textDocumentSync: TextDocumentSyncKind.Incremental,
            // Add capabilities as needed for signals/boilerplate
            completionProvider: {
                resolveProvider: true,
                triggerCharacters: ['.', ':']
            },
            codeActionProvider: true
        }
    };
    return result;
});

connection.onInitialized(() => {
    connection.console.log('JSWA Sidecar initialized');
});

// The content of a text document has changed. This event is emitted
// when the text document first opened or when its content has changed.
documents.onDidChangeContent(change => {
    validateTextDocument(change.document);
});

async function validateTextDocument(textDocument: TextDocument): Promise<void> {
    // This is where Signal-specific validation logic will go
    const text = textDocument.getText();
    const diagnostics: Diagnostic[] = [];

    // Placeholder: Look for common signal boilerplate errors
    // Search for patterns that might be missing types or correctly defined signals

    connection.sendDiagnostics({ uri: textDocument.uri, diagnostics });
}

connection.onCompletion((_textDocumentPosition, _token) => {
    // This is where Signal-specific completion logic will go
    return [
        {
            label: 'signal',
            kind: 25, // Snippet
            data: 1,
            detail: 'Create a new Signal',
            documentation: 'Boilerplate for jswa signal definition'
        }
    ];
});

connection.onCompletionResolve(item => {
    if (item.data === 1) {
        item.insertText = 'const ${1:name} = signal(${2:value});';
        item.insertTextFormat = 2; // Snippet
    }
    return item;
});

// Make the text document manager listen on the connection
documents.listen(connection);

// Listen on the connection
connection.listen();