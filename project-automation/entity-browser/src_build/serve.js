#!/usr/bin/env bun
/**
 * Standalone report server: static page plus the shared metadata/SSE host.
 *
 * <p>Usage: `bun serve.js [--dir <page directory>] [--port 0] [--host 127.0.0.1]`.</p>
 */
import { existsSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { startMetadataServer } from './metadata-server.js'

const DEFAULT_DIR = dirname(fileURLToPath(import.meta.url))

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
  if (!existsSync(`${root}/index.html`)) {
    usage(`no index.html in ${root} — run the entity-browser build first`)
    return 1
  }

  let host
  try {
    host = startMetadataServer({ root, host: options.host, port: options.port })
  } catch (error) {
    console.error(`[serve] could not start: ${error?.message ?? error}`)
    return 1
  }
  console.log(`Serving ${host.root}`)
  console.log(`URL: http://${options.host}:${host.server.port}/`)
  console.log(`Metadata SSE: /api/v1/metadata/events?report=${host.report}`)
  console.log('Press Ctrl+C to stop.')
  process.on('SIGINT', () => {
    host.close()
    process.exit(0)
  })
  return 0
}

if (import.meta.main) {
  const code = main()
  if (code !== 0) {
    process.exit(code)
  }
}
