#!/usr/bin/env node
// Does the sidecar actually SEND window/showDocument when /jump is called?
//
// The live spike showed the file opening but the caret staying put, and `JwaLanguageClient` never declares
// showDocument - it inherits lsp4j's `default` method. A Java dynamic proxy routes every interface call,
// including default ones, through the invocation handler, so whether a JSON-RPC request is emitted depends on
// what lsp4j does with an unannotated/default method. This harness answers that by acting as Zed: it speaks
// LSP to the sidecar over stdio, calls the sidecar's HTTP /jump, and logs every message that comes back.
//
//   node fake-zed-client.mjs <sidecar.jar> [jumpPort]

import { spawn } from 'node:child_process';
import fs from 'node:fs';

const jar = process.argv[2];
const jumpPort = Number(process.argv[3] ?? 7971);
const token = 'fake-zed-token';
const target = process.argv[4] ?? 'D:\\wrk\\java\\jcodebuddy\\webview\\zed\\phase0\\scratch\\Sample.rs';
const java = 'C:\\Program Files\\Java\\jdk-25\\bin\\java.exe';
const logPath = 'D:\\wrk\\java\\jcodebuddy\\target\\fake-zed.log';
fs.rmSync(logPath, { force: true });
const logStream = fs.createWriteStream(logPath, { flags: 'a' });
function log(...parts) {
  const text = `[${new Date().toISOString()}] ${parts.join(' ')}`;
  logStream.write(`${text}\n`);
  console.log(text);
}

const child = spawn(java, [`-Djwa.sidecar.token=${token}`, `-Djwa.sidecar.jumpPort=${jumpPort}`, '-jar', jar],
  { stdio: ['pipe', 'pipe', 'inherit'] });

function send(message) {
  const json = JSON.stringify(message);
  child.stdin.write(`Content-Length: ${Buffer.byteLength(json, 'utf8')}\r\n\r\n${json}`);
}

const received = [];
let buffer = Buffer.alloc(0);
child.stdout.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf('\r\n\r\n');
    if (headerEnd < 0) return;
    const length = Number(/Content-Length:\s*(\d+)/i.exec(buffer.subarray(0, headerEnd).toString('ascii'))[1]);
    if (buffer.length < headerEnd + 4 + length) return;
    const message = JSON.parse(buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString('utf8'));
    buffer = buffer.subarray(headerEnd + 4 + length);
    received.push(message);
    if (message.method && message.id !== undefined) {
      log(`<- REQUEST  ${message.method} id=${message.id}`, JSON.stringify(message.params));
      // The answer has to match what the method expects: showDocument wants ShowDocumentResult, applyEdit wants
      // ApplyWorkspaceEditResponse. Answering the wrong shape reads as a refusal, which is how a real client
      // that does not implement one of them behaves too.
      const result = message.method === 'workspace/applyEdit' ? { applied: true } : { success: true };
      send({ jsonrpc: '2.0', id: message.id, result });
      log(`-> response to ${message.method}`, JSON.stringify(result));
    } else if (message.method) {
      log(`<- NOTIFICATION ${message.method}`, JSON.stringify(message.params));
    } else {
      log(`<- RESPONSE id=${message.id}`, JSON.stringify(message.result ?? message.error));
    }
  }
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

await sleep(1500); // let the sidecar bind its jump port

send({
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: {
    processId: process.pid,
    clientInfo: { name: 'fake-zed', version: '0.0.1' },
    rootUri: 'file:///D:/wrk/java/jcodebuddy',
    capabilities: { window: { showDocument: { support: true } }, workspace: { applyEdit: true } },
  },
});
await sleep(800);
send({ jsonrpc: '2.0', method: 'initialized', params: {} });
await sleep(500);

const uri = 'file:///' + target.replace(/\\/g, '/').replace(/^\/+/, '');
const url = `http://127.0.0.1:${jumpPort}/jump?uri=${encodeURIComponent(uri)}&line=7&column=5`;
log(`-> HTTP GET ${url}`);
const response = await fetch(url, { headers: { 'X-WebView-Token': token } });
log(`<- HTTP ${response.status}`, await response.text());

await sleep(1200);

// The write half of the same channel: the route webviewd calls when a host declares the edit capability.
const editUrl = `http://127.0.0.1:${jumpPort}/applyEdit`;
const editBody = JSON.stringify({
  uri,
  edits: [{ startLine: 7, startColumn: 1, endLine: 7, endColumn: 4, newText: 'renamed' }],
});
log(`-> HTTP POST ${editUrl}`, editBody);
const editResponse = await fetch(editUrl, {
  method: 'POST',
  headers: { 'X-WebView-Token': token, 'Content-Type': 'application/json' },
  body: editBody,
});
log(`<- HTTP ${editResponse.status}`, await editResponse.text());

await sleep(2500);

const showDocument = received.filter((m) => m.method === 'window/showDocument');
const applyEdit = received.filter((m) => m.method === 'workspace/applyEdit');
log(`SUMMARY: ${received.length} messages; window/showDocument: ${showDocument.length}; workspace/applyEdit: ${applyEdit.length}`);
for (const message of showDocument) {
  log(`  showDocument id=${message.id} params=${JSON.stringify(message.params)}`);
}
for (const message of applyEdit) {
  log(`  applyEdit id=${message.id} params=${JSON.stringify(message.params)}`);
}
log(`SUMMARY methods seen: ${[...new Set(received.filter((m) => m.method).map((m) => m.method))].join(', ') || '(none)'}`);

child.kill();
process.exit(0);
