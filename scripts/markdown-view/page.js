#!/usr/bin/env bun
/**
 * Build a Markdown view — one renderer, two assemblies (DEC-043).
 *
 * **Inlined**: one self-contained file. The renderer, the highlighter, the style and the click client are all
 * inside it, so it works from a file manager with no server and no assets, and a host that can only hand a page
 * its bytes (an IDE webview loading HTML from memory) can still show it.
 *
 * **Assets**: a small page that references this package's own files next to it, by relative path, so a served
 * site carries one copy of the renderer instead of one copy per page. The client's own imports resolve against
 * the client file, so the page itself can live anywhere as long as the assets keep their layout.
 *
 * Both modes are the same renderer; only the assembly differs. That is the point of the package: no second
 * implementation to keep in step.
 */
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, posix } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PAGE_STYLE, openFileClientScript, DEFAULT_BRIDGE_PORT } from './open-file.js';

const HERE = dirname(fileURLToPath(import.meta.url));

/** The files an assets-mode page needs beside it. Order matters only for reading. */
export const PAGE_ASSETS = Object.freeze([
  'render.js',
  'open-file.js',
  'page-client.js',
  'assets/microlighter.js',
]);

/** The view a page is built with, and the keys `page-client.js` understands. */
export const VIEW_KEYS = Object.freeze(['markdown', 'docPath', 'docDir', 'title', 'moduleName', 'indexJson']);

/**
 * JSON inside a `<script type="application/json">`.
 *
 * Only `<` needs escaping, and it is not cosmetic: a document containing `</script>` would otherwise end the
 * tag and turn a Markdown file into a page-injection hole.
 */
export function jsonInScriptTag(value) {
  return JSON.stringify(value ?? null).replace(/</g, '\\u003c');
}

/** `#view-data`: how the host hands a document over, and where a save's new text can be read from. */
export function viewDataTag(view) {
  return `<script id="view-data" type="application/json">${jsonInScriptTag(view)}</script>`;
}

function head(title, extra = '') {
  return `<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${String(title ?? 'Markdown view').replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' })[c])}</title>
<style>${PAGE_STYLE}</style>${extra}`;
}

/** The page skeleton both modes share. */
function page(title, body, scripts, { linkBase = '', bridgePort = DEFAULT_BRIDGE_PORT } = {}) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
${head(title)}
</head>
<body data-link-base="${linkBase}" data-bridge-port="${bridgePort}">
<header class="top">
  <h1 id="view-title">${String(title ?? 'Markdown view').replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' })[c])}</h1>
  <p id="view-status">rendering…</p>
</header>
<main id="doc"></main>
<script>${openFileClientScript({ bridgePort })}</script>${scripts}
</body>
</html>
`;
}

/**
 * Build the page.
 *
 * @param {object} [options]
 * @param {'inlined'|'assets'} [options.mode] `inlined` (default) or `assets`
 * @param {string|null} [options.markdown] the document; `null` renders the page with an empty view, which is
 *        what a host that injects text at runtime wants
 * @param {string} [options.title]
 * @param {string} [options.docPath] the document's path, for display
 * @param {string} [options.docDir] its directory, POSIX-style, so relative links resolve against it
 * @param {object} [options.indexJson] a module's class index, when the caller has one
 * @param {string} [options.assetBase] assets mode: the prefix for the asset URLs, e.g. `../markdown-view`
 * @param {number} [options.bridgePort]
 * @returns {Promise<string>} the HTML
 */
export async function buildMarkdownPage(options = {}) {
  const mode = options.mode ?? 'inlined';
  if (mode !== 'inlined' && mode !== 'assets') {
    throw new Error(`unknown mode '${mode}': expected 'inlined' or 'assets'`);
  }
  const title = options.title ?? 'Markdown view';
  const view = {
    markdown: options.markdown ?? null,
    docPath: options.docPath ?? null,
    docDir: options.docDir ?? null,
    title,
    moduleName: options.moduleName ?? null,
    indexJson: options.indexJson ?? null,
  };
  const shared = { linkBase: options.linkBase ?? '', bridgePort: options.bridgePort };

  if (mode === 'assets') {
    const base = String(options.assetBase ?? '.').replace(/\/+$/, '');
    const scripts = `
<script src="${base}/assets/microlighter.js"></script>
<script type="module" src="${base}/page-client.js"></script>`;
    return page(title, null, `${viewDataTag(view)}${scripts}`, shared);
  }

  // Inlined: bundle the client with Bun - a build-time tool, not a dependency of the page. At view time there
  // is still no bundler, no node_modules, no CDN and no network, which is what DEC-027 actually requires.
  const bundle = await Bun.build({
    entrypoints: [join(HERE, 'page-client.js')],
    target: 'browser',
    format: 'iife',
    minify: false,
    write: false,
  });
  if (!bundle.success || bundle.outputs.length === 0) {
    throw new Error(`bundling the client failed: ${bundle.logs.map((l) => l.message).join('; ')}`);
  }
  const client = await bundle.outputs[0].text();
  const highlighter = readFileSync(join(HERE, 'assets', 'microlighter.js'), 'utf8');
  const scripts = `
${viewDataTag(view)}
<script>${highlighter}</script>
<script>${client}</script>`;
  return page(title, null, scripts, shared);
}

/**
 * Copy the assets a page needs into `targetDir`, keeping `assets/` under it.
 *
 * @returns {string[]} the paths written, relative to `targetDir`
 */
export function writePageAssets(targetDir) {
  const written = [];
  for (const relative of PAGE_ASSETS) {
    const destination = join(targetDir, relative);
    mkdirSync(dirname(destination), { recursive: true });
    copyFileSync(join(HERE, relative), destination);
    written.push(relative);
  }
  return written;
}

/** Where an assets-mode page should point, given where the page will be written. */
export function assetBaseFor(pageRelativePath) {
  const depth = posix.dirname(pageRelativePath.replace(/\\/g, '/')).split('/').filter((p) => p && p !== '.').length;
  return depth === 0 ? '.' : Array(depth).fill('..').join('/');
}

if (import.meta.main) {
  const [out = 'markdown-view.html', mode = 'inlined'] = Bun.argv.slice(2);
  const html = await buildMarkdownPage({
    mode,
    title: 'Markdown view builder',
    markdown: '# Built by page.js\n\nA smoke test of the builder itself.\n\n```java\nrecord Point(int x, int y) {}\n```\n',
  });
  writeFileSync(out, html);
  console.log(`wrote ${out} (${mode}, ${html.length} chars)`);
}
