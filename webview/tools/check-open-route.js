#!/usr/bin/env bun
/**
 * check-open-route.js — the outside-the-browser entry point (plan step 3.11b, DEC-049 decision 5).
 *
 * <p>An application that is not the browser asks an editor to open a location by calling
 * <b>{@code GET /open?filePath=…&line=…&column=…}</b> on a running host — no custom agent, no second API. What
 * such a caller needs and what this check is about is the <b>discovery</b> (which port, which token) and the
 * <b>frozen statuses</b> it must handle:</p>
 *
 * <table>
 *   <tr><td>{@code 200}</td><td>an adapter is attached and the file is in the project</td></tr>
 *   <tr><td>{@code 400}</td><td>no {@code filePath}</td></tr>
 *   <tr><td>{@code 403}</td><td>the path is outside the project, or no token and no allowed {@code Origin}</td></tr>
 *   <tr><td>{@code 404}</td><td>a location the file does not have — or <b>no editor adapter is attached</b>, which is
 *       the honest answer for a headless host rather than a false success</td></tr>
 *   <tr><td>{@code 429}</td><td>the rate limit is spent (20 per 20 s, per host)</td></tr>
 * </table>
 *
 * <p>Everything here is read off a live {@code webviewd}: the port and the token path from the descriptor it
 * publishes, the identity from {@code /health}, and the statuses from the route itself. No mock, because the
 * thing being checked is that the discovery order in
 * {@code webview/doc/webview-host-api.md} actually works against a real host.</p>
 *
 *   bun webview/tools/check-open-route.js [path-to-webviewd.jar]
 *
 * <p>Two host choices are exercised. {@code --host none} is the run every machine can make: the statuses that
 * do not need an editor (400, 403, 404, 429) are decided by the token check, the path jail and the rate limiter,
 * which are all in {@code webview-core}. {@code --host zed-cli} adds the **200**, and is used only when a Zed CLI
 * is actually on the PATH — the same detection the Java adapter does, so a machine without Zed asserts the same
 * things minus one line and says so rather than failing. That run opens one file in Zed, which is the point of
 * the check.</p>
 *
 * <p>The served project is a throwaway directory under the system temp root, so nothing in a real checkout is
 * opened and no real checkout's host state is touched. This script also removes any
 * {@code .jcodebuddy/webview/host.json} and {@code token} a previous run left in <b>this</b> repository, and says
 * so: it is a check, and a check may not leave a port behind.</p>
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { reportDuration, stopwatch } from '../../scripts/lib/timing.js';
import { REQUIRED_JAVA, resolveJdk25, repoRoot } from '../../scripts/lib/toolchain.js';

const repo = repoRoot();
const jar = process.argv[2] ?? join(repo, 'webview', 'core', 'webviewd', 'target', 'webviewd.jar');

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

/* ------------------------------------------------------------------ the fixture projects */

const root = join(tmpdir(), `jcb-open-route-${process.pid}`);
rmSync(root, { recursive: true, force: true });

/**
 * A throwaway project with one real file to navigate to.
 *
 * <p>One project per host, deliberately: a second host for the **same** project declines to serve (DEC-033's
 * one-bridge-per-project rule), so the editor-backed host at the end needs a project of its own rather than a
 * second endpoint for the first one's.</p>
 */
function makeProject(name) {
  const dir = join(root, name);
  mkdirSync(join(dir, 'src'), { recursive: true });
  const file = join(dir, 'src', 'Sample.java');
  writeFileSync(file, 'class Sample {}\n', 'utf8');
  return { dir, file, descriptor: join(dir, '.jcodebuddy', 'webview', 'host.json') };
}

const headlessProject = makeProject('headless');
/** What the descriptor of a given project says, or null while it has not been written. */
function readDescriptor(project) {
  try {
    return JSON.parse(readFileSync(project.descriptor, 'utf8'));
  } catch {
    return null;
  }
}

/** The repository's own host state, which a *previous run of this check* may have left behind. */
const repoHostState = join(repo, '.jcodebuddy', 'webview');

/* ------------------------------------------------------------------ running a host */

const hosts = [];

/**
 * Starts a host and waits until it is really serving: the port is published **by this process** and answers
 * {@code /health}, and the generated token file is readable.
 *
 * <p>The descriptor is checked for **this process's pid** because a stopped host leaves its record in place on
 * purpose (DEC-033 — it is the port this checkout is on), so a reader that only parsed the file could read a
 * dead host's port and probe something else entirely. The token file is waited for as well: the host publishes
 * the port and writes the secret in that order, so a caller that read it the instant the port appeared could
 * read nothing.</p>
 *
 * <p><b>No {@code --token} is passed, on purpose.</b> The subject of this check is discovery, and a caller that
 * is told the secret has discovered nothing: the host generates one, writes it where the descriptor says, and
 * every call below uses what was read from there. (Measured consequence of the other choice: webviewd writes the
 * file only when it generates the secret, so passing {@code --token} leaves the descriptor naming a
 * {@code tokenPath} that does not exist — a real fact about the host state a caller must handle, and not the
 * path this check is about.)</p>
 */
async function startHost(project, hostChoice, timeoutMillis = 30_000) {
  const child = spawn(jdk.java, ['-jar', jar, '--project', project.dir, '--host', hostChoice, '--port', '0'],
    { stdio: ['ignore', 'pipe', 'pipe'] });
  const state = { child, output: '' };
  child.stdout.on('data', (chunk) => { state.output += chunk; });
  child.stderr.on('data', (chunk) => { state.output += chunk; });
  hosts.push(state);

  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    const record = readDescriptor(project);
    if (record && record.pid === child.pid && record.port > 0) {
      try {
        const health = await (await fetch(`http://127.0.0.1:${record.port}/health`)).json();
        const token = existsSync(record.tokenPath) ? readFileSync(record.tokenPath, 'utf8').trim() : '';
        if (health.port === record.port && token.length > 0) {
          return { ...state, port: record.port, record, health, token };
        }
      } catch { /* bound but not answering yet */ }
    }
    if (child.exitCode !== null) {
      throw new Error(`the host exited with ${child.exitCode} before serving:\n${state.output}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`the host published no answering port within ${timeoutMillis}ms:\n${state.output}`);
}

function stopAll() {
  for (const host of hosts) {
    if (host.child.exitCode === null) {
      host.child.kill();
    }
  }
}

/** A GET whose status and body are both readable — the route answers text/plain, on purpose. */
async function get(port, path) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`);
  return { status: response.status, body: await response.text() };
}

/** The encoded form of a path and a token, for a route that takes them as query parameters. */
function openPath(port, filePath, token, { line = 3, column = 1 } = {}) {
  const query = `filePath=${encodeURIComponent(filePath)}&line=${line}&column=${column}`;
  return get(port, `/open?${query}${token === null ? '' : `&token=${encodeURIComponent(token)}`}`);
}

/** Zed's CLI under both spellings, detected the way the Java adapter does: on the PATH. */
function zedOnPath() {
  const names = process.platform === 'win32' ? ['zed.exe', 'zed.cmd', 'zed.bat'] : ['zed'];
  for (const entry of (process.env.PATH ?? '').split(process.platform === 'win32' ? ';' : ':')) {
    for (const name of names) {
      if (entry && existsSync(join(entry, name))) {
        return join(entry, name);
      }
    }
  }
  return null;
}

const elapsed = stopwatch();
const jdk = resolveJdk25();
if (jdk.version < REQUIRED_JAVA) {
  throw new Error(`JDK ${REQUIRED_JAVA}+ is required (webviewd is Java ${REQUIRED_JAVA} bytecode)`);
}
if (!existsSync(jar)) {
  console.error(`no webviewd jar at ${jar}`);
  console.error('build it first:  bun scripts/mvn-jdk25.js -pl webview-core,webviewd -am package');
  process.exit(1);
}

try {
  // --- 1. the discovery order: descriptor → /health probe → token file -------------------------------
  const host = await startHost(headlessProject, 'none');

  check('the descriptor publishes the port the host actually bound', host.record.port === host.port,
    `${host.record.port} vs ${host.port}`);
  check('and the project it serves', host.record.project === headlessProject.dir.replace(/\\/g, '/'),
    JSON.stringify(host.record.project));
  check('and never the token itself', !('token' in host.record),
    `the descriptor carries only tokenPath: ${Object.keys(host.record).join(', ')}`);

  const tokenPath = host.record.tokenPath;
  check('the descriptor names a tokenPath', typeof tokenPath === 'string' && tokenPath.length > 0,
    JSON.stringify(tokenPath));
  check('and the file at tokenPath holds the generated secret', host.token.length > 0,
    `read ${host.token.length} character(s) from ${tokenPath}`);

  // /health is credential-free, which is what makes it the liveness probe rather than part of the act.
  check('the credential-free /health confirms the host and names this project',
    host.health.plugin === 'hr.hrg.webview.webviewd' && host.health.project === headlessProject.dir.replace(/\\/g, '/')
      && host.health.port === host.port,
    JSON.stringify({ plugin: host.health.plugin, project: host.health.project, port: host.health.port }));

  // --- 2. the frozen statuses, on the headless host ---------------------------------------------------
  const noPath = await get(host.port, `/open?token=${encodeURIComponent(host.token)}`);
  check('400 for a call with no filePath', noPath.status === 400, `${noPath.status} ${noPath.body}`);

  const outside = await openPath(host.port, join(root, 'elsewhere', 'X.java'), host.token);
  check('403 for a path outside the project', outside.status === 403, `${outside.status} ${outside.body}`);

  const noToken = await openPath(host.port, headlessProject.file, null);
  check('403 for no token (and no allowed Origin)', noToken.status === 403, `${noToken.status} ${noToken.body}`);

  const wrongToken = await openPath(host.port, headlessProject.file, 'not-the-token');
  check('403 for a wrong token', wrongToken.status === 403, `${wrongToken.status} ${wrongToken.body}`);

  // The headless answer: no adapter is attached, so nothing was opened and the host says so. It is a 404 like
  // the other "nothing there" answers (WebviewServer.handleOpen), and asserting it is what keeps "headless"
  // from being reported as a success by a caller that only looked at the status class. That it is a 404 rather
  // than a 403 is also the proof that the discovered token was accepted.
  const headless = await openPath(host.port, headlessProject.file, host.token);
  check('404 with no editor adapter attached, rather than a false success', headless.status === 404,
    `${headless.status} ${headless.body}`);

  // The rate limit is per host and lasts 20 s, so this is the last thing done to this one: 20 answered calls,
  // then the 21st is refused. Every call above counted against the same budget.
  let refused = null;
  for (let i = 0; i < 25 && !refused; i++) {
    const attempt = await openPath(host.port, headlessProject.file, host.token);
    if (attempt.status === 429) {
      refused = attempt;
    }
  }
  check('429 once the rate limit (20 per 20 s) is spent', refused !== null && refused.status === 429,
    refused ? refused.body : 'no 429 within 25 further calls');
  check('and the refusal names the limit and its window',
    refused !== null && refused.body.includes('20 per 20s'), refused ? refused.body : 'no refusal');

  // --- 3. the 200, with an adapter that can actually open a file --------------------------------------
  //
  // A project of its own, because a second host for the *same* project declines to serve (DEC-033), and a fresh
  // one for the rate limit above, which belongs to the first host.
  const zed = zedOnPath();
  if (!zed) {
    console.log('note no Zed CLI on the PATH — the 200 assertion was skipped, not passed');
  } else {
    const withEditorProject = makeProject('with-editor');
    const withEditor = await startHost(withEditorProject, 'zed-cli');
    check('the host names the adapter it attached in /health',
      withEditor.health.ide === 'webviewd', JSON.stringify(withEditor.health.ide));
    const manifest = await (await fetch(`http://127.0.0.1:${withEditor.port}/.well-known/webview.json`)).json();
    check('the manifest lists the open capability', manifest.capabilities.includes('open'),
      JSON.stringify(manifest.capabilities));
    check('and is honest that the Zed CLI cannot place a caret', manifest.host.lineNavigation === 'file-only',
      JSON.stringify(manifest.host.lineNavigation));

    const opened = await openPath(withEditor.port, withEditorProject.file, withEditor.token);
    check('200 for a file in the project, with an adapter attached', opened.status === 200,
      `${opened.status} ${opened.body} (adapter ${zed})`);
    check('and the answer names the location that was opened',
      opened.body.includes('Sample.java') && opened.body.includes(':3:1'), opened.body);
  }
} catch (error) {
  failed++;
  console.log(`FAIL the scenario did not complete — ${error.message}`);
} finally {
  stopAll();
  rmSync(root, { recursive: true, force: true });
  // The host writes its port record into the *served* project, which is the temp fixture — so this repository is
  // only touched if an earlier run of this check served the repository itself. Clear that, and say so.
  for (const stale of ['host.json', 'token']) {
    const orphan = join(repoHostState, stale);
    if (existsSync(orphan)) {
      rmSync(orphan, { force: true });
      console.log(`note removed a stale ${orphan.replace(`${repo}\\`, '').replaceAll('\\', '/')} `
        + '(a previous run served this repository; host state belongs to a served project, not to a check)');
    }
  }
}

console.log(`\n${passed} passed, ${failed} failed`);
console.log(reportDuration(elapsed(), { what: 'open-route statuses checked' }));
process.exit(failed === 0 ? 0 : 1);
