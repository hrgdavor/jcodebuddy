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

const MIME = new Map(Object.entries({
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
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
 * @param {{root: string, host?: string, port?: number, pollMs?: number}} options
 */
export function startMetadataServer({ root, host = '127.0.0.1', port = 0, pollMs = 250 }) {
  const pageRoot = resolve(root)
  const manifestFile = join(pageRoot, 'manifest.json')
  const indexFile = join(pageRoot, 'index.json')
  const clients = new Set()
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
  }, 15_000)

  let server
  server = Bun.serve({
    hostname: host,
    port,
    development: false,
    async fetch(request) {
      const url = new URL(request.url)
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
      if (url.pathname === '/api/v1/metadata/catalog') {
        const catalog = join(pageRoot, '..', 'catalog.json')
        if (!existsSync(catalog)) {
          return new Response('{}', { status: 404, headers: { 'content-type': 'application/json' } })
        }
        return new Response(readFileSync(catalog, 'utf8'), {
          headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
        })
      }
      if (url.pathname === `/api/v1/metadata/${report}/index`) {
        if (!existsSync(indexFile)) {
          return new Response('{}', { status: 404, headers: { 'content-type': 'application/json' } })
        }
        return new Response(readFileSync(indexFile, 'utf8'), {
          headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
        })
      }
      const file = fileInside(pageRoot, url.pathname)
      if (!file) {
        return new Response('Not Found', { status: 404 })
      }
      if (!existsSync(file) || !statSync(file).isFile()) {
        return new Response('Not Found', { status: 404 })
      }
      const type = MIME.get(extname(file).toLowerCase()) ?? 'application/octet-stream'
      return new Response(request.method === 'HEAD' ? null : Bun.file(file), {
        headers: {
          'content-type': type,
          'cache-control': file.includes(`${sep}blobs${sep}`) ? 'public, max-age=31536000, immutable' : 'no-store',
          'x-content-type-options': 'nosniff',
        },
      })
    },
  })

  return {
    server,
    root: pageRoot,
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
      server.stop(true)
    },
  }
}
