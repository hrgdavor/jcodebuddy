import { existsSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'

/**
 * The esbuild definition for the review page — the same shape jsx6's own `setup.md` documents and
 * `apps/nodditor/src_build/esbDef.js` uses (rule: copy the reference, do not invent).
 *
 * `jsx: 'automatic'` + `jsxImportSource: '@jsx6'` is the whole JSX contract: `<div/>` compiles to
 * `jsx('div', …)` from `@jsx6/jsx-runtime`, which calls `toDom` — the DOM is built, not described.
 * `tsconfig: 'tsconfig-custom.json'` keeps this near-empty config in front of esbuild so the editor's
 * `"jsx": "preserve"` cannot reach it and make esbuild emit raw JSX.
 */
export const esbDef = {
  tsconfig: 'tsconfig-custom.json',
  jsx: 'automatic',
  jsxImportSource: '@jsx6',
  format: 'esm',
  loader: { '.js': 'tsx', '.jsx': 'tsx' },
  bundle: true,
  // Not minified: this page is read by a reviewer and by an agent, and a readable bundle is worth
  // more here than the bytes (jsx6's own app minifies because it ships; ours is inspected).
  minify: false,
  sourcemap: true,
}

/**
 * Where the jsx6 checkout is.
 *
 * AGENTS.md § 2 requires UI work to be built against a local checkout updated on demand, never a
 * remembered version: `JCODEBUDDY_JSX6_DIR` overrides, and the default is `<repo>/.jsx6` (a sibling
 * of `.tmp/`, because the recorded gate runs `clean` and would delete a checkout under `target/`).
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
 * A consumer outside the jsx6 workspace cannot resolve these by name: the packages depend on each
 * other with `workspace:*`, which only a workspace install satisfies, and installing a published
 * copy would pin a version — the opposite of what § 2 asks for. Aliasing the checkout's raw ESM
 * entry points gives both: no `node_modules`, and whatever `git pull` last put there.
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
