import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { absoluteBase, absoluteTarget, bridgeKind, bridgeUrl, formatTarget, openLocation } from './bridge.js'

test('the HTTP fallback URL encodes the path and carries line and column', () => {
  assert.equal(
    bridgeUrl({ path: 'src/main/java/demo/Order State.java', line: 42, column: 3 }, 18881),
    'http://127.0.0.1:18881/open?filePath=src%2Fmain%2Fjava%2Fdemo%2FOrder%20State.java&line=42&column=3',
  )
  assert.equal(
    bridgeUrl({ path: 'A.java', line: 1 }, 18881, 'abc123'),
    'http://127.0.0.1:18881/open?filePath=A.java&line=1&column=1&token=abc123',
    'a plugin-served page passes its one-time token back to /open',
  )
})

test('a missing line falls back to 1 in both the URL and the clipboard text', () => {
  assert.equal(bridgeUrl({ path: 'A.java' }, 18881), 'http://127.0.0.1:18881/open?filePath=A.java&line=1&column=1')
  assert.equal(formatTarget({ path: 'A.java' }), 'A.java:1')
  assert.equal(formatTarget({ path: 'A.java', line: 7 }), 'A.java:7')
})

test('a page with no bridge is a clipboard page, and a port makes it an HTTP page', () => {
  assert.equal(bridgeKind(0), 'clipboard')
  assert.equal(bridgeKind(18881), 'http')
})

test('the link base is resolved from the page location, so the host receives an absolute path', () => {
  assert.equal(
    absoluteBase('../../../', 'file:///C:/work/proj/.jcodebuddy/metadata/browser/index.html'),
    'C:/work/proj',
  )
  assert.equal(
    absoluteTarget('src/main/java/A.java', '../../../',
      'file:///C:/work/proj/.jcodebuddy/metadata/browser/index.html'),
    'C:/work/proj/src/main/java/A.java',
  )
  assert.equal(
    absoluteTarget('src/main/java/A.java', '../../../',
      'file:///home/me/proj/.jcodebuddy/metadata/browser/index.html'),
    '/home/me/proj/src/main/java/A.java',
  )
})

test('a served page keeps the relative spelling: the host owns the project root', () => {
  assert.equal(absoluteBase('../../', 'http://127.0.0.1:18881/page/index.html'), null)
  assert.equal(absoluteTarget('src/A.java', '../../', 'http://127.0.0.1:18881/page/index.html'), 'src/A.java')
})

test('openLocation hands the injected host the absolute path and the 1-based line', () => {
  const calls = []
  globalThis.window = {
    openFile: (...args) => calls.push(args),
  }
  try {
    const result = openLocation(
      { path: 'src/main/java/A.java', line: 7 },
      18881,
      {
        linkBase: '../../../',
        href: 'file:///C:/work/proj/.jcodebuddy/metadata/browser/index.html',
      },
    )
    assert.equal(result.transport, 'ide')
    assert.deepEqual(calls, [['C:/work/proj/src/main/java/A.java', 7, 1]])
  } finally {
    delete globalThis.window
  }
})
