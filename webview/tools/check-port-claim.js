#!/usr/bin/env bun
/**
 * check-port-claim.js — the one-bridge-per-project gate (DEC-033).
 *
 * The port rule has three cases that only a *second process* can demonstrate, and they are the ones a
 * user hits: two IDEs open on one project, an unrelated application squatting on the conventional port,
 * and a host for a different project holding it. Unit tests cover the decision; this script covers the
 * thing the decision is about, by starting real `webviewd` processes and reading the files and the exit
 * codes they leave behind.
 *
 *   1. **first host** — starts on an ephemeral port and publishes it;
 *   2. **second host, same project, same port** — declines to serve at all (exit `2`), says who is already
 *      there, and does **not** overwrite the first host's descriptor, because that is not its record;
 *   3. **host for another project, same port** — steps over it, serves on another port, and publishes
 *      *that* in the other project's own `.jcodebuddy/`.
 *
 * There is no mock in this file: every assertion is read off a live process's `/health`, its descriptor,
 * or its exit code.
 *
 *   bun webview/tools/check-port-claim.js [path-to-webviewd.jar]
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const jar = process.argv[2] ?? path.join(repo, 'webview', 'core', 'webviewd', 'target', 'webviewd.jar');
const java = process.env.WEBVIEWD_JAVA
  ?? (process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java');
const token = 'port-claim-gate';

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

/** Two throwaway projects, so nothing in the repository is served or written. */
const root = path.join(repo, 'target', 'port-claim-gate');
fs.rmSync(root, { recursive: true, force: true });
const projectA = path.join(root, 'project-a');
const projectB = path.join(root, 'project-b');
for (const project of [projectA, projectB]) {
  fs.mkdirSync(path.join(project, 'src'), { recursive: true });
  fs.writeFileSync(path.join(project, 'src', 'A.java'), 'class A {}\n', 'utf8');
}

const descriptorOf = (project) => path.join(project, '.jcodebuddy', 'webview', 'host.json');

/**
 * Starts a host and waits until it has published a port, or until it exits.
 *
 * @param port  the port to ask for, or null to ask for nothing at all (which lets the project's own record
 *              and committed default decide, and is the case the two-file model is about)
 * @param extra additional command-line arguments, appended after the port
 */
function startHost(project, port, extra = []) {
  const args = ['-jar', jar, '--project', project, '--host', 'none', '--token', token];
  if (port !== null) {
    args.push('--port', String(port));
  }
  args.push(...extra);
  const child = spawn(java, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  const state = { child, output: '', code: null };
  child.stdout.on('data', (chunk) => { state.output += chunk; });
  child.stderr.on('data', (chunk) => { state.output += chunk; });
  child.on('exit', (code) => { state.code = code; });
  return state;
}

/** The port a project's descriptor names, or 0 while it has not been written yet. */
function publishedPort(project) {
  try {
    return JSON.parse(fs.readFileSync(descriptorOf(project), 'utf8')).port ?? 0;
  } catch {
    return 0;
  }
}

async function waitForPort(project, host, timeoutMillis = 20000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    const port = publishedPort(project);
    if (port > 0) {
      return port;
    }
    if (host.code !== null) {
      throw new Error(`the host exited with ${host.code} before publishing a port:\n${host.output}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`no port was published in ${descriptorOf(project)}:\n${host.output}`);
}

/** Waits for a host that is expected to stop, and reports how it stopped. */
async function waitForExit(host, timeoutMillis = 20000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline && host.code === null) {
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return host.code;
}

/** Waits until the record has been rewritten by this host, and returns what it now says. */
async function waitForRepublish(project, host, timeoutMillis = 20000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    try {
      const record = JSON.parse(fs.readFileSync(descriptorOf(project), 'utf8'));
      if (record.pid === host.child.pid) {
        return record;
      }
    } catch { /* not written yet, or mid-write */ }
    if (host.code !== null) {
      throw new Error(`the host exited with ${host.code} before republishing:\n${host.output}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`the record was never rewritten by pid ${host.child.pid}:\n${host.output}`);
}

/** A port nobody holds right now: bind 0, read what we got, let it go. */
function freePort() {
  return new Promise((resolve) => {
    const probe = net.createServer();
    probe.listen(0, '127.0.0.1', () => {
      const port = probe.address().port;
      probe.close(() => resolve(port));
    });
  });
}

/** Waits until a port can be bound again, so a restart is not racing a dying process. */
function waitForFreePort(port, timeoutMillis = 10000) {
  const deadline = Date.now() + timeoutMillis;
  return new Promise((resolve) => {
    const attempt = () => {
      const probe = net.createServer();
      probe.once('error', () => {
        if (Date.now() > deadline) {
          resolve(false);
          return;
        }
        setTimeout(attempt, 100);
      });
      probe.listen(port, '127.0.0.1', () => probe.close(() => resolve(true)));
    };
    attempt();
  });
}

const hosts = [];
function stopAll() {
  for (const host of hosts) {
    if (host.code === null) {
      host.child.kill();
    }
  }
}

try {
  // --- 1. the first host takes an ephemeral port and publishes it ---------------------------------------
  const first = startHost(projectA, 0);
  hosts.push(first);
  const firstPort = await waitForPort(projectA, first);
  const firstDescriptor = JSON.parse(fs.readFileSync(descriptorOf(projectA), 'utf8'));

  check('the first host publishes the port it bound', firstPort > 0, String(firstPort));
  check('and the editor that published it', firstDescriptor.ide === 'webviewd',
    JSON.stringify(firstDescriptor.ide));
  check('and the project the port belongs to', firstDescriptor.project === projectA.replace(/\\/g, '/'),
    JSON.stringify(firstDescriptor.project));

  // --- 2. a second host for THE SAME project must open nothing ------------------------------------------
  const second = startHost(projectA, firstPort);
  hosts.push(second);
  const secondCode = await waitForExit(second);

  check('a second host for the same project declines to serve', secondCode === 2,
    `exit ${secondCode}: ${second.output.trim().split('\n').slice(-3).join(' | ')}`);
  check('and says which host already serves the project',
    second.output.includes('already serves this project') || second.output.includes('already running'),
    second.output.trim().split('\n').slice(-3).join(' | '));
  check('and names the port that is already serving', second.output.includes(String(firstPort)),
    second.output.trim().split('\n').slice(-3).join(' | '));

  const afterSecond = JSON.parse(fs.readFileSync(descriptorOf(projectA), 'utf8'));
  check('the declined host did not take over the published port',
    afterSecond.port === firstPort && afterSecond.pid === firstDescriptor.pid,
    `${afterSecond.port}/${afterSecond.pid} vs ${firstPort}/${firstDescriptor.pid}`);

  // --- 3. a host for ANOTHER project steps over that port ------------------------------------------------
  const other = startHost(projectB, firstPort);
  hosts.push(other);
  const otherPort = await waitForPort(projectB, other);
  const otherDescriptor = JSON.parse(fs.readFileSync(descriptorOf(projectB), 'utf8'));

  check('a host for another project does not stop: it serves somewhere else', otherPort > 0
    && otherPort !== firstPort, `first ${firstPort}, other ${otherPort}`);
  check('and publishes its own port in its own project', otherDescriptor.project === projectB.replace(/\\/g, '/'),
    JSON.stringify(otherDescriptor.project));
  check('the first host keeps its port and its descriptor', publishedPort(projectA) === firstPort,
    `${publishedPort(projectA)} vs ${firstPort}`);

  // Both hosts answer /health, and each names the project it serves — which is the fact the whole rule
  // turns on, so it is read from the live endpoints rather than from the files they wrote.
  const healthA = await (await fetch(`http://127.0.0.1:${firstPort}/health`)).json();
  const healthB = await (await fetch(`http://127.0.0.1:${otherPort}/health`)).json();
  check('the first host answers /health for its project', healthA.project === projectA.replace(/\\/g, '/'),
    JSON.stringify(healthA.project));
  check('the other host answers /health for its own project',
    healthB.project === projectB.replace(/\\/g, '/'), JSON.stringify(healthB.project));
  check('and both name the editor that answers', healthA.ide === 'webviewd' && healthB.ide === 'webviewd',
    `${healthA.ide} / ${healthB.ide}`);

  // --- 4. a pinned port: honoured, and an error when somebody else holds it ------------------------------
  //
  // The case this exists for: several worktrees of one project, one of which must keep its port (a bookmark,
  // a firewall rule, a second screen). The pair below is the whole rule — the pinned project refuses to move,
  // and an identical project without the pin steps over the same occupied port.
  const projectC = path.join(root, 'project-pinned');
  const projectD = path.join(root, 'project-unpinned');
  for (const project of [projectC, projectD]) {
    fs.mkdirSync(path.join(project, 'src'), { recursive: true });
    fs.writeFileSync(path.join(project, 'src', 'A.java'), 'class A {}\n', 'utf8');
  }

  const pinned = startHost(projectC, 0, ['--sticky']);
  hosts.push(pinned);
  const pinnedPort = await waitForPort(projectC, pinned);
  const pinnedRecord = JSON.parse(fs.readFileSync(descriptorOf(projectC), 'utf8'));
  check('--sticky records the pin beside the port it bound', pinnedRecord.sticky === true,
    JSON.stringify(pinnedRecord.sticky));

  // Stop that host and make the record deterministic: a pin must outlive the process that set it, which is the
  // whole point, and a killed host may or may not have run its shutdown hook.
  pinned.child.kill();
  await waitForExit(pinned);
  fs.writeFileSync(descriptorOf(projectC), JSON.stringify({ ...pinnedRecord, pid: 999999999 }, null, 2), 'utf8');

  const stranger = Bun.serve({
    port: pinnedPort,
    hostname: '127.0.0.1',
    fetch() {
      return new Response('no', { status: 404 });
    }
  });
  try {
    const refused = startHost(projectC, 0);
    hosts.push(refused);
    const refusedCode = await waitForExit(refused);

    check('a pinned port held by another application is reported, not moved past', refusedCode === 2,
      `exit ${refusedCode}: ${refused.output.trim().split('\n').slice(-3).join(' | ')}`);
    check('and the message names the pinned port and how to unpin it',
      refused.output.includes('sticky') && refused.output.includes(String(pinnedPort)),
      refused.output.trim().split('\n').slice(-3).join(' | '));
    const afterRefusal = JSON.parse(fs.readFileSync(descriptorOf(projectC), 'utf8'));
    check('and nothing was republished: the project still records its pinned port',
      afterRefusal.port === pinnedPort && afterRefusal.sticky === true,
      `${afterRefusal.port}/${afterRefusal.sticky}`);

    // The control: the same occupied port, the same request, but no pin — this one must move.
    const control = startHost(projectD, pinnedPort);
    hosts.push(control);
    const controlPort = await waitForPort(projectD, control);
    const controlRecord = JSON.parse(fs.readFileSync(descriptorOf(projectD), 'utf8'));
    check('without the pin, the same occupied port is stepped over as usual',
      controlPort > 0 && controlPort !== pinnedPort && controlRecord.sticky === false,
      `pinned ${pinnedPort}, control ${controlPort}, sticky ${controlRecord.sticky}`);
  } finally {
    stranger.stop(true);
  }

  // --- 5. the record is the checkout's current port, and it outlives the host --------------------------
  //
  // The two-file model, end to end: `.jcodebuddy/conf/webview.json` is the project's *default* (optional,
  // tracked), `.jcodebuddy/webview/host.json` is the port this *checkout* is on (local, never in git). A host
  // that stops leaves the record, so the next start asks for the same port instead of taking a new one.
  const projectE = path.join(root, 'project-remembered');
  fs.mkdirSync(path.join(projectE, 'src'), { recursive: true });
  fs.writeFileSync(path.join(projectE, 'src', 'A.java'), 'class A {}\n', 'utf8');

  const firstRun = startHost(projectE, 0);
  hosts.push(firstRun);
  const rememberedPort = await waitForPort(projectE, firstRun);
  firstRun.child.kill();
  await waitForExit(firstRun);

  check('a stopped host leaves the record in place: it is the port this checkout is on, not the process\'s',
    fs.existsSync(descriptorOf(projectE)));
  check('and the port it remembers is the one it bound',
    JSON.parse(fs.readFileSync(descriptorOf(projectE), 'utf8')).port === rememberedPort);

  await waitForFreePort(rememberedPort);
  const secondRun = startHost(projectE, null);
  hosts.push(secondRun);
  const republished = await waitForRepublish(projectE, secondRun);

  check(`a restart asks for the port the checkout is on rather than a fresh one`
    + ` (${rememberedPort} vs ${republished.port})`, republished.port === rememberedPort);
  check(`and says where that answer came from: ${secondRun.output.trim().split('\n').slice(-3).join(' | ')}`,
    secondRun.output.includes('currently on'));

  // The committed default is the fallback for a checkout that has no record yet — and it is optional.
  const projectF = path.join(root, 'project-default');
  fs.mkdirSync(path.join(projectF, '.jcodebuddy', 'conf'), { recursive: true });
  const defaultPort = await freePort();
  fs.writeFileSync(path.join(projectF, '.jcodebuddy', 'conf', 'webview.json'),
    `{ "port": ${defaultPort} }`, 'utf8');

  const fromDefault = startHost(projectF, null);
  hosts.push(fromDefault);
  const defaultDecision = await waitForRepublish(projectF, fromDefault);
  check(`a fresh checkout asks for the project's committed default`
    + ` (${defaultPort} vs ${defaultDecision.port})`, defaultDecision.port === defaultPort);
  check(`and says so: ${fromDefault.output.trim().split('\n').slice(-3).join(' | ')}`,
    fromDefault.output.includes('default'));
} catch (error) {
  failed++;
  console.log(`FAIL the scenario did not complete — ${error.message}`);
} finally {
  stopAll();
}

console.log(`\n${passed} passed, ${failed} failed`);
console.log('The IDE hosts run the same rule from the same core (HostPortClaim); their two-process case needs');
console.log('a GUI and is recorded in webview/doc/ide-observation-checklist.md.');
process.exit(failed === 0 ? 0 : 1);
