// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'
import { diffLines, diffWordsWithSpace } from 'diff'

const TOOL = 'jcodebuddy-agent'
const HOST_PAGE = location.pathname.startsWith('/app/')
const TOKEN_KEY = 'jcodebuddy.token'
const initialQuery = new URLSearchParams(location.search)
const OPEN_BATCH = initialQuery.get('batch')
let selectedFileKey = initialQuery.get('file')
let wholeFile = initialQuery.get('full') === '1'
document.body.classList.toggle('batch-mode', Boolean(OPEN_BATCH))

const $badge = signal('connecting…')
const $badgeClass = signal('badge')
const $summary = signal('')
const $error = signal('')
const $notice = signal('')

let tools = []
let batches = []
let refreshBusy = false
let actionBusy = false
let actionError = ''
let batchSignature = ''

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
  const pageScroll = document.scrollingElement?.scrollTop ?? window.scrollY ?? 0
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
  requestAnimationFrame(() => {
    if (document.scrollingElement) document.scrollingElement.scrollTop = pageScroll
    else window.scrollTo(0, pageScroll)
  })
}

function batchFiles(batch) {
  return (batch?.proposals || []).flatMap((proposal) => (proposal.files || []).map((file) => ({
    proposal, file, key: `${proposal.proposalId}:${file.path}`,
  })))
}

function batchUrl(fileKey) {
  const params = new URLSearchParams()
  params.set('batch', OPEN_BATCH)
  if (fileKey) params.set('file', fileKey)
  if (wholeFile) params.set('full', '1')
  return `${location.pathname}?${params.toString()}`
}

function selectBatchFile(fileKey) {
  selectedFileKey = fileKey
  history.replaceState(null, '', batchUrl(fileKey))
  renderBatchDetail()
}

function renderBatchDetail() {
  const view = document.getElementById('batch-view')
  if (!view) return
  const pageScroll = document.scrollingElement?.scrollTop ?? window.scrollY ?? 0
  view.replaceChildren()
  const batch = batches.find((candidate) => candidate.batchId === OPEN_BATCH)
  if (!batch) {
    view.append(
      textElement('h2', `Batch ${OPEN_BATCH} is no longer pending`),
      textElement('p', 'It may have been accepted, rejected or replaced by a newer proposal.'),
    )
    const back = document.createElement('a')
    back.href = './'
    back.className = 'button-link'
    back.textContent = '← Back to catalog'
    view.append(back)
    return
  }
  const files = batchFiles(batch)
  if (!selectedFileKey || !files.some((entry) => entry.key === selectedFileKey)) {
    selectedFileKey = files[0]?.key || null
  }
  const selected = files.find((entry) => entry.key === selectedFileKey) || null
  const tools = [...new Set((batch.proposals || []).map((proposal) => proposal.tool))]

  const header = document.createElement('div')
  header.className = 'batch-detail-head'
  const heading = document.createElement('div')
  const title = textElement('h2', `Batch ${batch.batchId}`)
  const meta = textElement('p', `${tools.join(', ')} · ${files.length} file(s) · ${batch.allApplied ? 'applied' : batch.anyApplied ? 'partially applied' : 'pending review'}${batch.summary ? ` · ${batch.summary}` : ''}`)
  meta.className = 'card-meta'
  heading.append(title, meta)
  const links = document.createElement('div')
  links.className = 'card-actions'
  const back = document.createElement('a')
  back.href = './'
  back.className = 'button-link'
  back.textContent = '← Catalog'
  links.append(back)
  const applyBatchButton = textButton('Apply batch', 'primary', () => applyBatch(batch.batchId))
  applyBatchButton.disabled = batch.allApplied
  const revertBatchButton = textButton('Revert batch', 'danger', () => revertBatch(batch.batchId))
  revertBatchButton.disabled = !batch.anyApplied
  links.append(applyBatchButton, revertBatchButton)
  header.append(heading, links)
  view.append(header)

  const state = document.createElement('div')
  state.className = `batch-state ${batch.allApplied ? 'applied' : batch.anyApplied ? 'partial' : 'pending'}`
  state.textContent = batch.allApplied
    ? '✓ Applied — the files are on disk; use Revert batch to undo the whole batch.'
    : batch.anyApplied
      ? 'Partially applied — some files are on disk; the rest is still reviewable.'
      : 'Pending review — preview only; no editor buffer has been changed.'
  view.append(state)

  const layout = document.createElement('div')
  layout.className = 'batch-layout'
  const sidebar = document.createElement('aside')
  sidebar.className = 'batch-files'
  const sideTitle = textElement('h3', 'Files')
  sidebar.append(sideTitle)
  for (const entry of files) {
    const button = document.createElement('button')
    button.type = 'button'
    button.className = `batch-file${entry.key === selectedFileKey ? ' selected' : ''}${entry.file.applied ? ' applied' : ''}`
    const path = document.createElement('span')
    path.className = 'batch-file-path'
    path.textContent = entry.file.path
    const stateLabel = document.createElement('span')
    stateLabel.className = 'batch-file-state'
    stateLabel.textContent = entry.file.applied ? 'applied' : entry.proposal.tool
    button.append(path, stateLabel)
    button.addEventListener('click', () => selectBatchFile(entry.key))
    sidebar.append(button)
  }
  const diffPanel = document.createElement('section')
  diffPanel.className = `batch-diff${batch.allApplied ? ' applied' : ''}`
  if (selected) {
    const fileHead = document.createElement('div')
    fileHead.className = 'batch-diff-head'
    const fileTitle = textElement('strong', selected.file.path)
    const fileMeta = textElement('span', `${selected.proposal.tool} · ${selected.file.applied ? 'applied' : 'not applied'}`)
    fileMeta.className = 'card-meta'
    fileHead.append(fileTitle, fileMeta)
    const fileActions = document.createElement('div')
    fileActions.className = 'card-actions'
    const toggle = textButton(wholeFile ? 'Changes only' : 'Whole file', '',
      () => {
        wholeFile = !wholeFile
        history.replaceState(null, '', batchUrl(selectedFileKey))
        renderBatchDetail()
      })
    fileActions.append(
      textButton('Open file', '', () => openFile(selected.file.path)),
      toggle,
      selected.file.applied
        ? textButton('Revert file', 'danger', () => revertFile(selected.proposal.proposalId, selected.file.path))
        : textButton('Apply file', 'primary', () => applyFile(selected.proposal.proposalId, selected.file.path)),
    )
    diffPanel.append(fileHead, fileActions, proposalSummary(selected.proposal),
      diffRows(selected.file.before || '', selected.file.after || '',
        languageFor(selected.file.path), wholeFile))
  } else {
    diffPanel.append(textElement('p', 'No files in this batch.'))
  }
  layout.append(sidebar, diffPanel)
  view.append(layout)
  highlightDiffs(diffPanel)
  requestAnimationFrame(() => {
    if (document.scrollingElement) document.scrollingElement.scrollTop = pageScroll
    else window.scrollTo(0, pageScroll)
  })
}


function batchCard(batch) {
  const card = document.createElement('article')
  card.className = `card batch${batch.allApplied ? ' applied' : ''}`
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
  meta.textContent = `${(batch.proposals || []).length} proposal(s) · ${fileCount} file(s) · ${batch.allApplied ? 'applied' : batch.anyApplied ? 'partially applied' : 'review'}${batch.summary ? ` · ${batch.summary}` : ''}`
  const toolCounts = document.createElement('div')
  toolCounts.className = 'card-meta'
  toolCounts.textContent = (batch.proposals || [])
    .map((proposal) => `${proposal.tool} (${proposal.files?.length || 0})`)
    .join(' · ')
  left.append(title, meta, toolCounts)
  const state = document.createElement('div')
  state.className = `badge${batch.allApplied ? ' live' : ''}`
  state.textContent = batch.allApplied ? 'applied' : 'pending'
  head.append(left, state)
  card.append(head)

  const actionsRow = document.createElement('div')
  actionsRow.className = 'card-actions'
  const open = document.createElement('a')
  open.className = 'button-link'
  open.href = `?batch=${encodeURIComponent(batch.batchId)}`
  open.textContent = 'Open batch'
  actionsRow.append(
    open,
    textButton('Apply batch', 'primary', () => applyBatch(batch.batchId)),
    textButton('Revert batch', 'danger', () => revertBatch(batch.batchId)),
  )
  card.append(actionsRow)
  return card
}

function proposalSummary(proposal) {
  const box = document.createElement('div')
  box.className = 'proposal-summary'
  if (proposal.summary) {
    const summary = textElement('p', proposal.summary)
    summary.className = 'proposal-summary-line'
    box.append(summary)
  }
  if (proposal.details?.length) {
    const list = document.createElement('ul')
    list.className = 'proposal-detail-list'
    for (const detail of proposal.details) {
      const item = document.createElement('li')
      item.textContent = detail
      list.append(item)
    }
    box.append(list)
  }
  return box
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
  section.append(head, proposalSummary(proposal))

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
  section.append(head, row, diffRows(file.before || '', file.after || '', languageFor(file.path), false))
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
    $error(status.configurationProblem || status.watchProblem || actionError || '')
    if (OPEN_BATCH) {
      document.getElementById('catalog-view').hidden = true
      document.getElementById('batch-view').hidden = false
      const nextBatchSignature = signatureOf(batches)
      if (nextBatchSignature !== batchSignature) {
        batchSignature = nextBatchSignature
        renderBatchDetail()
      }
    } else {
      document.getElementById('catalog-view').hidden = false
      document.getElementById('batch-view').hidden = true
      renderTools()
      const nextBatchSignature = signatureOf(batches)
      if (nextBatchSignature !== batchSignature) {
        batchSignature = nextBatchSignature
        renderBatches()
      }
    }
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

function signatureOf(value) {
  return (value || []).map((batch) => [
    batch.batchId,
    batch.allApplied ? 'a' : 'p',
    ...(batch.proposals || []).map((proposal) => [
      proposal.proposalId,
      proposal.mode,
      ...(proposal.files || []).map((file) => `${file.path}:${file.applied ? 1 : 0}`),
    ].join(':')),
  ].join('|')).join('~')
}

async function applyProposal(proposalId) {
  if (actionBusy) return
  actionBusy = true
  $notice(`applying ${proposalId}…`)
  try {
    await call('tool.apply', { proposalId })
    $notice(`applied ${proposalId}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  } finally {
    actionBusy = false
  }
}

async function applyFile(proposalId, path) {
  if (actionBusy) return
  actionBusy = true
  $notice(`applying ${path}…`)
  try {
    await call('tool.applyFile', { proposalId, path })
    $notice(`applied ${path}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  } finally {
    actionBusy = false
  }
}

async function revertFile(proposalId, path) {
  if (actionBusy) return
  actionBusy = true
  $notice(`reverting ${path}…`)
  try {
    await call('tool.revertFile', { proposalId, path })
    $notice(`reverted ${path}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  } finally {
    actionBusy = false
  }
}

async function applyBatch(batchId) {
  if (actionBusy) return
  actionBusy = true
  actionError = ''
  $notice(`applying batch ${batchId}…`)
  try {
    const result = await call('tool.applyBatch', { batchId })
    const failures = (result.proposals || []).flatMap((proposal) =>
      (proposal.files || []).filter((file) =>
        file.status !== 'APPLIED' && file.status !== 'already-applied'))
    if (result.status === 'partial' || result.status === 'refused' || failures.length > 0) {
      const detail = failures.map((file) => `${file.path}: ${file.message || file.status}`).join('; ')
      actionError = `batch ${batchId} ${result.status}: ${detail || 'no file was applied'}`
      $notice('')
    } else {
      $notice(`applied batch ${batchId}`)
    }
    await refresh()
  } catch (error) {
    actionError = error.message
  } finally {
    actionBusy = false
  }
}

async function revertBatch(batchId) {
  if (actionBusy) return
  actionBusy = true
  $notice(`reverting batch ${batchId}…`)
  try {
    await call('tool.revertBatch', { batchId })
    $notice(`reverted batch ${batchId}`)
    await refresh()
  } catch (error) {
    $error(error.message)
  } finally {
    actionBusy = false
  }
}

async function rejectProposal(proposalId, permanent) {
  if (actionBusy) return
  actionBusy = true
  $notice(permanent ? 'rejecting and remembering…' : 'rejecting…')
  try {
    const result = await call('tool.reject', { proposalId, permanent })
    $notice(result.permanent ? 'rejected and remembered' : 'rejected')
    await refresh()
  } catch (error) {
    $error(error.message)
  } finally {
    actionBusy = false
  }
}

function diffRows(before, after, language = 'java', wholeFile = true) {
  const lines = document.createElement('div')
  lines.className = 'diff-lines'
  const side = document.createElement('div')
  side.className = 'diff-side'
  const head = document.createElement('div')
  head.className = 'diff-side-head'
  head.append(textElement('span', 'Before'), textElement('span', 'After'))
  side.append(head)

  const wordRanges = []
  const rows = lineDiffRows(before, after)
  let oldLine = 1
  let newLine = 1
  for (const row of rows) {
    if (!wholeFile && row.kind === 'same') {
      if (row.left !== null) oldLine++
      if (row.right !== null) newLine++
      continue
    }
    const line = document.createElement('div')
    line.className = `side-line ${row.kind}`
    const leftCell = sideCell(row.left, row.left === null ? null : oldLine, 'left', language, row.kind)
    const rightCell = sideCell(row.right, row.right === null ? null : newLine, 'right', language, row.kind)
    if (row.kind === 'change') {
      addWordRanges(row.left, row.right, leftCell.code, rightCell.code, wordRanges)
    } else if (row.kind === 'remove') {
      addFullRange(leftCell.code, 'word-remove', wordRanges)
    } else if (row.kind === 'add') {
      addFullRange(rightCell.code, 'word-add', wordRanges)
    }
    line.append(leftCell, rightCell)
    if (row.left !== null) oldLine++
    if (row.right !== null) newLine++
    side.append(line)
  }
  lines._wordRanges = wordRanges
  lines.append(side)
  return lines
}

function textElement(tag, text) {
  const element = document.createElement(tag)
  element.textContent = text
  return element
}

function sideCell(text, lineNumber, side, language, kind) {
  const cell = document.createElement('div')
  cell.className = `side-cell ${side}${kind === 'same' ? '' : ` ${kind}`}`
  if (text === null) {
    cell.classList.add('empty')
    return cell
  }
  const number = document.createElement('span')
  number.className = 'ln'
  number.textContent = lineNumber === null ? '' : String(lineNumber)
  const code = document.createElement('code')
  code.className = `diff-code language-${language}`
  code.textContent = text
  cell.code = code
  cell.append(number, code)
  return cell
}

function addWordRanges(left, right, leftCode, rightCode, ranges) {
  if (!leftCode || !rightCode) return
  let leftOffset = 0
  let rightOffset = 0
  for (const part of diffWordsWithSpace(left, right)) {
    const length = part.value.length
    if (part.added) {
      addRange(rightCode, rightOffset, rightOffset + length, 'word-add', ranges)
      rightOffset += length
    } else if (part.removed) {
      addRange(leftCode, leftOffset, leftOffset + length, 'word-remove', ranges)
      leftOffset += length
    } else {
      leftOffset += length
      rightOffset += length
    }
  }
}

function addFullRange(code, kind, ranges) {
  if (code) addRange(code, 0, code.textContent.length, kind, ranges)
}

function addRange(code, start, end, kind, ranges) {
  if (code && end > start) ranges.push({ code, start, end, kind })
}

function lineDiffRows(before, after) {
  const left = normalizeDiffText(before)
  const right = normalizeDiffText(after)
  const parts = diffLines(left, right)
  const removed = []
  const added = []
  const rows = []
  const flush = () => {
    const count = Math.max(removed.length, added.length)
    for (let index = 0; index < count; index++) {
      const beforeLine = index < removed.length ? removed[index] : null
      const afterLine = index < added.length ? added[index] : null
      rows.push({
        left: beforeLine,
        right: afterLine,
        kind: beforeLine !== null && afterLine !== null ? 'change' : beforeLine !== null ? 'remove' : 'add',
      })
    }
    removed.length = 0
    added.length = 0
  }
  for (const part of parts) {
    const lines = part.value.split('\n')
    if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()
    if (part.added) added.push(...lines)
    else if (part.removed) removed.push(...lines)
    else {
      flush()
      for (const line of lines) rows.push({ left: line, right: line, kind: 'same' })
    }
  }
  flush()
  if (rows.length === 1 && rows[0].left === '' && rows[0].right === '') return []
  return rows
}

function normalizeDiffText(text) {
  return String(text || '').replace(/\r\n?/g, '\n')
}

/** Colour the side-by-side code and add word-level ranges after the line diff. */
async function highlightDiffs(root) {
  const microlighter = window.microlighter
  const hasHighlight = typeof CSS !== 'undefined' && Boolean(CSS.highlights)
    && typeof Highlight === 'function'
  if (microlighter && typeof microlighter.highlightAll === 'function' && hasHighlight) {
    try {
      await microlighter.highlightAll({ root, selector: 'code.diff-code' })
    } catch {
      // Colour is a review aid; the escaped diff text remains usable without it.
    }
  }
  if (!hasHighlight) return
  const wordRanges = []
  for (const lines of root.querySelectorAll('.diff-lines')) {
    if (Array.isArray(lines._wordRanges)) wordRanges.push(...lines._wordRanges)
  }
  for (const kind of ['word-add', 'word-remove']) CSS.highlights.delete(kind)
  const groups = new Map()
  for (const entry of wordRanges) {
    const code = entry.code
    const node = code?.firstChild
    if (!code || !node || node.nodeType !== Node.TEXT_NODE) continue
    const range = document.createRange()
    range.setStart(node, Math.min(entry.start, node.textContent.length))
    range.setEnd(node, Math.min(entry.end, node.textContent.length))
    if (range.collapsed) continue
    if (!groups.has(entry.kind)) groups.set(entry.kind, new Highlight())
    groups.get(entry.kind).add(range)
  }
  for (const [kind, highlight] of groups) CSS.highlights.set(kind, highlight)
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

    <div id="catalog-view">
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

    <div id="batch-view" hidden></div>

  </div>
))

void refresh()
setInterval(() => {
  if (document.visibilityState !== 'hidden') void refresh()
}, 1500)