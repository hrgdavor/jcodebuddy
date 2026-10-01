#!/usr/bin/env bun

import { existsSync, readFileSync } from "node:fs";
import { mkdir, readFile, readdir } from "node:fs/promises";
import path from "node:path";

// The gate compiles at release 25, so every class file this runner reads (target/classes,
// target/test-classes) is Java 25 bytecode (major version 69). A javac from an older JDK
// cannot read those files, and the failure surfaces as a wall of bogus "cannot find symbol"
// errors in the JMH-generated sources instead of as a version problem.
const REQUIRED_JDK_MAJOR = 25;

const rootDir = path.resolve(import.meta.dir, "..");
const coreDir = path.join(rootDir, "hipster-entity-core");
const arenaDir = path.join(rootDir, "metadata-arena");
const resultDir = path.join(rootDir, "target", "jmh");
const classpathFile = path.join(resultDir, "test-classpath.txt");

function parseArgs(argv) {
    const options = {
        include: ".*JmhBenchmark.*",
        // Inlining-oriented defaults: enough warmup and multiple forks to let C2 settle.
        forks: "3",
        warmupIterations: "6",
        measurementIterations: "8",
        warmupTime: "2s",
        measurementTime: "2s",
        threads: "1",
        extra: []
    };

    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if ((arg === "--include" || arg === "-i") && argv[i + 1]) {
            options.include = argv[++i];
            continue;
        }
        if (arg === "--forks" && argv[i + 1]) {
            options.forks = argv[++i];
            continue;
        }
        if (arg === "--warmup-iterations" && argv[i + 1]) {
            options.warmupIterations = argv[++i];
            continue;
        }
        if (arg === "--measurement-iterations" && argv[i + 1]) {
            options.measurementIterations = argv[++i];
            continue;
        }
        if (arg === "--warmup-time" && argv[i + 1]) {
            options.warmupTime = argv[++i];
            continue;
        }
        if (arg === "--measurement-time" && argv[i + 1]) {
            options.measurementTime = argv[++i];
            continue;
        }
        if (arg === "--threads" && argv[i + 1]) {
            options.threads = argv[++i];
            continue;
        }
        options.extra.push(arg);
    }

    return options;
}

function pickMavenExecutable() {
    return Bun.which("mvnd") ?? Bun.which("mvn");
}

function pickJavaExecutable() {
    if (process.env.JAVA_HOME) {
        const javaFromHome = path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
        if (existsSync(javaFromHome)) return javaFromHome;
    }
    return Bun.which("java");
}

function pickJavacExecutable(javaExecutable) {
    if (process.env.JAVA_HOME) {
        const javacFromHome = path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "javac.exe" : "javac");
        if (existsSync(javacFromHome)) return javacFromHome;
    }
    const javaDir = path.dirname(javaExecutable);
    const siblingJavac = path.join(javaDir, process.platform === "win32" ? "javac.exe" : "javac");
    if (existsSync(siblingJavac)) return siblingJavac;
    return Bun.which("javac");
}

// Read the JDK's own `release` file (JDK 9+) rather than spawning `javac -version`:
// no piped stdio, no shell, and it works from the JAVA_HOME the runner is about to use.
function detectJdkMajor(javaExecutable) {
    const homes = [];
    if (process.env.JAVA_HOME) homes.push(process.env.JAVA_HOME);
    if (javaExecutable) homes.push(path.dirname(path.dirname(javaExecutable)));
    for (const home of homes) {
        const releaseFile = path.join(home, "release");
        if (!existsSync(releaseFile)) continue;
        const match = readFileSync(releaseFile, "utf8").match(/JAVA_VERSION="(\d+)/);
        if (match) return { home, major: Number(match[1]) };
    }
    return null;
}

function checkJdkVersion(javaExecutable) {
    const jdk = detectJdkMajor(javaExecutable);
    if (!jdk) {
        console.warn("Warning: could not determine the JDK version (no JAVA_HOME/release file). "
            + `The gate compiles at release ${REQUIRED_JDK_MAJOR}; if compilation of the JMH-generated `
            + "sources fails with 'class file has wrong version', point JAVA_HOME at the gate JDK.");
        return;
    }
    if (jdk.major < REQUIRED_JDK_MAJOR) {
        const quote = process.platform === "win32"
            ? `$env:JAVA_HOME = "<path to JDK ${REQUIRED_JDK_MAJOR}>"`
            : `export JAVA_HOME="<path to JDK ${REQUIRED_JDK_MAJOR}>"`;
        console.error(
            `ERROR: this run would use JDK ${jdk.major} (${jdk.home}), but the gate compiles at release `
            + `${REQUIRED_JDK_MAJOR}.\n`
            + `The modules under target/ already hold Java ${REQUIRED_JDK_MAJOR} bytecode (class file `
            + `version ${REQUIRED_JDK_MAJOR + 44}.0), which JDK ${jdk.major}'s javac cannot read; the `
            + `recompile step would fail with "class file has wrong version" followed by hundreds of `
            + `misleading "cannot find symbol" errors in the JMH-generated sources.\n`
            + `Fix: point JAVA_HOME at the JDK ${REQUIRED_JDK_MAJOR} this repository builds with, then `
            + `re-run:\n  ${quote}\n  bun run scripts/run-jmh.js ...`);
        process.exit(1);
    }
}

async function collectJavaFiles(dir) {
    const out = [];
    if (!existsSync(dir)) return out;
    const entries = await readdir(dir, { withFileTypes: true });
    for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            out.push(...await collectJavaFiles(fullPath));
        } else if (entry.isFile() && entry.name.endsWith(".java")) {
            out.push(fullPath);
        }
    }
    return out;
}

function formatScore(score) {
    const numericScore = typeof score === "number" ? score : Number(score);
    if (score === undefined || score === null || Number.isNaN(numericScore)) return "n/a";
    if (Math.abs(numericScore) >= 1000) return numericScore.toFixed(1);
    if (Math.abs(numericScore) >= 100) return numericScore.toFixed(2);
    return numericScore.toFixed(3);
}

async function runCommand(cmd, cwd) {
    const child = Bun.spawn({
        cmd,
        cwd,
        stdout: "inherit",
        stderr: "inherit"
    });
    const exitCode = await child.exited;
    if (exitCode !== 0) process.exit(exitCode);
}

function summarize(results) {
    const rows = results
        .map((entry) => ({
            benchmark: entry.benchmark,
            method: entry.benchmark.split(".").at(-1),
            score: entry.primaryMetric?.score,
            error: entry.primaryMetric?.scoreError,
            unit: entry.primaryMetric?.scoreUnit ?? "",
            mode: entry.mode
        }))
        .sort((a, b) => a.benchmark.localeCompare(b.benchmark));

    const grouped = new Map();
    for (const row of rows) {
        // Groups are display-only: a benchmark whose name matches none of the entity families is an arena
        // benchmark (metadata-arena), and calling those "SetCompare" would mislabel the summary a reader
        // uses to find the numbers.
        const groupName = row.benchmark.includes("Tracking") ? "Tracking"
            : row.benchmark.includes("Overlap") ? "Overlap"
            : row.benchmark.includes("Arena") ? "Arena"
            : "SetCompare";
        if (!grouped.has(groupName)) grouped.set(groupName, []);
        grouped.get(groupName).push(row);
    }

    for (const [groupName, groupRows] of grouped) {
        console.log(`\n${groupName}`);
        console.log("Method                               Mode   Score        Error        Unit");
        console.log("-----------------------------------  -----  -----------  -----------  ----------------");
        for (const row of groupRows) {
            const method = row.method.padEnd(35, " ");
            const mode = String(row.mode ?? "").padEnd(5, " ");
            const score = formatScore(row.score).padStart(11, " ");
            const error = formatScore(row.error).padStart(11, " ");
            console.log(`${method}  ${mode}  ${score}  ${error}  ${row.unit}`);
        }

        const ratioRows = buildConcreteVsPolymorphicRatios(groupRows);
        if (ratioRows.length > 0) {
            console.log("\nConcrete vs Polymorphic Ratios");
            console.log("Concrete Method                      Polymorphic Method                  Speedup  Delta(%)");
            console.log("-----------------------------------  -----------------------------------  -------  --------");
            for (const ratioRow of ratioRows) {
                const concreteName = ratioRow.concrete.padEnd(35, " ");
                const polymorphicName = ratioRow.polymorphic.padEnd(35, " ");
                const speedup = ratioRow.speedup.toFixed(3).padStart(7, " ");
                const delta = ratioRow.deltaPercent.toFixed(2).padStart(8, " ");
                console.log(`${concreteName}  ${polymorphicName}  ${speedup}  ${delta}`);
            }
        }
    }
}

function buildConcreteVsPolymorphicRatios(groupRows) {
    const byMethod = new Map();
    for (const row of groupRows) {
        byMethod.set(row.method, row);
    }

    const ratios = [];
    for (const concreteRow of groupRows) {
        if (!concreteRow.method.includes("Concrete")) continue;

        const interfaceMethod = concreteRow.method.replace("Concrete", "Interface");
        const abstractMethod = concreteRow.method.replace("Concrete", "Abstract");
        let polymorphicRow = byMethod.get(interfaceMethod);
        if (!polymorphicRow) polymorphicRow = byMethod.get(abstractMethod);
        if (!polymorphicRow) continue;

        const concreteScore = Number(concreteRow.score);
        const polymorphicScore = Number(polymorphicRow.score);
        if (!Number.isFinite(concreteScore) || !Number.isFinite(polymorphicScore) || polymorphicScore === 0) continue;

        const speedup = concreteScore / polymorphicScore;
        const deltaPercent = ((concreteScore - polymorphicScore) / polymorphicScore) * 100;

        ratios.push({
            concrete: concreteRow.method,
            polymorphic: polymorphicRow.method,
            speedup,
            deltaPercent
        });
    }

    ratios.sort((a, b) => a.concrete.localeCompare(b.concrete));
    return ratios;
}

async function main() {
    const options = parseArgs(process.argv.slice(2));
    const maven = pickMavenExecutable();
    const java = pickJavaExecutable();
    const javac = java ? pickJavacExecutable(java) : null;
    if (!maven) {
        console.error("Neither 'mvnd' nor 'mvn' is available on PATH.");
        process.exit(1);
    }
    if (!java) {
        console.error("'java' is not available on PATH and JAVA_HOME is not set.");
        process.exit(1);
    }
    if (!javac) {
        console.error("'javac' is not available on PATH and JAVA_HOME is not set.");
        process.exit(1);
    }
    checkJdkVersion(java);

    await mkdir(resultDir, { recursive: true });

    const warmupIterationsNum = Number(options.warmupIterations);
    const warmupTimeMatch = String(options.warmupTime).match(/^(\d+)(ms|s|m)$/i);
    const warmupTimeSeconds = warmupTimeMatch
        ? (warmupTimeMatch[2].toLowerCase() === "ms"
            ? Number(warmupTimeMatch[1]) / 1000
            : warmupTimeMatch[2].toLowerCase() === "m"
                ? Number(warmupTimeMatch[1]) * 60
                : Number(warmupTimeMatch[1]))
        : NaN;
    const forksNum = Number(options.forks);
    const isShortProfile = (Number.isFinite(forksNum) && forksNum < 2)
        || (Number.isFinite(warmupIterationsNum) && warmupIterationsNum < 4)
        || (Number.isFinite(warmupTimeSeconds) && warmupTimeSeconds < 1.5);

    // A smoke run must not overwrite the decision-grade results file. The evidence tables in
    // doc-hipster-entity/architecture/enumset-implementation-and-jmh.md are read back from
    // results.json, and losing them to a one-iteration blast-radius check is silent data loss.
    const runResultFile = path.join(resultDir, isShortProfile ? "results-smoke.json" : "results.json");

    const jmhArgs = [
        options.include,
        "-rf", "json",
        "-rff", runResultFile,
        "-f", options.forks,
        "-wi", options.warmupIterations,
        "-i", options.measurementIterations,
        "-w", options.warmupTime,
        "-r", options.measurementTime,
        "-t", options.threads,
        ...options.extra
    ];

    const jacksonDir = path.join(rootDir, "hipster-entity-jackson");
    const testDir    = path.join(rootDir, "hipster-entity-test");

    // Install every benchmark-bearing module so hipster-entity-test can resolve them
    // as dependencies. The jmh profile activates the JMH annotation processor with
    // -proc:full, which JDK 25 requires for classpath processors.
    // `metadata-arena` is here for its own benchmarks (ArenaIndexJmhBenchmark) — it is not a dependency
    // of the entity modules, and it is independent, so it can land anywhere in reactor order.
    const benchmarkModuleList = "hipster-entity-api,hipster-entity-core,hipster-entity-jackson,"
        + "hipster-entity-test,metadata-arena";
    const bootstrapArgs = [
        "-pl", benchmarkModuleList,
        "-Pjmh",
        "-DskipTests",
        "install"
    ];

    // Compile all benchmark-bearing modules (and their deps): this compiles all benchmark
    // classes now living in the test, core and arena modules and runs the JMH annotation
    // processor, generating runners in each module's target/generated-test-sources/test-annotations.
    const compileArgs = [
        "-pl", benchmarkModuleList,
        "-Pjmh",
        "-DskipTests",
        "clean",
        "test-compile"
    ];

    // The classpath file is built from ONE named module rather than from whichever module happened to be
    // last in reactor order. That ordering used to decide it implicitly ("the last module in reactor order
    // is hipster-entity-test, so the file includes jackson-databind and every transitive dep"), and adding
    // an independent module — `metadata-arena`, which depends on none of the entity modules — can put a
    // different module last and silently produce a classpath missing the very dependencies the benchmarks
    // need. Naming the module makes the classpath a fact instead of an ordering accident; the bootstrap
    // install above is what lets it resolve its siblings from the local repository.
    // No `-Pjmh` here: this invocation builds ONE module, and hipster-entity-test does not define that
    // profile (the processor switch lives in the modules that own benchmarks). Passing it made Maven fail
    // with "The requested profiles [jmh] could not be activated ... because they do not exist", which is a
    // fair complaint — the profile is not this module's. It does declare the JMH artefacts itself, so the
    // classpath it produces carries them without any profile.
    const classpathArgs = [
        "-pl", "hipster-entity-test",
        "-DskipTests",
        "dependency:build-classpath",
        `-Dmdep.outputFile=${classpathFile}`,
        "-Dmdep.includeScope=test"
    ];

    console.log(`Bootstrapping JMH dependencies with ${path.basename(maven)}...`);
    await runCommand([maven, ...bootstrapArgs], rootDir);

    console.log(`Preparing test classpath with ${path.basename(maven)}...`);
    await runCommand([maven, ...compileArgs], rootDir);
    await runCommand([maven, ...classpathArgs], rootDir);

    const dependencyClasspath = (await readFile(classpathFile, "utf8")).trim();
    const classpathEntries = [
        // Test module: benchmark classes living here
        path.join(testDir, "target", "test-classes"),
        path.join(testDir, "target", "classes"),
        // Core module: EEnumSet benchmarks (EEnumSetJmhBenchmark, EEnumSetTrackingJmhBenchmark,
        // EEnumSetOverlapJmhBenchmark) and their JMH-generated runners
        path.join(coreDir, "target", "test-classes"),
        path.join(coreDir, "target", "classes"),
        // Jackson module: production classes (no benchmark classes remain here)
        path.join(jacksonDir, "target", "classes"),
        path.join(jacksonDir, "target", "test-classes"),
        // metadata-arena: ArenaIndexJmhBenchmark and the arena/index classes it measures
        path.join(arenaDir, "target", "test-classes"),
        path.join(arenaDir, "target", "classes"),
    ];
    if (dependencyClasspath.length > 0) classpathEntries.push(dependencyClasspath);
    const effectiveClasspath = classpathEntries.join(path.delimiter);

    console.log(`Running JMH with ${path.basename(java)}...`);
    console.log(`Include pattern: ${options.include}`);
    console.log("Inlining profile: defaults are tuned for C2 inline stabilization (forks=3, warmup=6x2s). Override only if you need faster smoke runs.");
    if (isShortProfile) {
        console.warn("Warning: current run uses short warmup/fork settings; JIT inlining may not be fully stabilized.");
        console.warn(`Warning: results go to ${path.basename(runResultFile)} — not a decision-grade run.`);
    }

    // Collect JMH generated runner sources from all benchmark-bearing modules —
    // benchmarks may live in any of them.
    const generatedJavaFiles = [
        ...await collectJavaFiles(path.join(coreDir,    "target", "generated-test-sources", "test-annotations")),
        ...await collectJavaFiles(path.join(jacksonDir, "target", "generated-test-sources", "test-annotations")),
        ...await collectJavaFiles(path.join(testDir,    "target", "generated-test-sources", "test-annotations")),
        ...await collectJavaFiles(path.join(arenaDir,   "target", "generated-test-sources", "test-annotations")),
    ];
    if (generatedJavaFiles.length > 0) {
        console.log(`Recompiling ${generatedJavaFiles.length} JMH generated sources with ${path.basename(javac)}...`);
        await runCommand([
            javac,
            "-cp", effectiveClasspath,
            "-d", path.join(testDir, "target", "test-classes"),
            ...generatedJavaFiles
        ], testDir);
    }
    console.log(`Results file: ${runResultFile}`);
    await runCommand([java, "-cp", effectiveClasspath, "org.openjdk.jmh.Main", ...jmhArgs], testDir);

    const jsonText = await readFile(runResultFile, "utf8");
    const results = JSON.parse(jsonText);
    summarize(results);
}

await main();
