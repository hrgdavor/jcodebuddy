#!/usr/bin/env bun
/**
 * gen.js — run JCodeBuddy on the side: regenerate this repository's entity output with one command, or watch the
 * sources and regenerate after each save.
 *
 * This replaces `scripts/gen.cmd` (the batch wrapper, now that scripts are Bun JavaScript — AGENTS.md § 2) and a
 * 16-line unreferenced stub that had lived at this path since the initial commit: it parsed `--rec`/`--from`
 * arguments and did nothing with them.
 *
 * The converted module is `hipster-entity-example`; there is exactly one today (DEC-026), so this script scopes
 * itself to the entity module set.
 *
 * WHY THIS IS NOT A MAVEN BUILD STEP
 *   JCodeBuddy in this project uses no annotation processing and no compile hooks. The generator is NOT bound to
 *   any lifecycle phase, so `mvn compile`, `mvn package` and `mvn test` only ever compile the committed generated
 *   source already in `src/main/java`. This script is how the pass is actually run: manually, or in `watch` mode.
 *
 * WHY IT DOES NOT USE `mvn exec:java`
 *   Two independent reasons, both measured:
 *     1. A direct goal invocation (`mvn exec:java@id`) runs on EVERY module in the reactor and fails on the parent
 *        and on every module without such an execution. Maven has no per-module selector for a direct goal.
 *     2. `exec:java` resolves the `provided` tooling dependency from the local repository instead of the reactor,
 *        so it silently runs whatever jar is installed in `~/.m2`.
 *   Maven's official answer for "use the reactor's classes without install" is `dependency:build-classpath`, which
 *   maps a reactor dependency to that module's `target/classes` directory. That is what happens below.
 *
 * NO JARS, NO INSTALL, COMPILE AT MOST
 *   The pass runs `java -cp <exported classpath>` against `target/classes`. No jar is packaged and nothing is
 *   installed into `~/.m2`, so a re-run after an edit is: compile (incremental) + one JVM start.
 *
 * Usage:
 *   bun scripts/gen.js                   regenerate, then render the HTML entity index; tests skipped
 *   bun scripts/gen.js with-tests        regenerate and run the entity test set
 *   bun scripts/gen.js watch             regenerate now, then regenerate on save
 *   bun scripts/gen.js <maven args...>   forwarded to the entity module set
 *
 * Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES.
 */

import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildGateArgs, splitPropertyAdvice } from './lib/gate.js';
import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

const REPO = repoRoot();
const EXAMPLE = join(REPO, 'hipster-entity-example');
const AGENT_STATE = join(EXAMPLE, '.jcodebuddy', 'agent-state');
const PACKAGES = 'hr.hrg.hipster.entityexample.person.entity,'
  + 'hr.hrg.hipster.entityexample.paymentMethod.entity';
const SOURCE_ROOT = join(EXAMPLE, 'src', 'main', 'java');
const METADATA_DIR = join(EXAMPLE, '.jcodebuddy', 'metadata', 'entity');

/** The lines worth showing after a pass; the full log stays on disk. */
const REPORT_KEYS = [
  'preflight ok', 'source root', 'Writing generated java', 'HTML entity index',
  'Link check', 'Divergences:', 'Validation:', 'divergences', 'BUILD',
];

function usage() {
  console.log(`gen.js — run the entity generator as a side tool (not a build step)

  bun scripts/gen.js                   regenerate, then render the HTML entity index
  bun scripts/gen.js with-tests        regenerate, then the entity test set
  bun scripts/gen.js watch             regenerate now, then on every save (Ctrl+C stops it)
  bun scripts/gen.js <maven args...>   forwarded to the entity module set

  Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES`);
}

function log(text, logPath, { echo = false } = {}) {
  appendFileSync(logPath, text);
  if (echo) {
    process.stdout.write(text);
  }
}

/** Runs a command, appending its output to the log. Returns its exit status. */
function step(command, args, { logPath, echo = false, env, cwd }) {
  log(`\n$ ${command} ${args.join(' ')}\n`, logPath);
  const result = run(command, args, { stdio: 'pipe', env, cwd });
  if (result.stdout) {
    log(result.stdout, logPath, { echo });
  }
  if (result.stderr) {
    log(result.stderr, logPath, { echo });
  }
  if (result.error) {
    log(`\n[gen] could not run ${command}: ${result.error.message}\n`, logPath);
    return 1;
  }
  return result.status;
}

/**
 * Reads the classpath Maven exported, prepending the module's own `target/classes`.
 *
 * `dependency:build-classpath` lists a module's *dependencies*, not its own output, and the entry point being run
 * lives there. The separator is the platform's — the `.cmd` could only ever emit `;`.
 */
function classpathFrom(file, moduleDir) {
  if (!existsSync(file)) {
    throw new Error(`no classpath file at ${file} — the export step failed`);
  }
  const exported = readFileSync(file, 'utf8').trim();
  if (!exported) {
    throw new Error(`the exported classpath file is empty: ${file}`);
  }
  const classes = join(moduleDir, 'target', 'classes');
  if (!existsSync(classes)) {
    throw new Error(`no compiled classes at ${classes} — the compile step failed`);
  }
  return `${classes}${delimiter}${exported}`;
}

function report(logPath, status) {
  console.log('[gen] what the pass did:');
  const lines = existsSync(logPath) ? readFileSync(logPath, 'utf8').split('\n') : [];
  for (const line of lines) {
    if (REPORT_KEYS.some((key) => line.includes(key))) {
      console.log(`  ${line.trim()}`);
    }
  }
  console.log(`[gen] full log: ${logPath}`);
  if (status !== 0) {
    console.error(`[gen] FAILED with exit code ${status}.`);
    console.error('[gen] A failure naming GeneratorPreflight means the tooling being run is older than this '
      + 'invocation, so it would ignore --java-out and write generated .java into the metadata directory. '
      + 'Re-run; if it persists, the local repository copy is being resolved instead of the reactor: compile '
      + 'with `bun scripts/mvn-jdk25.js -o -pl hipster-entity-tooling -am compile`.');
  }
  return status;
}

/** The HTML entity index (DEC-027). It is a Bun script, so it runs in this same runtime. */
function htmlIndex(logPath) {
  // "A missing Bun is reported and skipped" used to be a branch here, because the pass could run without Bun.
  // That case is gone: this script *is* Bun, so the renderer's exit status is the only outcome left.
  // A page that renders but cannot verify a link IS fatal: a link to the wrong line is worse than no link.
  return step('bun', ['run', join(REPO, 'scripts', 'entity-html', 'index.js'),
    '--module', 'hipster-entity-example'], { logPath });
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) {
    usage();
    return 0;
  }

  mkdirSync(AGENT_STATE, { recursive: true });
  const jdk = resolveJdk25();
  const maven = resolveMaven();
  const env = envWith(jdk);

  const isWatch = argv[0] === 'watch';
  const isTests = argv[0] === 'with-tests';

  const logPath = join(AGENT_STATE, isWatch ? 'watch.log' : 'gen.log');
  writeFileSync(logPath, `[gen] ${new Date().toISOString()}  bun ${Bun.version}\n`);

  // --- watch: the same classpath, but project-automation's watcher --------------------------------
  if (isWatch) {
    const classpathFile = join(AGENT_STATE, 'watch-classpath.txt');
    // A stale classpath file is deleted first: it could otherwise be read after a failed export, and the watcher
    // would run against a previous classpath. Its own log and its own file, so a watch session and a one-shot
    // pass can run at the same time without truncating each other's log.
    rmSync(classpathFile, { force: true });
    const exportStatus = step(maven.command, ['-o', '-pl', 'project-automation', '-am', 'compile',
      'dependency:build-classpath', `-Dmdep.outputFile=${classpathFile}`], { logPath, env });
    if (exportStatus !== 0) {
      console.error(`[gen] could not build the watch classpath; see ${logPath}`);
      return report(logPath, exportStatus);
    }
    let classpath;
    try {
      classpath = classpathFrom(classpathFile, join(REPO, 'project-automation'));
    } catch (error) {
      console.error(`[gen] ${error.message}`);
      return report(logPath, 1);
    }
    console.log(`[gen] watching ${SOURCE_ROOT} - press Ctrl+C to stop`);
    const status = run(jdk.java, ['-cp', classpath,
      'hr.hrg.jcodebuddy.automation.entity.EntityRegenerationWatcher',
      '--source', SOURCE_ROOT, '--packages', PACKAGES], { stdio: 'inherit', env });
    return status.status;
  }

  // --- forwarded arguments: the gate's shortcut with the caller's goals ---------------------------
  if (!isTests && argv.length > 0) {
    const gate = buildGateArgs(['hipster-entity', ...argv]);
    if (gate.error) {
      console.error(splitPropertyAdvice('gen', gate.error));
      return 2;
    }
    return report(logPath, step(maven.command, gate.args, { logPath, env }));
  }

  // --- with-tests: the pass, then the entity test set ---------------------------------------------
  if (isTests) {
    return report(logPath, step(maven.command, buildGateArgs(['hipster-entity', 'test']).args, { logPath, env }));
  }

  // --- the side-car pass -------------------------------------------------------------------------
  const classpathFile = join(AGENT_STATE, 'gen-classpath.txt');
  rmSync(classpathFile, { force: true });
  const exportStatus = step(maven.command, ['-o', '-pl', 'hipster-entity-tooling,hipster-entity-example',
    '-am', 'compile', 'dependency:build-classpath', `-Dmdep.outputFile=${classpathFile}`], { logPath, env });
  if (exportStatus !== 0) {
    return report(logPath, exportStatus);
  }

  let classpath;
  try {
    classpath = classpathFrom(classpathFile, join(REPO, 'hipster-entity-tooling'));
  } catch (error) {
    console.error(`[gen] ${error.message}`);
    return report(logPath, 1);
  }

  let status = step(jdk.java, ['-cp', classpath, 'hr.hrg.hipster.entity.tooling.GeneratorPreflight'],
    { logPath, env });
  if (status !== 0) {
    return report(logPath, status);
  }

  status = step(jdk.java, ['-cp', classpath, 'hr.hrg.hipster.entity.tooling.EntityMetadataGenerator',
    SOURCE_ROOT, METADATA_DIR, '--java-out', SOURCE_ROOT, '--packages', PACKAGES, '--validate',
    '--run-record', join(METADATA_DIR, 'generation.json')], { logPath, env });
  if (status !== 0) {
    return report(logPath, status);
  }

  return report(logPath, htmlIndex(logPath));
}

try {
  process.exit(main());
} catch (error) {
  console.error(`[gen] ${error.message}`);
  process.exit(1);
}
