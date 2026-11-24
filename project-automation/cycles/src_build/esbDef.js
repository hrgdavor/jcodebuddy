import { existsSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'

/** The same jsx6/esbuild definition the other project-automation pages use. */
export const esbDef = {
  tsconfig: 'tsconfig-custom.json',
  jsx: 'automatic',
  jsxImportSource: '@jsx6',
  format: 'esm',
  loader: { '.js': 'tsx', '.jsx': 'tsx' },
  bundle: true,
  minify: false,
  sourcemap: true,
}

export function jsx6Root(repoRoot) {
  const configured = process.env.JCODEBUDDY_JSX6_DIR
  const root = configured ? resolve(configured) : join(repoRoot, '.jsx6')
  if (!existsSync(join(root, 'libs'))) {
    throw new Error(`no jsx6 checkout at ${root}; clone it or set JCODEBUDDY_JSX6_DIR`)
  }
  return root
}

export function libraryAliases(root) {
  const aliases = {}
  for (const name of readdirSync(join(root, 'libs'))) {
    const entry = join(root, 'libs', name, 'index.js')
    if (existsSync(entry)) aliases[`@jsx6/${name}`] = entry
  }
  return aliases
}
