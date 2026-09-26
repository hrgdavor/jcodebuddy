#!/usr/bin/env bun
/**
 * check-capabilities.js — the capability-honesty gate (plan phase 6).
 *
 * A host's `/health` document is what a page decides its fallback ladder from, so a capability that is claimed
 * but not implemented is worse than a missing feature: the page takes a rung that leads nowhere. The rule this
 * script enforces is therefore two-directional:
 *
 *   1. **declared ⇒ served**: every capability in the document is one of the known verbs, the manifest agrees
 *      with `/health`, and no host may answer a verb it does not declare;
 *   2. **undeclared ⇒ refused with a reason**: asking for a buffer edit from a host with no editor must say
 *      `no-buffer-edit`, not fail silently or, worse, write the file.
 *
 * It runs the **reference host** (`webviewd`), which is the one that can be started headlessly: the IDE hosts
 * need a GUI, and their claims are covered by `doc/ide-observation-checklist.md` instead of pretended here.
 * Everything it checks is measured against a live process — there is no mock in this file.
 *
 *   bun webview/tools/check-capabilities.js [path-to-webviewd.jar]
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const jar = process.argv[2] ?? path.join(repo, 'webview', 'core', 'webviewd', 'target', 'webviewd.jar');
const java = process.env.WEBVIEWD_JAVA
  ?? (process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java');
const token = 'capability-gate';

/** The verbs a capability list may name. Anything else is a typo or an invention, and fails. */
const KNOWN = ['edit', 'open', 'reveal', 'select', 'serveFile', 'watch'];

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

// --- a throwaway project the host can serve ------------------------------------------------------------
const project = path.join(repo, 'target', 'capability-gate-project');
fs.rmSync(project, { recursive: true, force: true });
fs.mkdirSync(path.join(project, 'src'), { recursive: true });
fs.writeFileSync(path.join(project, 'src', 'A.java'), 'class A {\n    int x = 1;\n}\n', 'utf8');
fs.writeFileSync(path.join(project, 'page.html'), '<!doctype html><html><body>gate</body></html>', 'utf8');

const child = spawn(java, ['-jar', jar, '--project', project, '--port', '0', '--host', 'none',
  '--token', token], { stdio: ['ignore', 'pipe', 'pipe'] });
let output = '';
child.stdout.on('data', (chunk) => { output += chunk; });
child.stderr.on('data', (chunk) => { output += chunk; });
process.on('exit', () => child.kill());

const descriptor = path.join(project, '.jcodebuddy', 'webview', 'host.json');
let port = 0;
for (let i = 0; i < 40 && port === 0; i++) {
  await new Promise((resolve) => setTimeout(resolve, 250));
  try {
    port = JSON.parse(fs.readFileSync(descriptor, 'utf8')).port ?? 0;
  } catch { /* not written yet */ }
}
if (port === 0) {
  console.log('FAIL the host never published a port in .jcodebuddy/webview/host.json');
  if (output.includes('UnsupportedClassVersionError')) {
    console.log(`     the jar needs a newer JDK than ${java}: set WEBVIEWD_JAVA`);
  } else if (output.trim()) {
    console.log(`     the child said: ${output.trim().split('\n').slice(-2).join(' | ')}`);
  }
  child.kill();
  process.exit(1);
}

const base = `http://127.0.0.1:${port}`;

// The descriptor is what a page, a script or a second host reads to find the port (DEC-032, DEC-033). Its
// shape is asserted here rather than assumed, because every other check in this file depends on it.
const published = JSON.parse(fs.readFileSync(descriptor, 'utf8'));
check('the descriptor names the editor that published the port',
  typeof published.ide === 'string' && published.ide.length > 0, JSON.stringify(published.ide));
check('and the project the port belongs to', published.project === project.replace(/\\/g, '/'),
  JSON.stringify(published.project));
check('and the port it actually bound, which is the ephemeral one it asked for',
  published.port === port, `${published.port} vs ${port}`);
check('the descriptor carries the token PATH only, never the token',
  !Object.prototype.hasOwnProperty.call(published, 'token') && typeof published.tokenPath === 'string',
  JSON.stringify(Object.keys(published)));
check('and the state directory ignores itself, so a served project gains no untracked files',
  fs.readFileSync(path.join(project, '.jcodebuddy', 'webview', '.gitignore'), 'utf8').includes('*'),
  'no .gitignore, or one without a * in it');
const withToken = { 'X-WebView-Token': token, 'Content-Type': 'application/json' };
const json = async (route, init) => {
  const response = await fetch(`${base}${route}`, init);
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = text;
  }
  return { status: response.status, body, headers: response.headers };
};
const post = (route, payload, headers = withToken) =>
  json(route, { method: 'POST', headers, body: JSON.stringify(payload) });

// --- 1. the capability document itself ----------------------------------------------------------------
const health = await json('/health');
check('the host answers /health', health.status === 200, `HTTP ${health.status}`);
const declared = health.body.capabilities ?? [];
check('capabilities are a list', Array.isArray(declared), JSON.stringify(declared));
check('every declared capability is a known verb', declared.every((c) => KNOWN.includes(c)),
  `declared ${JSON.stringify(declared)}, known ${JSON.stringify(KNOWN)}`);

// This host has no editor attached, so the honest list is empty: anything else would be a claim it cannot serve.
check('a host with no editor declares no editor verbs', declared.length === 0, JSON.stringify(declared));
check('a host with a token reports that it needs one', health.body.tokenRequired === true);

// The identity the port claim turns on (DEC-033): a second host asks /health who holds the port, and only a
// document that names the SAME project may make it decline to open an endpoint.
check('the host names the editor serving this endpoint', health.body.ide === 'webviewd',
  JSON.stringify(health.body.ide));
check('and the project the endpoint serves', health.body.project === project.replace(/\\/g, '/'),
  JSON.stringify(health.body.project));

const manifest = await json('/.well-known/webview.json');
check('the manifest answers', manifest.status === 200, `HTTP ${manifest.status}`);
check('the manifest and /health agree about capabilities',
  JSON.stringify([...(manifest.body.capabilities ?? [])].sort()) === JSON.stringify([...declared].sort()),
  `manifest ${JSON.stringify(manifest.body.capabilities)} vs health ${JSON.stringify(declared)}`);
check('the manifest states how precisely a line can be reached',
  ['exact', 'file-only', 'none'].includes(manifest.body.host?.lineNavigation),
  JSON.stringify(manifest.body.host));

// --- 2. undeclared ⇒ refused with a reason, never a silent write ---------------------------------------
const file = path.join(project, 'src', 'A.java');
const digest = 'sha256:' + new Bun.CryptoHasher('sha256').update(fs.readFileSync(file)).digest('hex');
const edit = { startLine: 2, startColumn: 5, endLine: 2, endColumn: 14, newText: 'int x = 42;' };

const bufferAsk = await post('/api/v1/applyEdit',
  { filePath: file.replace(/\\/g, '/'), expectedDigest: digest, edits: [edit], dryRun: false, target: 'buffer' });
check('a buffer edit is refused when the host has no editor', bufferAsk.status === 409
  && bufferAsk.body.reason === 'no-buffer-edit', `${bufferAsk.status} ${JSON.stringify(bufferAsk.body)}`);
check('and the refusal did not write the file',
  fs.readFileSync(file, 'utf8').includes('int x = 1;'), 'the buffer refusal must not fall back to disk');

// --- 3. every route that exists is reachable, and an unknown one is distinguishable --------------------
const noToken = await post('/api/v1/applyEdit', { filePath: file, expectedDigest: digest, edits: [] },
  { 'Content-Type': 'application/json' });
check('a write route without the token is 403, which proves the route exists', noToken.status === 403,
  `HTTP ${noToken.status} ${JSON.stringify(noToken.body)}`);
check('and says why', String(noToken.body).includes('token is required'), JSON.stringify(noToken.body));

const unknown = await post('/api/v1/notAVerb', {}, withToken);
check('an unknown write route is 404, so 403 and 404 stay distinguishable', unknown.status === 404,
  `HTTP ${unknown.status}`);

const proposal = await post('/api/v1/diff',
  { filePath: file.replace(/\\/g, '/'), expectedDigest: digest, edits: [edit] });
check('diff serves a proposal without writing', proposal.status === 200 && proposal.body.applied === false,
  `${proposal.status} ${JSON.stringify(proposal.body)}`);

const disk = await post('/api/v1/applyEdit',
  { filePath: file.replace(/\\/g, '/'), expectedDigest: digest, edits: [edit], dryRun: false, target: 'disk' });
check('a disk edit is served, because that needs no editor', disk.status === 200 && disk.body.applied === true,
  `${disk.status} ${JSON.stringify(disk.body)}`);
check('and it changed the file', fs.readFileSync(file, 'utf8').includes('int x = 42;'));

const undo = await post('/api/v1/undo', { filePath: file.replace(/\\/g, '/') });
check('undo is served', undo.status === 200, `${undo.status} ${JSON.stringify(undo.body)}`);
check('and restored the exact bytes', fs.readFileSync(file, 'utf8').includes('int x = 1;'));

// --- 4. serving, which this host declares as a route rather than a capability -------------------------
const served = await json(`/file/${file.replace(/\\/g, '/')}?token=${token}`);
check('the host serves a project file', served.status === 200, `HTTP ${served.status}`);
check('and announces the digest a page must propose with',
  (served.headers.get('x-webview-digest') ?? '').startsWith('sha256:'),
  served.headers.get('x-webview-digest') ?? 'no header');

const page = await json(`/page/${path.join(project, 'page.html').replace(/\\/g, '/')}?token=${token}`);
check('the host serves a page with the bridge injected', page.status === 200
  && String(page.body).includes('window.openFile'), `HTTP ${page.status}`);

const outside = await json(`/file/${path.join(repo, 'pom.xml').replace(/\\/g, '/')}?token=${token}`);
check('and refuses a path outside the project', outside.status === 403, `HTTP ${outside.status}`);

child.kill();
console.log(`\n${passed} passed, ${failed} failed`);
console.log('The IDE hosts are not covered here: they need a GUI, and their claims are checked by');
console.log('webview/doc/ide-observation-checklist.md instead of being asserted by this script.');
process.exit(failed === 0 ? 0 : 1);
