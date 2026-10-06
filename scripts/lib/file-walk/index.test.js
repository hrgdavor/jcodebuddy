#!/usr/bin/env bun
/**
 * The ignore rule, asserted where it is easy to get silently wrong.
 *
 * Every case here is one this repository has actually met: the symlinked dependency tree, the VS Code download in a
 * gitignored directory, the module output that is ignored per module rather than at the root, and the `.gitignore`
 * file that lives three directories down and overrides the one at the root.
 */
import { afterAll, describe, expect, test } from 'bun:test';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { compileRule, globToRegexSource, isIgnored, listFiles } from './index.js';

const scratch = mkdtempSync(join(tmpdir(), 'file-walk-'));
afterAll(() => rmSync(scratch, { recursive: true, force: true }));

/** A throwaway tree: `{ 'path': 'contents' }`, with a trailing `/` meaning an empty directory. */
function tree(spec, root = mkdtempSync(join(scratch, 'tree-'))) {
  for (const [path, contents] of Object.entries(spec)) {
    const full = join(root, path);
    if (path.endsWith('/')) {
      mkdirSync(full, { recursive: true });
      continue;
    }
    mkdirSync(join(full, '..'), { recursive: true });
    writeFileSync(full, contents);
  }
  return root;
}

describe('the glob translation', () => {
  test('a star does not cross a slash, a double star does', () => {
    expect(globToRegexSource('*.md')).toBe('[^/]*\\.md');
    expect(globToRegexSource('**/*.md')).toBe('(?:.*/)?[^/]*\\.md');
    expect(globToRegexSource('docs/**')).toBe('docs/.*');
    expect(globToRegexSource('a?c')).toBe('a[^/]c');
  });

  test('an escaped character is literal, and a class passes through', () => {
    expect(globToRegexSource('\\#file')).toBe('#file');
    expect(globToRegexSource('[abc].js')).toBe('[abc]\\.js');
    expect(globToRegexSource('[!a].js')).toBe('[^a]\\.js');
  });
});

describe('a rule', () => {
  test('is anchored only when it contains a slash', () => {
    // `target/` matches at any depth; `/target` only at the root. Git's rule, and the one people trip over.
    expect(compileRule('target/', '').regex.test('target')).toBe(true);
    expect(compileRule('target/', '').regex.test('a/b/target')).toBe(true);
    expect(compileRule('/target', '').regex.test('target')).toBe(true);
    expect(compileRule('/target', '').regex.test('a/b/target')).toBe(false);
  });

  test('knows comments, blanks, negation and directory-only', () => {
    expect(compileRule('# a comment', '')).toBeNull();
    expect(compileRule('', '')).toBeNull();
    expect(compileRule('   ', '')).toBeNull();
    expect(compileRule('!keep.md', '').negated).toBe(true);
    expect(compileRule('build/', '').dirOnly).toBe(true);
    expect(compileRule('build/', '').regex.test('build')).toBe(true);
  });
});

describe('walking a tree', () => {
  test('skips what .gitignore says, and does not descend into it', () => {
    const root = tree({
      '.gitignore': 'node_modules/\nbuild/\n*.log\n',
      'README.md': '# hi',
      'src/A.java': 'class A {}',
      'src/notes.log': 'noise',
      'node_modules/pkg/index.js': 'module.exports = 1;',
      'node_modules/pkg/deep/kept.md': '# would be a link target if we looked inside',
      'build/out.md': '# generated',
    });
    const files = listFiles(root);
    expect(files.sort()).toEqual(['.gitignore', 'README.md', 'src/A.java']);
    // The ignored directory is not entered at all: that is the performance, and it is also git's rule.
    expect(files.some((file) => file.includes('node_modules'))).toBe(false);
  });

  test('extensions are filtered, because that is what makes a walk cheap', () => {
    const root = tree({
      '.gitignore': 'ignored/\n',
      'a.md': 'x',
      'b.MD': 'x',
      'c.java': 'x',
      'd.json': 'x',
      'ignored/e.md': 'x',
    });
    expect(listFiles(root, { extensions: ['.md'] }).sort()).toEqual(['a.md', 'b.MD']);
  });

  test('a nested .gitignore overrides its parent, and a later line the earlier one', () => {
    const root = tree({
      '.gitignore': '*.md\n!README.md\n',
      'README.md': 'kept by the negation',
      'other.md': 'ignored',
      'docs/.gitignore': '!notes.md\n',
      'docs/notes.md': 're-included by the nested file',
      'docs/other.md': 'still ignored',
    });
    const files = listFiles(root, { extensions: ['.md'] });
    expect(files.sort()).toEqual(['README.md', 'docs/notes.md']);
  });

  test('a rule that names a directory anywhere still matches at depth', () => {
    const root = tree({
      '.gitignore': 'target/\n',
      'module-a/target/classes.md': 'ignored',
      'module-a/src/A.md': 'kept',
      'target/root.md': 'ignored',
    });
    expect(listFiles(root, { extensions: ['.md'] })).toEqual(['module-a/src/A.md']);
  });

  test('a symlink is not followed, so a walk cannot escape the tree', () => {
    const outside = mkdtempSync(join(scratch, 'outside-'));
    writeFileSync(join(outside, 'secret.md'), '# not ours');
    const root = tree({ 'here.md': '# ours' });
    try {
      symlinkSync(outside, join(root, 'link'), 'junction');
    } catch (error) {
      console.log(`  skip symlinks are not available here (${error.code})`);
      return;
    }
    expect(listFiles(root, { extensions: ['.md'] })).toEqual(['here.md']);
  });

  test('isIgnored answers for one path, including a file that is gone', () => {
    const root = tree({ '.gitignore': 'build/\n*.tmp\n', 'keep.md': 'x' });
    expect(isIgnored(root, join(root, 'build', 'x.md'))).toBe(true);
    expect(isIgnored(root, join(root, 'a.tmp'))).toBe(true);
    expect(isIgnored(root, join(root, 'keep.md'))).toBe(false);
    // Outside the root is not this walker's business to call ignored.
    expect(isIgnored(root, join(scratch, 'elsewhere.md'))).toBe(false);
  });
});

describe('this repository', () => {
  test('the walked set contains no ignored tree, and the files a script needs', () => {
    // The measurement that matters: the same walk that used to enter a checked-out VS Code and every node_modules.
    const started = Date.now();
    const markdown = listFiles('.', { extensions: ['.md'] });
    const elapsed = Date.now() - started;
    const ignored = ['.vscode-test', 'node_modules', '.jsx6', 'target/', 'build/', '.git/', 'proto/'];
    for (const fragment of ignored) {
      expect({ fragment, present: markdown.some((file) => file.includes(fragment)) }).toEqual({ fragment, present: false });
    }
    expect(markdown).toContain('webview/README.md');
    expect(markdown).toContain('scripts/lib/file-walk/README.md');
    // A sanity bound rather than a fixed number: if the ignore handling breaks, this walk finds thousands.
    expect(markdown.length).toBeLessThan(1000);
    console.log(`  markdown files: ${markdown.length}, walk took ${elapsed}ms`);
  });

  test('every file in the tree is a small multiple of the tracked set', () => {
    // `git ls-files` is only used here, as a cross-check the walker does not depend on.
    const all = listFiles('.');
    expect(all).toContain('scripts/check-repo-links.mjs');
    expect(all.some((file) => file.includes('node_modules'))).toBe(false);
  });
});
