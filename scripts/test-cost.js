#!/usr/bin/env bun
/**
 * test-cost.js — where the test time went, read from Surefire's own reports.
 *
 * The plan [`plans/faster-java-tests.md`](../plans/faster-java-tests.md) starts from a measurement, and this is the
 * tool that takes it. It reads `target/surefire-reports/TEST-*.xml` for the requested modules and prints the
 * per-module totals, the slowest classes and the slowest individual methods. It writes nothing, runs no Maven and
 * parses no Java source: the report is the authority on class time, and a lane assignment is a tag on the test
 * source (F1), not something this script guesses from a class name.
 *
 * A report is evidence about the run that wrote it. Re-run the tests before trusting a stale tree, and read the
 * duration line at the bottom like every other utility here (`AGENTS.md` § 2, `scripts/lib/timing.js`).
 *
 * Usage:
 *   bun scripts/test-cost.js                                  the hipster-entity module set
 *   bun scripts/test-cost.js --module hipster-entity-tooling  one module (repeatable)
 *   bun scripts/test-cost.js --json                           a machine-readable object
 *   bun scripts/test-cost.js --top 20                         more (or fewer) rows in both rankings
 *
 * The module name is the directory under `hipster-entity/`; reports live in
 * `hipster-entity/<module>/target/surefire-reports`. A requested module with no reports is an error (exit 1),
 * because a missing directory would otherwise read as "no slow tests". With `--json`, stdout is exactly the JSON
 * object and the duration line goes to stderr, so a consumer can pipe stdout into a parser.
 *
 * Exit codes: 0 reports read; 1 a requested module has no usable reports; 2 a usage error.
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { repoRoot } from './lib/toolchain.js';
import { reportDuration, stopwatch } from './lib/timing.js';

/**
 * The module set the plan's § 1 baseline measured. It is written out rather than derived from `GATE_MODULES`
 * because `hipster-entity-example` is in the gate for its compile and has no test reports at all: a default that
 * demanded its reports would fail on a healthy checkout.
 */
const DEFAULT_MODULES = [
  'hipster-entity-tooling',
  'hipster-entity-core',
  'hipster-entity-jackson',
  'hipster-entity-test',
  'hipster-entity-api',
];

const MODULE_GROUP = 'hipster-entity';
const DEFAULT_TOP = 10;

const USAGE = `test-cost.js — surefire class time per module (the recorded gate is mvn-jdk25.js)

  bun scripts/test-cost.js                                  the hipster-entity module set
  bun scripts/test-cost.js --module hipster-entity-tooling  one module (repeatable)
  bun scripts/test-cost.js --json                           a machine-readable object on stdout
  bun scripts/test-cost.js --top 20                         rows in each ranking (default ${DEFAULT_TOP})

Reads hipster-entity/<module>/target/surefire-reports/TEST-*.xml. It runs no Maven and
parses no Java; re-run the tests before trusting reports from an old tree.`;

/** Reads an attribute from an XML start tag; Surefire writes plain names, so no XML parser is warranted. */
function attribute(tag, name) {
  const match = tag.match(new RegExp(`\\b${name}="([^"]*)"`));
  return match ? decodeXml(match[1]) : null;
}

/** The five entities an attribute value can use. Only a report name could carry one, but decoding is free. */
function decodeXml(value) {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function numberAttribute(tag, name) {
  const value = Number.parseFloat(attribute(tag, name) ?? '');
  return Number.isFinite(value) ? value : 0;
}

/** Surefire writes three decimals; rounding keeps a summed float from leaking into the JSON. */
function round3(value) {
  return Math.round(value * 1000) / 1000;
}

/**
 * Removes the blocks whose CDATA could contain text that looks like a testcase or a testsuite. Surefire escapes
 * nothing inside `<system-out>`, and a test that prints an XML sample would otherwise be counted as a test method.
 */
function stripEmbeddedMarkup(xml) {
  return xml
    .replace(/<properties\b[^>]*>[\s\S]*?<\/properties>/g, '')
    .replace(/<system-out\b[^>]*>[\s\S]*?<\/system-out>/g, '')
    .replace(/<system-err\b[^>]*>[\s\S]*?<\/system-err>/g, '');
}

/**
 * One module's reports: its classes (one `TEST-*.xml` each), tests, seconds, and every test method with a time.
 *
 * @returns {{module: string, classes: object[], methods: object[], tests: number, seconds: number,
 *            failures: number, errors: number, skipped: number, reportDir: string}}
 */
function readModule(root, module) {
  const reportDir = join(root, MODULE_GROUP, module, 'target', 'surefire-reports');
  const files = readdirSync(reportDir)
    .filter((name) => name.startsWith('TEST-') && name.endsWith('.xml'))
    .sort();

  const classes = [];
  const methods = [];
  let tests = 0;
  let seconds = 0;
  let failures = 0;
  let errors = 0;
  let skipped = 0;

  for (const file of files) {
    const xml = stripEmbeddedMarkup(readFileSync(join(reportDir, file), 'utf8'));
    const suiteTag = xml.match(/<testsuite\b[^>]*>/);
    if (!suiteTag) {
      continue;
    }

    const suite = suiteTag[0];
    const className = attribute(suite, 'name') ?? file.replace(/^TEST-/, '').replace(/\.xml$/, '');
    const classTests = numberAttribute(suite, 'tests');
    const classSeconds = round3(numberAttribute(suite, 'time'));
    const classFailures = numberAttribute(suite, 'failures');
    const classErrors = numberAttribute(suite, 'errors');
    const classSkipped = numberAttribute(suite, 'skipped');

    classes.push({
      module,
      className,
      tests: classTests,
      seconds: classSeconds,
      failures: classFailures,
      errors: classErrors,
      skipped: classSkipped,
    });
    tests += classTests;
    seconds += classSeconds;
    failures += classFailures;
    errors += classErrors;
    skipped += classSkipped;

    for (const tag of xml.match(/<testcase\b[^>]*>/g) ?? []) {
      // `<testcase>` keeps the full display name (a parameterised test carries `[…]`); `name` is the method.
      methods.push({
        module,
        className: attribute(tag, 'classname') ?? className,
        method: attribute(tag, 'name') ?? '(unnamed)',
        seconds: round3(numberAttribute(tag, 'time')),
      });
    }
  }

  return {
    module,
    classes,
    methods,
    tests,
    seconds: round3(seconds),
    failures,
    errors,
    skipped,
    reportDir: relative(root, reportDir).split('\\').join('/'),
  };
}

function usage() {
  console.log(USAGE);
}

/**
 * Parses this script's own options. A value that looks like a path is refused: the module name is a directory
 * under `hipster-entity/`, and `../…` would silently read another module's report or nothing at all.
 */
function parse(argv) {
  const options = { modules: [], json: false, top: DEFAULT_TOP, help: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--json') {
      options.json = true;
    } else if (arg === '--module') {
      const value = argv[++i];
      if (!value) return { error: '--module needs a module name (the directory under hipster-entity/)' };
      options.modules.push(value);
    } else if (arg.startsWith('--module=')) {
      options.modules.push(arg.slice('--module='.length));
    } else if (arg === '--top') {
      const value = argv[++i];
      if (!value) return { error: '--top needs a positive number' };
      options.top = Number.parseInt(value, 10);
    } else if (arg.startsWith('--top=')) {
      options.top = Number.parseInt(arg.slice('--top='.length), 10);
    } else {
      return { error: `unknown argument '${arg}'` };
    }
  }

  if (options.modules.length === 0) {
    options.modules = [...DEFAULT_MODULES];
  } else {
    // Repeating a module is a harmless typo, not a request to count it twice.
    options.modules = [...new Set(options.modules)];
  }
  if (!Number.isInteger(options.top) || options.top < 1) {
    return { error: '--top needs a positive number' };
  }
  const badModule = options.modules.find((name) => /[\\/]|\.\./.test(name) || name.length === 0);
  if (badModule !== undefined) {
    return { error: `'${badModule}' is not a module name: give the directory under hipster-entity/, without a path` };
  }
  return { options };
}

function seconds(value) {
  return `${value.toFixed(1)}s`;
}

function padLeft(value, width) {
  return String(value).padStart(width);
}

function padRight(value, width) {
  return String(value).padEnd(width);
}

function ranking(title, rows, render) {
  console.log(`\n${title}`);
  if (rows.length === 0) {
    console.log('  (none)');
    return;
  }
  const secondsWidth = Math.max(7, ...rows.map((row) => seconds(row.seconds).length));
  const moduleWidth = Math.max(6, ...rows.map((row) => row.module.length));
  for (const row of rows) {
    console.log(
      `  ${padLeft(seconds(row.seconds), secondsWidth)}  ${padRight(row.module, moduleWidth)}  ${render(row)}`
    );
  }
}

/**
 * Human output: the modules first, because that is the plan's table, then the two rankings.
 *
 * `classes` and `methods` are the full sorted lists; `top` limits only the rows shown, never the total the heading
 * reports — "top 6 of 83" says both what you are seeing and how much there is.
 */
function report(modules, classes, methods, top) {
  const totals = modules.reduce(
    (sum, module) => ({
      classes: sum.classes + module.classes.length,
      tests: sum.tests + module.tests,
      seconds: sum.seconds + module.seconds,
      failures: sum.failures + module.failures,
      errors: sum.errors + module.errors,
      skipped: sum.skipped + module.skipped,
    }),
    { classes: 0, tests: 0, seconds: 0, failures: 0, errors: 0, skipped: 0 }
  );
  totals.seconds = round3(totals.seconds);

  const moduleWidth = Math.max('Module'.length, ...modules.map((module) => module.module.length));
  const secondsWidth = Math.max('Time'.length, seconds(totals.seconds).length);

  console.log('test-cost — class time from target/surefire-reports');
  console.log('');
  console.log(`  ${padRight('Module', moduleWidth)}  ${padLeft('Classes', 7)}  ${padLeft('Tests', 5)}  ${padLeft('Time', secondsWidth)}`);
  for (const module of modules) {
    console.log(
      `  ${padRight(module.module, moduleWidth)}  ${padLeft(module.classes.length, 7)}  ` +
        `${padLeft(module.tests, 5)}  ${padLeft(seconds(module.seconds), secondsWidth)}`
    );
  }
  console.log(
    `  ${padRight('Total', moduleWidth)}  ${padLeft(totals.classes, 7)}  ` +
      `${padLeft(totals.tests, 5)}  ${padLeft(seconds(totals.seconds), secondsWidth)}`
  );

  const problems = [];
  if (totals.failures > 0) problems.push(`${totals.failures} failure(s)`);
  if (totals.errors > 0) problems.push(`${totals.errors} error(s)`);
  if (totals.skipped > 0) problems.push(`${totals.skipped} skipped`);
  if (problems.length > 0) {
    console.log(`\n  Reports carry ${problems.join(', ')} — these are times, not a verdict on the run.`);
  }

  ranking(
    `Slowest classes (top ${Math.min(top, classes.length)} of ${classes.length})`,
    classes.slice(0, top),
    (row) => `${row.className}  (${row.tests} test${row.tests === 1 ? '' : 's'})`
  );
  ranking(
    `Slowest methods (top ${Math.min(top, methods.length)} of ${methods.length})`,
    methods.slice(0, top),
    (row) => `${row.className.split('.').pop()}.${row.method}`
  );
}

/** The machine-readable object. Seconds carry Surefire's three decimals, so a consumer need not round. */
function toJson(modules, slowestClasses, slowestMethods, durationMs) {
  const totals = modules.reduce(
    (sum, module) => ({
      modules: sum.modules + 1,
      classes: sum.classes + module.classes.length,
      tests: sum.tests + module.tests,
      seconds: sum.seconds + module.seconds,
      failures: sum.failures + module.failures,
      errors: sum.errors + module.errors,
      skipped: sum.skipped + module.skipped,
    }),
    { modules: 0, classes: 0, tests: 0, seconds: 0, failures: 0, errors: 0, skipped: 0 }
  );
  totals.seconds = round3(totals.seconds);

  return {
    modules: modules.map((module) => ({
      module: module.module,
      reportDir: module.reportDir,
      classes: module.classes.length,
      tests: module.tests,
      seconds: module.seconds,
      failures: module.failures,
      errors: module.errors,
      skipped: module.skipped,
    })),
    totals,
    slowestClasses: slowestClasses.map((row) => ({
      module: row.module,
      className: row.className,
      tests: row.tests,
      seconds: row.seconds,
    })),
    slowestMethods: slowestMethods.map((row) => ({
      module: row.module,
      className: row.className,
      method: row.method,
      seconds: row.seconds,
    })),
    durationMs,
  };
}

function bySecondsThenName(left, right) {
  return right.seconds - left.seconds || left.className.localeCompare(right.className);
}

function main() {
  const elapsed = stopwatch();
  const { options, error } = parse(process.argv.slice(2));
  if (error) {
    console.error(`test-cost: ${error}`);
    console.error(USAGE);
    return 2;
  }
  if (options.help) {
    usage();
    return 0;
  }

  const root = repoRoot();
  const missing = options.modules
    .map((module) => {
      const reportDir = join(root, MODULE_GROUP, module, 'target', 'surefire-reports');
      if (!existsSync(reportDir)) {
        return { module, reportDir, reason: 'no such report directory' };
      }
      const reports = readdirSync(reportDir).filter((name) => name.startsWith('TEST-') && name.endsWith('.xml'));
      return reports.length === 0 ? { module, reportDir, reason: 'no TEST-*.xml reports in it' } : null;
    })
    .filter(Boolean);

  if (missing.length > 0) {
    console.error('test-cost: no usable surefire reports for:');
    for (const entry of missing) {
      console.error(`  ${entry.module}: ${relative(root, entry.reportDir).split('\\').join('/')} (${entry.reason})`);
    }
    console.error('  Run that module\'s tests first, for example: bun scripts/mvn-jdk25.js hipster-entity test');
    // On the error path the duration goes to stderr too, so it is the last line of the failure report.
    console.error(reportDuration(elapsed(), { what: 'test costs read' }));
    return 1;
  }

  const modules = options.modules.map((module) => readModule(root, module));
  const classes = modules.flatMap((module) => module.classes).sort(bySecondsThenName);
  const methods = modules
    .flatMap((module) => module.methods)
    .sort((left, right) => bySecondsThenName(left, right) || left.method.localeCompare(right.method));

  const slowestClasses = classes.slice(0, options.top);
  const slowestMethods = methods.slice(0, options.top);

  if (options.json) {
    // stdout stays exactly one JSON object; the repository's duration rule is kept on stderr so a consumer can
    // still pipe this program's stdout into a parser.
    const durationMs = Math.round(elapsed() * 10) / 10;
    process.stdout.write(`${JSON.stringify(toJson(modules, slowestClasses, slowestMethods, durationMs), null, 2)}\n`);
    console.error(reportDuration(elapsed(), { what: 'test costs read', files: classes.length }));
    return 0;
  }

  report(modules, classes, methods, options.top);
  console.log('');
  console.log(reportDuration(elapsed(), { what: 'test costs read', files: classes.length }));
  return 0;
}

process.exit(main());
