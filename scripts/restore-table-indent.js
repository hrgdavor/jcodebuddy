#!/usr/bin/env bun
/**
 * Repair: put back the indentation the first table sweep took away.
 *
 * WHAT HAPPENED. The 2026-10-03 sweep (commit 47f246c) ran `md-fix-tables` 1.0.0 over every tracked
 * Markdown file. That version re-emitted every table row from column 0 — its README said so: "a line that
 * already starts with `|` but is indented keeps its cells, but not its indentation". Two documents had a
 * table written *inside a bullet item*, where the indentation is what makes it part of the item:
 *
 *   - webview/PLAN-eclipse-host.md (15 rows): the "What is stale" table under "One commit updates every
 *     document that enumerates hosts".
 *   - webview/doc/ide-observation-checklist.md (4 rows).
 *
 * Pulling those rows to column 0 ends the list item and leaves the table as a new block, which is a
 * structural change to the document rather than table formatting. The tool's 1.1.0 fixes it at the source —
 * it re-emits each row at the block's own indentation — so the repair is to give the rows their indentation
 * back; the alignment the sweep produced is already correct and is not touched.
 *
 * HOW IT IS SURE IT PUTS THE RIGHT INDENT BACK. `47f246c^` is the file as it stood before the sweep, and the
 * tool never adds or removes lines: a row is rewritten in place. So line *i* of the old file is line *i* of
 * the current one, and the indentation to restore is read from the old file at the same index. Nothing is
 * guessed, and a line whose shape does not match — an indented table row before, a column-0 row now — is
 * reported rather than changed.
 *
 * Usage:
 *   bun scripts/restore-table-indent.js --check    report what would change, and change nothing
 *   bun scripts/restore-table-indent.js            restore, then re-align those two files
 *
 * The fixed tool must be on `PATH`; after the restore each file is put through it, so the result is what
 * 1.1.0 would emit rather than a hand-made approximation.
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const check = process.argv.includes('--check');
const SWEEP = '47f246c'; // the commit whose tool version stripped the indentation

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });

/** Files the sweep has to be re-examined for: everything it touched. */
const touched = git('diff', '--name-only', `${SWEEP}^`, SWEEP).split('\n').map((f) => f.trim()).filter(Boolean);

const indentOf = (line) => /^[ \t]*/.exec(line)[0];
let repaired = 0;
let rows = 0;

for (const file of touched) {
    let before;
    try {
        before = git('show', `${SWEEP}^:${file}`).split('\n');
    } catch {
        continue; // added or deleted in that commit
    }
    const current = readFileSync(`${root}/${file}`, 'utf8').split('\n');

    if (before.length !== current.length) {
        console.error(`SKIP ${file}: line count changed (${before.length} -> ${current.length}), so rows cannot be mapped`);
        continue;
    }

    let changed = 0;
    for (let i = 0; i < current.length; i++) {
        const indent = indentOf(before[i]);
        if (indent.length === 0) continue;
        // The old tool wrote the row from column 0, so the mark of the bug is exactly this pair.
        if (!/^[ \t]+\|/.test(before[i]) || !/^\|/.test(current[i])) continue;
        current[i] = indent + current[i];
        changed++;
    }

    if (changed === 0) continue;
    repaired++;
    rows += changed;
    console.log(`${check ? 'would restore' : 'restored'} ${changed} row(s) in ${file}`);

    if (check) continue;

    writeFileSync(`${root}/${file}`, current.join('\n'));

    // Canonical form: the fixed tool keeps the indentation and re-aligns the grid.
    execFileSync('md-fix-tables', [`${root}/${file}`], { stdio: ['ignore', 'pipe', 'pipe'] });
    execFileSync('md-fix-tables', ['--check', `${root}/${file}`], { stdio: ['ignore', 'pipe', 'pipe'] });
}

console.log(`\n${check ? 'DRY RUN' : 'APPLIED'}: ${repaired} file(s), ${rows} indented row(s)`
    + (repaired === 0 ? ' — nothing to repair' : ''));
