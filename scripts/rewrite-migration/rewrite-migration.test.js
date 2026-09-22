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
import { QUEUE, ALLOWLIST, DOC_ALLOWLIST, PREREQUISITES } from './curation.js';
import {
  classifyMarkdown,
  docKindOf,
  docOffenders,
} from './documentation.js';
import { parseArgs, UsageError } from './cli.js';
import {
  parseSurefireXml, summarise, readGateResult, renderReport,
} from './generate-test-report.js';

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

  test('every prerequisite records evidence and a boolean done flag', () => {
    // `done` is a fact about the tree, not an invariant: a prerequisite that has been cleared is
    // recorded as cleared (P0-1 was, when its two syntax errors were fixed). What must hold for every
    // entry is that it states why it exists and what the evidence is, so a reader can check the claim
    // rather than trust it.
    for (const prereq of PREREQUISITES) {
      expect(prereq.id).toMatch(/^P0-\d+$/);
      expect(prereq.why.length).toBeGreaterThan(0);
      expect(prereq.evidence.length).toBeGreaterThan(0);
      expect(typeof prereq.done).toBe('boolean');
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

/**
 * The Phase 7 report generator. What is worth testing here is not markdown layout but the three
 * judgement calls the page rests on: reading Surefire's attributes without assuming their order,
 * folding a nested-class suite into the class a reader counts, and — the one that matters most —
 * saying "this input was missing" instead of rendering a zero that reads like a result.
 */
describe('generate-test-report', () => {
  test('reads a surefire suite regardless of attribute order', () => {
    const suite = parseSurefireXml([
      '<testsuite skipped="1" name="p.SampleTest" time="0.5" tests="4" errors="0" failures="1">',
      '  <testcase name="aBrokenCase" classname="p.SampleTest"><failure message="x">trace</failure></testcase>',
      '</testsuite>',
    ].join('\n'));
    expect(suite.name).toBe('p.SampleTest');
    expect(suite.tests).toBe(4);
    expect(suite.failures).toBe(1);
    expect(suite.skipped).toBe(1);
    expect(suite.timeMs).toBe(500);
    expect(suite.failuresNamed).toEqual(['aBrokenCase']);
  });

  test('folds a nested-class suite onto its outer class', () => {
    // Surefire reports `@Nested` classes as separate suites named `Outer$Inner`. Without the fold a
    // reader counts one test class as four and concludes the suite grew.
    const outer = parseSurefireXml('<testsuite name="p.TreeQueriesTest" tests="20" failures="0" '
        + 'errors="0" skipped="0" time="1"/>');
    const nested = parseSurefireXml('<testsuite name="p.TreeQueriesTest$PackageName" tests="5" '
        + 'failures="0" errors="0" skipped="0" time="1"/>');
    expect(outer.outerClass).toBe('TreeQueriesTest');
    expect(nested.outerClass).toBe('TreeQueriesTest');
    expect(nested.nested).toBe(true);

    const summary = summarise([
      { ...outer, module: 'm' }, { ...nested, module: 'm' },
    ]);
    expect(summary.suites).toBe(2);
    expect(summary.rows).toHaveLength(1);
    expect(summary.rows[0].tests).toBe(25);
    expect(summary.rows[0].module).toBe('m');
    expect(summary.total).toBe(25);
  });

  test('yields nothing for a report it cannot read, rather than a suite of zeros', () => {
    // A truncated write mid-build is the real case. A zero-filled row would say "this class ran and
    // asserted nothing", which is a claim; null says "no report", which is the truth.
    expect(parseSurefireXml('<testsuite name="p.Half')).toBeNull();
    expect(parseSurefireXml('<testsuite tests="3" failures="0" errors="0" skipped="0" time="0"/>'))
        .toBeNull();
  });

  test('counts the gate verdicts from the gate\'s own line shape', () => {
    const gate = readGateResult([
      'Checks',
      '======',
      '  OK    queue-import-free: nothing left to port',
      '  WARN  stale-entries: 1 entry names a moved file',
      '  FAIL  curation-coverage: a/src/Main.java is unclassified',
      '',
      'RESULT: FAIL — 1 failing check(s).',
    ].join('\n'));
    expect(gate.present).toBe(true);
    expect(gate.verdict).toBe('RESULT: FAIL — 1 failing check(s).');
    expect(gate.checks.map(check => check.check)).toEqual(
        ['queue-import-free', 'stale-entries', 'curation-coverage']);
    expect([gate.passed, gate.warnings, gate.failing]).toEqual([1, 1, 1]);
  });

  test('renders a missing gate as missing, and never as a pass', () => {
    const summary = summarise([{ ...parseSurefireXml('<testsuite name="p.A" tests="7" failures="0" '
        + 'errors="0" skipped="0" time="0.25"/>'), module: 'm' }]);
    const page = renderReport({
      generated: '2026-09-22 12:00 UTC',
      modules: ['m'],
      missingReports: ['other-module'],
      emptyReports: ['no-tests-module'],
      summary,
      gate: readGateResult(null),
      benchmarks: { present: false, path: 'benchmarks/latest.json', rows: [] },
    });
    expect(page).toContain('**7 tests across 1 test class');
    expect(page).toContain('No captured gate run was supplied');
    expect(page).not.toMatch(/RESULT: PASS/);
    // The module with no reports is named, so the page cannot be read as a whole-reactor claim.
    expect(page).toContain('`other-module`');
    // And a module that ran with nothing to run is not reported as untested: Surefire wrote the
    // directory and no XML, which means "zero test classes", not "nobody looked".
    expect(page).toContain('Ran with no test classes');
    expect(page).toContain('`no-tests-module`');
    expect(page).toContain('No benchmark results');
  });
});

/**
 * Phase 8's documentation scan. The judgement calls worth pinning are the three that decide whether
 * `docs-honest` can be trusted: that the *kind* of a document comes from its path (so a record cannot
 * be laundered into the allowlist, and a generated page cannot be "fixed" by a human), that a mention
 * of OpenRewrite's own identically-named class is not counted against the document that explains it,
 * and that teaching a removed API is a distinct, catchable verdict rather than a louder mention.
 */
describe('documentation', () => {
  test('classifies a document by its path, most specific rule first', () => {
    // A generated page lives under a record prefix, and "a script writes this" is the more specific
    // fact — so the order of the rules is load-bearing, not cosmetic.
    expect(docKindOf('doc/brainstorm/rewrite-migration/06-migration/Checklist.md')).toBe('generated');
    expect(docKindOf('doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md')).toBe('generated');
    expect(docKindOf('doc/brainstorm/rewrite-migration/06-migration/MIGRATION-GUIDE.md')).toBe('record');
    expect(docKindOf('plans/rewrite-migration/06-Migration-Checklist.md')).toBe('record');
    expect(docKindOf('doc-hipster-entity/architecture/decisions/DEC-020.md')).toBe('decision');
    expect(docKindOf('doc-hipster-entity/brainstorm/gen-freezing.md')).toBe('decision');
    expect(docKindOf('AGENTS.md')).toBe('live');
    expect(docKindOf('hipster-entity-tooling/README.md')).toBe('live');
  });

  test('does not count OpenRewrite\'s own identically-named class as a removed mention', () => {
    // The guard the Java scanner has, for the same reason: OpenRewrite ships `JavaParser`, so a
    // repository that uses it must be able to say so without every mention being a finding.
    const migrated = classifyMarkdown(
      'Parse with `org.openrewrite.java.JavaParser.fromJavaVersion()`.', 'AGENTS.md');
    expect(migrated.liveNameLines).toEqual([]);
    expect(migrated.mentionLines).toBe(1);
    expect(migrated.teachesRemovedApi).toBe(false);

    // The package name is never excluded: no OpenRewrite line can contain it.
    const removed = classifyMarkdown(
      'Read source with `com.github.javaparser`.\nAnd `JavaParser` too.', 'AGENTS.md');
    expect(removed.packageLines).toBe(1);
    expect(removed.liveNameLines).toEqual(['2:And `JavaParser` too.']);
    expect(removed.teachesRemovedApi).toBe(false);
  });

  test('flags a document that teaches a removed API, and names the API', () => {
    const teaching = classifyMarkdown([
      '# How to add a builder',
      '',
      '```java',
      'CompilationUnit cu = StaticJavaParser.parse(code);',
      'LexicalPreservingPrinter.setup(cu);',
      '```',
    ].join('\n'), 'docs/SomeGuide.md');
    expect(teaching.teachesRemovedApi).toBe(true);
    expect(teaching.taughtApis).toEqual(['LexicalPreservingPrinter', 'StaticJavaParser']);
    expect(teaching.kind).toBe('live');

    // A record is allowed to teach by definition: its subject IS the removal, and rewriting the
    // sample would delete the instructions the port left for whoever reads it next.
    const guide = classifyMarkdown(
      'CompilationUnit cu = StaticJavaParser.parse(code);',
      'doc/brainstorm/rewrite-migration/06-migration/MIGRATION-GUIDE.md');
    expect(guide.teachesRemovedApi).toBe(true);
    expect(guide.isHistorical).toBe(true);
  });

  test('reports a live mention with no recorded reason, and nothing else', () => {
    const scan = {
      files: [
        { repoPath: 'AGENTS.md', kind: 'live', allowed: false, teachesRemovedApi: false },
        { repoPath: 'docs/Guide.md', kind: 'live', allowed: false, teachesRemovedApi: true },
        { repoPath: 'docs/Kept.md', kind: 'live', allowed: true, teachesRemovedApi: false },
        { repoPath: 'DEC-020.md', kind: 'decision', allowed: false, teachesRemovedApi: false },
        { repoPath: 'Checklist.md', kind: 'generated', allowed: false, teachesRemovedApi: false },
      ],
    };
    expect(docOffenders(scan).unallowlisted.map(file => file.repoPath))
        .toEqual(['AGENTS.md', 'docs/Guide.md']);
  });

  test('every documentation-allowlist entry gives a reason and an exit condition', () => {
    // Same contract as the source allowlist: an exemption without both is a permanent hole.
    for (const [path, entry] of Object.entries(DOC_ALLOWLIST)) {
      expect(entry.reason, `${path} needs a reason`).toBeTruthy();
      expect(entry.reason.trim().length, `${path}'s reason is too short to be a reason`)
          .toBeGreaterThan(40);
      expect(entry.deferredTo, `${path} needs a retirement condition`).toBeTruthy();
      expect(entry.deferredTo.trim().length, `${path}'s deferredTo is too short`)
          .toBeGreaterThan(15);
      expect(entry.status, `${path} must be exempt`).toBe('exempt');
    }
  });

  test('the documentation allowlist and the source allowlist are separate key spaces', () => {
    // A `.md` path in the Java allowlist (or the reverse) would be an exemption nobody reads.
    for (const path of Object.keys(DOC_ALLOWLIST)) {
      expect(path.endsWith('.md'), `${path} is not a markdown path`).toBe(true);
      expect(ALLOWLIST[path], `${path} is in both allowlists`).toBeUndefined();
      expect(QUEUE[path], `${path} is both queue and documentation exemption`).toBeUndefined();
    }
  });
});

