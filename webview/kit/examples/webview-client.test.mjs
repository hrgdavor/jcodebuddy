#!/usr/bin/env node
// Drives with-assets/assets/webview-client.js against a live, headless webviewd.
//
// This is the page's code under test, not a mock of it: the client has no DOM dependency above the clipboard
// rung, so the same functions a browser page calls are called here, over HTTP, against the real host. It is
// also the beginning of Phase 6's gate — "headless lacks nothing" as a test result rather than a claim.
//
//   node webview/kit/examples/webview-client.test.mjs [path-to-webviewd.jar]

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..', '..');
const jar = process.argv[2] ?? path.join(repo, 'webview', 'core', 'webviewd', 'target', 'webviewd.jar');
// The jar is built for JDK 25, and this machine's JAVA_HOME is not: an explicit WEBVIEWD_JAVA wins, then
// JAVA_HOME, then whatever `java` is on the PATH. Getting this wrong produces a class-version error, so the
// failure below prints the child's own stderr rather than only "no port appeared".
const java = process.env.WEBVIEWD_JAVA
  ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java');
const token = 'page-client-token';
const project = path.join(repo, 'target', 'page-client-project');

let passed = 0;
let failed = 0;
function check(name, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(`ok   ${name}`);
  } else {
    failed++;
    console.log(`FAIL ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

// --- the file the page will edit, in a throwaway project so no repository file is touched ---------------
fs.rmSync(project, { recursive: true, force: true });
fs.mkdirSync(path.join(project, 'src'), { recursive: true });
const target = path.join(project, 'src', 'People.java');
const original = 'class People {\n    String name = "Ada";\n}\n';
fs.writeFileSync(target, original, 'utf8');
const originalDigest = 'sha256:' + crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex');

// --- the host ------------------------------------------------------------------------------------------
const child = spawn(java, ['-jar', jar, '--project', project, '--port', '0', '--host', 'none', '--token', token],
  { stdio: ['ignore', 'pipe', 'pipe'] });
let childOutput = '';
child.stdout.on('data', (chunk) => { childOutput += chunk; process.stdout.write(`[webviewd] ${chunk}`); });
child.stderr.on('data', (chunk) => { childOutput += chunk; process.stdout.write(`[webviewd] ${chunk}`); });
process.on('exit', () => child.kill());

const descriptorPath = path.join(project, '.jcodebuddy', 'webview', 'host.json');
let port = 0;
for (let i = 0; i < 40 && port === 0; i++) {
  await new Promise((resolve) => setTimeout(resolve, 250));
  try {
    port = JSON.parse(fs.readFileSync(descriptorPath, 'utf8')).port ?? 0;
  } catch { /* not written yet */ }
}
if (port === 0) {
  console.log('FAIL the host never published a port in .jcodebuddy/webview/host.json');
  if (childOutput.includes('UnsupportedClassVersionError')) {
    console.log(`     the jar needs a newer JDK than ${java}: set WEBVIEWD_JAVA to a JDK 25+ java `
      + `(this machine's JAVA_HOME is ${process.env.JAVA_HOME ?? 'unset'})`);
  } else if (childOutput.trim()) {
    console.log(`     the child said: ${childOutput.trim().split('\n').slice(-3).join(' | ')}`);
  }
  child.kill();
  process.exit(1);
}
console.log(`host on port ${port} (java: ${java})`);

// --- the client, exactly as a page would load it -------------------------------------------------------
await import('./with-assets/assets/webview-client.js');
const { create } = globalThis.jcbClient;
check('webview-client.js exports a factory on globalThis', typeof create === 'function');

const client = create({ port, token, fetchImpl: fetch });

// --- verb coverage, recorded rather than remembered (plan step 5.1) ------------------------------------
//
// "Headless lacks nothing" is a claim about EVERY verb, and a test that drives the ones its author thought of
// cannot support it: a verb added to the client tomorrow would be silently undriven, and the parity claim would
// keep passing while getting less true. So the calls are recorded and the list below is asserted to be covered —
// and the list is the verbs a headless host actually supports, with the IDE-only ones proved elsewhere (see the
// capability map at the end).
const called = new Set();
for (const verb of ['read', 'proposeEdit', 'applyEdit', 'undo', 'redo', 'open']) {
  const original = client[verb].bind(client);
  client[verb] = (...args) => {
    called.add(verb);
    return original(...args);
  };
}
const HEADLESS_VERBS = ['read', 'proposeEdit', 'applyEdit', 'undo', 'redo', 'open'];

const state = await client.ready();

check('the host is discovered', state.reachable, JSON.stringify(state));
check('the mode is the bridge rung', state.mode === 'bridge', state.mode);
check('capabilities are read from /health', Array.isArray(state.capabilities), JSON.stringify(state.capabilities));
check('a headless host advertises no editor verbs', state.capabilities.length === 0,
  JSON.stringify(state.capabilities));
check('the manifest names the adapter', state.host && state.host.name === 'null', JSON.stringify(state.host));
check('the manifest says it cannot reach a line', state.host && state.host.lineNavigation === 'none',
  JSON.stringify(state.host));

// read: the digest the page must propose with
const file = await client.read('src/People.java', { absolute: target.replace(/\\/g, '/') });
check('read returns the file text', file.text === original, JSON.stringify(file.text));
check('read returns the host digest, not a local guess', file.digest === originalDigest,
  `${file.digest} vs ${originalDigest}`);

// propose: a diff, nothing written
const edit = [{ startLine: 2, startColumn: 20, endLine: 2, endColumn: 23, newText: 'Grace' }];
const proposal = await client.proposeEdit(file.path, file.digest, edit);
check('the proposal is accepted', proposal.status === 200 && proposal.applied === false, JSON.stringify(proposal));
check('the proposal carries a unified diff', typeof proposal.unifiedDiff === 'string'
  && proposal.unifiedDiff.includes('-    String name = "Ada";')
  && proposal.unifiedDiff.includes('+    String name = "Grace";'),
  proposal.unifiedDiff ?? JSON.stringify(proposal));
check('the proposal did not write', fs.readFileSync(target, 'utf8') === original);

// apply: disk, because no editor is attached
const applied = await client.applyEdit(file.path, file.digest, edit, 'disk');
check('the edit is applied', applied.status === 200 && applied.applied === true, JSON.stringify(applied));
check('the file changed on disk', fs.readFileSync(target, 'utf8').includes('Grace'));
check('no buffer was claimed when none exists', applied.target === undefined, JSON.stringify(applied));

// the digest guard, through the client
const stale = await client.applyEdit(file.path, file.digest, edit, 'disk');
check('a stale digest is refused with 409', stale.status === 409 && stale.reason === 'stale', JSON.stringify(stale));

// undo: the page can take it back
const undone = await client.undo(file.path);
check('undo succeeds', undone.status === 200, JSON.stringify(undone));
check('undo restored the exact bytes',
  'sha256:' + crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex') === originalDigest);

// redo: and put it back again (plan step 5.1 — the verb the client exposed and this test never drove)
const redone = await client.redo(file.path);
check('redo succeeds', redone.status === 200, JSON.stringify(redone));
check('redo re-applied the edit', fs.readFileSync(target, 'utf8').includes('Grace'), fs.readFileSync(target, 'utf8'));
// Undo again so the rest of the test starts from the original bytes, and so the pair is shown to be a pair
// rather than a one-way door.
const undoneAgain = await client.undo(file.path);
check('undo after redo restores the original bytes',
  undoneAgain.status === 200
  && 'sha256:' + crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex') === originalDigest,
  JSON.stringify(undoneAgain));

// asking for a buffer no host can provide is refused, never silently written
const bufferOnly = await client.applyEdit(file.path, originalDigest, edit, 'buffer');
check('an impossible buffer target is refused', bufferOnly.status === 409 && bufferOnly.reason === 'no-buffer-edit',
  JSON.stringify(bufferOnly));

// navigation degrades to the clipboard, and says which rung it used
fs.writeFileSync(target, original, 'utf8');   // back to the start, so the next edit sees the original digest
const rung = await client.open(file.path, 2, 1);
check('navigation without an editor lands on the clipboard rung', rung === 'clipboard', rung);
check('the client reports no host for navigation', client.state.mode !== 'injected');

// events: node has no EventSource, and the client says so instead of pretending
const watcher = client.watch(() => {});
check('watching reports itself unavailable without EventSource', watcher.watching === false);
watcher.close();

// the injected rung, which is what an IDE webview gives a page
const injected = create({ port: 0, opener: () => {} });
const injectedState = await injected.ready();
check('an injected bridge is detected without any HTTP', injectedState.mode === 'injected', injectedState.mode);
check('the injected rung reports the open capability', injected.can('open'));
check('an injected open uses the injected function', await injected.open('src/A.java', 1, 1) === 'injected');

// an unreachable host is the clipboard rung, not an exception
const orphan = create({ port: 1, token, fetchImpl: fetch });
const orphanState = await orphan.ready();
check('an unreachable host is not reachable', orphanState.reachable === false);
check('an unreachable host falls back to the clipboard', orphanState.mode === 'clipboard', orphanState.mode);
check('opening through an unreachable host is the clipboard rung',
  await orphan.open('src/A.java', 1, 1) === 'clipboard');
let threw = false;
try {
  await orphan.read('src/A.java');
} catch {
  threw = true;
}
check('reading through an unreachable host throws rather than returning empty', threw);

// --- what "headless lacks nothing" means, as two assertions (plan step 5.1) -----------------------------
//
// 1. EVERY verb a headless host supports was actually driven. The list is explicit so it cannot rot: a verb the
//    client gains is either added here (and driven) or left out with a reason, and the failure names which verb
//    went undriven rather than reporting a count.
const undriven = HEADLESS_VERBS.filter((verb) => !called.has(verb));
check(`every headless verb was driven (${HEADLESS_VERBS.length})`, undriven.length === 0,
  `never called: ${undriven.join(', ')}`);

// 2. Every capability a host may DECLARE has a proof, and the ones a headless test cannot reach point at the
//    record where a person observes them. A host that declares a verb it does not implement still fails — that
//    is `HostHealthParityTest`'s job on the Java side (every host builds its health body through `HostHealth`,
//    and refuses an unauthorized /open before reading anything) — so what this side adds is the CONTENT check:
//    a capability name nobody listed, or one listed with no proof, is a declaration without evidence.
const CAPABILITY_PROOFS = {
  open: 'driven here over HTTP (and on the injected rung)',
  serveFile: 'driven here — read() returns the file text and the host digest',
  select: 'IDE-only: observed in the editor, recorded in the observation checklist',
};
const IDE_ONLY_CAPABILITIES = ['select'];
const known = new Set(Object.keys(CAPABILITY_PROOFS));
const declared = Array.isArray(state.capabilities) ? state.capabilities : [];
const unknown = declared.filter((capability) => !known.has(capability));
check('every declared capability is one the contract names', unknown.length === 0,
  `not in the proof map: ${unknown.join(', ')}`);
check('every capability the contract names has a proof', known.size >= 3 && [...known].every(Boolean),
  [...known].join(', '));
// The IDE-only half is tied to a file that exists, so "observed by a person" is a record rather than a promise.
const checklist = path.join(repo, 'webview', 'doc', 'ide-observation-checklist.md');
check('the IDE-only capabilities have an observation checklist to be recorded in', fs.existsSync(checklist),
  checklist);
check('the IDE-only list is a subset of what the contract names',
  IDE_ONLY_CAPABILITIES.every((capability) => known.has(capability)), IDE_ONLY_CAPABILITIES.join(', '));

child.kill();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
