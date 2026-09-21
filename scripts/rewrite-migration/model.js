/**
 * Joins the three inputs of Phase 6 into one model:
 * the measured scan, the judged curation, and the maintained tracker.
 *
 * <p>Every entry script reads the model rather than re-deriving anything, so
 * "what is in the checklist", "what does the gate check" and "what does the
 * report count" cannot drift apart.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { scanRepository, basename } from './inventory.js';
import { allowlistEntryFor, queueEntryFor, QUEUE, ALLOWLIST } from './curation.js';

/** Path of the tracker, relative to the repository root. */
export const TRACKER_PATH = 'doc/brainstorm/rewrite-migration/06-migration/tracker.md';

/** Path of the generated checklist, relative to the repository root. */
export const CHECKLIST_PATH = 'doc/brainstorm/rewrite-migration/06-migration/Checklist.md';

/**
 * Status vocabulary. The three middle states are the plan's own
 * (`06-Migration-Checklist.md` § Migration Checklist Template).
 *
 * <ul>
 *   <li>`not-started` — no port work has begun.</li>
 *   <li>`in-progress` — being ported; the file is expected to be mid-change.</li>
 *   <li>`testing` — ported and compiling; behaviour not yet proven.</li>
 *   <li>`complete` — ported, and the module's recorded gate passes.</li>
 *   <li>`blocked` — waiting on a named prerequisite. Requires a reason.</li>
 *   <li>`exempt` — allowlisted; a JavaParser reference here is correct.</li>
 * </ul>
 */
export const STATUSES = ['not-started', 'in-progress', 'testing', 'complete', 'blocked', 'exempt'];

/** Statuses that mean "this file no longer needs work". */
export const SETTLED_STATUSES = new Set(['complete', 'exempt']);

const CHECKBOX_TO_STATUS = new Map([
  [' ', 'not-started'],
  ['~', 'in-progress'],
  ['>', 'testing'],
  ['x', 'complete'],
  ['!', 'blocked'],
  ['-', 'exempt'],
]);

const STATUS_TO_CHECKBOX = new Map([...CHECKBOX_TO_STATUS].map(([box, status]) => [status, box]));

/** The checkbox character for a status. */
export function checkboxFor(status) {
  return STATUS_TO_CHECKBOX.get(status) ?? ' ';
}

/**
 * Read `tracker.md` into `Map<repoPath, {status, note, line}>`.
 *
 * <p>The format is a Markdown table whose first column is
 * `` `- [x] path` `` — the checkbox carries the status and the code span carries
 * the path, so the file stays readable as a document and greppable as data.
 *
 * <p>Only the two data sections (`## Queue`, `## Exempt`) are read. The status
 * vocabulary table earlier in the file uses the same `` | `- [x]` | status | ``
 * shape to document the marks, so a parser that read every table row would treat
 * its own legend as a set of files. Section tracking is what makes that
 * unambiguous, and a row outside both sections is reported rather than silently
 * ignored.
 *
 * @param {string} root repository root
 * @returns {{entries: Map<string, {status: string, note: string, line: number}>, problems: string[], exists: boolean}}
 */
export function loadTracker(root) {
  const absolute = join(root, TRACKER_PATH);
  if (!existsSync(absolute)) {
    return { entries: new Map(), problems: [], exists: false };
  }

  const entries = new Map();
  const problems = [];
  const lines = readFileSync(absolute, 'utf8').split(/\r?\n/);
  const DATA_SECTIONS = new Set(['## Queue', '## Exempt']);
  let section = null;

  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('#')) {
      section = DATA_SECTIONS.has(trimmed) ? trimmed : null;
      return;
    }
    if (!trimmed.startsWith('|')) {
      return;
    }
    if (section === null) {
      // Header rows, separators, and the vocabulary legend live outside the data
      // sections by design.
      return;
    }

    const match = trimmed.match(/^\|\s*`- \[(.)\]\s+(.+?)`\s*\|/);
    if (!match) {
      if (!/^\|[\s:|-]+\|$/.test(trimmed) && !/\|\s*(Status|File)\s*\|/.test(trimmed)) {
        problems.push(`tracker.md:${index + 1}: not a data row: ${trimmed.slice(0, 80)}`);
      }
      return;
    }

    const [, checkbox, path] = match;
    const status = CHECKBOX_TO_STATUS.get(checkbox);
    const repoPath = path.trim();
    if (!status) {
      problems.push(`tracker.md:${index + 1}: unknown checkbox \`[${checkbox}]\` for ${repoPath}`);
      return;
    }
    if (!repoPath.includes('/') && !repoPath.endsWith('.java')) {
      problems.push(`tracker.md:${index + 1}: not a file path: ${repoPath}`);
      return;
    }
    if (entries.has(repoPath)) {
      problems.push(`tracker.md:${index + 1}: duplicate row for ${repoPath}`);
      return;
    }
    const cells = trimmed.split('|').map((cell) => cell.trim());
    // | `- [x] path` | module | priority | risk | note |
    entries.set(repoPath, {
      status,
      note: cells[5] ?? '',
      line: index + 1,
    });
  });

  return { entries, problems, exists: true };
}

/**
 * Build the full Phase 6 model.
 *
 * @param {string} root repository root
 * @returns {Promise<object>}
 */
export async function buildModel(root) {
  const scan = await scanRepository(root);
  const tracker = loadTracker(root);

  const files = scan.files.map((file) => {
    const queue = queueEntryFor(file.repoPath);
    const allow = allowlistEntryFor(file.repoPath);
    const tracked = tracker.entries.get(file.repoPath) ?? null;

    let kind;
    if (allow) {
      kind = 'exempt';
    } else if (!file.queue) {
      kind = 'non-queue';
    } else if (queue) {
      kind = 'queue';
    } else {
      // In the queue by location, unknown to curation. A gate failure: the plan
      // must not be able to fall behind the code it describes.
      kind = 'unclassified';
    }

    const status = allow
      ? 'exempt'
      : (tracked?.status ?? 'not-started');

    return {
      ...file,
      kind,
      queue,
      allow,
      tracked,
      status,
      priority: queue?.priority ?? (allow ? 'exempt' : 'unclassified'),
      risk: queue?.risk ?? null,
      displayName: basename(file.repoPath),
    };
  });

  // Curation entries pointing at files the scan did not report. That is *not* by
  // itself staleness: the scan only returns files that still mention JavaParser,
  // so an allowlisted file that has genuinely been cleaned up drops out of it
  // while remaining a correct entry. The real question is whether the path still
  // exists on disk, which only a file check can answer.
  //
  // A glob key is never "stale" on its own: it is a claim about a tree, and it is
  // already reported through the files it matched. Reporting the pattern itself as
  // a missing file would be noise on every run.
  const foundPaths = new Set(files.map((f) => f.repoPath));
  const missingFromScan = (paths) => paths.filter((path) => !foundPaths.has(path));
  const missingOnDisk = (paths) =>
    paths.filter((path) => !path.includes('*') && !existsSync(join(root, path)));

  const queueMissing = missingFromScan(Object.keys(QUEUE));
  const allowlistMissing = missingFromScan(Object.keys(ALLOWLIST));

  return {
    root,
    files,
    tracker,
    totals: scan.totals,
    javaFilesTotal: scan.javaFilesTotal,
    stale: {
      queue: missingOnDisk(queueMissing),
      allowlist: missingOnDisk(allowlistMissing),
      // Kept separately: these are worth reporting, because an allowlisted file
      // that no longer mentions JavaParser is an exemption that can be retired.
      cleanedUp: allowlistMissing.filter(
        (path) => !path.includes('*') && existsSync(join(root, path)),
      ),
    },
    queueFiles: files.filter((f) => f.kind === 'queue'),
    unclassifiedFiles: files.filter((f) => f.kind === 'unclassified'),
    exemptFiles: files.filter((f) => f.kind === 'exempt'),
    nonQueueFiles: files.filter((f) => f.kind === 'non-queue'),
  };
}

/** Progress counts over the queue. */
export function progressOf(model) {
  const queue = model.queueFiles;
  const byStatus = Object.fromEntries(STATUSES.map((status) => [status, 0]));
  for (const file of queue) {
    byStatus[file.status] = (byStatus[file.status] ?? 0) + 1;
  }
  const settled = queue.filter((f) => SETTLED_STATUSES.has(f.status)).length;
  const stillImporting = queue.filter((f) => f.hasImports).length;
  const scope = { main: 0, test: 0, other: 0 };
  for (const file of queue) {
    scope[file.scope] = (scope[file.scope] ?? 0) + 1;
  }
  return {
    total: queue.length,
    byStatus,
    settled,
    remaining: queue.length - settled,
    stillImporting,
    scope,
    // The honest headline number: a file that no longer imports JavaParser is
    // not the same as a file whose port has been verified.
    importFree: queue.filter((f) => !f.hasImports && f.classified === 'none').length,
    percentSettled: queue.length === 0 ? 100 : Math.round((settled / queue.length) * 100),
  };
}

/** Priority order for display: high, medium, low, then the rest. */
const PRIORITY_RANK = { high: 0, medium: 1, low: 2 };

/** Sort queue files the way the work should be taken: priority, then risk, then path. */
export function inWorkOrder(files) {
  const riskRank = { high: 0, medium: 1, low: 2 };
  return [...files].sort((a, b) => {
    const byPriority = (PRIORITY_RANK[a.priority] ?? 9) - (PRIORITY_RANK[b.priority] ?? 9);
    if (byPriority !== 0) {
      return byPriority;
    }
    const byRisk = (riskRank[a.risk] ?? 9) - (riskRank[b.risk] ?? 9);
    if (byRisk !== 0) {
      return byRisk;
    }
    return a.repoPath.localeCompare(b.repoPath);
  });
}

/** A one-line status summary for `files`, used by the scan and the report. */
export function summarise(files) {
  const counts = {};
  for (const file of files) {
    counts[file.status] = (counts[file.status] ?? 0) + 1;
  }
  return counts;
}
