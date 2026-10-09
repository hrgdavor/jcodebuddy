#!/usr/bin/env bun
/**
 * How the page delivers its decisions (plan step 4.13, deliverable 3): **POST when served, download when it is
 * a file**.
 *
 * The property that matters most here is the one about the *standalone* document: opened from `file://` it must
 * still be able to export, because the server is an enhancement and never the thing that makes the tool work
 * (AGENTS.md § 2). The second is that a failed POST is **reported** — a reviewer told nothing would believe a
 * branch carries decisions it does not.
 *
 * Run from `merge-java/review`: `bun test`.
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { DECISIONS_ENDPOINT, downloadDecisions, isServed, saveDecisions } from '../src/save.js'

const FILE_URL = { protocol: 'file:' }
const HTTP_URL = { protocol: 'http:' }

/** A document walker that records what the download did to it. */
function fakeDocument() {
  const clicked = []
  return {
    clicked,
    createElement(tag) {
      return {
        tag,
        set href(value) {
          this._href = value
        },
        get href() {
          return this._href
        },
        download: '',
        click() {
          clicked.push({ tag, download: this.download })
        },
      }
    },
  }
}

test('a file:// document is not served, and an http one is', () => {
  assert.equal(isServed(FILE_URL), false)
  assert.equal(isServed(HTTP_URL), true)
  assert.equal(isServed({ protocol: 'https:' }), true)
  assert.equal(isServed(undefined), false, 'a document with no location is not served')
})

test('a file:// page still downloads its decisions — the standalone path is untouched', async () => {
  const doc = fakeDocument()
  const blobUrls = []
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  URL.createObjectURL = () => {
    const url = `blob:test-${blobUrls.length}`
    blobUrls.push(url)
    return url
  }
  URL.revokeObjectURL = () => {}
  try {
    const result = await saveDecisions({ schemaVersion: 1, decisions: [] }, {
      location: FILE_URL,
      document: doc,
      fileName: 'decisions-feature.json',
      fetchImpl: () => {
        throw new Error('a file:// page must not try to reach a server')
      },
    })

    assert.equal(result.transport, 'download')
    assert.equal(result.ok, true)
    assert.equal(doc.clicked.length, 1, 'the anchor was clicked once')
    assert.equal(doc.clicked[0].download, 'decisions-feature.json')
    assert.equal(blobUrls.length, 1)
  } finally {
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  }
})

test('a served page POSTs the identical payload, and reports success', async () => {
  const calls = []
  const result = await saveDecisions({ schemaVersion: 1, decisions: [{ signature: 'a' }] }, {
    location: HTTP_URL,
    document: fakeDocument(),
    fetchImpl: async (url, init) => {
      calls.push({ url, init })
      return { ok: true, status: 200, text: async () => '1 decision(s) recorded\n' }
    },
  })

  assert.equal(result.transport, 'post')
  assert.equal(result.ok, true)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, DECISIONS_ENDPOINT)
  assert.equal(calls[0].init.method, 'POST')
  assert.deepEqual(JSON.parse(calls[0].init.body), { schemaVersion: 1, decisions: [{ signature: 'a' }] },
    'the server receives what the download would have contained')
})

test('a refused POST is reported, not swallowed', async () => {
  const result = await saveDecisions({ schemaVersion: 1, decisions: [] }, {
    location: HTTP_URL,
    document: fakeDocument(),
    fetchImpl: async () => ({ ok: false, status: 400, text: async () => 'not a decisions document' }),
  })

  assert.equal(result.transport, 'post')
  assert.equal(result.ok, false)
  assert.match(result.detail, /refused/)
  assert.match(result.detail, /400/)
  assert.match(result.detail, /not a decisions document/)
})

test('a server that cannot be reached says so, and points at the download that still works', async () => {
  const result = await saveDecisions({ schemaVersion: 1, decisions: [] }, {
    location: HTTP_URL,
    document: fakeDocument(),
    fetchImpl: async () => {
      throw new Error('connection refused')
    },
  })

  assert.equal(result.ok, false)
  assert.match(result.detail, /could not reach the server/)
  assert.match(result.detail, /download still works/)
})

test('the endpoint constant matches what serve.js exposes', () => {
  // Two files name this path — the page that posts to it and the server that answers — so a test reads the
  // server's source and compares, rather than trusting that the two were edited together.
  const serve = readFileSync(new URL('../src_build/serve.js', import.meta.url), 'utf8')
  const declared = serve.match(/const DECISIONS_ENDPOINT = '([^']+)'/)
  assert.ok(declared, 'serve.js must declare the endpoint for this test to compare')
  assert.equal(declared[1], DECISIONS_ENDPOINT)
})

test('downloadDecisions sets the file name it was given', () => {
  const doc = fakeDocument()
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  URL.createObjectURL = () => 'blob:test'
  URL.revokeObjectURL = () => {}
  try {
    downloadDecisions({ schemaVersion: 1, decisions: [] }, { document: doc, fileName: 'x.json' })
    assert.equal(doc.clicked[0].download, 'x.json')
  } finally {
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  }
})
