#!/usr/bin/env bun
/**
 * run-watch-sample.js
 * ─────────────────────────────────────────────────────────────────────────────
 * Builds the java-watch-run daemon and the sample project, then launches the
 * daemon watching java-watch-run-sample/src/ for .java changes.
 *
 * Usage (from the java_watch2 root):
 *   bun run-watch-sample.js [--debounce=<ms>]
 *
 * Options:
 *   --debounce=<ms>   Quiet window after last save before reload fires.
 *                     Default: 300 ms.  Try --debounce=0 for minimum latency.
 *
 * Latency measurement (reading the console output):
 *   - Daemon log "ECJ full/incremental compile" → compile started (T0)
 *   - "⚡ Hot-reload" banner in SampleMain      → main() started  (T1)
 *   - Printed "Reload latency"                  → T1 - T0  (compile+load)
 *   - Add debounce window                        → total perceived latency
 * ─────────────────────────────────────────────────────────────────────────────
 */

import { join, dirname } from "node:path";
import { existsSync, mkdirSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

// ── Helpers ───────────────────────────────────────────────────────────────────

const root = dirname(fileURLToPath(import.meta.url));
const isWindows = process.platform === "win32";
const sep = isWindows ? ";" : ":";

function color(code, text) {
  return `\x1b[${code}m${text}\x1b[0m`;
}
const cyan    = t => color(36, t);
const green   = t => color(32, t);
const yellow  = t => color(33, t);
const magenta = t => color(35, t);
const gray    = t => color(90, t);

/**
 * Run a command synchronously, inheriting stdout/stderr.
 * Exits the process if the command fails.
 */
function run(cmd, args, { cwd = root, label = cmd } = {}) {
  const result = spawnSync(cmd, args, {
    cwd,
    stdio: "inherit",
    shell: isWindows,   // needed for mvn.cmd on Windows
  });
  if (result.status !== 0) {
    console.error(`\nERROR: "${label}" failed (exit ${result.status})`);
    process.exit(1);
  }
}

/**
 * Capture stdout of a command; return trimmed string.
 * Returns "" on failure instead of throwing.
 */
function capture(cmd, args, { cwd = root } = {}) {
  const result = spawnSync(cmd, args, { cwd, shell: isWindows });
  return result.status === 0 ? result.stdout.toString().trim() : "";
}

// ── 1. Build the daemon fat jar ───────────────────────────────────────────────
console.log();
console.log(cyan("► Building java-watch-run daemon..."));
run("mvn", ["package", "-q", "-pl", "java-watch-run", "-am", "-DskipTests"],
    { label: "mvn package" });
console.log(green("  Done."));

// ── 2. Install to local Maven repo (so sample pom can resolve it) ─────────────
console.log();
console.log(cyan("► Installing java-watch-run to local Maven repo..."));
run("mvn", ["install", "-q", "-pl", "java-watch-run", "-am", "-DskipTests"],
    { label: "mvn install" });
console.log(green("  Done."));

// ── 3. Copy sample runtime deps to lib/ ──────────────────────────────────────
console.log();
console.log(cyan("► Fetching sample project dependencies into lib/..."));
run("mvn", ["generate-resources", "-q"],
    { cwd: join(root, "java-watch-run-sample"), label: "mvn generate-resources" });
console.log(green("  Done."));

// ── 4. Ensure bin/ output dir exists ─────────────────────────────────────────
const binDir = join(root, "java-watch-run-sample", "bin");
if (!existsSync(binDir)) mkdirSync(binDir, { recursive: true });

// ── 5. Build classpath ────────────────────────────────────────────────────────
const daemonJar = join(root, "java-watch-run", "target", "java-watch-run.jar");
const libDir    = join(root, "java-watch-run-sample", "lib");

const libJars = existsSync(libDir)
  ? readdirSync(libDir)
      .filter(f => f.endsWith(".jar"))
      .map(f => join(libDir, f))
  : [];

const classpath = [...libJars, daemonJar].join(sep);

const srcDir    = join(root, "java-watch-run-sample", "src");
const mainCls   = "hr.hrg.watch2.sample.SampleMain";

// ── 5b. Parse --debounce from this script's CLI ───────────────────────────────
let debounceArg = null;
for (const arg of process.argv.slice(2)) {
  if (arg.startsWith("--debounce=")) { debounceArg = arg; break; }
}

// ── 6. Locate the JDK that Maven uses ────────────────────────────────────────
// Prefer JAVA_HOME env var; fall back to asking Maven what java.home it sees.
function findJavaExe() {
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const exe = join(javaHome, "bin", isWindows ? "java.exe" : "java");
    if (existsSync(exe)) return exe;
  }

  console.log(cyan("► Detecting Maven's JDK..."));
  const mavenJavaHome = capture("mvn", [
    "help:evaluate",
    "-Dexpression=java.home",
    "-q",
    "-DforceStdout",
    "-pl", "java-watch-run",
  ]);

  // Filter out Maven log lines (start with "[")
  const home = mavenJavaHome
    .split("\n")
    .map(l => l.trim())
    .filter(l => l && !l.startsWith("["))
    .at(-1) ?? "";

  const exe = join(home, "bin", isWindows ? "java.exe" : "java");
  return existsSync(exe) ? exe : "java"; // last-resort fallback
}

const javaExe = findJavaExe();
console.log(gray(`  Java  : ${javaExe}`));

// ── 7. Launch ─────────────────────────────────────────────────────────────────
console.log();
console.log(magenta("══════════════════════════════════════════════════════"));
console.log(magenta("  java-watch-run  ·  Hot-Reload Daemon"));
console.log(magenta("══════════════════════════════════════════════════════"));
console.log(gray(`  Watching : ${srcDir}`));
console.log(gray(`  Output   : ${binDir}`));
console.log(gray(`  Main     : ${mainCls}`));
console.log(gray(`  Debounce : ${debounceArg ? debounceArg.split("=")[1] + " ms" : "300 ms (default)"}  ← use --debounce=0 to minimize latency`));
console.log();
console.log(yellow("  Edit any .java file in src/ and save to trigger reload."));
console.log(yellow("  Change VERSION in SampleMain.java for the simplest test."));
console.log(magenta("══════════════════════════════════════════════════════"));
console.log();

// Hand off to Java — inherit all stdio so output streams through directly.
// --debounce is forwarded as a flag to HotSwapDaemon *before* positional args.
const javaArgs = [
  "-cp", classpath,
  "-Dorg.slf4j.simpleLogger.showDateTime=true",
  "-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS",
  "-Dorg.slf4j.simpleLogger.showThreadName=false",
  "-Dorg.slf4j.simpleLogger.levelInBrackets=true",
  "hr.hrg.watch2.run.HotSwapDaemon",
  ...(debounceArg ? [debounceArg] : []),
  srcDir,
  binDir,
  mainCls,
];

const proc = spawnSync(javaExe, javaArgs, { cwd: root, stdio: "inherit", shell: false });
process.exit(proc.status ?? 0);
