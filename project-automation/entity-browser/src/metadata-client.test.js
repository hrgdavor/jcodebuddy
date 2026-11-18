import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { loadMetadata } from './metadata-client.js'

const realFetch = globalThis.fetch
const realEventSource = globalThis.EventSource
const realDocument = globalThis.document

/**
 * The live client is tested with a tiny fetch/EventSource seam, not a browser: the contract is which URLs
 * are fetched and which keys are replaced, and that is observable without a DOM.
 */
function fakeWorld(files) {
  const requests = []
  const listeners = new Map()
  let eventSource
  class FakeEventSource {
    constructor(url) {
      this.url = url
      this.listeners = new Map()
      eventSource = this
    }

    addEventListener(name, handler) {
      this.listeners.set(name, handler)
    }

    close() {}
  }
  globalThis.document = { baseURI: 'http://example.test/report/' }
  globalThis.EventSource = FakeEventSource
  globalThis.fetch = async (url) => {
    const key = String(url).replace('http://example.test/report/', '').replace(/^\.\//, '')
    requests.push(key)
    if (!(key in files)) {
      return new Response('not found', { status: 404 })
    }
    return Response.json(files[key])
  }
  return {
    requests,
    files,
    source: () => eventSource,
    listeners,
    emit(name, data) {
      eventSource?.listeners.get(name)?.({ data: JSON.stringify(data) })
    },
  }
}

test('falls back to the single model.json when no manifest is published', async () => {
  const world = fakeWorld({
    'model.json': { title: 'static', types: { 'demo.A': { name: 'A' } } },
  })
  const loaded = await loadMetadata()
  assert.equal(loaded.live, false)
  assert.equal(loaded.model.types['demo.A'].name, 'A')
  assert.deepEqual(world.requests, ['manifest.json', 'model.json'])
  globalThis.fetch = realFetch
  globalThis.EventSource = realEventSource
  globalThis.document = realDocument
})

test('loads index plus every shard and applies only changed shards from SSE', async () => {
  const world = fakeWorld({
    'manifest.json': { report: 'demo', generation: 'g1' },
    'index.json': { report: 'demo', generation: 'g1', shards: ['s0', 's1'], stats: { records: 2 } },
    'blobs/s0.json': { 'demo.A': { name: 'A' } },
    'blobs/s1.json': { 'demo.B': { name: 'B' } },
  })
  const updates = []
  const loaded = await loadMetadata({ onChange: (model) => updates.push(model) })
  assert.deepEqual(Object.keys(loaded.model.types).sort(), ['demo.A', 'demo.B'])
  assert.equal(loaded.live, true)
  assert.equal(world.source().url, '/api/v1/metadata/events?report=demo')
  assert.deepEqual(world.requests.sort(), ['blobs/s0.json', 'blobs/s1.json', 'index.json', 'manifest.json'])

  world.requests.length = 0
  world.files['blobs/s1.json'] = { 'demo.B': { name: 'B2' } }
  world.files['index.json'] = { report: 'demo', generation: 'g2', shards: ['s0', 's1'], stats: { records: 2 } }
  world.emit('metadata', {
    report: 'demo',
    generation: 'g2',
    changedShards: [1],
    indexChanged: false,
  })
  await Bun.sleep(10)
  assert.equal(updates.length, 1)
  assert.equal(updates[0].types['demo.B'].name, 'B2')
  assert.deepEqual(world.requests, ['blobs/s1.json'])
  globalThis.fetch = realFetch
  globalThis.EventSource = realEventSource
  globalThis.document = realDocument
})
