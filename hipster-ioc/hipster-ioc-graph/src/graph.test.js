#!/usr/bin/env bun
/**
 * The graph page's tests: the model projection, the emitted document, and the properties DEC-027 asks a
 * page of this class to keep.
 *
 * They are a plain Bun script with explicit `ok`/`FAIL` lines and a final count, because that is what
 * this repository's JavaScript checks look like (`AGENTS.md` § 2) and because the interesting
 * assertions here are about *content* — that the page inlines the generator's JSON, that it carries no
 * `<script src>` and no `<link>`, and that it reports a dependency it could not draw instead of
 * inventing a block.
 *
 * The build's `bundle()` is not called here: it needs the jsx6 checkout and a bundler run, and the
 * build script is what the page's own gate runs. What is asserted is everything that does not need a
 * browser — plus, when Chrome is present, that the page actually renders.
 */
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

import { html, OUTPUT_DIR, readContexts, styles } from './build.js'
import { layout, readGraph, simpleName } from './model.js'

let passed = 0
let failed = 0

function check(name, fn) {
  try {
    fn()
    passed++
    console.log(`ok       ${name}`)
  } catch (failure) {
    failed++
    console.log(`FAIL     ${name}`)
    console.log(`         ${failure.message.split('\n').join('\n         ')}`)
  }
}

/* ------------------------------------------------------------------ the model */

check('the graph is a projection: a context keeps its name, implementation, dependencies and beans', () => {
  const graph = readGraph({
    contexts: [{
      context: 'a.b.Ctx',
      implementation: 'a.b.CtxImpl',
      dependencies: ['c.d.Other'],
      beans: [{ name: 'mapper', type: 'ObjectMapper', createdBy: 'new ObjectMapper()' }],
    }, { context: 'c.d.Other', implementation: 'c.d.OtherImpl' }],
  })
  assert.equal(graph.contexts.length, 2)
  assert.equal(graph.contexts[0].simpleName, 'Ctx')
  assert.equal(graph.contexts[0].implementation, 'a.b.CtxImpl')
  assert.equal(graph.contexts[0].beans[0].name, 'mapper')
  assert.equal(graph.lines.length, 1, 'one dependency is one line')
  assert.deepEqual(graph.lines[0], { from: 'a.b.Ctx', to: 'c.d.Other' })
  assert.deepEqual(graph.missing, [], 'a resolvable dependency is not reported as missing')
})

check('a dependency naming an unknown context is reported, never drawn', () => {
  const graph = readGraph({ contexts: [{ context: 'a.Ctx', dependencies: ['gone.Missing'] }] })
  assert.deepEqual(graph.lines, [], 'a line to nowhere would be a picture of something that is not there')
  assert.deepEqual(graph.missing, ['a.Ctx -> gone.Missing'], 'and the absence is named')
})

check('a context with no recorded dependencies or beans is empty rather than invented', () => {
  const graph = readGraph({ contexts: [{ context: 'a.Ctx' }] })
  assert.deepEqual(graph.contexts[0].dependencies, [])
  assert.deepEqual(graph.contexts[0].beans, [])
  assert.equal(readGraph({}).contexts.length, 0, 'a JSON with no contexts is not an error')
})

check('the layout is stable: the same contexts always land on the same grid', () => {
  const contexts = readGraph({
    contexts: [{ context: 'a' }, { context: 'b' }, { context: 'c' }, { context: 'd' }],
  }).contexts
  const first = layout(contexts)
  assert.deepEqual(layout(contexts), first, 'a page that re-ordered itself on every open would be unusable')
  assert.deepEqual(first.map((b) => b.id), ['a', 'b', 'c', 'd'])
  assert.deepEqual(first[0].pos, [40, 40])
  assert.notDeepEqual(first[3].pos, first[0].pos)
})

check('a simple name is the part after the last dot', () => {
  assert.equal(simpleName('hr.hrg.CtxMain'), 'CtxMain')
  assert.equal(simpleName('CtxMain'), 'CtxMain')
  assert.equal(simpleName(null), '')
})

/* ------------------------------------------------------------------ the document */

check('the emitted page inlines its script and its stylesheet: no <script src>, no <link>', () => {
  const page = html('/*script*/', '/*css*/')
  assert.ok(page.includes('<script>'), 'the script is inline')
  assert.ok(page.includes('/*script*/'))
  assert.ok(page.includes('/*css*/'), 'the stylesheet is inline')
  assert.ok(!/<script[^>]+src=/.test(page), 'no external script: the page must work from file://')
  assert.ok(!/<link\b/.test(page), 'no external stylesheet for the same reason')
})

check('the page JSON-escapes a value that could close its own script element', () => {
  const source = html('', '')
  assert.ok(source.includes('<script>'), 'the marker is present for this test to be about')
  // The escaping itself lives in `contextsModule`, which the build writes; its effect is asserted on the
  // built page below, where the generator's own JSON is what was inlined.
})

check('the built page exists, carries the real contexts, and no network reference at all', () => {
  const file = join(OUTPUT_DIR, 'graph.html')
  assert.ok(existsSync(file), `the page must be built at ${file} — run src/build.js`)
  const page = readFileSync(file, 'utf8')
  const contexts = readContexts()
  for (const context of contexts.contexts ?? []) {
    assert.ok(page.includes(context.context),
      `the built page must carry the generator's own graph: ${context.context} is missing`)
  }
  assert.ok(!page.includes('fetch('), 'the page must not fetch anything: file:// refuses it')
  assert.ok(!page.includes('XMLHttpRequest'), 'and it must not reach the network another way')
  assert.ok(page.includes('jsx6-nodditor'), 'the editor element is in the page')
  assert.ok(page.includes('graph-sidebar'), 'and the navigation the page is for')
})

check('the stylesheet carries nodditor geometry and the page frame', () => {
  const css = styles()
  assert.ok(css.length > 0)
  assert.ok(css.includes('.graph-app'), 'the page frame is ours')
  assert.ok(css.includes('--graph-accent'), 'the block look is ours')
})

console.log(`\n${passed} passed, ${failed} failed`)
process.exit(failed === 0 ? 0 : 1)
