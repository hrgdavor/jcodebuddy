/**
 * The browser half of a Markdown view (DEC-043).
 *
 * A host hands this module a document — through the `#view-data` JSON script tag, or by setting
 * `window.__view` — and calls `window.__renderMarkdown()` again after a save, so a page never has to reload to
 * show new text. That is the whole reason this is a function and not a page-load script.
 *
 * What it does: render the Markdown with `render.js` (the one renderer this repository has), then colour the
 * fences with microlighter. What it does NOT do: decide whether a link is real. It cannot — a browser has no
 * filesystem — so it links anything path-shaped and lets the host answer for it (`404` rather than a caret on
 * line 1, per the frozen page contract).
 */
import { renderMarkdown } from './render.js';
import { anchorFor, linkContext } from './link-context.js';
import { escapeHtml } from './open-file.js';

/**
 * Fence names mapped to the grammars microlighter actually has (javascript, json, java, html, markdown, css,
 * yaml, and the scopes they pull in). Java is native — that is why this highlighter was chosen over one
 * without it (DEC-043). TypeScript and JSX are highlighted as JavaScript: they are the same family and the
 * alternative is no colour at all, which is what microlighter does for a language it does not know.
 *
 * Kotlin is deliberately absent, and microlighter has no Kotlin grammar: a Kotlin fence stays escaped
 * plaintext. An approximation there would be a claim this page cannot keep (DEC-043 clause 5).
 */
const LANGUAGE_ALIASES = Object.freeze({
  js: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  ts: 'javascript',
  jsx: 'javascript',
  tsx: 'javascript',
  yml: 'yaml',
  xml: 'html',
  md: 'markdown',
});

/** The view data, from whichever of the two places the host used. */
export function viewData() {
  if (window.__view && typeof window.__view === 'object') {
    return window.__view;
  }
  const tag = document.getElementById('view-data');
  let parsed = {};
  if (tag && tag.textContent) {
    try {
      parsed = JSON.parse(tag.textContent);
    } catch (error) {
      parsed = { error: `view data is not valid JSON: ${error && error.message}` };
    }
  }
  window.__view = parsed;
  return parsed;
}

/** Colour every fence microlighter has a grammar for. Returns what it managed, for the status line. */
async function highlightFences(root) {
  const microlighter = window.microlighter;
  if (!microlighter || typeof microlighter.highlightAll !== 'function') {
    return { supported: false, blocks: 0 };
  }
  const supported = typeof CSS !== 'undefined' && Boolean(CSS.highlights) && typeof Highlight === 'function';
  try {
    const blocks = await microlighter.highlightAll({
      root,
      selector: '#doc pre > code',
      languageAliases: LANGUAGE_ALIASES,
    });
    return { supported, blocks: Array.isArray(blocks) ? blocks.length : 0 };
  } catch (error) {
    // A highlighter must never take the document down with it: the code is still there, escaped.
    return { supported, blocks: 0, error: String((error && error.message) || error) };
  }
}

/**
 * Render the current view data into `#doc` and colour it.
 *
 * @returns {Promise<{links: number, headings: number, highlighted: object}>} what it did, so a caller can show
 *          an honest status rather than claim success
 */
export async function renderCurrent() {
  const view = viewData();
  const doc = document.getElementById('doc');
  if (!doc) {
    return { links: 0, headings: 0, highlighted: { supported: false, blocks: 0, error: 'no #doc element' } };
  }

  const markdown = String(view.markdown ?? '');
  const directory = String(view.docDir ?? '').replace(/\\/g, '/').replace(/\/+$/, '');
  // Ctrl+click on text opens THIS document at the line the text came from, so the page has to name it. It is
  // set on every render, not once at build time: a host that pushes another document (a save, or a different
  // file from a popup menu) would otherwise leave Ctrl+click pointing at the file it opened first. `source` is
  // the explicit spelling for a host that has an absolute path; `docPath` is what a caller that only knows the
  // document's path in its own tree supplies.
  const source = String(view.source ?? view.docPath ?? '');
  document.body.setAttribute('data-source', source);
  // The link logic lives in link-context.js, where a test can call it: a browser-rendered page has no
  // data-open in its bytes, so nothing about a link can be checked by looking at the page.
  let links = 0;
  const context = linkContext({
    docDir: view.docDir,
    indexJson: view.indexJson,
    moduleName: view.moduleName,
    makeLink: (target, text, kind) => {
      links++;
      return anchorFor(target, text, kind);
    },
  });
  const { html, headings } = renderMarkdown(markdown, context);

  doc.innerHTML = html;
  document.title = String(view.title || view.docPath || 'Markdown view');

  const highlighted = await highlightFences(doc);
  const status = document.getElementById('view-status');
  if (status) {
    const parts = [`${headings.length} heading(s)`, `${links} link(s)`];
    if (!highlighted.supported) {
      parts.push('syntax colour unavailable: this engine has no CSS Custom Highlight API');
    } else if (highlighted.error) {
      parts.push(`syntax colour failed: ${highlighted.error}`);
    } else {
      parts.push(`${highlighted.blocks} code block(s) coloured`);
    }
    status.textContent = parts.join(' · ');
  }
  return { links, headings: headings.length, highlighted };
}

// The host's hook: a save calls this again with fresh text in `window.__view`.
window.__renderMarkdown = renderCurrent;

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => { renderCurrent(); });
} else {
  renderCurrent();
}
