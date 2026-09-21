#!/usr/bin/env bun
/**
 * `scan-remaining-javafiles` — Phase 6 deliverable 2, script 1.
 *
 * <p>Answers the plan's Step 1 ("scan for remaining JavaParser usage") and
 * Check 1 ("no remaining JavaParser imports"), with the two corrections the
 * plan's `grep` cannot make:
 *
 * <ol>
 *   <li>`grep -r "import com.github.javaparser"` misses fully-qualified uses
 *       written with no import — and this repo has six of them, including a
 *       main-source generator. It also matches `org.openrewrite.java.JavaParser`,
 *       which is a *different class from a different library* whose simple name
 *       collides. A case-insensitive grep reports already-migrated files as
 *       unmigrated; the whole `hr.hrg.rewrite` staging package is exactly that
 *       false positive.</li>
 *   <li>The plan's Check 1 filters on the literal substrings "test" and
 *       "comment", which drops every test file and any path containing "test"
 *       from the count. A check that cannot fail is not a check.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   bun run scripts/rewrite-migration/scan-remaining-javafiles.js [--root DIR] [--json]
 * </pre>
 *
 * <p>Exit code is always 0: this is a report, not a gate. `verify-migration.js`
 * is the gate.
 */

import { groupBy, basename } from './inventory.js';
import { buildModel, inWorkOrder, progressOf } from './model.js';
import { parseArgs, resolveRoot, heading, out, pad, padLeft, listOrCount, UsageError } from './cli.js';

const USAGE = `usage: bun run scripts/rewrite-migration/scan-remaining-javafiles.js [--root DIR] [--json]`;

async function main() {
  const { flags } = parseArgs(process.argv.slice(2), { root: 'string', json: 'boolean' });
  const root = resolveRoot(flags);
  const model = await buildModel(root);

  if (flags.json) {
    process.stdout.write(`${JSON.stringify(toJson(model), null, 2)}\n`);
    return;
  }

  report(model);
}

/** A machine-readable form, so a later phase can consume the scan. */
function toJson(model) {
  return {
    root: model.root,
    totals: model.totals,
    progress: progressOf(model),
    files: model.files.map((file) => ({
      path: file.repoPath,
      module: file.module,
      scope: file.scope,
      kind: file.kind,
      classified: file.classified,
      status: file.status,
      priority: file.priority,
      risk: file.risk,
      importCount: file.imports.length,
      imports: file.imports,
      qualifiedRefs: file.qualifiedRefs,
      lineCount: file.lineCount,
    })),
    stale: model.stale,
  };
}

function report(model) {
  const { totals } = model;
  const progress = progressOf(model);
  // `model.queueFiles` is the curated queue; `totals.queueFiles` counts by
  // location only, so it also includes the allowlisted and already-ported files.
  // Reporting both without labelling them is how two different "queue" counts end
  // up on one screen.
  const allowlisted = model.exemptFiles.length;
  const byLocation = totals.queueFiles;

  out(heading('Phase 6 — remaining JavaParser usage'));
  out(`repository: ${model.root}`);

  out(heading('Headline'));
  out(`  Java files in the tree                   : ${model.javaFilesTotal}`);
  out(`  Java files mentioning JavaParser at all  : ${totals.javaFilesWithAnyMention}`);
  out(`  JavaParser import lines, whole tree      : ${totals.imports}`);
  out(`  Files to migrate (the curated queue)     : ${progress.total}`);
  out(`  … of those, still importing JavaParser   : ${totals.queueWithImports}`);
  out(`  … import lines to remove                 : ${totals.queueImportLines}`);
  out(`  … main sources / test sources            : ${progress.scope.main} / ${progress.scope.test}`);
  out(`  Files with a JavaParser mention but no curation exclusion: ${byLocation - progress.total - allowlisted}`);
  out(`  Allowlisted (exempt)                     : ${allowlisted}`);
  out(`  Outside the queue (doc/plans/archive)    : ${totals.nonQueueFiles}`);
  out(`  Modules with migration work              : ${listOrCount([...new Set(model.queueFiles.map((f) => f.module))].sort())}`);

  out(heading('By classification'));
  const byClass = groupBy(model.queueFiles, (file) => file.classified);
  for (const [kind, files] of byClass) {
    out(`  ${pad(kind, 16)} ${padLeft(files.length, 3)}  ${describeClass(kind)}`);
  }

  out(heading('By module and scope'));
  const byModule = groupBy(model.queueFiles, (file) => file.module);
  for (const [module, files] of byModule) {
    const main = files.filter((f) => f.scope === 'main').length;
    const test = files.filter((f) => f.scope === 'test').length;
    const other = files.length - main - test;
    const parts = [`main ${main}`, test ? `test ${test}` : null, other ? `other ${other}` : null]
      .filter(Boolean)
      .join(', ');
    out(`  ${pad(module, 26)} ${padLeft(files.length, 3)}  (${parts})`);
  }

  out(heading('Migration queue, in work order'));
  out(
    `  ${pad('PRIORITY', 9)} ${pad('RISK', 6)} ${pad('STATUS', 12)} ${pad('IMP', 4)} FILE`,
  );
  for (const file of inWorkOrder(model.queueFiles)) {
    out(
      `  ${pad(file.priority, 9)} ${pad(file.risk ?? '-', 6)} ${pad(file.status, 12)} ` +
        `${padLeft(file.imports.length, 3)}  ${file.repoPath}`,
    );
  }

  if (model.unclassifiedFiles.length > 0) {
    out(heading('UNCLASSIFIED — scanner found these, curation.js does not know them'));
    for (const file of model.unclassifiedFiles) {
      out(`  ${file.repoPath}`);
    }
    out('  Add a QUEUE entry in scripts/rewrite-migration/curation.js, or an ALLOWLIST entry');
    out('  with a reason. verify-migration.js fails until one of the two exists.');
  }

  if (model.exemptFiles.length > 0) {
    out(heading('Exempt — a JavaParser mention here is correct'));
    for (const file of model.exemptFiles) {
      out(`  ${file.repoPath}`);
      out(`      ${file.allow.reason.split('\n')[0]}`);
    }
  }

  if (model.nonQueueFiles.length > 0) {
    out(heading('Outside the queue (documentation and archive)'));
    for (const file of model.nonQueueFiles) {
      out(`  ${file.repoPath}  [${file.nonQueueReason}]`);
    }
  }

  out(heading('Progress'));
  for (const [status, count] of Object.entries(progress.byStatus)) {
    if (count > 0) {
      out(`  ${pad(status, 14)} ${padLeft(count, 3)}`);
    }
  }
  out(`  ${pad('settled', 14)} ${padLeft(progress.settled, 3)} / ${progress.total} (${progress.percentSettled}%)`);

  out('');
  out('This is a report; the gate is: bun run scripts/rewrite-migration/verify-migration.js');
}

function describeClass(kind) {
  switch (kind) {
    case 'imports':
      return 'has `import com.github.javaparser…`';
    case 'qualified-only':
      return 'no import — fully-qualified call sites only (a grep for imports misses these)';
    case 'comment-only':
      return 'mentions JavaParser only in a comment';
    default:
      return '';
  }
}

try {
  await main();
} catch (error) {
  if (error instanceof UsageError) {
    process.stderr.write(`${error.message}\n${USAGE}\n`);
    process.exit(2);
  }
  throw error;
}
