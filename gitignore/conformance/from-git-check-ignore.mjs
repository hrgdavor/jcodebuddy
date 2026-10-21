#!/usr/bin/env bun
// A conformance corpus from git's own answers, without carrying a repository's file contents.
//
//   bun conformance/from-git-check-ignore.mjs <checkout> [--out <file>] [--limit N] [--max-rule-bytes N]
//
// The checkout is a path argument, never bundled: this is the one case source that cannot live in this
// project's own tree. For each path `git ls-files --others --ignored --exclude-standard` reports, one
// `git check-ignore -v --stdin` call says which `.gitignore` line decided it, and a case is emitted with
// the `.gitignore` files that govern that path and the name of the path. Nothing else is read: a case
// carries **names and rules, never the ignored files' contents**.
//
// Cases whose verdict comes from `.git/info/exclude` or a global `core.excludesFile` are skipped, because
// this project deliberately does not read those files (see `README.md` and `doc/rules.md`).
//
// The output is the same schema as `gitignore-vectors.json`, so it can be run without editing anything:
//
//   bun conformance/from-git-check-ignore.mjs ../some-checkout --out .tmp/corpus.json
//   D:\wrk\zig\17\zig.exe build test -Dvectors=.tmp/corpus.json
//
// Licence: cases taken from a checkout carry that repository's `.gitignore` text and its path names, which
// is why the generated file is written to `.tmp/` (ignored) rather than committed. Cases taken from git's
// own test suite, which this tool does not read, would carry git's licence and must be attributed as such.

import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { basename, join, posix } from 'node:path';

const USAGE = `from-git-check-ignore — a conformance corpus from a checkout's own git answers

usage:
  bun conformance/from-git-check-ignore.mjs <checkout> [--out <file>] [--limit N] [--max-rule-bytes N]

options:
  --out <file>           write the vectors here instead of stdout
  --limit N              treat at most N candidates (default 1000; 0 = no limit)
  --max-rule-bytes N     skip a case whose rule files exceed N bytes (default 65536)
`;

function fail(message) {
  console.error(`from-git-check-ignore: ${message}`);
  console.error(USAGE);
  process.exit(2);
}

const argv = process.argv.slice(2);
let checkout = null;
let out = null;
let limit = 1000;
let maxRuleBytes = 65536;
for (let index = 0; index < argv.length; index += 1) {
  const arg = argv[index];
  if (arg === '--out') {
    out = argv[++index] ?? fail('--out needs a file');
  } else if (arg === '--limit') {
    limit = Number(argv[++index]);
    if (!Number.isInteger(limit) || limit < 0) fail('--limit needs a non-negative integer');
  } else if (arg === '--max-rule-bytes') {
    maxRuleBytes = Number(argv[++index]);
    if (!Number.isInteger(maxRuleBytes) || maxRuleBytes <= 0) fail('--max-rule-bytes needs a positive integer');
  } else if (arg === '--help' || arg === '-h') {
    console.log(USAGE);
    process.exit(0);
  } else if (arg.startsWith('-')) {
    fail(`unknown option: ${arg}`);
  } else if (checkout === null) {
    checkout = arg;
  } else {
    fail(`unexpected argument: ${arg}`);
  }
}
checkout ??= fail('a checkout path is required');

function git(root, args, input) {
  const result = spawnSync('git', ['-C', root, ...args], {
    input,
    stdio: [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
    maxBuffer: 1 << 30,
  });
  if (result.status !== 0) {
    const detail = result.stderr?.toString().trim() || `exit ${result.status}`;
    throw new Error(`git ${args.join(' ')} failed: ${detail}`);
  }
  return result.stdout;
}

let root;
try {
  root = realpathSync(spawnSync('git', ['-C', checkout, 'rev-parse', '--show-toplevel'], { encoding: 'utf8' })
    .stdout.trim());
} catch {
  fail(`${checkout} is not a git work tree`);
}

let commit = 'uncommitted';
try {
  commit = git(root, ['rev-parse', '--short=12', 'HEAD']).toString().trim();
} catch {
  // An empty repository has no HEAD; the cases are still valid.
}
const gitVersion = spawnSync('git', ['--version'], { encoding: 'utf8' }).stdout.trim();

const started = performance.now();
const listed = git(root, ['ls-files', '--others', '--ignored', '--exclude-standard', '-z']).toString('utf8');
const candidates = listed.split('\0').filter(Boolean);
const considered = limit === 0 ? candidates : candidates.slice(0, limit);
if (considered.length === 0) fail(`no ignored paths in ${root}`);

// One process for every candidate: check-ignore speaks `-z --stdin`, and its output is
// `source\0line\0pattern\0pathname\0` per ignored path.
let checkOutput;
try {
  checkOutput = git(root, ['check-ignore', '-v', '-z', '--stdin'], Buffer.from(`${considered.join('\0')}\0`)).toString('utf8');
} catch (error) {
  fail(error.message);
}
const fields = checkOutput.split('\0');
const matches = new Map();
for (let index = 0; index + 3 < fields.length; index += 4) {
  const source = fields[index];
  if (!source) break;
  matches.set(fields[index + 3], { source, line: fields[index + 1], pattern: fields[index + 2] });
}

/** Whether a check-ignore source is a `.gitignore` inside the checkout (not info/exclude or a global file). */
function isRepoGitignore(source) {
  if (!source.endsWith('.gitignore')) return false;
  if (source.startsWith('/') || /^[A-Za-z]:[\\/]/.test(source)) return false;
  if (source.split('/').includes('..')) return false;
  return existsSync(join(root, ...source.split('/')));
}

/** The `.gitignore` files that govern `pathname`, root first, each with its root-relative directory. */
function governingRules(pathname) {
  const dir = posix.dirname(pathname);
  const parts = dir === '.' ? [] : dir.split('/');
  const ruleFiles = [];
  for (let depth = 0; depth <= parts.length; depth += 1) {
    const relative = parts.slice(0, depth).join('/');
    const source = relative ? `${relative}/.gitignore` : '.gitignore';
    if (!existsSync(join(root, ...source.split('/')))) continue;
    const rules = readFileSync(join(root, ...source.split('/')), 'utf8');
    if (Buffer.byteLength(rules) > maxRuleBytes) return null;
    ruleFiles.push({ dir: relative, rules });
  }
  return ruleFiles;
}

const cases = [];
let skipped = 0;
for (const pathname of considered) {
  const match = matches.get(pathname);
  if (!match || !isRepoGitignore(match.source)) {
    skipped += 1;
    continue;
  }
  const ruleFiles = governingRules(pathname);
  if (ruleFiles === null) {
    skipped += 1;
    continue;
  }
  cases.push({
    name: `git: ${pathname}`,
    ruleFiles,
    path: pathname,
    isDir: false,
    expect: 'exclude',
    source: `${match.source}:${match.line}`,
  });
}

if (cases.length === 0) fail(`no cases: every ignored path came from a source this project does not read`);

const document = {
  format: 'gitignore-vectors',
  version: 1,
  generatedBy: `conformance/from-git-check-ignore.mjs from ${basename(root)} at ${commit} (${gitVersion}); cases carry names and rules, never file contents`,
  note: 'Each case is a path git check-ignore reported as ignored by a .gitignore file in the checkout. The `source` field names the deciding file and line; it is provenance, not part of the matcher contract.',
  cases,
};
const json = `${JSON.stringify(document, null, 2)}\n`;
if (out) {
  writeFileSync(out, json);
} else {
  process.stdout.write(json);
}

const elapsed = Math.round(performance.now() - started);
console.error(
  `from-git-check-ignore: ${cases.length} cases from ${basename(root)} ` +
    `(${considered.length} candidates, ${skipped} skipped) in ${elapsed}ms`,
);
