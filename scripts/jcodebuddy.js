#!/usr/bin/env bun
/**
 * `jcodebuddy` — the command line DEC-W008 names, whose one command today is
 * `jcodebuddy metadata parse <file>` (plan step 7.7).
 *
 *   bun scripts/jcodebuddy.js metadata parse src/main/java/demo/Person.java
 *
 * It prints the file's metadata as JSON on stdout and exits 0; a file it cannot read exits 2 with the reason on
 * stderr (the CLI prints nothing on stdout in that case, so a caller piping the output never mistakes an error for
 * metadata).
 *
 * WHY THIS IS A BUN SCRIPT AND NOT `mvn exec:java`, and why it needs no `.cmd`: rule AGENTS.md § 2 wants the script
 * JavaScript and the build step Maven, and `scripts/gen.js` already records the two measured reasons `exec:java` is
 * the wrong shape here — a direct goal invocation runs on EVERY module in the reactor, and `exec:java` resolves a
 * `provided` tooling dependency from the local repository instead of the reactor. Maven's own answer for "use the
 * reactor's classes without installing" is `dependency:build-classpath`, which is what this does: compile the module
 * (and its dependencies) with the pinned JDK 25, export the classpath once into `.tmp/`, and run the class with
 * `java -cp <module classes><delimiter><exported dependencies>`.
 *
 * The JDK is resolved through `scripts/lib/toolchain.js`, so this launcher inherits the same "always run the
 * candidate before accepting it" rule the gate uses, and reports a wrong JDK as a version problem rather than as a
 * wall of class-file errors.
 */
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js'

const REPO = repoRoot()
const MODULE = 'project-automation'
const MODULE_DIR = join(REPO, MODULE)
const MAIN_CLASS = 'hr.hrg.jcodebuddy.automation.cli.MetadataCli'
const CLASSPATH_FILE = join(REPO, '.tmp', 'jcodebuddy-classpath.txt')

/** What this launcher knows how to run. A command nobody implements is a usage error, not a silent success. */
const COMMANDS = ['metadata parse']

function usage(stream) {
  stream.write(`usage: jcodebuddy <command> [args]

Commands:
  metadata parse <file>   print the file's metadata as JSON (DEC-W008's no-cache path)

Run from a checkout: this launcher compiles the module with JDK 25 and runs it from target/classes. It needs no
daemon, no cache folder and no prior scan, and it writes nothing but the JSON on stdout.

Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN (as everywhere in this repository).
`)
}

const args = process.argv.slice(2)
const command = args.length >= 2 ? `${args[0]} ${args[1]}` : ''
if (args.length === 0 || args[0] === '--help' || args[0] === '-h') {
  usage(process.stdout)
  process.exit(args.length === 0 ? 2 : 0)
}
if (!COMMANDS.includes(command)) {
  process.stderr.write(`unknown command: ${args.join(' ')}\n`)
  usage(process.stderr)
  process.exit(2)
}

const jdk = resolveJdk25()
const mvn = resolveMaven()
const environment = envWith(jdk)

function runOrExit(label, mavenArgs, options = {}) {
  const result = run(mvn.command, mavenArgs, { cwd: REPO, env: environment, ...options })
  if (result.status !== 0) {
    process.stderr.write(`[jcodebuddy] ${label} failed with exit code ${result.status}\n`)
    process.exit(result.status ?? 1)
  }
  return result
}

// 1. Compile the module and everything it needs, and export the classpath in the same invocation.
//
//    ONE command, exactly as `scripts/gen.js` does it, and the shape matters: `-pl <module> -am` builds the closure and
//    runs the goals on the selected module LAST, so the classpath file ends up holding the selected module's own
//    dependencies. Exporting in a separate `-pl` invocation without `-am` is what fails, because the module's sibling
//    snapshots are not installed in the local repository and Maven cannot resolve them.
//
//    `-o` is deliberately NOT passed: in a warm checkout nothing is downloaded anyway, and in a fresh one offline mode
//    would fail on the first missing artifact instead of fetching it.
mkdirSync(join(REPO, '.tmp'), { recursive: true })
rmSync(CLASSPATH_FILE, { force: true })
runOrExit(`compile ${MODULE} and export its classpath`, [
  '-q', '-pl', MODULE, '-am', 'compile', 'dependency:build-classpath', `-Dmdep.outputFile=${CLASSPATH_FILE}`,
])
if (!existsSync(CLASSPATH_FILE)) {
  process.stderr.write(`[jcodebuddy] Maven did not write a classpath to ${CLASSPATH_FILE}\n`)
  process.exit(1)
}
const exported = readFileSync(CLASSPATH_FILE, 'utf8').trim()
const classes = join(MODULE_DIR, 'target', 'classes')
if (!existsSync(classes)) {
  process.stderr.write(`[jcodebuddy] no compiled classes at ${classes}\n`)
  process.exit(1)
}

// 2. Run it. The CLI's own exit code is this launcher's exit code, so a script that checks it sees the truth.
//    `resolveJdk25()` returns the JDK's home, the checked `java` binary and its major version; the binary is used
//    rather than a path built here, because resolving it is what makes the candidate *run* before it is accepted.
const java = jdk.java
const commandArgs = args.slice(2)
const result = run(java, ['-cp', `${classes}${delimiter}${exported}`, MAIN_CLASS, args[0], args[1], ...commandArgs], {
  cwd: REPO,
  env: environment,
})
process.exit(result.status ?? 1)
