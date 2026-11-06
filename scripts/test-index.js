#!/usr/bin/env bun
/**
 * test-index.js — generate the test dependency index for `hipster-entity-tooling` (plan T1).
 *
 * WHAT IT DOES
 *   `test-compile` the module and the reactor modules it needs, export the module's **test** classpath
 *   from Maven, and run `TestIndexGenerator` over it. The generator writes
 *   `hipster-entity/hipster-entity-tooling/.jcodebuddy/index/tests.json`: for every test class, the
 *   production classes it names, the test-support classes it names, its transitive production closure and
 *   its `@Tag`s; for every project production class on the classpath, its file, its module and its direct
 *   project dependencies (plan § 4).
 *
 * WHAT IT DOES NOT DO
 *   It never runs a test: the Maven goal is `test-compile`, and the test runner is a different lane
 *   (`scripts/mvn-fast.js` / `test-affected`, T4). It never builds the whole reactor: `-pl <module> -am`
 *   is the recorded tooling closure, not the 18-module gate. It never writes a Maven cache entry for the
 *   narrowed compile (`-Dmaven.build.cache.skipSave=true`), because a `test-compile` cache entry is not a
 *   test run and a later full build must not restore it as one (doc/AGENTS.md).
 *
 * WHY THE CLASSPATH COMES FROM MAVEN
 *   The plan's rule is "scan the test classpath's project target/classes and target/test-classes
 *   directories", and Maven's own answer for "use the reactor's classes without install" is
 *   `dependency:build-classpath` with `-am`: it maps a reactor dependency to that module's
 *   `target/classes`. The module's own two output directories are prepended, because
 *   `dependency:build-classpath` lists a module's dependencies, not its own output. The generator resolves
 *   a workspace jar back to its sibling `target/classes` as well, so a surefire-shaped classpath (which
 *   names packaged reactor dependencies as jars) indexes the same project sources.
 *
 * Usage:
 *   bun scripts/test-index.js          generate the table for hipster-entity-tooling
 *
 * Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN.
 *
 * Exit code: 0 the table was written; non-zero the compile, the classpath export or the generator failed.
 * The duration is the last line (scripts/lib/timing.js).
 */

import { existsSync, readFileSync, rmSync } from 'node:fs';
import { join, relative } from 'node:path';

import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';
import { reportDuration, stopwatch } from './lib/timing.js';

const REPO = repoRoot();
const MODULE = 'hipster-entity/hipster-entity-tooling';
const MODULE_DIR = join(REPO, MODULE);
const CLASSPATH_FILE = join(MODULE_DIR, 'target', 'test-index-classpath.txt');
const GENERATOR = 'hr.hrg.hipster.entity.tooling.index.TestIndexGenerator';
const INDEX_FILE = join(MODULE_DIR, '.jcodebuddy', 'index', 'tests.json');

const USAGE = `test-index.js — the generated test dependency index for hipster-entity-tooling (T1)

  bun scripts/test-index.js          test-compile the module, export its test classpath, and write
                                     hipster-entity/hipster-entity-tooling/.jcodebuddy/index/tests.json
  bun scripts/test-index.js --help   this text

It runs no tests and builds only the module and the reactor modules it needs (-pl <module> -am).
The index is derived and regenerable; see plans/incremental-test-selection.md § 4.`;

/** Runs one command with the selected JDK and returns its status; a spawn failure is status 1. */
function step(label, command, args, env) {
  const result = run(command, args, { stdio: 'inherit', env, cwd: REPO });
  if (result.error) {
    console.error(`test-index: could not run ${label}: ${result.error.message}`);
    return 1;
  }
  return result.status ?? 1;
}

function main() {
  const elapsed = stopwatch();
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) {
    console.log(USAGE);
    return 0;
  }
  if (argv.length > 0) {
    console.error(`test-index: unknown argument '${argv[0]}'`);
    console.error(USAGE);
    return 2;
  }

  const jdk = resolveJdk25();
  const maven = resolveMaven();
  const env = envWith(jdk);

  // A stale classpath file is deleted first: it could otherwise be read after a failed export and the
  // generator would index a previous build's modules.
  rmSync(CLASSPATH_FILE, { force: true });

  // `-DskipTests=true` skips the test runner, not test compilation: testCompile still runs, which is the
  // one phase this step needs. `-am` builds only this module's reactor dependencies.
  const build = step('the compile/classpath step', maven.command, [
    '-o', '-q', '-pl', MODULE, '-am',
    '-DskipTests=true',
    '-Dmaven.build.cache.skipSave=true',
    'test-compile', 'dependency:build-classpath',
    `-Dmdep.outputFile=${CLASSPATH_FILE}`,
    '-Dmdep.includeScope=test',
  ], env);
  if (build !== 0) {
    return build;
  }

  const testClasses = join(MODULE_DIR, 'target', 'test-classes');
  const classes = join(MODULE_DIR, 'target', 'classes');
  const exported = existsSync(CLASSPATH_FILE) ? readFileSync(CLASSPATH_FILE, 'utf8').trim() : '';
  const missing = [];
  if (!exported) missing.push(`the exported classpath at ${relative(REPO, CLASSPATH_FILE)}`);
  if (!existsSync(testClasses)) missing.push(relative(REPO, testClasses));
  if (!existsSync(classes)) missing.push(relative(REPO, classes));
  if (missing.length > 0) {
    console.error(`test-index: missing ${missing.join(', ')}; the compile/export step did not produce it`);
    return 1;
  }

  const classpath = [testClasses, classes, exported].join(delimiter);
  const generated = step('the test index generator', jdk.java, [
    '-cp', classpath,
    GENERATOR,
    '--repo-root', REPO,
    '--module-dir', MODULE_DIR,
  ], env);
  if (generated !== 0) {
    return generated;
  }

  if (!existsSync(INDEX_FILE)) {
    console.error(`test-index: the generator succeeded but ${relative(REPO, INDEX_FILE)} was not written`);
    return 1;
  }
  console.log(`test-index: wrote ${relative(REPO, INDEX_FILE).split('\\').join('/')}`);
  console.log(reportDuration(elapsed(), { what: 'test index generated' }));
  return 0;
}

process.exit(main());
