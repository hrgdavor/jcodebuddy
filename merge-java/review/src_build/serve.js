#!/usr/bin/env bun
/**
 * Serve the review page and accept its decisions (plan step 4.13, deliverable 3).
 *
 * ```sh
 * bun run src_build/serve.js --report <report.json> [--port 8788] [--host 127.0.0.1] [--out <dir>]
 * ```
 *
 * <p>It builds the page with `build.js` first, so the served document and the standalone file come from **one
 * build and one source tree** — a server with its own copy of the page is how the two drift apart. Then it
 * serves that page and exposes {@link DECISIONS_ENDPOINT}, which takes **the same payload** the `file://` page
 * downloads. Nothing about the page changes when it is served except where its payload goes (`src/save.js`),
 * and the standalone file keeps working untouched: the server is an enhancement, never the thing that makes the
 * tool work (AGENTS.md § 2).</p>
 *
 * <h3>Why the default host is loopback only</h3>
 * <p>The endpoint takes write-intent payloads, so it is bound to `127.0.0.1` unless the caller says otherwise:
 * a review of a branch should not be reachable from the network because somebody ran a helper without
 * arguments. `--host 0.0.0.0` is available for a deliberate remote review, and the startup line prints what was
 * bound so that decision is visible rather than assumed.</p>
 *
 * <h3>What it does with a payload</h3>
 * <p>It writes it to `<out>/decisions-<branch>.json` and, unless `--no-record` is given, runs
 * {@link DecisionRecorder}'s CLI against it — the same command the standalone flow prints, so the served path
 * and the file path cannot diverge in what they do. Recording is what turns accepted decisions into sticky
 * replay; the page never touches a branch itself.</p>
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(here, '..')
const repoRoot = resolve(packageRoot, '../..')

const args = process.argv.slice(2)
const valueOf = (flag, fallback) => {
  const index = args.indexOf(flag)
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback
}

const outDir = resolve(valueOf('--out', join(packageRoot, 'build')))
const host = valueOf('--host', '127.0.0.1')
const port = Number(valueOf('--port', '8788'))
const branch = valueOf('--branch', '')
const historyRoot = valueOf('--history', '')
const noRecord = args.includes('--no-record')
const reportArg = valueOf('--report', null)

/** The endpoint `src/save.js` posts to; kept in step with that constant by a test. */
const DECISIONS_ENDPOINT = '/api/decisions'

function usage(stream) {
  stream.write(
    'usage: bun run src_build/serve.js [--report <report.json>] [--out <dir>] [--host <addr>] '
      + '[--port <n>] [--branch <name>] [--history <dir>] [--no-record]\n',
  )
}

// One build, so the served page is the page the standalone file is.
const buildArgs = ['run', join(here, 'build.js'), '--out', outDir]
if (reportArg) {
  buildArgs.push('--report', reportArg)
}
const built = Bun.spawnSync(['bun', ...buildArgs], { stdio: ['ignore', 'pipe', 'pipe'], cwd: packageRoot })
if (built.exitCode !== 0) {
  process.stderr.write(`[serve] the page build failed:\n${built.stderr?.toString() ?? ''}\n`)
  usage(process.stderr)
  process.exit(built.exitCode ?? 1)
}

const pagePath = join(outDir, 'index.html')
const scriptPath = join(outDir, 'review.js')
if (!existsSync(pagePath) || !existsSync(scriptPath)) {
  process.stderr.write(`[serve] the build did not produce ${pagePath} and ${scriptPath}\n`)
  process.exit(1)
}

/**
 * Record a payload with the Java command, so the served flow and the file flow do the same thing.
 *
 * <p>Failure is reported, never swallowed: a reviewer told "sent" whose decisions were refused would believe a
 * branch carries decisions it does not.</p>
 */
function record(payload) {
  const decisionsFile = join(outDir, `decisions-${(payload.branchName || 'branch').replace(/[^\w.-]+/g, '-')}.json`)
  writeFileSync(decisionsFile, JSON.stringify(payload, null, 2), 'utf8')
  if (noRecord) {
    return { ok: true, detail: `written to ${decisionsFile} (--no-record: not recorded)` }
  }
  if (!branch || !historyRoot) {
    return {
      ok: true,
      detail: `written to ${decisionsFile}; pass --branch and --history to also record it`,
    }
  }
  const run = Bun.spawnSync(['bun', join(repoRoot, 'scripts', 'mvn-jdk25.js'), '-o', '-q',
    '-pl', ':merge-java', '-am', 'compile'], { stdio: 'ignore', cwd: repoRoot })
  if (run.exitCode !== 0) {
    return { ok: false, detail: `written to ${decisionsFile}, but the recorder could not be compiled` }
  }
  const recorded = Bun.spawnSync([
    'java', '-cp', join(repoRoot, 'merge-java', 'target', 'classes'),
    'com.codebuddy.merge.DecisionRecorder',
    '--decisions', decisionsFile, '--history', historyRoot, '--branch', branch,
    '--repo', String(payload.repoPath || repoRoot),
  ], { stdio: ['ignore', 'pipe', 'pipe'], cwd: repoRoot })
  const text = `${recorded.stdout?.toString() ?? ''}${recorded.stderr?.toString() ?? ''}`.trim()
  return { ok: recorded.exitCode === 0, detail: `${decisionsFile}\n${text}` }
}

const server = Bun.serve({
  hostname: host,
  port,
  async fetch(request) {
    const url = new URL(request.url)
    if (url.pathname === DECISIONS_ENDPOINT) {
      if (request.method !== 'POST') {
        return new Response('the decisions endpoint takes POST\n', { status: 405 })
      }
      let payload
      try {
        payload = await request.json()
      } catch (failure) {
        return new Response(`the body is not JSON: ${failure.message}\n`, { status: 400 })
      }
      if (payload?.schemaVersion !== 1 || !Array.isArray(payload?.decisions)) {
        return new Response('that is not a decisions document (schemaVersion 1 with a decisions list)\n',
          { status: 400 })
      }
      const result = record(payload)
      return new Response(`${result.detail}\n`, { status: result.ok ? 200 : 500 })
    }
    if (url.pathname === '/' || url.pathname === '/index.html') {
      return new Response(readFileSync(pagePath, 'utf8'), { headers: { 'content-type': 'text/html' } })
    }
    if (url.pathname === '/review.js') {
      return new Response(readFileSync(scriptPath, 'utf8'), { headers: { 'content-type': 'text/javascript' } })
    }
    return new Response('not found\n', { status: 404 })
  },
})

console.log(`[serve] page      http://${host}:${server.port}/`)
console.log(`[serve] decisions POST http://${host}:${server.port}${DECISIONS_ENDPOINT}`)
console.log(`[serve] page from ${outDir}`)
if (host !== '127.0.0.1' && host !== 'localhost') {
  console.log(`[serve] NOTE: bound to ${host}, so the endpoint is reachable from the network`)
}
if (!noRecord && (!branch || !historyRoot)) {
  console.log('[serve] no --branch/--history: payloads are written beside the page and not recorded')
}
