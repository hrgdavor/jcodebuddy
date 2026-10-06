#!/usr/bin/env bun
/**
 * The two assemblies, and the one thing that must not leak.
 *
 * What is worth a test here is not that HTML comes out — it is the three claims a consumer depends on:
 *
 * 1. **Inlined really is self-contained.** No `<script src>`, no `<link>`, no fetch at view time. A host that can
 *    only hand a page its bytes (an IDE webview loading HTML from memory or from a file with no server) must still
 *    get a working page, and the panel's own history is a long list of pages that quietly needed a server.
 * 2. **Assets mode really shares.** The page must reference the package's files next to it, and must not also inline
 *    its own copy of the renderer — the point of the mode is one copy on disk, not one copy per page.
 * 3. **The view data cannot end the script tag.** A Markdown file containing `</script>` is a document, not an
 *    injection; the JSON is escaped for the tag, and this is the test that keeps it that way.
 *
 * Not tested here: how a browser renders it. `page-client.js` needs a DOM, and what the page *does* with the
 * renderer is covered by `markdown-view.test.js` (which owns the renderer) and by the plugin's own Gradle test of
 * the template substitution.
 */
import { describe, expect, test } from 'bun:test';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  PAGE_ASSETS,
  assetBaseFor,
  buildMarkdownPage,
  jsonInScriptTag,
  writePageAssets,
  VIEW_DATA_MARKER,
} from './page.js';

const view = {
  markdown: '# Title\n\nA [link](src/A.java#add) and a fence.\n\n```java\nrecord P(int x) {}\n```\n',
  docPath: 'D:/proj/README.md',
  docDir: 'D:/proj',
  title: 'README',
};

describe('the inlined page', () => {
  test('is self-contained: no script src, no link, no remote reference', async () => {
    const html = await buildMarkdownPage({ mode: 'inlined', ...view });
    expect(html).not.toMatch(/<script[^>]+src=/i);
    expect(html).not.toMatch(/<link[^>]+href=/i);
    expect(html).not.toMatch(/https?:\/\/[^"'\s]+\/\/[^"'\s]*\.(js|css)/i);
    // The highlighter and the renderer are both in there, or the page is not self-contained.
    expect(html).toContain('window.microlighter');
    expect(html).toContain('__renderMarkdown');
  });

  test('carries the view data, and the renderer reads it', async () => {
    const html = await buildMarkdownPage({ mode: 'inlined', ...view });
    expect(html).toContain('<script id="view-data" type="application/json">');
    expect(html).not.toContain(VIEW_DATA_MARKER);
    const json = html.slice(html.indexOf('id="view-data"'));
    expect(json).toContain('# Title');
    expect(json).toContain('src/A.java#add');
  });

  test('a document containing a closing script tag cannot end the tag', async () => {
    const html = await buildMarkdownPage({
      mode: 'inlined',
      markdown: 'before </script><script>alert(1)</script> after',
    });
    // The literal sequence must not appear inside the JSON tag at all.
    const data = html.slice(html.indexOf('id="view-data"'), html.indexOf('</script>', html.indexOf('id="view-data"')));
    expect(data).not.toContain('</script>');
    expect(data).toContain('\\u003c/script');
  });

  test('a host can build it with no document yet, which is what the plugin does', async () => {
    // The template: the page exists, the view is empty, and the host fills it at runtime.
    const html = await buildMarkdownPage({ mode: 'inlined', template: true, title: 'Markdown view' });
    expect(html).toContain('id="view-data"');
    // The marker, not an empty object: a host substitutes it, and the Java side names the same constant.
    expect(html).toContain(VIEW_DATA_MARKER);
    expect(html).not.toContain('"markdown":null');
    // The substitution is a single replacement, and what it produces is valid JSON.
    const filled = html.replace(VIEW_DATA_MARKER, jsonInScriptTag({ markdown: '# From the host' }));
    const data = filled.slice(filled.indexOf('id="view-data"') + 20);
    const json = data.slice(data.indexOf('>') + 1, data.indexOf('</script>'));
    expect(JSON.parse(json).markdown).toBe('# From the host');
  });
});

describe('the style', () => {
  test('is light, in the palette a reader of documentation expects', async () => {
    // Asked for by the maintainer after seeing the dark teal page: "the default markdown color scheme is ugly, use
    // something light that resembles github light mode". Stated as values, so a future edit that darkens the page
    // has to disagree with a test rather than with a memory.
    const html = await buildMarkdownPage({ mode: 'inlined', template: true });
    expect(html).toContain('--bg: #ffffff');
    expect(html).toContain('--text: #1f2328');
    expect(html).toContain('--accent: #0969da');
    expect(html).toContain('--border: #d0d7de');
    expect(html).toContain('#doc h1');
    expect(html).toContain('#doc blockquote');
    // The dark palette this replaced, gone rather than merely overridden.
    expect(html).not.toContain('#04191b');
    expect(html).not.toContain('#2ee6d0');
    expect(html).not.toContain('#d8f2f0');
  });

  test('colours the code microlighter finds, which needs the ::highlight() rules', async () => {
    // microlighter registers ranges on CSS.highlights and never wraps a token in a <span>, so without these rules
    // the ranges exist and nothing is coloured - which is exactly how a page with no theme looks broken.
    const html = await buildMarkdownPage({ mode: 'inlined', template: true });
    for (const category of ['comment', 'keyword', 'string', 'function', 'type', 'tag', 'selector', 'numeric']) {
      expect(html).toContain(`::highlight(${category})`);
    }
    expect(html).toContain('--syntax-comment: #6e7781');
  });
});

describe('the assets page', () => {
  test('references the package files and does not inline the renderer', async () => {
    const html = await buildMarkdownPage({ mode: 'assets', assetBase: '../markdown-view', ...view });
    expect(html).toContain('src="../markdown-view/page-client.js"');
    expect(html).toContain('src="../markdown-view/assets/microlighter.js"');
    // The renderer must NOT also be inlined: that is the whole point of this mode.
    expect(html).not.toContain('window.microlighter = (function');
  });

  test('the style is shared by both modes, so a page looks the same either way', async () => {
    const inlined = await buildMarkdownPage({ mode: 'inlined', ...view });
    const assets = await buildMarkdownPage({ mode: 'assets', ...view });
    const style = (html) => html.slice(html.indexOf('<style>'), html.indexOf('</style>'));
    expect(style(inlined)).toBe(style(assets));
  });

  test('assetBaseFor points from where the page is back to the package', () => {
    expect(assetBaseFor('view.html')).toBe('.');
    expect(assetBaseFor('sub/view.html')).toBe('..');
    expect(assetBaseFor('a/b/view.html')).toBe('../..');
  });

  test('writePageAssets copies every file the page will ask for', () => {
    const dir = mkdtempSync(join(tmpdir(), 'markdown-assets-'));
    try {
      const written = writePageAssets(dir);
      expect(written.sort()).toEqual([...PAGE_ASSETS].sort());
      for (const relative of written) {
        expect(readFileSync(join(dir, relative), 'utf8').length).toBeGreaterThan(0);
      }
      // The highlighter is the big one: if it is missing, the page loads and has no colour.
      expect(readFileSync(join(dir, 'assets', 'microlighter.js'), 'utf8')).toContain('window.microlighter');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe('the view data escapes for its container', () => {
  test('only < is escaped, and it is enough', () => {
    expect(jsonInScriptTag({ a: '</script>' })).toBe('{"a":"\\u003c/script>"}');
    expect(jsonInScriptTag(null)).toBe('null');
    expect(jsonInScriptTag({ a: 'plain & > |' })).toBe('{"a":"plain & > |"}');
  });

  test('an unknown mode is refused rather than silently defaulted', async () => {
    // A caller that misspells the mode must not get a page that quietly needs a server.
    await expect(buildMarkdownPage({ mode: 'asset', markdown: 'x' })).rejects.toThrow(/unknown mode/);
  });
});

describe('the page a host writes', () => {
  test('is a complete document, so a host can write it and load it as a file', async () => {
    const html = await buildMarkdownPage({ mode: 'inlined', markdown: null });
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(html.trimEnd().endsWith('</html>')).toBe(true);
    expect(html).toContain('<main id="doc"></main>');
    // The click client must be present in both modes: without it a link is decoration.
    expect(html).toContain('data-bridge-port');
  });

  test('the bridge port is honoured, because a served page needs it', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'markdown-port-'));
    try {
      const html = await buildMarkdownPage({ mode: 'inlined', markdown: 'x', bridgePort: 18899 });
      expect(html).toContain('data-bridge-port="18899"');
      writeFileSync(join(dir, 'x.html'), html);
      expect(readFileSync(join(dir, 'x.html'), 'utf8')).toContain('18899');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
