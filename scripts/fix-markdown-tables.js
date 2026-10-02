#!/usr/bin/env bun
/**
 * fix-markdown-tables.js — the sweep form of AGENTS.md § 2's markdown-table rule: run the `md-fix-tables` tool
 * over every tracked Markdown file, one file per invocation, and prove afterwards that the run touched tables
 * and nothing else.
 *
 * WHY THE RULE NEEDS A SCRIPT AT ALL. The rule is "run the tool on the document you changed", and for a single
 * document that is the whole of it. Over 263 files it is not, because a sweep is where a formatter's mistakes
 * stop being visible one at a time. This repository has the evidence: the first sweep (2026-10-03, commit
 * 47f246c) ran `md-fix-tables` 1.0.0 and it did four things that were not table formatting —
 *
 *   1. **It rewrote `|`-bearing lines inside fenced code blocks as if they were table rows.** A flow diagram's
 *      `|` shaft came back as `|  |`, a directory tree's pointer shafts were re-padded out of alignment under
 *      their `^` markers, and a Java continuation line beginning with `||` came back as
 *      `|     | resolution.getExplanation().contains("both"), |` — operator and indentation gone, the sample no
 *      longer Java. This script **restores every line inside a fenced block to its original text** and prints
 *      what it restored.
 *   2. **It wrote LF on every row it rewrote.** The committed content here is LF, but the working tree is not
 *      uniform: 133 of the tracked Markdown files are checked out CRLF, 128 are LF, two are mixed. This script
 *      rebuilds each file with **every line keeping its own original ending**, so nothing becomes mixed within
 *      itself; only cell whitespace moves.
 *   3. **It pulled an indented table out to column 0**, which ends the list item a nested table belongs to. Two
 *      documents were damaged that way and repaired by `scripts/restore-table-indent.js`.
 *   4. **It widened a table to fit a row with more cells than the header** — one unescaped `|` inside a cell was
 *      enough to add a column to the whole table.
 *
 * All four are fixed in `md-fix-tables` 1.1.0 (D:\wrk\utils\md-fix-tables), and this script is what verified
 * they were bugs rather than opinions: it kept the check while the tool could not pass it. **The check stays**,
 * because a promise about a tool nobody re-measures is the promise that rots — and because a future version can
 * regress in exactly these four ways.
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
 *     compared by cell count only, since rewriting its dashes to the column width is the tool's job;
 *   - a row lost its leading indentation (the check that would have caught 1.0.0's third bug on the day).
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

/**
 * The command to run. `JCODEBUDDY_MD_FIX_TABLES` overrides it — a command, not only a path, so the check can be
 * pointed at one build of the tool (`node /path/to/an-older/md-fix-tables.js`) and shown to fail on an input
 * the current one gets right. That is how the check itself was verified rather than assumed to work; the shape
 * matches `JCODEBUDDY_JDK25` / `JCODEBUDDY_MVN` in `scripts/lib/toolchain.js`.
 */
const override = (process.env.JCODEBUDDY_MD_FIX_TABLES ?? '').split(' ').filter(Boolean);
const command = override.length > 0 ? override[0] : findTool();
const commandArgs = override.slice(1);

if (command === null || command === undefined) {
    console.error('md-fix-tables is not on PATH.\n\n'
        + 'It is the tool AGENTS.md Section 2 requires for Markdown tables: one argument, the path to a .md '
        + 'file, edited in place.\n'
        + 'Install it and put it on PATH for the user running the agent — hand-aligning is not the same thing, '
        + 'and this repository has the scar: one row\'s escaped \\| had padding inserted inside the escape, '
        + 'which silently added a column.\n'
        + '(`JCODEBUDDY_MD_FIX_TABLES` overrides the command, for checking a specific build.)');
    process.exit(2);
}

const splitLines = (text) => text.split(/\r\n|\n|\r/);
const splitEndings = (text) => text.match(/\r\n|\n|\r/g) ?? [];

/** A table row is a line that leads with `|`; this repository has no table without outer pipes (checked). */
const isRow = (line) => line.trimStart().startsWith('|');

/** The leading run of spaces and tabs: the indentation that keeps a nested table in its list item. */
const indentOf = (line) => /^[ \t]*/.exec(line)[0];

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

    // The block's indentation is its first row's, and every row of the block is re-emitted at it. A row that
    // leaves it is a table that has left whatever it was nested in — the list item whose content it was — so
    // this is compared block by block rather than line by line. It is the check 1.0.0 would have failed on two
    // documents in this repository. Lines inside a fence are not compared here: whatever the tool did to them is
    // restored below, which is the repair rather than a refusal.
    let blockIndent = null;
    for (let i = 0; i < beforeLines.length; i++) {
        if (!isRow(beforeLines[i])) {
            blockIndent = null;
            continue;
        }
        if (blockIndent === null) blockIndent = indentOf(beforeLines[i]);
        if (fenced[i] || fencedAfter[i]) continue;
        if (indentOf(afterLines[i]) !== blockIndent) {
            refuse.push(`line ${i + 1}: the row left its indentation `
                + `(${JSON.stringify(blockIndent)} -> ${JSON.stringify(indentOf(afterLines[i]))})`);
        }
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
        execFileSync(command, [...commandArgs, copy], { stdio: ['ignore', 'pipe', 'pipe'] });
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
