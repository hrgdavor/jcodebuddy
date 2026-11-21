#!/usr/bin/env bun
/**
 * The standalone host's port of DEC-053's tool API.
 *
 * <p>The Java hosts share `jcodebuddy-ipc`'s `ToolProxy`; this file is the Bun server's side of the same wire,
 * because a JavaScript host cannot call into a Java library. It must therefore keep the same statuses and the
 * same SSE frame: `GET /api/tools` lists `.jcodebuddy/ipc/*.json`, a call is `200`/`400`/`404`/`502`, and a
 * stream is `event: tool-event`, `data: {"topic","data"}`, the data's `sequence` as the SSE `id`.</p>
 *
 * <p>No token here: authentication belongs to the route, which is `server.js`'s job because that is the layer
 * that knows the host's token rule. This file only speaks the socket protocol.</p>
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { connect } from 'node:net'

export const PROTOCOL = 'jcodebuddy-tool/1'

/** A refusal the route turns into a JSON response with this status. */
export class ToolHttpError extends Error {
  constructor(status, body) {
    super(body)
    this.status = status
    this.body = body
  }
}

function errorBody(error, message) {
  return JSON.stringify({ error, message: message ?? '' })
}

export function toolDirectory(projectRoot) {
  return join(projectRoot, '.jcodebuddy', 'ipc')
}

/** Every readable descriptor, sorted by tool; a malformed one hides only itself. */
export function listTools(projectRoot) {
  const directory = toolDirectory(projectRoot)
  if (!existsSync(directory)) {
    return []
  }
  const tools = []
  for (const name of readdirSync(directory).sort()) {
    if (!name.endsWith('.json')) {
      continue
    }
    try {
      const descriptor = JSON.parse(readFileSync(join(directory, name), 'utf8'))
      if (descriptor && descriptor.tool && descriptor.socket) {
        tools.push(descriptor)
      }
    } catch {
      // One broken descriptor is reported by reading it directly; listing is best-effort by design.
    }
  }
  tools.sort((left, right) => String(left.tool).localeCompare(String(right.tool)))
  return tools
}

/** The token a webview host published for this project, or an empty string when no host has run. */
export function readToolToken(projectRoot) {
  try {
    return readFileSync(join(projectRoot, '.jcodebuddy', 'webview', 'token'), 'utf8').trim()
  } catch {
    return ''
  }
}

/** Opens one Unix-socket connection and provides request/response plus the notification queue a stream needs. */
function openConnection(socketPath, timeoutMs = 2000) {
  return new Promise((resolve, reject) => {
    const socket = connect({ path: socketPath })
    const queue = []
    let buffer = ''
    let closed = false
    let failure = null
    let pending = null
    let nextId = 0

    const settle = (message) => {
      if (pending) {
        const { resolve: resolveNext } = pending
        pending = null
        resolveNext(message)
      } else {
        queue.push(message)
      }
    }
    const fail = (error) => {
      if (closed) {
        return
      }
      closed = true
      failure = error
      if (pending) {
        const { reject: rejectNext } = pending
        pending = null
        rejectNext(error)
      }
    }
    const timer = setTimeout(() => {
      socket.destroy()
      fail(new Error(`timed out connecting to ${socketPath}`))
    }, timeoutMs)

    socket.setEncoding('utf8')
    socket.on('data', (chunk) => {
      buffer += chunk
      let index
      while ((index = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, index).trim()
        buffer = buffer.slice(index + 1)
        if (!line) {
          continue
        }
        try {
          settle(JSON.parse(line))
        } catch {
          // A malformed line from a local tool is skipped; the next well-formed one carries on.
        }
      }
    })
    socket.on('error', (error) => {
      clearTimeout(timer)
      fail(error)
    })
    socket.on('close', () => {
      fail(new Error(`the tool closed ${socketPath}`))
    })

    const connection = {
      next() {
        return new Promise((resolveNext, rejectNext) => {
          if (queue.length) {
            resolveNext(queue.shift())
            return
          }
          if (closed) {
            rejectNext(failure ?? new Error('closed'))
            return
          }
          pending = { resolve: resolveNext, reject: rejectNext }
        })
      },
      async request(method, params = {}) {
        const id = ++nextId
        socket.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`)
        const message = await connection.next()
        if (message.error) {
          const refusal = new Error(message.error.message ?? 'refused')
          refusal.code = message.error.code
          throw refusal
        }
        return message.result
      },
      close() {
        closed = true
        socket.destroy()
      },
    }

    socket.on('connect', () => {
      clearTimeout(timer)
      resolve(connection)
    })
    socket.on('error', reject)
  })
}

function findTool(projectRoot, tool) {
  return listTools(projectRoot).find((descriptor) => descriptor.tool === tool) ?? null
}

/** `POST /api/tools/<tool>/call`: one JSON-RPC call, mapped to the statuses the page expects. */
export async function callTool(projectRoot, tool, body) {
  const descriptor = findTool(projectRoot, tool)
  if (!descriptor) {
    throw new ToolHttpError(404, errorBody('unknown-tool', `no tool descriptor for '${tool}'`))
  }
  let request
  try {
    request = JSON.parse(body ?? '')
  } catch (error) {
    throw new ToolHttpError(400, errorBody('bad-request',
      `the body must be a JSON object {method, params}: ${error.message}`))
  }
  if (!request || typeof request !== 'object' || Array.isArray(request)) {
    throw new ToolHttpError(400, errorBody('bad-request', 'the body must be a JSON object {method, params}'))
  }
  if (typeof request.method !== 'string' || !request.method.trim()) {
    throw new ToolHttpError(400, errorBody('bad-request', 'the body must name a method'))
  }
  if (request.params != null && (typeof request.params !== 'object' || Array.isArray(request.params))) {
    throw new ToolHttpError(400, errorBody('bad-request', 'params must be a JSON object'))
  }
  let connection
  try {
    connection = await openConnection(descriptor.socket)
  } catch (error) {
    throw new ToolHttpError(502, errorBody('tool-unreachable', error.message))
  }
  try {
    const result = await connection.request(request.method, request.params ?? {})
    return { status: 200, body: JSON.stringify({ tool, result: result ?? null }) }
  } catch (error) {
    if (typeof error.code === 'number') {
      return {
        status: 400,
        body: JSON.stringify({ error: 'tool-refused', code: error.code, message: error.message }),
      }
    }
    return { status: 502, body: errorBody('tool-unreachable', error.message) }
  } finally {
    connection.close()
  }
}

/**
 * `GET /api/tools/<tool>/events`: subscribe and return a stream whose `next()` is one notification.
 *
 * @returns {Promise<{topic: string, next: () => Promise<object>, close: () => void}>}
 */
export async function openToolStream(projectRoot, tool, topic, since) {
  const descriptor = findTool(projectRoot, tool)
  if (!descriptor) {
    throw new ToolHttpError(404, errorBody('unknown-tool', `no tool descriptor for '${tool}'`))
  }
  const topics = Array.isArray(descriptor.topics) ? descriptor.topics : []
  const resolved = topic && String(topic).trim() ? String(topic) : (topics[0] ?? '')
  if (!topics.includes(resolved)) {
    throw new ToolHttpError(400, errorBody('unknown-topic',
      `no topic '${resolved}'; this tool streams ${JSON.stringify(topics)}`))
  }
  let connection
  try {
    connection = await openConnection(descriptor.socket)
  } catch (error) {
    throw new ToolHttpError(502, errorBody('tool-unreachable', error.message))
  }
  try {
    await connection.request('tool.subscribe', since == null ? { topic: resolved } : { topic: resolved, since })
  } catch (error) {
    connection.close()
    if (typeof error.code === 'number') {
      throw new ToolHttpError(400,
        JSON.stringify({ error: 'tool-refused', code: error.code, message: error.message }))
    }
    throw new ToolHttpError(502, errorBody('tool-unreachable', error.message))
  }
  return {
    topic: resolved,
    next() {
      return connection.next()
    },
    close() {
      connection.close()
    },
  }
}
