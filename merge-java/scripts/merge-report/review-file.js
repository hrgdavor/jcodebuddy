#!/usr/bin/env bun
/**
 * Review ONE conflict file in the browser — the whole flow, from a file with git conflict markers to a page where
 * a reviewer picks what each block should become, and back again.
 *
 *   bun run merge-java/scripts/merge-report/review-file.js <file> [options]
 *
 * What it does, in order:
 *
 *   1. runs `MergeFileTool <file> --report <scratch>/<name>-merge-report.json` (a DRY RUN: nothing is written to
 *      the file), which both analyses the blocks and writes the report the page renders;
 *   2. builds the review page from that report with `merge-java/review/src_build/build.js`;
 *   3. prints the file:// path to open — **no webview, no server, no host**: the page is one self-contained HTML
 *      file, which is what makes it usable on a machine that has nothing else running;
 *   4. and if you pass `--apply-decisions <export.json>`, runs the SAME CLI again with `--decisions <export.json>
 *      --apply`, so what the reviewer accepted is recorded into the branch's history and written to the file.
 *      Blocks nobody decided keep their conflict markers, which is what lets the merge continue in another editor.
 *
 * Options:
 *   --branch <name>        the branch whose decision history is consulted (default: MergeFileTool's own default)
 *   --classpath <entries>  the project's compile classpath, separator-separated, so conflicts about the project's
 *                          own types can be resolved instead of escalated
 *   --classpath-from <f>   a file holding that classpath, as
 *                          `mvn dependency:build-classpath -Dmdep.outputFile=<f>` writes it (cross-shell, and the
 *                          reason this option exists: the alternative is quoting a classpath in a shell)
 *   --out <dir>            where the page is built (default: merge-java/review/build)
 *   --apply-decisions <f>  the decisions file the page exported: record and apply them (implies --apply)
 *   --no-build             stop after writing the report (do not build the page)
 *   --help                 this text
 *
 * Exit status: whatever MergeFileTool reports for the analysis (0 nothing left for a human, 1 something is),
 * because that is the number a caller scripting this cares about. 2 for this script's own misuse.
 */
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { delimiter, envWith, repoRoot, resolveJdk25, resolveMaven, run } from '../../../scripts/lib/toolchain.js'
import { moduleClasspath } from './classpath.js'

const here = dirname(fileURLToPath(import.meta.url))
const mergeJavaRoot = resolve(here, '../..')
const root = repoRoot()

const args = process.argv.slice(2)
if (args.includes('--help') || args.includes('-h') || args.length === 0) {
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
  process.exit(args.length === 0 ? 2 : 0)
}

const valueOf = (flag, fallback = null) => {
  const index = args.indexOf(flag)
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback
}
const positional = args.filter((arg, i) => !arg.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')))
const target = positional[0]
if (!target) {
  console.error('usage: review-file.js <file> [--branch <name>] [--classpath <entries>] [--classpath-from <file>]')
  process.exit(2)
}
const file = resolve(target)
if (!existsSync(file)) {
  console.error(`[review-file] no such file: ${file}`)
  process.exit(2)
}

const branch = valueOf('--branch')
const outDir = resolve(valueOf('--out', join(mergeJavaRoot, 'review', 'build')))
const applyDecisions = valueOf('--apply-decisions')
const scratch = join(root, '.tmp', 'merge-review')
mkdirSync(scratch, { recursive: true })
const reportPath = join(scratch, `${basename(file).replace(/\.[^.]+$/, '')}-merge-report.json`)

// The classpath, either given or read from the file Maven can write for you. Without one, a conflict about the
// project's own types escalates instead of resolving - the tool says so with a warning, which is better than a
// silently empty classpath here.
const classpath = valueOf('--classpath') ?? (valueOf('--classpath-from')
  ? readFileSync(resolve(valueOf('--classpath-from')), 'utf8').trim()
  : null)

const jdk = resolveJdk25()
const maven = resolveMaven()
const env = envWith(jdk)

// The module's own classes plus its dependencies: MergeFileTool runs from target/classes, not from an
// installed jar. The cache is keyed by the POM, so adding a dependency cannot leave a stale answer behind.
const resolvedClasspath = moduleClasspath({
  moduleRoot: mergeJavaRoot, scratchDir: scratch, maven, env, cwd: root,
})
console.log(`[review-file] module classpath ${resolvedClasspath.fromCache ? 'from cache' : 'resolved'} (keyed by the POM)`)
const runClasspath = [
  join(mergeJavaRoot, 'target', 'classes'),
  resolvedClasspath.entries,
].filter(Boolean).join(delimiter)

const fileToolArgs = [
  '-cp', runClasspath,
  'com.codebuddy.merge.MergeFileTool', file,
  '--report', reportPath,
  ...(branch ? ['--branch', branch] : []),
  ...(classpath ? ['--classpath', classpath] : []),
  ...(applyDecisions ? ['--decisions', resolve(applyDecisions), '--apply'] : []),
]

console.log(`[review-file] analysing ${file}`)
const analysis = run(jdk.java, fileToolArgs, { env, cwd: root })
const analysisStatus = analysis.status ?? 1

if (args.includes('--no-build')) {
  console.log(`[review-file] report: ${reportPath}`)
  process.exit(analysisStatus)
}

console.log('[review-file] building the review page')
const page = run('bun', ['run', join(mergeJavaRoot, 'review', 'src_build', 'build.js'),
  '--report', reportPath, '--out', outDir], { env, cwd: join(mergeJavaRoot, 'review') })
if (page.status !== 0) {
  console.error('[review-file] the page build failed')
  process.exit(page.status ?? 1)
}

const pagePath = join(outDir, 'index.html')
console.log('')
console.log(`  open this:   ${pathToFileURL(pagePath).href}`)
console.log(`  report:      ${reportPath}`)
console.log('  accept what you can, then export and apply with:')
console.log(`    bun run merge-java/scripts/merge-report/review-file.js ${target} \\`)
console.log('        --apply-decisions <the downloaded decisions.json> [--branch <name>]')
console.log('  blocks nobody decided keep their markers, so the merge can continue in your editor.')
process.exit(analysisStatus)
