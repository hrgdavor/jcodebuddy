#!/usr/bin/env bun
/**
 * fix-markdown-tables.js — the sweep form of AGENTS.md § 2's markdown-table rule: run the `md-fix-tables` tool
 * over every tracked Markdown file, one file per invocation, and prove afterwards that the run touched tables
 * and nothing else.
 *
 * WHY THE RULE NEEDS A SCRIPT AT ALL. The rule is "run the tool on the document you changed", and for a single
 * document that is the whole of it. Over 263 files it is not, because of two properties of the tool that only a
 * sweep exposes:
 *
 *   1. **It rewrites `|`-bearing lines inside fenced code blocks as if they were table rows.** Measured, not
 *      guessed: a lone `|` used as a flow-diagram shaft comes back as `|  |`, a directory tree's pointer shafts
 *      are re-padded out of alignment under their `^` markers, and a Java continuation line beginning with `||`
 *      inside a ```java sample came back as
 *      `|     | resolution.getExplanation().contains("both"), |` — the operator and the indentation gone, and
 *      the sample no longer Java. This script **restores every line inside a fenced block to its original
 *      text** and prints what it restored, so the drawing and the code survive the sweep. (It is a real
 *      limitation of the tool, not of the document: AGENTS.md § 2 records it as such.)
 *   2. **It writes LF on the lines it rewrites.** The committed content here is LF, but the working tree is
 *      not uniform: 133 of the tracked Markdown files are checked out CRLF, 128 are LF, and two are already
 *      mixed. This script rebuilds each file with **every line keeping its own original ending**, so a CRLF
 *      file stays CRLF and a mixed one keeps its mix; only cell whitespace moves.
 *
 * Nothing is written that fails the check: the tool runs on a temporary copy, the copy is repaired and
 * re-verified, and only a clean result is written back. A damaged file on disk is worse than an incomplete
 * sweep.
 *
 * THE CHECK, exactly. Before and after are compared line by line, and a file fails if:
 *   - the line count changed, or the number or shape of line endings changed;
 *   - the fenced/plain structure changed (a fence was opened, closed or moved);
 *   - a changed line is not a table row (a line leading with `|`) in either version **and is not inside a
 *     fence** — fenced changes are restored, not failed;
 *   - a row's cells differ after trimming — trailing empty cells ignored, because the tool pads a short row out
 *     to the table's column count and `| a | b |` and `| a | b |  |  |` render identically. A separator row is
 *     compared by cell count only, since rewriting its dashes to the column width is the tool's job.
 * The cell comparison is what catches a phantom column: the count changes, or the content does.
 *
 * A cell-count change is *not* repaired automatically, and that is deliberate: it means a row was split on a
 * bare `|` inside a code span (or a row broken by a blank line), which is a bug in the document. The file is
 * left untouched and named, and the source gets fixed first — the tool is not asked to guess.
 *
 * Usage:
 *   bun scripts/fix-markdown-tables.js --check    report what would change, and change nothing
 *   bun scripts/fix-markdown-tables.js            run the tool over every tracked .md file, in place
 *
 * Run `--check` after a sweep and it must report nothing; that is how idempotence is checked.
 *
 * The tool must be on `PATH`: it is a user-provided program, and a missing `md-fix-tables` is reported with the
 * fix to apply rather than as a stack trace — the same shape `scripts/lib/toolchain.js` uses for a JDK that is
 * not JDK 25.
 */

import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const check = process.argv.includes('--check');

/** Every tracked Markdown file — tracked, because that is the set a reviewer sees (`proto/` is untracked). */
const files = execFileSync('git', ['ls-files', '-z', '*.md'], { cwd: root, encoding: 'utf8' })
    .split('\0').filter(Boolean);

/** The tool, by name, on `PATH` — with the reason it is required when it is not there. */
function findTool() {
    const exts = process.platform === 'win32'
        ? (process.env.PATHEXT ?? '.COM;.EXE;.BAT;.CMD').split(';').filter(Boolean).flatMap((e) => [e, e.toLowerCase()])
        : [''];
    const dirs = (process.env.PATH ?? '').split(process.platform === 'win32' ? ';' : ':').filter(Boolean);
    for (const dir of dirs) {
        for (const ext of exts) {
            const candidate = join(dir, `md-fix-tables${ext}`);
            if (existsSync(candidate)) return candidate;
        }
    }
    return null;
}

const tool = findTool();
if (tool === null) {
    console.error('md-fix-tables is not on PATH.\n\n'
        + 'It is the tool AGENTS.md Section 2 requires for Markdown tables: one argument, the path to a .md '
        + 'file, edited in place.\n'
        + 'Install it and put it on PATH for the user running the agent — hand-aligning is not the same thing, '
        + 'and this repository has the scar: one row\'s escaped \\| had padding inserted inside the escape, '
        + 'which silently added a column.');
    process.exit(2);
}

const splitLines = (text) => text.split(/\r\n|\n|\r/);
const splitEndings = (text) => text.match(/\r\n|\n|\r/g) ?? [];

/** A table row is a line that leads with `|`; this repository has no table without outer pipes (checked). */
const isRow = (line) => line.trimStart().startsWith('|');

/** GFM cells: an unescaped `|` delimits, `\|` is literal, and the outer pipes are optional and dropped. */
function cells(line) {
    const out = [];
    let cell = '';
    for (let i = 0; i < line.length; i++) {
        if (line[i] === '\\' && line[i + 1] === '|') { cell += '|'; i++; continue; }
        if (line[i] === '|') { out.push(cell); cell = ''; continue; }
        cell += line[i];
    }
    out.push(cell);
    if (out.length > 0 && out[0].trim() === '') out.shift();
    if (out.length > 0 && out[out.length - 1].trim() === '') out.pop();
    return out.map((c) => c.trim());
}

/** `| --- | :-: |` — its dashes are the tool's to rewrite; its shape is not. */
const isSeparator = (line) => {
    const parts = cells(line);
    return parts.length > 0 && parts.every((c) => /^:?-+:?$/.test(c));
};

/** A short row padded out to the table's column count gains empty cells that render as nothing. */
const withoutTrailingEmpties = (parts) => {
    const out = [...parts];
    while (out.length > 0 && out[out.length - 1] === '') out.pop();
    return out;
};

/** Which lines sit inside a fenced code block. */
function fenceMask(lines) {
    const mask = [];
    let fence = null;
    for (const line of lines) {
        const marker = /^\s*(```|~~~)/.exec(line);
        if (marker) {
            mask.push(fence !== null);
            fence = fence === null ? marker[1] : null;
            continue;
        }
        mask.push(fence !== null);
    }
    return mask;
}

const sameMask = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

/** What the tool did: the lines to restore, and the reasons to refuse the file. */
function inspect(beforeLines, afterLines) {
    const fenced = fenceMask(beforeLines);
    const fencedAfter = fenceMask(afterLines);
    const restore = [];
    const refuse = [];

    if (beforeLines.length !== afterLines.length) {
        refuse.push(`line count changed: ${beforeLines.length} -> ${afterLines.length}`);
        return { restore, refuse };
    }
    if (!sameMask(fenced, fencedAfter)) {
        refuse.push('the fenced-code-block structure changed');
        return { restore, refuse };
    }

    for (let i = 0; i < beforeLines.length; i++) {
        const a = beforeLines[i];
        const b = afterLines[i];
        if (a === b) continue;
        const where = `line ${i + 1}`;
        if (fenced[i]) {
            // The tool's reach inside a fence is never table formatting, so the line goes back.
            restore.push({ index: i, where, before: a, after: b });
            continue;
        }
        const shown = `\n      - ${a}\n      + ${b}`;
        if (!isRow(a) && !isRow(b)) {
            refuse.push(`${where}: a non-table line changed${shown}`);
            continue;
        }
        const ca = withoutTrailingEmpties(cells(a));
        const cb = withoutTrailingEmpties(cells(b));
        if (ca.length !== cb.length) {
            refuse.push(`${where}: cell count changed ${ca.length} -> ${cb.length}${shown}`);
        } else if (!isSeparator(a) && !isSeparator(b) && ca.join('\u0000') !== cb.join('\u0000')) {
            refuse.push(`${where}: cell content changed${shown}`);
        }
    }
    return { restore, refuse };
}

const scratch = mkdtempSync(join(tmpdir(), 'md-fix-tables-'));
const tally = { changed: 0, refused: 0, restored: 0, restoredFiles: 0, tableLines: 0 };

try {
    for (const file of files) {
        const path = join(root, file);
        const before = readFileSync(path, 'utf8');
        const endings = splitEndings(before);
        const beforeLines = splitLines(before);

        // The tool always runs on a copy: anything that fails the check is never written back.
        const copy = join(scratch, file.replace(/[\\/]/g, '__'));
        copyFileSync(path, copy);
        execFileSync(tool, [copy], { stdio: ['ignore', 'pipe', 'pipe'] });
        const after = readFileSync(copy, 'utf8');
        const afterLines = splitLines(after);

        if (before === after) continue;

        const { restore, refuse } = inspect(beforeLines, afterLines);

        if (refuse.length > 0) {
            tally.refused += refuse.length;
            console.error(`REFUSED ${file} (left untouched)\n    ${refuse.join('\n    ')}`);
            continue;
        }

        const repaired = [...afterLines];
        for (const line of restore) {
            repaired[line.index] = line.before;
        }

        // Re-verify what will actually be written: after the fenced lines are back, every remaining change
        // must be a table row.
        const recheck = inspect(beforeLines, repaired);
        if (recheck.refuse.length > 0 || recheck.restore.length > 0) {
            tally.refused += 1;
            console.error(`REFUSED ${file} (left untouched)\n    the repair did not verify: `
                + `${[...recheck.refuse, ...recheck.restore.map((r) => r.where)].join(', ')}`);
            continue;
        }

        let changedLines = 0;
        for (let i = 0; i < beforeLines.length; i++) {
            if (beforeLines[i] !== repaired[i]) {
                changedLines++;
                if (isRow(repaired[i])) tally.tableLines++;
            }
        }

        // Reported before the "nothing left to change" test: a file whose only difference was inside a fence is
        // still evidence of what the tool would have done to it, and that belongs in the record.
        if (restore.length > 0) {
            tally.restored += restore.length;
            tally.restoredFiles++;
            console.log(`${file}: restored ${restore.length} fenced line(s) the tool rewrote:`);
            for (const line of restore) {
                console.log(`    ${line.where}`);
                console.log(`      tool wanted: ${line.after.trim()}`);
                console.log(`      kept:        ${line.before.trim()}`);
            }
        }

        if (changedLines === 0) {
            if (restore.length > 0) console.log(`    (nothing else in ${file} changed, so it is untouched)`);
            continue;
        }

        tally.changed++;
        if (check) {
            console.log(`would change ${file} (${changedLines} line(s))`);
            continue;
        }

        // Every line keeps its own original ending, so a CRLF file stays CRLF and a mixed one keeps its mix.
        const text = repaired.map((line, i) => line + (endings[i] ?? '')).join('');
        if (text !== before) writeFileSync(path, text);
        console.log(`changed ${file} (${changedLines} line(s))`);
    }
} finally {
    rmSync(scratch, { recursive: true, force: true });
}

console.log(`\n${check ? 'DRY RUN' : 'APPLIED'}: ${files.length} tracked Markdown files, `
    + `${tally.changed} ${check ? 'would change' : 'changed'}, ${tally.tableLines} table lines rewritten`
    + (tally.restored > 0 ? `, ${tally.restored} fenced line(s) in ${tally.restoredFiles} file(s) restored` : ''));

if (tally.refused > 0) {
    console.error(`\n${tally.refused} file(s) were refused and left untouched. Both known causes are bugs in the `
        + 'document rather than in the tool: an unescaped `|` inside a code span (the row is split and the header '
        + 'padded, inventing a column) and a table row broken by a blank line. Fix the source, then run again.');
    process.exit(1);
}
