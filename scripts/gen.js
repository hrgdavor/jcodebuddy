#!/usr/bin/env bun
/**
 * gen.js — run JCodeBuddy on the side: regenerate this repository's entity output with one command, or watch the
 * sources and regenerate after each save.
 *
 * Watch mode is the generator's own `--watch` (plan E11): this script forwards the flag, and the fs-meta daemon is
 * the event source. It is not a second watcher and does not route through `project-automation` any more; without a
 * daemon the generator prints what to start and exits non-zero.
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
 *   bun scripts/gen.js watch             regenerate now, then regenerate on save (forwarded as --watch)
 *   bun scripts/gen.js --watch           the same watch mode; the flag is forwarded to the generator CLI
 *   bun scripts/gen.js <maven args...>   forwarded to the entity module set
 *
 * Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES.
 */

import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildGateArgs, splitPropertyAdvice } from './lib/gate.js';
import { failureHint } from './lib/gen-diagnostic.js';
import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

const REPO = repoRoot();
/**
 * The example module's directory, **module-relative**, and the path the HTML renderer is given.
 *
 * <p>It carries the group folder (`hipster-entity/…`) because step 3.0o moved the modules into them, and
 * both consumers of this constant resolve it against the repository root: Maven's `dependency:build-classpath`
 * output is prepended by a path under here, and `scripts/entity-html/index.js` resolves `--module` the same
 * way. A bare `hipster-entity-example` — what this line said before the move — silently resolved to a
 * directory that does not exist, which is how this script ended up reporting a `GeneratorPreflight` failure
 * while the real error was Maven refusing an unknown `-pl` selector.</p>
 */
const EXAMPLE_PATH = 'hipster-entity/hipster-entity-example';
const EXAMPLE = join(REPO, EXAMPLE_PATH);
const AGENT_STATE = join(EXAMPLE, '.jcodebuddy', 'agent-state');
const PACKAGES = 'hr.hrg.hipster.entityexample.person.entity,'
  + 'hr.hrg.hipster.entityexample.paymentMethod.entity';
const SOURCE_ROOT = join(EXAMPLE, 'src', 'main', 'java');
const METADATA_DIR = join(EXAMPLE, '.jcodebuddy', 'metadata', 'entity');
/** The tooling module's directory, for the same reason as {@link EXAMPLE_PATH}. */
const TOOLING = join(REPO, 'hipster-entity', 'hipster-entity-tooling');

/** The lines worth showing after a pass; the full log stays on disk. */
const REPORT_KEYS = [
  'preflight ok', 'source root', 'Writing generated java', 'HTML entity index',
  'Link check', 'Divergences:', 'Validation:', 'divergences', 'BUILD', 'check:',
];

function usage() {
  console.log(`gen.js — run the entity generator as a side tool (not a build step)

  bun scripts/gen.js                   regenerate, then render the HTML entity index
  bun scripts/gen.js --full            force a full pass
  bun scripts/gen.js --check           verify in a temp tree without writing
  bun scripts/gen.js with-tests        regenerate, then the entity test set
  bun scripts/gen.js watch             regenerate now, then on every save (Ctrl+C stops it)
  bun scripts/gen.js --watch           same as 'watch'; the flag is forwarded to the generator
  bun scripts/gen.js <maven args...>   forwarded to the entity module set

  Watch mode needs an fs-meta daemon serving the project root; the generator never starts a Java watcher.
  Start one with 'fsmeta daemon start' and register the root with 'fsmeta root add <project root>'.

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
 * The same step, but with the child's stdio inherited.
 *
 * A watch session is a long-lived process whose whole value is the log it prints while it runs; capturing that
 * output into a file and showing it only after Ctrl+C would make the loop invisible exactly while it is useful.
 */
function liveStep(command, args, { logPath, env }) {
  log(`\n$ ${command} ${args.join(' ')}\n`, logPath);
  const result = run(command, args, { stdio: 'inherit', env });
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
  const text = existsSync(logPath) ? readFileSync(logPath, 'utf8') : '';
  const lines = text.split('\n');
  for (const line of lines) {
    if (REPORT_KEYS.some((key) => line.includes(key))) {
      console.log(`  ${line.trim()}`);
    }
  }
  console.log(`[gen] full log: ${logPath}`);
  if (status !== 0) {
    console.error(`[gen] FAILED with exit code ${status}.`);
    // The hint belongs to the step that failed. Printing it for every failure is what sent a reader
    // after the tooling version while the log's own preflight said "ok" and the HTML index was the
    // failing step (plan step 6.10). The last `$ …` line is the step that ran last, so it is the one.
    const { command, hint } = failureHint(text);
    if (command) {
      console.error(`[gen] the failing step was: ${command}`);
    } else {
      console.error('[gen] no step logged a command; read the first error in the full log.');
    }
    if (hint) {
      console.error(hint);
    }
  }
  return status;
}

/** The HTML entity index (DEC-027). It is a Bun script, so it runs in this same runtime. */
function htmlIndex(logPath) {
  // "A missing Bun is reported and skipped" used to be a branch here, because the pass could run without Bun.
  // That case is gone: this script *is* Bun, so the renderer's exit status is the only outcome left.
  // A page that renders but cannot verify a link IS fatal: a link to the wrong line is worse than no link.
  return step('bun', ['run', join(REPO, 'scripts', 'entity-html', 'index.js'),
    '--module', EXAMPLE_PATH], { logPath });
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

  const isWatch = argv[0] === 'watch' || argv.includes('--watch');
  const isTests = argv[0] === 'with-tests';
  const isCheck = argv.includes('--check');
  const isFull = argv.includes('--full');
  const modeArgs = argv.filter((arg) => arg === '--check' || arg === '--full' || arg === '--incremental'
    || arg === '--watch');
  const forwarded = argv.filter((arg) => arg !== 'watch' && !modeArgs.includes(arg));

  if (isWatch && isCheck) {
    // Usage, like the Java CLI's own refusal: one writes nothing by definition, the other regenerates in place.
    console.error('[gen] --watch cannot be combined with --check; run the check separately.');
    return 2;
  }
  if (isWatch && isTests) {
    console.error('[gen] with-tests cannot be combined with --watch; run the watch session and the tests separately.');
    return 2;
  }

  const logPath = join(AGENT_STATE, isWatch ? 'watch.log' : 'gen.log');
  writeFileSync(logPath, `[gen] ${new Date().toISOString()}  bun ${Bun.version}\n`);

  // --- forwarded arguments: the gate's shortcut with the caller's goals ---------------------------
  if (!isTests && !isCheck && !isWatch && forwarded.length > 0) {
    const gate = buildGateArgs(['hipster-entity', ...forwarded]);
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
  // Its own classpath file for a watch session, so a one-shot pass can run at the same time without
  // truncating the file a starting watch session is reading.
  const classpathFile = join(AGENT_STATE, isWatch ? 'watch-classpath.txt' : 'gen-classpath.txt');
  rmSync(classpathFile, { force: true });
  const exportStatus = step(maven.command, ['-o', '-pl',
    ':hipster-entity-tooling,:hipster-entity-example',
    '-am', 'compile', 'dependency:build-classpath', `-Dmdep.outputFile=${classpathFile}`], { logPath, env });
  if (exportStatus !== 0) {
    return report(logPath, exportStatus);
  }

  let classpath;
  try {
    classpath = classpathFrom(classpathFile, TOOLING);
  } catch (error) {
    console.error(`[gen] ${error.message}`);
    return report(logPath, 1);
  }

  let status = step(jdk.java, ['-cp', classpath, 'hr.hrg.hipster.entity.tooling.GeneratorPreflight'],
    { logPath, env });
  if (status !== 0) {
    return report(logPath, status);
  }

  // `--validate=STRICT` since plan step 6.3, on the maintainer's decision of 2026-10-08: the example's build gates
  // on the entity CONTRACT rules (a marker that declares domain methods, a field enum out of ledger order, `@View`
  // misapplied) and never on the CONVENTION ones (the view hierarchy naming rule, the Auditable package layout),
  // which are reported as `[advisory]` and cannot fail a pass in any policy. The example is clean of both today, so
  // this changes no output — it makes a future contract violation the build's problem rather than a log line.
  // `--dto-projections` since plan step 6.5: the example demonstrates DEC-003/DEC-007's read-projection
  // pattern, and `PersonDto` carries the `@View(dto = true)` marker. Both halves are required — the flag
  // enables the emitter, the marker selects the view — so this line adds one committed class
  // (`PersonDtoJson`) and leaves every unmarked view exactly as it was.
  // `--mapper` since plan step 6.9: the converter manifest is machinery output and byte-reproducible, so
  // the example needs a mapping that produces pairs. `PersonSummary -> PersonDto` is the projection pair
  // the pattern documents; its committed `.jcodebuddy/conf/converters.json` is what
  // ExampleConverterManifestTest compares against a fresh pass.
  // `--watch` is forwarded to the generator (E11): the fs-meta daemon is the watcher, the CLI owns the initial
  // pass and the loop, and this script no longer starts project-automation's watcher. The first pass keeps the
  // usual `--incremental` default (or `--full` when asked), and every pass after a batch is incremental.
  const generatorArgs = ['-cp', classpath, 'hr.hrg.hipster.entity.tooling.EntityMetadataGenerator',
    SOURCE_ROOT, METADATA_DIR, '--java-out', SOURCE_ROOT, '--packages', PACKAGES, '--validate=STRICT',
    '--dto-projections',
    '--mapper', 'PersonSummary:PersonDto',
    ...(isCheck ? ['--check'] : isFull ? ['--full'] : ['--incremental']),
    ...(isWatch ? ['--watch'] : []),
    '--run-record', join(METADATA_DIR, 'generation.json')];
  status = isWatch
    ? liveStep(jdk.java, generatorArgs, { logPath, env })
    : step(jdk.java, generatorArgs, { logPath, env });
  if (status !== 0) {
    return report(logPath, status);
  }
  if (isCheck || isWatch) {
    // A watch session never returns until Ctrl+C, and it writes metadata on every pass; there is no single
    // "after the pass" moment at which to render the HTML index, exactly as the previous watch mode was.
    return report(logPath, status);
  }

  // An incremental no-op must not re-render the HTML index: the page reads the metadata JSON, which
  // the no-op deliberately left alone. Re-rendering would turn a one-line "no changes" into a full
  // page generation and hide the fact that nothing ran.
  const recordPath = join(METADATA_DIR, 'generation.json');
  if (existsSync(recordPath)) {
    const record = JSON.parse(readFileSync(recordPath, 'utf8'));
    if (record.mode === 'noop') {
      console.log('[gen] generation: no-op; HTML index left unchanged');
      return report(logPath, status);
    }
    if (record.mode) {
      console.log(`[gen] generation: ${record.mode}`
        + (record.emitted != null ? `, emitted ${record.emitted}, reused ${record.reused}` : ''));
    }
  }

  return report(logPath, htmlIndex(logPath));
}

try {
  process.exit(main());
} catch (error) {
  console.error(`[gen] ${error.message}`);
  process.exit(1);
}
