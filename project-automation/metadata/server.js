#!/usr/bin/env bun
/**
 * The shared read-only metadata host used by a standalone report directory.
 *
 * <p>The build writes `manifest.json`, `index.json` and immutable `blobs/<sha256>.json` files beside
 * the page. This server serves that directory, exposes the catalog/index for clients, and turns a
 * manifest/index change into one SSE event naming the shards that changed. It polls only the small
 * manifest; it never parses Java and never writes report metadata.</p>
 *
 * <p>`webviewd` may implement the same wire contract independently; the on-disk publisher is the
 * shared part.</p>
 */
import { existsSync, readFileSync, statSync } from 'node:fs'
import { extname, join, resolve, sep } from 'node:path'
import { callTool, listTools, openToolStream, readToolToken, ToolHttpError, PROTOCOL as TOOL_PROTOCOL } from './tool-api.js'

export const APP_ROUTE = '/app/'
export const APP_DIR = '.jcodebuddy/app'

const MIME = new Map(Object.entries({
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.java': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.ts': 'text/plain; charset=utf-8',
  '.tsx': 'text/plain; charset=utf-8',
  '.jsx': 'text/plain; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}))

function jsonFile(file) {
  return JSON.parse(readFileSync(file, 'utf8'))
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char])
}

function fileInside(root, pathname) {
  let decoded
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    return null
  }
  if (decoded === '/' || decoded === '') {
    decoded = '/index.html'
  }
  if (decoded.includes('\0')) {
    return null
  }
  const candidate = resolve(root, `.${decoded}`)
  const jail = root.endsWith(sep) ? root : `${root}${sep}`
  if (candidate !== root && !candidate.toLowerCase().startsWith(jail.toLowerCase())) {
    return null
  }
  return candidate
}

function etagOf(file) {
  const stat = statSync(file)
  return `"${stat.size.toString(16)}-${Math.floor(stat.mtimeMs).toString(16)}"`
}

function conditional(request, file, contentType, immutable) {
  const etag = etagOf(file)
  const requested = request.headers.get('if-none-match')
  if (requested && requested.split(',').map((value) => value.trim()).includes(etag)) {
    return new Response(null, {
      status: 304,
      headers: { etag, 'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-store' },
    })
  }
  return new Response(request.method === 'HEAD' ? null : Bun.file(file), {
    headers: {
      'content-type': contentType,
      etag,
      'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-store',
      'x-content-type-options': 'nosniff',
    },
  })
}

function metaWithoutShards(index) {
  const { shards: _shards, generation: _generation, ...rest } = index
  return rest
}

function changedEvent(previous, next, report) {
  if (!previous) {
    return {
      report,
      generation: next.generation,
      changedShards: next.shards.map((_, index) => index),
      indexChanged: true,
    }
  }
  const changedShards = []
  const count = Math.max(previous.shards?.length ?? 0, next.shards?.length ?? 0)
  for (let index = 0; index < count; index += 1) {
    if (previous.shards?.[index] !== next.shards?.[index]) {
      changedShards.push(index)
    }
  }
  const indexChanged = JSON.stringify(metaWithoutShards(previous)) !== JSON.stringify(metaWithoutShards(next))
  if (changedShards.length === 0 && !indexChanged) {
    return null
  }
  return {
    report,
    generation: next.generation,
    changedShards,
    indexChanged,
  }
}

/**
 * Start the static page + metadata + SSE server for one report directory.
 *
 * @param {{root: string, host?: string, port?: number, pollMs?: number, toolToken?: string}} options
 */
export function startMetadataServer({ root, host = '127.0.0.1', port = 0, pollMs = 250,
  projectRoot: explicitProjectRoot, metadataRoot: explicitMetadataRoot, toolToken }) {
  const pageRoot = resolve(root)
  const metadataRoot = explicitMetadataRoot ? resolve(explicitMetadataRoot) : resolve(pageRoot, '..')
  const projectRoot = explicitProjectRoot ? resolve(explicitProjectRoot) : (() => {
    let current = metadataRoot
    while (current && current !== resolve(current, '..')) {
      if (current.split(/[\\/]/).pop() === '.jcodebuddy') {
        return resolve(current, '..')
      }
      current = resolve(current, '..')
    }
    return pageRoot
  })()
  const catalogFile = join(metadataRoot, 'catalog.json')
  const appRoot = join(projectRoot, '.jcodebuddy', 'app')
  const manifestFile = join(pageRoot, 'manifest.json')
  const indexFile = join(pageRoot, 'index.json')
  const clients = new Set()
  /** Live tool streams: controller → the open tool subscription, so close() can end both ends. */
  const toolStreams = new Map()
  let report = pageRoot.split(/[\\/]/).pop() ?? 'report'
  let lastGeneration = ''
  let lastIndex = null
  let lastEvent = null
  let closed = false

  function readManifest() {
    if (!existsSync(manifestFile)) {
      return null
    }
    try {
      return jsonFile(manifestFile)
    } catch {
      return null
    }
  }

  function publishIfChanged() {
    if (closed) {
      return
    }
    const manifest = readManifest()
    if (!manifest || !manifest.generation || manifest.generation === lastGeneration) {
      return
    }
    let nextIndex
    try {
      nextIndex = jsonFile(indexFile)
    } catch {
      return
    }
    report = manifest.report ?? report
    const event = changedEvent(lastIndex, nextIndex, report)
    lastGeneration = manifest.generation
    lastIndex = nextIndex
    if (!event) {
      return
    }
    lastEvent = event
    const frame = `id: ${event.generation}\nevent: metadata\ndata: ${JSON.stringify(event)}\n\n`
    for (const client of clients) {
      try {
        client.enqueue(frame)
      } catch {
        clients.delete(client)
      }
    }
  }

  const initialManifest = readManifest()
  if (initialManifest) {
    report = initialManifest.report ?? report
    lastGeneration = initialManifest.generation ?? ''
    try {
      lastIndex = jsonFile(indexFile)
    } catch {
      lastIndex = null
    }
  }
  const poll = setInterval(publishIfChanged, pollMs)
  const keepalive = setInterval(() => {
    for (const client of clients) {
      try {
        client.enqueue(': keepalive\n\n')
      } catch {
        clients.delete(client)
      }
    }
    for (const controller of toolStreams.keys()) {
      try {
        controller.enqueue(': keepalive\n\n')
      } catch {
        toolStreams.delete(controller)
      }
    }
  }, 15_000)

  function reportDirFor(id) {
    if (!/^[A-Za-z0-9._-]+$/.test(id ?? '')) {
      return null
    }
    if (id === report) {
      return pageRoot
    }
    try {
      const catalog = jsonFile(catalogFile)
      const entry = (catalog.reports ?? []).find((item) => item.report === id)
      if (entry?.manifest?.endsWith('/manifest.json')) {
        return resolve(metadataRoot, entry.manifest.slice(0, -'/manifest.json'.length))
      }
    } catch {
      // No catalog: only the report passed to this server is known.
    }
    return null
  }

  function staticFile(request, root, pathname, immutable) {
    const file = fileInside(root, pathname)
    if (!file || !existsSync(file) || !statSync(file).isFile()) {
      return new Response('Not Found', { status: 404 })
    }
    return conditional(request, file, MIME.get(extname(file).toLowerCase()) ?? 'application/octet-stream',
      immutable)
  }

  function landing() {
    let reports = []
    try {
      reports = jsonFile(catalogFile).reports ?? []
    } catch {
      // A single-report legacy server still has a usable landing page.
    }
    if (reports.length === 0) {
      reports = [{ report, title: report, page: `${report}/index.html` }]
    }
    const links = reports.map((item) => `<li><a href="/reports/${encodeURIComponent(item.report)}/">`
      + `${escapeHtml(item.title ?? item.report)}</a></li>`).join('')
    const app = existsSync(join(appRoot, 'index.html'))
      ? `<p><a href="/app/">Open the project app</a></p>`
      : ''
    return new Response(`<!doctype html><meta charset="utf-8"><title>JCodeBuddy reports</title>`
      + `<h1>JCodeBuddy</h1>${app}<h2>Reports</h2><ul>${links}</ul>`, {
      headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
    })
  }

  function toolTokenValue() {
    return typeof toolToken === 'string' && toolToken.length > 0 ? toolToken : readToolToken(projectRoot)
  }

  function toolAuthorized(request, url) {
    const required = toolTokenValue()
    if (!required) {
      return false
    }
    const presented = request.headers.get('x-webview-token') ?? url.searchParams.get('token')
    return presented === required
  }

  function jsonResponse(body, status = 200) {
    return new Response(body, {
      status,
      headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
    })
  }

  /**
   * DEC-053 on the standalone server: the descriptors, the call proxy and the SSE stream.
   *
   * <p>The list is open like the rest of this read-only server; a call or a stream can change the tool (rebuild,
   * stop watching), so those require the webview host's token — read from `.jcodebuddy/webview/token` or given
   * as `toolToken` — in a header or the query (EventSource cannot set a header). Without a token they answer
   * `403` and say so, rather than exposing a command channel to any page on the loopback interface.</p>
   */
  async function toolApi(request, url) {
    if (url.pathname === '/api/tools' || url.pathname === '/api/tools/') {
      if (request.method !== 'GET') {
        return new Response('Method Not Allowed', { status: 405, headers: { allow: 'GET' } })
      }
      return jsonResponse(JSON.stringify({ protocol: TOOL_PROTOCOL, tools: listTools(projectRoot) }))
    }
    const match = url.pathname.match(/^\/api\/tools\/([^/]+)\/(call|events)$/)
    if (!match) {
      return new Response('Not Found', { status: 404 })
    }
    const tool = decodeURIComponent(match[1])
    if (!toolAuthorized(request, url)) {
      const hint = toolTokenValue()
        ? ''
        : ' (this server has no token: start a webview host for the project, or pass toolToken)'
      return new Response(`Forbidden: the token is required for a tool call or stream${hint}`, { status: 403 })
    }
    if (match[2] === 'call') {
      if (request.method !== 'POST') {
        return new Response('Method Not Allowed', { status: 405, headers: { allow: 'POST' } })
      }
      try {
        const answer = await callTool(projectRoot, tool, await request.text())
        return jsonResponse(answer.body, answer.status)
      } catch (error) {
        if (error instanceof ToolHttpError) {
          return jsonResponse(error.body, error.status)
        }
        return jsonResponse(JSON.stringify({ error: 'internal', message: error.message }), 500)
      }
    }
    if (request.method !== 'GET') {
      return new Response('Method Not Allowed', { status: 405, headers: { allow: 'GET' } })
    }
    const sinceText = request.headers.get('last-event-id') ?? url.searchParams.get('since')
    const since = /^\d+$/.test(String(sinceText ?? '')) ? Number(sinceText) : null
    let stream
    try {
      stream = await openToolStream(projectRoot, tool, url.searchParams.get('topic'), since)
    } catch (error) {
      if (error instanceof ToolHttpError) {
        return jsonResponse(error.body, error.status)
      }
      return jsonResponse(JSON.stringify({ error: 'internal', message: error.message }), 500)
    }
    let controllerRef
    let done = false
    const closeStream = () => {
      if (done) {
        return
      }
      done = true
      toolStreams.delete(controllerRef)
      stream.close()
    }
    const body = new ReadableStream({
      start(controller) {
        controllerRef = controller
        toolStreams.set(controller, stream)
        // A comment as the first chunk: it flushes the response headers at once, so a consumer's fetch
        // resolves before the first log line, and it is the SSE keep-alive shape a proxy already understands.
        controller.enqueue(': connected\n\n')
        void pump()
      },
      cancel() {
        closeStream()
      },
    })
    async function pump() {
      try {
        while (!done) {
          const message = await stream.next()
          if (message.method === 'tool.event-error') {
            throw new Error(message.params?.reason ?? 'stream ended')
          }
          if (message.method !== 'tool.event') {
            continue
          }
          const data = message.params?.data
          const id = Number.isInteger(data?.sequence) ? data.sequence : null
          const payload = JSON.stringify({ topic: stream.topic, data: data ?? null })
          controllerRef.enqueue(`${id === null ? '' : `id: ${id}\n`}event: tool-event\ndata: ${payload}\n\n`)
        }
      } catch {
        closeStream()
        try {
          controllerRef.close()
        } catch {
          // The browser already closed it.
        }
      }
    }
    return new Response(body, {
      headers: {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
        connection: 'keep-alive',
        'x-accel-buffering': 'no',
      },
    })
  }

  let server
  server = Bun.serve({
    hostname: host,
    port,
    development: false,
    async fetch(request) {
      const url = new URL(request.url)
      if (url.pathname === '/api/tools' || url.pathname.startsWith('/api/tools/')) {
        return toolApi(request, url)
      }
      if (url.pathname === '/events' || url.pathname === '/api/v1/metadata/events') {
        if (request.method !== 'GET') {
          return new Response('Method Not Allowed', { status: 405, headers: { allow: 'GET' } })
        }
        const requested = url.searchParams.get('report')
        if (requested && requested !== report) {
          return new Response(`unknown report ${requested}`, { status: 404 })
        }
        let stream
        const body = new ReadableStream({
          start(controller) {
            stream = controller
            clients.add(controller)
            controller.enqueue(': connected\n\n')
            const lastId = request.headers.get('last-event-id')
            if (lastId && lastId !== lastGeneration && lastEvent) {
              controller.enqueue(`id: ${lastEvent.generation}\nevent: metadata\ndata: ${JSON.stringify(lastEvent)}\n\n`)
            }
          },
          cancel() {
            clients.delete(stream)
          },
        })
        return new Response(body, {
          headers: {
            'content-type': 'text/event-stream',
            'cache-control': 'no-store',
            connection: 'keep-alive',
          },
        })
      }
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        return new Response('Method Not Allowed', { status: 405, headers: { allow: 'GET, HEAD' } })
      }
      if (url.pathname === '/') {
        return landing()
      }
      if (url.pathname === '/app' || url.pathname.startsWith('/app/')) {
        return staticFile(request, appRoot, url.pathname.slice('/app'.length) || '/', false)
      }
      if (url.pathname.startsWith('/reports/')) {
        const rest = url.pathname.slice('/reports/'.length)
        const slash = rest.indexOf('/')
        const id = slash < 0 ? rest : rest.slice(0, slash)
        const reportDir = reportDirFor(id)
        if (!reportDir) {
          return new Response('Unknown report', { status: 404 })
        }
        if (slash < 0) {
          return Response.redirect(`/reports/${id}/`, 302)
        }
        const relative = `/${rest.slice(slash + 1)}`
        return staticFile(request, reportDir, relative, false)
      }
      if (url.pathname.startsWith('/lib/')) {
        return staticFile(request, join(metadataRoot, 'lib'), url.pathname.slice('/lib'.length), true)
      }
      if (url.pathname.startsWith('/source/')) {
        return staticFile(request, projectRoot, url.pathname.slice('/source'.length), false)
      }
      if (url.pathname === '/api/v1/metadata/catalog') {
        if (!existsSync(catalogFile)) {
          return new Response('{}', { status: 404, headers: { 'content-type': 'application/json' } })
        }
        return conditional(request, catalogFile, 'application/json; charset=utf-8', false)
      }
      if (url.pathname === `/api/v1/metadata/${report}/index`) {
        if (!existsSync(indexFile)) {
          return new Response('{}', { status: 404, headers: { 'content-type': 'application/json' } })
        }
        return conditional(request, indexFile, 'application/json; charset=utf-8', false)
      }
      const file = fileInside(pageRoot, url.pathname)
      if (!file) {
        return new Response('Not Found', { status: 404 })
      }
      if (!existsSync(file) || !statSync(file).isFile()) {
        return new Response('Not Found', { status: 404 })
      }
      const type = MIME.get(extname(file).toLowerCase()) ?? 'application/octet-stream'
      return conditional(request, file, type, file.includes(`${sep}blobs${sep}`))
    },
  })

  return {
    server,
    root: pageRoot,
    projectRoot,
    appRoot,
    report,
    close() {
      closed = true
      clearInterval(poll)
      clearInterval(keepalive)
      for (const client of clients) {
        try {
          client.close()
        } catch {
          // already closed
        }
      }
      clients.clear()
      for (const [controller, stream] of toolStreams) {
        try {
          stream.close()
        } catch {
          // already closed
        }
        try {
          controller.close()
        } catch {
          // already closed
        }
      }
      toolStreams.clear()
      server.stop(true)
    },
  }
}
