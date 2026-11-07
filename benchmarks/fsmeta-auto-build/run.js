#!/usr/bin/env bun
/**
 * run.js — fs-meta batch → standalone ECJ incremental compile benchmark.
 *
 * Synthetic Java project, N independent classes, one prepared edit. The daemon root is the
 * project root; the Java runner subscribes at LATEST, indexes the compiler once, writes a ready
 * file, then compiles exactly the `.java` paths the daemon delivers in one batch.
 *
 * This driver builds the benchmark module + dependency classpath first, starts a private fs-meta
 * daemon under `.tmp/`, applies the prepared edit, and prints one JSON line per run.
 */

import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { envWith, resolveJdk25 } from "../../scripts/lib/toolchain.js";

const HERE = import.meta.dir;
const REPO = join(HERE, "..", "..");
const TMP = join(REPO, ".tmp", "fsmeta-auto-build");
const FS = process.env.FSMETA_BIN ?? "D:\\wrk\\core\\fs-meta\\zig-out\\bin\\fsmeta.exe";
const CLASSES = (process.env.BENCH_CLASSES ?? "42").split(",").map(Number);
const REPEAT = Number(process.env.BENCH_REPEAT ?? "1");

function arg(name, fallback) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : fallback;
}

const jdk = resolveJdk25();
const env = envWith(jdk);

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: REPO,
    encoding: "utf8",
    env: env,
    timeout: options.timeout ?? 120_000,
    ...options,
  });
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(`${command} ${args.join(" ")} failed (${result.status}):\n${result.stdout}\n${result.stderr}`);
  }
  return result;
}

function writeFixture(classes, name) {
  const root = join(TMP, name);
  rmSync(root, { recursive: true, force: true });
  mkdirSync(join(root, "src", "bench"), { recursive: true });
  mkdirSync(join(root, "bin"), { recursive: true });
  for (let index = 0; index < classes; index++) {
    writeFileSync(join(root, "src", "bench", `C${index}.java`),
      `package bench;\npublic final class C${index} {\n    public static int value() { return ${index}; }\n}\n`);
  }
  writeFileSync(join(root, "src", "bench", "Warmup.java"),
    "package bench;\nfinal class Warmup { static int value() { return 0; } }\n");
  return root;
}

function buildClasspath() {
  const output = join(TMP, "runtime-classpath.txt");
  rmSync(output, { force: true });
  // Install the two reactor modules the runner needs, so the dependency plugin can resolve them
  // from the local repository on a fresh checkout as well as after an earlier build.
  run("mvn", ["-q", "-o", "-pl", ":jcodebuddy-compile,:jcodebuddy-watch,:fsmeta-auto-build-bench", "-am",
    "install", "-DskipTests=true"], { timeout: 300_000 });
  run("mvn", ["-q", "-o", "-pl", ":fsmeta-auto-build-bench",
    "dependency:build-classpath", `-Dmdep.outputFile=${output}`, "-DincludeScope=runtime"], { timeout: 180_000 });
  return [
    join(REPO, "benchmarks", "fsmeta-auto-build", "target", "classes"),
    join(REPO, "jcodebuddy", "jcodebuddy-compile", "target", "classes"),
    join(REPO, "jcodebuddy", "jcodebuddy-watch", "target", "classes"),
    readFileSync(output, "utf8").trim(),
  ].join(";");
}

async function runOnce(classes, runIndex, classpath) {
  const root = writeFixture(classes, `fixture-${classes}-${runIndex}-${Date.now()}`);
  const stamp = Date.now();
  const base = join(TMP, `daemon-${classes}-${runIndex}-${stamp}`);
  const config = join(TMP, `config-${classes}-${runIndex}-${stamp}`);
  const result = join(TMP, `result-${classes}-${runIndex}-${stamp}.json`);
  const ready = join(TMP, `ready-${classes}-${runIndex}-${stamp}.txt`);
  const start = join(TMP, `start-${classes}-${runIndex}-${stamp}.txt`);
  mkdirSync(base, { recursive: true });
  mkdirSync(config, { recursive: true });
  const daemonEnv = { ...env, FSMETA_CONFIG_HOME: config, FSMETA_RUNTIME_DIR: base };

  const daemon = Bun.spawn([FS, "daemon", "start", "--base", base], {
    cwd: root, env: daemonEnv, stdout: "ignore", stderr: "ignore", stdin: "ignore",
  });
  try {
    await daemon.exited;
    const add = spawnSync(FS, ["root", "add", root, "--mode", "always", "--idle", "0", "--base", base], {
      cwd: root, env: daemonEnv, encoding: "utf8",
    });
    if (add.status !== 0) throw new Error(`root add failed: ${add.stdout}\n${add.stderr}`);

    const runner = Bun.spawn([
      jdk.java, "-cp", classpath, "bench.CompileBatchRunner",
      root, join(root, "src"), join(root, "bin"), result, ready, start, "30000",
    ], { cwd: root, env: daemonEnv, stdout: "pipe", stderr: "pipe" });

    const deadline = Date.now() + 30_000;
    while (!existsSync(ready)) {
      if (Date.now() > deadline) {
        const stderr = await new Response(runner.stderr).text();
        const stdout = await new Response(runner.stdout).text();
        throw new Error(`runner did not become ready\nstdout:\n${stdout}\nstderr:\n${stderr}`);
      }
      await Bun.sleep(25);
    }
    const source = join(root, "src", "bench", "C0.java");
    writeFileSync(source, "package bench;\npublic final class C0 {\n    public static int value() { return 42; }\n}\n");
    writeFileSync(start, String(Date.now()));

    const resultDeadline = Date.now() + 60_000;
    while (!existsSync(result)) {
      if (Date.now() > resultDeadline) throw new Error("runner produced no result");
      await Bun.sleep(25);
    }
    const runnerExit = await runner.exited;
    const value = JSON.parse(readFileSync(result, "utf8"));
    console.log(JSON.stringify({ classes, run: runIndex, runnerExit, ...value }));
    return value;
  } finally {
    spawnSync(FS, ["daemon", "stop", "--base", base], { cwd: root, env: daemonEnv, encoding: "utf8", timeout: 10_000 });
    daemon.kill();
  }
}

try {
  const classpath = buildClasspath();
  for (const classes of CLASSES) {
    for (let index = 0; index < REPEAT; index++) {
      await runOnce(classes, index, classpath);
    }
  }
} catch (error) {
  console.error(String(error?.stack ?? error));
  process.exit(1);
}
