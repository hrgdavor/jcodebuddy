#!/usr/bin/env node

/**
 * Injects file content into markdown documents, at the markers that name them.
 *
 * A marker is a line that is nothing but a link to a real path, labelled with
 * that same path:
 *
 *     [fixtures/example-1/before.md](./fixtures/example-1/before.md)
 *
 *     ```markdown
 *     ...                      <- replaced byte-for-byte from that file
 *     ```
 *
 * The marker can name a region, to inject part of a larger file rather than
 * all of it:
 *
 *     [fixtures/example-4/source.md](./fixtures/example-4/source.md#region:table)
 *
 * A region runs from `#region <name>` to the matching `#endregion`, under any
 * language's comment prefix (`#region`, `// #region`, `//region`,
 * `<!-- #region -->`, or a C-style block comment), and the directive lines
 * themselves are not injected — only what lies between them. Marker paths
 * resolve relative to the markdown file's directory first and to the
 * repository root second (see ../test-fixtures.js).
 *
 * Because the content is copied verbatim, the document cannot show a block
 * that differs from the file the tests use.
 *
 *   node scripts/inject-examples.mjs                    rewrite README.md in place
 *   node scripts/inject-examples.mjs --check            exit 1 if README.md is stale
 *   node scripts/inject-examples.mjs merge-java/docs/resolvers
 *       rewrite every markdown file under the given directories / files
 *       (repository-root-relative or absolute), e.g. all resolver READMEs
 */

import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve as resolvePath, sep } from 'node:path';
import { findMarkers, resolveMarker, ROOT } from '../test-fixtures.js';

const README = join(ROOT, 'README.md');
const FENCE = '```';

/**
 * Replace the body of the fenced block that follows `marker` in `lines`.
 * Returns the new line array, or throws when the marker/fence is malformed.
 */
function injectInto(lines, marker, content, doc) {
    const markerIndex = lines.findIndex((line) => line.trim() === marker.raw);
    if (markerIndex === -1) throw new Error(`marker not found in ${doc}: ${marker.raw}`);

    let open = -1;
    for (let i = markerIndex + 1; i < lines.length; i++) {
        if (lines[i].startsWith(FENCE)) { open = i; break; }
        if (lines[i].trim() !== '') {
            throw new Error(`expected a fenced code block right after the marker in ${doc}`);
        }
    }
    if (open === -1) throw new Error(`no code block after the marker in ${doc}`);

    let close = -1;
    for (let i = open + 1; i < lines.length; i++) {
        if (lines[i].startsWith(FENCE)) { close = i; break; }
    }
    if (close === -1) throw new Error(`unclosed code block after the marker in ${doc}`);

    const current = lines.slice(open + 1, close).join('\n');
    const next = [...lines.slice(0, open + 1), ...content.split('\n'), ...lines.slice(close)];

    return { lines: next, changed: current !== content };
}

/** Expand the positional arguments into the markdown files to process. */
function collectTargets(args) {
    const files = [];
    for (const arg of args) {
        const abs = resolvePath(ROOT, arg);
        const stat = statSync(abs, { throwIfNoEntry: false });
        if (!stat) throw new Error(`target not found: ${arg}`);
        if (stat.isDirectory()) collectMarkdown(abs, files);
        else files.push(abs);
    }
    const unique = [...new Set(files)];
    if (unique.length === 0) throw new Error(`no markdown files under: ${args.join(', ')}`);
    return unique;
}

function collectMarkdown(dir, out) {
    const entries = readdirSync(dir, { withFileTypes: true })
        .sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
        if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
        const full = join(dir, entry.name);
        if (entry.isDirectory()) collectMarkdown(full, out);
        else if (entry.isFile() && entry.name.endsWith('.md')) out.push(full);
    }
}

/** Inject every marker of one document; returns its block statistics. */
function processFile(file, check) {
    const doc = relative(ROOT, file).split(sep).join('/');
    const original = readFileSync(file, 'utf8');
    const eol = original.includes('\r\n') ? '\r\n' : '\n'; // write back with the document's own EOL
    let lines = original.split(/\r?\n/);

    const markers = findMarkers(lines);
    if (markers.length === 0) throw new Error(`no injection markers found in ${doc}`);

    const seen = new Set();
    const results = [];

    for (const marker of markers) {
        if (seen.has(marker.raw)) throw new Error(`duplicate marker in ${doc}: ${marker.raw}`);
        seen.add(marker.raw);

        let content;
        try {
            content = resolveMarker(marker, dirname(file));
        } catch (err) {
            throw new Error(`${doc}: ${marker.raw}: ${err.message}`);
        }

        const result = injectInto(lines, marker, content, doc);
        lines = result.lines;
        results.push({ marker, changed: result.changed });
    }

    for (const { marker, changed } of results) {
        console.log(`${changed ? 'updated ' : 'ok      '} ${doc}: ${marker.raw}`);
    }

    const updated = lines.join(eol);
    const stale = results.filter((result) => result.changed).length;
    if (!check && updated !== original) writeFileSync(file, updated, 'utf8');
    if (stale > 0) console.log(`${doc} ${check ? 'is stale' : 'updated'}: ${stale} block(s).`);
    return { total: results.length, stale };
}

function main(argv) {
    const check = argv.includes('--check');
    const positional = argv.filter((arg) => arg !== '--check');
    const unknown = positional.find((arg) => arg.startsWith('-'));
    if (unknown) throw new Error(`unknown option: ${unknown}`);

    const targets = positional.length === 0 ? [README] : collectTargets(positional);
    let total = 0;
    let stale = 0;
    for (const file of targets) {
        const result = processFile(file, check);
        total += result.total;
        stale += result.stale;
    }

    if (check) {
        if (stale > 0) {
            console.error(`\n${stale} of ${total} block(s) differ from their files.`);
            console.error('Run: node scripts/inject-examples.mjs <same targets>');
            return 1;
        }
        console.log(`\nAll ${total} marker(s) match their files.`);
        return 0;
    }

    console.log(`\n${total} marker(s) processed, ${stale} updated.`);
    return 0;
}

try {
    process.exitCode = main(process.argv.slice(2));
} catch (err) {
    console.error(`Error: ${err.message}`);
    process.exitCode = 1;
}
