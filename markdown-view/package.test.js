#!/usr/bin/env bun
/**
 * The package's own contract: what it promises, and the one copy that could quietly rot.
 *
 * Two claims are worth a test rather than a paragraph. First, that this package has **no dependencies** —
 * DEC-027's rule is that a vanilla renderer lives under `scripts/` and stays dependency-free, and a
 * `dependencies` entry added later would break that silently. Second, that the vendored highlighter is the
 * same file the kit uses: a package cannot reach into `webview/kit/examples/`, so it carries a copy, and
 * two copies drift unless something checks.
 */
import { describe, expect, test } from 'bun:test';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..');
const manifest = JSON.parse(readFileSync(join(here, 'package.json'), 'utf8'));

describe('the markdown-view package', () => {
  test('declares no dependencies and no devDependencies', () => {
    // A renderer under scripts/ is dependency-free by rule, not by luck (DEC-027, DEC-043).
    expect(Object.keys(manifest.dependencies ?? {})).toEqual([]);
    expect(Object.keys(manifest.devDependencies ?? {})).toEqual([]);
  });

  test('does not carry a node_modules of its own', () => {
    expect(existsSync(join(here, 'node_modules'))).toBe(false);
  });

  test('exports every entry point it advertises, and the files are there', () => {
    const entries = Object.entries(manifest.exports ?? {});
    expect(entries.length).toBeGreaterThanOrEqual(4);
    for (const [name, target] of entries) {
      expect({ name, exists: existsSync(join(here, target)) }).toEqual({ name, exists: true });
    }
    // The entry the renderer is consumed by, named so a rename cannot orphan it silently. `./page` joins
    // this list when the two-mode builder lands - the test above already fails a manifest that advertises a
    // file which is not there, which is how this assertion was caught advertising one.
    expect(manifest.exports['./render']).toBe('./render.js');
  });

  test('the vendored highlighter is byte-identical to the kit copy', () => {
    // microlighter ships no Kotlin grammar and is unmodified upstream code: any difference between these
    // two files is either a fix someone forgot to apply twice, or an edit nobody reviewed.
    const vendored = readFileSync(join(here, 'assets', 'microlighter.js'));
    const kit = readFileSync(join(repo, 'webview', 'kit', 'examples', 'with-assets', 'assets', 'microlighter.js'));
    expect(vendored.equals(kit)).toBe(true);
  });

  test('the highlighter still carries its provenance and its Java grammar', () => {
    const source = readFileSync(join(here, 'assets', 'microlighter.js'), 'utf8');
    // Provenance: a vendored file that does not say where it came from is a file nobody can update.
    expect(source).toContain('davatron5000/microlighter');
    expect(source).toContain('window.microlighter');
    // Java is the reason this highlighter was chosen over one that has no Java grammar (DEC-043).
    expect(source).toContain("name: 'java'");
    // And the honest gap, so nobody assumes the C-family trick is hiding somewhere.
    expect(source).not.toContain("name: 'kotlin'");
  });
});
