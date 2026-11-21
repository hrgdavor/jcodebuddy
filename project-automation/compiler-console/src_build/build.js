#!/usr/bin/env bun
/**
 * Builds the compiler console: the served page that talks to the incremental compiler through DEC-053's live
 * tool socket, proxied by a host at `/api/tools`.
 *
 * <p>The page is static — it has no model to resolve and no source line to verify, because the tool answers
 * everything at runtime over the socket. What the build produces is therefore one HTML shell and one bundle,
 * written into the project's derived `.jcodebuddy/app/` directory, which every host already serves at
 * `/app/` with the injected navigation bridge (`webview/doc/webview-app-contract.md`).</p>
 *
 * <p>Usage:</p>
 *
 *     bun project-automation/compiler-console/src_build/build.js --link-base .
 *     bun project-automation/compiler-console/src_build/build.js --link-base . --out /tmp/page
 *
 * <p>Then open `/app/incremental-build.html?token=<host token>` on the host that proxies the socket (the IDE
 * host's `customServer`, or `webviewd`). The token is required because every tool call may change state; the
 * page reads it from its own URL, which is the Eclipse host's documented mechanism.</p>
 */
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { reportDuration, stopwatch } from '../../../scripts/lib/timing.js'
import { esbDef, jsx6Root, libraryAliases } from './esbDef.js'

const VERSION = '1.0'
const PAGE_NAME = 'incremental-build'
const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))
const PACKAGE_ROOT = resolve(SCRIPT_DIR, '..')
const REPO_ROOT = resolve(PACKAGE_ROOT, '..', '..')

const HELP = `compiler-console ${VERSION} — the served page over the incremental-compiler tool socket

Usage: bun project-automation/compiler-console/src_build/build.js [options]

Options:
  --link-base <dir>  project root the page is served from (default: this repository)
  --out <dir>        directory for ${PAGE_NAME}.html and ${PAGE_NAME}.js
                     (default: <link-base>/.jcodebuddy/app)
  --quiet            print only the output path and the summary
  --help             print this text and stop
`

function options(argv) {
  const opts = { linkBase: REPO_ROOT, out: null, quiet: false }
  for (let index = 0; index < argv.length; index++) {
    switch (argv[index]) {
      case '--link-base':
        opts.linkBase = resolve(required(argv, ++index, '--link-base'))
        break
      case '--out':
        opts.out = resolve(required(argv, ++index, '--out'))
        break
      case '--quiet':
        opts.quiet = true
        break
      case '--help':
        process.stdout.write(HELP)
        process.exit(0)
        break
      default:
        throw new Error(`unknown argument: ${argv[index]}\n\n${HELP}`)
    }
  }
  opts.out = opts.out ?? join(opts.linkBase, '.jcodebuddy', 'app')
  return opts
}

function required(argv, index, flag) {
  const value = argv[index]
  if (!value || value.startsWith('--')) {
    throw new Error(`${flag} needs a value`)
  }
  return value
}

/**
 * esbuild is a dev dependency of this page; the entity-browser beside it already has one, so a checkout that
 * built that page can build this one without a second install. `bunx` is the last resort and is what a fresh
 * clone uses after `bun install`.
 */
function esbuild() {
  const executable = process.platform === 'win32' ? 'esbuild.exe' : 'esbuild'
  const candidates = [
    process.env.ESBUILD,
    join(PACKAGE_ROOT, 'node_modules', '.bin', executable),
    join(REPO_ROOT, 'project-automation', 'entity-browser', 'node_modules', '.bin', executable),
  ].filter(Boolean)
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      return [candidate]
    }
  }
  return ['bunx', 'esbuild']
}

function main() {
  const elapsed = stopwatch()
  const opts = options(process.argv.slice(2))
  const jsx6 = jsx6Root(REPO_ROOT)
  const aliases = libraryAliases(jsx6)
  mkdirSync(opts.out, { recursive: true })

  const bundle = join(opts.out, `${PAGE_NAME}.js`)
  const args = [
    join(PACKAGE_ROOT, 'src', 'index.jsx'),
    `--outfile=${bundle}`,
    `--format=${esbDef.format}`,
    `--jsx=${esbDef.jsx}`,
    `--jsx-import-source=${esbDef.jsxImportSource}`,
    `--tsconfig=${join(PACKAGE_ROOT, esbDef.tsconfig)}`,
    '--sourcemap',
    ...Object.entries(esbDef.loader).map(([extension, loader]) => `--loader:${extension}=${loader}`),
    ...Object.entries(aliases).map(([specifier, entry]) => `--alias:${specifier}=${entry}`),
    ...(esbDef.bundle ? ['--bundle'] : []),
  ]
  const result = Bun.spawnSync([...esbuild(), ...args], {
    cwd: PACKAGE_ROOT,
    stdio: ['inherit', 'inherit', 'inherit'],
  })
  if (result.exitCode !== 0) {
    throw new Error(`esbuild failed with exit code ${result.exitCode}`)
  }

  const shell = readFileSync(join(PACKAGE_ROOT, 'index.html'), 'utf8')
    .replace('__BUNDLE__', `./${PAGE_NAME}.js`)
  const page = join(opts.out, `${PAGE_NAME}.html`)
  writeFileSync(page, shell, 'utf8')

  if (!opts.quiet) {
    process.stdout.write(`jsx6: ${jsx6} (${Object.keys(aliases).length} libraries)\n`)
  }
  process.stdout.write(`page: ${page}\n`)
  process.stdout.write(`bundle: ${bundle} (${Math.round(statSync(bundle).size / 1024)} KiB)\n`)
  process.stdout.write(`open: /app/${PAGE_NAME}.html?token=<host token>\n`)
  process.stdout.write(reportDuration(elapsed(), { what: 'compiler console built' }) + '\n')
}

main()
