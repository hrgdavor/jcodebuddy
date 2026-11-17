import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
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
