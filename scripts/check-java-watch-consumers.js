#!/usr/bin/env bun
/**
 * check-java-watch-consumers.js — inventory the JCodeBuddy modules that still depend on the frozen `java-watch*`.
 *
 * WHY THIS EXISTS
 *   The migration plan is [`plans/java-watch-to-fs-meta.md`](../plans/java-watch-to-fs-meta.md), and the first step is to
 *   make the consumer list measurable rather than remembered. The list changes as W2/W4/W5 land, so the script reports
 *   every module it finds, marks the six known consumers of 2026-10-31, and fails on a module that is not on that
 *   baseline. Once the migration is done, run it with `--strict`: any remaining consumer is then a failure, not a
 *   progress report.
 *
 * WHAT COUNTS
 *   - a POM outside `watch/` declaring `<artifactId>java-watch-…</artifactId>` (the root POM's dependencyManagement
 *     is not a consumer and is skipped);
 *   - a Java source outside `watch/` referencing `hr.hrg.watch2.core` (an import or a fully-qualified use) — the
 *     package `java-watch-core` actually owns.
 *
 *   The agent's own `hr.hrg.watch2.agent` package and `metadata-arena`'s `hr.hrg.watch2.arena` package are namespaces,
 *   not dependencies, and are deliberately not counted. Neither is the `hr.hrg.watch2.sidecar` protocol string.
 *
 * Usage:
 *   bun scripts/check-java-watch-consumers.js            report; fail only on an unknown consumer
 *   bun scripts/check-java-watch-consumers.js --strict   fail on any consumer (the W6 gate)
 *   bun scripts/check-java-watch-consumers.js --help      this text
 *
 * Exit code: 0 when the report matches the baseline (or `--strict` finds none), 1 otherwise.
 */

import { readFileSync } from 'node:fs';
import { posix } from 'node:path';
import { listFiles } from './lib/file-walk/index.js';
import { reportDuration, stopwatch } from './lib/timing.js';
import { repoRoot } from './lib/toolchain.js';

/** The six consumers measured on 2026-10-31; a seventh is the thing this check exists to catch. */
const EXPECTED = new Set([
  'jcodebuddy/jcodebuddy-agent',
  'jcodebuddy/jcodebuddy-builder',
  'jcodebuddy/jcodebuddy-watch-tools',
  'hipster-ioc/hipster-ioc-tooling',
  'project-automation',
  'webview/jwa-sidecar',
]);

const DEPENDENCY = /<artifactId>\s*(java-watch-[^<\s]+)\s*<\/artifactId>/g;
const REFERENCE = /\bhr\.hrg\.watch2\.core\./m;

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) {
    console.log(usage());
    return 0;
  }
  const strict = argv.includes('--strict');
  const elapsed = stopwatch();
  const root = repoRoot();
  const files = listFiles(root, {
    extensions: ['.xml', '.java'],
    // Derived state must not count as a consumer; `.watch/` holds the agent's audit copies.
    ignore: ['.watch', '.jcodebuddy'],
  });

  /** module relative dir → { pom: string|null, imports: string[] } */
  const consumers = new Map();
  const unknown = [];
  const ensure = (module) => {
    if (!consumers.has(module)) {
      consumers.set(module, { pom: null, imports: [] });
    }
    return consumers.get(module);
  };

  // First pass: POM dependencies, and the module roots they establish.
  const moduleRoots = [];
  for (const relative of files) {
    if (relative.startsWith('watch/') || !relative.endsWith('/pom.xml')) {
      continue;
    }
    const text = readFileSync(posix.join(root, relative), 'utf8');
    const dependencies = [...text.matchAll(DEPENDENCY)].map((match) => match[1]);
    if (dependencies.length === 0) {
      continue;
    }
    const module = posix.dirname(relative);
    moduleRoots.push(module);
    ensure(module).pom = dependencies.join(', ');
  }
  // A root pom.xml is excluded by the `/pom.xml` filter above; dependencyManagement on the parent is not a consumer.

  // Second pass: Java references to the package java-watch-core owns, imported or fully qualified.
  for (const relative of files) {
    if (relative.startsWith('watch/') || !relative.endsWith('.java')) {
      continue;
    }
    if (!REFERENCE.test(readFileSync(posix.join(root, relative), 'utf8'))) {
      continue;
    }
    const module = moduleRoots
      .filter((candidate) => relative.startsWith(`${candidate}/`))
      .sort((left, right) => right.length - left.length)[0];
    if (module === undefined) {
      unknown.push(relative);
      continue;
    }
    ensure(module).imports.push(relative);
  }

  const modules = [...consumers.keys()].sort();
  const unexpected = modules.filter((module) => !EXPECTED.has(module));
  const resolved = [...EXPECTED].filter((module) => !consumers.has(module)).sort();

  console.log('java-watch consumers (POM dependency and/or hr.hrg.watch2.core reference)\n');
  for (const module of modules) {
    const entry = consumers.get(module);
    const marker = EXPECTED.has(module) ? ' ' : '!';
    const imports = entry.imports.length === 0
      ? '—'
      : `${entry.imports.length} (${entry.imports.map((path) => posix.basename(path)).join(', ')})`;
    console.log(`${marker} ${module}`);
    console.log(`    pom:          ${entry.pom ?? '—'}`);
    console.log(`    core refs:    ${imports}`);
  }
  if (unknown.length > 0) {
    console.log('\nimports with no module POM:');
    for (const path of unknown) {
      console.log(`! ${path}`);
    }
  }
  if (resolved.length > 0) {
    console.log(`\nresolved since the baseline: ${resolved.join(', ')}`);
  }
  if (unexpected.length > 0) {
    console.log(`\nUNEXPECTED (not in the 2026-10-31 baseline): ${unexpected.join(', ')}`);
  }
  console.log(`\n${modules.length} consumer module(s), ${unexpected.length + unknown.length} unexpected; `
    + `${resolved.length} of the baseline resolved.`);

  const failed = unexpected.length > 0 || unknown.length > 0 || (strict && modules.length > 0);
  if (strict && modules.length > 0) {
    console.error('strict: the migration is not finished — the modules above still depend on java-watch*');
  }
  console.log(reportDuration(elapsed(), { what: 'java-watch consumers checked', files: files.length }));
  return failed ? 1 : 0;
}

function usage() {
  return `check-java-watch-consumers.js — inventory the frozen java-watch* consumers

  bun scripts/check-java-watch-consumers.js            report; fail only on an unknown consumer
  bun scripts/check-java-watch-consumers.js --strict   fail on any consumer (the W6 gate)
  bun scripts/check-java-watch-consumers.js --help      this text

Baseline (2026-10-31): six modules. A seventh fails; a baseline entry that disappears is reported as
resolved. The full plan and inventory are in plans/java-watch-to-fs-meta.md.`;
}

process.exit(main());
