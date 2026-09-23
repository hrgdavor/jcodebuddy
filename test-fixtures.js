/**
 * Marker discovery and content resolution for scripts/inject-examples.mjs.
 *
 * A marker is a line that is nothing but a link to a real path, labelled with
 * that same path:
 *
 *     [fixtures/example-1/before.md](./fixtures/example-1/before.md)
 *
 * optionally with a region fragment naming a part of that file:
 *
 *     [fixtures/example-4/source.md](./fixtures/example-4/source.md#region:table)
 *
 * The path resolves relative to the markdown file's own directory first and
 * relative to the repository root second, so a marker works from any document
 * depth — and because the label is the path, the rendered line is a working
 * link to the source in any README viewer.
 *
 * A region runs from `#region <name>` to the matching `#endregion`, under any
 * language's comment prefix (`#region`, `// #region`, `//region`, `//#region`,
 * `<!-- #region -->`, or a C-style block comment); the directive lines
 * themselves are not part of the content. Because the content is copied
 * verbatim (line endings normalised, one trailing newline dropped for whole
 * files), a README cannot show a block that differs from the file the tests
 * use — `--check` in the script is the drift gate.
 */

import { existsSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve as resolvePath } from 'node:path';

/** The repository root: the directory this file lives in. */
export const ROOT = dirname(fileURLToPath(import.meta.url));

/** A whole line that is nothing but a markdown link. */
const LINK_LINE = /^\[([^\]]+)]\(([^)\s]+)\)$/;
/** The only fragment a marker may carry. */
const REGION_FRAGMENT = /^region:(.+)$/;

function stripDotSlash(path) {
    return path.startsWith('./') ? path.slice(2) : path;
}

function escapeRegExp(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Find every injection marker in a document given as its lines.
 *
 * A line is a marker when its trimmed form is exactly `[label](target)`, the
 * label is the target's path (a leading `./` on either side is ignored), and
 * the target carries no fragment or a `#region:<name>` fragment. Any other
 * link — a different label, a plain anchor, inline links inside sentences —
 * is ordinary prose and is left alone.
 *
 * @returns {{raw: string, path: string, region: string|null, line: number}[]}
 *          markers in document order; `raw` is the trimmed line, `line` its
 *          0-based index.
 */
export function findMarkers(lines) {
    const markers = [];
    lines.forEach((line, index) => {
        const match = LINK_LINE.exec(line.trim());
        if (!match) return;

        const [, label, target] = match;
        const hash = target.indexOf('#');
        const path = stripDotSlash(hash === -1 ? target : target.slice(0, hash));
        if (path === '') return;
        if (stripDotSlash(label) !== path) return;

        let region = null;
        if (hash !== -1) {
            const fragment = REGION_FRAGMENT.exec(target.slice(hash + 1));
            if (!fragment) return;
            region = fragment[1];
        }

        markers.push({ raw: line.trim(), path, region, line: index });
    });
    return markers;
}

/**
 * Resolve one marker to the exact content its block must show: the whole file
 * (one trailing newline dropped) or the lines strictly between its region's
 * `#region <name>` and `#endregion` markers, whichever comes first when a
 * name is used more than once — callers that need uniqueness enforce it
 * themselves.
 *
 * @param {{path: string, region: string|null}} marker
 * @param {string} [baseDir] directory of the markdown file carrying the
 *        marker; defaults to the repository root.
 */
export function resolveMarker(marker, baseDir = ROOT) {
    const file = resolveFile(marker.path, baseDir);
    const text = readFileSync(file, 'utf8').replace(/\r\n/g, '\n');

    if (!marker.region) return text.replace(/\n$/, '');

    const lines = text.split('\n');
    const start = new RegExp(
        `^\\s*(?://|/\\*+|<!--|#)\\s*#?region\\s+${escapeRegExp(marker.region)}\\b`);
    const end = /^\s*(?:\/\/|\/\*+|<!--|#)\s*#?endregion\b/;

    const startIdx = lines.findIndex((line) => start.test(line));
    if (startIdx === -1) {
        throw new Error(`region '${marker.region}' not found in ${marker.path}`);
    }
    let endIdx = -1;
    for (let i = startIdx + 1; i < lines.length; i++) {
        if (end.test(lines[i])) { endIdx = i; break; }
    }
    if (endIdx === -1) {
        throw new Error(`region '${marker.region}' has no #endregion in ${marker.path}`);
    }
    return lines.slice(startIdx + 1, endIdx).join('\n');
}

function resolveFile(path, baseDir) {
    for (const candidate of [resolvePath(baseDir, path), resolvePath(ROOT, path)]) {
        if (existsSync(candidate) && statSync(candidate).isFile()) return candidate;
    }
    throw new Error(`included file does not exist: ${path}`);
}
