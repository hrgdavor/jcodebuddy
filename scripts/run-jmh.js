#!/usr/bin/env bun

import { existsSync } from "node:fs";
import { mkdir, readFile, readdir } from "node:fs/promises";
import path from "node:path";

const rootDir = path.resolve(import.meta.dir, "..");
const coreDir = path.join(rootDir, "hipster-entity-core");
const resultDir = path.join(rootDir, "target", "jmh");
const resultFile = path.join(resultDir, "results.json");
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
        const groupName = row.benchmark.includes("Tracking") ? "Tracking" : "SetCompare";
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

    await mkdir(resultDir, { recursive: true });

    const jmhArgs = [
        options.include,
        "-rf", "json",
        "-rff", resultFile,
        "-f", options.forks,
        "-wi", options.warmupIterations,
        "-i", options.measurementIterations,
        "-w", options.warmupTime,
        "-r", options.measurementTime,
        "-t", options.threads,
        ...options.extra
    ];

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

    const jacksonDir = path.join(rootDir, "hipster-entity-jackson");
    const testDir    = path.join(rootDir, "hipster-entity-test");

    // Install jackson so hipster-entity-test can resolve it as a dependency.
    const bootstrapArgs = [
        "-pl", "hipster-entity-test",
        "-Pjmh",
        "-DskipTests",
        "install"
    ];

    // Compile hipster-entity-test (and its deps via -am): this compiles all benchmark
    // classes now living in the test module and runs the JMH annotation processor,
    // generating runners in hipster-entity-test/target/generated-test-sources.
    // The classpath file is written by the last module in reactor order (hipster-entity-test),
    // so it includes jackson-databind, JMH, and all transitive deps.
    const compileArgs = [
        "-pl", "hipster-entity-test",
        "-DskipTests",
        "clean",
        "test-compile",
        "dependency:build-classpath",
        `-Dmdep.outputFile=${classpathFile}`,
        "-Dmdep.includeScope=test"
    ];

    console.log(`Bootstrapping JMH dependencies with ${path.basename(maven)}...`);
    await runCommand([maven, ...bootstrapArgs], rootDir);

    console.log(`Preparing test classpath with ${path.basename(maven)}...`);
    await runCommand([maven, ...compileArgs], rootDir);

    const dependencyClasspath = (await readFile(classpathFile, "utf8")).trim();
    const classpathEntries = [
        // Test module first: all benchmark classes live here after the move
        path.join(testDir, "target", "test-classes"),
        path.join(testDir, "target", "classes"),
        // Jackson module: production classes (no benchmark classes remain here)
        path.join(jacksonDir, "target", "classes"),
        path.join(jacksonDir, "target", "test-classes"),
    ];
    if (dependencyClasspath.length > 0) classpathEntries.push(dependencyClasspath);
    const effectiveClasspath = classpathEntries.join(path.delimiter);

    console.log(`Running JMH with ${path.basename(java)}...`);
    console.log(`Include pattern: ${options.include}`);
    console.log("Inlining profile: defaults are tuned for C2 inline stabilization (forks=3, warmup=6x2s). Override only if you need faster smoke runs.");
    if ((Number.isFinite(forksNum) && forksNum < 2)
        || (Number.isFinite(warmupIterationsNum) && warmupIterationsNum < 4)
        || (Number.isFinite(warmupTimeSeconds) && warmupTimeSeconds < 1.5)) {
        console.warn("Warning: current run uses short warmup/fork settings; JIT inlining may not be fully stabilized.");
    }

    // Collect JMH generated runner sources from both modules — benchmarks may live in either.
    const generatedJavaFiles = [
        ...await collectJavaFiles(path.join(jacksonDir, "target", "generated-test-sources", "test-annotations")),
        ...await collectJavaFiles(path.join(testDir,    "target", "generated-test-sources", "test-annotations")),
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
    console.log(`Results file: ${resultFile}`);
    await runCommand([java, "-cp", effectiveClasspath, "org.openjdk.jmh.Main", ...jmhArgs], testDir);

    const jsonText = await readFile(resultFile, "utf8");
    const results = JSON.parse(jsonText);
    summarize(results);
}

await main();
