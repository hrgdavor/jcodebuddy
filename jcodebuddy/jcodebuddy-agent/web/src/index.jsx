// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'
import { diffLines } from 'diff'

const TOOL = 'jcodebuddy-agent'
const HOST_PAGE = location.pathname.startsWith('/app/')
const TOKEN_KEY = 'jcodebuddy.token'

const $badge = signal('connecting…')
const $badgeClass = signal('badge')
const $summary = signal('')
const $error = signal('')
const $notice = signal('')

let tools = []
let batches = []
let refreshBusy = false

function resolveToken() {
  const fromUrl = new URLSearchParams(location.search).get('token') || ''
  if (fromUrl) {
    try {
      localStorage.setItem(TOKEN_KEY, fromUrl)
      const clean = new URL(location.href)
      clean.searchParams.delete('token')
      history.replaceState(null, '', clean)
    } catch {
      // The token still works for this page even without storage/history.
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
    ? await fetch(`/api/tools/${TOOL}/call`, {
        method: 'POST',
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          ...(TOKEN ? { 'X-WebView-Token': TOKEN } : {}),
        },
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
    // An empty/text response leaves only the status code.
  }
  if (!response.ok || body.error) {
    throw new Error(body.error || body.message || `HTTP ${response.status}`)
  }
  return body.result
}

function setBadge(text, kind) {
  $badge(text)
  $badgeClass(`badge${kind ? ` ${kind}` : ''}`)
}

function textButton(label, className, onClick) {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = label
  if (className) button.className = className
  button.addEventListener('click', onClick)
  return button
}

function toolLink(tool) {
  const link = document.createElement('a')
  link.className = 'button-link'
  link.href = `${tool.id}.html`
  link.textContent = `Open ${tool.title || tool.id}`
  return link
}

function renderTools() {
  const list = document.getElementById('tools')
  if (!list) return
  list.replaceChildren()
  if (tools.length === 0) {
    const empty = document.createElement('div')
    empty.className = 'empty'
    empty.textContent = 'No tools registered.'
    list.append(empty)
    return
  }
  for (const tool of tools) {
    const card = document.createElement('article')
    card.className = 'card'
    const head = document.createElement('div')
    head.className = 'card-head'
    const left = document.createElement('div')
    const title = document.createElement('div')
    title.className = 'card-title'
    title.textContent = tool.title || tool.id
    const meta = document.createElement('div')
    meta.className = 'card-meta'
    meta.textContent = `${tool.description || 'proposal tool'} · activation ${tool.activation || 'explicit'}`
    left.append(title, meta)
    head.append(left)
    const footer = document.createElement('div')
    footer.className = 'card-actions'
    if (tool.page) footer.append(toolLink(tool))
    card.append(head, footer)
    list.append(card)
  }
}

function renderBatches() {
  const list = document.getElementById('batches')
  if (!list) return
  list.replaceChildren()
  if (batches.length === 0) {
    const empty = document.createElement('div')
    empty.className = 'empty'
    empty.textContent = 'No change batches. A proposal appears here when a tool previews a change.'
    list.append(empty)
    return
  }
  for (const batch of batches) list.append(batchCard(batch))
  highlightDiffs(list)
}

function batchCard(batch) {
  const card = document.createElement('article')
  card.className = 'card batch'
  const head = document.createElement('div')
  head.className = 'card-head'
  const left = document.createElement('div')
  const title = document.createElement('div')
  title.className = 'card-title'
  const tools = [...new Set((batch.proposals || []).map((proposal) => proposal.tool))]
  title.textContent = `Batch ${batch.batchId} · ${tools.join(', ')}`
  const meta = document.createElement('div')
  meta.className = 'card-meta'
  const fileCount = (batch.proposals || []).reduce((count, proposal) => count + (proposal.files?.length || 0), 0)
  meta.textContent = `${fileCount} file(s) · ${batch.allApplied ? 'applied' : batch.anyApplied ? 'partially applied' : 'review'}`
  left.append(title, meta)
  const state = document.createElement('div')
  state.className = `badge${batch.allApplied ? ' live' : ''}`
  state.textContent = batch.allApplied ? 'applied' : 'pending'
  head.append(left, state)
  card.append(head)

  const actionsRow = document.createElement('div')
  actionsRow.className = 'card-actions'
  actionsRow.append(
    textButton('Apply batch', 'primary', () => applyBatch(batch.batchId)),
    textButton('Revert batch', 'danger', () => revertBatch(batch.batchId)),
  )
  card.append(actionsRow)

  for (const proposal of batch.proposals || []) {
    card.append(proposalBlock(proposal))
  }
  return card
}

function proposalBlock(proposal) {
  const section = document.createElement('section')
  section.className = 'batch-proposal'
  const head = document.createElement('div')
  head.className = 'card-head'
  const left = document.createElement('div')
  const title = document.createElement('div')
  title.className = 'card-title'
  title.textContent = proposal.title || proposal.tool
  const meta = document.createElement('div')
  meta.className = 'card-meta'
  meta.textContent = `${proposal.mode} · ${proposal.files?.length || 0} file(s)` +
    (proposal.caseKey ? ` · ${proposal.caseKey}` : '')
  left.append(title, meta)
  head.append(left)
  section.append(head)

  for (const file of proposal.files || []) {
    section.append(batchFile(proposal, file))
  }

  const footer = document.createElement('div')
  footer.className = 'card-actions'
  footer.append(textButton('Accept proposal', 'primary', () => applyProposal(proposal.proposalId)))
  if (proposal.refusalSupported && proposal.caseKey) {
    footer.append(textButton('Reject & skip future', 'danger',
      () => rejectProposal(proposal.proposalId, true)))
  } else {
    footer.append(textButton('Discard', 'danger', () => rejectProposal(proposal.proposalId, false)))
  }
  section.append(footer)
  return section
}

function batchFile(proposal, file) {
  const section = document.createElement('section')
  section.className = 'diff-file'
  const head = document.createElement('div')
  head.className = 'diff-file-head'
  head.textContent = `${file.path}${file.applied ? ' · applied' : ''}`
  const row = document.createElement('div')
  row.className = 'card-actions'
  row.append(
    textButton('Open file', '', () => openFile(file.path)),
    file.applied
      ? textButton('Revert file', 'danger', () => revertFile(proposal.proposalId, file.path))
      : textButton('Apply file', 'primary', () => applyFile(proposal.proposalId, file.path)),
  )
  section.append(head, row, diffRows(file.before || '', file.after || '', languageFor(file.path)))
  return section
}

function languageFor(path) {
  const extension = String(path || '').toLowerCase().split('.').pop()
  if (extension === 'js' || extension === 'mjs' || extension === 'cjs') return 'javascript'
  if (extension === 'md') return 'markdown'
  if (['java', 'json', 'html', 'css', 'yml', 'yaml'].includes(extension)) {
    return extension === 'yml' ? 'yaml' : extension
  }
  return 'java'
}

async function refresh() {
  if (refreshBusy) return
  refreshBusy = true
  try {
    const status = await call('agent.status')
    tools = await call('tool.list')
    batches = await call('tool.batches')
    setBadge(
      status.watching ? 'watching' : 'no fs-meta daemon',
      status.watching ? 'live' : 'warn',
    )
    $summary(
      `${status.tools?.length || tools.length} tool(s) · ${batches.length} change batch(es)` +
        ` · ${status.root}`,
    )
    $error(status.watchProblem || '')
    renderTools()
    renderBatches()
  } catch (error) {
    setBadge('disconnected', 'bad')
    $error(
      `${error.message}${HOST_PAGE && !TOKEN
        ? ' — open this page once with ?token=<host token> or from the IDE, which injects it.'
        : ' — is the agent running and is this page served by the host that proxies .jcodebuddy/ipc?'}`,
    )
  } finally {
    refreshBusy = false
  }
}

async function openFile(path) {
  try {
    if (HOST_PAGE && typeof window.openFile === 'function') {
      window.openFile(path, 1, 1)
      $notice(`open requested: ${path}`)
      return
    }
    const response = await fetch('/jump', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ uri: path, line: 1 }),
    })
    const body = await response.json().catch(() => ({}))
    $notice(body.detail || body.reason || body.error || `jump: HTTP ${response.status}`)
  } catch (error) {
    $notice(`open failed: ${error.message}`)
  }
}

async function applyProposal(proposalId) {
  try {
    await call('tool.apply', { proposalId })
    $notice(`applied ${proposalId}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  }
}

async function applyFile(proposalId, path) {
  try {
    await call('tool.applyFile', { proposalId, path })
    $notice(`applied ${path}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  }
}

async function revertFile(proposalId, path) {
  try {
    await call('tool.revertFile', { proposalId, path })
    $notice(`reverted ${path}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  }
}

async function applyBatch(batchId) {
  try {
    await call('tool.applyBatch', { batchId })
    $notice(`applied batch ${batchId}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  }
}

async function revertBatch(batchId) {
  try {
    await call('tool.revertBatch', { batchId })
    $notice(`reverted batch ${batchId}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  }
}

async function rejectProposal(proposalId, permanent) {
  try {
    const result = await call('tool.reject', { proposalId, permanent })
    $notice(result.permanent ? 'rejected and remembered' : 'rejected')
    await refresh()
  } catch (error) {
    $error(error.message)
  }
}

function diffRows(before, after, language = 'java') {
  const lines = document.createElement('div')
  lines.className = 'diff-lines'
  const diff = lineDiff(before, after)
  let oldLine = 1
  let newLine = 1
  for (const entry of diff) {
    const row = document.createElement('div')
    row.className = `diff-line${entry.kind === 'add' ? ' add' : entry.kind === 'remove' ? ' remove' : ''}`
    const old = document.createElement('span')
    old.className = 'ln'
    old.textContent = entry.kind === 'add' ? '' : String(oldLine)
    if (entry.kind !== 'add') oldLine++
    const next = document.createElement('span')
    next.className = 'ln'
    next.textContent = entry.kind === 'remove' ? '' : String(newLine)
    if (entry.kind !== 'remove') newLine++
    const sign = document.createElement('span')
    sign.className = 'sign'
    sign.textContent = entry.kind === 'add' ? '+' : entry.kind === 'remove' ? '-' : ' '
    const content = document.createElement('code')
    content.className = `diff-code language-${language}`
    content.textContent = entry.text
    row.append(old, next, sign, content)
    lines.append(row)
  }
  return lines
}

/** Colour the rendered diff lines with the same vendored microlighter used by Markdown reports. */
async function highlightDiffs(root) {
  const microlighter = window.microlighter
  if (!microlighter || typeof microlighter.highlightAll !== 'function') return
  if (typeof CSS === 'undefined' || !CSS.highlights || typeof Highlight !== 'function') return
  try {
    await microlighter.highlightAll({ root, selector: 'code.diff-code' })
  } catch {
    // Colour is a review aid; the escaped diff text remains usable without it.
  }
}

/** Line diff from jsdiff (`diff`), the shared review diff implementation. */
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
        <h1>JCodeBuddy Agent</h1>
        <p class="sub">Proposal batches, diffs and review for the JCodeBuddy code tools.</p>
      </div>
      <span class={$badgeClass}>{$badge}</span>
    </header>

    <section class="status">
      <strong>Agent</strong>
      <span class="muted">{$summary}</span>
      <p class="error">{$error}</p>
      <span class="notice">{$notice}</span>
    </section>

    <section>
      <div class="section-head">
        <h2>Tools</h2>
      </div>
      <div class="actions" id="tools"></div>
    </section>

    <section>
      <div class="section-head">
        <h2>Changes</h2>
        <button type="button" onclick={() => void refresh()}>Refresh</button>
      </div>
      <div class="actions" id="batches"></div>
    </section>

  </div>
))

void refresh()
setInterval(() => {
  if (document.visibilityState !== 'hidden') void refresh()
}, 1500)