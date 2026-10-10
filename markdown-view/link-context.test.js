#!/usr/bin/env bun
/**
 * The links a page produces, asserted where they can be wrong.
 *
 * This exists because of a live report — a click that asked the IDE for a file "at line 1" and opened no tab — and
 * because the first attempt to check it looked at the generated HTML, where a browser-rendered page has *no*
 * `data-open` at all: the links are built at view time. So the links are built by `linkContext`, which a test can
 * call, and the cases below are the ones that were wrong.
 */
import { describe, expect, test } from 'bun:test';
import { anchorFor, linkContext, linksFor } from './link-context.js';
import { renderMarkdown } from './render.js';

const DOC_DIR = 'D:/proj/webview/doc';

describe('the links a page builds', () => {
  test('a declaration link carries the fragment, absolutely', () => {
    const links = linksFor('See [the method](../core/Navigator.java#open).\n', { docDir: DOC_DIR });
    expect(links.length).toBe(1);
    // Absolute, because the host is handed a path; and the fragment is the whole point of the link.
    expect(links[0].open).toBe('D:/proj/webview/core/Navigator.java#open');
    expect(links[0].path).toBe('D:/proj/webview/core/Navigator.java');
    expect(links[0].fragment).toBe('open');
  });

  test('every location spelling survives the trip, including the inject ones', () => {
    const links = linksFor([
      '- [a line](../core/Navigator.java#L42)',
      '- [a range](../core/Navigator.java#L42-L58)',
      '- [body only](../core/Navigator.java#-open)',
      '- [a region](../test/MergeConflictResolverTest.java#replays-sticky-decision)',
      '- [body only, again](../test/MergeConflictResolverTest.java#-replays-sticky-decision)',
      '- [a json key](../package.json#name,version)',
      '',
    ].join('\n'), { docDir: DOC_DIR });
    const opens = links.map((link) => link.open);
    expect(opens).toEqual([
      // A plain line is the page's own: it travels as data-line, and there is no fragment to resolve.
      'D:/proj/webview/core/Navigator.java',
      'D:/proj/webview/core/Navigator.java#L42-L58',
      'D:/proj/webview/core/Navigator.java#-open',
      'D:/proj/webview/test/MergeConflictResolverTest.java#replays-sticky-decision',
      'D:/proj/webview/test/MergeConflictResolverTest.java#-replays-sticky-decision',
      // ../ from webview/doc is webview/, not the project root: the join is right, the expectation was not.
      'D:/proj/webview/package.json#name,version',
    ]);
  });

  test('the anchor puts the fragment on data-open and the member on data-member', () => {
    const html = anchorFor({ open: 'D:/p/A.java', line: 7, fragment: 'add', member: 'add', role: 'member' }, 'add', 'code');
    expect(html).toContain('data-open="D:/p/A.java#add"');
    expect(html).toContain('data-line="7"');
    // Display metadata, never a routing key - the contract says so, and the fragment above is the key.
    expect(html).toContain('data-member="add"');
  });

  test('a link with no fragment is a path and a line, exactly as before', () => {
    const html = anchorFor({ open: 'D:/p/A.java', line: 14, fragment: null, member: '', role: 'file' }, 'A.java', 'link');
    expect(html).toContain('data-open="D:/p/A.java"');
    expect(html).not.toContain('data-member');
  });

  test('an in-page anchor and an external link are not navigation links', () => {
    // Neither names a file: the first is the page's own business, the second is somebody else's.
    const links = linksFor('[a heading](#the-section) and [a site](https://example.com/x)\n', { docDir: DOC_DIR });
    expect(links).toEqual([]);
  });

  test('prose that merely looks like a path is left alone', () => {
    // The rule the page can actually keep: a source extension, no whitespace, absolute.
    const links = linksFor('The words src/main/java and a sentence with a /slash are not links.\n', { docDir: DOC_DIR });
    expect(links).toEqual([]);
  });

  test('an index row travels as the host wrote it, which is why the host makes it absolute', () => {
    // The renderer emits an index row's path exactly as it finds it - it does not know a module root, and a document
    // may sit in docs/nested/. So the contract between host and page is this: rows arrive absolute. MarkdownView
    // fulfils it (absoluteIndex), and this test is what would catch either side breaking it.
    const relativeRow = { classes: { 'a.b.Person': { path: 'src/main/java/a/b/Person.java', kind: 'class' } } };
    const fromRelative = linksFor('See `Person` for the fields.\n', { docDir: DOC_DIR, indexJson: relativeRow });
    expect(fromRelative.length).toBe(1);
    expect(fromRelative[0].open).toBe('src/main/java/a/b/Person.java');

    const absoluteRow = { classes: { 'a.b.Person': { path: 'D:/proj/webview/src/main/java/a/b/Person.java', kind: 'class' } } };
    const fromAbsolute = linksFor('See `Person` for the fields.\n', { docDir: DOC_DIR, indexJson: absoluteRow });
    expect(fromAbsolute.length).toBe(1);
    expect(fromAbsolute[0].open).toBe('D:/proj/webview/src/main/java/a/b/Person.java');
  });

  test('the context is what renderMarkdown needs, and nothing browser-shaped', () => {
    // A pure context is why a test can do this at all: no document, no window, no DOM.
    const context = linkContext({ docDir: DOC_DIR });
    expect(context.directory).toBe(DOC_DIR);
    expect(context.isOpenable('D:/proj/A.java')).toBe(true);
    expect(context.isOpenable('D:/proj/A.java with a space')).toBe(false);
    expect(context.isOpenable('relative/A.java')).toBe(false);
    expect(context.isOpenable('D:/proj/notes.txt')).toBe(false);
  });

  test('the renderer and this module agree about a line fragment', () => {
    // A plain #L42 is the page's own line - the host has nothing to resolve - and it still travels, because a host
    // that ignores fragments must still land on the right line.
    const { html } = renderMarkdown('[A](../core/Navigator.java#L42)\n', linkContext({ docDir: DOC_DIR }));
    // The page settles a plain line itself, so there is no fragment to resolve - the line is data-line, and a
    // host that ignores fragments still lands correctly.
    expect(html).toContain('data-open="D:/proj/webview/core/Navigator.java"');
    expect(html).toContain('data-line="42"');
  });
});
