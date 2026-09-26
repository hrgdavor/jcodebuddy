#!/usr/bin/env bun
/**
 * run-demo.js — run the hipster-entity end-to-end demo (plan.dsflash.md 9/4.2).
 *
 * This replaces `scripts/run-demo.cmd`. It builds the modules the demo needs (offline, JDK 25) and then runs
 * `hr.hrg.hipster.entityexample.person.PersonDemo`, which walks:
 *
 *   row array -> read view -> JSON -> tracking builder -> changed fields
 *             -> change-set JSON -> the changed columns a partial write would touch
 *
 * The classpath is derived from the module output directories plus the two Jackson artifacts the root POM pins. It
 * is deliberately explicit rather than a repository glob: the local repository holds many Jackson versions, and
 * mixing them produces a `NoSuchFieldError` at runtime. Building the path here also fixes what the `.cmd` could
 * not: the separator is `path.delimiter`, so the same script runs on POSIX.
 *
 * Usage:
 *   bun scripts/run-demo.js              build, then run the demo
 *   bun scripts/run-demo.js --no-build   run it against whatever is already compiled
 */

import { existsSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { buildGateArgs } from './lib/gate.js';
import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

const REPO = repoRoot();
const DEMO_CLASS = 'hr.hrg.hipster.entityexample.person.PersonDemo';

/** The Jackson artifacts the root POM pins; the demo cannot run without exactly these. */
const JACKSON = [
  ['tools', 'jackson', 'core', 'jackson-core', '3.2.1', 'jackson-core-3.2.1.jar'],
  ['tools', 'jackson', 'core', 'jackson-databind', '3.2.1', 'jackson-databind-3.2.1.jar'],
  ['com', 'fasterxml', 'jackson', 'core', 'jackson-annotations', '2.22', 'jackson-annotations-2.22.jar'],
];

/** The module output directories, in the order the demo needs them. */
const MODULES = ['hipster-entity-example', 'hipster-entity-jackson', 'hipster-entity-core', 'hipster-entity-api'];

/**
 * Finds a pinned artifact in the local repository.
 *
 * Two layouts have to be tolerated, because both are real on developers' machines: Maven's own
 * `~/.m2/repository`, and the `~/.m2/repository` a wrapper or an IDE has populated under a different home. A
 * missing jar is reported with the path it looked for rather than surfacing as a `NoClassDefFoundError`.
 */
function findJar(parts) {
  const roots = [join(homedir(), '.m2', 'repository')];
  if (process.env.M2_REPO) {
    roots.unshift(process.env.M2_REPO);
  }
  const relative = parts.slice(0, -1);
  const file = parts[parts.length - 1];
  for (const root of roots) {
    const candidate = join(root, ...relative, file);
    if (existsSync(candidate)) {
      return candidate;
    }
    // A version directory that is not the one pinned is a legitimate reason to look: report the directory listing
    // so the reader sees which versions are actually installed.
    const directory = join(root, ...relative);
    if (existsSync(directory)) {
      const versions = readdirSync(directory).filter((entry) => entry !== file);
      if (versions.length > 0) {
        throw new Error(`${file} is not in ${directory}; installed: ${versions.join(', ')}. `
          + 'Install the pinned version or update the list in scripts/run-demo.js.');
      }
    }
  }
  throw new Error(`could not find ${file} in ${roots.join(' or ')}`);
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) {
    console.log(`run-demo.js — build (offline, JDK 25) and run ${DEMO_CLASS}

  bun scripts/run-demo.js              build, then run the demo
  bun scripts/run-demo.js --no-build   run against what is already compiled`);
    return 0;
  }
  const noBuild = argv.includes('--no-build');

  const jdk = resolveJdk25();
  const env = envWith(jdk);

  if (!noBuild) {
    const maven = resolveMaven();
    // The property is spelled with an explicit `=true`: an unquoted `-DskipTests` is a single token with no `=`,
    // which the gate refuses because that is also what a property split by a shell looks like.
    const gate = buildGateArgs(['hipster-entity', '-DskipTests=true', 'package']);
    if (gate.error) {
      console.error(`[run-demo] ERROR: ${gate.error}`);
      return 2;
    }
    const build = run(maven.command, gate.args, { stdio: 'inherit', env });
    if (build.status !== 0) {
      console.error('[run-demo] ERROR: the build failed; the demo was not run.');
      return 1;
    }
  }

  const parts = [];
  for (const module of MODULES) {
    const classes = join(REPO, module, 'target', 'classes');
    if (!existsSync(classes)) {
      console.error(`[run-demo] ERROR: no compiled classes at ${classes}`);
      console.error('[run-demo]   Build first (bun scripts/run-demo.js without --no-build).');
      return 1;
    }
    parts.push(classes);
  }
  try {
    for (const jar of JACKSON) {
      parts.push(findJar(jar));
    }
  } catch (error) {
    console.error(`[run-demo] ERROR: ${error.message}`);
    return 1;
  }

  const result = run(jdk.java, ['-cp', parts.join(delimiter), DEMO_CLASS], { stdio: 'inherit', env });
  return result.status;
}

process.exit(main());
