import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { join, resolve } from 'node:path'
import { publishReport, shardOf } from './publish.js'
import { startMetadataServer } from './server.js'

const REPO_ROOT = resolve(import.meta.dir, '..', '..')

function model(label) {
  return {
    title: 'test report',
    types: {
      'demo.A': { name: 'A', kind: 'record', label },
      'demo.B': { name: 'B', kind: 'enum', label },
    },
    stats: { records: 1, types: 2, enums: 1, components: 0, inlineEnums: 0, modules: 1 },
    linkBase: '../../',
    reportPath: 'metadata/test/index.html',
    bridgePort: 0,
    diagnostics: [],
  }
}

function waitForLine(reader, timeoutMs = 3000) {
  return Promise.race([
    (async () => {
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { value, done } = await reader.read()
        if (done) return null
        buffer += decoder.decode(value, { stream: true })
        const end = buffer.indexOf('\n\n')
        if (end >= 0) return buffer.slice(0, end)
      }
    })(),
    Bun.sleep(timeoutMs).then(() => null),
  ])
}

/** The next SSE block that is an event, skipping comment frames such as `: connected`. */
async function waitForEvent(reader, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const frame = await waitForLine(reader, Math.max(1, deadline - Date.now()))
    if (frame === null) return null
    if (frame.includes('event: tool-event')) return frame
  }
  return null
}

/**
 * A fake live tool on a real Unix socket: enough of DEC-053 to exercise the standalone server's proxy.
 * The confirmation is written before the backlog, exactly as `ToolEndpoint` does, so the client's subscribe
 * request cannot mistake an event for its answer.
 */
async function startFakeTool(socketPath) {
  const connections = new Map()
  const history = []
  const server = createServer((socket) => {
    const state = { socket }
    connections.set(socket, state)
    let buffer = ''
    socket.setEncoding('utf8')
    socket.on('data', (chunk) => {
      buffer += chunk
      let index
      while ((index = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, index).trim()
        buffer = buffer.slice(index + 1)
        if (!line) continue
        let request
        try {
          request = JSON.parse(line)
        } catch {
          continue
        }
        const respond = (result) => socket.write(`${JSON.stringify({ jsonrpc: '2.0', id: request.id, result })}\n`)
        if (request.method === 'tool.subscribe') {
          const since = Number.isInteger(request.params?.since) ? request.params.since : null
          respond({ topic: request.params.topic, since: since ?? 0 })
          if (since !== null) {
            for (const event of history) {
              if (event.sequence > since) {
                socket.write(notification(event))
              }
            }
          }
        } else if (request.method === 'echo') {
          respond(request.params)
        } else {
          socket.write(`${JSON.stringify({
            jsonrpc: '2.0',
            id: request.id,
            error: { code: -32601, message: 'no such method' },
          })}\n`)
        }
      }
    })
    socket.on('close', () => connections.delete(socket))
    socket.on('error', () => connections.delete(socket))
  })
  await new Promise((resolveListen, rejectListen) => {
    server.once('error', rejectListen)
    server.listen(socketPath, resolveListen)
  })
  return {
    push(text) {
      const event = { sequence: history.length + 1, text }
      history.push(event)
      for (const { socket } of connections.values()) {
        socket.write(notification(event))
      }
    },
    close() {
      for (const { socket } of connections.values()) {
        socket.destroy()
      }
      connections.clear()
      server.close()
    },
  }
}

function notification(event) {
  return `${JSON.stringify({
    jsonrpc: '2.0',
    method: 'tool.event',
    params: { topic: 'log', data: event },
  })}\n`
}

test('publishReport writes a catalog, manifest, index and content-addressed shards', () => {
  const root = mkdtempSync(join(REPO_ROOT, '.tmp', 'metadata-publish-'))
  try {
    const metadataRoot = join(root, 'metadata')
    const reportDir = join(metadataRoot, 'test')
    mkdirSync(reportDir, { recursive: true })
    const first = publishReport({
      metadataRoot,
      report: 'test',
      title: 'test',
      model: model('one'),
      pageDir: reportDir,
      reportDir,
    })
    assert.ok(existsSync(join(metadataRoot, 'catalog.json')))
    assert.ok(existsSync(join(reportDir, 'manifest.json')))
    assert.ok(existsSync(join(reportDir, 'index.json')))
    const index = JSON.parse(readFileSync(join(reportDir, 'index.json'), 'utf8'))
    assert.equal(index.shards.length, 64)
    assert.ok(index.shards.every((hash) => existsSync(join(reportDir, 'blobs', `${hash}.json`))))
    assert.equal(first.catalog.reports[0].report, 'test')
    assert.equal(shardOf('demo.A', 64), shardOf('demo.A', 64))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the metadata host serves report, source and lib namespaces from one site root', async () => {
  const root = mkdtempSync(join(REPO_ROOT, '.tmp', 'metadata-site-'))
  try {
    const projectRoot = join(root, 'project')
    const metadataRoot = join(projectRoot, '.jcodebuddy', 'metadata')
    const reportDir = join(metadataRoot, 'test')
    mkdirSync(reportDir, { recursive: true })
    mkdirSync(join(projectRoot, 'src'), { recursive: true })
    mkdirSync(join(metadataRoot, 'lib'), { recursive: true })
    mkdirSync(join(projectRoot, '.jcodebuddy', 'app'), { recursive: true })
    writeFileSync(join(projectRoot, '.jcodebuddy', 'app', 'index.html'),
      '<!doctype html><title>app</title>\n', 'utf8')
    writeFileSync(join(projectRoot, 'src', 'A.java'), 'class A {}\n', 'utf8')
    writeFileSync(join(metadataRoot, 'lib', 'shared.js'), 'export const x = 1\n', 'utf8')
    writeFileSync(join(reportDir, 'index.html'), '<!doctype html><title>site</title>\n', 'utf8')
    publishReport({
      metadataRoot,
      report: 'test',
      title: 'test',
      model: model('one'),
      pageDir: reportDir,
      reportDir,
    })
    const host = startMetadataServer({ root: reportDir, projectRoot, metadataRoot, port: 0 })
    const base = `http://127.0.0.1:${host.server.port}`
    try {
      assert.equal((await fetch(`${base}/reports/test/`)).status, 200)
      assert.equal((await fetch(`${base}/app/`)).status, 200)
      assert.equal(await (await fetch(`${base}/source/src/A.java`)).text(), 'class A {}\n')
      assert.equal((await fetch(`${base}/lib/shared.js`)).status, 200)
      assert.equal((await fetch(`${base}/source/../outside`)).status, 404)
      const landing = await fetch(`${base}/`)
      assert.equal(landing.status, 200)
      assert.match(await landing.text(), /JCodeBuddy reports/)
    } finally {
      host.close()
    }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('large collections get more shards automatically', () => {
  const root = mkdtempSync(join(REPO_ROOT, '.tmp', 'metadata-shards-'))
  try {
    const metadataRoot = join(root, 'metadata')
    const reportDir = join(metadataRoot, 'test')
    const types = {}
    for (let index = 0; index < 1000; index += 1) {
      types[`demo.Type${index}`] = { name: `Type${index}`, kind: 'record' }
    }
    publishReport({
      metadataRoot,
      report: 'test',
      title: 'test',
      model: { ...model('one'), types, stats: { ...model('one').stats, types: 1000 } },
      pageDir: reportDir,
      reportDir,
    })
    const index = JSON.parse(readFileSync(join(reportDir, 'index.json'), 'utf8'))
    assert.equal(index.shards.length, 128, '1000 types use 128 buckets instead of 64')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('a three-type change sends only those shard ids, not all 64', async () => {
  const root = mkdtempSync(join(REPO_ROOT, '.tmp', 'metadata-sse-1000-'))
  try {
    const metadataRoot = join(root, 'metadata')
    const reportDir = join(metadataRoot, 'test')
    mkdirSync(reportDir, { recursive: true })
    writeFileSync(join(reportDir, 'index.html'), '<!doctype html><title>test</title>\n', 'utf8')
    const many = (label) => {
      const types = {}
      for (let index = 0; index < 300; index += 1) {
        types[`demo.Type${index}`] = { name: `Type${index}`, kind: 'record', label }
      }
      return { ...model(label), types, stats: { ...model(label).stats, types: 300 } }
    }
    publishReport({ metadataRoot, report: 'test', title: 'test', model: many('one'),
      pageDir: reportDir, reportDir })
    const host = startMetadataServer({ root: reportDir, port: 0, pollMs: 25 })
    const base = `http://127.0.0.1:${host.server.port}`
    try {
      const stream = await fetch(`${base}/events?report=test`)
      const reader = stream.body.getReader()
      assert.match(await waitForLine(reader) ?? '', /: connected/)
      const next = many('one')
      for (const name of ['demo.Type0', 'demo.Type100', 'demo.Type200']) {
        next.types[name].label = 'two'
      }
      publishReport({ metadataRoot, report: 'test', title: 'test', model: next,
        pageDir: reportDir, reportDir })
      const frame = await waitForLine(reader)
      assert.ok(frame)
      const dataLine = frame.split('\n').find((line) => line.startsWith('data: '))
      const event = JSON.parse(dataLine.slice('data: '.length))
      assert.ok(event.changedShards.length <= 3,
        `three changed types must not name all shards: ${JSON.stringify(event.changedShards)}`)
      assert.ok(event.changedShards.length < 64)
      assert.equal(event.indexChanged, false, 'stats/title did not change: the small index is unchanged')
    } finally {
      host.close()
    }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the metadata host publishes one targeted SSE event for a changed shard', async () => {
  const root = mkdtempSync(join(REPO_ROOT, '.tmp', 'metadata-sse-'))
  try {
    const metadataRoot = join(root, 'metadata')
    const reportDir = join(metadataRoot, 'test')
    mkdirSync(reportDir, { recursive: true })
    writeFileSync(join(reportDir, 'index.html'), '<!doctype html><title>test</title>\n', 'utf8')
    const first = publishReport({
      metadataRoot,
      report: 'test',
      title: 'test',
      model: model('one'),
      pageDir: reportDir,
      reportDir,
    })
    const host = startMetadataServer({ root: reportDir, port: 0, pollMs: 25 })
    const base = `http://127.0.0.1:${host.server.port}`
    try {
      const catalog = await fetch(`${base}/api/v1/metadata/catalog`)
      assert.equal(catalog.status, 200)
      const indexResponse = await fetch(`${base}/index.json`)
      const etag = indexResponse.headers.get('etag')
      assert.ok(etag, 'index has an ETag')
      const conditionalResponse = await fetch(`${base}/index.json`, {
        headers: { 'if-none-match': etag },
      })
      assert.equal(conditionalResponse.status, 304)
      const stream = await fetch(`${base}/events?report=test`)
      assert.equal(stream.status, 200)
      const reader = stream.body.getReader()
      const firstConnect = await waitForLine(reader)
      assert.match(firstConnect ?? '', /: connected/)
      // The second manifest write makes exactly one event; the page fetches only the changed shard.
      publishReport({
        metadataRoot,
        report: 'test',
        title: 'test',
        model: model('two'),
        pageDir: reportDir,
        reportDir,
      })
      const frame = await waitForLine(reader)
      assert.ok(frame, 'an SSE frame arrived')
      const dataLine = frame.split('\n').find((line) => line.startsWith('data: '))
      assert.ok(dataLine, 'the frame carries data')
      const event = JSON.parse(dataLine.slice('data: '.length))
      assert.equal(event.report, 'test')
      assert.ok(event.changedShards.length >= 1)
      assert.notEqual(event.generation, first.index.generation)
      const index = JSON.parse(readFileSync(join(reportDir, 'index.json'), 'utf8'))
      assert.equal(index.generation, event.generation)
    } finally {
      host.close()
    }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the standalone host proxies /api/tools: list, token-gated calls and SSE events', async () => {
  const project = mkdtempSync(join(REPO_ROOT, '.tmp', 'metadata-tools-'))
  const ipc = join(project, '.jcodebuddy', 'ipc')
  mkdirSync(ipc, { recursive: true })
  const socket = join(ipc, 'test-tool.sock')
  const fake = await startFakeTool(socket)
  writeFileSync(join(ipc, 'test-tool.json'), JSON.stringify({
    protocol: 'jcodebuddy-tool/1',
    tool: 'test-tool',
    title: 'Test tool',
    ide: 'JUnit',
    ideVersion: '1',
    pid: process.pid,
    socket,
    project,
    startedAt: new Date().toISOString(),
    capabilities: ['tool.info', 'echo', 'tool.subscribe'],
    topics: ['log'],
  }, null, 2))
  const host = startMetadataServer({ root: project, projectRoot: project, port: 0, toolToken: 'secret' })
  const base = `http://127.0.0.1:${host.server.port}`
  try {
    const listed = await fetch(`${base}/api/tools`)
    assert.equal(listed.status, 200)
    const body = await listed.json()
    assert.equal(body.protocol, 'jcodebuddy-tool/1')
    assert.equal(body.tools[0].tool, 'test-tool')

    const anonymous = await fetch(`${base}/api/tools/test-tool/call`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ method: 'echo' }),
    })
    assert.equal(anonymous.status, 403, 'a call can change the tool, so it needs the token')

    const called = await fetch(`${base}/api/tools/test-tool/call`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-webview-token': 'secret' },
      body: JSON.stringify({ method: 'echo', params: { value: 42 } }),
    })
    assert.equal(called.status, 200)
    assert.deepEqual((await called.json()).result, { value: 42 })

    const refused = await fetch(`${base}/api/tools/test-tool/call`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-webview-token': 'secret' },
      body: JSON.stringify({ method: 'nope' }),
    })
    assert.equal(refused.status, 400)

    const streamed = await fetch(`${base}/api/tools/test-tool/events?topic=log&since=0&token=secret`)
    assert.equal(streamed.status, 200)
    assert.match(streamed.headers.get('content-type') ?? '', /text\/event-stream/)
    const reader = streamed.body.getReader()
    fake.push('pushed by the tool')
    const frame = await waitForEvent(reader)
    assert.ok(frame?.includes('event: tool-event'), frame ?? 'no frame')
    assert.ok(frame?.includes('pushed by the tool'), frame)
    assert.ok(frame?.includes('id: 1'), frame)
    await reader.cancel()

    assert.equal((await fetch(`${base}/api/tools/test-tool/events?topic=nope&token=secret`)).status, 400)
    const unknownTool = await fetch(`${base}/api/tools/no-such-tool/call`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-webview-token': 'secret' },
      body: JSON.stringify({ method: 'tool.info' }),
    })
    assert.equal(unknownTool.status, 404)
  } finally {
    host.close()
    fake.close()
    rmSync(project, { recursive: true, force: true })
  }
})
