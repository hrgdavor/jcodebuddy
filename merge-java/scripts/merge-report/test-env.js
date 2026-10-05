#!/usr/bin/env bun
/**
 * Build a throwaway mid-merge repository from a test fixture, and put it in front of the review page — the
 * environment to look at the UI in, and to rebuild after every change to it.
 *
 *   bun run merge-java/scripts/merge-report/test-env.js [<fixture|path>] [options]
 *
 * What you get, in one command:
 *
 *   1. a real git repository under `.tmp/merge-review/test-env-<fixture>` left mid-merge, with conflict markers
 *      (built by `scripts/git-sample/sample-repo.js`, so it is the same fixture the tests use);
 *   2. the review report for its conflicted file, and the page built from it;
 *   3. the `file://` URL to open — no server, no host;
 *   4. the exact commands to rebuild the page after a UI change, and to recreate the whole environment from
 *      scratch (which `--force` does, so a half-edited repository never accumulates).
 *
 * Options:
 *   --list                 list the fixtures that can be simulated, then stop
 *   --out <dir>            where the environment lives (default: <repo>/.tmp/merge-review/test-env-<fixture>)
 *   --branch <name>        the branch the decisions belong to (default: feature, the fixture's own branch)
 *   --no-flow              build the repository only, do not run the review flow
 *   --help                 this text
 *
 * A fixture is a directory holding three complete versions of the same file -
 * `src/test/resources/fixtures/<case>/{base,ours,theirs}/*.txt` - and you can pass a path to your own instead of a
 * name (`sample-repo.js ../my-cases/my-case`). See scripts/git-sample/README.md.
 *
 * Note for whoever reviews the UI this way: the two shipped fixtures exercise the page, not the apply. Both produce
 * a block carrying several conflicts, and the tool refuses to compose several conflicts into one block answer, so
 * `LEFT_MANUAL` there is correct rather than a bug - see review/README.md, "What the engine will not apply".
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { repoRoot, run } from '../../../scripts/lib/toolchain.js'

const here = dirname(fileURLToPath(import.meta.url))
const mergeJavaRoot = resolve(here, '../..')
const root = repoRoot()

const args = process.argv.slice(2)
const valueOf = (flag, fallback = null) => {
  const index = args.indexOf(flag)
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback
}
const positional = args.filter((arg, i) => !arg.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')))

if (args.includes('--help') || args.includes('-h')) {
  // The doc block is the usage text: drop the shebang and the block comment's own decoration.
  console.log(
    readFileSync(fileURLToPath(import.meta.url), 'utf8')
      .split('*/')[0]
      .replace(/^#![^\n]*\n/, '')
      .split('\n')
      .map((line) => line.replace(/^\s*\/?\*+\/?\s?/, ''))
      .join('\n')
      .trim(),
  )
  process.exit(0)
}
if (args.includes('--list')) {
  const listed = run('bun', ['run', join(mergeJavaRoot, 'scripts', 'git-sample', 'sample-repo.js'), '--list'], {
    cwd: mergeJavaRoot,
  })
  process.exit(listed.status ?? 0)
}

const fixture = positional[0] ?? 'import-add-both'
const branch = valueOf('--branch', 'feature')
const fixtureName = fixture.replace(/[\\/]+$/, '').split(/[\\/]/).pop()
const outDir = resolve(valueOf('--out', join(root, '.tmp', 'merge-review', `test-env-${fixtureName}`)))

console.log(`[test-env] fixture:  ${fixture}`)
console.log(`[test-env] folder:   ${outDir}`)

// ── 1. The environment: a real repository, left mid-merge, rebuilt from scratch every time ──────────
const built = run('bun', [
  'run', join(mergeJavaRoot, 'scripts', 'git-sample', 'sample-repo.js'),
  fixture, '--merge', '--force', '--out', outDir,
], { cwd: mergeJavaRoot })
if (built.status !== 0) {
  console.error(`[test-env] could not build the environment (exit ${built.status})`)
  process.exit(built.status ?? 1)
}

// ── 2. Which files carry markers (a fixture can also merge cleanly, which is worth saying out loud) ──
function markerFiles(dir) {
  const found = []
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      if (entry.name === '.git') {
        continue
      }
      const full = join(current, entry.name)
      if (entry.isDirectory()) {
        walk(full)
      } else if (readFileSync(full, 'utf8').includes('<<<<<<<')) {
        found.push(full)
      }
    }
  }
  walk(dir)
  return found
}

const conflicted = markerFiles(outDir)
console.log(`[test-env] conflicted: ${conflicted.length ? conflicted.map((f) => relative(outDir, f)).join(', ') : '(none)'}`)

if (conflicted.length === 0) {
  console.log('')
  console.log('  This fixture merges cleanly, so there is nothing for the review page to show.')
  console.log(`  Inspect it with:  git -C "${outDir}" log --oneline --graph --all`)
  console.log('  Try another fixture:')
  run('bun', ['run', join(mergeJavaRoot, 'scripts', 'git-sample', 'sample-repo.js'), '--list'], { cwd: mergeJavaRoot })
  process.exit(0)
}

const target = conflicted[0]

// ── 3. The review flow: report + page + the URL to open ─────────────────────────────────────────────
const reportPath = join(root, '.tmp', 'merge-review', `${target.slice(target.lastIndexOf(sep) + 1).replace(/\.[^.]+$/, '')}-merge-report.json`)
if (!args.includes('--no-flow')) {
  const flow = run('bun', [
    'run', join(mergeJavaRoot, 'scripts', 'merge-report', 'review-file.js'),
    target, '--branch', branch,
  ], { cwd: root })
  if (flow.status !== 0 && flow.status !== 1) {
    console.error(`[test-env] the review flow failed (exit ${flow.status})`)
    process.exit(flow.status ?? 1)
  }
} else {
  console.log(`[test-env] --no-flow: the file is ready at ${target}`)
}

// ── 4. How to come back here, which is the point of a test environment ──────────────────────────────
console.log('')
console.log('  ── recreate, after a UI change ───────────────────────────────────────────────')
console.log('  the page only (same repository, same report):')
console.log(`    cd merge-java/review && bun run src_build/build.js --report "${reportPath}"`)
console.log('  then reload the file:// page it prints. Sources: src/index.jsx (the page),')
console.log('  src/decisions.js (the export format), index.html (the styles).')
console.log('')
console.log('  ── recreate, from scratch (always safe: --force replaces the folder) ─────────')
console.log(`    bun run merge-java/scripts/merge-report/test-env.js ${fixture} --branch ${branch}`)
console.log('')
console.log(`  repository:  git -C "${outDir}" status`)
console.log(`  conflicted:  ${target}`)
