#!/usr/bin/env bun
/**
 * Builds the entity browser page from one or more `.jcodebuddy/index/classes.json` class indexes.
 *
 * <p>The class index is the model (DEC-029): it carries each type's kind, modifiers, relations,
 * members, record components and enum constants, and the `imports.json` sidecar beside it carries
 * the declaring file's imports. This script resolves those facts into `model.json`, verifies every
 * source line it will link to, bundles the jsx6 page with Bun's esbuild, and writes a **page
 * directory** — `index.html`, `browser.js`, `model.json` — that is served over HTTP by the JetBrains
 * WebView Explorer or by `serve.js`. The page fetches `./model.json`; it is not a `file://` document.</p>
 *
 * <p>Usage (from the repository root, or with paths that exist from the current directory):</p>
 *
 *     bun project-automation/entity-browser/src_build/build.js --module hipster-entity/hipster-entity-example
 *     bun project-automation/entity-browser/src_build/build.js --index proto/business-logic/.jcodebuddy/index/classes.json
 *     bun project-automation/entity-browser/src_build/build.js --index a/.../classes.json --index b/.../classes.json \
 *         --link-base . --out .jcodebuddy/metadata/browser
 *
 * <p>Defaults: the example module, its module root as the link base, and
 * `<link base>/.jcodebuddy/metadata/browser` as the page directory. That is DEC-026's derived,
 * ignored metadata subtree — a served page is a report, not build tool output — so `mvn clean` does
 * not delete it and no `target/` rule is fought.</p>
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildBrowserModel, formatDiagnostic } from '../src/model.js'
import { verifySourceLinks } from '../src/verify.js'
import { publishReport } from '../../metadata/publish.js'
import { stopwatch, reportDuration } from './timing.js'
import { esbDef, jsx6Root, libraryAliases } from './esbDef.js'

const VERSION = '1.0'
const DEFAULT_MODULE = 'hipster-entity/hipster-entity-example'
const DEFAULT_BRIDGE_PORT = 18881
const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))
const PACKAGE_ROOT = resolve(SCRIPT_DIR, '..')
const REPO_ROOT = resolve(PACKAGE_ROOT, '..', '..')

const HELP = `entity-browser ${VERSION} — a searchable record/entity page from the class index

Usage: bun project-automation/entity-browser/src_build/build.js [options]

Options:
  --module <dir>        module whose .jcodebuddy/index/classes.json to read (repeatable;
                        default: ${DEFAULT_MODULE})
  --index <file>        a class index file to read (repeatable; alternative to --module)
  --link-base <dir>     directory that data-open paths are relative to — normally the project
                        root the IDE has open (default: the index's module root, or the common
                        ancestor of several module roots)
  --out <dir>           page directory for index.html, browser.js, manifest/index/blobs,
                        assets/jsx6 and serve.js (default: <link-base>/.jcodebuddy/metadata/browser)
  --config <file>       project build configuration (default: build.config.json beside this tool)
  --title <text>        page title (default: "<module> — entity browser")
  --bridge-port <n>     WebView Explorer HTTP bridge port for the fallback, 0 to disable
                        (default: ${DEFAULT_BRIDGE_PORT})
  --soft                exit 0 even when a source link cannot be verified
  --quiet               print only the output path and the summary
  --version             print the builder identity and stop
  --help                print this text

The model comes from JSON metadata only. The page parses no Java: a type name the index and its
imports cannot resolve stays external text, and a source line whose text does not contain the
member is dropped from the page and reported in DEC-022's format.`;

function identity() {
  const runtime = typeof Bun !== 'undefined' ? `bun ${Bun.version}` : `node ${process.version}`
  return `entity-browser ${VERSION} (${runtime}) from `
    + `${relative(REPO_ROOT, fileURLToPath(import.meta.url)).split(sep).join('/')}`
}

function usageError(message) {
  console.error(`[browser] ${message}`)
  console.error(HELP)
  return 2
}

/** Resolves a CLI path against the current directory first, then the repository root. */
function resolveInputPath(value) {
  const fromCwd = resolve(process.cwd(), value)
  if (existsSync(fromCwd)) {
    return fromCwd
  }
  return resolve(REPO_ROOT, value)
}

/** The checked-in customization point: which modules this project's browser covers, and its defaults. */
const CONFIG_FILE = join(PACKAGE_ROOT, 'build.config.json')

const CONFIG_DEFAULTS = Object.freeze({
  modules: [],
  indexes: [],
  linkBase: null,
  out: null,
  title: null,
  bridgePort: DEFAULT_BRIDGE_PORT,
})

function configPathFrom(argv) {
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--config') {
      return index + 1 < argv.length ? argv[index + 1] : null
    }
    if (argv[index].startsWith('--config=')) {
      return argv[index].slice('--config='.length)
    }
  }
  return existsSync(CONFIG_FILE) ? CONFIG_FILE : null
}

/** Reads the project's build configuration; CLI flags still override it. */
function readConfig(file) {
  if (!file) {
    return { ...CONFIG_DEFAULTS }
  }
  const resolved = isAbsolute(file) ? file : resolveInputPath(file)
  if (!existsSync(resolved)) {
    throw new Error(`no build config at ${resolved}`)
  }
  let raw
  try {
    raw = JSON.parse(readFileSync(resolved, 'utf8'))
  } catch (error) {
    throw new Error(`the build config at ${resolved} is not valid JSON (${error?.message ?? error})`)
  }
  return {
    modules: Array.isArray(raw.modules) ? raw.modules : [],
    indexes: Array.isArray(raw.indexes) ? raw.indexes : [],
    linkBase: raw.linkBase ?? null,
    out: raw.out ?? null,
    title: raw.title ?? null,
    bridgePort: Number.isFinite(raw.bridgePort) ? raw.bridgePort : DEFAULT_BRIDGE_PORT,
  }
}

function parseArgs(argv, config) {
  const options = {
    ...config,
    modules: [...config.modules],
    indexes: [...config.indexes],
    soft: false,
    quiet: false,
  }
  const value = (index, flag) => {
    if (index + 1 >= argv.length) {
      throw new Error(`${flag} needs a value`)
    }
    return argv[index + 1]
  }
  // An explicit --module/--index set replaces the configured source set; otherwise the config's
  // modules would silently be added to a one-off build.
  let cliSources = false
  const source = () => {
    if (!cliSources) {
      options.modules = []
      options.indexes = []
      cliSources = true
    }
  }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--help' || arg === '-h') {
      options.help = true
    } else if (arg === '--version') {
      options.version = true
    } else if (arg === '--soft') {
      options.soft = true
    } else if (arg === '--quiet') {
      options.quiet = true
    } else if (arg === '--module') {
      source()
      options.modules.push(value(i, arg))
      i += 1
    } else if (arg.startsWith('--module=')) {
      source()
      options.modules.push(arg.slice('--module='.length))
    } else if (arg === '--index') {
      source()
      options.indexes.push(value(i, arg))
      i += 1
    } else if (arg.startsWith('--index=')) {
      source()
      options.indexes.push(arg.slice('--index='.length))
    } else if (arg === '--link-base') {
      options.linkBase = value(i, arg)
      i += 1
    } else if (arg.startsWith('--link-base=')) {
      options.linkBase = arg.slice('--link-base='.length)
    } else if (arg === '--out') {
      options.out = value(i, arg)
      i += 1
    } else if (arg.startsWith('--out=')) {
      options.out = arg.slice('--out='.length)
    } else if (arg === '--title') {
      options.title = value(i, arg)
      i += 1
    } else if (arg.startsWith('--title=')) {
      options.title = arg.slice('--title='.length)
    } else if (arg === '--bridge-port') {
      options.bridgePort = Number(value(i, arg))
      i += 1
    } else if (arg.startsWith('--bridge-port=')) {
      options.bridgePort = Number(arg.slice('--bridge-port='.length))
    } else if (arg === '--config') {
      value(i, arg)
      i += 1
    } else if (arg.startsWith('--config=')) {
      // read before parse; nothing to do here
    } else {
      throw new Error(`unknown argument: ${arg}`)
    }
  }
  return options
}

/** The module root above an index file: the parent of the nearest `.jcodebuddy` ancestor. */
function moduleRootOf(indexFile) {
  let directory = dirname(indexFile)
  while (directory !== dirname(directory)) {
    if (basename(directory) === '.jcodebuddy') {
      return dirname(directory)
    }
    directory = dirname(directory)
  }
  return dirname(indexFile)
}

/** The common ancestor of several module roots, used when no --link-base is given. */
function commonAncestor(paths) {
  if (paths.length === 1) {
    return paths[0]
  }
  const split = (path) => resolve(path).split(sep).filter((part) => part !== '')
  let common = split(paths[0])
  for (const path of paths.slice(1)) {
    const parts = split(path)
    let index = 0
    while (index < common.length && index < parts.length
      && common[index].toLowerCase() === parts[index].toLowerCase()) {
      index += 1
    }
    common = common.slice(0, index)
  }
  const joined = common.join(sep)
  if (!joined) {
    return sep
  }
  // Windows: `C:` alone is drive-relative, not the drive root. `C:\` is the root the walk means.
  if (sep === '\\' && /^[A-Za-z]:$/.test(joined)) {
    return `${joined}${sep}`
  }
  return joined
}

/** The path from `from` to `to` with forward slashes, for display; absolute when no relative form exists. */
function displayPath(from, to) {
  try {
    return relativePath(from, to)
  } catch {
    return to.split(sep).join('/')
  }
}

/** A module-relative path with forward slashes, never absolute. */
function relativePath(from, to) {
  const rel = relative(from, to)
  if (isAbsolute(rel)) {
    throw new Error(`cannot express ${to} relative to ${from} — choose a --link-base on the same drive`)
  }
  return rel.split(sep).join('/')
}

/** The path from the output file's directory to the link base, as the page resolves it against `location`. */
function linkBaseFromOut(out, linkBase) {
  const rel = relativePath(dirname(out), linkBase)
  if (rel === '') {
    return './'
  }
  return `${rel}/`
}

function readIndexes(options) {
  const files = []
  for (const modulePath of options.modules) {
    const moduleDir = resolveInputPath(modulePath)
    const indexFile = join(moduleDir, '.jcodebuddy', 'index', 'classes.json')
    if (!existsSync(indexFile)) {
      throw new Error(`no class index at ${indexFile} — run the module's generator pass first`)
    }
    files.push(indexFile)
  }
  for (const indexPath of options.indexes) {
    const indexFile = resolveInputPath(indexPath)
    if (!existsSync(indexFile) || !statSync(indexFile).isFile()) {
      throw new Error(`no class index file at ${indexFile}`)
    }
    files.push(indexFile)
  }
  if (files.length === 0) {
    const defaultIndex = join(resolve(REPO_ROOT, DEFAULT_MODULE), '.jcodebuddy', 'index', 'classes.json')
    if (!existsSync(defaultIndex)) {
      throw new Error(`no class index at ${defaultIndex} — run \`bun scripts/gen.js\` first, or pass --index`)
    }
    files.push(defaultIndex)
  }

  const seen = new Set()
  const indexes = []
  for (const file of files) {
    const key = resolve(file).toLowerCase()
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    let data
    try {
      data = JSON.parse(readFileSync(file, 'utf8'))
    } catch (error) {
      throw new Error(`the class index at ${file} is not valid JSON (${error?.message ?? error})`)
    }
    if (!data || data.format !== 1) {
      throw new Error(`the class index at ${file} has format ${JSON.stringify(data?.format)}; this browser understands only 1`)
    }
    const importsFile = join(dirname(file), 'imports.json')
    let imports = {}
    if (existsSync(importsFile)) {
      try {
        imports = JSON.parse(readFileSync(importsFile, 'utf8')).imports ?? {}
      } catch (error) {
        throw new Error(`the imports sidecar at ${importsFile} is not valid JSON (${error?.message ?? error})`)
      }
    }
    const root = moduleRootOf(file)
    const classes = []
    for (const [fqn, row] of Object.entries(data.classes ?? {})) {
      if (!row || typeof row.path !== 'string') {
        continue
      }
      classes.push({
        fqn,
        row,
        path: relativePath(root, join(root, row.path)),
        imports: imports[row.path],
      })
    }
    indexes.push({
      id: displayPath(REPO_ROOT, file),
      file,
      root,
      data,
      classes,
      module: data.module || basename(root),
      sourceRoot: data.sourceRoot ?? '',
      directory: displayPath(REPO_ROOT, dirname(file)),
    })
  }
  return indexes
}

function writeModel(outDir, model) {
  mkdirSync(outDir, { recursive: true })
  const file = join(outDir, 'model.json')
  writeFileSync(file, `${JSON.stringify(model, null, 2)}\n`, 'utf8')
  return file
}

export function buildPage(indexes, options, linkBase, outPage) {
  const prepared = indexes.map((index) => ({
    id: relativePath(linkBase, index.file),
    module: index.module,
    sourceRoot: index.sourceRoot,
    directory: relativePath(linkBase, dirname(index.file)),
    root: index.root,
    data: index.data,
    // `readIndexes` records each row's module-relative path so the imports sidecar can be keyed. The
    // page needs one namespace for every module, so the path is recomputed against the link base here;
    // a module-relative path would lose its `module/` prefix as soon as a second module shares the page.
    classes: index.classes.map((entry) => ({
      ...entry,
      path: relativePath(linkBase, join(index.root, entry.row.path)),
    })),
  }))
  const model = buildBrowserModel({
    indexes: prepared,
    title: options.title ?? (indexes.length === 1
      ? `${indexes[0].module} — entity browser`
      : 'entity browser'),
    bridgePort: options.bridgePort,
    generation: {
      tool: 'entity-browser',
      version: VERSION,
      indexes: indexes.map((index) => index.id),
    },
  })
  // The page resolves the link base against its own location, so the value is the path from the
  // output's directory to the link base — one value, never a secret second depth.
  model.linkBase = linkBaseFromOut(outPage, linkBase)
  model.reportPath = relativePath(linkBase, outPage)
  const verification = verifySourceLinks(model, prepared)
  return { model, verification }
}

function runEsbuild(outDir) {
  const root = jsx6Root(REPO_ROOT)
  const aliases = libraryAliases(root)
  const bundle = join(outDir, 'browser.js')
  const args = [
    'esbuild',
    join(PACKAGE_ROOT, 'src', 'index.jsx'),
    `--outfile=${bundle}`,
    '--bundle',
    `--format=${esbDef.format}`,
    `--jsx=${esbDef.jsx}`,
    `--jsx-import-source=${esbDef.jsxImportSource}`,
    `--tsconfig=${join(PACKAGE_ROOT, esbDef.tsconfig)}`,
    '--sourcemap',
    '--external:@jsx6/jsx6',
    '--external:@jsx6/jsx-runtime',
    ...Object.entries(esbDef.loader).map(([extension, loader]) => `--loader:${extension}=${loader}`),
  ]
  const result = Bun.spawnSync(['bunx', ...args], {
    cwd: PACKAGE_ROOT,
    stdio: ['inherit', 'inherit', 'inherit'],
  })
  if (result.exitCode !== 0) {
    throw new Error(`esbuild failed with exit code ${result.exitCode}`)
  }
  return { bundle, jsx6: root, aliasCount: Object.keys(aliases).length }
}

/** A content hash of the jsx6 checkout's library sources, used as the shared asset directory key. */
function jsx6Hash(root) {
  const hash = createHash('sha256')
  const visit = (directory) => {
    for (const name of readdirSync(directory).sort()) {
      const file = join(directory, name)
      const stat = statSync(file)
      if (stat.isDirectory()) {
        visit(file)
      } else {
        hash.update(relative(root, file).split(sep).join('/'))
        hash.update('\0')
        hash.update(readFileSync(file))
        hash.update('\0')
      }
    }
  }
  visit(join(root, 'libs'))
  return hash.digest('hex').slice(0, 16)
}

/**
 * Build the jsx6 libraries once under the serving root. The report bundle references these files
 * through an import map, so changing only page code does not rebuild jsx6, and a second report can
 * reuse the same content-addressed directory.
 */
function buildSharedJsx6(servingRoot, root) {
  const hash = jsx6Hash(root)
  const relativeDir = `lib/jsx6/${hash}`
  const assetDir = join(servingRoot, relativeDir)
  const entries = {
    '@jsx6/jsx6': join(root, 'libs', 'jsx6', 'index.js'),
    '@jsx6/jsx-runtime': join(root, 'libs', 'jsx-runtime', 'index.js'),
  }
  if (existsSync(join(assetDir, 'jsx6.js'))) {
    return { hash, relativeDir, assetDir, built: false, count: Object.keys(entries).length }
  }
  mkdirSync(assetDir, { recursive: true })
  for (const [specifier, entry] of Object.entries(entries)) {
    const name = specifier.slice('@jsx6/'.length)
    const result = Bun.spawnSync([
      'bunx', 'esbuild', entry,
      `--outfile=${join(assetDir, `${name}.js`)}`,
      '--bundle',
      '--format=esm',
      '--sourcemap',
    ], { cwd: PACKAGE_ROOT, stdio: ['inherit', 'inherit', 'inherit'] })
    if (result.exitCode !== 0) {
      throw new Error(`jsx6 shared asset build failed for ${specifier} (exit ${result.exitCode})`)
    }
  }
  return { hash, relativeDir, assetDir, built: true, count: Object.keys(entries).length }
}

function copyShell(outDir, jsx6Assets) {
  const shell = join(PACKAGE_ROOT, 'index.html')
  const target = join(outDir, 'index.html')
  mkdirSync(outDir, { recursive: true })
  // Reports are served at /reports/<id>/; shared libraries are at /lib/. That is two URL levels above
  // the page, which is one more than the filesystem relationship between the report dir and lib.
  const imports = {
    '@jsx6/jsx6': `../../${jsx6Assets.relativeDir}/jsx6.js`,
    '@jsx6/jsx-runtime': `../../${jsx6Assets.relativeDir}/jsx-runtime.js`,
  }
  const importMap = `<script type="importmap">${JSON.stringify({ imports })}</script>`
  const html = readFileSync(shell, 'utf8').replace('<head>', `<head>\n    ${importMap}`)
  writeFileSync(target, html, 'utf8')
  return target
}

/**
 * Puts everything needed to run the page into the output directory: the bundle (which already carries
 * jsx6), the model, the shell, the server, and a two-line note. `target/` is disposable, so a copy of
 * the page is self-runnable with Bun alone and needs no access to `project-automation/`.
 */
function copyRuntime(outDir) {
  const serverSource = join(PACKAGE_ROOT, 'src_build', 'serve.js')
  const server = join(outDir, 'serve.js')
  writeFileSync(server, readFileSync(serverSource, 'utf8'), 'utf8')
  const metadataServer = join(REPO_ROOT, 'project-automation', 'metadata', 'server.js')
  writeFileSync(join(outDir, 'metadata-server.js'), readFileSync(metadataServer, 'utf8'), 'utf8')
  writeFileSync(join(outDir, 'README.txt'),
    'Entity browser\n\n'
    + 'Standalone:  bun serve.js\n'
    + 'Open the printed http://127.0.0.1:<port>/ URL.\n'
    + 'In IntelliJ: right-click index.html in the Project view and choose "Open in WebView Explorer".\n'
    + 'Deep link:   append #<fully qualified type> to the served URL.\n', 'utf8')
  return server
}

export function main(argv = process.argv.slice(2)) {
  let options
  try {
    options = parseArgs(argv, readConfig(configPathFrom(argv)))
  } catch (error) {
    return usageError(error.message)
  }
  if (options.help) {
    console.log(HELP)
    return 0
  }
  if (options.version) {
    console.log(identity())
    return 0
  }
  if (!Number.isFinite(options.bridgePort) || options.bridgePort < 0) {
    return usageError(`--bridge-port must be a number >= 0`)
  }

  const elapsed = stopwatch()

  let indexes
  try {
    indexes = readIndexes(options)
  } catch (error) {
    console.error(`[browser] ${error.message}`)
    return 1
  }

  const linkBase = options.linkBase
    ? resolveInputPath(options.linkBase)
    : commonAncestor(indexes.map((index) => index.root))
  const requestedOut = options.out ? resolveInputPath(options.out) : null
  const outDir = requestedOut && requestedOut.toLowerCase().endsWith('.html')
    ? dirname(requestedOut)
    : requestedOut ?? join(linkBase, '.jcodebuddy', 'metadata', 'browser')
  const outPage = requestedOut && requestedOut.toLowerCase().endsWith('.html')
    ? requestedOut
    : join(outDir, 'index.html')

  let built
  try {
    built = buildPage(indexes, options, linkBase, outPage)
  } catch (error) {
    console.error(`[browser] ${error?.message ?? error}`)
    return 1
  }
  const { model, verification } = built

  try {
    mkdirSync(outDir, { recursive: true })
    const metadataRoot = dirname(outDir)
    const jsx6Assets = buildSharedJsx6(metadataRoot, jsx6Root(REPO_ROOT))
    const published = publishReport({
      metadataRoot,
      report: 'entity-browser',
      title: model.title,
      model,
      pageDir: outDir,
      reportDir: outDir,
      assets: [jsx6Assets.relativeDir],
    })
    const build = runEsbuild(outDir)
    const pageFile = copyShell(outDir, jsx6Assets)
    const serverFile = copyRuntime(outDir)
    if (!options.quiet) {
      console.log(identity())
      console.log(`Indexes: ${indexes.map((index) => displayPath(linkBase, index.file)).join(', ')}`)
      console.log(`Link base: ${displayPath(REPO_ROOT, linkBase)}   page: ${displayPath(REPO_ROOT, pageFile)}`)
      console.log(`Metadata index: ${displayPath(REPO_ROOT, join(published.reportDir, 'index.json'))}`
        + `   bundle: ${displayPath(REPO_ROOT, build.bundle)}`)
      console.log(`Server: ${displayPath(REPO_ROOT, serverFile)}`)
      console.log(`jsx6 shared: ${jsx6Assets.relativeDir} (${jsx6Assets.built ? 'built' : 'reused'}, `
        + `${jsx6Assets.count} libs)`)
    }
    console.log(`HTML entity browser written to: ${pageFile}`)
    console.log(`Serve its directory: bun ${displayPath(REPO_ROOT, serverFile)}`)
    console.log(`Types: ${model.stats.types}, records: ${model.stats.records}, enums: ${model.stats.enums}, `
      + `components: ${model.stats.components}, inline enum values on ${model.stats.inlineEnums} component(s)`)
    console.log(`Source links: ${verification.verified} verified, ${verification.stale} stale (dropped), `
      + `${verification.missing} file(s) missing`)
    const errors = model.diagnostics.filter((item) => item.severity !== 'warning')
    const warnings = model.diagnostics.filter((item) => item.severity === 'warning')
    console.log(`Divergences: ${errors.length} (${warnings.length} warning(s))`)
    if (!options.quiet) {
      for (const item of model.diagnostics) {
        console.log(`  ${formatDiagnostic(item)}`)
      }
      console.log('Open it in IntelliJ: right-click the HTML file in the Project view and choose '
        + '"Open in WebView Explorer" (Ctrl+Alt+Shift+W); the plugin serves the page over HTTP.')
      console.log('Deep link: append #<fully qualified type> to the served page URL.')
    }
    if (errors.length > 0 && !options.soft) {
      console.error('[browser] source verification failed; fix the reported divergence or pass --soft.')
    }
    // The last line a utility prints is its own duration (AGENTS.md § 2, scripts/lib/timing.js).
    console.log(reportDuration(elapsed(), {
      what: 'entity browser built',
      detail: `· ${model.stats.types} types · ${model.stats.records} records`,
    }))
    return errors.length > 0 && !options.soft ? 1 : 0
  } catch (error) {
    console.error(`[browser] ${error?.message ?? error}`)
    return 1
  }
}

if (import.meta.main) {
  process.exit(main())
}
