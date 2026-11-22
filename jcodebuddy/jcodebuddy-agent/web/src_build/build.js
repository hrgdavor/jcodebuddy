#!/usr/bin/env bun
// Builds the jsx6 agent dashboard into the Java module's resources. The same files are copied by
// AgentPageInstaller into <project>/.jcodebuddy/app for the webview host.
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { esbDef } from './esbDef.js'

const here = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(here, '..')
const repoRoot = resolve(packageRoot, '..', '..', '..')
const resources = resolve(packageRoot, '..', 'src', 'main', 'resources', 'web')
const jsx6 = resolve(process.env.JCODEBUDDY_JSX6_DIR || join(repoRoot, '.jsx6'))

if (!existsSync(jsx6)) {
  throw new Error(`no jsx6 checkout at ${jsx6}; clone https://github.com/hrgdavor/jsx6 or set JCODEBUDDY_JSX6_DIR`)
}

mkdirSync(resources, { recursive: true })
const aliases = Object.fromEntries(
  readdirSync(join(jsx6, 'libs'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => [`@jsx6/${entry.name}`, join(jsx6, 'libs', entry.name, 'index.js')])
    .filter(([, entry]) => existsSync(entry)),
)

function build(entry, output) {
  const args = [
    'esbuild',
    join(packageRoot, 'src', entry),
    `--outfile=${join(resources, output)}`,
    '--bundle',
    `--format=${esbDef.format}`,
    `--jsx=${esbDef.jsx}`,
    `--jsx-import-source=${esbDef.jsxImportSource}`,
    `--tsconfig=${join(packageRoot, esbDef.tsconfig)}`,
    '--sourcemap',
    ...Object.entries(esbDef.loader).map(([ext, loader]) => `--loader:${ext}=${loader}`),
    ...Object.entries(aliases).map(([name, entryPath]) => `--alias:${name}=${entryPath}`),
  ]
  const result = Bun.spawnSync(['bunx', ...args], { cwd: packageRoot, stdio: ['inherit', 'inherit', 'inherit'] })
  if (result.exitCode !== 0) throw new Error(`esbuild failed with ${result.exitCode} for ${entry}`)
}

build('index.jsx', 'jcodebuddy-agent.js')
build('builder.jsx', 'builder.js')
copyFileSync(join(packageRoot, 'index.html'), join(resources, 'index.html'))
copyFileSync(join(packageRoot, 'builder.html'), join(resources, 'builder.html'))
copyFileSync(join(packageRoot, 'style.css'), join(resources, 'jcodebuddy-agent.css'))
copyFileSync(join(repoRoot, 'markdown-view', 'assets', 'microlighter.js'),
  join(resources, 'microlighter.js'))
console.log(`agent dashboard: ${join(resources, 'index.html')}`)
console.log(`builder tool: ${join(resources, 'builder.html')}`)
console.log(`jsx6: ${jsx6}`)
