#!/usr/bin/env bun
/**
 * `verify-migration` — Phase 6 deliverable 2, script 3, and the phase's gate.
 *
 * <p>The plan's Check 1 is a `grep` whose output is then filtered with
 * `grep -v "test"` and `grep -v "comment"`, with the expectation "no matches (or
 * only in test/commented code)". That check cannot pass honestly and cannot fail
 * usefully: it drops every test file from the count, and it would report success
 * for a tree that still imports JavaParser everywhere as long as the paths or
 * lines happen to contain the word "test".
 *
 * <p>This gate replaces it with checks that can fail, and it checks the thing the
 * plan actually cares about but cannot express as a grep: whether the tracked
 * status agrees with the code. A tracker that drifts into optimism is the real
 * risk in a 34-file migration, not a missed import.
 *
 * <p>Usage:
 * <pre>
 *   bun run scripts/rewrite-migration/verify-migration.js [--root DIR] [--strict] [--quiet]
 * </pre>
 *
 * <p>`--strict` additionally fails on WARN-level findings. Exit code 1 on any
 * FAIL.
 */

import { join } from 'node:path';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';

import { buildModel, progressOf, inWorkOrder, STATUSES } from './model.js';
import { parseArgs, resolveRoot, heading, out, warn, UsageError } from './cli.js';
import { DOC_ALLOWLIST } from './curation.js';
import { scanDocumentation, docOffenders, staleDocAllowlistPaths } from './documentation.js';

const USAGE = `usage: bun run scripts/rewrite-migration/verify-migration.js [--root DIR] [--strict] [--quiet]`;

const findings = { fail: [], warn: [], ok: [] };

function fail(check, message) {
  findings.fail.push({ check, message });
}

function caution(check, message) {
  findings.warn.push({ check, message });
}

function pass(check, message) {
  findings.ok.push({ check, message });
}

async function main() {
  const { flags } = parseArgs(process.argv.slice(2), {
    root: 'string',
    strict: 'boolean',
    quiet: 'boolean',
  });
  const strict = Boolean(flags.strict);
  const quiet = Boolean(flags.quiet);
  const root = resolveRoot(flags);

  const model = await buildModel(root);
  const progress = progressOf(model);
  const docs = await scanDocumentation(root);

  checkQueueImportFree(model);
  checkStagingCompiles(model);
  checkTrackerAgreement(model);
  checkTrackerVocabulary(model);
  checkUnclassified(model);
  checkCurationFreshness(model);
  checkAllowlistHonest(model);
  checkModuleDependencies(root, model);
  checkDocsHonest(root, docs);

  report(model, progress, docs, { strict, quiet });

  const failed = findings.fail.length > 0 || (strict && findings.warn.length > 0);
  process.exit(failed ? 1 : 0);
}

/**
 * Check 1 (the plan's): no remaining JavaParser imports in a queue file that is
 * marked settled.
 */
function checkQueueImportFree(model) {
  const check = 'queue-import-free';
  const offenders = model.queueFiles.filter(
    (file) => file.hasImports && (file.status === 'complete' || file.status === 'testing'),
  );
  if (offenders.length === 0) {
    pass(check, 'no queue file is marked complete/testing while still importing JavaParser');
    return;
  }
  for (const file of offenders) {
    fail(
      check,
      `${file.repoPath} is marked \`${file.status}\` but still has ${file.imports.length} ` +
        `JavaParser import line(s). Fall back to \`in-progress\`, or finish the port.`,
    );
  }
}

/**
 * The phase's own prerequisite: the staging code Phase 6 was meant to build on
 * must compile. Checked by looking for the dependency + the known-broken API
 * surface, not by shelling out to Maven, because a gate that needs a 2-minute
 * build will be skipped.
 */
function checkStagingCompiles(model) {
  const check = 'staging-compiles';
  const stagingDir = 'project-automation/src/main/java/hr/hrg/rewrite';
  const stagingFiles = model.files.filter((file) => file.repoPath.startsWith(stagingDir));

  // The scan only sees JavaParser mentions; staging files that were already
  // converted away from JavaParser need a direct existence check.
  const pomPath = join(model.root, 'project-automation/pom.xml');
  const hasRewriteDependency =
    existsSync(pomPath) && /org\.openrewrite/.test(readText(pomPath));

  if (!existsSync(join(model.root, stagingDir)) && stagingFiles.length === 0) {
    pass(check, 'no hr.hrg.rewrite staging package present');
    return;
  }

  if (!hasRewriteDependency) {
    fail(
      check,
      'project-automation/pom.xml declares no org.openrewrite dependency, so the ' +
        'ported hr.hrg.rewrite staging package has no OpenRewrite API to compile ' +
        'against. See prerequisite P0-3 in curation.js.',
    );
  } else {
    pass(check, 'project-automation declares an OpenRewrite dependency');
  }

  const invented = inventedTypeRefs(model.root, stagingDir);
  if (invented.length > 0) {
    fail(
      check,
      `the staging package references ${invented.length} type(s) that do not exist ` +
        `(e.g. ${invented[0]}). The real names are org.openrewrite.java.tree.*. ` +
        'See prerequisite P0-2.',
    );
  }
}

/** Read a small text file, or `''`. */
function readText(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    return '';
  }
}

/**
 * Types the staging package refers to under the project's own tooling package
 * that are not in the OpenRewrite API. Cheap and targeted: a name that appears as
 * `hr.hrg.hipster.entity.tooling.X` where `X` is not a real file in that package.
 */
function inventedTypeRefs(root, stagingDir) {
  const found = [];
  const javaFiles = findJavaUnder(join(root, stagingDir));
  const toolingDir = join(root, 'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling');

  for (const file of javaFiles) {
    const text = readText(file);
    for (const match of text.matchAll(/hr\.hrg\.hipster\.entity\.tooling\.([A-Z]\w*)/g)) {
      const name = match[1];
      const exists =
        existsSync(join(toolingDir, `${name}.java`)) ||
        existsSync(join(toolingDir, 'meta', `${name}.java`)) ||
        existsSync(join(toolingDir, 'index', `${name}.java`)) ||
        existsSync(join(toolingDir, 'validation', `${name}.java`));
      if (!exists) {
        found.push(`hr.hrg.hipster.entity.tooling.${name}`);
      }
    }
  }
  return [...new Set(found)];
}

/** Every `*.java` under `dir`, recursively, without following the usual skips. */
function findJavaUnder(dir) {
  const result = [];
  if (!existsSync(dir)) {
    return result;
  }
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const child = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'target' || entry.name === 'node_modules') {
        continue;
      }
      result.push(...findJavaUnder(child));
    } else if (entry.name.endsWith('.java') && statSync(child).isFile()) {
      result.push(child);
    }
  }
  return result;
}

/**
 * The check a grep cannot do: does the tracker agree with the disk?
 */
function checkTrackerAgreement(model) {
  const check = 'tracker-agreement';

  if (!model.tracker.exists) {
    fail(
      check,
      'doc/brainstorm/rewrite-migration/06-migration/tracker.md does not exist. ' +
        'Generate it with: bun run scripts/rewrite-migration/generate-checklist.js --write',
    );
    return;
  }

  const tracked = new Set(model.tracker.entries.keys());
  const queuePaths = new Set(model.queueFiles.map((f) => f.repoPath));

  const missing = [...queuePaths].filter((path) => !tracked.has(path));
  if (missing.length > 0) {
    fail(check, `${missing.length} queue file(s) have no tracker row: ${missing.slice(0, 3).join(', ')}`);
  }

  // Rows whose file the scan no longer reports. A *settled* row is expected here: the
  // scan only returns files that still mention JavaParser, so a file that has been
  // fully ported drops out of it — the row is the completion record, not a
  // disagreement. An unsettled row naming a file with no JavaParser reference is the
  // real signal: the tracker claims work remains on a file that has none.
  const extra = [...tracked].filter((path) => {
    if (queuePaths.has(path)) {
      return false;
    }
    if (model.exemptFiles.some((f) => f.repoPath === path)) {
      return false;
    }
    const status = model.tracker.entries.get(path)?.status;
    return status !== 'complete' && status !== 'exempt';
  });
  if (extra.length > 0) {
    caution(check, `${extra.length} tracker row(s) claim unfinished work on a file with no JavaParser reference: ${extra.slice(0, 3).join(', ')}`);
  }

  if (missing.length === 0 && extra.length === 0) {
    pass(check, 'every queue file has exactly one tracker row');
  }
}

/** Statuses must come from the vocabulary, and `blocked` must say what blocks it. */
function checkTrackerVocabulary(model) {
  const check = 'tracker-vocabulary';
  const problems = [...model.tracker.problems];

  for (const [path, entry] of model.tracker.entries) {
    if (!STATUSES.includes(entry.status)) {
      problems.push(`${path}: unknown status \`${entry.status}\``);
      continue;
    }
    if (entry.status === 'blocked' && entry.note.trim().length === 0) {
      problems.push(`${path}: status \`blocked\` needs a reason in the notes column`);
    }
  }

  if (problems.length === 0) {
    pass(check, 'tracker rows use the known status vocabulary');
    return;
  }
  for (const problem of problems.slice(0, 10)) {
    fail(check, problem);
  }
  if (problems.length > 10) {
    fail(check, `…and ${problems.length - 10} more tracker problems`);
  }
}

/** A file the scanner found that curation does not know is a gate failure. */
function checkUnclassified(model) {
  const check = 'curation-coverage';
  if (model.unclassifiedFiles.length === 0) {
    pass(check, 'every queue file is classified in curation.js');
    return;
  }
  for (const file of model.unclassifiedFiles) {
    fail(
      check,
      `${file.repoPath} is in the migration queue but has no QUEUE or ALLOWLIST entry. ` +
        'Add one to scripts/rewrite-migration/curation.js.',
    );
  }
}

/** Curation entries pointing at files that no longer exist, or that no longer need an exemption. */
function checkCurationFreshness(model) {
  const check = 'curation-freshness';
  const stale = [...model.stale.queue, ...model.stale.allowlist];
  for (const path of stale) {
    caution(check, `curation.js names ${path}, which no longer exists in the tree`);
  }
  for (const path of model.stale.cleanedUp) {
    caution(
      check,
      `${path} is allowlisted but no longer mentions JavaParser — the exemption can be retired.`,
    );
  }
  if (stale.length === 0 && model.stale.cleanedUp.length === 0) {
    pass(check, 'every curation entry names a file that exists');
  }
}

/** An allowlist entry that no longer needs to exist is a stale permission. */
function checkAllowlistHonest(model) {
  const check = 'allowlist-honest';
  if (model.exemptFiles.length === 0) {
    pass(check, 'allowlist is empty');
    return;
  }
  for (const file of model.exemptFiles) {
    if (file.status !== 'exempt') {
      fail(
        check,
        `${file.repoPath} is allowlisted but has tracker status \`${file.status}\`; allowlisted files are always \`exempt\`.`,
      );
    }
  }
  pass(check, `${model.exemptFiles.length} allowlisted file(s), each with a recorded reason`);
}

/**
 * The last mile: a module whose sources no longer import JavaParser but whose POM
 * still declares it. Reported, not failed — a module can be mid-migration, and
 * the dependency removal is a separate commit.
 */
function checkModuleDependencies(root, model) {
  const check = 'pom-dependencies';
  const modules = [...new Set(model.queueFiles.map((f) => f.module))].sort();
  const stillDeclaring = [];

  for (const module of modules) {
    if (module === '(root)') {
      continue;
    }
    const pomPath = join(root, module, 'pom.xml');
    if (existsSync(pomPath) && /com\.github\.javaparser/.test(readText(pomPath))) {
      stillDeclaring.push(module);
    }
  }

  if (stillDeclaring.length === 0) {
    pass(check, 'no queue module declares javaparser-core');
    return;
  }
  caution(
    check,
    `${stillDeclaring.length} module(s) still declare javaparser-core: ${stillDeclaring.join(', ')}. ` +
      'Remove the dependency once every file in the module is ported and the gate is green.',
  );
}

/**
 * Phase 8's check, and the reason this gate is still the entry point: the code
 * was migrated in Phase 6, but nothing in the build fails when a *document*
 * contradicts the tree. `AGENTS.md` alone proved that — it mandated the removed
 * library for a whole phase, and no test could see it.
 *
 * <p>Two verdicts, and both are the same defect seen from two sides. A live
 * document that names the library with no recorded reason is an unrecorded
 * decision; a live document that <em>teaches</em> the removed API is an
 * instruction to re-add the dependency the migration removed. The second fails
 * hard. The first also fails, because "somebody must have looked at this" is the
 * only thing that keeps an exemption list meaningful — a check that only failed
 * on teaching would let a document accumulate calls to removed methods one line
 * at a time, each individually below the teaching threshold.</p>
 *
 * <p>Fail-safe in the same direction as the rest of the gate: a file the scanner
 * cannot classify is a failure, not a pass. Only three classifications are
 * legitimate — generated, record, decision — and every other mentioning file must
 * be on the allowlist with a reason and a retirement condition.</p>
 */
function checkDocsHonest(root, docs) {
  const check = 'docs-honest';
  const { unallowlisted } = docOffenders(docs);

  // An exemption whose file no longer mentions the library is a permission
  // nobody needs. This is distinct from `staleAllow` inside the scan, which only
  // sees files that *did* mention and found themselves exempted: a document that
  // stopped mentioning at all drops out of the scan entirely, and a check that
  // reads only the scan would never notice the entry left behind.
  const removedFromScan = Object.keys(DOC_ALLOWLIST).filter(
    (path) => !path.includes('*') && !docs.files.some((file) => file.repoPath === path),
  );

  // An exemption without a reason is a hole, and one without a retirement
  // condition is a permanent hole. Same rule the source allowlist follows.
  const thin = [];
  for (const [path, entry] of Object.entries(DOC_ALLOWLIST)) {
    if (!entry.reason || entry.reason.trim().length < 40) {
      thin.push(`${path}: the reason is missing or too short to be a reason`);
    }
    if (!entry.deferredTo || entry.deferredTo.trim().length < 15) {
      thin.push(`${path}: deferredTo must name what removes the exemption`);
    }
  }
  for (const path of staleDocAllowlistPaths(root)) {
    thin.push(`${path}: allowlisted but the file does not exist`);
  }
  for (const path of removedFromScan) {
    thin.push(
      `${path}: allowlisted but the file no longer names the removed library — ` +
        'retire the exemption, or the allowlist stops meaning anything',
    );
  }

  if (unallowlisted.length > 0) {
    for (const file of unallowlisted.slice(0, 20)) {
      const teaching = file.teachesRemovedApi
        ? ` and TEACHES the removed API (${file.taughtApis.join(', ')})`
        : '';
      fail(
        check,
        `${file.repoPath} is a live document naming the removed library${teaching}, with no ` +
          'DOC_ALLOWLIST entry. Rewrite it against the current API, or add a reason and a ' +
          'retirement condition to scripts/rewrite-migration/curation.js.',
      );
    }
    if (unallowlisted.length > 20) {
      fail(check, `…and ${unallowlisted.length - 20} more unclassified document(s)`);
    }
  }

  // A live document that only mentions the name (no removed API, an entry, a
  // reason) is the recorded case, and it is still worth printing: it is the list
  // of prose a reader will meet that says the old name.
  const taughtLive = docs.files.filter((file) => file.kind === 'live' && file.teachesRemovedApi);
  for (const file of taughtLive.slice(0, 10)) {
    caution(
      check,
      `${file.repoPath} still teaches ${file.taughtApis.join(', ')} — allowed by its recorded ` +
        'reason, but a reader will follow it.',
    );
  }

  if (thin.length > 0) {
    for (const problem of thin.slice(0, 10)) {
      fail(check, `DOC_ALLOWLIST: ${problem}`);
    }
    if (thin.length > 10) {
      fail(check, `…and ${thin.length - 10} more documentation-allowlist problems`);
    }
  }

  if (unallowlisted.length === 0 && thin.length === 0 && taughtLive.length === 0) {
    pass(
      check,
      `${docs.files.length} document(s) name the removed library; all are generated, records, ` +
        `decisions, or allowlisted with a reason (${Object.keys(DOC_ALLOWLIST).length} entries)`,
    );
    return;
  }
  if (unallowlisted.length === 0 && thin.length === 0) {
    pass(
      check,
      `every mentioning document is classified; ${taughtLive.length} allowlisted live ` +
        'document(s) are flagged as still teaching the removed API',
    );
  }
}

// ------------------------------------------------------------------ output ----

function report(model, progress, docs, { strict, quiet }) {
  if (!quiet) {
    out(heading('Phase 6 — migration verification'));
    out(`repository: ${model.root}`);
    out('');
    out(`  queue files        ${progress.total}`);
    out(`  settled            ${progress.settled} (${progress.percentSettled}%)`);
    out(`  still importing    ${progress.stillImporting}`);
    out(`  import-free        ${progress.importFree}`);
    out(`  unclassified       ${model.unclassifiedFiles.length}`);
    out(`  exempt             ${model.exemptFiles.length}`);
    out('');
    out(`  markdown scanned   ${docs.markdownTotal}`);
    out(`  still mentioning   ${docs.files.length} (${docs.totals.mentions} lines)`);
    out(`  live documents     ${docs.totals.live} (${docs.totals.liveTeaching} teaching the removed API)`);
    out(`  doc allowlist      ${Object.keys(DOC_ALLOWLIST).length} entries`);
    out('');
    for (const [status, count] of Object.entries(progress.byStatus)) {
      if (count > 0) {
        out(`    ${status.padEnd(14)} ${String(count).padStart(3)}`);
      }
    }

    const remaining = inWorkOrder(model.queueFiles).filter(
      (file) => !['complete', 'exempt'].includes(file.status),
    );
    if (remaining.length > 0) {
      out(heading(`Remaining work (${remaining.length})`));
      for (const file of remaining) {
        out(`  [${file.priority}/${file.risk ?? '-'}] ${file.status.padEnd(11)} ${file.repoPath}`);
      }
    }
  }

  out(heading('Checks'));
  for (const finding of findings.ok) {
    out(`  OK    ${finding.check}: ${finding.message}`);
  }
  for (const finding of findings.warn) {
    out(`  WARN  ${finding.check}: ${finding.message}`);
  }
  for (const finding of findings.fail) {
    out(`  FAIL  ${finding.check}: ${finding.message}`);
  }

  out('');
  if (findings.fail.length > 0) {
    out(`RESULT: FAIL — ${findings.fail.length} failing check(s).`);
    return;
  }
  if (strict && findings.warn.length > 0) {
    out(`RESULT: FAIL (--strict) — ${findings.warn.length} warning(s) treated as failures.`);
    return;
  }
  if (findings.warn.length > 0) {
    out(`RESULT: PASS with ${findings.warn.length} warning(s).`);
    return;
  }
  out('RESULT: PASS.');
}

try {
  await main();
} catch (error) {
  if (error instanceof UsageError) {
    warn(`${error.message}\n${USAGE}`);
    process.exit(2);
  }
  throw error;
}
