#!/usr/bin/env bun
/**
 * Prove that every file the JetBrains port derives from is a real file in a real checkout of the pinned
 * upstream revision.
 *
 * The port is a citation of one revision of `JetBrains/intellij-community`. `JetBrainsAttributionTest`
 * proves the internal consistency of that citation — that every derived file declares a licence, an
 * upstream path and the pinned commit, and that the notices list it. What a test inside the build
 * **cannot** prove is that the cited files exist, because proving it means reading the upstream
 * repository, and a build that reaches the network is a build that fails when the network does.
 *
 * So this check is separate and deliberate, the same split the repository already uses for anything
 * network-shaped:
 *
 * - the build asserts the citation is complete and self-consistent;
 * - this script asserts the citation is **true** — the paths exist, and the checkout is at the pin.
 *
 * Usage:
 *
 *   bun merge-java/scripts/verify-jetbrains-sources.js [checkout]
 *
 * `checkout` defaults to `.tmp/jb-ic`, the gitignored scratch clone the port was read from (root
 * `AGENTS.md` § 2 — `.tmp/` is scratch and is never a build input). Recreate it with the commands in
 * `merge-java/docs/JETBRAINS_PORT.md` § 12.
 *
 * Exit codes: 0 the citation is verified · 1 a path or the revision does not check out · 2 the script
 * could not verify anything (no checkout, no git, no declared paths), which is reported as a failure to
 * verify rather than as a passing run.
 */

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { reportDuration, stopwatch } from '../../scripts/lib/timing.js';

const MODULE_ROOT = resolve(import.meta.dir, '..');
const REPO_ROOT = resolve(MODULE_ROOT, '..');
const PROVENANCE = join(
  MODULE_ROOT,
  'src/main/java/com/codebuddy/merge/jetbrains/JetBrainsProvenance.java',
);
const PACKAGE_DIR = join(MODULE_ROOT, 'src/main/java/com/codebuddy/merge/jetbrains');

const args = process.argv.slice(2);
if (args.includes('--help') || args.includes('-h')) {
  const header = readFileSync(import.meta.filename, 'utf8');
  console.log(header.slice(header.indexOf('/**') + 3, header.indexOf('*/')).trim());
  process.exit(0);
}

const elapsed = stopwatch();
const checkout = resolve(
  args.find((a) => !a.startsWith('-')) ?? join(REPO_ROOT, '.tmp/jb-ic'),
);
const checkoutExists = () => existsSync(join(checkout, '.git'));

/** Problems that mean "cannot verify", as opposed to "verification failed". */
const cannotVerify = [];
/** Problems that mean the citation itself is wrong. */
const failures = [];

/** Run git in the checkout and return trimmed stdout, or null when the command fails. */
function git(...gitArgs) {
  const result = Bun.spawnSync({
    cmd: ['git', '-C', checkout, ...gitArgs],
    stdout: 'pipe',
    stderr: 'pipe',
  });
  return result.exitCode === 0 ? result.stdout.toString().trim() : null;
}

// ------------------------------------------------- the pinned revision, read from its one home

let pinnedCommit = null;
let upstreamRepository = null;

if (!existsSync(PROVENANCE)) {
  cannotVerify.push(`the provenance file is missing: ${PROVENANCE}`);
} else {
  const source = readFileSync(PROVENANCE, 'utf8');
  const pinned = source.match(/PINNED_COMMIT\s*=\s*"([0-9a-f]{40})"/);
  const repository = source.match(/UPSTREAM_REPOSITORY\s*=\s*"([^"]+)"/);
  if (pinned) {
    pinnedCommit = pinned[1];
  } else {
    failures.push(`${PROVENANCE} declares no 40-character PINNED_COMMIT`);
  }
  if (repository) {
    upstreamRepository = repository[1];
  } else {
    failures.push(`${PROVENANCE} declares no UPSTREAM_REPOSITORY`);
  }
  if (pinnedCommit && upstreamRepository) {
    console.log(`pinned revision: ${pinnedCommit.slice(0, 12)}… (${upstreamRepository})`);
  }
}

// ------------------------------------------------- the checkout

if (!checkoutExists()) {
  cannotVerify.push(
    `no upstream checkout at ${checkout}. Recreate it with the commands in ` +
      `merge-java/docs/JETBRAINS_PORT.md § 12; this check is not part of the build, so a missing ` +
      `checkout is a check that did not run.`,
  );
} else if (git('rev-parse', '--git-dir') === null) {
  cannotVerify.push(`git is not usable against ${checkout} (is git on PATH?)`);
} else if (pinnedCommit) {
  const head = git('rev-parse', 'HEAD');
  const headDate = git('show', '-s', '--format=%cs', 'HEAD');
  console.log(`checkout HEAD:  ${head?.slice(0, 12) ?? '(unknown)'}… (${headDate ?? 'unknown date'})`);
  if (head !== pinnedCommit) {
    failures.push(
      `the checkout is at ${head}, but the port is pinned at ${pinnedCommit}. ` +
        `Run: git -C ${checkout} fetch --depth 1 origin ${pinnedCommit} && ` +
        `git -C ${checkout} checkout FETCH_HEAD`,
    );
  }
}

// ------------------------------------------------- every declared upstream path

/** The upstream paths the derived files declare, as a map of path to the files declaring it. */
function declaredUpstreamPaths() {
  const declared = new Map();
  const pattern =
    /(platform\/(?:util\/diff|diff-impl|diff-api|util\/src)\/[A-Za-z0-9_./-]+\.(?:java|kt))/g;

  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!entry.name.endsWith('.java')) continue;

      const text = readFileSync(path, 'utf8');
      // `@derived none` marks this module's own bookkeeping about upstream, not a translation of it.
      if (/@derived\s+none\b/.test(text)) continue;

      const match = pattern.exec(text);
      pattern.lastIndex = 0;
      if (!match) continue;

      const relative = path.slice(MODULE_ROOT.length + 1);
      if (!declared.has(match[1])) declared.set(match[1], []);
      declared.get(match[1]).push(relative);
    }
  };

  if (existsSync(PACKAGE_DIR)) walk(PACKAGE_DIR);
  return declared;
}

const declared = declaredUpstreamPaths();
if (declared.size === 0) {
  cannotVerify.push(
    `no derived file under ${PACKAGE_DIR} declares an upstream path, so this check verified nothing. ` +
      `Step 4.7 creates the package; a run that finds no declaration is a run that asserted nothing.`,
  );
}

// A git checkout at the pin is the only authority on whether a path was there. `cat-file` answers from
// the object database, so the sparse checkout's materialised paths do not matter.
//
// **Several derived files may cite one upstream file**, and two of them do: the root and `text` package
// docs both restate `ComparisonMergeUtil.kt`. That is not a defect to refuse — a derived file states the
// upstream file it is *primarily* derived from, and a package doc may summarise a file another package
// doc also summarises. Demanding a one-to-one mapping would force a false attribution on one of them.
const canCheckPaths = checkoutExists() && pinnedCommit !== null;
for (const [upstreamPath, declaring] of declared) {
  if (!canCheckPaths) {
    console.log(`  cannot check ${upstreamPath} — no usable checkout`);
    continue;
  }
  if (git('cat-file', '-e', `HEAD:${upstreamPath}`) === null) {
    failures.push(`${upstreamPath} (declared by ${declaring.join(', ')}) is not a file at the pinned revision`);
  } else {
    console.log(`  ok ${upstreamPath} (${declaring.length} derived file(s))`);
  }
}

// ------------------------------------------------- report

for (const message of cannotVerify) console.log(`NOT VERIFIED: ${message}`);
for (const message of failures) console.log(`FAIL: ${message}`);

const exitCode = failures.length > 0 ? 1 : cannotVerify.length > 0 ? 2 : 0;
const what = exitCode === 0 ? 'upstream sources verified' : 'upstream sources NOT verified';
const detail =
  exitCode === 0
    ? `${declared.size} declared path(s)`
    : `${failures.length} failure(s), ${cannotVerify.length} unverifiable`;
console.log(reportDuration(elapsed(), { what, detail }));
process.exit(exitCode);
