import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { buildBrowserModel } from './model.js'
import { verifySourceLinks } from './verify.js'

function row(path, components) {
  return {
    path,
    kind: 'record',
    line: 3,
    modifiers: ['public'],
    members: components.map(([name, type, line]) => ({
      name,
      kind: 'record-component',
      type,
      line,
      parameters: [],
      modifiers: [],
      annotations: [],
      hasInitializer: false,
      throws: [],
      hasBody: false,
    })),
    relations: [],
    annotations: [],
  }
}

function fixture(tree, options = {}) {
  const sourcePath = 'src/main/java/demo/OrderState.java'
  mkdirSync(join(tree, 'src/main/java/demo'), { recursive: true })
  writeFileSync(join(tree, sourcePath), 'package demo;\n\n'
    + 'public record OrderState(Long id, String status) {\n'
    + '}\n', 'utf8')
  const index = {
    id: 'demo/.jcodebuddy/index/classes.json',
    module: 'demo',
    sourceRoot: 'src/main/java',
    directory: 'demo/.jcodebuddy/index',
    root: tree,
    data: { format: 1 },
    classes: [{
      fqn: 'demo.OrderState',
      path: sourcePath,
      row: row(options.path ?? sourcePath, options.components ?? [
        ['id', 'Long', 3],
        ['status', 'String', 3],
      ]),
      imports: [],
    }],
  }
  const model = buildBrowserModel({ indexes: [{
    id: index.id,
    module: index.module,
    sourceRoot: index.sourceRoot,
    data: index.data,
    classes: index.classes,
  }] })
  return { model, indexes: [index] }
}

test('a recorded line that contains the member is kept for the page', () => {
  const tree = mkdtempSync(join(tmpdir(), 'jcb-browser-verify-'))
  try {
    const { model, indexes } = fixture(tree)
    const result = verifySourceLinks(model, indexes)
    const state = model.types['demo.OrderState']
    assert.equal(state.sourceAvailable, true)
    assert.equal(state.sourceLine, 3)
    assert.deepEqual(state.components.map((component) => component.sourceLine), [3, 3])
    assert.equal(result.verified, 1)
    assert.equal(result.stale, 0)
    assert.equal(result.missing, 0)
    assert.equal(model.diagnostics.length, 0)
  } finally {
    rmSync(tree, { recursive: true, force: true })
  }
})

test('a stale member line is dropped and reported, not linked', () => {
  const tree = mkdtempSync(join(tmpdir(), 'jcb-browser-verify-'))
  try {
    const { model, indexes } = fixture(tree, {
      components: [['id', 'Long', 3], ['status', 'String', 4]],
    })
    verifySourceLinks(model, indexes)
    const state = model.types['demo.OrderState']
    assert.deepEqual(state.components.map((component) => component.sourceLine), [3, 0],
      'line 4 is `}` and does not contain `status`, so that link is not offered')
    assert.ok(model.diagnostics.some((item) => item.kind === 'browser_line_stale'
      && item.severity === 'warning'))
  } finally {
    rmSync(tree, { recursive: true, force: true })
  }
})

test('a missing source file is an error and disables every link for that type', () => {
  const tree = mkdtempSync(join(tmpdir(), 'jcb-browser-verify-'))
  try {
    const { model, indexes } = fixture(tree, {
      path: 'src/main/java/demo/DoesNotExist.java',
    })
    const result = verifySourceLinks(model, indexes)
    const state = model.types['demo.OrderState']
    assert.equal(state.sourceAvailable, false)
    assert.equal(state.sourceLine, 0)
    assert.equal(result.missing, 1)
    assert.ok(model.diagnostics.some((item) => item.kind === 'browser_source_missing'
      && item.severity === 'error'))
  } finally {
    rmSync(tree, { recursive: true, force: true })
  }
})
