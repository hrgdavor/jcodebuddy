// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'
import { diffLines } from 'diff'

const HOST_PAGE = location.pathname.startsWith('/app/')
const TOKEN_KEY = 'jcodebuddy.token'
const $file = signal('')
const $line = signal('1')
const $status = signal('Preview a class, review the generated members, then apply them.')
const $error = signal('')
const $busy = signal(false)
let proposal = null

function resolveToken() {
  const fromUrl = new URLSearchParams(location.search).get('token') || ''
  if (fromUrl) {
    try {
      localStorage.setItem(TOKEN_KEY, fromUrl)
      const clean = new URL(location.href)
      clean.searchParams.delete('token')
      history.replaceState(null, '', clean)
    } catch {
      // Keep the in-memory value below.
    }
    return fromUrl
  }
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

const TOKEN = HOST_PAGE ? resolveToken() : ''

async function call(method, params = {}) {
  const response = HOST_PAGE
    ? await fetch('/api/tools/jcodebuddy-agent/call', {
        method: 'POST',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json', ...(TOKEN ? { 'X-WebView-Token': TOKEN } : {}) },
        body: JSON.stringify({ method, params }),
      })
    : await fetch('/rpc', {
        method: 'POST',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ method, params }),
      })
  let body = {}
  try {
    body = await response.json()
  } catch {
    // Status is the only information left.
  }
  if (!response.ok || body.error) {
    throw new Error(body.error || body.message || `HTTP ${response.status}`)
  }
  return body.result
}

async function preview() {
  const file = $file().trim()
  if (!file) {
    $error('Enter a project-relative Java file.')
    return
  }
  $busy(true)
  $error('')
  $status('Generating a proposal…')
  try {
    const result = await call('tool.preview', { tool: 'builder', file, line: Number($line()) || 1 })
    proposal = result
    $status(
      result.proposalId
        ? `Proposal ${result.proposalId}: ${result.files?.length || 0} file(s). Review before applying.`
        : result.message || 'No change.',
    )
    renderDiff(result)
  } catch (error) {
    proposal = null
    $error(error.message)
    $status('Preview failed.')
    renderDiff(null)
  } finally {
    $busy(false)
  }
}

async function apply() {
  if (!proposal?.proposalId) return
  $busy(true)
  $error('')
  try {
    const result = await call('tool.apply', { proposalId: proposal.proposalId })
    $status(`Apply: ${result.status}${result.files ? ` (${result.files.length} file(s))` : ''}`)
    proposal = null
    renderDiff(null)
  } catch (error) {
    $error(error.message)
  } finally {
    $busy(false)
  }
}

async function reject() {
  if (!proposal?.proposalId) return
  $busy(true)
  $error('')
  try {
    const result = await call('tool.reject', { proposalId: proposal.proposalId })
    $status(`Reject: ${result.status}`)
    proposal = null
    renderDiff(null)
  } catch (error) {
    $error(error.message)
  } finally {
    $busy(false)
  }
}

function renderDiff(result) {
  const body = document.getElementById('diff')
  body.replaceChildren()
  if (!result?.files?.length) {
    const empty = document.createElement('div')
    empty.className = 'empty'
    empty.textContent = 'No proposed change.'
    body.append(empty)
    return
  }
  for (const file of result.files) {
    const section = document.createElement('section')
    section.className = 'diff-file'
    const head = document.createElement('div')
    head.className = 'diff-file-head'
    head.textContent = file.path
    const lines = document.createElement('div')
    lines.className = 'diff-lines'
    for (const entry of lineDiff(file.before || '', file.after || '')) {
      const row = document.createElement('div')
      row.className = `diff-line simple${entry.kind === 'add' ? ' add' : entry.kind === 'remove' ? ' remove' : ''}`
      const sign = document.createElement('span')
      sign.className = 'sign'
      sign.textContent = entry.kind === 'add' ? '+' : entry.kind === 'remove' ? '-' : ' '
      const content = document.createElement('code')
      content.className = 'diff-code language-java'
      content.textContent = entry.text
      row.append(sign, content)
      lines.append(row)
    }
    section.append(head, lines)
    body.append(section)
  }
  void highlightDiffs(body)
}

async function highlightDiffs(root) {
  const microlighter = window.microlighter
  if (!microlighter || typeof microlighter.highlightAll !== 'function') return
  if (typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight !== 'function') return
  try {
    await microlighter.highlightAll({ root, selector: 'code.diff-code' })
  } catch {
    // Colour is optional; escaped text remains correct.
  }
}

function lineDiff(before, after) {
  const out = []
  for (const part of diffLines(before || '', after || '')) {
    const lines = part.value.split('\n')
    if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()
    for (const text of lines) {
      out.push({ kind: part.added ? 'add' : part.removed ? 'remove' : 'same', text })
    }
  }
  if (out.length === 1 && out[0].text === '') return []
  return out
}

insert(document.getElementById('page'), (
  <div class="agent-shell">
    <header class="top">
      <div>
        <h1>Builder tool</h1>
        <p class="sub">OpenRewrite-backed builder generation, previewed before it is applied.</p>
      </div>
      <a class="badge" href="jcodebuddy-agent.html">← Agent catalog</a>
    </header>

    <section class="status">
      <strong>Preview</strong>
      <span class="muted">{$status}</span>
      <p class="error">{$error}</p>
    </section>

    <section class="tool-form">
      <label>Java file <input placeholder="src/main/java/.../Order.java" value={$file} oninput={(event) => $file(event.target.value)} /></label>
      <label>Line <input type="number" min="1" value={$line} oninput={(event) => $line(event.target.value)} /></label>
      <button type="button" class="primary" disabled={$busy} onclick={() => void preview()}>Preview</button>
    </section>

    <div id="diff" class="diff-body"></div>

    <div class="tool-actions">
      <button type="button" class="primary" disabled={$busy} onclick={() => void apply()}>Apply proposal</button>
      <button type="button" class="danger" disabled={$busy} onclick={() => void reject()}>Reject</button>
    </div>
  </div>
))

// The builder page is a webview too: the heartbeat keeps explicit tools proposing while it is open.
void call('agent.status').catch(() => {})
setInterval(() => {
  if (document.visibilityState !== 'hidden') void call('agent.status').catch(() => {})
}, 5000)
