#!/usr/bin/env bun
/**
 * Renders Phase 7's TEST-REPORT.md from the artifacts a test run leaves behind.
 *
 * <p>Why a generator and not a handwritten file: the plan's deliverable is a report whose numbers are
 * true. Written by hand, the test counts are whatever the last run happened to produce, and nothing
 * ties them to the build that produced them. This reads what Surefire actually wrote
 * ({@code <module>/target/surefire-reports/TEST-*.xml}), what the module's JMH benchmarks actually
 * wrote ({@code benchmarks/latest.json}), and what the migration gate actually wrote (a captured
 * {@code verify-migration.js} run), and renders a report from those three. Re-run it after any build
 * and the numbers move with the build.</p>
 *
 * <p>It is Bun, not Java, for the reason AGENTS.md § 1 gives for every report: presentation is not the
 * generator's job, and a Java renderer would put a second parser between the facts and the page.</p>
 *
 * <h3>What it refuses to do</h3>
 * <p>It never spawns the gate. A child process with captured stdio is exactly what the Windows file
 * sandbox blocks, and a report that silently loses one of its three inputs would be worse than one
 * that says which input is missing — so a missing gate capture is reported as missing, in the page,
 * in the same fail-safe direction the migration itself chose for an unreadable file.</p>
 *
 * <p>Usage (repository root):</p>
 * <pre>
 *   bun run scripts/rewrite-migration/generate-test-report.js \
 *     --module hipster-entity-tooling --module project-automation \
 *     --gate-out target/gate/verify-migration.txt \
 *     --out doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md
 * </pre>
 *
 * Flags: {@code --module <dir>} (repeatable; default: every reactor module with surefire reports),
 * {@code --gate-out <file>} (a captured gate run), {@code --benchmarks <file>} (default
 * {@code doc/brainstorm/rewrite-migration/07-testing/benchmarks/latest.json}), {@code --out <file>},
 * {@code --stdout} (print instead of writing).
 */
import { existsSync } from "node:fs";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";

const ROOT = path.resolve(import.meta.dir, "..", "..");
const DEFAULT_OUT = path.join(ROOT, "doc", "brainstorm", "rewrite-migration", "07-testing", "TEST-REPORT.md");
const DEFAULT_BENCHMARKS = path.join(ROOT,
    "doc", "brainstorm", "rewrite-migration", "07-testing", "benchmarks", "latest.json");

/** A repo-relative path with forward slashes, because it ends up in markdown and in links. */
const relative = target => path.relative(ROOT, target).split(path.sep).join("/");

export function parseArgs(argv) {
    const options = { modules: [], gateOut: null, benchmarks: DEFAULT_BENCHMARKS, out: DEFAULT_OUT, stdout: false };
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === "--module") options.modules.push(argv[++i]);
        else if (arg === "--gate-out") options.gateOut = path.resolve(ROOT, argv[++i]);
        else if (arg === "--benchmarks") options.benchmarks = path.resolve(ROOT, argv[++i]);
        else if (arg === "--out") options.out = path.resolve(ROOT, argv[++i]);
        else if (arg === "--stdout") options.stdout = true;
        else throw new Error(`Unknown argument: ${arg}`);
    }
    return options;
}

/**
 * One Surefire `<testsuite>` element, read with a regex rather than an XML parser.
 *
 * <p>Deliberate: the attributes are a fixed, shallow shape, and pulling an XML dependency into the
 * migration's own script directory would make the report depend on something the gate does not check.
 * The parser is strict about what it accepts — an element it cannot read yields null, so a malformed
 * or truncated report disappears into the "no reports" path instead of contributing a zero that makes
 * a suite look empty.</p>
 */
export function parseSurefireXml(text) {
    const match = /<testsuite\b([^>]*)>/.exec(text);
    if (!match) {
        return null;
    }
    const attributes = {};
    for (const pair of match[1].matchAll(/([\w:-]+)\s*=\s*"([^"]*)"/g)) {
        attributes[pair[1]] = pair[2];
    }
    const name = attributes.name ?? attributes.classname;
    if (!name) {
        return null;
    }
    const number = key => Number(attributes[key] ?? 0);
    return {
        name,
        tests: number("tests"),
        failures: number("failures"),
        errors: number("errors"),
        skipped: number("skipped"),
        timeMs: Math.round((Number(attributes.time ?? 0)) * 1000),
        // A nested-class suite reports as `Outer$Inner`; the report groups by the outer name so a
        // reader counts one test class, not however many shapes JUnit chose to report it in.
        outerClass: name.split("$")[0].split(".").at(-1),
        nested: name.includes("$"),
        // `\sname=` rather than `name=`: `classname="..."` contains `name="`, and a greedy match
        // would report the class as the failing case.
        failuresNamed: [...text.matchAll(/<testcase\b[^>]*\sname="([^"]+)"[^>]*>\s*<(failure|error)/g)]
            .map(found => found[1]),
    };
}

/** Totals over suites, with the nested-class rows folded into their outer class for display. */
export function summarise(suites) {
    const byClass = new Map();
    for (const suite of suites) {
        const row = byClass.get(suite.outerClass)
            ?? { name: suite.outerClass, module: suite.module, tests: 0, failures: 0, errors: 0,
                 skipped: 0, timeMs: 0, failuresNamed: [] };
        row.tests += suite.tests;
        row.failures += suite.failures;
        row.errors += suite.errors;
        row.skipped += suite.skipped;
        row.timeMs += suite.timeMs;
        row.failuresNamed.push(...suite.failuresNamed);
        byClass.set(suite.outerClass, row);
    }
    const rows = [...byClass.values()].sort((a, b) => a.name.localeCompare(b.name));
    const total = rows.reduce((sum, row) => sum + row.tests, 0);
    const troubled = rows.filter(row => row.failures > 0 || row.errors > 0);
    return { rows, total, troubled, suites: suites.length };
}

export async function collectSuites(modules) {
    const found = [];
    const missing = [];
    const empty = [];
    for (const module of modules) {
        const reportDir = path.join(ROOT, module, "target", "surefire-reports");
        if (!existsSync(reportDir)) {
            missing.push(module);
            continue;
        }
        const files = (await readdir(reportDir)).filter(name => name.startsWith("TEST-") && name.endsWith(".xml"));
        if (files.length === 0) {
            // A module that ran and found no tests writes the directory and no XML. Saying "no reports"
            // for it would read as "this module was not tested", which is a different and worse claim.
            empty.push(module);
            continue;
        }
        for (const file of files.sort()) {
            const suite = parseSurefireXml(await readFile(path.join(reportDir, file), "utf8"));
            if (suite) {
                found.push({ ...suite, module });
            }
        }
    }
    return { found, missing, empty };
}

/** Reactor modules that have a `target` directory, when the caller did not name any. */
export async function modulesWithReports() {
    const names = [];
    for (const entry of await readdir(ROOT, { withFileTypes: true })) {
        if (!entry.isDirectory() || entry.name.startsWith(".") || entry.name === "target" || entry.name === "doc") {
            continue;
        }
        if (existsSync(path.join(ROOT, entry.name, "target", "surefire-reports"))) {
            names.push(entry.name);
        }
    }
    return names.sort();
}

export function readGateResult(text) {
    if (!text) {
        return { present: false };
    }
    // The gate's own line shape: two spaces, the verdict word padded to six, then `check: detail`.
    const checks = [...text.matchAll(/^ {2}(OK|FAIL|WARN)\s+(\S+):\s+(.*)$/gm)]
        .map(found => ({ verdict: found[1], check: found[2], detail: found[3].trim() }));
    const line = text.split(/\r?\n/).find(candidate => candidate.startsWith("RESULT:"));
    return {
        present: true,
        verdict: line ?? "RESULT: never printed — the capture is truncated",
        passed: checks.filter(check => check.verdict === "OK").length,
        failing: checks.filter(check => check.verdict === "FAIL").length,
        warnings: checks.filter(check => check.verdict === "WARN").length,
        checks,
    };
}

export function renderReport({ generated, modules, summary, missingReports, emptyReports, gate, benchmarks }) {
    const lines = [];
    lines.push("# Phase 7 test report — JavaParser to OpenRewrite migration");
    lines.push("");
    lines.push(`Generated by \`scripts/rewrite-migration/generate-test-report.js\` on ${generated}.`);
    lines.push("Edit the inputs and re-run; editing this page would only make it disagree with them.");
    lines.push("");
    lines.push("## Build gate");
    lines.push("");
    if (!gate.present) {
        lines.push("**No captured gate run was supplied**, so this report says nothing about the migration");
        lines.push("gate. Produce one with `bun run scripts/rewrite-migration/verify-migration.js` and pass it");
        lines.push("in with `--gate-out`; the absence is stated rather than guessed at. Keep the capture out");
        lines.push("of `target/`, which the next `clean` build deletes — that is how this line gets printed.");
    } else {
        lines.push(`\`scripts/rewrite-migration/verify-migration.js\` — **${gate.verdict}**`);
        lines.push("");
        lines.push(`${gate.checks.length} checks: ${gate.passed} OK, ${gate.warnings} warn,`
            + ` ${gate.failing} fail.`);
        lines.push("");
        lines.push("| Check | Verdict | Detail |");
        lines.push("|---|---|---|");
        for (const check of gate.checks) {
            lines.push(`| \`${check.check}\` | ${check.verdict} | ${check.detail} |`);
        }
        if (gate.checks.length === 0) {
            lines.push("_The capture contained no check lines: it is either a different tool's output or "
                + "a truncated file._");
        }
    }
    lines.push("");
    lines.push("## Tests executed");
    lines.push("");
    lines.push(`Modules read: ${modules.map(name => `\`${name}\``).join(", ")}.`);
    if (missingReports.length > 0) {
        lines.push("");
        lines.push(`No Surefire reports found for: ${missingReports.map(name => `\`${name}\``).join(", ")}`
            + " — run those modules' tests before rendering this page, or they are silently absent from it.");
    }
    if (emptyReports.length > 0) {
        lines.push("");
        lines.push(`Ran with no test classes: ${emptyReports.map(name => `\`${name}\``).join(", ")}`
            + " — the module built and Surefire ran, and there was nothing to run.");
    }
    lines.push("");
    lines.push(`**${summary.total} tests across ${summary.rows.length} test classes`
        + ` (${summary.suites} Surefire suites), ${summary.troubled.length} classes with failures or errors.**`);
    lines.push("");
    lines.push("| Test class | Module | Tests | Failures | Errors | Skipped | Time |");
    lines.push("|---|---|---:|---:|---:|---:|---:|");
    for (const row of summary.rows) {
        lines.push(`| \`${row.name}\` | \`${row.module ?? ""}\` | ${row.tests} | ${row.failures} | ${row.errors}`
            + ` | ${row.skipped} | ${(row.timeMs / 1000).toFixed(2)} s |`);
    }
    if (summary.troubled.length > 0) {
        lines.push("");
        lines.push("### Failing cases");
        lines.push("");
        for (const row of summary.troubled) {
            for (const named of row.failuresNamed) {
                lines.push(`- \`${row.name}\` — ${named}`);
            }
            if (row.failuresNamed.length === 0) {
                lines.push(`- \`${row.name}\` — ${row.failures} failure(s), ${row.errors} error(s)`);
            }
        }
    }
    lines.push("");
    lines.push("## Benchmarks");
    lines.push("");
    if (!benchmarks.present) {
        lines.push(`No benchmark results at \`${benchmarks.path}\`. Produce them with`
            + " `bun run scripts/rewrite-migration/run-tooling-benchmarks.js`.");
    } else {
        lines.push(`From \`${benchmarks.path}\` (absolute baselines only: JavaParser left the build in`
            + " Phase 6, so there is no alternative to compare against). Interpretation is in");
        lines.push("[`benchmarks/BenchmarkReport.md`](benchmarks/BenchmarkReport.md).");
        lines.push("");
        lines.push("| Benchmark | Mode | Score | ± | Unit |");
        lines.push("|---|---|---:|---:|---|");
        for (const row of benchmarks.rows) {
            lines.push(`| \`${row.benchmark}\` | ${row.mode} | ${row.score} | ${row.error} | ${row.unit} |`);
        }
    }
    lines.push("");
    lines.push("## Reading this page");
    lines.push("");
    lines.push("The three sections are three different claims. The gate says the migration is complete in the");
    lines.push("sense the tracker defines. The test table says the code that replaced JavaParser executes and");
    lines.push("asserts, on this machine, in this build. The benchmarks say what the replacement costs.");
    lines.push("A number missing from any of them is stated as missing rather than left at zero, because a");
    lines.push("report that reads as complete when it is not is the failure mode this whole phase is about.");
    lines.push("");
    return lines.join("\n");
}

async function main() {
    const options = parseArgs(process.argv.slice(2));
    const modules = options.modules.length > 0 ? options.modules : await modulesWithReports();
    const { found, missing, empty } = await collectSuites(modules);
    if (found.length === 0) {
        console.error("No Surefire reports found. Run the tests first, e.g.\n"
                + `  cmd /c "scripts\\mvn-jdk25.cmd -o -pl ${modules[0] ?? "<module>"} -am test"`);
        process.exit(1);
    }
    const gate = readGateResult(options.gateOut && existsSync(options.gateOut)
        ? await readFile(options.gateOut, "utf8") : null);
    let benchmarks = { present: false, path: relative(options.benchmarks), rows: [] };
    if (existsSync(options.benchmarks)) {
        const rows = JSON.parse(await readFile(options.benchmarks, "utf8"));
        benchmarks = {
            present: rows.length > 0, path: relative(options.benchmarks),
            rows: rows.map(row => ({
                ...row,
                score: Number(row.score).toFixed(row.score >= 1000 ? 1 : 3),
                error: Number(row.error).toFixed(row.error >= 1000 ? 1 : 3),
            })),
        };
    }
    const generated = new Date().toISOString().replace("T", " ").slice(0, 16) + " UTC";
    const markdown = renderReport({
        generated, modules, missingReports: missing, emptyReports: empty,
        summary: summarise(found), gate, benchmarks,
    });
    if (options.stdout) {
        process.stdout.write(markdown);
        return;
    }
    await mkdir(path.dirname(options.out), { recursive: true });
    await writeFile(options.out, markdown);
    console.log(`Wrote ${relative(options.out)}: ${summarise(found).total} tests, `
            + `${found.length} suites, gate ${gate.present ? gate.verdict : "not supplied"}`);
}

if (import.meta.path === process.argv[1] || import.meta.main) {
    await main();
}
