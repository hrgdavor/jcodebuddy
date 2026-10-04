#!/usr/bin/env bun
/**
 * mvn-fast.js — iterate with the Maven build cache; NOT the recorded gate.
 *
 * The recorded gate (`bun scripts/mvn-jdk25.js`) is deliberately the slow, honest thing: `clean`, incremental
 * compilation off, and the build cache off, because F-47 found the gate satisfiable by a previous revision's
 * class files and a cache hit is the same class of hazard. That is the right cost once per commit and the wrong
 * cost twenty times an hour, which is what this script is for.
 *
 * WHAT IT CHANGES, ALL OF IT, so the difference is not a mystery when a result surprises someone:
 *
 *   1. the **build cache is on** (`.mvn/extensions.xml`) — an unchanged module's compile *and test* phases are
 *      restored instead of re-run, which is where the time actually goes: a warm, no-clean `-pl … -am test` of
 *      the ioc tooling measured 12:43 with the cache off, almost all of it test execution;
 *   2. **incremental compilation is left at Maven's default** — on a cache miss this recompiles only what
 *      changed, instead of the whole module;
 *   3. `-T 1C` runs modules in parallel where the reactor allows it.
 *
 * It still runs `clean`, because with the cache a clean is cheap (outputs are restored) and because "the gate
 * minus the cache" is a difference a person can hold in their head. `--no-clean` is there for the case where you
 * are re-running one test and do not want Maven to touch the tree at all.
 *
 * TWO THINGS LEARNED BY RUNNING IT, both of which cost a broken build before being written down:
 *
 *   1. **The goal is `package`, not `test`.** A cached module restored without a JAR cannot be depended on by the
 *      next module in the reactor: the build fails with "Could not find artifact <sibling>:jar". `package` runs the
 *      tests anyway, and it is what puts the JAR in `target/`.
 *   2. **Never populate the cache with a phase that produces nothing.** A `mvn … validate` run stores an entry per
 *      module whose output tree is next to empty, and a later `package` build that HITS such an entry then
 *      compiles a sibling against a module with no classes — which reads as "cannot find symbol" on sources that
 *      compile perfectly. The cache is keyed in a way that lets this happen, so the rule is about how it is used:
 *      populate with `package` (this script), and if a build reports impossible symbol errors, delete
 *      `~/.m2/build-cache` — it is a cache, and deleting it is always safe.
 *   3. **`--tests` and `--no-clean` turn the cache OFF, and that is a correctness rule rather than a preference.**
 *      A narrowed run and a full run share a cache key: `-Dtest=…` is not part of it. So a run that verified ONE
 *      test class stores an entry that a later full run can hit — and that run then reports BUILD SUCCESS with
 *      surefire skipped and **no tests executed at all**. Measured: cache on, 0 tests run, SUCCESS; cache off,
 *      467 tests, 1 failure, FAILURE. A narrowed run must therefore never populate the cache, which this script
 *      enforces by not using it; `--no-clean` does the same because a cache restore into a tree that was not
 *      cleaned is how a half-restored `target/` produced a compile failure in a test harness that resolves the
 *      reactor's own output directories.
 *
 * Usage:
 *   bun scripts/mvn-fast.js                          the recorded module set, `clean test`, cached
 *   bun scripts/mvn-fast.js --tests MarkersTest      the same, narrowed to one test class
 *   bun scripts/mvn-fast.js --tests 'Marker*Test'    surefire patterns work; quote them for your shell
 *   bun scripts/mvn-fast.js --no-clean --tests X     no clean at all
 *   bun scripts/mvn-fast.js -pl :hipster-ioc-tooling -am test   a free-form Maven invocation, cached
 *   bun scripts/mvn-fast.js --off-cache              bypass the cache but keep the rest of the fast path
 *
 * Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES (all as in mvn-jdk25.js).
 */

import { GATE_MODULES, moduleSelectors, splitProperty, splitPropertyAdvice } from './lib/gate.js';
import { envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

const CACHE_OFF = '-Dmaven.build.cache.enabled=false';
const PARALLEL = '-T';
const PARALLEL_FACTOR = '1C';
const NO_TESTS_SPECIFIED = '-Dsurefire.failIfNoSpecifiedTests=false';

/** The lifecycle phases this script recognises when deciding whether a free-form call already names a goal. */
const LIFECYCLE_PHASES = new Set([
    'validate', 'compile', 'test', 'package', 'verify', 'install', 'deploy', 'clean',
]);

function usage() {
    console.log(`mvn-fast.js — the cached iteration build (the recorded gate is mvn-jdk25.js)

  bun scripts/mvn-fast.js                        the recorded module set, clean package, build cache on
  bun scripts/mvn-fast.js --tests SomeTest       the same narrowed to one test class (cache off: see below)
  bun scripts/mvn-fast.js --no-clean             reuse the tree as it is (cache off: see below)
  bun scripts/mvn-fast.js --off-cache            full no-reuse run
  bun scripts/mvn-fast.js --no-parallel          one module at a time
  bun scripts/mvn-fast.js -pl :module -am package   a free-form Maven invocation

  Differences from the recorded gate: build cache ON, incremental compilation at Maven's default, -T 1C.
  The gate stays \`clean test\`, cache off, incremental off — run it before a commit.

  --tests and --no-clean force the cache OFF. A narrowed run shares a cache key with a full one (the -Dtest
  property is not hashed), so allowing it to populate the cache lets a later full run hit evidence from one test
  class and report SUCCESS without running anything.`);
}

/** Splits this script's own options from the Maven arguments that follow them. */
function parse(argv) {
    const maven = [];
    const options = { tests: null, clean: true, cache: true, parallel: true, help: false };
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === '--help' || arg === '-h') {
            options.help = true;
        } else if (arg === '--no-clean') {
            options.clean = false;
        } else if (arg === '--off-cache') {
            options.cache = false;
        } else if (arg === '--no-parallel') {
            options.parallel = false;
        } else if (arg === '--tests') {
            options.tests = argv[++i] ?? '';
        } else if (arg.startsWith('--tests=')) {
            options.tests = arg.slice('--tests='.length);
        } else {
            maven.push(arg);
        }
    }
    // Correctness, not preference. `-Dtest=…` is not part of the cache key, so a narrowed run and a full run share
    // an entry: a one-class run would populate it and a later full run would hit it, skip surefire and report
    // SUCCESS having run no tests. Measured, not reasoned: cache on, zero tests, SUCCESS; cache off, 467 tests,
    // one failure, FAILURE. `--no-clean` is the same rule for a different reason — restoring a cache entry into a
    // tree that was not cleaned is how a half-restored `target/` made a compile harness fail on sources that
    // compile.
    if (options.tests !== null || !options.clean) {
        options.cache = false;
    }
    return { maven, options };
}

function main() {
    const { maven, options } = parse(process.argv.slice(2));
    if (options.help) {
        usage();
        return 0;
    }

    const bad = splitProperty(maven);
    if (bad) {
        console.error(splitPropertyAdvice('mvn-fast', bad));
        return 2;
    }

    const jdk = resolveJdk25();
    const mavenLauncher = resolveMaven();

    const testArgs = options.tests ? [`-Dtest=${options.tests}`, NO_TESTS_SPECIFIED] : [];

    // Maven's command line is options-then-goals: `-T`, `-o` and `-pl` placed *after* `clean test` are not
    // tolerated, and the first version of this script appended them there and failed with "Could not resolve
    // dependencies" — a failure that names the reactor while the cause is the argument order. So the extra
    // options are built first and inserted before the first lifecycle phase, in both paths.
    const extraOptions = [];
    if (!options.cache) {
        // The cache is on by default through .mvn/, so nothing has to be passed to enable it. `--off-cache` is the
        // switch worth having, and it is the same property the gate sets.
        extraOptions.push(CACHE_OFF);
    }
    if (options.parallel && !maven.some((arg) => arg === PARALLEL || arg.startsWith(PARALLEL))) {
        extraOptions.push(PARALLEL, PARALLEL_FACTOR);
    }

    // The shortcut form (no arguments, or nothing that looks like a Maven option) uses the recorded module set, so
    // "fast" and "the gate" cover the same modules and a green fast run means something. A free-form invocation is
    // the caller's own argument list, with the script's own options applied on top.
    const freeForm = maven.some((arg) => arg.startsWith('-'));
    let args;
    if (freeForm) {
        const firstPhase = maven.findIndex((arg) => LIFECYCLE_PHASES.has(arg));
        if (options.tests && firstPhase < 0) {
            // `--tests` needs a phase to attach to; the explicit list avoids guessing whether a token is a goal,
            // because an option's value (`-pl :module`) also does not start with a dash.
            maven.push('test');
        }
        const at = maven.findIndex((arg) => LIFECYCLE_PHASES.has(arg));
        if (at < 0) {
            args = [...maven, ...extraOptions, ...testArgs];
        } else {
            args = [...maven.slice(0, at), ...extraOptions, ...maven.slice(at), ...testArgs];
        }
        if (!args.includes('-o')) {
            args.unshift('-o');
        }
    } else {
        args = ['-o', '-pl', moduleSelectors(GATE_MODULES), '-am', ...extraOptions, ...testArgs];
        if (options.clean) {
            args.push('clean');
        }
        // `package`, not `test`: `package` runs the tests anyway, and it is what puts a JAR in each module's
        // `target/`. With `test` alone a cached module is restored with its classes but no artifact, and the next
        // module in the reactor then fails with "Could not find artifact <sibling>:jar" — a cached module that
        // cannot be depended on is not a module this build can use. Measured, not assumed: that was the first
        // version's failure.
        args.push('package');
    }

    if (process.env.JCODEBUDDY_QUIET !== '1') {
        console.error(`[mvn-fast] JDK ${jdk.version}; ${mavenLauncher.distribution}`);
        console.error(`[mvn-fast] repo: ${repoRoot()}`);
        console.error(`[mvn-fast] cache: ${options.cache ? 'ON (.mvn/extensions.xml)' : 'OFF (-Dmaven.build.cache.enabled=false)'}`);
        console.error('[mvn-fast] this is NOT the recorded gate: for a commit, run `bun scripts/mvn-jdk25.js`');
    }

    const result = run(mavenLauncher.command, args, { stdio: 'inherit', env: envWith(jdk) });
    if (result.error) {
        console.error(`[mvn-fast] ERROR: could not run ${mavenLauncher.command}: ${result.error.message}`);
        return 1;
    }
    return result.status;
}

process.exit(main());
