#!/usr/bin/env bun
/**
 * Runs the hipster-entity-tooling JMH benchmarks and writes their JSON next to the Phase 7 report.
 *
 * Why a script and not the `mvn exec:java` line the profile would suggest: the recorded build gate on
 * Windows goes through `scripts/mvn-jdk25.cmd`, which is what pins both the Maven JVM and the forked test
 * JVM to JDK 25. A benchmark that runs on a different JDK than the gate measures a different machine, so
 * this script shells through the same launcher, asks Maven for the module's *test* classpath, and then
 * starts JMH with the same `java` the launcher resolved (`JCODEBUDDY_RESOLVED_JDK`).
 *
 * `scripts/run-jmh.js` is the existing JMH runner for hipster-entity-core/-test; it is left alone. This
 * one is narrow on purpose - it runs the migration's read-path benchmarks and nothing else, and it is the
 * input `generate-test-report.js` folds into TEST-REPORT.md.
 *
 * Usage (from the repository root):
 *   bun run scripts/rewrite-migration/run-tooling-benchmarks.js
 *   bun run scripts/rewrite-migration/run-tooling-benchmarks.js -- --include ReadPath --forks 1
 *
 * Flags:
 *   --include <regex>    benchmark name filter (default: .*JmhBenchmark.*)
 *   --forks <n>          JMH forks (default 1: these are order-of-magnitude baselines, not published
 *                        micro-optimisation results, and one fork keeps a full run under two minutes)
 *   --warmup <n>x<time>  warmup iterations (default 3x1s)
 *   --measurement <n>x<time>  measurement iterations (default 5x1s)
 *   --skip-compile       reuse whatever is already in target/
 *   --out <file>         result JSON (default doc/brainstorm/rewrite-migration/07-testing/benchmarks/benchmarks.json)
 */
import { existsSync, readFileSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const rootDir = path.resolve(import.meta.dir, "..", "..");
const moduleDir = path.join(rootDir, "hipster-entity-tooling");
const launcher = path.join(rootDir, "scripts", "mvn-jdk25.cmd");
const defaultOut = path.join(
    rootDir, "doc", "brainstorm", "rewrite-migration", "07-testing", "benchmarks", "benchmarks.json");

function parseArgs(argv) {
    const options = {
        include: ".*JmhBenchmark.*",
        forks: "1",
        warmup: "3x1s",
        measurement: "5x1s",
        skipCompile: false,
        out: defaultOut,
    };
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === "--include") options.include = argv[++i];
        else if (arg === "--forks") options.forks = argv[++i];
        else if (arg === "--warmup") options.warmup = argv[++i];
        else if (arg === "--measurement") options.measurement = argv[++i];
        else if (arg === "--skip-compile") options.skipCompile = true;
        else if (arg === "--out") options.out = path.resolve(rootDir, argv[++i]);
        else {
            console.error(`Unknown argument: ${arg}`);
            process.exit(2);
        }
    }
    return options;
}

/** `[warmupCount, warmupTime, measurementCount, measurementTime]` from `3x1s` style values. */
function parseIterations(value) {
    const match = String(value).match(/^(\d+)x(\d+(?:ms|s|m))$/i);
    if (!match) {
        console.error(`Expected <count>x<time>, got "${value}" (e.g. 3x1s).`);
        process.exit(2);
    }
    return [match[1], match[2]];
}

/**
 * The JDK 25 home, chosen the way `scripts/mvn-jdk25.cmd` chooses it.
 *
 * <p>This matters more than it looks: the classes are compiled with `maven.compiler.release=25` (class
 * file 69), so a fork started on whatever `JAVA_HOME` happens to point at dies with
 * `UnsupportedClassVersionError` - and JMH reports that once per benchmark, then writes an empty result
 * file and exits 0. A runner that cannot measure anything must not look like a run that measured nothing
 * interesting, so the version is checked here and the script refuses otherwise. The check reads the JDK's
 * own `release` file rather than asking `java -version`, because this script must not depend on being able
 * to capture a child process's output.</p>
 */
function resolveJava() {
    const candidates = [
        process.env.JCODEBUDDY_JDK25,
        "C:\\Program Files\\Java\\jdk-25",
        process.env.JAVA_HOME,
    ].filter(Boolean);
    for (const home of candidates) {
        const java = path.join(home, "bin", process.platform === "win32" ? "java.exe" : "java");
        if (!existsSync(java)) {
            continue;
        }
        const release = path.join(home, "release");
        if (existsSync(release)) {
            const version = readFileSync(release, "utf8").match(/JAVA_VERSION="([^"]+)"/)?.[1] ?? "unknown";
            if (!version.startsWith("25")) {
                console.error(`${home} is JDK ${version}; these benchmarks need JDK 25 (the classes are`
                        + ` class-file 69). Set JCODEBUDDY_JDK25 and try again.`);
                process.exit(1);
            }
        }
        return java;
    }
    console.error(`No JDK 25 found. Tried: ${candidates.join(", ") || "nothing"} - set JCODEBUDDY_JDK25.`);
    process.exit(1);
}

async function run(cmd, cwd) {
    console.log(`\n$ ${cmd.join(" ")}`);
    const child = Bun.spawn({ cmd, cwd, stdout: "inherit", stderr: "inherit" });
    const code = await child.exited;
    if (code !== 0) {
        console.error(`Command failed with exit code ${code}`);
        process.exit(code);
    }
}

const options = parseArgs(process.argv.slice(2));
const [warmupIterations, warmupTime] = parseIterations(options.warmup);
const [measureIterations, measureTime] = parseIterations(options.measurement);

if (!existsSync(launcher)) {
    console.error(`Missing ${launcher}`);
    process.exit(1);
}

const classpathFile = path.join(moduleDir, "target", "jmh-test-classpath.txt");

if (!options.skipCompile) {
    // test-compile under the jmh profile is what runs the annotation processor; without -proc:full (which
    // the profile adds, see the module POM) the harness classes silently do not exist and JMH finds no
    // benchmark at all. Note F-49 records exactly that trap in hipster-entity-core.
    await run([launcher, "-o", "-pl", "hipster-entity-tooling", "-am", "-Pjmh",
        "-Dmaven.compiler.useIncrementalCompilation=false", "clean", "test-compile"], rootDir);
    await run([launcher, "-o", "-pl", "hipster-entity-tooling", "-Pjmh", "dependency:build-classpath",
        `-Dmdep.outputFile=${classpathFile}`, "-Dmdep.includeScope=test"], rootDir);
}

const dependencyClasspath = (await readFile(classpathFile, "utf8")).trim();
// The module's own output first, exactly as surefire would put it: the benchmark reads committed sources
// by repo-relative path, so it needs the compiled classes, not a shaded jar.
const classpath = [
    path.join(moduleDir, "target", "test-classes"),
    path.join(moduleDir, "target", "classes"),
    dependencyClasspath,
].join(path.delimiter);

await mkdir(path.dirname(options.out), { recursive: true });

const jmhArgs = [
    options.include,
    "-f", options.forks,
    "-wi", warmupIterations,
    "-i", measureIterations,
    "-w", warmupTime,
    "-r", measureTime,
    "-t", "1",
    "-rf", "json",
    "-rff", options.out,
];

console.log(`\nJMH include: ${options.include}`);
console.log(`Forks ${options.forks}, warmup ${warmupIterations}x${warmupTime}, measurement ${measureIterations}x${measureTime}`);
await run([resolveJava(), "-cp", classpath, "org.openjdk.jmh.Main", ...jmhArgs], rootDir);

const results = JSON.parse(await readFile(options.out, "utf8"));
if (results.length === 0) {
    // JMH exits 0 with an empty result file when every fork failed to start (a wrong JDK, a missing
    // harness class). Report that as the failure it is instead of printing a clean empty table.
    console.error("\nNo benchmark results. Either the include pattern matched nothing, or every fork"
            + " failed to start - check the lines above for UnsupportedClassVersionError (wrong JDK) or"
            + " for 'No matching benchmarks' (harness not generated: build with -Pjmh).");
    process.exit(1);
}
const rows = results.map(entry => ({
    benchmark: entry.benchmark.split(".").at(-1),
    mode: entry.mode,
    score: entry.primaryMetric.score,
    error: entry.primaryMetric.scoreError,
    unit: entry.primaryMetric.scoreUnit,
})).sort((a, b) => a.benchmark.localeCompare(b.benchmark));

console.log("\nBenchmark                             Mode          Score      Error   Unit");
console.log("------------------------------------  ------------  ---------  --------  ------");
for (const row of rows) {
    console.log(`${row.benchmark.padEnd(35)}  ${String(row.mode).padEnd(12)}  `
        + `${row.score.toFixed(3).padStart(9)}  ${row.error.toFixed(3).padStart(8)}  ${row.unit}`);
}
console.log(`\nWrote ${rows.length} results to ${path.relative(rootDir, options.out)}`);

// A copy with a fixed name, so generate-test-report.js can find the numbers without being told where
// they came from. Overwritten on every run, like the report itself.
await writeFile(path.join(path.dirname(options.out), "latest.json"),
    JSON.stringify(rows, null, 2) + "\n");
