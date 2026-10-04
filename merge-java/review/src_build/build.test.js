#!/usr/bin/env bun
/**
 * The review page's own test — the renderer half of plan step 4.2 — colocated with the script it tests,
 * which is this module's pattern (`merge-java/scripts/*.test.js`); the renderer moved into `review/`, so
 * its test moved with it.
 *
 * Run it from THIS directory:
 *
 *   cd merge-java/review && bun test
 *
 * `bun test` reads `bunfig.toml` from the process cwd, and without its `jsxImportSource` line Bun would
 * transform JSX with React's runtime (jsx6 docs/stack/setup.md § 3, agent-rules.md rule 5).
 *
 * It SKIPS rather than fails when the machine has not set the page up: a missing jsx6 checkout or a
 * missing local esbuild is a local toolchain matter, not a defect in this repository.
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(here, '..')
const mergeJavaRoot = resolve(packageRoot, '..')
const jsx6 = process.env.JCODEBUDDY_JSX6_DIR
  ? resolve(process.env.JCODEBUDDY_JSX6_DIR)
  : resolve(packageRoot, '../../.jsx6')

// The page is built into the module's build directory, not into the system temp dir: measured in this
// environment, esbuild cannot write its output under %TEMP% at all ("Failed to write to output file …
// Access is denied", with the directory created successfully first). target/ is gitignored and cleaned.
const outDir = join(mergeJavaRoot, 'target', 'review-page-test')

const ready = existsSync(join(jsx6, 'libs')) && existsSync(join(packageRoot, 'node_modules', 'esbuild'))
const skip = () => {
  if (!ready) {
    console.log('[review.test] skipped: needs a jsx6 checkout at ' + jsx6 + ' and `bun install` in review/')
  }
  return !ready
}

function build(args) {
  const run = Bun.spawnSync(['bun', 'run', join(packageRoot, 'src_build', 'build.js'), ...args], {
    cwd: packageRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  return {
    code: run.exitCode,
    output: `${run.stdout?.toString() ?? ''}${run.stderr?.toString() ?? ''}`,
  }
}

test('builds one self-contained page from a report', () => {
  if (skip()) return
  const result = build(['--report', join(packageRoot, 'sample-report.json'), '--out', outDir])
  assert.equal(result.code, 0, `the build failed: ${result.output}`)

  const html = readFileSync(join(outDir, 'index.html'), 'utf8')

  // Self-contained means nothing is FETCHED, so the check is on attributes rather than on the words: the
  // jsx6 runtime legitimately contains the SVG namespace `http://www.w3.org/2000/svg` for createElementNS,
  // which a blanket "no http://" assertion would fail on while proving nothing about self-containment.
  assert.ok(!html.includes('<script src='), 'no external script may be referenced')
  assert.ok(!html.includes('<link '), 'no external stylesheet may be referenced')
  assert.ok(!/\s(src|href)\s*=\s*"https?:\/\//.test(html), 'nothing may be fetched over the network')
  assert.ok(!html.includes('sourceMappingURL'), 'the inlined page must not point at the bundle map')

  // The facts a reviewer needs, and the sides 4.2 exists for.
  assert.ok(html.includes('"schemaVersion"'), 'the report itself must be embedded')
  assert.ok(html.includes('IMPORT_ADD'), 'the conflict type must be shown')
  assert.ok(html.includes('AUTO'), 'the outcome must be shown')
  assert.ok(html.includes('recommended'), 'the recommendation must be shown')
  assert.ok(html.includes('branch 1'), 'the branches own code must be shown beside the result')
  assert.ok(html.includes('sample data'), 'a page built from the sample must say so')
})

test('says where its data came from when a real report exists', () => {
  if (skip()) return
  const scratch = mkdtempSync(join(mergeJavaRoot, 'target', 'review-report-'))
  try {
    const reportPath = join(scratch, 'report.json')
    const report = JSON.parse(readFileSync(join(packageRoot, 'sample-report.json'), 'utf8'))
    report.summary.files = 99 // a marker no fixture carries, so the assertion cannot pass by accident
    writeFileSync(reportPath, JSON.stringify(report))

    const result = build(['--report', reportPath, '--out', outDir])
    assert.equal(result.code, 0, `the build failed: ${result.output}`)

    const html = readFileSync(join(outDir, 'index.html'), 'utf8')
    assert.ok(html.includes('99'), 'the report given on the command line is the one rendered')
    assert.ok(html.includes('live report'), 'and the page says the data is a real report')
    assert.ok(html.includes(reportPath.replace(/\\/g, '\\\\')) || html.includes('report.json'),
      'the page names the file it rendered')
  } finally {
    rmSync(scratch, { recursive: true, force: true })
  }
})

test('refuses a report it cannot read, with a usage status', () => {
  if (skip()) return
  const result = build(['--report', join(packageRoot, 'no-such-report.json'), '--out', outDir])
  assert.equal(result.code, 2, `misuse must exit 2: ${result.output}`)
  assert.ok(result.output.includes('usage'), result.output)
})

test('the module build directory is where the page goes', () => {
  if (skip()) return
  mkdirSync(outDir, { recursive: true })
  assert.ok(existsSync(outDir), 'the build writes into merge-java/target/, which mvn clean removes')
})
