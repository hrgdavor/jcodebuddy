#!/usr/bin/env bun
/**
 * Serves a built entity-browser directory over HTTP.
 *
 * <p>The page fetches `./model.json`; a `file://` document cannot read a sibling JSON (its origin is
 * null), so this is the standalone server. The JetBrains WebView Explorer serves the same directory
 * itself when you right-click the HTML file; this script is for a plain browser, a terminal session, or
 * a host you point the plugin at through `pageBaseUrl`.</p>
 *
 * Usage:
 *
 *   bun serve.js [--dir <page directory>] [--port 0] [--host 127.0.0.1]
 *
 * A built page directory contains this script, so running `bun serve.js` inside it needs no arguments;
 * its own directory is the default. The source copy under `project-automation/` uses `--dir`.
 */
import { existsSync, statSync } from 'node:fs'
import { extname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

/**
 * The directory this script sits in is the page directory it serves. That is what makes the copy in a
 * generated `.jcodebuddy/metadata/browser/` runnable on its own: no repository layout is assumed.
 */
const DEFAULT_DIR = dirname(fileURLToPath(import.meta.url))

const MIME = new Map(Object.entries({
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
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

function usage(message) {
  if (message) {
    console.error(`[serve] ${message}`)
  }
  console.error('usage: bun serve.js [--dir <page directory>] [--port 0] [--host 127.0.0.1]')
}

function parseArgs(argv) {
  const options = { dir: DEFAULT_DIR, port: 0, host: '127.0.0.1' }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    const value = () => {
      if (index + 1 >= argv.length) {
        throw new Error(`${arg} needs a value`)
      }
      index += 1
      return argv[index]
    }
    if (arg === '--help' || arg === '-h') {
      options.help = true
    } else if (arg === '--dir') {
      options.dir = value()
    } else if (arg.startsWith('--dir=')) {
      options.dir = arg.slice('--dir='.length)
    } else if (arg === '--port') {
      options.port = Number(value())
    } else if (arg.startsWith('--port=')) {
      options.port = Number(arg.slice('--port='.length))
    } else if (arg === '--host') {
      options.host = value()
    } else if (arg.startsWith('--host=')) {
      options.host = arg.slice('--host='.length)
    } else {
      throw new Error(`unknown argument: ${arg}`)
    }
  }
  return options
}

function requestedFile(root, pathname) {
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
  const folded = candidate.toLowerCase()
  const foldedJail = jail.toLowerCase()
  if (folded !== root.toLowerCase() && !folded.startsWith(foldedJail)) {
    return null
  }
  return candidate
}

function main(argv = process.argv.slice(2)) {
  let options
  try {
    options = parseArgs(argv)
  } catch (error) {
    usage(error.message)
    return 2
  }
  if (options.help) {
    usage()
    return 0
  }
  if (!Number.isInteger(options.port) || options.port < 0 || options.port > 65535) {
    usage('--port must be an integer between 0 and 65535')
    return 2
  }
  const root = resolve(options.dir)
  if (!existsSync(root) || !statSync(root).isDirectory()) {
    usage(`no page directory at ${root}`)
    return 1
  }
  if (!existsSync(join(root, 'index.html'))) {
    usage(`no index.html in ${root} — run the entity-browser build first`)
    return 1
  }

  let server
  try {
    server = Bun.serve({
      hostname: options.host,
      port: options.port,
      development: false,
      async fetch(request) {
        const url = new URL(request.url)
        if (request.method !== 'GET' && request.method !== 'HEAD') {
          return new Response('Method Not Allowed', { status: 405, headers: { allow: 'GET, HEAD' } })
        }
        const file = requestedFile(root, url.pathname)
        if (!file) {
          return new Response('Not Found', { status: 404 })
        }
        const body = Bun.file(file)
        if (!(await body.exists())) {
          return new Response('Not Found', { status: 404 })
        }
        const type = MIME.get(extname(file).toLowerCase()) ?? 'application/octet-stream'
        return new Response(request.method === 'HEAD' ? null : body, {
          headers: {
            'content-type': type,
            'cache-control': 'no-store',
            'x-content-type-options': 'nosniff',
          },
        })
      },
    })
  } catch (error) {
    console.error(`[serve] could not start: ${error?.message ?? error}`)
    return 1
  }

  console.log(`Serving ${root}`)
  console.log(`URL: http://${options.host}:${server.port}/`)
  console.log('Press Ctrl+C to stop.')
  return 0
}

if (import.meta.main) {
  const code = main()
  if (code !== 0) {
    process.exit(code)
  }
}
