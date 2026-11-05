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
 *   1. **The default goal is `package`, not `test`.** A cached module restored without a JAR cannot be depended on
 *      by the next module in the reactor: the build fails with "Could not find artifact <sibling>:jar". `package`
 *      runs the tests anyway, and it is what puts the JAR in `target/`. **`--fast` is the one deliberate
 *      exception**: it is the edit-loop lane, where the modules are already there, so it runs `test` with no
 *      `clean` and no jar — see the usage.
 *   2. **Never populate the cache with a phase that produces nothing.** A `mvn … validate` run stores an entry per
 *      module whose output tree is next to empty, and a later `package` build that HITS such an entry then
 *      compiles a sibling against a module with no classes — which reads as "cannot find symbol" on sources that
 *      compile perfectly. Which is the general rule: an entry is evidence about the run that produced it, and it is
 *      only valid for runs at least as broad. If a build ever reports impossible symbol errors, delete
 *      `~/.m2/build-cache`; it is a cache, and deleting it is always safe.
 *
 * THE CACHE IS NEVER DISABLED HERE, only its *saving* is withheld where that would be wrong. Measured: `-Dtest=…`
 * is not part of the cache checksum, so a run that executes ONE test class and saves would leave an entry that a
 * later FULL run hits — and that run then reports SUCCESS with surefire skipped and no tests executed (cache on:
 * zero tests, SUCCESS; cache off: 468 tests, one failure, FAILURE). The remedy is `-Dmaven.build.cache.skipSave=true`
 * on `--tests`, `--fast` and `--no-clean` runs: they still READ the cache (so a module whose inputs are unchanged,
 * and whose every test passed in a previous full run, resolves instantly), they simply never WRITE. Disabling the
 * cache would have thrown away the mechanism that makes iteration fast; withholding the save keeps it and closes
 * the hole.
 *
 * Usage:
 *   bun scripts/mvn-fast.js                          the recorded module set, `clean package`, cached
 *   bun scripts/mvn-fast.js --fast                   the fast lane: `-Pfast-tests test`, no clean, save withheld
 *   bun scripts/mvn-fast.js --tests MarkersTest      the same, narrowed to one test class
 *   bun scripts/mvn-fast.js --tests 'Marker*Test'    surefire patterns work; quote them for your shell
 *   bun scripts/mvn-fast.js --fast --tests X         the fast lane, one unit test class
 *   bun scripts/mvn-fast.js --no-clean --tests X     no clean at all
 *   bun scripts/mvn-fast.js -pl :hipster-ioc-tooling -am test   a free-form Maven invocation, cached
 *   bun scripts/mvn-fast.js --off-cache              bypass the cache but keep the rest of the fast path
 *
 * Environment: JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES (all as in mvn-jdk25.js).
 */

import { GATE_MODULES, moduleSelectors, splitProperty, splitPropertyAdvice } from './lib/gate.js';
import { envWith, repoRoot, resolveJdk25, resolveMaven, run } from './lib/toolchain.js';

const CACHE_OFF = '-Dmaven.build.cache.enabled=false';
const CACHE_SKIP_SAVE = '-Dmaven.build.cache.skipSave=true';
const FAST_PROFILE = '-Pfast-tests';
const PARALLEL_TESTS_PROFILE = '-Pparallel-tests';
/**
 * The fast lane's module set: the generator module and the dependencies `-am` needs. It is deliberately
 * narrower than the gate's set — the edit loop is usually about the tooling, and pulling project-automation
 * and the other consumers into a unit-only run spends time on code the change cannot have affected. The
 * full gate still uses [`GATE_MODULES`].
 */
const FAST_MODULES = 'hipster-entity-tooling';
const PARALLEL = '-T';
const PARALLEL_FACTOR = '1C';
const NO_TESTS_SPECIFIED = '-Dsurefire.failIfNoSpecifiedTests=false';

/** The lifecycle phases this script recognises when deciding whether a free-form call already names a goal. */
const LIFECYCLE_PHASES = new Set([
    'validate', 'compile', 'test', 'package', 'verify', 'install', 'deploy', 'clean',
]);

function usage() {
    console.log(`mvn-fast.js — the cached iteration build (the recorded gate is mvn-jdk25.js)

  bun scripts/mvn-fast.js                          the recorded module set, clean package, build cache on
  bun scripts/mvn-fast.js --fast                   the fast lane: tooling + deps, -Pfast-tests test, no clean
  bun scripts/mvn-fast.js --parallel-tests         the opt-in surefire fork lane (see F4)
  bun scripts/mvn-fast.js --tests SomeTest         the same narrowed to one test class (save withheld: see below)
  bun scripts/mvn-fast.js --fast --tests SomeTest  the fast lane, one unit test class
  bun scripts/mvn-fast.js --no-clean               reuse the tree as it is (save withheld: see below)
  bun scripts/mvn-fast.js --off-cache              full no-reuse run
  bun scripts/mvn-fast.js --no-parallel            one module at a time
  bun scripts/mvn-fast.js -pl :module -am package  a free-form Maven invocation

  Differences from the recorded gate: build cache ON, incremental compilation at Maven's default, -T 1C.
  The gate stays \`clean package\`, incremental off, cache on — run it before a commit.

  --tests narrows the default run to one test class; --fast activates the fast-tests profile, which excludes the
  generator, compiler and completeness test groups, and runs \`test\` with no \`clean\` because the edit loop needs
  no jar. The fast lane's module set is the tooling module plus its \`-am\` dependencies, not the gate's full set.
  Combine --fast with --tests for one unit class. --parallel-tests selects the surefire fork profile.

  --tests, --fast and --no-clean withhold the cache SAVE (-Dmaven.build.cache.skipSave=true); the cache is still
  READ, never turned off. A narrowed run shares a cache key with a full one (-Dtest= is not hashed), so an entry
  saved from one test class would be trusted by a later full run and report SUCCESS without running anything.
  Withholding the save closes that while keeping every read.`);
}

/** Splits this script's own options from the Maven arguments that follow them. */
function parse(argv) {
    const maven = [];
    const options = { tests: null, fast: false, clean: true, cache: true, parallel: true,
        parallelTests: false, help: false };
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === '--help' || arg === '-h') {
            options.help = true;
        } else if (arg === '--fast') {
            options.fast = true;
        } else if (arg === '--no-clean') {
            options.clean = false;
        } else if (arg === '--off-cache') {
            options.cache = false;
        } else if (arg === '--no-parallel') {
            options.parallel = false;
        } else if (arg === '--parallel-tests') {
            // Surefire's opt-in two/four-fork lane; distinct from -T, which parallelises modules.
            options.parallelTests = true;
        } else if (arg === '--tests') {
            options.tests = argv[++i] ?? '';
        } else if (arg.startsWith('--tests=')) {
            options.tests = arg.slice('--tests='.length);
        } else {
            maven.push(arg);
        }
    }
    // Correctness for the WRITE side only: the cache is still read. `-Dtest=…` is not part of the cache checksum,
    // so a narrowed run that saved an entry would leave evidence a later FULL run trusts — which then reports
    // SUCCESS with surefire skipped and nothing executed. skipSave lets the narrowed run use the cache (a module
    // whose inputs are unchanged resolves instantly from a previous full run) without defining any.
    // `--no-clean` gets the same treatment: an entry saved from a tree that was not cleaned can carry outputs the
    // run did not produce. `--fast` never cleans either, so it joins them: a test-only lane must not define the
    // entry a later full `package` run trusts.
    if (options.tests !== null || options.fast || !options.clean) {
        options.skipSave = true;
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
        // explicit escape hatch for a run that must reuse nothing at all.
        extraOptions.push(CACHE_OFF);
    } else if (options.skipSave) {
        extraOptions.push(CACHE_SKIP_SAVE);
    }
    if (options.fast) {
        // F1 owns the profile: it excludes the generator/compiler/completeness groups in the root POM. This script
        // only forwards it, so the lane definition stays in one place.
        extraOptions.push(FAST_PROFILE);
    }
    if (options.parallelTests) {
        // F4's surefire profile: two/four forks, generator statics isolated per JVM. It is opt-in; the
        // serial run remains the default.
        extraOptions.push(PARALLEL_TESTS_PROFILE);
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
        if ((options.tests || options.fast) && firstPhase < 0) {
            // `--tests` and `--fast` need a phase to attach to; the explicit list avoids guessing whether a token is
            // a goal, because an option's value (`-pl :module`) also does not start with a dash.
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
        const modules = options.fast ? FAST_MODULES : GATE_MODULES;
        args = ['-o', '-pl', moduleSelectors(modules), '-am', ...extraOptions, ...testArgs];
        if (options.clean && !options.fast) {
            args.push('clean');
        }
        if (options.fast) {
            // The fast lane is the edit loop, not a build: `test` with no `clean` is enough, and a jar is not
            // needed. The default path keeps `package` for the reason below.
            args.push('test');
        } else {
            // `package`, not `test`: `package` runs the tests anyway, and it is what puts a JAR in each module's
            // `target/`. With `test` alone a cached module is restored with its classes but no artifact, and the
            // next module in the reactor then fails with "Could not find artifact <sibling>:jar" — a cached module
            // that cannot be depended on is not a module this build can use. Measured, not assumed: that was the
            // first version's failure.
            args.push('package');
        }
    }

    if (process.env.JCODEBUDDY_QUIET !== '1') {
        console.error(`[mvn-fast] JDK ${jdk.version}; ${mavenLauncher.distribution}`);
        console.error(`[mvn-fast] repo: ${repoRoot()}`);
        console.error(`[mvn-fast] cache: ${options.cache ? 'ON (.mvn/extensions.xml)' : 'OFF (-Dmaven.build.cache.enabled=false)'}` +
            `${options.skipSave ? ', save withheld (-Dmaven.build.cache.skipSave=true)' : ''}`);
        console.error(`[mvn-fast] lane: ${options.fast ? 'FAST (-Pfast-tests: unit subset; test, no clean)' : 'default'}` +
            `${options.tests ? `; tests: ${options.tests}` : ''}`);
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
