import { existsSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'

/**
 * The esbuild definition for the compiler console — the same shape jsx6's `setup.md` documents and
 * `project-automation/entity-browser/src_build/esbDef.js` uses (rule: copy the reference, do not invent).
 *
 * `tsconfig: 'tsconfig-custom.json'` keeps this near-empty config in front of esbuild so an editor's
 * `"jsx": "preserve"` cannot reach it and make esbuild emit raw JSX.
 */
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

/**
 * Where the jsx6 checkout is; `JCODEBUDDY_JSX6_DIR` overrides, `<repo>/.jsx6` is the default (AGENTS.md § 2:
 * a local checkout updated on demand, never a remembered version).
 */
export function jsx6Root(repoRoot) {
  const configured = process.env.JCODEBUDDY_JSX6_DIR
  const root = configured ? resolve(configured) : join(repoRoot, '.jsx6')
  if (!existsSync(join(root, 'libs'))) {
    throw new Error(
      `no jsx6 checkout at ${root}\n` +
        `  clone it:  git clone https://github.com/hrgdavor/jsx6 ${root}\n` +
        `  or point JCODEBUDDY_JSX6_DIR at an existing one`,
    )
  }
  return root
}

/**
 * `@jsx6/*` → the checkout's `libs/*`.
 *
 * This page bundles jsx6 rather than loading it from a shared `/lib/` directory: it is served at
 * `/app/incremental-build.html`, while a report is served two levels deeper, so the report's import map
 * (`../../lib/jsx6/...`) would point at the wrong place. One tool page is one self-contained bundle, and the
 * aliases are what let esbuild resolve a checkout the workspace-install cannot.
 */
export function libraryAliases(root) {
  const aliases = {}
  for (const name of readdirSync(join(root, 'libs'))) {
    const entry = join(root, 'libs', name, 'index.js')
    if (existsSync(entry)) {
      aliases[`@jsx6/${name}`] = entry
    }
  }
  return aliases
}
