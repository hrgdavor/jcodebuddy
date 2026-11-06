#!/usr/bin/env bun
/**
 * test-lanes.js — the T0 evidence for [`plans/incremental-test-selection.md`](../plans/incremental-test-selection.md).
 *
 * The plan starts by measuring, not by guessing: for every test class in a module it wants the class name, the
 * `@Tag` values, the **direct production** project classes the compiled class names, the **direct test-support**
 * project classes it names, and the lane § 3's rule would put it in. This is that measurement, and it is
 * **read-only**: it runs no Maven, compiles nothing, writes nothing and never touches a test or a source file.
 *
 * **Why the compiled classes, not the sources.** An import scan misses a fully-qualified use, a static import and a
 * nested type; the class file's constant pool is what § 4 makes the test index read, so the probe reads the same
 * thing the index will. `javap -v` from the JDK the build compiles with prints the pool and the runtime-visible
 * annotations; a dependency is **production** when its class file exists under some project module's
 * `target/classes`, and **test-support** when it exists under some module's `target/test-classes`. Everything else
 * (JDK, JUnit, third-party jars) is simply not in either set, so it cannot leak into a count — the exclusion is a
 * property of the classification rather than a package-name filter that somebody has to maintain.
 *
 * **The lane rule (§ 3).** A `completeness` tag wins, then `compiler`, then `generator` — a class carrying more than
 * one of them is reported under the later stage, because that is the stricter thing it does. Without a heavy tag a
 * class is a candidate `unit` when it has at most **three** direct production classes, names none of the four
 * forbidden integration surfaces (`CompileHarness`, `ExamplePassFixture`, `EntityMetadataGenerator`,
 * `SourceReader`), and uses none of the file/resource/environment APIs the index cannot see (`Class.forName`,
 * `Class.getResource*`, `Files.read*`, `System.getProperty/setProperty/getenv`). Anything else untagged is a
 * `component`. `unclassified` is a safety net: it is what a test class whose compiled class file could not be read
 * is reported as, so a broken measurement is visible rather than silently counted as `component`.
 *
 * A **production dependency is collapsed to its top-level class** for the count, as § 3 says ("nested types count as
 * their declaring top-level class for the threshold"), and a test's **nested classes are part of it**: a `@Nested`
 * test class is run as part of its outer class, so its dependencies and its test methods belong to the outer row.
 * The exact FQNs are still named in `--json`.
 *
 * **Why a walk of its own.** `scripts/lib/file-walk/` is the repository's walker, and it deliberately does not enter
 * a `.gitignore`d directory. The compiled output this probe must read is `target/`, which *is* ignored, so that
 * walker cannot see the thing being measured. This is not a file-search walk at all: it is a lookup of module roots
 * (directories holding a `pom.xml`) and then of exactly two paths under each — no skip list beyond the directories
 * that cannot hold a module.
 *
 * Usage:
 *   bun scripts/test-lanes.js                                  hipster-entity-tooling
 *   bun scripts/test-lanes.js --module hipster-entity-tooling  one module under hipster-entity/
 *   bun scripts/test-lanes.js --json                           a machine-readable object on stdout
 *
 * With `--json`, stdout is exactly the JSON object and the duration line goes to stderr, so a consumer can pipe
 * stdout into a parser (the same convention as `scripts/test-cost.js`). Exit codes: 0 measured; 1 the module has no
 * compiled test classes; 2 a usage error. The duration is the last line either way (`scripts/lib/timing.js`).
 */

import { existsSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { spawnSync } from 'node:child_process';
import { repoRoot, resolveJdk25 } from './lib/toolchain.js';
import { reportDuration, stopwatch } from './lib/timing.js';

const MODULE_GROUP = 'hipster-entity';
const DEFAULT_MODULE = 'hipster-entity-tooling';
const DEFAULT_MAX_PRODUCTION = 3;
const LANES = ['unit', 'component', 'generator', 'compiler', 'completeness', 'unclassified'];
const HEAVY_TAGS = ['completeness', 'compiler', 'generator'];
const LANE_TAGS = new Set(['unit', 'component']);

/** The integration surfaces § 3 names by simple name; they disqualify a class from `unit` whatever the count. */
const FORBIDDEN_SURFACES = new Set([
  'CompileHarness',
  'ExamplePassFixture',
  'EntityMetadataGenerator',
  'SourceReader',
]);

/**
 * The calls the test index cannot see (§ 3, and the T2 contract list). Each is `owner simple name` + a method
 * predicate; the read-only set is what the plan calls a file/resource/environment dependency.
 */
const IO_SURFACES = [
  { owner: 'Files', method: /^read/ },
  { owner: 'Class', method: /^(forName|getResource|getResourceAsStream)$/ },
  { owner: 'ClassLoader', method: /^getResource/ },
  { owner: 'System', method: /^(getProperty|setProperty|getProperties|setProperties|getenv)$/ },
];

/**
 * Directories that cannot hold a Maven module root in this checkout. `target` is skipped because a module root is
 * never inside one; `proto` because those are other repositories that are not part of this reactor; `.tmp` because
 * it is scratch (and holds copies of this tree).
 */
const SKIP_DIRS = new Set([
  '.git',
  '.idea',
  '.jcodebuddy',
  '.jsx6',
  '.kilo',
  '.mvn',
  '.mvn-local-repo',
  '.mvnd-home',
  'node_modules',
  'proto',
  'target',
  '.tmp',
]);

const USAGE = `test-lanes.js — candidate lanes from the compiled test classes (read-only, no Maven)

  bun scripts/test-lanes.js                                  ${DEFAULT_MODULE}
  bun scripts/test-lanes.js --module hipster-entity-tooling  one module under ${MODULE_GROUP}/
  bun scripts/test-lanes.js --json                           a machine-readable object on stdout

Reads <module>/target/test-classes with \`javap -v\` (JDK 25) and classifies each test class's direct
project dependencies against every module's target/classes and target/test-classes. It compiles
nothing, writes nothing and changes no test. Reports the lane summary, the production-dependency
histogram and the low-dependency classes currently hidden inside a heavy tag.`;

/** Command-line parsing, matching the shape of `scripts/test-cost.js`: a module name is a directory, not a path. */
function parse(argv) {
  const options = { module: DEFAULT_MODULE, json: false, help: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--json') {
      options.json = true;
    } else if (arg === '--module') {
      const value = argv[++i];
      if (!value) return { error: '--module needs a module name (the directory under hipster-entity/)' };
      options.module = value;
    } else if (arg.startsWith('--module=')) {
      options.module = arg.slice('--module='.length);
    } else {
      return { error: `unknown argument '${arg}'` };
    }
  }
  if (/[\\/]|\.\./.test(options.module) || options.module.length === 0) {
    return { error: `'${options.module}' is not a module name: give the directory under ${MODULE_GROUP}/, without a path` };
  }
  return { options };
}

/** Every directory visible in the checkout that holds a `pom.xml`, i.e. a Maven module root. */
function moduleRoots(root) {
  const out = [];
  const visit = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    if (entries.some((entry) => entry.isFile() && entry.name === 'pom.xml')) {
      out.push(dir);
    }
    for (const entry of entries) {
      if (entry.isDirectory() && !SKIP_DIRS.has(entry.name)) {
        visit(join(dir, entry.name));
      }
    }
  };
  visit(root);
  return out;
}

/** The fully qualified names of every class file under one `target/*-classes` directory. */
function classNamesIn(dir) {
  const names = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) {
        visit(full);
      } else if (entry.name.endsWith('.class')) {
        names.push(relative(dir, full).replace(/\\/g, '/').replace(/\.class$/, '').replace(/\//g, '.'));
      }
    }
  };
  try {
    visit(dir);
  } catch {
    /* no compiled output for this module: it contributes nothing to either set */
  }
  return names;
}

/** The JDK 25 `javap`, resolved and version-checked like every other tool that runs repository Java. */
function resolveJavap() {
  const jdk = resolveJdk25();
  const executable = join(jdk.home, 'bin', process.platform === 'win32' ? 'javap.exe' : 'javap');
  if (!existsSync(executable)) {
    throw new Error(`no javap in ${jdk.home}/bin (needed to read the compiled test classes)`);
  }
  return { javap: executable, home: jdk.home };
}

/**
 * Runs `javap -v` over every class file, in batches that stay well under a Windows command line, and splits the
 * output back into one chunk per class. `this_class` is read from the output rather than from the path, so the
 * chunks need no path bookkeeping.
 */
function javapClasses(javap, classFiles) {
  const chunks = [];
  let batch = [];
  let chars = 0;
  const flush = () => {
    if (batch.length === 0) return;
    const result = spawnSync(javap, ['-v', ...batch], {
      encoding: 'utf8',
      maxBuffer: 512 * 1024 * 1024,
      windowsHide: true,
    });
    if (result.status !== 0) {
      throw new Error(`javap failed (${result.status}): ${(result.stderr || result.error || '').toString().slice(0, 2000)}`);
    }
    chunks.push(...result.stdout.split(/^Classfile /m).slice(1));
    batch = [];
    chars = 0;
  };
  for (const file of classFiles) {
    batch.push(file);
    chars += file.length + 1;
    if (batch.length >= 60 || chars >= 18_000) flush();
  }
  flush();
  return chunks;
}

const TEST_METHOD_ANNOTATION = /^\s{8}org\.junit\.jupiter\.(?:api|params)\.(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)(?:\(|$)/;
const TAG_ANNOTATION = /org\.junit\.jupiter\.api\.Tag\(\s*value="([^"]*)"/g;
const CLASS_ENTRY = /^\s*#\d+ = Class\s+#\d+\s+\/\/ (\S+)/;
const MEMBER_ENTRY = /^\s*#\d+ = (?:Methodref|InterfaceMethodref)\s+#\d+\.#\d+\s+\/\/ (\S+?)\.([^.:]+):/;

/** One class file's facts: its own name, class-level tags, test-method count, pool classes and member references. */
function readClass(chunk) {
  const lines = chunk.split(/\r?\n/);
  const thisClass = lines.find((line) => /^\s*this_class: #\d+\s+\/\/ /.test(line));
  if (!thisClass) return null;
  const fqn = thisClass.match(/\/\/ (\S+)/)[1].replace(/\//g, '.');

  const classNames = new Set();
  const members = [];
  for (const line of lines) {
    const classEntry = line.match(CLASS_ENTRY);
    if (classEntry) {
      const raw = classEntry[1];
      // An array class entry (`[Lpkg/Type;`) is a use of `pkg.Type`, not a class named `[L...`.
      const array = raw.match(/^\[+(?:L([^;]+);)?$/);
      if (array) {
        if (array[1]) classNames.add(array[1].replace(/\//g, '.'));
      } else if (!raw.startsWith('[')) {
        classNames.add(raw.replace(/\//g, '.'));
      }
      continue;
    }
    const memberEntry = line.match(MEMBER_ENTRY);
    if (memberEntry) members.push({ owner: memberEntry[1].replace(/\//g, '.'), method: memberEntry[2] });
  }

  // The class-level block is the unindented one javap prints after the class body; method blocks are indented four.
  const classAnnotationBlocks = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i] === 'RuntimeVisibleAnnotations:') {
      let end = i + 1;
      while (end < lines.length && !/^\S/.test(lines[end])) end++;
      classAnnotationBlocks.push(lines.slice(i + 1, end).join('\n'));
    }
  }
  const tags = [
    ...new Set(
      classAnnotationBlocks.flatMap((block) => [...block.matchAll(TAG_ANNOTATION)].map((match) => match[1]))
    ),
  ].sort();

  const testMethods = lines.filter((line) => TEST_METHOD_ANNOTATION.test(line)).length;

  return { fqn, tags, testMethods, classNames, members };
}

const topLevel = (fqn) => fqn.split('$')[0];

/** The module's test-classes directory, or null when it has not been compiled. */
function testClassesDir(root, module) {
  const dir = join(root, MODULE_GROUP, module, 'target', 'test-classes');
  return existsSync(dir) ? dir : null;
}

/** Every `.class` under a directory, recursively. */
function classFilesUnder(dir) {
  const files = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) visit(full);
      else if (entry.name.endsWith('.class')) files.push(full);
    }
  };
  visit(dir);
  return files;
}

/**
 * Builds the lane report from the parsed class files and the production/test-support name sets.
 *
 * @returns {{classes: object[], laneCounts: object, totals: object, histogram: object, lowDependency: object}}
 */
function measure(parsed, production, testSupport, maxProduction) {
  const byOuter = new Map();
  for (const info of parsed) {
    const outer = topLevel(info.fqn);
    if (!byOuter.has(outer)) byOuter.set(outer, []);
    byOuter.get(outer).push(info);
  }

  const classes = [];
  for (const [outer, infos] of byOuter) {
    const outerInfo = infos.find((info) => info.fqn === outer);
    const testMethods = infos.reduce((sum, info) => sum + info.testMethods, 0);
    // A top-level class is a test class when it, or one of its nested classes, carries a test method.
    if (!infos.some((info) => info.testMethods > 0)) continue;

    const tags = [...new Set(infos.flatMap((info) => info.tags))].sort();
    const productionDeps = new Set();
    const testDeps = new Set();
    const selfNames = new Set(infos.map((info) => info.fqn));
    const pool = new Set();
    const members = [];
    for (const info of infos) {
      for (const name of info.classNames) pool.add(name);
      members.push(...info.members);
    }
    const collapsedSelf = new Set([...selfNames].map(topLevel));
    for (const name of pool) {
      if (selfNames.has(name) || collapsedSelf.has(topLevel(name))) continue;
      const collapsed = topLevel(name);
      if (production.has(name) || production.has(collapsed)) productionDeps.add(collapsed);
      else if (testSupport.has(name) || testSupport.has(collapsed)) testDeps.add(collapsed);
    }

    const forbidden = [...productionDeps, ...testDeps]
      .filter((name) => FORBIDDEN_SURFACES.has(name.split('.').pop()))
      .sort();
    const io = new Set();
    for (const member of members) {
      const simpleOwner = member.owner.split('.').pop();
      for (const surface of IO_SURFACES) {
        if (surface.owner === simpleOwner && surface.method.test(member.method)) {
          io.add(`${simpleOwner}.${member.method}`);
        }
      }
    }

    const heavy = HEAVY_TAGS.find((tag) => tags.includes(tag));
    const declared = tags.find((tag) => LANE_TAGS.has(tag));
    const reasons = [];
    let lane;
    if (heavy) {
      lane = heavy;
      reasons.push(`@Tag("${heavy}")`);
    } else if (productionDeps.size > maxProduction) {
      lane = 'component';
      reasons.push(`${productionDeps.size} production deps > ${maxProduction}`);
    } else if (forbidden.length > 0) {
      lane = 'component';
      reasons.push(`integration surface ${forbidden.join(', ')}`);
    } else if (io.size > 0) {
      lane = 'component';
      reasons.push(`file/resource/environment api ${[...io].sort().join(', ')}`);
    } else {
      lane = 'unit';
      reasons.push(`${productionDeps.size} production deps <= ${maxProduction}; no integration surface`);
    }

    const warnings = [];
    if (declared !== undefined && declared !== lane) {
      warnings.push(`tagged '${declared}' but the measured rule says '${lane}'`);
    }
    if (heavy !== undefined && declared !== undefined) {
      warnings.push(`carries both a heavy tag ('${heavy}') and a lane tag ('${declared}')`);
    }

    classes.push({
      name: outer,
      tags,
      testMethods,
      productionDeps: [...productionDeps].sort(),
      testSupportDeps: [...testDeps].sort(),
      forbiddenSurfaces: forbidden,
      ioApis: [...io].sort(),
      lane,
      declaredLane: declared ?? null,
      reasons,
      warnings,
    });
  }

  classes.sort((left, right) => LANES.indexOf(left.lane) - LANES.indexOf(right.lane) || left.name.localeCompare(right.name));

  const laneCounts = Object.fromEntries(LANES.map((lane) => [lane, { classes: 0, tests: 0 }]));
  const histogram = { 0: 0, 1: 0, 2: 0, 3: 0, '4-5': 0, '6+': 0 };
  const heavyTags = { generator: 0, compiler: 0, completeness: 0 };
  const hiddenByLane = { generator: 0, compiler: 0, completeness: 0 };
  let heavyClasses = 0;
  let hiddenLow = 0;
  let hiddenUnitEligible = 0;
  const tagCounts = {};
  for (const entry of classes) {
    laneCounts[entry.lane].classes += 1;
    laneCounts[entry.lane].tests += entry.testMethods;
    for (const tag of entry.tags) tagCounts[tag] = (tagCounts[tag] ?? 0) + 1;
    const count = entry.productionDeps.length;
    const bucket = count <= 3 ? String(count) : count <= 5 ? '4-5' : '6+';
    histogram[bucket] += 1;
    if (HEAVY_TAGS.includes(entry.lane)) {
      heavyClasses += 1;
      heavyTags[entry.lane] += 1;
      if (count <= maxProduction) {
        hiddenLow += 1;
        hiddenByLane[entry.lane] += 1;
        if (entry.forbiddenSurfaces.length === 0 && entry.ioApis.length === 0) hiddenUnitEligible += 1;
      }
    }
  }

  return {
    classes,
    laneCounts,
    totals: {
      classes: classes.length,
      tests: classes.reduce((sum, entry) => sum + entry.testMethods, 0),
    },
    histogram,
    lowDependency: { heavyClasses, byLane: heavyTags, hiddenLow, hiddenByLane, hiddenUnitEligible },
    tagCounts,
  };
}

function padRight(value, width) {
  return String(value).padEnd(width);
}
function padLeft(value, width) {
  return String(value).padStart(width);
}

/** Human output: the lane summary, the dependency histogram, the hidden-low-dependency finding, then every row. */
function report(module, result, maxProduction) {
  console.log(`test-lanes — candidate lanes from ${module}/target/test-classes (read-only)`);
  console.log('');
  console.log(`  ${padRight('lane', 15)}${padLeft('classes', 8)}${padLeft('methods', 8)}`);
  for (const lane of LANES) {
    const entry = result.laneCounts[lane];
    console.log(`  ${padRight(lane, 15)}${padLeft(entry.classes, 8)}${padLeft(entry.tests, 8)}`);
  }
  console.log(
    `  ${padRight('total', 15)}${padLeft(result.totals.classes, 8)}${padLeft(result.totals.tests, 8)}`
  );
  console.log('  (tests are the JUnit test methods found in the bytecode; a parameterized method counts once)');
  console.log('');
  console.log(`  production deps per test class (unit threshold is <= ${maxProduction})`);
  for (const bucket of ['0', '1', '2', '3', '4-5', '6+']) {
    console.log(`  ${padRight(bucket, 15)}${padLeft(result.histogram[bucket], 8)}`);
  }
  console.log('');
  const low = result.lowDependency;
  console.log(
    `  low-dependency (<= ${maxProduction} production deps) hidden inside heavy tags: ` +
      `${low.hiddenLow} of ${low.heavyClasses} heavy classes`
  );
  console.log(
    `    ${HEAVY_TAGS.map((tag) => `${tag} ${low.hiddenByLane[tag]}`).join(', ')}; ` +
      `${low.hiddenUnitEligible} of the ${low.hiddenLow} have no integration surface or file/resource API ` +
      `and would be unit-eligible if the tag were ignored`
  );
  console.log('');
  console.log(`  tags: ${Object.entries(result.tagCounts).map(([tag, count]) => `${tag} ${count}`).join(', ') || '(none)'}`);
  console.log('');
  console.log(`  ${padRight('lane', 13)}${padLeft('prod', 5)}${padLeft('test', 5)}${padLeft('methods', 8)}  class (tags)`);
  const nameWidth = Math.max(...result.classes.map((entry) => entry.name.length));
  for (const entry of result.classes) {
    const tags = entry.tags.length > 0 ? entry.tags.join(',') : '-';
    console.log(
      `  ${padRight(entry.lane, 13)}${padLeft(entry.productionDeps.length, 5)}` +
        `${padLeft(entry.testSupportDeps.length, 5)}${padLeft(entry.testMethods, 8)}  ` +
        `${padRight(entry.name, nameWidth)}  ${tags}`
    );
    for (const warning of entry.warnings) {
      console.log(`  ${' '.repeat(33)}  ! ${warning}`);
    }
  }
}

/** The machine-readable object. Paths stay out; the class names are the same keys the test index will use. */
function toJson(module, result, durationMs) {
  return {
    format: 1,
    module,
    maxProductionDepsForUnit: DEFAULT_MAX_PRODUCTION,
    lanes: result.laneCounts,
    totals: result.totals,
    productionDependencyHistogram: result.histogram,
    hiddenLowDependency: result.lowDependency,
    tagCounts: result.tagCounts,
    classes: result.classes,
    durationMs,
  };
}

function main() {
  const elapsed = stopwatch();
  const { options, error } = parse(process.argv.slice(2));
  if (error) {
    console.error(`test-lanes: ${error}`);
    console.error(USAGE);
    return 2;
  }
  if (options.help) {
    console.log(USAGE);
    return 0;
  }

  const root = repoRoot();
  const dir = testClassesDir(root, options.module);
  if (dir === null) {
    console.error(
      `test-lanes: ${MODULE_GROUP}/${options.module}/target/test-classes does not exist — compile that module's tests first ` +
        `(for example: bun scripts/mvn-fast.js --tests <Class>).`
    );
    console.error(reportDuration(elapsed(), { what: 'lanes measured' }));
    return 1;
  }

  const production = new Set();
  const testSupport = new Set();
  for (const moduleRoot of moduleRoots(root)) {
    for (const name of classNamesIn(join(moduleRoot, 'target', 'classes'))) production.add(name);
    for (const name of classNamesIn(join(moduleRoot, 'target', 'test-classes'))) testSupport.add(name);
  }

  const testFiles = classFilesUnder(dir);
  if (testFiles.length === 0) {
    console.error(`test-lanes: ${MODULE_GROUP}/${options.module}/target/test-classes holds no class files`);
    console.error(reportDuration(elapsed(), { what: 'lanes measured' }));
    return 1;
  }

  let parsed;
  try {
    const { javap } = resolveJavap();
    parsed = javapClasses(javap, testFiles)
      .map(readClass)
      .filter((info) => info !== null);
  } catch (failure) {
    console.error(`test-lanes: ${failure.message}`);
    console.error(reportDuration(elapsed(), { what: 'lanes measured' }));
    return 1;
  }

  const result = measure(parsed, production, testSupport, DEFAULT_MAX_PRODUCTION);
  if (result.classes.length === 0) {
    console.error(
      `test-lanes: javap read ${parsed.length} class files under ${MODULE_GROUP}/${options.module}/target/test-classes ` +
        `but none is a test class (no JUnit test method found)`
    );
    console.error(reportDuration(elapsed(), { what: 'lanes measured' }));
    return 1;
  }

  if (options.json) {
    const durationMs = Math.round(elapsed() * 10) / 10;
    process.stdout.write(`${JSON.stringify(toJson(options.module, result, durationMs), null, 2)}\n`);
    console.error(reportDuration(elapsed(), { what: 'lanes measured', files: result.totals.classes }));
    return 0;
  }

  report(options.module, result, DEFAULT_MAX_PRODUCTION);
  console.log('');
  console.log(reportDuration(elapsed(), { what: 'lanes measured', files: result.totals.classes }));
  return 0;
}

process.exit(main());
