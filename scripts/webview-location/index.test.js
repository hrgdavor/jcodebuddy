#!/usr/bin/env bun
/**
 * The location grammar, asserted against the shared vectors.
 *
 * `webview/conformance/location-fragments.json` is the claim; this test is one of the two readers that must agree
 * with it. The other is `webview/core/webview-core`'s `LocationFragmentTest`, which loads the same file — that is the
 * point of the file existing, per `webview/conformance/README.md`: a rule that lives in two languages is only one
 * rule while both are checked against the same claims, and neither generates the claims.
 *
 * Run: `bun test scripts/webview-location`
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { LOCATION_KINDS, locationSummary, parseLocation } from './index.js'

const here = dirname(fileURLToPath(import.meta.url))
const vectorsPath = join(here, '..', '..', 'webview', 'conformance', 'location-fragments.json')
const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8'))

test('the vectors are the shape this test reads', () => {
  assert.equal(vectors.shape, 'location-fragments', 'another file is not the location vectors')
  assert.ok(Array.isArray(vectors.cases) && vectors.cases.length > 20, 'the grammar is not a handful of cases')
})

test('every vector case parses to what the file claims', () => {
  for (const vector of vectors.cases) {
    const actual = parseLocation(vector.path, vector.fragment)
    assert.deepEqual(
      actual,
      vector.expect,
      `${vector.path}#${vector.fragment} (${vector.why}) should be ${JSON.stringify(vector.expect)}, ` +
        `got ${JSON.stringify(actual)}`,
    )
  }
})

test('every kind the grammar can return is one of the declared kinds', () => {
  for (const vector of vectors.cases) {
    const actual = parseLocation(vector.path, vector.fragment)
    if (actual !== null) {
      assert.ok(
        LOCATION_KINDS.includes(actual.kind),
        `${vector.fragment} produced an undeclared kind: ${actual.kind}`,
      )
    }
  }
})

test('a location always has a summary, and a non-location has none', () => {
  for (const vector of vectors.cases) {
    const actual = parseLocation(vector.path, vector.fragment)
    const summary = locationSummary(actual)
    if (actual === null) {
      assert.equal(summary, '', `${vector.fragment} is not a location, so it has no summary`)
    } else {
      assert.notEqual(summary, '', `${vector.fragment} is a location, so it needs a summary for a tooltip`)
    }
  }
})

test('the scope modifiers read as what they mean', () => {
  const base = 'src/main/java/com/hrg/bla/SomeFile.java'
  assert.equal(locationSummary(parseLocation(base, '-add')), 'region add (body only)')
  assert.equal(locationSummary(parseLocation(base, '+add')), 'region add (with annotations)')
  assert.equal(locationSummary(parseLocation(base, '++add')), 'region add (with annotations and doc comment)')
  // A name with no modifier is a member: a declaration, or a heading anchor in a document.
  assert.equal(locationSummary(parseLocation(base, 'add')), 'member add')
  assert.equal(locationSummary(parseLocation(base, 'L42-L58')), 'lines 42–58')
  assert.equal(locationSummary(parseLocation('package.json', 'name,scripts.test')), 'keys name, scripts.test')
})

test('a missing or non-string fragment is not a location', () => {
  const path = 'src/main/java/com/hrg/bla/SomeFile.java'
  assert.equal(parseLocation(path, undefined), null)
  assert.equal(parseLocation(path, null), null)
  assert.equal(parseLocation(path, 42), null)
  assert.equal(parseLocation(path, '#'), null)
  assert.equal(parseLocation(null, 'someMethod'), null, 'no path means no file type, and a member needs one')
  assert.deepEqual(parseLocation(null, 'L42'), { kind: 'line', line: 42 }, 'an explicit line needs no file type')
})
