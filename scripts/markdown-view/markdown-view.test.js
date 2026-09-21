/**
 * Tests for the Markdown viewer (`bun test markdown-view`, from `scripts/`).
 *
 * They assert the properties that make a generated page trustworthy, not its exact bytes: a link is
 * emitted only for something that exists, a type name resolves through the class index to its declaration
 * line, prose that merely looks like a path stays prose, and a full run over the example module produces
 * pages whose every link verifies.
 *
 * The last one is the acceptance test: it renders the real `hipster-entity-example` and checks every link
 * it emitted against the real tree.
 */
import { describe, expect, test } from 'bun:test';
import { existsSync, readFileSync, statSync } from 'fs';
import { join, resolve } from 'path';
import { fileURLToPath } from 'url';
import { dirname } from 'path';

import { DEFAULT_BRIDGE_PORT, escapeHtml, openFileClientScript } from './open-file.js';
import {
  ClassIndex, classIndexFrom, joinPosix, renderMarkdown, resolveTarget, slugify, splitLineSuffix,
} from './render.js';
import { findMarkdown, loadIndex, main, parseArgs, renderDocument, resolveModule, resolveOpenable, verifyLinks } from './index.js';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..', '..');
const MODULE = 'hipster-entity-example';
const MODULE_ROOT = join(REPO_ROOT, MODULE);

/** One class index row, as a document would consult it. */
function fixtureIndex() {
  const rows = new Map([
    ['a.b.Person', { path: 'src/main/java/a/b/Person.java', line: 14, kind: 'interface' }],
    ['a.b.Person.Record', { path: 'src/main/java/a/b/Person.java', line: 18, kind: 'record' }],
    ['a.b.PersonSummaryBuilder', { path: 'src/main/java/a/b/PersonSummaryBuilder.java', line: 13, kind: 'class' }],
  ]);
  return new ClassIndex(rows, 'fixture');
}

describe('the line suffix', () => {
  test('reads #L14, #14 and :14', () => {
    expect(splitLineSuffix('src/A.java#L14')).toEqual({ target: 'src/A.java', line: 14 });
    expect(splitLineSuffix('src/A.java#14')).toEqual({ target: 'src/A.java', line: 14 });
    expect(splitLineSuffix('src/A.java:14')).toEqual({ target: 'src/A.java', line: 14 });
  });

  test('a Windows drive letter is not a line', () => {
    expect(splitLineSuffix('C:/work/A.java')).toEqual({ target: 'C:/work/A.java', line: 1 });
    expect(splitLineSuffix('C:/work/A.java#L7')).toEqual({ target: 'C:/work/A.java', line: 7 });
  });

  test('no suffix means line 1', () => {
    expect(splitLineSuffix('src/A.java')).toEqual({ target: 'src/A.java', line: 1 });
    expect(splitLineSuffix('')).toEqual({ target: '', line: 1 });
  });
});

describe('resolving a target', () => {
  const context = (directory = '') => ({
    directory,
    index: fixtureIndex(),
    isOpenable: (candidate) => candidate === 'doc/README.md' || candidate === 'README.md',
  });

  test('a type name resolves through the class index to its declaration line', () => {
    const target = resolveTarget('Person', context());
    expect(target).not.toBeNull();
    expect(target.open).toBe('src/main/java/a/b/Person.java');
    expect(target.line).toBe(14);
    expect(target.role).toBe('type');
    expect(target.member).toBe('Person');
    expect(target.fromIndex).toBe(true);
  });

  test('a nested type written both ways resolves', () => {
    expect(resolveTarget('Person.Record', context()).open).toBe('src/main/java/a/b/Person.java');
    expect(resolveTarget('Record', context()).line).toBe(18);
  });

  test('an explicit line wins over the declaration line, and is not marked as the index', () => {
    const target = resolveTarget('Person#L48', context());
    expect(target.line).toBe(48);
    expect(target.fromIndex).toBe(false);
  });

  test('a relative path resolves against the document directory', () => {
    expect(resolveTarget('README.md', context()).open).toBe('README.md');
    expect(resolveTarget('doc/README.md', context('doc')).open).toBe('doc/README.md');
  });

  test('prose that merely looks like a path does not resolve', () => {
    for (const prose of ['src/main/java', '.java', 'not-a-file.txt', 'some_word', 'a b c']) {
      expect(resolveTarget(prose, context())).toBeNull();
    }
  });

  test('a bare lowercase word does not resolve, even when a directory of that name exists', () => {
    // Directory handling needs the real filesystem, so this goes through `resolveOpenable` with the real
    // tree: `scripts/` and `doc/` are directories at the repository root, and neither is a link unless the
    // author asked for one with a trailing slash.
    expect(resolveOpenable('scripts', MODULE_ROOT)).toBeNull();
    expect(resolveOpenable('doc', MODULE_ROOT)).toBeNull();
    expect(resolveOpenable('doc/', MODULE_ROOT)).not.toBeNull();
    expect(resolveOpenable('scripts/gen.cmd', MODULE_ROOT)).not.toBeNull();
    // And a path that is not there never resolves, whatever it looks like.
    expect(resolveOpenable('src/main/java/…', MODULE_ROOT)).toBeNull();
    expect(resolveOpenable('does/not/exist.java', MODULE_ROOT)).toBeNull();
  });

  test('an elided path never resolves, whatever the predicate says', () => {
    // `isOpenable` is deliberately permissive here: the guard belongs to the resolver, so that no caller
    // can emit a link to `src/main/java/…` by handing it a predicate that says yes.
    const permissive = { directory: '', index: fixtureIndex(), isOpenable: () => true };
    expect(resolveTarget('src/main/java/…', permissive)).toBeNull();
    expect(resolveTarget('a b c', permissive)).toBeNull();
  });
});

describe('the markdown renderer', () => {
  const context = (extra = {}) => ({
    directory: '',
    index: fixtureIndex(),
    isOpenable: () => false,
    makeLink: (target, text) => `<a data-open="${target.open}" data-line="${target.line}">${text}</a>`,
    ...extra,
  });

  test('headings get stable anchors and are collected for the contents', () => {
    const { html, headings } = renderMarkdown('# One\n\n## Two Words\n\n### Three\n', context());
    expect(html).toContain('<h1 id="one">One</h1>');
    expect(html).toContain('<h2 id="two-words">Two Words</h2>');
    expect(headings.map((heading) => heading.id)).toEqual(['one', 'two-words', 'three']);
    expect(slugify('DEC-029: the class index!')).toBe('dec-029-the-class-index');
  });

  test('a fenced code block is never inline-processed', () => {
    const { html } = renderMarkdown('```java\nString s = `not a code span`;\n```\n', context());
    expect(html).toContain('<pre><code class="language-java">String s = `not a code span`;</code></pre>');
    expect(html).not.toContain('<code>not a code span</code></pre>');
  });

  test('a code span that names a type becomes a link, and one that does not stays code', () => {
    const { html } = renderMarkdown('See `Person` and `just_a_word`.\n', context());
    expect(html).toContain('<a data-open="src/main/java/a/b/Person.java" data-line="14">Person</a>');
    expect(html).toContain('<code>just_a_word</code>');
    expect(html).not.toContain('<a data-open="just_a_word"');
  });

  test('a table renders with a header row and body rows', () => {
    const { html } = renderMarkdown('| a | b |\n|---|---|\n| 1 | 2 |\n', context());
    expect(html).toContain('<table><thead><tr><th>a</th><th>b</th></tr></thead>');
    expect(html).toContain('<tr><td>1</td><td>2</td></tr>');
  });

  test('lists, task items and blockquotes render', () => {
    expect(renderMarkdown('- one\n- two\n', context()).html).toContain('<ul><li>one</li><li>two</li></ul>');
    expect(renderMarkdown('1. one\n2. two\n', context()).html).toContain('<ol><li>one</li><li>two</li></ol>');
    expect(renderMarkdown('- [x] done\n- [ ] todo\n', context()).html)
      .toContain('<input type="checkbox" disabled checked>');
    expect(renderMarkdown('> quoted\n', context()).html).toContain('<blockquote>');
  });

  test('an external link stays a link and is not asked to resolve', () => {
    const { html } = renderMarkdown('[maven](https://maven.apache.org/)\n', context());
    expect(html).toContain('<a href="https://maven.apache.org/">maven</a>');
  });

  test('an internal link that does not resolve shows its target as text', () => {
    const { html } = renderMarkdown('[gone](does/not/exist.md)\n', context());
    expect(html).toContain('gone');
    expect(html).toContain('<code>does/not/exist.md</code>');
    expect(html).not.toContain('data-open="does/not/exist.md"');
  });

  test('HTML in prose is escaped', () => {
    const { html } = renderMarkdown('Use <script>alert(1)</script> here.\n', context());
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).not.toContain('<script>alert(1)</script>');
  });

  test('a table cell pipe escape survives', () => {
    const { html } = renderMarkdown('| a | b |\n|---|---|\n| x \\| y | z |\n', context());
    expect(html).toContain('<td>x | y</td>');
  });
});

describe('the client script', () => {
  test('is valid JavaScript with the documented fallback ladder', () => {
    const script = openFileClientScript({ bridgePort: 18881 });
    expect(() => new Function(script)).not.toThrow();
    expect(script).toContain('window.openFile');
    expect(script).toContain('/open?filePath=');
    expect(script).toContain('navigator.clipboard');
    expect(script).toContain('data-open');
    expect(script).toContain('18881');
  });

  test('port 0 disables the HTTP fallback', () => {
    expect(openFileClientScript({ bridgePort: 0 })).toContain("|| '0'");
  });

  test('escapes what must be escaped in an attribute', () => {
    expect(escapeHtml('a"b<c>&d')).toBe('a&quot;b&lt;c&gt;&amp;d');
  });

  test('carries the regular expressions it needs, undamaged', () => {
    const script = openFileClientScript();
    // Both expressions depend on backslashes surviving into the emitted script, which is why
    // `openFileClientScript` uses a String.raw literal: an ordinary template literal would have eaten one
    // level and silently changed what the regex matches.
    expect(script).toContain('/^[A-Za-z]:[\\\\/]/');
    expect(script).toContain('/^\\/([A-Za-z]:)/');
  });
});

describe('the CLI', () => {
  test('parses its flags and refuses what it does not know', () => {
    const options = parseArgs(['--module', 'x', '--only', 'a.md,b.md', '--verify', '--bridge-port', '0']);
    expect(options.module).toBe('x');
    expect(options.only).toEqual(['a.md', 'b.md']);
    expect(options.verify).toBe(true);
    expect(options.bridgePort).toBe(0);
    expect(parseArgs([]).bridgePort).toBe(DEFAULT_BRIDGE_PORT);
    expect(() => parseArgs(['--nope'])).toThrow();
    expect(() => parseArgs(['--bridge-port', 'abc'])).toThrow();
  });

  test('resolves the module by name, and finds the same one by default', () => {
    expect(resolveModule(MODULE)).toBe(MODULE_ROOT);
    expect(() => resolveModule('no-such-module')).toThrow();
  });

  test('discovers markdown and skips derived directories', () => {
    const files = findMarkdown(MODULE_ROOT);
    expect(files).toContain('README.md');
    expect(files).toContain('.jcodebuddy/README.md');
    expect(files.every((file) => !file.startsWith('target/'))).toBe(true);
    expect(files).toEqual([...files].sort());
  });

  test('loads the class index the pages resolve type names through', () => {
    const index = loadIndex(MODULE_ROOT);
    expect(index.size).toBeGreaterThan(20);
    const row = index.lookup('PersonSummary');
    expect(row).not.toBeNull();
    expect(row.path).toBe('src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java');
    expect(row.line).toBeGreaterThan(1);
    // A nested type resolves too, which is what makes `PersonSummary.Record` in prose a link.
    expect(index.lookup('PersonSummary.Record').path).toBe(row.path);
  });

  test('an absent index does not fail, it only resolves less', () => {
    const empty = classIndexFrom(null, 'nothing');
    expect(empty.size).toBe(0);
    expect(empty.lookup('Person')).toBeNull();
    expect(classIndexFrom('{ not json').size).toBe(0);
  });

  test('joinPosix normalises without touching the filesystem', () => {
    expect(joinPosix('a/b', '../c.md')).toBe('a/c.md');
    expect(joinPosix('', './d.md')).toBe('d.md');
    expect(joinPosix('a/b/c', '../../x.md')).toBe('a/x.md');
  });
});

describe('a full run over the example module', () => {
  test('writes the pages and the index page, and every emitted link verifies', () => {
    const exit = main(['--module', MODULE, '--only', 'README.md', '--quiet', '--verify']);
    expect(exit).toBe(0);
  }, 120_000);

  test('a rendered page is one self-contained file that loads nothing', () => {
    const out = join(MODULE_ROOT, '.jcodebuddy', 'agent-state', 'markdown-view', 'README.html');
    expect(existsSync(out)).toBe(true);
    const html = readFileSync(out, 'utf8');
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(html.trimEnd().endsWith('</html>')).toBe(true);
    expect(html).toContain('<meta charset="utf-8">');
    for (const forbidden of ['<script src=', '<link rel="stylesheet"', '<img ', '@import', '<iframe src="http']) {
      expect(html).not.toContain(forbidden);
    }
    // The click contract, inlined.
    expect(html).toContain('window.openFile');
    expect(html).toContain('data-link-base');
    expect(html).toContain('data-bridge-port');
    // And no absolute path from this machine.
    expect(html).not.toContain(REPO_ROOT);
    expect(html).not.toContain(REPO_ROOT.split('\\').join('/'));
  });

  test('a type named in prose links to its declaration, and prose stays prose', () => {
    const module = resolveModule(MODULE);
    const index = loadIndex(module);
    const rendered = renderDocument(readFileSync(join(module, 'README.md'), 'utf8'), {
      documentPath: 'README.md',
      moduleRoot: module,
      index,
      outModuleRelative: 'README.html',
      bridgePort: 0,
    });
    const links = rendered.links;
    expect(links.length).toBeGreaterThan(5);
    // Every link resolves to something real, and the index-sourced ones land on their own declaration.
    expect(verifyLinks(links, module)).toEqual([]);
    const type = links.find((link) => link.role === 'type');
    expect(type).toBeDefined();
    expect(statSync(join(module, type.open)).isFile()).toBe(true);
  });

  test('a link the tree cannot satisfy is reported, not rendered', () => {
    const problems = verifyLinks([
      { open: 'does/not/exist.java', line: 1, member: 'X', from: 'a.md', fromIndex: true },
    ], MODULE_ROOT);
    expect(problems.length).toBe(1);
    expect(problems[0]).toContain('does not exist');
  });

  test('a wrong line for an index-sourced member is reported', () => {
    const problems = verifyLinks([
      {
        open: 'src/main/java/hr/hrg/hipster/entityexample/example/Auditable.java',
        line: 1, member: 'Auditable', from: 'a.md', fromIndex: true,
      },
    ], MODULE_ROOT);
    expect(problems.length).toBe(1);
    expect(problems[0]).toContain('does not contain Auditable');
  });
});
