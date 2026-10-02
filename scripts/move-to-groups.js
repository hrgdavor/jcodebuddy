#!/usr/bin/env bun
/**
 * Move modules into the repository's group folders (one group at a time).
 *
 * The repository's top level is being grouped by family — `webview/` did it first, and now `watch/`,
 * `hipster-entity/` and `jcodebuddy/` follow. Doing that by hand across ~28 modules is how a reactor ends
 * up with a parent POM that resolves to a stale installed artifact, so the move is scripted:
 *
 *   1. `fs.renameSync` each module directory into its group folder (no shell, so no shell-specific path);
 *   2. fix each moved POM's `<parent><relativePath>` for the extra level (`../pom.xml` → `../../pom.xml`);
 *   3. rewrite the root POM's `<module>` entries;
 *   4. rewrite path references in tracked text files, longest module name first so `java-watch-run-sample`
 *      is never rewritten as `java-watch-run` + garbage.
 *
 * Usage:
 *   bun scripts/move-to-groups.js --dry-run              # what would change, and how many references
 *   bun scripts/move-to-groups.js --group hipster-entity # move one group
 *   bun scripts/move-to-groups.js --all                  # move every configured group
 *
 * Notes the next reader needs:
 * - Step 4 is a *flat* prefix rewrite: `hipster-entity/hipster-entity-api/` becomes `hipster-entity/hipster-entity-api/`
 *   everywhere, including inside a moved module. That is right for root-relative strings and for links from
 *   outside the group, and wrong for a relative link between two modules that moved *together* (both sides
 *   gained the same prefix, so the relative path is unchanged and must NOT be rewritten). The link checker
 *   is the oracle for those: `node scripts/check-repo-links.mjs` fails on any that step 4 got wrong, and the
 *   fix is to point the link at the sibling as it is now. Java/JS strings that name a repo-relative path are
 *   covered by step 4 correctly, and the gate catches the ones that are not.
 * - `.gitignore`, `target/`, `.kilo/` and `node_modules/` are skipped; the moves themselves are ordinary
 *   renames, so git records them as renames at commit time.
 */

import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

/**
 * The configured moves: `from` is a module directory at the repository root today, `to` is where it goes.
 * Order inside a group does not matter (step 4 sorts by name length); the groups are independent.
 */
const MOVES = [
  { group: 'watch', from: 'java-watch-core', to: 'watch/java-watch-core' },
  { group: 'watch', from: 'java-watch-scp', to: 'watch/java-watch-scp' },
  { group: 'watch', from: 'java-watch-run', to: 'watch/java-watch-run' },
  { group: 'watch', from: 'java-watch-run-sample', to: 'watch/java-watch-run-sample' },
  { group: 'watch', from: 'java-watch-agent', to: 'watch/java-watch-agent' },
  { group: 'hipster-entity', from: 'hipster-entity-api', to: 'hipster-entity/hipster-entity-api' },
  { group: 'hipster-entity', from: 'hipster-entity-core', to: 'hipster-entity/hipster-entity-core' },
  { group: 'hipster-entity', from: 'hipster-entity-tooling', to: 'hipster-entity/hipster-entity-tooling' },
  { group: 'hipster-entity', from: 'hipster-entity-jackson', to: 'hipster-entity/hipster-entity-jackson' },
  { group: 'hipster-entity', from: 'hipster-entity-example', to: 'hipster-entity/hipster-entity-example' },
  { group: 'hipster-entity', from: 'hipster-entity-test', to: 'hipster-entity/hipster-entity-test' },
  { group: 'jcodebuddy', from: 'jcodebuddy-core', to: 'jcodebuddy/jcodebuddy-core' },
  { group: 'jcodebuddy', from: 'jcodebuddy-codegen-api', to: 'jcodebuddy/jcodebuddy-codegen-api' },
  { group: 'jcodebuddy', from: 'jwa-builder', to: 'jcodebuddy/jwa-builder' },
  { group: 'jcodebuddy', from: 'jwa-builder-api', to: 'jcodebuddy/jwa-builder-api' },
  { group: 'jcodebuddy', from: 'metadata-server', to: 'jcodebuddy/metadata-server' },
  { group: 'jcodebuddy', from: 'metadata-mcp-server', to: 'jcodebuddy/metadata-mcp-server' },
  { group: 'jcodebuddy', from: 'metadata-arena', to: 'jcodebuddy/metadata-arena' },
  // `hipster-ioc/` is the group *and* the documentation directory it has always been, exactly like `webview/`:
  // its module directories go inside it and the docs stay at its root.
  { group: 'hipster-ioc', from: 'hipster-ioc-api', to: 'hipster-ioc/hipster-ioc-api' },
  { group: 'hipster-ioc', from: 'hipster-ioc-tooling', to: 'hipster-ioc/hipster-ioc-tooling' },
  { group: 'hipster-ioc', from: 'hipster-ioc-test', to: 'hipster-ioc/hipster-ioc-test' },
  // Five earlier attempts at what `webview/jwa-sidecar` and the webview hosts now do. They are not reactor
  // modules (two Kotlin/Gradle IntelliJ plugins, two VS Code extensions, one TypeScript library), so this is a
  // directory move with no POM to fix; their duplication against the current implementation is audited by
  // plan step 3.0p, and 3.0q merges or deletes what that finds.
  { group: 'webview', from: 'intellij-jwa', to: 'webview/intellij-jwa' },
  { group: 'webview', from: 'intellij-jswa', to: 'webview/intellij-jswa' },
  { group: 'webview', from: 'vscode-jwa', to: 'webview/vscode-jwa' },
  { group: 'webview', from: 'vscode-jswa', to: 'webview/vscode-jswa' },
  { group: 'webview', from: 'jswa-core', to: 'webview/jswa-core' },
];

const TEXT_EXTENSIONS = ['.md', '.java', '.js', '.mjs', '.cjs', '.ts', '.json', '.xml', '.txt', '.properties', '.yml', '.yaml'];
const SKIP_DIRS = new Set(['.git', 'target', 'node_modules', '.kilo', '.jsx6', 'build', 'out', 'dist']);

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const all = args.includes('--all');
const groupArg = args.includes('--group') ? args[args.indexOf('--group') + 1] : null;

if (!dryRun && !all && !groupArg) {
  console.error('Nothing to do: pass --dry-run, --group <name> or --all.');
  process.exit(2);
}

const moves = all ? MOVES : MOVES.filter((m) => m.group === groupArg);
if (moves.length === 0) {
  console.error(`No configured moves for group '${groupArg}'. Groups: ${[...new Set(MOVES.map((m) => m.group))].join(', ')}`);
  process.exit(2);
}

/** Every module name that is moving in this run, longest first — see the header's note on step 4. */
const prefixes = moves.map((m) => ({ from: m.from, to: m.to })).sort((a, b) => b.from.length - a.from.length);

/** Collect the text files to rewrite, once, skipping derived and ignored trees. */
function textFiles(dir, found = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      textFiles(join(dir, entry.name), found);
    } else if (TEXT_EXTENSIONS.some((ext) => entry.name.endsWith(ext))) {
      found.push(join(dir, entry.name));
    }
  }
  return found;
}

const summary = { moved: [], relativePaths: 0, modules: 0, rewritten: 0, files: 0 };

if (!dryRun) {
  // 1. the directories themselves
  for (const move of moves) {
    const from = join(root, move.from);
    const to = join(root, move.to);
    if (!existsSync(from)) {
      console.error(`SKIP (missing): ${move.from}`);
      continue;
    }
    if (existsSync(to)) {
      console.error(`REFUSING: ${move.to} already exists — resolve that by hand.`);
      process.exit(1);
    }
    const parent = dirname(to);
    if (existsSync(parent) && !statSync(parent).isDirectory()) {
      console.error(`REFUSING: ${parent} exists and is not a directory — resolve that by hand.`);
      process.exit(1);
    }
    if (!existsSync(parent)) mkdirSync(parent, { recursive: true });
    renameSync(from, to);
    summary.moved.push(move);
  }

  // 2. each moved POM's parent path (one level deeper than the root)
  for (const move of summary.moved) {
    const pom = join(root, move.to, 'pom.xml');
    if (!existsSync(pom)) continue;
    const text = readFileSync(pom, 'utf8');
    const fixed = text.replace(/<relativePath>\.\.\/pom\.xml<\/relativePath>/g,
      '<relativePath>../../pom.xml</relativePath>');
    if (fixed !== text) {
      writeFileSync(pom, fixed);
      summary.relativePaths++;
    }
  }

  // 3. the root POM's module entries
  const rootPomPath = join(root, 'pom.xml');
  let rootPom = readFileSync(rootPomPath, 'utf8');
  for (const move of summary.moved) {
    const before = rootPom;
    rootPom = rootPom.replace(new RegExp(`<module>${move.from}</module>`, 'g'), `<module>${move.to}</module>`);
    if (rootPom !== before) summary.modules++;
  }
  writeFileSync(rootPomPath, rootPom);
}

// 4. path references in text. Idempotent by construction: `hipster-entity/hipster-entity-api/` is rewritten only when it is
//    not *already* preceded by its group folder, so running the script twice cannot produce
//    `hipster-entity/hipster-entity-api/` — which it did once, and --fix-links cleans up.
const files = textFiles(root);
const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
for (const file of files) {
  const text = readFileSync(file, 'utf8');
  let next = text;
  for (const { from, to } of prefixes) {
    const group = to.slice(0, to.lastIndexOf('/'));
    next = next.replace(new RegExp(`(?<!${escapeRegex(group)}/)${escapeRegex(from)}/`, 'g'), `${to}/`);
  }
  if (next !== text) {
    summary.files++;
    summary.rewritten += text.split('\n').reduce((count, line, index) => {
      const replacement = next.split('\n')[index];
      return count + (replacement !== line ? 1 : 0);
    }, 0);
    if (!dryRun) writeFileSync(file, next);
  }
}

// 5. The two cases step 4 gets wrong, repaired where they are *provable*: a broken relative link is fixed only
//    when a candidate target actually exists. A sibling link between modules that moved together gained the
//    group prefix twice (step 4 is flat), and a link to a target that did not move is now one level short.
//    The filesystem decides, not a rule in a comment — and anything still broken is printed, never guessed at.
if (args.includes('--fix-links')) {
  const groups = [...new Set(moves.map((m) => m.group))];
  const alternation = groups.join('|');
  const modules = moves.map((m) => m.from);

  // 5a. Undo the doubling a second run of step 4 produced before it was idempotent:
  //     `hipster-entity/hipster-entity-api` -> `hipster-entity/hipster-entity-api`.
  let dedoubled = 0;
  for (const move of moves) {
    const group = move.group;
    const doubled = new RegExp(`${escapeRegex(group)}/${escapeRegex(group)}/${escapeRegex(move.from)}(?![\\w-])`, 'g');
    for (const file of files) {
      const text = readFileSync(file, 'utf8');
      if (!doubled.test(text)) continue;
      doubled.lastIndex = 0;
      const next = text.replace(doubled, `${group}/${move.from}`);
      dedoubled += text.split('\n').filter((line, index) => line !== next.split('\n')[index]).length;
      if (!dryRun) writeFileSync(file, next);
    }
  }
  console.log(`  doubled group prefixes collapsed: ${dryRun ? '(dry run)' : dedoubled} line(s)`);

  const linkPattern = /\]\(([^)\s]+)\)/g;
  let repaired = 0;
  const stillBroken = [];

  // 5b. Module names used as *path segments* in Java/JS — `resolve("hipster-entity/hipster-entity-example")`. Step 4 only saw
  //     the slash form, and the gate found the rest the hard way: every generated-source compile looked for
  //     its classpath in a directory that no longer exists. The rewrite is anchored on `resolve(` and
  //     `Path.of(` so a module name used as a *label* (an index's `module` field, an assertion's expected
  //     value) is never touched — those names did not change.
  let segments = 0;
  if (args.includes('--fix-segments')) {
    const segmentPrefixes = moves.map((m) => ({ from: m.from, to: m.to })).sort((a, b) => b.from.length - a.from.length);
    for (const file of files.filter((f) => f.endsWith('.java') || f.endsWith('.js') || f.endsWith('.mjs'))) {
      const text = readFileSync(file, 'utf8');
      let next = text;
      for (const { from, to } of segmentPrefixes) {
        const quoted = new RegExp(`(resolve\\(|Path\\.of\\()"${escapeRegex(from)}"`, 'g');
        next = next.replace(quoted, `$1"${to}"`);
      }
      if (next !== text) {
        segments += text.split('\n').filter((line, index) => line !== next.split('\n')[index]).length;
        if (!dryRun) writeFileSync(file, next);
      }
    }
    console.log(`  module names used as path segments rewritten: ${dryRun ? '(dry run)' : segments} line(s)`);
  }

  for (const file of files.filter((f) => f.endsWith('.md'))) {
    const text = readFileSync(file, 'utf8');
    const dir = dirname(file);
    let touched = false;

    const next = text.replace(linkPattern, (whole, target) => {
      if (/^(https?:|mailto:|#|\/)/.test(target)) return whole;
      const [pathPart, anchor = ''] = target.split(/(?=#)/);
      if (existsSync(join(dir, pathPart))) return whole; // resolves as written: leave it alone
      const collapsed = pathPart.replace(new RegExp(`^\\.\\./(${alternation})/`), '../');
      const candidates = [
        `../${pathPart}`,
        collapsed,
        `../${collapsed}`,
        pathPart.replace(new RegExp(`^(${alternation})/`), ''),
      ];
      const hit = candidates.find((candidate) => existsSync(join(dir, candidate)));
      if (!hit) {
        stillBroken.push(`${relative(root, file).split(sep).join('/')} -> ${target}`);
        return whole;
      }
      touched = true;
      repaired++;
      return `](${hit}${anchor})`;
    });

    if (touched && !dryRun) writeFileSync(file, next);
  }

  console.log(`  relative links repaired: ${dryRun ? '(dry run)' : repaired}`);
  if (stillBroken.length > 0) {
    console.log(`  STILL BROKEN (${stillBroken.length}) — fix these by hand:`);
    for (const line of stillBroken.slice(0, 20)) console.log(`    ${line}`);
  }
}

const rel = (p) => relative(root, p).split(sep).join('/');
console.log(`${dryRun ? 'DRY RUN' : 'MOVED'} — group(s): ${[...new Set(moves.map((m) => m.group))].join(', ')}`);
for (const move of moves) console.log(`  ${move.from}  ->  ${move.to}`);
console.log(`  POMs whose <relativePath> was fixed: ${dryRun ? '(dry run)' : summary.relativePaths}`);
console.log(`  root POM <module> entries rewritten: ${dryRun ? '(dry run)' : summary.modules}`);
console.log(`  text files with path references: ${summary.files} (${summary.rewritten} lines)`);
if (dryRun) console.log('  nothing was written; re-run without --dry-run to apply');
console.log('  next: bun scripts/check-repo-links.mjs, then the gate');