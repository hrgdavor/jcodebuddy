#!/usr/bin/env bun
/**
 * check-test-lanes.js — the T2 contract for [`plans/incremental-test-selection.md`](../plans/incremental-test-selection.md).
 *
 * The plan's § 3 gives each test class a lane: `unit`, `component`, `generator`, `compiler` or
 * `completeness`. The tag on the class is the human-readable claim; the generated test index
 * (`.jcodebuddy/index/tests.json`, T1) is the measured record. A claim nobody checks is the one that rots,
 * so this script checks the two against each other and against the lane rule itself.
 *
 * **What it reads, and what it never does.** It requires the module's `tests.json` and runs the read-only
 * probe `bun scripts/test-lanes.js --json` over the compiled test classes. It does **not** compile, does
 * **not** run a test, and writes nothing: the only side effect of a contract check must be a verdict. The
 * index is derived and regenerable, so a missing one is answered with the command that makes it
 * (`bun scripts/test-index.js`), not with a silent pass.
 *
 * **The four agreements it enforces** — all of them are the plan's, not this script's invention:
 *
 *   1. **Measured `unit` ⇒ tagged `unit`.** A class the probe finds inside the ≤3-production-class rule
 *      must carry `@Tag("unit")`; if it does not, the lane shrinks silently.
 *   2. **Measured `component` ⇒ tagged `component`.** The same rule for the other side: a class that does
 *      not meet the strict rule must not claim to.
 *   3. **Measured heavy ⇒ carries that heavy tag.** `generator`, `compiler` and `completeness` are the
 *      lanes that never run in the affected/fast unit subset, so the tag is what keeps them out; a heavy
 *      class without it would leak into the fast lane.
 *   4. **No class may be both.** A `unit`/`component` tag on a heavy class is a contradiction: the lanes
 *      are mutually exclusive (§ 3), and a class in both would be selected by the affected lane *and*
 *      excluded by the fast lane's profile.
 *
 * **The unit threshold is checked from the index, too.** The probe reports the threshold, but the index is
 * what the selector (T3) will read, so a `unit` row whose recorded direct production deps exceed three is
 * a contract failure even when the probe happens to agree. Nested types count as their declaring
 * top-level class for the threshold, exactly as § 3 says, while the index keeps the exact FQN.
 *
 * **Every row must have a lane.** An untagged test class has no lane: it would be invisible to
 * `test-affected` and ambiguous to a reader. The completeness of the index is therefore part of the
 * contract rather than a nicety.
 *
 * Usage:
 *   bun scripts/check-test-lanes.js          human report; exit 0 clean, 1 violations
 *   bun scripts/check-test-lanes.js --json   a machine-readable object on stdout; duration on stderr
 *
 * The index path is the tooling module's own — the same one T1 generates — because that is the module the
 * plan's lanes are measured over. Exit codes: 0 clean; 1 violations or an unreadable index/measurement;
 * 2 usage error. The duration is the last line either way (`scripts/lib/timing.js`).
 */

import { existsSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { spawnSync } from 'node:child_process';

import { repoRoot } from './lib/toolchain.js';
import { reportDuration, stopwatch } from './lib/timing.js';

const MODULE = 'hipster-entity-tooling';
const INDEX_FILE = join(
  'hipster-entity',
  'hipster-entity-tooling',
  '.jcodebuddy',
  'index',
  'tests.json'
);
const TEST_LANES = join('scripts', 'test-lanes.js');
const MAX_UNIT_PRODUCTION_DEPS = 3;
const LANE_TAGS = new Set(['unit', 'component']);
const HEAVY_TAGS = new Set(['generator', 'compiler', 'completeness']);
const ALL_LANE_TAGS = new Set([...LANE_TAGS, ...HEAVY_TAGS]);

const USAGE = `check-test-lanes.js — the unit/component lane contract for ${MODULE} (plan T2)

  bun scripts/check-test-lanes.js          check the tags in the generated test index against the
                                           measured lanes; exit 0 clean, 1 violations
  bun scripts/check-test-lanes.js --json   a machine-readable object on stdout; duration on stderr
  bun scripts/check-test-lanes.js --help   this text

Requires hipster-entity/hipster-entity-tooling/.jcodebuddy/index/tests.json (run
\`bun scripts/test-index.js\` when it is missing). It runs the read-only probe
\`bun scripts/test-lanes.js --json\`; it compiles nothing, runs no test and writes nothing.`;

function parse(argv) {
  const options = { json: false, help: false };
  for (const arg of argv) {
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--json') {
      options.json = true;
    } else {
      return { error: `unknown argument '${arg}'` };
    }
  }
  return { options };
}

/** Nested classes count as their declaring top-level class for the unit threshold (plan § 3). */
function topLevel(fqn) {
  return fqn.split('$')[0];
}

/** The distinct direct production classes a `tests.json` row names, collapsed for the threshold. */
function productionClassCount(row) {
  const deps = Array.isArray(row.productionDeps) ? row.productionDeps : [];
  return new Set(deps.map(topLevel)).size;
}

/** The lane tags a `tests.json` row carries, split by kind. */
function tagsOf(row) {
  const tags = Array.isArray(row.tags) ? row.tags : [];
  return {
    all: tags,
    lane: tags.filter((tag) => LANE_TAGS.has(tag)),
    heavy: tags.filter((tag) => HEAVY_TAGS.has(tag)),
  };
}

/**
 * Runs the T0 probe and returns its JSON. `test-lanes --json` keeps its duration on stderr, so stdout is
 * the object and nothing else; a non-zero exit is the probe reporting that there is nothing to measure
 * (usually: the tests have not been compiled), which is a contract failure rather than a pass.
 */
function measure(root) {
  const result = spawnSync(process.execPath, [TEST_LANES, '--json', '--module', MODULE], {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    windowsHide: true,
  });
  if (result.error) {
    return { error: `could not run ${TEST_LANES}: ${result.error.message}` };
  }
  if (result.status !== 0) {
    const detail = (result.stderr || '').trim();
    return {
      error: `${TEST_LANES} failed (exit ${result.status})${detail ? `: ${detail.split(/\r?\n/)[0]}` : ''}`,
    };
  }
  try {
    return { lanes: JSON.parse(result.stdout) };
  } catch (failure) {
    return { error: `${TEST_LANES} --json did not print valid JSON: ${failure.message}` };
  }
}

/**
 * The contract itself. Pure over its two inputs so the report path and the JSON path cannot disagree.
 *
 * @param {object} index the parsed `.jcodebuddy/index/tests.json`
 * @param {object} lanes the parsed `test-lanes.js --json` object
 * @returns {{problems: object[], counts: object}}
 */
function check(index, lanes) {
  const problems = [];
  const add = (kind, name, message) => problems.push({ kind, class: name, message });

  const rows = index && typeof index.tests === 'object' && index.tests !== null ? index.tests : {};
  const measuredClasses = Array.isArray(lanes.classes) ? lanes.classes : [];
  const measuredByName = new Map(measuredClasses.map((entry) => [entry.name, entry]));

  const counts = {
    rows: Object.keys(rows).length,
    measured: measuredClasses.length,
    unit: 0,
    component: 0,
    generator: 0,
    compiler: 0,
    completeness: 0,
  };

  for (const [name, row] of Object.entries(rows)) {
    const tags = tagsOf(row);

    // Every row must name a lane. `no lane tag` is the untagged row: nothing selects it, nothing
    // excludes it, and a reader cannot tell what it is.
    if (!tags.all.some((tag) => ALL_LANE_TAGS.has(tag))) {
      add('no-lane-tag', name, 'no lane tag (expected one of unit/component/generator/compiler/completeness)');
    }

    // One lane per class: `unit` and `component` are two different lanes, and either one is a
    // contradiction with a heavy tag (§ 3).
    if (tags.lane.length > 1) {
      add('multiple-lane-tags', name, `carries both '${tags.lane.join("' and '")}'`);
    }
    if (tags.lane.length > 0 && tags.heavy.length > 0) {
      add(
        'lane-and-heavy-tag',
        name,
        `carries a lane tag ('${tags.lane.join("', '")}') and a heavy tag ('${tags.heavy.join("', '")}')`
      );
    }

    // The unit threshold is a property of the row the selector will read, not only of the probe.
    if (tags.lane.includes('unit') && productionClassCount(row) > MAX_UNIT_PRODUCTION_DEPS) {
      add(
        'unit-over-threshold',
        name,
        `${productionClassCount(row)} direct production classes > ${MAX_UNIT_PRODUCTION_DEPS} for the unit lane`
      );
    }

    const measured = measuredByName.get(name);
    if (!measured) {
      add('not-measured', name, 'the compiled test classes have no measured lane for this index row (regenerate the index)');
      continue;
    }
    counts[measured.lane] = (counts[measured.lane] ?? 0) + 1;

    if (measured.lane === 'unit' && !tags.all.includes('unit')) {
      add('measured-unit-untagged', name, "measured 'unit' but not tagged unit");
    }
    if (measured.lane === 'component' && !tags.all.includes('component')) {
      add('measured-component-untagged', name, "measured 'component' but not tagged component");
    }
    if (HEAVY_TAGS.has(measured.lane) && !tags.all.includes(measured.lane)) {
      add('heavy-untagged', name, `measured '${measured.lane}' but not tagged ${measured.lane}`);
    }
  }

  // A compiled test class the index does not know is a stale index: the selector would never see it.
  for (const entry of measuredClasses) {
    if (!Object.prototype.hasOwnProperty.call(rows, entry.name)) {
      add('missing-index-row', entry.name, 'measured test class has no row in tests.json (regenerate the index)');
    }
  }

  return { problems, counts };
}

function main() {
  const elapsed = stopwatch();
  const { options, error } = parse(process.argv.slice(2));
  if (error) {
    console.error(`check-test-lanes: ${error}`);
    console.error(USAGE);
    return 2;
  }
  if (options.help) {
    console.log(USAGE);
    return 0;
  }

  const root = repoRoot();
  const indexPath = join(root, INDEX_FILE);
  const show = relative(root, indexPath).split('\\').join('/');
  if (!existsSync(indexPath)) {
    console.error(`check-test-lanes: ${show} is missing — generate it first: bun scripts/test-index.js`);
    console.error(reportDuration(elapsed(), { what: 'test lanes checked' }));
    return 1;
  }

  let index;
  try {
    index = JSON.parse(readFileSync(indexPath, 'utf8'));
  } catch (failure) {
    console.error(`check-test-lanes: ${show} is not readable JSON: ${failure.message}`);
    console.error(reportDuration(elapsed(), { what: 'test lanes checked' }));
    return 1;
  }
  if (typeof index.tests !== 'object' || index.tests === null) {
    console.error(`check-test-lanes: ${show} has no 'tests' table — regenerate it: bun scripts/test-index.js`);
    console.error(reportDuration(elapsed(), { what: 'test lanes checked' }));
    return 1;
  }

  const measured = measure(root);
  if (measured.error) {
    console.error(`check-test-lanes: ${measured.error}`);
    console.error(reportDuration(elapsed(), { what: 'test lanes checked' }));
    return 1;
  }

  const { problems, counts } = check(index, measured.lanes);
  const durationMs = Math.round(elapsed() * 10) / 10;

  if (options.json) {
    process.stdout.write(
      `${JSON.stringify(
        {
          format: 1,
          module: MODULE,
          index: show,
          counts,
          problems,
          ok: problems.length === 0,
          durationMs,
        },
        null,
        2
      )}\n`
    );
    console.error(reportDuration(elapsed(), { what: 'test lanes checked', files: counts.rows }));
    return problems.length === 0 ? 0 : 1;
  }

  console.log(`check-test-lanes — the unit/component lane contract for ${MODULE} (read-only)`);
  console.log('');
  console.log(`  index:    ${show}`);
  console.log(`  measured: ${counts.measured} compiled test classes (bun scripts/test-lanes.js --json)`);
  console.log('');
  for (const problem of problems) {
    console.log(`  FAIL ${problem.class}: ${problem.message}`);
  }
  if (problems.length > 0) {
    console.log('');
  }
  console.log(
    `  ${counts.rows} test row(s): ${counts.unit} unit, ${counts.component} component, ` +
      `${counts.generator} generator, ${counts.compiler} compiler, ${counts.completeness} completeness ` +
      `(measured lanes)`
  );
  console.log(
    problems.length === 0
      ? '  OK: every measured lane matches its tag, every unit row is within the threshold, and every test has a lane'
      : `  ${problems.length} problem(s): the tags and the measured lanes disagree`
  );
  console.log(reportDuration(elapsed(), { what: 'test lanes checked', files: counts.rows }));
  return problems.length === 0 ? 0 : 1;
}

process.exit(main());
