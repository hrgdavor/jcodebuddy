#!/usr/bin/env bun
/**
 * Does the build cache's include list actually cover every module? — plan step 9.8, and the answer to the question
 * that step's `Done when` line asks: **"if I change file X, which modules rebuild?"**
 *
 *   bun scripts/check-cache-inputs.js
 *
 * WHY THIS EXISTS. `input/global/includes` in `.mvn/maven-build-cache-config.xml` lists files a module's tests read
 * from *outside* the module, which Maven therefore cannot see. The extension resolves each entry against **each
 * module's own basedir**, so a repository-relative path needs `../` repeated once per directory level — and when
 * this check was written (2026-10-08) the list held a bare form and a `../../` form only. Measured: that covered the
 * root and the 25 depth-2 modules, while **`project-automation` (depth 1, and in the recorded gate set) and all seven
 * depth-3 modules resolved NOTHING** — so a change under `scripts/` was invisible to them and could be answered from a
 * cache entry that never saw it. That is the F-47 class of failure this repository keeps a scar for.
 *
 * WHAT IT FAILS ON, and nothing else:
 *
 *   1. an entry that resolves for **no** module — it hashes nothing (this found `../../merge-java/AGENTS.md`, a file
 *      that does not exist in the tree);
 *   2. a module that resolves **no** entry at all — it is blind to every file Maven cannot see.
 *
 * It reports coverage per depth, because the gap was a depth gap and a reviewer reading a failure should see which
 * depth is uncovered rather than only that something is.
 *
 * Its scope is "modules git would call ours": `proto/` is a driver workspace with its own unrelated builds, and the
 * walk respects `.gitignore`, so it is out by construction rather than by a skip list here.
 */
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { listFiles } from './lib/file-walk/index.js'
import { reportDuration, stopwatch } from './lib/timing.js'

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const elapsed = stopwatch()

const CONFIG = '.mvn/maven-build-cache-config.xml'
const config = readFileSync(join(root, CONFIG), 'utf8')
// Only the `<input>` block's includes: the configuration block has no `<include>`, but scoping it keeps the check
// honest if one is ever added elsewhere in the file.
const inputBlock = config.slice(config.indexOf('<input>'), config.indexOf('</input>'))
const includes = [...inputBlock.matchAll(/<include>([^<]+)<\/include>/g)].map((match) => match[1].trim())

if (includes.length === 0) {
  console.error(`no <include> entries found in ${CONFIG}'s <input> block`)
  process.exit(1)
}

const pomFiles = listFiles(root, { extensions: ['.xml'] })
  .filter((path) => path === 'pom.xml' || path.endsWith('/pom.xml'))
const modules = [...new Set(pomFiles.map((path) => dirname(path)))].sort()

const failures = []
const coverage = new Map()          // include -> the modules that resolve it
const perDepth = new Map()          // depth -> { modules, blind }
for (const include of includes) coverage.set(include, [])

for (const module of modules) {
  // `listFiles` yields '/'-separated paths on every platform, so depth is counted on '/' — splitting on the
  // platform separator makes every module look one level deep on Windows, which is how this check first
  // reported eleven healthy modules as blind.
  const depth = module === '.' ? 0 : module.split('/').length
  const resolved = includes.filter((include) => existsSync(join(root, module, include)))
  for (const include of resolved) coverage.get(include).push(module)
  const entry = perDepth.get(depth) ?? { modules: 0, blind: [] }
  entry.modules++
  if (resolved.length === 0) entry.blind.push(module === '.' ? '(root)' : module)
  perDepth.set(depth, entry)
}

let dead = 0
for (const include of includes) {
  const resolvedBy = coverage.get(include)
  if (resolvedBy.length === 0) {
    dead++
    failures.push(`the entry '${include}' resolves for no module — it hashes nothing`)
  }
}
const blindModules = [...perDepth.values()].flatMap((entry) => entry.blind)
for (const module of blindModules) {
  failures.push(`'${module}' resolves no entry at all — a change to a file Maven cannot see is invisible to it`)
}

if (process.argv.includes('--verbose')) {
  for (const include of includes) {
    console.log(`${include.padEnd(34)} resolves for ${coverage.get(include).length} module(s)`)
  }
}
for (const [depth, entry] of [...perDepth].sort((a, b) => a[0] - b[0])) {
  console.log(`depth ${depth}: ${entry.modules} module(s), ${entry.blind.length} blind`)
  for (const module of entry.blind) console.log(`   blind: ${module}`)
}
console.log(`${modules.length} module(s), ${includes.length} configured include(s), ${dead} dead entr(y|ies)`)

if (failures.length > 0) {
  console.error('')
  for (const failure of failures) console.error(`FAIL ${failure}`)
  console.error('')
  console.error('Every module must resolve at least one entry, and every entry must resolve for at least one module.')
  console.error(`Fix ${CONFIG}: an entry is relative to a module's basedir, so depth N needs '../' repeated N times.`)
  reportDuration(elapsed(), { what: 'cache inputs checked', files: modules.length })
  process.exit(1)
}
console.log('ok every module resolves an entry, and every entry resolves')
reportDuration(elapsed(), { what: 'cache inputs checked', files: modules.length })
