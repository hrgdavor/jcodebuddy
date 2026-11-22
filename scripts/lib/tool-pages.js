#!/usr/bin/env bun
/**
 * Ingest a tool's standalone UI into a project-automation app according to the project's
 * `.jcodebuddy/conf/agent.json` UI mode.
 *
 * <p>Modes are the plan's contract: `standalone` emits the page, `integrated` emits only the
 * app-embedded route, and `both` emits both. A missing `ui` is `standalone`; an unknown mode is a
 * failure, not a silent fallback. The builder is the first packaged tool; adding a tool means adding
 * its manifest and asset list here, not a second copy in each driver project.</p>
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const TOOLS = [
  {
    id: 'builder',
    title: 'Builder',
    manifest: join('jcodebuddy', 'jcodebuddy-tool-builder', 'tool.json'),
    page: 'builder.html',
    assets: ['builder.js', 'jcodebuddy-agent.css', 'microlighter.js'],
  },
]

/**
 * @param {{repo: string, projectRoot: string, app: string}} options
 * @returns {{id: string, title: string, mode: string, page: string}[]}
 */
export function ingestToolPages({ repo, projectRoot, app }) {
  const configFile = join(projectRoot, '.jcodebuddy', 'conf', 'agent.json')
  const config = existsSync(configFile) ? JSON.parse(readFileSync(configFile, 'utf8')) : {}
  const toolsConfig = config.tools && typeof config.tools === 'object' ? config.tools : {}
  mkdirSync(app, { recursive: true })
  const result = []

  for (const tool of TOOLS) {
    const manifestFile = join(repo, tool.manifest)
    if (!existsSync(manifestFile)) throw new Error(`tool manifest missing: ${manifestFile}`)
    const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'))
    const configured = toolsConfig[tool.id]
    const mode = configured?.ui || manifest.ui || 'standalone'
    if (!['standalone', 'integrated', 'both'].includes(mode)) {
      throw new Error(`tool ${tool.id}: ui must be standalone, integrated or both, got ${mode}`)
    }
    const web = join(repo, 'jcodebuddy', 'jcodebuddy-agent', 'src', 'main', 'resources', 'web')
    const standaloneFile = join(app, tool.page)
    const integratedFile = join(app, `tool-${tool.id}.html`)
    for (const asset of tool.assets) copyFileSync(join(web, asset), join(app, asset))

    if (mode === 'standalone' || mode === 'both') {
      copyFileSync(join(web, tool.page), standaloneFile)
    } else {
      rmSync(standaloneFile, { force: true })
    }
    if (mode === 'integrated' || mode === 'both') {
      const html = readFileSync(join(web, tool.page), 'utf8')
        .replace('<title>Builder — JCodeBuddy Agent</title>', '<title>Builder · integrated</title>')
      writeFileSync(integratedFile, html, 'utf8')
    } else {
      rmSync(integratedFile, { force: true })
    }
    result.push({
      id: tool.id,
      title: tool.title,
      mode,
      page: mode === 'integrated' ? `tool-${tool.id}.html` : tool.page,
    })
  }

  writeFileSync(join(app, 'tools.json'), JSON.stringify(result, null, 2) + '\n', 'utf8')
  return result
}

/** HTML cards shared by a project's dashboard; the page it links already exists. */
export function toolCards(tools) {
  if (tools.length === 0) return ''
  return tools.map((tool) => `    <li>
      <a href="${tool.page}">${tool.title}</a>
      <p>Agent tool UI, <code>${tool.mode}</code> mode.</p>
    </li>`).join('\n')
}
