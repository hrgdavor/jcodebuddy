import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SERVE = join(PACKAGE_ROOT, 'src_build', 'serve.js')

async function waitFor(url, attempts = 80) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const response = await fetch(url)
      if (response.ok) {
        return response
      }
    } catch {
      // the server is not listening yet
    }
    await Bun.sleep(50)
  }
  throw new Error(`server did not answer ${url}`)
}

test('serve.js serves the page, its JSON and nested assets, and refuses traversal', async () => {
  const root = mkdtempSync(join(PACKAGE_ROOT, '.tmp-serve-'))
  const port = 20000 + Math.floor(Math.random() * 2000)
  mkdirSync(join(root, 'nested'), { recursive: true })
  writeFileSync(join(root, 'index.html'), '<!doctype html><title>probe</title>\n')
  writeFileSync(join(root, 'manifest.json'), '{"report":"probe","generation":"g1"}\n')
  writeFileSync(join(root, 'model.json'), '{"ok":true}\n')
  writeFileSync(join(root, 'nested', 'a.js'), 'export const a = 1\n')
  writeFileSync(join(root, '..', 'outside-probe.txt'), 'not served\n')
  const child = Bun.spawn(['bun', SERVE, '--dir', root, '--port', String(port)], {
    stdio: ['ignore', 'ignore', 'ignore'],
  })
  try {
    const page = await waitFor(`http://127.0.0.1:${port}/reports/probe/`)
    assert.equal(await page.text(), '<!doctype html><title>probe</title>\n')
    const model = await waitFor(`http://127.0.0.1:${port}/reports/probe/model.json`)
    assert.deepEqual(await model.json(), { ok: true })
    assert.match(model.headers.get('content-type') ?? '', /application\/json/)
    const asset = await waitFor(`http://127.0.0.1:${port}/reports/probe/nested/a.js`)
    assert.match(asset.headers.get('content-type') ?? '', /text\/javascript/)

    const traversal = await fetch(`http://127.0.0.1:${port}/../outside-probe.txt`)
    assert.equal(traversal.status, 404, 'a path outside the served directory is not readable')
    const post = await fetch(`http://127.0.0.1:${port}/reports/probe/model.json`, { method: 'POST' })
    assert.equal(post.status, 405)
  } finally {
    child.kill()
    rmSync(join(root, '..', 'outside-probe.txt'), { force: true })
    rmSync(root, { recursive: true, force: true })
  }
})
