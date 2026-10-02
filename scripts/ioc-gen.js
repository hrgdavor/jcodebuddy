#!/usr/bin/env bun
/**
 * ioc-gen.js — run the hipster-ioc context generator (DEC-036) as a side tool, not a build step.
 *
 * WHY THIS IS NOT A MAVEN BUILD STEP
 *   JCodeBuddy uses no annotation processing and no compile hooks. Nothing in `hipster-ioc-tooling` is bound to
 *   a lifecycle phase, so `mvn compile`, `mvn package` and `mvn test` only ever compile the generated source
 *   already committed under `src/main/java`. This script is how a pass is actually run.
 *
 * WHY IT DOES NOT USE `mvn exec:java`
 *   Two independent reasons, both measured in this repository:
 *     1. A direct goal invocation (`mvn exec:java@id`) runs on EVERY module in the reactor and fails on the
 *        parent and on every module without such an execution; Maven has no per-module selector.
 *     2. `exec:java` resolves a `provided` tooling dependency from the local repository instead of the reactor,
 *        so it silently runs whatever jar is installed in `~/.m2` — which is how a change made in this
 *        checkout appears to have no effect.
 *   The answer is `dependency:build-classpath` with `-am`, which maps a reactor dependency to that module's
 *   `target/classes`. That is what happens below.
 *
 * WHY JDK 25 IS PINNED
 *   The reactor compiles at release 25, so every class file under `target/` is Java 25 bytecode (version 69).
 *   A JVM from an older JDK cannot read them, and the failure looks like a code problem rather than a
 *   toolchain one. `resolveJdk25` fails with an actionable message instead.
 *
 * Usage:
 *   bun scripts/ioc-gen.js                         generate for hipster-ioc-test
 *   bun scripts/ioc-gen.js --root <dir>            generate for another source root
 *   bun scripts/ioc-gen.js --indent "  "           use two-space indentation
 *   bun scripts/ioc-gen.js --quiet                 print only the divergences
 *
 * Exit code: non-zero when any context was refused (a cycle, a lazy bean with no factory, or a context that
 * names its own implementation), so a check can depend on it. Report-only divergences exit 0.
 *
 * Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

const REPO = repoRoot();
const MODULE = join(REPO, 'hipster-ioc-tooling');
const DEFAULT_ROOT = join(REPO, 'hipster-ioc-test', 'src', 'test', 'java');
const CLASSPATH_FILE = join(MODULE, 'target', 'ioc-classpath.txt');

function usage() {
  console.log(`ioc-gen.js — generate hipster-ioc context implementations (DEC-036)

  bun scripts/ioc-gen.js                        generate for hipster-ioc/hipster-ioc-test/src/test/java
  bun scripts/ioc-gen.js --root <dir>           generate for another source root
  bun scripts/ioc-gen.js --indent "  "          one indentation step for generated code
  bun scripts/ioc-gen.js --quiet                print only the divergences
  bun scripts/ioc-gen.js --help                 this text

The generator reads @HipsterContext interfaces, writes <Context>Impl beside each one, and writes the
dependency graph to the module's .jcodebuddy/metadata/hipster-ioc/contexts.json.`);
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) {
    usage();
    return 0;
  }

  const jdk = resolveJdk25();
  const maven = resolveMaven();
  const env = envWith(jdk);
  const root = argValue(argv, '--root') ?? DEFAULT_ROOT;
  if (!existsSync(root)) {
    console.error(`ioc-gen: no such source root: ${root}`);
    return 2;
  }

  // Compile the module and its reactor dependencies, and export the classpath in the same invocation: the
  // export maps reactor modules to target/classes only while they are in this reactor run.
  console.log(`ioc-gen: compiling with ${jdk.version} …`);
  const build = run(maven.command, [
    '-o', '-q', '-pl', 'hipster-ioc-tooling', '-am', '-DskipTests',
    'test-compile', 'dependency:build-classpath', `-Dmdep.outputFile=${CLASSPATH_FILE}`,
  ], { stdio: 'inherit', env, cwd: REPO });
  if (build.error || build.status !== 0) {
    console.error(`ioc-gen: the compile/classpath step failed (${build.error?.message ?? build.status})`);
    return build.status ?? 1;
  }

  const exported = readFileSync(CLASSPATH_FILE, 'utf8').trim();
  const classes = join(MODULE, 'target', 'classes');
  if (!exported || !existsSync(classes)) {
    console.error(`ioc-gen: no usable classpath (file: ${CLASSPATH_FILE}, classes: ${classes})`);
    return 1;
  }

  const passthrough = [];
  const indent = argValue(argv, '--indent');
  if (indent !== undefined) passthrough.push('--indent', indent);
  if (argv.includes('--quiet')) passthrough.push('--quiet');

  return run(jdk.java, [
    '-cp', `${classes}${delimiter}${exported}`,
    'hr.hrg.hipster.ioc.tooling.IocTool', '--root', root, ...passthrough,
  ], { stdio: 'inherit', env, cwd: REPO }).status ?? 1;
}

/** The value after {@code flag}, or undefined. */
function argValue(argv, flag) {
  const index = argv.indexOf(flag);
  return index >= 0 && index + 1 < argv.length ? argv[index + 1] : undefined;
}

process.exit(main());
