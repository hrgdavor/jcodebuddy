#!/usr/bin/env bun
/**
 * test-affected.js — select and run the unit tests a tree change actually affects
 * (plan [`plans/incremental-test-selection.md`](../plans/incremental-test-selection.md) § 5.1–5.3, step T4).
 *
 * WHAT IT DOES
 *   It reads the disposable `fs-meta` state at
 *   `hipster-entity/hipster-entity-tooling/.jcodebuddy/cache/test-affected/` (`TestRunState`, the trio plus
 *   `run.json`), asks the generated test index (`tests.json`, T1) which unit tests the changed files reach
 *   (`TestSelector`, T3), and runs exactly those through the **existing fast lane**:
 *
 *     bun scripts/mvn-fast.js --fast --tests 'ClassA,ClassB'
 *
 *   It adds no second Maven goal and no second test runner. On a successful run it publishes the new state,
 *   so the next invocation over an unchanged tree is a no-op that runs no Maven and writes nothing. A failed
 *   run leaves the old state, so the same tests (and their dependents) are selected again.
 *
 * WHEN THE INDEX IS MISSING OR STALE
 *   The selector needs the compiled test classpath, and the index is a fact about a compiled classpath. On a
 *   clean tree (or when `TestSelector` reports `missing-index` / `stale-index` / `corrupt-index`) this script
 *   runs `bun scripts/test-index.js` once: `test-compile` plus a classpath export for the tooling module and
 *   its `-am` dependencies, never the whole reactor. `--explain` and `--dry-run` do not regenerate a stale
 *   index; they report the fallback and run nothing.
 *
 * WHAT IT WRITES
 *   Only `TestRunState.publish` writes, and only after Maven exits 0: the `fs-meta` trio and `run.json` under
 *   the module's derived `.jcodebuddy/cache/` subtree. No-op, `--explain` and `--dry-run` write no state.
 *
 * Usage:
 *   bun scripts/test-affected.js                          select and run affected unit tests; no-op when clean
 *   bun scripts/test-affected.js --module hipster-entity-tooling
 *   bun scripts/test-affected.js --all-unit               run every unit test and publish on success
 *   bun scripts/test-affected.js --include-component      also select affected component tests
 *   bun scripts/test-affected.js --explain                print changed file -> FQN -> test chains; run nothing
 *   bun scripts/test-affected.js --dry-run                print the Maven command; run nothing
 *   bun scripts/test-affected.js --json                   machine-readable selection and result (duration on stderr)
 *
 *   Exit codes: 0 no-op / explain / dry-run / every selected test passed; 1 a test failed, a changed test
 *   cannot run through this lane, the state could not be published, or the toolchain failed; 2 a usage error.
 *   The duration is the last line (`scripts/lib/timing.js`); with `--json` it goes to stderr so stdout is only
 *   the JSON object.
 */

import { existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { basename, delimiter, dirname, join, resolve } from 'node:path';

import { envWith, repoRoot, resolveJdk25 } from './lib/toolchain.js';
import { reportDuration, stopwatch } from './lib/timing.js';

const REPO = repoRoot();
const MODULE_GROUP = 'hipster-entity';
const FAST_LANE_MODULE = 'hipster-entity-tooling';
const INDEX_REL = join('.jcodebuddy', 'index', 'tests.json');
const STATE_REL = join('.jcodebuddy', 'cache', 'test-affected');
const CLASSPATH_REL = join('target', 'test-index-classpath.txt');
const SELECTOR = 'hr.hrg.hipster.entity.tooling.index.TestSelector';
const RUN_STATE = 'hr.hrg.hipster.entity.tooling.index.TestRunState';
const TEST_INDEX = join(REPO, 'scripts', 'test-index.js');
const MVN_FAST = join(REPO, 'scripts', 'mvn-fast.js');
/** The selector fallbacks this script can fix by regenerating the index. */
const REFRESHABLE_FALLBACKS = new Set(['missing-index', 'stale-index', 'corrupt-index']);

const USAGE = `test-affected.js — affected unit tests through the fast lane (plan T4)

  bun scripts/test-affected.js                          select and run affected unit tests
  bun scripts/test-affected.js --module hipster-entity-tooling
  bun scripts/test-affected.js --all-unit               run every unit test; publish state on success
  bun scripts/test-affected.js --include-component      also select affected component tests
  bun scripts/test-affected.js --explain                print the chains; run nothing
  bun scripts/test-affected.js --dry-run                print the Maven command; run nothing
  bun scripts/test-affected.js --json                   machine-readable selection and result
  bun scripts/test-affected.js --help                   this text

The run is exactly \`bun scripts/mvn-fast.js --fast --tests 'ClassA,ClassB'\`; there is no second Maven goal.
The state is the disposable fs-meta trio plus run.json under <module>/.jcodebuddy/cache/test-affected/.
No-op, --explain and --dry-run do not write the state. A failed run never publishes it.`;

/** Parses this script's own options; anything else is a usage error. */
function parse(argv) {
  const options = {
    module: null,
    allUnit: false,
    includeComponent: false,
    explain: false,
    dryRun: false,
    json: false,
    help: false
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--all-unit') {
      options.allUnit = true;
    } else if (arg === '--include-component') {
      options.includeComponent = true;
    } else if (arg === '--explain') {
      options.explain = true;
    } else if (arg === '--dry-run') {
      options.dryRun = true;
    } else if (arg === '--json') {
      options.json = true;
    } else if (arg === '--module') {
      const value = argv[++i];
      if (value === undefined) {
        return { error: '--module needs a value' };
      }
      options.module = value;
    } else if (arg.startsWith('--module=')) {
      options.module = arg.slice('--module='.length);
    } else {
      return { error: `unknown argument '${arg}'` };
    }
  }
  return { options };
}

/** The module the cwd is in when it carries a generated index, else the tooling default (plan § 5.3). */
function defaultModule() {
  let directory = resolve(process.cwd());
  for (;;) {
    if (existsSync(join(directory, 'pom.xml')) && existsSync(join(directory, INDEX_REL))) {
      return directory;
    }
    const parent = dirname(directory);
    if (parent === directory) {
      break;
    }
    directory = parent;
  }
  return join(REPO, MODULE_GROUP, FAST_LANE_MODULE);
}

/** Resolves `--module` as a path or as a directory name under `hipster-entity/`; null when nothing matches. */
function resolveModule(name) {
  if (!name) {
    return defaultModule();
  }
  for (const candidate of [name, join(REPO, name), join(REPO, MODULE_GROUP, name)]) {
    const directory = resolve(candidate);
    if (existsSync(join(directory, 'pom.xml'))) {
      return directory;
    }
  }
  return null;
}

/** The compiled test classpath, or null when the Java helpers cannot run yet. */
function compiledClasspath(moduleDir) {
  const classpathFile = join(moduleDir, CLASSPATH_REL);
  const testClasses = join(moduleDir, 'target', 'test-classes');
  const classes = join(moduleDir, 'target', 'classes');
  if (!existsSync(classpathFile) || !existsSync(testClasses) || !existsSync(classes)) {
    return null;
  }
  for (const helper of [SELECTOR, RUN_STATE]) {
    if (!existsSync(join(testClasses, `${helper.replace(/\./g, '/')}.class`))) {
      return null;
    }
  }
  const exported = readFileSync(classpathFile, 'utf8').trim();
  if (!exported) {
    return null;
  }
  return [testClasses, classes, exported].join(delimiter);
}

/**
 * Runs `bun scripts/test-index.js` when the index or the compiled classpath is missing. The index is the
 * selector's input and the classpath is how the selector is executed; both are one `test-compile` plus a
 * classpath export of the tooling module and its `-am` dependencies, never the whole reactor.
 */
function ensureTooling(moduleDir, indexFile, log) {
  const existing = compiledClasspath(moduleDir);
  if (existing && existsSync(indexFile)) {
    return { classpath: existing, rebuilt: false, status: 0 };
  }
  if (log) {
    console.error(
      `[test-affected] ${existsSync(indexFile) ? 'the compiled test classpath is missing' : 'the test index is missing'};` +
        ' running bun scripts/test-index.js (test-compile + classpath export)'
    );
  }
  const result = spawnSync(process.execPath, [TEST_INDEX], { cwd: REPO, stdio: 'inherit' });
  const status = result.status ?? 1;
  const classpath = compiledClasspath(moduleDir);
  if (!classpath) {
    throw new Error(
      `the compiled test classpath is still missing after bun scripts/test-index.js (exit ${status}); ` +
        `run it by hand to see why: bun scripts/test-index.js`
    );
  }
  if (status !== 0) {
    console.error(
      `[test-affected] warning: bun scripts/test-index.js exited ${status}; the index may be stale, ` +
        'so the selector may fall back to every unit test'
    );
  }
  return { classpath, rebuilt: true, status };
}

/** Runs a Java helper with the module's compiled test classpath and returns its stdout. */
function javaHelper(classpath, className, args) {
  const jdk = resolveJdk25();
  const result = spawnSync(jdk.java, ['-cp', classpath, className, ...args], {
    cwd: REPO,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    windowsHide: true,
    env: envWith(jdk)
  });
  if (result.error) {
    throw new Error(`could not run ${className}: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const detail = (result.stderr || '').trim() || '(no stderr)';
    throw new Error(`${className} failed (exit ${result.status}): ${detail}`);
  }
  return result.stdout;
}

/** The TestRunState decision for the current tree. */
function decide(classpath, moduleDir, indexFile) {
  const output = javaHelper(classpath, RUN_STATE, [
    '--root', REPO,
    '--module-dir', moduleDir,
    '--index', indexFile,
    '--json'
  ]);
  try {
    return JSON.parse(output);
  } catch (failure) {
    throw new Error(`TestRunState did not print valid JSON: ${failure.message}\n${output}`);
  }
}

/** The TestSelector result, as JSON (or as the human explain text when `--explain` asked for chains). */
function selectTests(classpath, moduleDir, indexFile, decision, options) {
  const humanExplain = options.explain && !options.json;
  const args = ['--index', indexFile, '--root', REPO, '--module-dir', moduleDir];
  if (options.allUnit || decision.mode === 'full') {
    args.push('--all-unit');
  } else {
    args.push('--changed', (decision.changed ?? []).join(','));
  }
  if (options.includeComponent) {
    args.push('--include-component');
  }
  args.push(humanExplain ? '--explain' : '--json');
  const output = javaHelper(classpath, SELECTOR, args);
  if (humanExplain) {
    return { text: output };
  }
  try {
    return JSON.parse(output);
  } catch (failure) {
    throw new Error(`TestSelector did not print valid JSON: ${failure.message}\n${output}`);
  }
}

/** Regenerates the test index; returns the exit status. */
function regenerateIndex() {
  const result = spawnSync(process.execPath, [TEST_INDEX], { cwd: REPO, stdio: 'inherit' });
  return result.status ?? 1;
}

/** The exact Maven invocation `mvn-fast --fast` builds for a selected class list, for display. */
function mavenCommand(selected) {
  const tests = selected.join(',');
  return {
    wrapper: `bun scripts/mvn-fast.js --fast --tests '${tests}'`,
    args: [
      '-o',
      '-pl',
      `:${FAST_LANE_MODULE}`,
      '-am',
      '-Dmaven.build.cache.skipSave=true',
      '-Pfast-tests',
      '-T',
      '1C',
      'test',
      `-Dtest=${tests}`,
      '-Dsurefire.failIfNoSpecifiedTests=false'
    ]
  };
}

/** Runs the selected tests through the existing fast lane; returns its exit status. */
function runFastLane(selected) {
  const result = spawnSync(process.execPath, [MVN_FAST, '--fast', '--tests', selected.join(',')], {
    cwd: REPO,
    stdio: 'inherit',
    env: process.env
  });
  return result.status ?? 1;
}

/** Publishes the successful run's selection; returns the parsed TestRunState publish result. */
function publish(classpath, moduleDir, indexFile, decision, selected) {
  const args = ['--root', REPO, '--module-dir', moduleDir, '--index', indexFile, '--publish', '--json'];
  if (decision.indexChecksum) {
    args.push('--expect-index-checksum', decision.indexChecksum);
  }
  if (selected.length > 0) {
    args.push('--selected', selected.join(','));
  }
  const output = javaHelper(classpath, RUN_STATE, args);
  return JSON.parse(output);
}

/** Prints the state decision, without pretending it has a selection. */
function printDecision(decision) {
  console.log(`test-affected: decision ${decision.mode} - ${decision.reason}`);
  for (const changed of decision.changed ?? []) {
    console.log(`  changed:  ${changed}`);
  }
}

/** Prints the selected tests and the reason they were selected, in the human format. */
function printSelection(selection, decision) {
  console.log(`test-affected: ${selection.module || FAST_LANE_MODULE}`);
  console.log(`  decision: ${decision.mode} - ${decision.reason}`);
  for (const changed of decision.changed ?? []) {
    console.log(`  changed:  ${changed}`);
  }
  const selected = selection.selected ?? [];
  const notRunnable = selection.notRunnable ?? [];
  console.log(`  selected: ${selected.length} runnable test(s)`);
  for (const fqn of selected) {
    console.log(`    ${fqn}`);
  }
  if (notRunnable.length > 0) {
    console.log(`  affected but NOT RUN by this lane (${notRunnable.length}):`);
    for (const fqn of notRunnable) {
      console.log(`    ${fqn}`);
    }
    console.log('  use bun scripts/mvn-fast.js --fast (or the gate) for those');
  }
  if (selection.fallback) {
    console.log(`  fallback: ${selection.fallback.kind} - ${selection.fallback.detail}`);
  }
  for (const note of selection.notes ?? []) {
    console.log(`  note:     ${note}`);
  }
}

/** The machine-readable result (plan § 5.3). With --json, this is all stdout holds. */
function result(moduleDir, status, decision, selection, command, options, ran, exitCode, published, durationMs) {
  return {
    format: 1,
    module: basename(moduleDir),
    status,
    decision,
    selection,
    command: command ? command.wrapper : null,
    mavenCommand: command ? command.args : null,
    ran,
    exitCode,
    published,
    durationMs,
    allUnit: options.allUnit,
    includeComponent: options.includeComponent
  };
}

function main() {
  const elapsed = stopwatch();
  const { options, error } = parse(process.argv.slice(2));
  if (error) {
    console.error(`test-affected: ${error}`);
    console.error(USAGE);
    return 2;
  }
  if (options.help) {
    console.log(USAGE);
    return 0;
  }

  const moduleDir = resolveModule(options.module);
  if (!moduleDir) {
    console.error(`test-affected: no module '${options.module}' (looked for a pom.xml as a path, under the` +
      ` repository root, and under ${MODULE_GROUP}/)`);
    return 2;
  }
  const moduleName = basename(moduleDir);
  if (moduleName !== FAST_LANE_MODULE) {
    console.error(`test-affected: ${moduleName} is not the fast lane's module (${FAST_LANE_MODULE}); ` +
      'T4 runs the existing fast lane and does not add a second Maven path');
    return 2;
  }
  const indexFile = join(moduleDir, INDEX_REL);

  let classpath;
  try {
    ({ classpath } = ensureTooling(moduleDir, indexFile, true));
  } catch (failure) {
    console.error(`test-affected: ${failure.message}`);
    console.error(reportDuration(elapsed(), { what: 'affected tests selected' }));
    return 1;
  }

  try {
    let decision = decide(classpath, moduleDir, indexFile);

    // A no-op needs no selection: print nothing-run, write nothing, start no Maven.
    if (decision.mode === 'noop' && !options.allUnit) {
      if (options.json) {
        const payload = result(moduleDir, 'no-op', decision, null, null, options, false, 0, false,
          Math.round(elapsed()));
        process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
        console.error(reportDuration(elapsed(), { what: 'affected tests selected' }));
      } else {
        console.log(`test-affected: no-op - ${decision.reason}`);
        console.log('nothing to run');
        console.log(reportDuration(elapsed(), { what: 'affected tests selected' }));
      }
      return 0;
    }

    if (options.explain && !options.json) {
      const selection = selectTests(classpath, moduleDir, indexFile, decision, options);
      printDecision(decision);
      process.stdout.write(selection.text);
      console.log(reportDuration(elapsed(), { what: 'affected tests explained' }));
      return 0;
    }

    let selection = selectTests(classpath, moduleDir, indexFile, decision, options);

    // A stale/missing/corrupt index is regenerable, and the plan allows the one test-compile that fixes it
    // (plan T4). --dry-run never does it: the point of a dry run is to show the fallback it would take.
    if (!options.dryRun && selection.fallback && REFRESHABLE_FALLBACKS.has(selection.fallback.kind)) {
      console.error(`[test-affected] ${selection.fallback.kind}: ${selection.fallback.detail}`);
      console.error('[test-affected] regenerating the test index (bun scripts/test-index.js)');
      const status = regenerateIndex();
      if (status === 0) {
        decision = decide(classpath, moduleDir, indexFile);
        selection = selectTests(classpath, moduleDir, indexFile, decision, options);
      } else {
        console.error(`[test-affected] index regeneration failed (exit ${status}); using every unit test`);
        decision = { ...decision, mode: 'full', reason: `${decision.reason}; the index could not be rebuilt` };
        selection = selectTests(classpath, moduleDir, indexFile, decision, options);
      }
    }

    const selected = selection.selected ?? [];
    const notRunnable = selection.notRunnable ?? [];
    const command = selected.length > 0 ? mavenCommand(selected) : null;

    if (options.dryRun) {
      if (options.json) {
        const payload = result(moduleDir, 'dry-run', decision, selection, command, options, false, 0, false,
          Math.round(elapsed()));
        process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
        console.error(reportDuration(elapsed(), { what: 'affected tests selected', files: selected.length }));
      } else {
        printSelection(selection, decision);
        if (command) {
          console.log(`  would run: ${command.wrapper}`);
          console.log(`  maven:     ${command.args.join(' ')}`);
        } else {
          console.log('  would run: nothing (no affected unit test)');
        }
        if (notRunnable.length > 0) {
          console.error('[test-affected] the affected lane cannot run the non-unit test(s) above');
        }
        console.log(reportDuration(elapsed(), { what: 'affected tests selected', files: selected.length }));
      }
      return 0;
    }

    if (selected.length === 0) {
      if (notRunnable.length > 0) {
        if (options.json) {
          const payload = result(moduleDir, 'blocked', decision, selection, null, options, false, 1, false,
            Math.round(elapsed()));
          process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
          console.error(reportDuration(elapsed(), { what: 'affected tests selected' }));
        } else {
          printSelection(selection, decision);
          console.error('[test-affected] no unit test to run, and the affected lane cannot run the non-unit test(s) above');
          console.log(reportDuration(elapsed(), { what: 'affected tests selected' }));
        }
        return 1;
      }

      // Nothing is affected. Publish the new tree so the next invocation is a no-op, but report that no
      // test process started: this is not a "no-op" (the tree did change), it is an empty selection.
      let published = false;
      try {
        publish(classpath, moduleDir, indexFile, decision, []);
        published = true;
      } catch (failure) {
        console.error(`[test-affected] the tests passed but publishing the state failed: ${failure.message}`);
        if (options.json) {
          const payload = result(moduleDir, 'publish-failed', decision, selection, null, options, false, 1,
            false, Math.round(elapsed()));
          process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
          console.error(reportDuration(elapsed(), { what: 'affected tests selected' }));
        } else {
          console.log(reportDuration(elapsed(), { what: 'affected tests selected' }));
        }
        return 1;
      }
      if (options.json) {
        const payload = result(moduleDir, 'nothing-selected', decision, selection, null, options, false, 0,
          published, Math.round(elapsed()));
        process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
        console.error(reportDuration(elapsed(), { what: 'affected tests selected' }));
      } else {
        printSelection(selection, decision);
        console.log('nothing to run (0 affected unit tests)');
        console.log(reportDuration(elapsed(), { what: 'affected tests selected' }));
      }
      return 0;
    }

    if (!options.json) {
      printSelection(selection, decision);
      console.log(`  running:  ${command.wrapper}`);
    }

    const exitCode = runFastLane(selected);
    if (exitCode !== 0) {
      console.error(`test-affected: tests FAILED (exit ${exitCode}); the previous state was left in place`);
      if (options.json) {
        const payload = result(moduleDir, 'failed', decision, selection, command, options, true, exitCode,
          false, Math.round(elapsed()));
        process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
        console.error(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
      } else {
        console.log(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
      }
      return exitCode;
    }

    // A non-unit test changed. Its runnable dependents passed, but this lane did not validate the changed
    // test, so the state must not advance: the next run has to select it again (plan § 5.2 rule 8).
    if (notRunnable.length > 0) {
      console.error('[test-affected] the selected unit tests passed, but the affected lane cannot run:');
      for (const fqn of notRunnable) {
        console.error(`  ${fqn}`);
      }
      console.error('[test-affected] state NOT published; use bun scripts/mvn-fast.js --fast (or the gate)');
      if (options.json) {
        const payload = result(moduleDir, 'partial', decision, selection, command, options, true, 1, false,
          Math.round(elapsed()));
        process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
        console.error(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
      } else {
        console.log(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
      }
      return 1;
    }

    let publishResult;
    try {
      publishResult = publish(classpath, moduleDir, indexFile, decision, selected);
    } catch (failure) {
      console.error(`test-affected: the tests passed but publishing the state failed: ${failure.message}`);
      console.error('test-affected: the previous state was left in place, so the tests will be selected again');
      if (options.json) {
        const payload = result(moduleDir, 'publish-failed', decision, selection, command, options, true, 1,
          false, Math.round(elapsed()));
        process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
        console.error(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
      } else {
        console.log(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
      }
      return 1;
    }

    if (options.json) {
      const payload = result(moduleDir, 'passed', decision, selection, command, options, true, 0,
        Boolean(publishResult), Math.round(elapsed()));
      process.stdout.write(`${JSON.stringify(payload, null, 2)}\n`);
      console.error(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
    } else {
      console.log(`test-affected: published ${selected.length} selected test(s) to ${STATE_REL.split('\\').join('/')}`);
      console.log(reportDuration(elapsed(), { what: 'affected tests run', files: selected.length }));
    }
    return 0;
  } catch (failure) {
    console.error(`test-affected: ${failure.message}`);
    console.error(reportDuration(elapsed(), { what: 'affected tests run' }));
    return 1;
  }
}

process.exit(main());
