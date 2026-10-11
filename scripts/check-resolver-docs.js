#!/usr/bin/env bun
/**
 * The page policy of `merge-java/docs/resolvers`, checked through the **reference implementation's own marker
 * parser** — never a regex of our own.
 *
 *   bun scripts/check-resolver-docs.js
 *
 * `@hrg/inject-examples` owns what a marker is (`parseMarker`, `findMarkers`), where the fenced blocks are
 * (`fenceRanges`) and what a region directive is (`regionDirective`). This checker imports those functions, so
 * its answer cannot disagree with the injector's about a line inside a fence, an empty `#` fragment or a
 * scheme-like destination — which a hand-rolled Java regex did, and that regex is why this file exists (see the
 * note at the end). The injector's own `--check` (`npm run check:examples`) proves nothing has drifted; it cannot
 * say that a page shows too few examples, that a fenced block has no marker in front of it, or that a page shows
 * something outside test material. That is this checker's job.
 *
 * What it checks, per page:
 *
 *  1. every marker's path resolves, relative to the page, to a regular file under `merge-java/src/test/` —
 *     examples come from test material, never from prose or from main sources;
 *  2. at least four markers on a resolver page;
 *  3. a marker into `ConflictFixtures.java#<type>-sample`, where `<type>` is the conflict type the index table
 *     states for that resolver, and that region really exists in the fixtures file;
 *  4. a marker into the resolver's own test (`<FolderInPascalCase>Test.java`);
 *  5. every fenced block in a resolver page is preceded by a marker — a hand-written example is the copy that
 *     goes stale, so it is not accepted.
 *
 * The index page (`docs/resolvers/README.md`) is held to rule 1 only: it is a table of contents whose examples
 * are injected like any other page, not a resolver page with the shape rules above.
 *
 * **Why not in Java.** The marker grammar is the reference's, and `ResolverDocsTest` used to carry a second
 * definition of it in a regex — fence-blind where `findMarkers` is fence-aware, accepting a scheme-like target
 * that `parseMarker` rejects, and refusing the empty `#` fragment that `parseMarker` reads as a whole-file
 * marker. Those checks now live here, in the language the reference is written in; what stays in Java is what
 * needs the resolver registry (`ResolverDocsTest`: folders against the registry, the index rows, and a page
 * naming its resolver and conflict type).
 *
 * The package's **root** export is all this needs, so it runs against the published `1.0.1` as well as the local
 * `1.1.0` — unlike `scripts/webview-location`, which needs the `lib/section.mjs` subpath that first ships in
 * 1.1.0.
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { findMarkers, fenceRanges, parseMarker, regionDirective } from '@hrg/inject-examples'
import { reportDuration, stopwatch } from './lib/timing.js'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const DOCS = join(ROOT, 'merge-java', 'docs', 'resolvers')
const INDEX = join(DOCS, 'README.md')
const TEST_ROOT = join(ROOT, 'merge-java', 'src', 'test')
const FIXTURES = join(TEST_ROOT, 'java', 'com', 'codebuddy', 'merge', 'ConflictFixtures.java')

const elapsed = stopwatch()
/** @type {string[]} */
const problems = []
let pages = 0
let markerCount = 0

/** The lines of a document, with CRLF normalised — the shape `findMarkers` and `fenceRanges` take. */
const linesOf = (path) => readFileSync(path, 'utf8').replace(/\r\n/g, '\n').split('\n')

/** `import-conflict-resolver` -> `ImportConflictResolver`, the class the folder was named after. */
const pascalCase = (folder) =>
  folder.split('-').map((part) => (part === '' ? part : part[0].toUpperCase() + part.slice(1))).join('')

/** A table cell that is one code span, without its backticks. */
const unquoted = (cell) => (cell.startsWith('`') && cell.endsWith('`') ? cell.slice(1, -1) : cell)

/**
 * The conflict type the index states per resolver folder, read by the column the header names — so reordering the
 * table is not a silent way to make this check read the wrong cell.
 *
 * @returns {{types: Map<string, string>, problems: string[]}}
 */
function conflictTypes(indexText) {
  const types = new Map()
  const failures = []
  let typeColumn = -1

  for (const line of indexText.split('\n')) {
    if (!line.trim().startsWith('|')) {
      continue
    }
    const cells = line.split('|').map((cell) => cell.trim())
    if (typeColumn < 0) {
      if (!cells.includes('Conflict type')) {
        continue
      }
      typeColumn = cells.indexOf('Conflict type')
      continue
    }
    const doc = cells.find((cell) => cell.includes('/README.md)'))
    if (!doc) {
      continue
    }
    const open = doc.indexOf('](')
    const close = doc.indexOf('/README.md)', open)
    if (open < 0 || close < 0) {
      continue
    }
    const folder = doc.slice(open + 2, close)
    const type = unquoted(cells[typeColumn] ?? '')
    if (type === '') {
      failures.push(`docs/resolvers/README.md: the row linking ${folder}/README.md states no conflict type in `
        + 'the "Conflict type" column')
      continue
    }
    types.set(folder, type)
  }

  if (typeColumn < 0) {
    failures.push('docs/resolvers/README.md: the table has no "Conflict type" column, so the canonical sample '
      + 'region of a resolver page cannot be checked')
  }
  return { types, problems: failures }
}

/** The region names `ConflictFixtures.java` declares, read by the reference's own directive reader. */
function fixtureRegionNames() {
  const names = new Set()
  for (const line of linesOf(FIXTURES)) {
    const directive = regionDirective(line)
    if (directive?.kind === 'region') {
      names.add(directive.name)
    }
  }
  return names
}

/** A marker's target as an absolute path, or null when it is not a regular file. */
function markerFile(docPath, marker) {
  const absolute = resolve(dirname(docPath), marker.path)
  return existsSync(absolute) && statSync(absolute).isFile() ? absolute : null
}

/** True when `absolute` is inside the module's test tree. */
function underTestRoot(absolute) {
  const rel = relative(TEST_ROOT, absolute)
  return rel !== '' && !rel.startsWith('..') && !isAbsolute(rel)
}

/**
 * Checks one page: rule 1 always, rules 2–5 when `expected` names the resolver the page documents.
 *
 * @param {string} docPath
 * @param {string} label the path as it is reported
 * @param {{folder: string, type: string}|null} expected
 * @returns {number} the number of markers the page carries
 */
function checkPage(docPath, label, expected, fixtureRegions) {
  const lines = linesOf(docPath)
  const markers = findMarkers(lines)
  markerCount += markers.length

  for (const marker of markers) {
    const where = `${label}:${marker.index + 1}`
    const file = markerFile(docPath, marker)
    if (file === null) {
      problems.push(`${where}: included file does not exist relative to the page: ${marker.path}`)
      continue
    }
    if (!underTestRoot(file)) {
      problems.push(`${where}: examples must come from test fixtures or tests under src/test/, not from `
        + relative(ROOT, file).split(sep).join('/'))
    }
  }

  if (expected === null) {
    return markers.length
  }

  if (markers.length < 4) {
    problems.push(`${label}: includes only ${markers.length} example block(s); a resolver page must show at `
      + 'least four')
  }

  const sample = `${expected.type.toLowerCase().split('_').join('-')}-sample`
  if (!fixtureRegions.has(sample)) {
    problems.push(`${label}: the index states ${expected.type} for this folder, and ConflictFixtures.java has no `
      + `"#region ${sample}" to show for it`)
  } else if (!markers.some((marker) => marker.path.endsWith('ConflictFixtures.java') && marker.reference === sample)) {
    problems.push(`${label}: does not include the canonical sample region ConflictFixtures.java#${sample}`)
  }

  const ownTest = `${pascalCase(expected.folder)}Test.java`
  if (!markers.some((marker) => marker.path.endsWith(ownTest))) {
    problems.push(`${label}: includes no example from ${ownTest}`)
  }

  for (const [openIndex] of fenceRanges(lines)) {
    let above = openIndex - 1
    while (above >= 0 && lines[above].trim() === '') {
      above--
    }
    if (above < 0 || parseMarker(lines[above]) === null) {
      problems.push(`${label}:${openIndex + 1}: fenced block is not preceded by an injection marker - examples `
        + 'must be included from test fixtures, never written by hand')
    }
  }

  return markers.length
}

// ------------------------------------------------------------------- the report

/** Runs one page and prints its `ok` line when it added no problem. */
function reportPage(docPath, label, expected, fixtureRegions) {
  const before = problems.length
  const markers = checkPage(docPath, label, expected, fixtureRegions)
  pages++
  if (problems.length === before) {
    console.log(`ok   ${label} (${markers} marker(s))`)
  }
}

const fixtureRegions = fixtureRegionNames()
const { types, problems: indexProblems } = conflictTypes(readFileSync(INDEX, 'utf8'))
problems.push(...indexProblems)

reportPage(INDEX, 'docs/resolvers/README.md', null, fixtureRegions)

const folders = readdirSync(DOCS, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .sort()

for (const folder of folders) {
  const docPath = join(DOCS, folder, 'README.md')
  const label = `docs/resolvers/${folder}/README.md`
  if (!existsSync(docPath)) {
    problems.push(`${label} does not exist`)
    continue
  }
  const type = types.get(folder)
  if (type === undefined) {
    // The registry check (`ResolverDocsTest`) owns "this folder has a resolver"; here a missing row only means
    // the canonical sample region cannot be named, so the page is still held to rule 1 and to rules 2 and 5.
    problems.push(`docs/resolvers/README.md has no row stating the conflict type of ${label}`)
    reportPage(docPath, label, null, fixtureRegions)
    continue
  }
  reportPage(docPath, label, { folder, type }, fixtureRegions)
}

if (problems.length > 0) {
  for (const problem of problems) {
    console.error(`FAIL ${problem}`)
  }
}
console.log(`resolver docs: ${pages} page(s), ${markerCount} marker(s), ${problems.length} problem(s)`)
console.log(reportDuration(elapsed(), {
  what: 'resolver docs checked',
  files: pages,
  detail: problems.length > 0 ? '- fix the FAIL lines above' : '- every page follows the policy',
}))
process.exit(problems.length > 0 ? 1 : 0)
