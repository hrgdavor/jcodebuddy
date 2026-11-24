#!/usr/bin/env bun
/**
 * Dependency-cycle report over JCodeBuddy class-index metadata.
 *
 * <p>The model is the same one the entity browser uses: one or more
 * `.jcodebuddy/index/classes.json` files plus their `imports.json` sidecars. This tool builds a
 * class-level graph, runs Tarjan's strongly-connected-components algorithm and writes one
 * self-contained `cycles.html` that can be opened from a project's `.jcodebuddy/app/` directory.
 * It does not parse Java; unresolved names remain external and are not invented as edges.</p>
 *
 * <p>Usage:</p>
 *
 *     bun project-automation/cycles/build.js \
 *       --index core/.jcodebuddy/index/classes.json \
 *       --index chat-app/.jcodebuddy/index/classes.json \
 *       --link-base . --out .jcodebuddy/app/cycles.html
 */
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { basename, dirname, isAbsolute, relative, resolve } from 'node:path'
import { reportDuration, stopwatch } from '../../scripts/lib/timing.js'
import { esbDef, jsx6Root, libraryAliases } from './src_build/esbDef.js'

const elapsed = stopwatch()
const PACKAGE_ROOT = dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))
const REPO_ROOT = resolve(PACKAGE_ROOT, '..', '..')
const HELP = `dependency-cycles report

Usage: bun project-automation/cycles/build.js --index <classes.json> [--index ...] [options]

Options:
  --index <file>       a .jcodebuddy/index/classes.json file (repeatable, required)
  --link-base <dir>    project root the page is served from (default: cwd)
  --out <file>         output HTML file (default: <link-base>/.jcodebuddy/app/cycles.html)
  --title <text>       page heading (default: Dependency cycles)
  --quiet              print only the output path
  --help               print this text
`

function options(argv) {
  const opts = { indexes: [], linkBase: process.cwd(), out: null, title: 'Dependency cycles', quiet: false }
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index]
    if (arg === '--index') opts.indexes.push(resolve(process.cwd(), required(argv, ++index, arg)))
    else if (arg === '--link-base') opts.linkBase = resolve(process.cwd(), required(argv, ++index, arg))
    else if (arg === '--out') opts.out = resolve(process.cwd(), required(argv, ++index, arg))
    else if (arg === '--title') opts.title = required(argv, ++index, arg)
    else if (arg === '--quiet') opts.quiet = true
    else if (arg === '--help') {
      process.stdout.write(HELP)
      process.exit(0)
    } else throw new Error(`unknown argument: ${arg}\n${HELP}`)
  }
  if (opts.indexes.length === 0) throw new Error(HELP)
  opts.out = opts.out ?? resolve(opts.linkBase, '.jcodebuddy', 'app', 'cycles.html')
  return opts
}

function required(argv, index, flag) {
  const value = argv[index]
  if (!value || value.startsWith('--')) throw new Error(`${flag} needs a value`)
  return value
}

function esbuild() {
  const executable = process.platform === 'win32' ? 'esbuild.exe' : 'esbuild'
  const candidates = [
    process.env.ESBUILD,
    resolve(REPO_ROOT, 'project-automation', 'entity-browser', 'node_modules', '.bin', executable),
  ].filter(Boolean)
  for (const candidate of candidates) if (existsSync(candidate)) return candidate
  return null
}

function main() {
  const opts = options(process.argv.slice(2))
  const outDir = dirname(opts.out)
  const baseName = basename(opts.out, '.html')
  mkdirSync(outDir, { recursive: true })
  const indexUrls = opts.indexes.map((index) => {
    if (!existsSync(index)) throw new Error(`missing class index: ${index}`)
    return relative(outDir, index).split('\\').join('/')
  })

  const jsx6 = jsx6Root(REPO_ROOT)
  const aliases = libraryAliases(jsx6)
  const bundle = resolve(outDir, `${baseName}.js`)
  const executable = esbuild()
  const command = executable ? executable : 'bunx'
  const args = executable ? [] : ['esbuild']
  args.push(
    resolve(PACKAGE_ROOT, 'src', 'index.jsx'),
    `--outfile=${bundle}`,
    `--format=${esbDef.format}`,
    `--jsx=${esbDef.jsx}`,
    `--jsx-import-source=${esbDef.jsxImportSource}`,
    `--tsconfig=${resolve(PACKAGE_ROOT, esbDef.tsconfig)}`,
    '--sourcemap',
    ...Object.entries(esbDef.loader).map(([extension, loader]) => `--loader:${extension}=${loader}`),
    ...Object.entries(aliases).map(([specifier, entry]) => `--alias:${specifier}=${entry}`),
    ...(esbDef.bundle ? ['--bundle'] : []),
  )
  const result = Bun.spawnSync([command, ...args], { cwd: PACKAGE_ROOT, stdio: ['inherit', 'inherit', 'inherit'] })
  if (result.exitCode !== 0) throw new Error(`esbuild failed with exit code ${result.exitCode}`)

  const shell = readFileSync(resolve(PACKAGE_ROOT, 'index.html'), 'utf8')
    .replace('__BUNDLE__', `./${baseName}.js`)
    .replace('__INDEXES__', JSON.stringify(indexUrls))
    .replace('__TITLE__', JSON.stringify(opts.title))
  writeFileSync(opts.out, shell, 'utf8')
  if (!opts.quiet) {
    console.log(`dependency cycles: indexes=${indexUrls.length}`)
    console.log(`jsx6: ${jsx6} (${Object.keys(aliases).length} libraries)`)
    console.log(`page: ${opts.out}`)
    console.log(`bundle: ${bundle} (${Math.round(statSync(bundle).size / 1024)} KiB)`)
    console.log(reportDuration(elapsed(), { what: 'dependency-cycle page' }))
  }
}

main()
