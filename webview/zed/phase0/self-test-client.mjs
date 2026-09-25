#!/usr/bin/env node
// Self-test for stub-lsp.mjs: a synthetic LSP *client* that plays the part of Zed, so the stub can
// be proven correct without an editor attached. It sends `initialize` advertising the same
// capabilities Zed advertises, answers `window/showDocument` with `{success:true}` and
// `workspace/applyEdit` with `{applied:true}`, and fails if the stub does not reach both.
//
//   node self-test-client.mjs
//
// Exit code 0 means the stub framed, ordered and issued both requests correctly.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const stub = path.join(here, 'stub-lsp.mjs');
const logPath = path.join(here, 'self-test.log');
fs.rmSync(logPath, { force: true });

const child = spawn(process.execPath, [
  stub,
  '--log', logPath,
  '--file', path.join(here, 'scratch', 'Sample.java'),
  '--line', '10',
  '--column', '9',
  '--edit-line', '8',
  '--delay', '50',
], { stdio: ['pipe', 'pipe', 'inherit'] });

const seen = { showDocument: null, applyEdit: null };
let finished = false;

function send(message) {
  const json = JSON.stringify(message);
  child.stdin.write(`Content-Length: ${Buffer.byteLength(json, 'utf8')}\r\n\r\n${json}`);
}

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
    handle(message);
  }
});

function handle(message) {
  if (message.method === 'window/showDocument') {
    seen.showDocument = message.params;
    console.log('client received window/showDocument:', JSON.stringify(message.params));
    send({ jsonrpc: '2.0', id: message.id, result: { success: true } });
    return;
  }
  if (message.method === 'workspace/applyEdit') {
    seen.applyEdit = message.params;
    console.log('client received workspace/applyEdit:', JSON.stringify(message.params));
    send({ jsonrpc: '2.0', id: message.id, result: { applied: true } });
    return;
  }
  if (message.id !== undefined && message.method === undefined) return;
  console.log('client received unhandled:', JSON.stringify(message));
}

send({
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: {
    processId: process.pid,
    clientInfo: { name: 'self-test-client' },
    rootUri: null,
    capabilities: {
      window: { showDocument: { support: true } },
      workspace: { applyEdit: true },
    },
  },
});

setTimeout(() => {
  send({ jsonrpc: '2.0', method: 'initialized', params: {} });
}, 100);

const deadline = setTimeout(() => {
  console.log('FAIL: the stub did not issue both requests within 10s');
  child.kill();
  process.exit(1);
}, 10000);

const poll = setInterval(() => {
  if (seen.showDocument && seen.applyEdit) {
    clearInterval(poll);
    clearTimeout(deadline);
    const uri = seen.showDocument.uri;
    const edit = seen.applyEdit.edit.changes[uri][0];
    const checks = [
      ['showDocument takeFocus is true', seen.showDocument.takeFocus === true],
      ['showDocument selection is line 9 char 8 (0-based)', seen.showDocument.selection.start.line === 9 && seen.showDocument.selection.start.character === 8],
      ['showDocument uri is the scratch file', uri.endsWith('/webview/zed/phase0/scratch/Sample.java')],
      ['applyEdit inserts at line 7 char 0 (0-based)', edit.range.start.line === 7 && edit.range.start.character === 0],
      ['applyEdit text is the phase0 marker', edit.newText.startsWith('// phase0-applyEdit ')],
    ];
    let ok = true;
    for (const [name, pass] of checks) {
      console.log(`${pass ? 'ok  ' : 'FAIL'} ${name}`);
      if (!pass) ok = false;
    }
    console.log(ok ? 'SELF-TEST PASS: the stub is a correct LSP client-facing server.' : 'SELF-TEST FAIL');
    if (!finished) {
      finished = true;
      child.kill();
      process.exit(ok ? 0 : 1);
    }
  }
}, 20);
