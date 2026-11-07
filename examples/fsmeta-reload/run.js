#!/usr/bin/env bun
/**
 * run.js - one command for the fs-meta reload example.
 *
 * What it does, in order:
 *
 *   1. resolves the JDK 25 and the Apache Maven launcher the repository's tooling resolves;
 *   2. cleans the example's classes directory, so the run starts from no class files at all;
 *   3. builds and installs the example module (and the reactor modules it depends on) offline - the Maven
 *      invocation only compiles the runner, never the project sources; those are compiled at run time;
 *   4. starts a private fs-meta daemon under `.tmp/fsmeta-reload` and registers the example's `project/`
 *      directory with it (`root add ... --mode always --idle 0 --tick 50`), so edits are batched immediately;
 *   5. runs `mvn -o -pl :fsmeta-reload-example exec:java` with the two paths as Maven properties, streaming
 *      its stdio: the runner subscribes to that daemon, then prints the initial app/test output and waits;
 *   6. on exit or Ctrl+C, stops the private daemon.
 *
 * While it runs: edit and save
 * `project/src/main/java/demo/project/Calculation.java` (or `App.java`, or the test). Each save becomes one
 * fs-meta batch, and the runner prints `[reload] compile=..ms reload=..ms app=..ms test=..ms
 * totalFromSave=..ms` after recompiling, reloading and re-running the project.
 *
 * Environment:
 *   FSMETA_BIN   the fs-meta executable (default `D:\wrk\core\fs-meta\zig-out\bin\fsmeta.exe`)
 *   FSMETA_TICK_MS  the root scheduler tick (default 50; 5..1000; the scheduler's 25 ms quiet floor applies)
 *   JCODEBUDDY_JDK25 / JCODEBUDDY_MVN   as for every other script in `scripts/`
 */

import { existsSync, mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { envWith, repoRoot, resolveJdk25, resolveMaven, run } from "../../scripts/lib/toolchain.js";

const HERE = import.meta.dir;
const REPO = repoRoot();
const TMP = join(REPO, ".tmp", "fsmeta-reload");
const FS = process.env.FSMETA_BIN ?? "D:\\wrk\\core\\fs-meta\\zig-out\\bin\\fsmeta.exe";
const TICK_MS = process.env.FSMETA_TICK_MS ?? "50";

const MODULE = ":fsmeta-reload-example";
const PROJECT = join(HERE, "project");
const CLASSES = join(PROJECT, "classes");
const EDIT_ME = join(PROJECT, "src", "main", "java", "demo", "project", "Calculation.java");

const DAEMON_BASE = join(TMP, "daemon");
const DAEMON_CONFIG = join(TMP, "config");

/** The environment every fs-meta call and the runner inherit: the private daemon's own state directories. */
let daemonEnv = null;

function step(message) {
  console.log(`[run] ${message}`);
}

function fail(message, code = 1) {
  console.error(`[run] ${message}`);
  process.exit(code);
}

/** Runs the Maven launcher and returns its captured output; the caller decides what a failure means. */
function maven(args) {
  return run(launcher.command, args, { stdio: "pipe", env, cwd: REPO });
}

/** Runs fs-meta and returns its captured output. Only for commands that return without leaving the daemon up. */
function fsmeta(args) {
  return run(FS, args, { stdio: "pipe", env: daemonEnv, cwd: PROJECT });
}

/**
 * Starts the daemon and waits only for the start command to return; the daemon itself keeps running.
 *
 * Its stdio is ignored on purpose: a daemonized child that inherited a pipe keeps that pipe open, so a call
 * that captures the start command's output would wait for a process that is never going to exit.
 */
async function startDaemon() {
  const starting = Bun.spawn([FS, "daemon", "start", "--base", DAEMON_BASE], {
    cwd: PROJECT,
    env: daemonEnv,
    stdin: "ignore",
    stdout: "ignore",
    stderr: "ignore",
  });
  return await starting.exited;
}

/** Stops the private daemon. Best effort: it is called from the exit paths and at the end. */
function stopDaemon() {
  if (!daemonEnv) {
    return;
  }
  spawnSync(FS, ["daemon", "stop", "--base", DAEMON_BASE], {
    cwd: PROJECT,
    env: daemonEnv,
    stdio: "ignore",
    timeout: 10_000,
  });
}

const jdk = resolveJdk25();
const launcher = resolveMaven();
const env = envWith(jdk);

step(`JDK ${jdk.version}: ${jdk.home}`);
step(`Maven: ${launcher.distribution}`);
if (!existsSync(FS)) {
  fail(`no fs-meta executable at ${FS}; set FSMETA_BIN to the fs-meta build you have`, 2);
}

// The project's class files are the runner's output, not an input: a stale one from an earlier run would be
// seeded into the first loader and could hide a compile that did not happen.
rmSync(CLASSES, { recursive: true, force: true });
mkdirSync(CLASSES, { recursive: true });
step(`cleaned ${CLASSES}`);

step("building and installing the example module (offline)");
const build = maven(["-o", "-q", "-pl", MODULE, "-am", "install", "-DskipTests=true"]);
if (build.status !== 0) {
  console.error(build.stdout);
  console.error(build.stderr);
  fail("building the example module failed");
}

// A private daemon: its own socket, config and root list, so nothing here touches a developer's own daemons,
// and stopping it at the end leaves no watcher behind.
mkdirSync(TMP, { recursive: true });
daemonEnv = { ...env, FSMETA_CONFIG_HOME: DAEMON_CONFIG, FSMETA_RUNTIME_DIR: DAEMON_BASE };
stopDaemon();
rmSync(DAEMON_CONFIG, { recursive: true, force: true });
rmSync(DAEMON_BASE, { recursive: true, force: true });
mkdirSync(DAEMON_BASE, { recursive: true });

step(`starting a private fs-meta daemon (base ${DAEMON_BASE})`);
const startedCode = await startDaemon();
if (startedCode !== 0) {
  fail(`starting the fs-meta daemon failed (exit ${startedCode}); see ${join(DAEMON_BASE, "logs")}`);
}
const added = fsmeta(["root", "add", PROJECT, "--mode", "always", "--idle", "0", "--tick", TICK_MS,
  "--base", DAEMON_BASE]);
if (added.status !== 0) {
  console.error(added.stdout);
  console.error(added.stderr);
  stopDaemon();
  fail(`registering ${PROJECT} with the daemon failed`);
}

step(`watching ${PROJECT} (root: the project directory, filter: src/**/*.java, tick: ${TICK_MS} ms)`);
step(`edit and save: ${EDIT_ME}`);
step("Ctrl+C stops the runner and the private daemon");

// `exec:java` runs the runner inside Maven's JVM. The two paths are passed as separate Maven properties, which
// the POM maps to two `<arguments>` entries: each `-D` is one argv token (no whitespace splitting, unlike
// `exec.args`), and a checkout whose path contains a space still reaches the runner intact.
const runner = Bun.spawn(
  [
    launcher.command,
    "-o",
    "-q",
    "-pl",
    MODULE,
    `-Dreload.exampleRoot=${HERE}`,
    `-Dreload.classesDir=${CLASSES}`,
    "exec:java",
  ],
  { cwd: REPO, env: daemonEnv, stdout: "inherit", stderr: "inherit", stdin: "inherit" },
);

let stopping = false;
function finish(code) {
  if (stopping) {
    return;
  }
  stopping = true;
  stopDaemon();
  process.exit(code);
}

process.on("SIGINT", () => {
  console.log("\n[run] interrupted, stopping the private daemon");
  try {
    runner.kill();
  } catch {
    // Already gone: the daemon is what needs stopping.
  }
  finish(130);
});
process.on("SIGTERM", () => finish(143));

const exitCode = await runner.exited;
step(`the runner exited with ${exitCode}; stopping the private daemon`);
finish(exitCode ?? 1);
