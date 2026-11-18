#!/usr/bin/env bun
/**
 * Headless live-update gate for a built entity-browser page.
 *
 *   bun scripts/check-metadata-live-page.js [page-dir]
 *
 * Opens the page in a real headless Chrome over the shared metadata host, records a page marker, publishes
 * a model revision that changes only `stats.records`, and asserts that the visible count changes while the
 * marker (and therefore the page document) survives. Exits 0 on success, 1 on failure, and skips with 0
 * when the page or a Chrome binary is unavailable.
 */
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:net'
import { startMetadataServer } from '../project-automation/metadata/server.js'
import { publishReport } from '../project-automation/metadata/publish.js'

const REPO_ROOT = resolve(import.meta.dir, '..')
const PAGE = resolve(process.argv[2] ?? join(REPO_ROOT,
  'hipster-entity', 'hipster-entity-example', '.jcodebuddy', 'metadata', 'browser'))

const CHROME = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean).find((file) => existsSync(file))

if (!existsSync(join(PAGE, 'index.html')) || !existsSync(join(PAGE, 'manifest.json'))) {
  console.log(`skip: no built live page at ${PAGE}`)
  process.exit(0)
}
if (!CHROME) {
  console.log('skip: no Chrome/Edge binary; set CHROME_PATH to run the live-page gate')
  process.exit(0)
}

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer()
    server.unref()
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      server.close(() => resolvePort(port))
    })
  })
}

async function waitForDevTools(port, attempts = 100) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (response.ok) return (await response.json()).webSocketDebuggerUrl
    } catch {
      // Not listening yet.
    }
    await Bun.sleep(100)
  }
  return null
}

async function connect(endpoint) {
  const socket = new WebSocket(endpoint)
  const pending = new Map()
  const events = []
  const waiters = []
  let nextId = 1

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      const { resolve: done, reject } = pending.get(message.id)
      pending.delete(message.id)
      if (message.error) reject(new Error(message.error.message))
      else done(message.result)
      return
    }
    events.push(message)
    for (let index = waiters.length - 1; index >= 0; index -= 1) {
      if (waiters[index].method === message.method) {
        waiters[index].resolve(message.params)
        waiters.splice(index, 1)
      }
    }
  })

  await new Promise((done, reject) => {
    socket.addEventListener('open', done)
    socket.addEventListener('error', () => reject(new Error('DevTools socket failed')))
  })

  const send = (method, params = {}, sessionId) => new Promise((done, reject) => {
    const id = nextId++
    pending.set(id, { resolve: done, reject })
    socket.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }))
  })

  const nextEvent = (method, timeout = 10000) => {
    const found = events.findIndex((event) => event.method === method)
    if (found !== -1) return Promise.resolve(events.splice(found, 1)[0].params)
    return new Promise((done, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${method}`)), timeout)
      waiters.push({ method, resolve: (params) => { clearTimeout(timer); done(params) } })
    })
  }

  return { send, nextEvent, close: () => socket.close() }
}

async function evaluate(browser, sessionId, expression) {
  const result = await browser.send('Runtime.evaluate', {
    expression: `JSON.stringify({ value: (${expression}) })`,
    returnByValue: true,
  }, sessionId)
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text)
  }
  return JSON.parse(result.result.value).value
}

function killTree(pid) {
  if (!pid) return
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/F', '/T', '/PID', String(pid)], { stdio: 'ignore' })
  } else {
    try { process.kill(-pid, 'SIGKILL') } catch { /* already gone */ }
  }
}

const host = startMetadataServer({ root: PAGE, port: 0, pollMs: 100 })
const debugPort = await freePort()
const profile = mkdtempSync(join(REPO_ROOT, '.tmp', 'live-page-chrome-'))
const chrome = spawn(CHROME, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  `--remote-debugging-port=${debugPort}`,
  `--user-data-dir=${profile}`,
  'about:blank',
], { stdio: 'ignore' })
let browser
let failures = 0
try {
  const endpoint = await waitForDevTools(debugPort)
  if (!endpoint) throw new Error('Chrome never opened a DevTools endpoint')
  browser = await connect(endpoint)
  const created = await browser.send('Target.createTarget', { url: 'about:blank' })
  const attached = await browser.send('Target.attachToTarget', { targetId: created.targetId, flatten: true })
  const sessionId = attached.sessionId
  await browser.send('Runtime.enable', {}, sessionId)
  await browser.send('Page.enable', {}, sessionId)
  const loaded = browser.nextEvent('Page.loadEventFired')
  await browser.send('Page.navigate', { url: `http://127.0.0.1:${host.server.port}/` }, sessionId)
  await loaded
  await Bun.sleep(1000)
  const before = await evaluate(browser, sessionId, `(() => {
    window.__jcbLiveMarker = 'still-here';
    const text = document.body.innerText;
    return { marker: window.__jcbLiveMarker, text };
  })()`)
  if (!/records/i.test(before.text)) {
    console.log(`page text: ${before.text.replace(/\s+/g, ' ').slice(0, 800)}`)
    const errorText = await evaluate(browser, sessionId, 'document.querySelector("p")?.textContent ?? ""')
    console.log(`page error: ${errorText}`)
    throw new Error('page did not render the record UI')
  }

  const model = JSON.parse(readFileSync(join(PAGE, 'model.json'), 'utf8'))
  const expected = Number(model.stats?.records ?? 0) + 1
  const changed = {
    ...model,
    stats: { ...model.stats, records: expected },
  }
  publishReport({
    metadataRoot: dirname(PAGE),
    report: 'entity-browser',
    title: changed.title,
    model: changed,
    pageDir: PAGE,
    reportDir: PAGE,
  })
  await Bun.sleep(1200)
  const after = await evaluate(browser, sessionId, `(() => ({
    marker: window.__jcbLiveMarker,
    text: document.body.innerText,
    generation: document.querySelector('[data-metadata-generation]')?.textContent ?? ''
  }))()`)

  if (after.marker !== 'still-here') {
    failures += 1
    console.log('FAIL: the page reloaded instead of live-updating')
  } else {
    console.log('ok live page kept its document marker')
  }
  if (!after.text.includes(`${expected}`) || !/records/i.test(after.text)) {
    failures += 1
    console.log(`FAIL: updated record count ${expected} is not visible`)
  } else {
    console.log(`ok live page rendered the new record count ${expected}`)
  }
} catch (error) {
  failures += 1
  console.log(`FAIL: ${error?.message ?? error}`)
} finally {
  try { await browser?.send('Browser.close') } catch { /* Chrome already gone */ }
  browser?.close()
  killTree(chrome.pid)
  host.close()
  rmSync(profile, { recursive: true, force: true, maxRetries: 6, retryDelay: 100 })
}

process.exit(failures === 0 ? 0 : 1)
