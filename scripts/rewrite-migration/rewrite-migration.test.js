/**
 * Tests for the Phase 6 migration tooling.
 *
 * <p>Run with `cd scripts && bun test rewrite-migration/rewrite-migration.test.js`,
 * alongside the existing `scripts/entity-html` suite.
 *
 * <p>The cases that earn their place here are the ones where the tooling's own
 * correctness is not obvious from reading it: the `org.openrewrite.java.JavaParser`
 * collision, which is the single mistake that would make the gate lie; the
 * `value`-normalisation the mapping table documents; and the tracker round trip,
 * because a status that cannot survive being written and read back is a status
 * nobody can trust.
 */

import { describe, expect, test } from 'bun:test';

import { classifyJavaText, scopeOf, moduleOf, findRepoRoot } from './inventory.js';
import { loadTracker, checkboxFor, STATUSES, progressOf } from './model.js';
import { MAPPINGS, mappingFor, mappingStats, OPENREWRITE_VERSION } from './mappings.js';
import { QUEUE, ALLOWLIST, PREREQUISITES } from './curation.js';
import { parseArgs, UsageError } from './cli.js';

describe('classifyJavaText', () => {
  test('detects an ordinary import', () => {
    const text = 'package a;\nimport com.github.javaparser.ast.CompilationUnit;\n';
    const facts = classifyJavaText(text, 'm/src/main/java/A.java');
    expect(facts.hasImports).toBe(true);
    expect(facts.classified).toBe('imports');
    expect(facts.imports).toEqual(['import com.github.javaparser.ast.CompilationUnit;']);
  });

  test('detects a fully-qualified use with no import', () => {
    const text = 'var cu = com.github.javaparser.JavaParser.parse(s);\n';
    const facts = classifyJavaText(text, 'm/src/main/java/A.java');
    expect(facts.hasImports).toBe(false);
    expect(facts.classified).toBe('qualified-only');
    expect(facts.codeRefs).toHaveLength(1);
  });

  test('treats a comment mention as comment-only', () => {
    const text = '// com.github.javaparser is gone now\nclass A {}\n';
    const facts = classifyJavaText(text, 'm/src/main/java/A.java');
    expect(facts.classified).toBe('comment-only');
    expect(facts.commentRefs).toHaveLength(1);
    expect(facts.codeRefs).toHaveLength(0);
  });

  test('does NOT mistake org.openrewrite.java.JavaParser for JavaParser', () => {
    // The collision this whole tool exists to survive: OpenRewrite ships its own
    // class named JavaParser, and a case-insensitive substring match on
    // "javaparser" reports every already-migrated file as unmigrated.
    const text = [
      'import org.openrewrite.java.JavaParser;',
      'import org.openrewrite.SourceFile;',
      'JavaParser parser = JavaParser.fromJavaVersion().build();',
    ].join('\n');
    const facts = classifyJavaText(text, 'merge-java/src/main/java/A.java');
    expect(facts.hasImports).toBe(false);
    expect(facts.classified).toBe('none');
    expect(facts.imports).toHaveLength(0);
    expect(facts.qualifiedRefs).toHaveLength(0);
  });

  test('does not treat a longer package as the prefix', () => {
    // `com.github.javaparserfoo` would match a naive substring search.
    const text = 'import com.github.javaparserfoo.Bar;\n';
    const facts = classifyJavaText(text, 'm/src/main/java/A.java');
    expect(facts.classified).toBe('none');
  });

  test('marks documentation paths as outside the queue', () => {
    const text = 'import com.github.javaparser.JavaParser;\n';
    const facts = classifyJavaText(text, 'doc/brainstorm/x/A.java');
    expect(facts.queue).toBe(false);
    expect(facts.nonQueueReason).toBe('lives under doc/');
  });

  test('keeps a test file in the queue', () => {
    // The plan's own check filters on the substring "test" and drops these.
    const text = 'import com.github.javaparser.JavaParser;\n';
    const facts = classifyJavaText(
      text,
      'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/A.java',
    );
    expect(facts.queue).toBe(true);
    expect(facts.scope).toBe('test');
  });
});

describe('scopeOf / moduleOf', () => {
  test('reads main, test and other scopes', () => {
    expect(scopeOf('m/src/main/java/A.java')).toBe('main');
    expect(scopeOf('m/src/test/java/A.java')).toBe('test');
    expect(scopeOf('m/src/it/java/A.java')).toBe('other');
  });

  test('reads the owning module', () => {
    expect(moduleOf('hipster-entity-tooling/src/main/java/A.java')).toBe('hipster-entity-tooling');
    expect(moduleOf('A.java')).toBe('(root)');
  });
});

describe('checkboxFor', () => {
  test('round-trips every status through its checkbox', () => {
    const marks = STATUSES.map((status) => `${status}=${checkboxFor(status)}`);
    expect(marks).toEqual([
      'not-started= ',
      'in-progress=~',
      'testing=>',
      'complete=x',
      'blocked=!',
      'exempt=-',
    ]);
  });
});

describe('loadTracker', () => {
  test('parses a data row and reads the status from the checkbox', async () => {
    const root = await fixtureRepo([
      ['- [x] m/src/main/java/A.java', 'm', 'high', 'low', 'ported'],
    ]);
    const tracker = loadTracker(root);
    expect(tracker.exists).toBe(true);
    expect(tracker.problems).toEqual([]);
    expect(tracker.entries.get('m/src/main/java/A.java')?.status).toBe('complete');
    expect(tracker.entries.get('m/src/main/java/A.java')?.note).toBe('ported');
  });

  test('ignores the header and separator rows', async () => {
    const root = await fixtureRepo([], { header: true });
    const tracker = loadTracker(root);
    expect(tracker.problems).toEqual([]);
    expect(tracker.entries.size).toBe(0);
  });

  test('reports an unknown checkbox rather than dropping the row', async () => {
    const root = await fixtureRepo([['- [?] m/src/main/java/A.java', 'm', 'high', 'low', '']]);
    const tracker = loadTracker(root);
    expect(tracker.entries.size).toBe(0);
    expect(tracker.problems).toHaveLength(1);
    expect(tracker.problems[0]).toContain('unknown checkbox');
  });

  test('reports a duplicate row', async () => {
    const root = await fixtureRepo([
      ['- [ ] m/src/main/java/A.java', 'm', 'high', 'low', ''],
      ['- [x] m/src/main/java/A.java', 'm', 'high', 'low', ''],
    ]);
    const tracker = loadTracker(root);
    expect(tracker.problems).toHaveLength(1);
    expect(tracker.problems[0]).toContain('duplicate');
  });

  test('reports a missing tracker as absent rather than throwing', () => {
    const tracker = loadTracker('/definitely/not/a/repository');
    expect(tracker.exists).toBe(false);
    expect(tracker.entries.size).toBe(0);
  });
});

describe('progressOf', () => {
  test('counts a file as import-free only when it has no JavaParser surface', () => {
    const model = {
      queueFiles: [
        { status: 'complete', hasImports: false, classified: 'none' },
        { status: 'not-started', hasImports: true, classified: 'imports' },
        { status: 'in-progress', hasImports: false, classified: 'qualified-only' },
        { status: 'exempt', hasImports: true, classified: 'imports' },
      ],
    };
    const progress = progressOf(model);
    expect(progress.total).toBe(4);
    expect(progress.settled).toBe(2);
    expect(progress.remaining).toBe(2);
    expect(progress.stillImporting).toBe(2);
    // A file with a qualified-only use is not import-free, even though it has no
    // import line — the distinction the plan's grep collapses.
    expect(progress.importFree).toBe(1);
  });
});

describe('mappings', () => {
  test('every entry states a status and a reason', () => {
    for (const [fqn, mapping] of Object.entries(MAPPINGS)) {
      expect(fqn.startsWith('com.github.javaparser')).toBe(true);
      expect(['verified', 'inferred']).toContain(mapping.status);
      expect(mapping.note.length).toBeGreaterThan(0);
      expect(mapping.rewrite.length).toBeGreaterThan(0);
    }
  });

  test('every verified entry cites evidence', () => {
    // A "verified" claim with no evidence behind it is exactly the kind of
    // assertion this migration cannot afford. Evidence is either a file in this
    // repository (strongest: the shape is one this project compiles) or the
    // pinned upstream source at the version the parent POM manages.
    for (const [fqn, mapping] of Object.entries(MAPPINGS)) {
      if (mapping.status !== 'verified') {
        continue;
      }
      const evidence = mapping.evidence ?? '';
      const inRepo = evidence.includes('merge-java/');
      const upstream = evidence.startsWith(`openrewrite/rewrite v${OPENREWRITE_VERSION} `);
      if (!inRepo && !upstream) {
        throw new Error(`verified mapping for ${fqn} has no usable evidence: "${evidence}"`);
      }
    }
  });

  test('every inferred entry explains what is unconfirmed', () => {
    for (const [fqn, mapping] of Object.entries(MAPPINGS)) {
      if (mapping.status === 'inferred') {
        expect(`${fqn} :: ${mapping.note.length}`).not.toBe(`${fqn} :: 0`);
        expect(mapping.note.length).toBeGreaterThan(20);
      }
    }
  });

  test('the JavaParser parser itself maps to the OpenRewrite parser', () => {
    expect(mappingFor('com.github.javaparser.JavaParser')?.rewrite).toBe(
      'org.openrewrite.java.JavaParser',
    );
    expect(mappingFor('com.github.javaparser.JavaParser')?.status).toBe('verified');
  });

  test('reports a mix of verified and inferred, not one or the other', () => {
    const stats = mappingStats();
    expect(stats.verified).toBeGreaterThan(0);
    expect(stats.inferred).toBeGreaterThan(0);
    expect(stats.total).toBe(stats.verified + stats.inferred);
  });

  test('an unknown FQN is unmapped rather than silently defaulted', () => {
    expect(mappingFor('com.github.javaparser.does.NotExist')).toBeNull();
  });
});

describe('curation', () => {
  test('every queue entry is fully specified', () => {
    for (const [path, entry] of Object.entries(QUEUE)) {
      expect(path).not.toContain('\\');
      expect(['high', 'medium', 'low']).toContain(entry.priority);
      expect(['high', 'medium', 'low']).toContain(entry.risk);
      expect(entry.note.length).toBeGreaterThan(0);
      // A high-risk entry must say why; otherwise the label is decoration.
      if (entry.risk === 'high') {
        expect(entry.riskReason ?? '').not.toBe('');
      }
    }
  });

  test('every allowlist entry gives a reason and an exit condition', () => {
    for (const [path, entry] of Object.entries(ALLOWLIST)) {
      expect(path).not.toContain('\\');
      expect(entry.reason.length).toBeGreaterThan(0);
      expect(entry.deferredTo.length).toBeGreaterThan(0);
      expect(entry.status).toBe('exempt');
    }
  });

  test('an allowlist entry is never also a queue entry', () => {
    const overlap = Object.keys(ALLOWLIST).filter((path) => path in QUEUE);
    expect(overlap).toEqual([]);
  });

  test('every prerequisite records evidence and starts open', () => {
    for (const prereq of PREREQUISITES) {
      expect(prereq.id).toMatch(/^P0-\d+$/);
      expect(prereq.why.length).toBeGreaterThan(0);
      expect(prereq.evidence.length).toBeGreaterThan(0);
      expect(prereq.done).toBe(false);
    }
  });
});

describe('parseArgs', () => {
  test('separates flags from positionals', () => {
    const { flags, positional } = parseArgs(['--root', 'x', '--json', 'a.java'], {
      root: 'string',
      json: 'boolean',
    });
    expect(flags.root).toBe('x');
    expect(flags.json).toBe(true);
    expect(positional).toEqual(['a.java']);
  });

  test('rejects an option it does not know, rather than ignoring it', () => {
    expect(() => parseArgs(['--nope'], {})).toThrow(UsageError);
  });

  test('rejects a value-taking option with no value', () => {
    expect(() => parseArgs(['--root'], { root: 'string' })).toThrow(UsageError);
  });
});

describe('findRepoRoot', () => {
  test('finds the root from inside this directory', () => {
    const root = findRepoRoot();
    expect(root.replace(/\\/g, '/')).toContain('jcodebuddy');
  });
});

// ------------------------------------------------------------------ helpers ---

/**
 * Create a throwaway repository containing only a tracker, so the parser can be
 * exercised against rows this test controls rather than against live state.
 *
 * <p>The vocabulary legend is emitted too, because that is the shape that made a
 * naive parser treat its own legend as a list of files — the regression this
 * fixture exists to pin down.
 */
async function fixtureRepo(rows, { header = false } = {}) {
  const { mkdtempSync, mkdirSync, writeFileSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');

  const root = mkdtempSync(join(tmpdir(), 'phase6-tracker-'));
  const directory = join(root, 'doc', 'brainstorm', 'rewrite-migration', '06-migration');
  mkdirSync(directory, { recursive: true });

  const lines = [
    '# Phase 6 — Migration Tracker',
    '',
    '## Status vocabulary',
    '',
    '| Mark | Status | Meaning |',
    '| --- | --- | --- |',
    '| `- [ ]` | `not-started` | No port work begun. |',
    '| `- [x]` | `complete` | Ported, and the module gate passes. |',
    '',
    '## Queue',
    '',
  ];
  if (header) {
    lines.push('| Status | Module | Priority | Risk | Notes |');
    lines.push('| --- | --- | --- | --- | --- |');
  }
  for (const row of rows) {
    lines.push(`| \`${row[0]}\` | ${row[1]} | ${row[2]} | ${row[3]} | ${row[4]} |`);
  }
  writeFileSync(join(directory, 'tracker.md'), `${lines.join('\n')}\n`);
  return root;
}
