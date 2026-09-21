#!/usr/bin/env bun
/**
 * Regenerates the two *derived* Phase 6 documents:
 *
 * <ul>
 *   <li>`06-migration/Checklist.md` — always regenerated from the scan + curation
 *       + tracker.</li>
 *   <li>`06-migration/tracker.md` — written **only when absent**, because it is
 *       hand-maintained state. Regenerating it would silently reset every status
 *       to `not-started`.</li>
 * </ul>
 *
 * <p>So this is the "generate initial checklist" step of the plan
 * (`06-Migration-Checklist.md` § Implementation Steps 4 and 8). It is a separate
 * entry point from the four the plan names because the plan lists four scripts
 * and this is the fifth job: keeping the prose in step with the code.
 *
 * <p>Usage:
 * <pre>
 *   bun run scripts/rewrite-migration/generate-checklist.js [--write] [--root DIR]
 *   bun run scripts/rewrite-migration/generate-checklist.js --check
 * </pre>
 *
 * `--check` exits 1 when the committed `Checklist.md` differs from what would be
 * written now — the guard against a checklist that has drifted from the tree.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import {
  buildModel,
  progressOf,
  inWorkOrder,
  checkboxFor,
  CHECKLIST_PATH,
  TRACKER_PATH,
} from './model.js';
import { DEPENDENCIES, PREREQUISITES } from './curation.js';
import { OPENREWRITE_VERSION, JAVAPARSER_VERSION, mappingFor } from './mappings.js';
import { parseArgs, resolveRoot, out, warn, UsageError } from './cli.js';

const USAGE = `usage: bun run scripts/rewrite-migration/generate-checklist.js [--root DIR] [--write|--check]`;

async function main() {
  const { flags } = parseArgs(process.argv.slice(2), {
    root: 'string',
    write: 'boolean',
    check: 'boolean',
  });
  if (flags.write && flags.check) {
    throw new UsageError('--write and --check are exclusive');
  }
  const root = resolveRoot(flags);
  const model = await buildModel(root);
  const generatedAt = new Date().toISOString().slice(0, 10);

  const checklist = renderChecklist(model, generatedAt);
  const checklistPath = join(root, CHECKLIST_PATH);
  const trackerPath = join(root, TRACKER_PATH);

  if (flags.check) {
    checkUpToDate(checklistPath, checklist);
    return;
  }

  if (flags.write) {
    mkdirSync(dirname(checklistPath), { recursive: true });
    writeFileSync(checklistPath, checklist);
    warn(`wrote ${CHECKLIST_PATH}`);

    if (existsSync(trackerPath)) {
      // Reconcile rather than regenerate: the tracker holds hand-maintained statuses, so it is
      // never rewritten wholesale. But a file the scan has found and the tracker has not named is
      // a hole the gate fails on, and a generator that refuses to close it just makes the human
      // do bookkeeping. Existing rows are preserved byte-for-byte; only wholly absent queue files
      // are appended, at `not-started`.
      const added = reconcileTracker(trackerPath, model);
      if (added.length === 0) {
        warn(`left ${TRACKER_PATH} alone (hand-maintained; no queue file was missing from it)`);
      } else {
        warn(`appended ${added.length} new queue row(s) to ${TRACKER_PATH} at \`not-started\`:`);
        for (const path of added) {
          warn(`  ${path}`);
        }
      }
    } else {
      writeFileSync(trackerPath, renderTracker(model, generatedAt));
      warn(`wrote ${TRACKER_PATH}`);
    }
    return;
  }

  process.stdout.write(checklist);
}

/** Fail when the committed checklist no longer matches the tree. */
function checkUpToDate(path, expected) {
  if (!existsSync(path)) {
    warn(`${CHECKLIST_PATH} does not exist. Run with --write.`);
    process.exit(1);
  }
  const actual = readFileSync(path, 'utf8');
  if (actual !== expected) {
    warn(`${CHECKLIST_PATH} is out of date. Run: bun run scripts/rewrite-migration/generate-checklist.js --write`);
    process.exit(1);
  }
  out(`${CHECKLIST_PATH} is up to date.`);
}

// ---------------------------------------------------------------- checklist ---

function renderChecklist(model, generatedAt) {
  const progress = progressOf(model);
  const lines = [];
  const push = (line = '') => lines.push(line);

  push('# Phase 6 — Migration Checklist');
  push();
  push('> **Generated file.** Written by');
  push('> `bun run scripts/rewrite-migration/generate-checklist.js --write`.');
  push('> Do not edit by hand: edit `scripts/rewrite-migration/curation.js` for what a file');
  push(`> needs, or \`${TRACKER_PATH}\` for its status, and regenerate.`);
  push('>');
  push('> Regenerate after any change to the Java tree, or `verify-migration.js` will fail');
  push('> on the disagreement.');
  push();
  push(`Generated: ${generatedAt}`);
  push();

  // ---- first-pass results --------------------------------------------------
  // Hand-written because these are findings, not measurements: they cannot be derived from the tree
  // and they are the most valuable thing the first porting pass produced for the files still to come.
  if (progress.settled > 0) {
    push('## First pass — completed');
    push();
    push(`The read-only spine is ported: **${progress.settled} files settled**, ` +
      `${progress.byStatus['in-progress'] ?? 0} in progress, ${progress.remaining} remaining. ` +
      '`hipster-entity-tooling` builds and its full test suite is green.');
    push();
    push('Three API findings from that pass are recorded in `MIGRATION-GUIDE.md` § 4, and each one ' +
      'matters for every file still to be ported because each is silent:');
    push();
    push('- **§ 4.5** OpenRewrite *recovers* from syntax errors — no throw, no ' +
      '`ParseExceptionResult` marker, still a well-formed `J.CompilationUnit`. The check the guide ' +
      'previously prescribed cannot detect this, so `JavaSyntaxCheck` asks javac instead.');
    push('- **§ 4.6** a reused parser must be `reset()` between parse sets, or the second read of any ' +
      'type reports "unparseable".');
    push('- **§ 4.7** an interface\'s `extends` clause is held in `getImplements()`, not ' +
      '`getExtends()`; reading the latter finds no supertype for any interface.');
    push();
  }

  // ---- phase state ---------------------------------------------------------
  push('## Phase 6 state');
  push();
  push('| Measure | Count |');
  push('| --- | --- |');
  push(`| Files in the migration queue | ${progress.total} |`);
  push(`| Settled (complete or exempt) | ${progress.settled} (${progress.percentSettled}%) |`);
  push(`| Remaining | ${progress.remaining} |`);
  push(`| Still importing JavaParser | ${progress.stillImporting} |`);
  push(`| Unclassified by curation | ${model.unclassifiedFiles.length} |`);
  push(`| JavaParser import lines to remove | ${model.totals.queueImportLines} |`);
  push();

  if (progress.settled === 0) {
    push('**No file has been ported yet.** Phase 6 is at the checklist stage. The');
    push('prerequisites below gate the first port.');
    push();
  }

  // ---- prerequisites -------------------------------------------------------
  push('## Phase-0 prerequisites');
  push();
  push('These are not part of the queue: they are the conditions that must hold');
  push('before a port can be verified. Each was found by running the tooling, and each');
  push('is real in the tree today.');
  push();
  push('| Id | Prerequisite | Done |');
  push('| --- | --- | --- |');
  for (const prereq of PREREQUISITES) {
    push(`| ${prereq.id} | ${prereq.title} | ${prereq.done ? 'yes' : '**no**'} |`);
  }
  push();
  for (const prereq of PREREQUISITES.filter((p) => !p.done)) {
    push(`### ${prereq.id} — ${prereq.title}`);
    push();
    push(prereq.why);
    push();
    push(`*Evidence:* ${prereq.evidence}`);
    push();
  }

  // ---- dependency set ------------------------------------------------------
  push('## Dependency set');
  push();
  push(`Leaving JavaParser ${JAVAPARSER_VERSION}, arriving at OpenRewrite ${OPENREWRITE_VERSION}.`);
  push(`Versions are managed by the root POM; the set below mirrors \`merge-java\`, which`);
  push('is the one module already fully ported.');
  push();
  push('| Artifact | Version | Why |');
  push('| --- | --- | --- |');
  for (const dependency of DEPENDENCIES) {
    push(`| \`${dependency.artifact}\` | ${dependency.version} | ${dependency.why} |`);
  }
  push();

  // ---- how to read an entry ------------------------------------------------
  push('## How to read an entry');
  push();
  push('Each file below carries: the JavaParser surface measured on disk, the');
  push('OpenRewrite mapping for each type, and the migration notes from');
  push('`curation.js`. Mapping confidence is marked on every type:');
  push();
  push('- **verified** — the OpenRewrite side is already used in this repository');
  push('  (`merge-java`), so the shape is one this project has compiled.');
  push('- **inferred** — the documented LST type for the concept, but unused here.');
  push(`  Confirm against the OpenRewrite reference for ${OPENREWRITE_VERSION} before`);
  push('  relying on it.');
  push();

  // ---- the queue -----------------------------------------------------------
  push(`## The queue (${model.queueFiles.length} files, in work order)`);
  push();
  push('Ordered by priority, then by risk. See `## Per-file detail` for the notes.');
  push();
  push('| Status | Priority | Risk | Imports | File |');
  push('| --- | --- | --- | --- | --- |');
  for (const file of inWorkOrder(model.queueFiles)) {
    push(
      `| \`[${checkboxFor(file.status)}]\` ${file.status} | ${file.priority} | ${file.risk ?? '-'} | ` +
        `${file.imports.length} | \`${file.repoPath}\` |`,
    );
  }
  push();

  // ---- per-file detail -----------------------------------------------------
  push('## Per-file detail');
  push();
  for (const file of inWorkOrder(model.queueFiles)) {
    renderFile(push, file);
  }

  // ---- allowlist -----------------------------------------------------------
  push(`## Exempt (${model.exemptFiles.length})`);
  push();
  push('Files that legitimately keep a JavaParser reference. Each states why, and what');
  push('removes the exemption — an allowlist entry without an exit condition is a hole.');
  push();
  for (const file of model.exemptFiles) {
    push(`### \`${file.repoPath}\``);
    push();
    push(`**Reason**: ${file.allow.reason}`);
    push();
    push(`**Deferred to**: ${file.allow.deferredTo}`);
    push();
  }
  if (model.exemptFiles.length === 0) {
    push('None.');
    push();
  }

  // ---- outside the queue ---------------------------------------------------
  if (model.nonQueueFiles.length > 0) {
    push(`## Outside the queue (${model.nonQueueFiles.length})`);
    push();
    push('Documentation and archive. These files *are* the migration’s own notes or a');
    push('historical record, so their JavaParser references are the subject matter.');
    push();
    push('| File | Why excluded |');
    push('| --- | --- |');
    for (const file of model.nonQueueFiles) {
      push(`| \`${file.repoPath}\` | ${file.nonQueueReason} |`);
    }
    push();
  }

  // ---- plan corrections ----------------------------------------------------
  push('## Corrections to the plan’s file list');
  push();
  push('`plans/rewrite-migration/06-Migration-Checklist.md` § Files to Migrate names');
  push('paths that do not exist and omits files that do. Following it literally migrates');
  push('the wrong set. The differences:');
  push();
  push('| Plan says | Reality |');
  push('| --- | --- |');
  push(
    '| `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/HttpBridgeStartupActivity.java` | ' +
      'does not exist |',
  );
  push(
    '| `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/JwaTextDocumentService.java` | ' +
      'the real file is `jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java` |',
  );
  push(
    '| `hipster-entity-tooling/src/test/java/.../validation/JavaParserTool.java` | ' +
      'the real `JavaParserTool` is main-source, at `.../tooling/validation/JavaParserTool.java` |',
  );
  push(
    '| `validation/EnumCompactionCliTest.java` listed twice | ' +
      'one file: `hipster-entity-tooling/src/test/java/.../validation/EnumCompactionCliTest.java` |',
  );
  push(
    '| `project-automation` — "all files using JavaParser" | ' +
      'the `hr.hrg.rewrite` package uses no JavaParser and does not compile; it needs Phase-0 ' +
      'repair, not a port |',
  );
  push(
    '| (not listed) | seven files use JavaParser **fully qualified with no import**: ' +
      '`ViewBuilderGenerator.java` (main), `meta/InterfaceInfo.java` (main),',
  );
  push(
    '| | `AddonAndInheritanceTest.java`, `CompactionRoundTripTest.java`, ' +
      '`SourceReaderTest.java`, `EnumCompactionCliTest.java`, and the `test` half of ' +
      '`DependencyBoundaryTest.java` |',
  );
  push();

  push('## Verification');
  push();
  push('```sh');
  push('bun run scripts/rewrite-migration/verify-migration.js          # the gate');
  push('bun run scripts/rewrite-migration/generate-checklist.js --check # this file is current');
  push('cmd /c "scripts\\mvn-jdk25.cmd -o -pl <module> -am -Dmaven.compiler.useIncrementalCompilation=false clean test"');
  push('```');
  push();
  push('The plan’s Check 1 (`grep -r "import com.github.javaparser" | grep -v test |');
  push('grep -v comment`) is **not** used, for two reasons recorded in');
  push('`scan-remaining-javafiles.js`: the filter drops every test file from the count,');
  push('and the pattern misses fully-qualified uses while matching');
  push('`org.openrewrite.java.JavaParser`, which is a different library’s class.');
  push();

  return `${lines.join('\n')}\n`;
}

/** One file's checklist section. */
function renderFile(push, file) {
  push(`### \`${file.repoPath}\``);
  push();
  push(
    `**Status**: \`[${checkboxFor(file.status)}]\` ${file.status}` +
      ` · **Priority**: ${file.priority}` +
      (file.risk ? ` · **Risk**: ${file.risk}` : '') +
      ` · **Detected**: ${file.classified}` +
      ` · **Lines**: ${file.lineCount}`,
  );
  push();

  push('**JavaParser surface on disk**:');
  push();
  if (file.imports.length === 0 && file.codeRefs.length === 0 && file.proseRefs.length === 0) {
    push('- (none)');
  } else if (file.imports.length === 0) {
    if (file.codeRefs.length > 0) {
      push('- No import lines. Every use is fully qualified, so an import-driven port');
      push('  misses this file:');
      for (const line of file.codeRefs) {
        push(`  - \`${line}\``);
      }
    }
    if (file.proseRefs.length > 0) {
      push(`- ${file.proseRefs.length} bare-name mention(s) — \`JavaParser\` or \`javaparser\``);
      push('  with no package qualifier. Prose, artifact ids and test assertions:');
      for (const line of file.proseRefs.slice(0, 6)) {
        push(`  - \`${line}\``);
      }
      if (file.proseRefs.length > 6) {
        push(`  - …and ${file.proseRefs.length - 6} more`);
      }
    }
  } else {
    for (const line of file.imports) {
      const fqn = line.replace(/^import\s+(?:static\s+)?/, '').replace(/;$/, '');
      const mapping = mappingFor(fqn);
      const mark = mapping ? `**${mapping.status}**` : '**UNMAPPED**';
      push(`- \`${fqn}\` — ${mark}`);
    }
  }
  push();

  if (file.queue) {
    push('**Migration notes**:');
    push();
    if (file.queue.riskReason) {
      push(`> **Why this is not mechanical**: ${file.queue.riskReason}`);
      push();
    }
    push(file.queue.note);
    push();
    if (file.queue.openrewrite?.length) {
      push(`**OpenRewrite classes involved**: ${file.queue.openrewrite.map((c) => `\`${c}\``).join(', ')}`);
      push();
    }
    if (file.queue.blocks) {
      push(`**Blocks**: ${file.queue.blocks}`);
      push();
    }
    if (file.queue.steps?.length) {
      push('**Steps**:');
      push();
      file.queue.steps.forEach((step, index) => push(`${index + 1}. ${step}`));
      push();
    }
  }

  push('**Port procedure**:');
  push();
  push('```sh');
  push(`bun run scripts/rewrite-migration/migrate-file.js --baseline ${file.repoPath}`);
  push(`# ... edit ...`);
  push(`cmd /c "scripts\\mvn-jdk25.cmd -o -pl ${file.module} -am -Dmaven.compiler.useIncrementalCompilation=false clean test"`);
  push(`bun run scripts/rewrite-migration/migrate-file.js --after ${file.repoPath}`);
  push('```');
  push();
}

// ------------------------------------------------------------------ tracker ---

/**
 * Append a `not-started` row for every queue file the tracker does not name.
 *
 * <p>Existing rows are never touched — this is a hand-maintained file, and the whole reason it is
 * separate from the generated checklist is that its statuses are the one thing no script may
 * overwrite. A row is appended only when the path does not appear anywhere in the file, which is what
 * makes a rename safe: the old path stays (and the gate reports it as stale so a human removes it),
 * while the new one gets a row rather than being silently untracked.</p>
 *
 * @returns {string[]} the paths appended
 */
function reconcileTracker(trackerPath, model) {
  const existing = readFileSync(trackerPath, 'utf8');
  const missing = inWorkOrder(model.queueFiles)
    .filter((file) => !existing.includes(file.repoPath))
    .map((file) => file.repoPath);

  if (missing.length === 0) {
    return [];
  }

  const newRows = missing.map((repoPath) => {
    const file = model.queueFiles.find((candidate) => candidate.repoPath === repoPath);
    return (
      `| \`- [ ] ${repoPath}\` | ${file.module} | ${file.priority} | ` +
      `${file.risk ?? '-'} | added by generate-checklist.js |`
    );
  });

  // The rows must land *inside* the `## Queue` section: the tracker parser is section-aware (it has
  // to be, or the status-vocabulary legend above reads as a list of files), so a row appended at
  // end-of-file is a row the gate still cannot see. Inserted after the last existing row of that
  // section, which keeps every hand-written row above it untouched.
  const lines = existing.split(/\r?\n/);
  let queueHeader = -1;
  for (let index = 0; index < lines.length; index++) {
    if (lines[index].trim() === '## Queue') {
      queueHeader = index;
      break;
    }
  }
  if (queueHeader === -1) {
    throw new UsageError(
      `${TRACKER_PATH} has no "## Queue" section, so new rows cannot be placed where the gate reads ` +
        'them. Restore the section heading, or delete the file and regenerate.',
    );
  }

  let insertAt = queueHeader + 1;
  for (let index = queueHeader + 1; index < lines.length; index++) {
    const trimmed = lines[index].trim();
    if (trimmed.startsWith('#')) {
      break; // the next section
    }
    if (trimmed.startsWith('|')) {
      insertAt = index + 1;
    }
  }

  lines.splice(insertAt, 0, ...newRows);
  writeFileSync(trackerPath, lines.join('\n'));
  return missing;
}

/**
 * The tracker template. Hand-maintained state, so it is written once and then
 * left alone — see the header of this file.
 */
function renderTracker(model, generatedAt) {
  const lines = [];
  const push = (line = '') => lines.push(line);

  push('# Phase 6 — Migration Tracker');
  push();
  push('> **Hand-maintained.** This file is the status source of truth, and nothing');
  push('> regenerates it. `Checklist.md` is generated from it, and');
  push('> `verify-migration.js` fails when a row here disagrees with the tree.');
  push();
  push(`Created: ${generatedAt}`);
  push();
  push('## Status vocabulary');
  push();
  push('| Mark | Status | Meaning |');
  push('| --- | --- | --- |');
  push('| `- [ ]` | `not-started` | No port work begun. |');
  push('| `- [~]` | `in-progress` | Mid-change; the file may not compile. |');
  push('| `- [>]` | `testing` | Ported and compiling; behaviour not yet proven. |');
  push('| `- [x]` | `complete` | Ported, and the module gate passes. |');
  push('| `- [!]` | `blocked` | Waiting on a named prerequisite — say which in Notes. |');
  push('| `- [-]` | `exempt` | Allowlisted; a JavaParser reference here is correct. |');
  push();
  push('Rules the gate enforces:');
  push();
  push('- A file that still imports JavaParser must not be `complete` or `testing`.');
  push('- A `blocked` row must name its blocker in Notes.');
  push('- Every queue file has exactly one row.');
  push();
  push('## Rules for editing');
  push();
  push('1. Set `testing` only when the module compiles with `clean`.');
  push('2. Set `complete` only when the module’s `clean test` gate passes.');
  push('3. Never set `complete` for a file whose port you have not seen run: this');
  push('   phase rewrites behaviour-bearing generators, and a compile is not a proof.');
  push('4. Add a row when the scanner finds a new file — `verify-migration.js` fails');
  push('   until you do.');
  push();
  push('## Queue');
  push();
  push('| Status | Module | Priority | Risk | Notes |');
  push('| --- | --- | --- | --- | --- |');
  for (const file of inWorkOrder(model.queueFiles)) {
    const note = file.queue?.riskReason ? 'see Checklist.md' : '';
    push(
      `| \`- [${checkboxFor(file.status)}] ${file.repoPath}\` | ${file.module} | ` +
        `${file.priority} | ${file.risk ?? '-'} | ${note} |`,
    );
  }
  push();
  push('## Exempt');
  push();
  push('| Status | Module | Priority | Risk | Notes |');
  push('| --- | --- | --- | --- | --- |');
  for (const file of model.exemptFiles) {
    push(
      `| \`- [${checkboxFor(file.status)}] ${file.repoPath}\` | ${file.module} | ` +
        `exempt | - | ${file.allow.deferredTo} |`,
    );
  }
  push();
  push('## Unclassified');
  push();
  if (model.unclassifiedFiles.length === 0) {
    push('None. Every file the scanner found is classified in `curation.js`.');
  } else {
    push('These were found by the scanner but have no `curation.js` entry. The gate fails');
    push('until each has a `QUEUE` row or an `ALLOWLIST` row with a reason.');
    push();
    for (const file of model.unclassifiedFiles) {
      push(`- \`${file.repoPath}\``);
    }
  }
  push();

  return `${lines.join('\n')}\n`;
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
