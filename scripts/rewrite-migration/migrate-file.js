#!/usr/bin/env bun
/**
 * `migrate-file` — Phase 6 deliverable 2, script 2.
 *
 * <p>Phase 6's Step 4 is "for each file: read it, identify the calls, plan the
 * approach, migrate, test, update the checklist". This script owns everything in
 * that list except the edit itself: the imports it must lose, the verified
 * mapping for each, the notes and steps from `curation.js`, and a recorded
 * before/after so the port can be attributed when it goes wrong.
 *
 * <p>It deliberately does **not** edit the file. Phase 6 is a rewrite of
 * behaviour-bearing generators, and a mechanical transform is exactly what the
 * plan's risk table is warning about.
 *
 * <p>Usage:
 * <pre>
 *   bun run scripts/rewrite-migration/migrate-file.js &lt;repo-relative-path&gt;
 *   bun run scripts/rewrite-migration/migrate-file.js --baseline &lt;path&gt;   # before editing
 *   bun run scripts/rewrite-migration/migrate-file.js --after    &lt;path&gt;   # after editing
 * </pre>
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { MAPPINGS, mappingFor } from './mappings.js';
import { classifyJavaText } from './inventory.js';
import { buildModel, checkboxFor } from './model.js';
import { parseArgs, resolveRoot, heading, out, pad, warn, UsageError } from './cli.js';

const USAGE = `usage: bun run scripts/rewrite-migration/migrate-file.js [--root DIR] (--baseline|--after)? <repo-relative-path>`;

/** Where before/after snapshots live. Ignored by git; see .gitignore in this directory. */
const SNAPSHOT_DIR = '.baseline';

async function main() {
  const { flags, positional } = parseArgs(process.argv.slice(2), {
    root: 'string',
    baseline: 'boolean',
    after: 'boolean',
    json: 'boolean',
  });

  if (positional.length !== 1) {
    throw new UsageError('exactly one file path is required');
  }
  if (flags.baseline && flags.after) {
    throw new UsageError('--baseline and --after are exclusive');
  }

  const root = resolveRoot(flags);
  const repoPath = normalisePath(positional[0]);
  const absolute = join(root, repoPath);
  if (!existsSync(absolute)) {
    throw new UsageError(`no such file: ${repoPath}`);
  }

  const model = await buildModel(root);
  const file = model.files.find((candidate) => candidate.repoPath === repoPath) ?? null;

  if (flags.baseline) {
    recordBaseline(root, repoPath, absolute, file);
    return;
  }
  if (flags.after) {
    compareAfter(root, repoPath, absolute, file);
    return;
  }
  preflight(model, repoPath, absolute, file);
}

/** Accept a native Windows path or a `/`-separated repo path. */
function normalisePath(input) {
  return input.split('\\').join('/').replace(/^\.\//, '');
}

/** Pre-flight: everything needed to port this one file. */
function preflight(model, repoPath, absolute, file) {
  out(heading(`Phase 6 pre-flight — ${repoPath}`));

  if (!file) {
    warn(`This file has no JavaParser mention at all.`);
    warn(`Nothing to migrate. (If you expected one, run scan-remaining-javafiles.js.)`);
    process.exitCode = 1;
    return;
  }

  out(`  module      ${file.module}`);
  out(`  scope       ${file.scope}`);
  out(`  kind        ${file.kind}`);
  out(`  status      ${file.status}   [${checkboxFor(file.status)}]  (tracker.md)`);
  out(`  priority    ${file.priority}`);
  out(`  risk        ${file.risk ?? '-'}`);
  out(`  detected    ${file.classified}`);
  out(`  lines       ${file.lineCount}`);

  if (file.allow) {
    out(heading('EXEMPT — this file should keep its JavaParser reference'));
    out(`  reason      ${file.allow.reason}`);
    out(`  deferred to ${file.allow.deferredTo}`);
    out('');
    out('  Do not port this file. If you believe the exemption is wrong, change');
    out('  ALLOWLIST in scripts/rewrite-migration/curation.js and say why.');
    return;
  }

  if (file.kind === 'unclassified') {
    warn(heading('UNCLASSIFIED — add a curation entry before porting'));
    warn(`  ${repoPath} is in the queue by location but has no QUEUE entry.`);
    warn('  Add one to scripts/rewrite-migration/curation.js first: verify-migration.js');
    warn('  fails on an unclassified file, so the port cannot be recorded as complete.');
  }

  if (file.kind === 'non-queue') {
    out(heading('OUTSIDE THE QUEUE'));
    out(`  ${file.nonQueueReason}. This is documentation or archive, not migration work.`);
    return;
  }

  // ---- the surface to remove ------------------------------------------------
  out(heading('1. JavaParser surface to remove'));
  if (file.imports.length === 0) {
    out('  No import lines — every use is fully qualified. A port driven by the');
    out('  import list would miss this file entirely:');
    for (const line of file.codeRefs) {
      out(`      ${line}`);
    }
  } else {
    for (const line of file.imports) {
      const fqn = line.replace(/^import\s+(?:static\s+)?/, '').replace(/;$/, '');
      const mapping = mappingFor(fqn);
      const mark = mapping ? (mapping.status === 'verified' ? 'verified' : 'inferred') : 'UNMAPPED';
      out(`      [${pad(mark, 9)}] ${fqn}`);
    }
  }
  if (file.commentRefs.length > 0) {
    out('  Comment-only mentions (leave them; they are documentation):');
    for (const line of file.commentRefs) {
      out(`      ${line}`);
    }
  }

  // ---- the mapping table ----------------------------------------------------
  const fqns = [
    ...new Set(file.imports.map((i) => i.replace(/^import\s+(?:static\s+)?/, '').replace(/;$/, ''))),
  ];

  out(heading('2. Mapping'));
  out('  `verified` means the OpenRewrite side is already used in this repo (merge-java).');
  out('  `inferred` means it is the documented LST type but unused here — confirm it');
  out('  against the OpenRewrite reference for version 8.90.4 before relying on it.');
  out('');
  for (const fqn of fqns) {
    const mapping = MAPPINGS[fqn];
    if (!mapping) {
      out(`  ${fqn}`);
      out(`      UNMAPPED — add it to scripts/rewrite-migration/mappings.js.`);
      continue;
    }
    out(`  ${fqn}`);
    out(`      -> ${mapping.rewrite}   [${mapping.status}]`);
    out(`      ${mapping.note}`);
    if (mapping.evidence) {
      out(`      evidence: ${mapping.evidence}`);
    }
    out('');
  }

  // ---- the file's own knowledge --------------------------------------------
  if (file.queue) {
    out(heading('3. Why this file is not mechanical'));
    if (file.queue.riskReason) {
      out(`  RISK (${file.risk}): ${file.queue.riskReason}`);
      out('');
    }
    out(`  ${file.queue.note}`);
    if (file.queue.openrewrite?.length) {
      out('');
      out(`  OpenRewrite classes involved: ${file.queue.openrewrite.join(', ')}`);
    }
    if (file.queue.blocks) {
      out('');
      out(`  Blocks: ${file.queue.blocks}`);
    }
    if (file.queue.steps?.length) {
      out('');
      out('  Steps for this file:');
      file.queue.steps.forEach((step, index) => out(`    ${index + 1}. ${step}`));
    }
  }

  // ---- the procedure --------------------------------------------------------
  out(heading('4. Procedure'));
  out(`     bun run scripts/rewrite-migration/migrate-file.js --baseline ${repoPath}`);
  out('       ... make the edit ...');
  out(`     cmd /c "scripts\\mvn-jdk25.cmd -o -pl ${moduleList(file.module)} -am -Dmaven.compiler.useIncrementalCompilation=false clean test"`);
  out(`     bun run scripts/rewrite-migration/migrate-file.js --after ${repoPath}`);
  out(`     bun run scripts/rewrite-migration/verify-migration.js`);
  out('');
  out('  `clean` is not optional: this repo records (note F-47) that a gate without it');
  out('  can be satisfied by the previous revision’s class files, which is how the');
  out('  staging code in project-automation stayed "green" while broken.');
  out('');
  out(`  Then set the status in ${'`'}doc/brainstorm/rewrite-migration/06-migration/tracker.md${'`'}:`);
  out(`      - [>] ${repoPath}    # testing, once it compiles`);
  out(`      - [x] ${repoPath}    # complete, once the module gate passes`);
  out('');
  out('  A file that still imports JavaParser must not be marked complete:');
  out('  verify-migration.js fails on that pair.');
}

/** The Maven `-pl` list for a module: the module plus the siblings it needs. */
function moduleList(module) {
  if (module === '(root)') {
    return '.';
  }
  return module;
}

// ---------------------------------------------------------------- snapshots ---

function snapshotPath(root, repoPath) {
  const digest = createHash('sha256').update(repoPath).digest('hex').slice(0, 16);
  return join(root, 'scripts', 'rewrite-migration', SNAPSHOT_DIR, `${digest}.json`);
}

function recordBaseline(root, repoPath, absolute, file) {
  const text = readFileSync(absolute, 'utf8');
  const digest = createHash('sha256').update(text).digest('hex');
  const facts = classifyJavaText(text, repoPath);

  const directory = join(root, 'scripts', 'rewrite-migration', SNAPSHOT_DIR);
  mkdirSync(directory, { recursive: true });
  writeFileSync(
    snapshotPath(root, repoPath),
    `${JSON.stringify(
      {
        repoPath,
        recordedAt: new Date().toISOString(),
        sha256: digest,
        importCount: facts.imports.length,
        imports: facts.imports,
        codeRefs: facts.codeRefs,
        status: file?.status ?? 'not-started',
      },
      null,
      2,
    )}\n`,
  );

  out(heading(`Baseline recorded — ${repoPath}`));
  out(`  sha256       ${digest}`);
  out(`  imports      ${facts.imports.length}`);
  out(`  status       ${file?.status ?? 'not-started'}`);
  out('');
  out('  Now make the edit, then run:');
  out(`    bun run scripts/rewrite-migration/migrate-file.js --after ${repoPath}`);
}

function compareAfter(root, repoPath, absolute, file) {
  const path = snapshotPath(root, repoPath);
  if (!existsSync(path)) {
    throw new UsageError(
      `no baseline for ${repoPath}. Run --baseline <path> before editing, or delete the --after flag.`,
    );
  }
  const baseline = JSON.parse(readFileSync(path, 'utf8'));
  const text = readFileSync(absolute, 'utf8');
  const digest = createHash('sha256').update(text).digest('hex');
  const facts = classifyJavaText(text, repoPath);

  out(heading(`Post-edit check — ${repoPath}`));
  out(`  baseline sha256 ${baseline.sha256}`);
  out(`  current  sha256 ${digest}`);

  if (digest === baseline.sha256) {
    warn('  The file is byte-identical to the baseline: nothing was changed.');
    process.exitCode = 1;
  } else {
    out('  The file changed.');
  }

  out('');
  out(`  imports before ${baseline.importCount}`);
  out(`  imports after  ${facts.imports.length}`);
  for (const line of baseline.imports) {
    if (!facts.imports.includes(line)) {
      out(`      removed  ${line}`);
    }
  }
  for (const line of facts.imports) {
    if (!baseline.imports.includes(line)) {
      out(`      added    ${line}`);
    }
  }
  for (const line of baseline.codeRefs) {
    if (!facts.codeRefs.includes(line)) {
      out(`      removed  (qualified) ${line}`);
    }
  }
  for (const line of facts.codeRefs) {
    if (!baseline.codeRefs.includes(line)) {
      out(`      added    (qualified) ${line}`);
    }
  }

  out('');
  out('  What this script can and cannot tell you:');
  out('    CAN  — whether the JavaParser surface is gone.');
  out('    CANNOT — whether the port behaves the same. That is the module gate:');
  out(`      cmd /c "scripts\\mvn-jdk25.cmd -o -pl ${moduleList(file?.module ?? '.')} -am -Dmaven.compiler.useIncrementalCompilation=false clean test"`);
  out('');
  out('  Do not mark the file complete until that gate passes. Then update tracker.md:');
  out(`      - [x] ${repoPath}`);
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
