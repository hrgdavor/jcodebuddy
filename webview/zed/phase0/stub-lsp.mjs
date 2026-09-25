#!/usr/bin/env node
// Phase 0 stub language server for webview/PLAN-webview-suite.md.
//
// It exists to answer one question with evidence from the *installed* Zed rather than from Zed's
// source: does the Zed LSP client honour `window/showDocument` (with a selection range) and
// `workspace/applyEdit`? Registered under a throwaway name in the project's `.zed/settings.json`,
// it logs the client's own `initialize` capabilities, sends both requests, and logs the responses.
//
// Usage (Zed supplies the transport, so this is normally invoked by Zed, not by hand):
//   node stub-lsp.mjs --log <file> --file <abs path> --line <1-based> --column <1-based>
//                     [--edit-line <1-based>] [--delay <ms>]
//
// Nothing here is production code: it speaks just enough LSP to reach the two verbs, and every
// branch it takes is written to the log file.

import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

// Scan for the flags we know rather than pairing argv blindly: an editor may append flags of its
// own (Zed passes some adapters extra arguments), and a blind pairing would mis-assign every value
// after the first unknown flag.
const KNOWN = new Set(['log', 'file', 'line', 'column', 'edit-line', 'delay']);
const opt = new Map();
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length - 1; i++) {
  const key = argv[i].startsWith('--') ? argv[i].slice(2) : null;
  if (key && KNOWN.has(key)) {
    opt.set(key, argv[i + 1]);
    i++;
  }
}

const logPath = opt.get('log') ?? 'stub-lsp.log';
const targetFile = opt.get('file');
const targetLine = Number(opt.get('line') ?? 1);
const targetColumn = Number(opt.get('column') ?? 1);
const editLine = Number(opt.get('edit-line') ?? 1);
const delayMs = Number(opt.get('delay') ?? 3000);

const logStream = fs.createWriteStream(logPath, { flags: 'a' });
function log(...parts) {
  const text = `[${new Date().toISOString()}] ${parts.join(' ')}`;
  logStream.write(`${text}\n`);
  process.stderr.write(`${text}\n`);
}

log('stub starting', JSON.stringify({ pid: process.pid, cwd: process.cwd(), argv, logPath, targetFile, targetLine, targetColumn, editLine, delayMs }));

let nextId = 1;
const pending = new Map();

function send(message) {
  const json = JSON.stringify(message);
  process.stdout.write(`Content-Length: ${Buffer.byteLength(json, 'utf8')}\r\n\r\n${json}`);
}

function request(method, params) {
  const id = nextId++;
  return new Promise((resolve) => {
    pending.set(id, { method, resolve });
    send({ jsonrpc: '2.0', id, method, params });
  });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function sequence() {
  await sleep(delayMs);
  if (!targetFile) {
    log('SEQUENCE ABORTED: no --file was given');
    return;
  }
  const uri = pathToFileURL(targetFile).href;

  const position = { line: targetLine - 1, character: targetColumn - 1 };
  log(`-> window/showDocument ${uri} at ${targetLine}:${targetColumn} (takeFocus=true)`);
  const shown = await request('window/showDocument', {
    uri,
    takeFocus: true,
    selection: { start: position, end: position },
  });
  log('<- window/showDocument response', JSON.stringify(shown));

  await sleep(1500);

  const marker = `// phase0-applyEdit ${new Date().toISOString()}`;
  log(`-> workspace/applyEdit inserting ${JSON.stringify(marker)} at line ${editLine} of ${uri}`);
  const applied = await request('workspace/applyEdit', {
    label: 'webview Phase 0 stub',
    edit: {
      changes: {
        [uri]: [
          {
            range: {
              start: { line: editLine - 1, character: 0 },
              end: { line: editLine - 1, character: 0 },
            },
            newText: `${marker}\n`,
          },
        ],
      },
    },
  });
  log('<- workspace/applyEdit response', JSON.stringify(applied));
  log('SEQUENCE COMPLETE');
}

async function handle(message) {
  if (!message.method && message.id !== undefined) {
    const waiter = pending.get(message.id);
    if (waiter) {
      pending.delete(message.id);
      log(`<- response to ${waiter.method}`, JSON.stringify(message));
      waiter.resolve(message);
    } else {
      log('<- unexpected response id', message.id);
    }
    return;
  }

  log(`<- ${message.method}${message.id !== undefined ? ` (request id ${message.id})` : ' (notification)'}`);

  if (message.method === 'initialize') {
    const caps = message.params?.capabilities ?? {};
    log('CLIENT CAPABILITIES window.showDocument =', JSON.stringify(caps?.window?.showDocument ?? null));
    log('CLIENT CAPABILITIES workspace.applyEdit =', JSON.stringify(caps?.workspace?.applyEdit ?? null));
    log('CLIENT INFO', JSON.stringify({ clientInfo: message.params?.clientInfo ?? null, processId: message.params?.processId ?? null, rootUri: message.params?.rootUri ?? null }));
    log('FULL INITIALIZE PARAMS', JSON.stringify(message.params));
    send({
      jsonrpc: '2.0',
      id: message.id,
      result: {
        capabilities: { textDocumentSync: 1 },
        serverInfo: { name: 'webview-phase0-stub', version: '0.1.0' },
      },
    });
    return;
  }

  if (message.method === 'initialized') {
    void sequence();
    return;
  }

  if (message.method === 'shutdown') {
    send({ jsonrpc: '2.0', id: message.id, result: null });
    return;
  }

  if (message.method === 'exit') {
    log('exit notification received, stopping');
    logStream.end(() => process.exit(0));
    return;
  }

  if (message.id !== undefined) {
    send({ jsonrpc: '2.0', id: message.id, result: null });
  }
}

let buffer = Buffer.alloc(0);
process.stdin.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf('\r\n\r\n');
    if (headerEnd < 0) return;
    const header = buffer.subarray(0, headerEnd).toString('ascii');
    const match = /Content-Length:\s*(\d+)/i.exec(header);
    if (!match) {
      log('BAD FRAME HEADER', JSON.stringify(header));
      buffer = buffer.subarray(headerEnd + 4);
      continue;
    }
    const length = Number(match[1]);
    if (buffer.length < headerEnd + 4 + length) return;
    const body = buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString('utf8');
    buffer = buffer.subarray(headerEnd + 4 + length);
    try {
      void handle(JSON.parse(body));
    } catch (error) {
      log('FRAME PARSE ERROR', String(error));
    }
  }
});

process.stdin.on('end', () => {
  log('stdin closed, stopping');
  logStream.end(() => process.exit(0));
});
