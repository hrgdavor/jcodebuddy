#!/usr/bin/env bun
/**
 * The dependency-graph page's build (plan step 3.8): bundle the host, inline the stylesheets and the
 * script, and write **one self-contained HTML file** next to the generator's `contexts.json`.
 *
 * <h3>Why the bundle is built from a staging directory inside the checkout</h3>
 * <p>`@jsx6/*` and `@jsx6/nodditor` resolve through the jsx6 workspace, so a bundle entry point has to
 * be built from *inside* the checkout — measured: the same import from this repository's tree fails
 * with `Could not resolve "@jsx6/nodditor"`, and from inside the checkout it resolves and bundles (90
 * modules). So the entry is copied into the checkout's `.tmp/` (ignored by both repositories), bundled
 * there with the automatic jsx6 runtime, and the result is inlined into the page. The checkout's
 * location is `JCODEBUDDY_JSX6_DIR`, defaulting to the one `AGENTS.md` § 2 names.</p>
 *
 * <h3>No network at view time</h3>
 * <p>The page inlines its CSS and its script, and reads `contexts.json` from beside itself. That is what
 * DEC-027 keeps for every page class: a `jsx6` page may be built, but it never reaches the network when
 * it is opened.</p>
 *
 * <h3>The join to source, and why the build is where it happens</h3>
 * <p>Since plan step 3.11a the page also navigates: each context, implementation and bean is joined to a
 * **project-relative** `{path, line}` from the module's class index (`.jcodebuddy/index/classes.json`,
 * DEC-029), and the join is inlined like everything else — a `file://` page may not fetch a sibling, so a
 * view-time join would close the standalone path. DEC-049 decides this; the index's own shape and the
 * reported-not-guessed rule are in `src/locations.js`.</p>
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

import { classRows, joinLocations } from './locations.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const PACKAGE = join(HERE, '..')
/** The repository root — exported because the join's own test asserts paths against the real tree. */
export const REPO = join(PACKAGE, '..', '..')
const JSX6 = process.env.JCODEBUDDY_JSX6_DIR ?? join(REPO, '.jsx6')

/** Where the generator's graph JSON and the page live (DEC-026: the module's derived subtree). */
export const OUTPUT_DIR = join(REPO, 'hipster-ioc', 'hipster-ioc-test', '.jcodebuddy', 'metadata', 'hipster-ioc')

/**
 * The module whose graph this page renders, and its class index — the other half of the join.
 *
 * <p>The module directory matters beyond locating the file: the index's rows are **module**-relative, while a
 * host resolves a link against the **project** root (`Navigator` confines every navigation to it), so the
 * module's own directory prefixes every joined path (DEC-049 decision 2, and `src/locations.js`).</p>
 */
export const MODULE_DIR = join(REPO, 'hipster-ioc', 'hipster-ioc-test')
export const INDEX_FILE = join(MODULE_DIR, '.jcodebuddy', 'index', 'classes.json')

/** The stylesheets nodditor's geometry needs; the package ships no CSS build but requires this one. */
const NODDITOR_CSS = join(JSX6, 'apps', 'nodditor', 'static', 'nodditor.css')

/**
 * The page's source files. They are **copied** into the staging directory and imported from there
 * rather than concatenated, because each one imports the others by name: concatenation would produce
 * one module with two copies of every import, which the JSX transform then sees as duplicate bindings.
 */
export const SOURCE_FILES = ['src/model.js', 'src/locations.js', 'src/navigate.js', 'src/blocks.js', 'src/host.jsx', 'src/main.js']

/** The bundle's staging home inside the checkout — ignored by both trees, and never `target/`. */
export function stagingDir() {
  return join(JSX6, '.tmp', 'hipster-ioc-graph')
}

/**
 * The bundle command, with the flags the jsx6 stack requires — and **`iife`, not `esm`**.
 *
 * That last flag is a measured consequence of DEC-027's standalone rule: `file://` refuses a
 * `<script type="module">` ("Access to script at 'file:///…' from origin 'null' has been blocked by
 * CORS policy"), so an ESM bundle leaves the page on its loading line for anyone who opens it by
 * double-clicking. A classic script has no such rule, and an inline classic script is not an external
 * file at all. `--jsx-runtime automatic` with `--jsx-import-source=@jsx6` is the stack's own rule 2:
 * the wrong runtime compiles fine and fails at run time.
 */
export function buildArgs(entry, outdir) {
  return ['build', entry, '--outdir', outdir, '--target', 'browser', '--format', 'iife',
    '--jsx-runtime', 'automatic', '--jsx-import-source=@jsx6']
}

function run(command, args, options = {}) {
  const result = Bun.spawnSync([command, ...args], { stdio: ['ignore', 'pipe', 'pipe'], ...options })
  return {
    status: result.exitCode,
    out: result.stdout ? new TextDecoder().decode(result.stdout) : '',
    err: result.stderr ? new TextDecoder().decode(result.stderr) : '',
  }
}

/**
 * Bundles the host and returns the JavaScript.
 *
 * <p>The join is already made by the caller and arrives here, so the graph the bundle inlines is the same one
 * `main` reports on — reading it twice would let the report describe a different pass than the page.</p>
 */
export function bundle({ contexts, joined } = {}) {
  if (!existsSync(JSX6)) {
    throw new Error(`no jsx6 checkout at ${JSX6} — clone https://github.com/hrgdavor/jsx6 there, `
      + 'or set JCODEBUDDY_JSX6_DIR (AGENTS.md § 2)')
  }
  const graph = contexts ?? readContexts()
  const placed = joined ?? readLocations(graph)
  const staging = stagingDir()
  rmSync(staging, { recursive: true, force: true })
  mkdirSync(staging, { recursive: true })
  // The sources are copied to a flat directory, so their relative imports (`./host.jsx`) resolve there
  // exactly as they do here, and the bare `@jsx6/*` specifiers resolve through the checkout.
  for (const name of SOURCE_FILES) {
    copyFileSync(join(PACKAGE, name), join(staging, name.split('/').pop()))
  }
  const entry = join(staging, 'entry.jsx')
  writeFileSync(entry, entrySourceOf(), 'utf8')
  writeFileSync(join(staging, 'contexts.js'),
    contextsModule(placed.contexts ?? graph, placed.locations ?? {}, placed.locationless ?? []), 'utf8')

  const result = run('bun', buildArgs(entry, staging), { cwd: JSX6 })
  if (result.status !== 0) {
    throw new Error(`the bundle failed:\n${result.out}\n${result.err}`)
  }
  return readFileSync(join(staging, 'entry.js'), 'utf8')
}

/**
 * The staging entry. It is one import and nothing else, because a bundler hoists imports: a statement
 * written above `import './main.js'` does **not** run first (measured — the page reported "not built
 * with a graph" while the assignment sat on line 1). The graph therefore arrives as a module of its
 * own, `./contexts.js`, which `main.js` imports before it uses it, so the order is the module system's
 * rather than a line's.
 */
export function entrySourceOf() {
  return "import './main.js'\n"
}

/**
 * The generator's graph as a module: `export default { contexts, locations, locationless }`.
 *
 * It is inlined rather than fetched because `file://` refuses a sibling fetch — also measured: Chrome
 * reports "Failed to fetch" and the page is a blank shell for anyone who opens it by double-clicking,
 * which DEC-027 forbids for a page a person opens to do one task. Bun still reads the JSON; it reads
 * it at build time, and `<` is escaped so the value cannot close the page's own script element.
 *
 * `locations` and `locationless` are the step-3.11a join: the FQN → project-relative `{path, line}` map the
 * page navigates through, and the names the index could not answer, which the page **reports** rather than
 * dropping (DEC-049 decisions 3–4).
 */
export function contextsModule(contexts, locations = {}, locationless = []) {
  const payload = { contexts: contexts ?? readContexts()?.contexts ?? [], locations, locationless }
  const json = JSON.stringify(payload).replaceAll('<', '\\u003c')
  return `// Written by src/build.js from the generator's contexts.json and .jcodebuddy/index/classes.json — do not edit.\nexport default ${json}\n`
}

/**
 * The class index's rows, and the module directory the join needs.
 *
 * <p>The project root is what makes a joined path **host-resolvable**: the index is module-relative and a
 * host resolves against the project (`Navigator`), so without it every link would be refused as "not a
 * usable path". It comes from `--project` (`JCODEBUDDY_PROJECT_DIR`), and a build that cannot say where the
 * project is **stops** rather than emitting paths aimed at the wrong root.</p>
 *
 * @param {{project?: string, indexPath?: string}} [options]
 * @throws when the index is missing, is a format this reader does not know, or the project root is not given
 */
export function readLocations(contexts, { project, indexPath = INDEX_FILE } = {}) {
  if (!project) {
    throw new Error('no project root — pass --project=<dir> (or set JCODEBUDDY_PROJECT_DIR): the class '
      + 'index is module-relative, and a joined path a host cannot resolve is worse than no join')
  }
  if (!existsSync(indexPath)) {
    throw new Error(`no class index at ${indexPath} — run the generator first (bun scripts/ioc-gen.js)`)
  }
  const rows = classRows(JSON.parse(readFileSync(indexPath, 'utf8')))
  const moduleDir = relative(resolve(project), resolve(MODULE_DIR)).split(sep).join('/')
  if (moduleDir.startsWith('..')) {
    throw new Error(`the module ${MODULE_DIR} is not inside the project ${resolve(project)}`)
  }
  const joined = joinLocations(contexts, rows, { moduleDir })
  return { ...joined, contexts, moduleDir }
}

/** The node CSS: nodditor's geometry plus this page's block look. */
export function styles() {
  const geometry = existsSync(NODDITOR_CSS) ? readFileSync(NODDITOR_CSS, 'utf8') : ''
  return `${geometry}\n${readFileSync(join(PACKAGE, 'src', 'page.css'), 'utf8')}`
}

/**
 * One self-contained document: no `<script src>`, no `<link>`, no fetch at all — the graph JSON is
 * inlined into the script, because `file://` refuses a sibling fetch and DEC-027 requires the page to
 * be usable standalone. Bun still reads the generator's JSON; it reads it **here**, at build time.
 */
export function html(script, css) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>hipster-ioc — context dependency graph</title>
<style>
${css}
</style>
</head>
<body>
<div id="graph-root" class="graph-root">loading…</div>
<script>
${script}
</script>
</body>
</html>
`
}

/** The generator's graph, read from the module's derived metadata subtree. */
export function readContexts() {
  const file = join(OUTPUT_DIR, 'contexts.json')
  if (!existsSync(file)) {
    throw new Error(`no graph at ${file} — run the generator first (bun scripts/ioc-gen.js)`)
  }
  return JSON.parse(readFileSync(file, 'utf8'))
}

function main() {
  const check = process.argv.includes('--check')
  const projectArg = process.argv.find((arg) => arg.startsWith('--project='))
  const project = projectArg ? projectArg.slice('--project='.length) : process.env.JCODEBUDDY_PROJECT_DIR
  const { script, joined } = build({ project })
  const page = html(script, styles())
  if (joined.locationless.length > 0) {
    // Reported, not dropped (DEC-049 decision 3): a name the index cannot answer is the page's own line to
    // say, and the build says it too, so a join that silently resolved nothing is visible here.
    console.log(`reported ${joined.locationless.length} name(s) with no location: ${joined.locationless.join(', ')}`)
  }
  if (check) {
    const existing = existsSync(join(OUTPUT_DIR, 'graph.html'))
      ? readFileSync(join(OUTPUT_DIR, 'graph.html'), 'utf8')
      : null
    if (existing !== page) {
      console.error('graph.html is not what this build would write — re-run without --check')
      process.exit(1)
    }
    console.log(`ok       graph.html is current (${page.length} bytes)`)
    return
  }
  mkdirSync(OUTPUT_DIR, { recursive: true })
  writeFileSync(join(OUTPUT_DIR, 'graph.html'), page, 'utf8')
  console.log(`written  ${join(OUTPUT_DIR, 'graph.html')} (${page.length} bytes, script ${script.length})`)
}

/** The bundle and the join it was built from, so `main` can report what it could not place. */
function build(options) {
  const contexts = readContexts()
  const joined = readLocations(contexts, options)
  return { script: bundle({ contexts, joined }), joined }
}

if (import.meta.main) {
  main()
}
