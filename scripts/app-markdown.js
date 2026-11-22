/**
 * The default Markdown handler for a project's generated `/app/` shell.
 *
 * <p>The webview action opens any project file as `/app/?openFile=<project-relative>`, or as
 * `/app/#openFile=<project-relative>` when the app opts into hash routing with
 * `.jcodebuddy/app/navigation.json`. For Markdown, this module fetches the source through the host's
 * `/source/` route and renders it in the app, without leaving the app URL. It also declares
 * {@code window.jcodebuddyNavIntercept}, so the host can render a second file in place and only update the
 * URL; a custom SPA can replace that function. Links work:</p>
 *
 * <ul>
 *   <li>a `.md`/`.markdown` link reloads the app with that document;</li>
 *   <li>a source link keeps its host URL for a normal click, and <b>Ctrl/Cmd+click</b> asks the injected
 *       IDE bridge ({@code window.openFile}) to open the source at the referenced line;</li>
 *   <li><b>Ctrl/Cmd+click on any other rendered segment</b> — a paragraph, bold/italic text, a heading,
 *       a list item, a table row or a code block — opens the current Markdown file at the source line
 *       that segment came from;</li>
 *   <li>an inject-examples marker link (a file link with {@code #L12}, {@code #region name} or a
 *       {@code Class/Member} path) resolves to an exact line when the fragment gives one, or to an
 *       approximate declaration line found in the source;</li>
 *   <li>relative links resolve against the Markdown file's own directory, not the app directory.</li>
 * </ul>
 *
 * <p>It is deliberately vanilla and dependency-free: it is copied by project-automation into
 * `.jcodebuddy/app/markdown.js` and customized by changing the project's page builder, not by installing a
 * Markdown package into the served app. The renderer covers headings, paragraphs, lists, blockquotes,
 * fenced code, tables and inline emphasis/code/links/images — the shapes project documentation uses.</p>
 *
 * <p>It subscribes to the host's mandatory `/api/v1/events` file-change SSE and re-renders the current
 * document when a project file changes. The re-render anchors on the source segment nearest the viewport
 * (falling back to the pixel scroll offset), so a live update does not throw the reader to the top. The
 * stream covers every project file the app can read, not only source files.</p>
 */

const APP_PREFIX = '.jcodebuddy/app/'
const MARKDOWN_RE = /\.(md|markdown)$/i

/** True when a project-relative path is a Markdown document. */
export function isMarkdownPath(path) {
  return typeof path === 'string' && MARKDOWN_RE.test(path)
}

/** Split a link into path and fragment; the fragment is URL-decoded once. */
function splitFragment(href) {
  const index = href.indexOf('#')
  if (index < 0) return { path: href, fragment: '' }
  return { path: href.slice(0, index), fragment: decodeFragment(href.slice(index + 1)) }
}

function decodeFragment(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function normalizePath(path) {
  const parts = String(path || '').replaceAll('\\', '/').split('/')
  const out = []
  for (const part of parts) {
    if (!part || part === '.') continue
    if (part === '..') {
      if (out.length > 0) out.pop()
      continue
    }
    out.push(part)
  }
  return out.join('/')
}

function dirname(path) {
  const index = path.lastIndexOf('/')
  return index < 0 ? '' : path.slice(0, index)
}

/** Resolve one Markdown link against the document path and the app's `openFile` contract. */
export function resolveLink(href, sourcePath) {
  const raw = String(href || '').trim()
  if (!raw) return { kind: 'anchor', href: '' }
  if (/^(https?:|mailto:|tel:|data:)/i.test(raw)) {
    return { kind: 'external', href: raw }
  }
  if (raw.startsWith('#')) {
    return { kind: 'anchor', href: raw, fragment: decodeFragment(raw.slice(1)) }
  }

  const { path: linkPath, fragment } = splitFragment(raw)
  let decodedPath = linkPath
  try {
    decodedPath = decodeURIComponent(linkPath)
  } catch {
    // Leave a partially encoded path alone; the server will refuse it rather than fetch something else.
  }
  const path = decodedPath.startsWith('/')
    ? normalizePath(decodedPath.slice(1))
    : normalizePath([dirname(sourcePath), decodedPath].filter(Boolean).join('/'))
  if (!path) {
    return { kind: 'anchor', href: fragment ? `#${encodeURIComponent(fragment)}` : '', fragment }
  }
  if (isMarkdownPath(path)) {
    return {
      kind: 'doc',
      path,
      fragment,
      href: appUrlFor(path, fragment),
    }
  }
  return {
    kind: 'source',
    path,
    fragment,
    line: lineFromFragment(fragment),
    href: sourceUrlFor(path, fragment),
  }
}

/** The `/app/?openFile=` or `/app/#openFile=` URL for a project-relative file. */
export function appUrlFor(path, fragment) {
  if (typeof location === 'undefined') {
    const open = encodeURIComponent(path)
    return `?openFile=${open}${fragment ? `#${encodeURIComponent(fragment)}` : ''}`
  }
  const url = new URL(location.href)
  const hashMode = new URLSearchParams(location.hash.replace(/^#/, '')).has('openFile')
  if (hashMode) {
    url.searchParams.delete('openFile')
    const route = `openFile=${encodeURIComponent(path)}`
    url.hash = fragment ? `${route}&${encodeURIComponent(fragment)}` : route
  } else {
    url.searchParams.set('openFile', path)
    url.hash = fragment ? `#${encodeURIComponent(fragment)}` : ''
  }
  return `${url.pathname}${url.search}${url.hash}`
}

/** The openFile in either routing spelling: query (default) or hash (navigation.json opt-in). */
export function readOpenFile() {
  if (typeof location === 'undefined') return ''
  const query = new URLSearchParams(location.search).get('openFile')
  if (query) return query
  const hash = location.hash.replace(/^#/, '')
  if (!hash) return ''
  return new URLSearchParams(hash).get('openFile') || ''
}

/** The host route for a project source file. */
export function sourceUrlFor(path, token = tokenFromLocation()) {
  const encoded = path.split('/').map((segment) => encodeURIComponent(segment)).join('/')
  return `/source/${encoded}${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

let activeToken = ''

function tokenFromLocation() {
  return activeToken || storedToken() || urlToken()
}

function urlToken() {
  if (typeof location === 'undefined') return ''
  try {
    return new URLSearchParams(location.search).get('token') || ''
  } catch {
    return ''
  }
}

function storedToken() {
  try {
    return localStorage.getItem('jcodebuddy.token') || ''
  } catch {
    return ''
  }
}

function tokenCandidates() {
  const tokens = [...new Set([activeToken, urlToken(), storedToken()].filter(Boolean))]
  return tokens.length > 0 ? tokens : ['']
}

/** Parse `#L12`, `#L12:5`, `#L12-L14`, `#L12:5-L14:2`; returns `{line, column}` or null. */
export function lineFromFragment(fragment) {
  if (!fragment) return null
  const match = /^L(\d+)(?::(\d+))?/i.exec(decodeFragment(fragment))
  if (!match) return null
  return {
    line: Number.parseInt(match[1], 10),
    column: match[2] ? Number.parseInt(match[2], 10) : 1,
  }
}

/** Approximate a declaration line for an inject-examples name/region fragment. */
export function approximateLine(text, fragment) {
  const name = decodeFragment(fragment || '').trim()
  if (!name) return 1
  const exact = lineFromFragment(name)
  if (exact) return exact.line
  const lines = String(text || '').split(/\r?\n/)
  const region = /^region\s+(.+)$/i.exec(name)
  if (region) {
    const marker = new RegExp(`#region\\s+${escapeRegExp(region[1])}\\b`)
    const at = lines.findIndex((line) => marker.test(line))
    if (at >= 0) return at + 1
  }
  const segments = name.split('/').filter(Boolean)
  let candidate = segments.at(-1) || name
  if (candidate === '-' || candidate === '++') candidate = segments.at(-2) || name
  candidate = candidate.replace(/[-+]+$/, '')
  if (!candidate) return 1
  const word = new RegExp(`\\b${escapeRegExp(candidate)}\\b`)
  const at = lines.findIndex((line) => word.test(line))
  return at >= 0 ? at + 1 : 1
}

/** Render one Markdown document. Links are already routed for the app and the IDE bridge. */
export function renderMarkdown(markdown, sourcePath = '', firstLine = 1) {
  const text = String(markdown || '').replace(/\r\n?/g, '\n')
  const lines = text.split('\n')
  const out = []
  let index = 0
  const lineAt = (at = index) => firstLine + at

  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) {
      index++
      continue
    }

    const fence = /^(`{3,})([\w-]*)\s*$/.exec(line)
    if (fence) {
      const startLine = lineAt()
      const marker = fence[1].length
      const closing = new RegExp('^`{' + marker + ',}\\s*$')
      const body = []
      index++
      while (index < lines.length && !closing.test(lines[index])) {
        body.push(lines[index++])
      }
      if (index < lines.length) index++
      const language = fence[2] ? ` class="language-${escapeAttr(fence[2])}"` : ''
      out.push(`<pre data-md-line="${startLine}"><code${language}>${escapeHtml(body.join('\n'))}</code></pre>`)
      continue
    }

    const heading = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line)
    if (heading) {
      const level = heading[1].length
      const label = heading[2]
      out.push(`<h${level} data-md-line="${lineAt()}" id="${escapeAttr(slug(label))}">`
        + `${inline(label, sourcePath)}</h${level}>`)
      index++
      continue
    }

    if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
      out.push(`<hr data-md-line="${lineAt()}">`)
      index++
      continue
    }

    if (line.includes('|') && index + 1 < lines.length
        && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[index + 1])) {
      out.push(renderTable(lines, index, sourcePath, firstLine))
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) index++
      continue
    }

    if (/^\s*>\s?/.test(line)) {
      const start = index
      const body = []
      while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
        body.push(lines[index].replace(/^\s*>\s?/, ''))
        index++
      }
      out.push(`<blockquote data-md-line="${lineAt(start)}">`
        + `${renderMarkdown(body.join('\n'), sourcePath, lineAt(start))}</blockquote>`)
      continue
    }

    if (/^\s*(?:[-*]|\d+\.)\s+/.test(line)) {
      const ordered = /^\s*\d+\.\s+/.test(line)
      const tag = ordered ? 'ol' : 'ul'
      const items = []
      while (index < lines.length && /^\s*(?:[-*]|\d+\.)\s+/.test(lines[index])) {
        items.push({
          line: lineAt(),
          text: lines[index].replace(/^\s*(?:[-*]|\d+\.)\s+/, ''),
        })
        index++
      }
      out.push(`<${tag}>${items.map((item) => `<li data-md-line="${item.line}">`
        + `${inline(item.text, sourcePath)}</li>`).join('')}</${tag}>`)
      continue
    }

    const paragraph = []
    while (index < lines.length && lines[index].trim()
        && !/^```/.test(lines[index])
        && !/^#{1,6}\s+/.test(lines[index])
        && !/^\s*>\s?/.test(lines[index])
        && !/^\s*(?:[-*]|\d+\.)\s+/.test(lines[index])
        && !(lines[index].includes('|') && index + 1 < lines.length
          && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[index + 1]))) {
      paragraph.push({ line: lineAt(), text: lines[index++].trim() })
    }
    out.push(`<p data-md-line="${paragraph[0].line}">${paragraph.map((part) =>
      `<span data-md-line="${part.line}">${inline(part.text, sourcePath)}</span>`).join(' ')}</p>`)
  }

  return out.join('\n')
}

function renderTable(lines, start, sourcePath, firstLine) {
  const split = (line) => {
    const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
    return trimmed.split(/(?<!\\)\|/).map((cell) => cell.trim().replaceAll('\\|', '|'))
  }
  const header = split(lines[start])
  let index = start + 2
  const rows = []
  while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
    rows.push({ line: firstLine + index, cells: split(lines[index]) })
    index++
  }
  return `<table><thead><tr data-md-line="${firstLine + start}">`
    + `${header.map((cell) => `<th>${inline(cell, sourcePath)}</th>`).join('')}`
    + `</tr></thead><tbody>${rows.map((row) => `<tr data-md-line="${row.line}">`
      + `${row.cells.map((cell) => `<td>${inline(cell, sourcePath)}</td>`).join('')}</tr>`).join('')}`
    + '</tbody></table>'
}

/** Inline Markdown. Code spans and generated link markup are protected from emphasis passes. */
function inline(text, sourcePath) {
  const code = []
  let value = String(text).replace(/`([^`\n]+)`/g, (_, body) => {
    code.push(escapeHtml(body))
    return `\u0000C${code.length - 1}\u0000`
  })
  value = escapeHtml(value)

  const markup = []
  const protect = (html) => {
    markup.push(html)
    return `\u0000M${markup.length - 1}\u0000`
  }
  value = value.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)/g,
    (_, alt, href) => protect(imageMarkup(href, alt, sourcePath)))
  value = value.replace(/\[([^\]]*)\]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)/g,
    (_, label, href) => protect(linkMarkup(href, label, sourcePath)))
  value = value.replace(/<((?:https?:\/\/|mailto:)[^>]+)>/g,
    (_, href) => protect(`<a href="${escapeAttr(href)}" target="_blank" rel="noopener">${escapeHtml(href)}</a>`))

  value = value.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  value = value.replace(/__([^_]+)__/g, '<strong>$1</strong>')
  value = value.replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
  value = value.replace(/(^|[^_])_([^_]+)_/g, '$1<em>$2</em>')
  value = value.replace(/\u0000M(\d+)\u0000/g, (_, at) => markup[Number(at)])
  value = value.replace(/\u0000C(\d+)\u0000/g, (_, at) => `<code>${code[Number(at)]}</code>`)
  return value
}

function linkMarkup(href, label, sourcePath) {
  const target = resolveLink(href, sourcePath)
  if (target.kind === 'external') {
    return `<a href="${escapeAttr(target.href)}" target="_blank" rel="noopener">${label}</a>`
  }
  if (target.kind === 'anchor') {
    return `<a href="${escapeAttr(target.href)}">${label}</a>`
  }
  if (target.kind === 'doc') {
    const fragment = target.fragment ? ` data-fragment="${escapeAttr(target.fragment)}"` : ''
    return `<a href="${escapeAttr(target.href)}" data-doc="${escapeAttr(target.path)}"`
      + ` data-source="${escapeAttr(target.path)}"${fragment}`
      + ` title="Ctrl+click to open ${escapeAttr(target.path)} in the IDE">${label}</a>`
  }
  const fragment = target.fragment ? ` data-fragment="${escapeAttr(target.fragment)}"` : ''
  return `<a href="${escapeAttr(target.href)}" data-source="${escapeAttr(target.path)}"${fragment}`
    + ` title="Ctrl+click to open ${escapeAttr(target.path)} in the IDE">${label}</a>`
}

function imageMarkup(href, alt, sourcePath) {
  const target = resolveLink(href, sourcePath)
  const src = target.kind === 'external' ? target.href : sourceUrlFor(target.path)
  return `<img src="${escapeAttr(src)}" alt="${escapeAttr(alt)}" loading="lazy">`
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

function escapeAttr(value) {
  return escapeHtml(value).replaceAll("'", '&#39;')
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function slug(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'section'
}

// ---- browser wiring ----

function installStyles() {
  if (document.getElementById('jcb-markdown-style')) return
  const style = document.createElement('style')
  style.id = 'jcb-markdown-style'
  style.textContent = `
    body.jcb-markdown { margin: 0; background: #f7f7f5; color: #1c1c1a;
      font: 15px/1.65 ui-sans-serif, system-ui, "Segoe UI", sans-serif; }
    .jcb-doc { max-width: 980px; margin: 0 auto; padding: 32px 28px 80px; }
    .jcb-doc > header { display: flex; flex-wrap: wrap; gap: 10px; align-items: baseline;
      border-bottom: 1px solid #e2e0da; padding-bottom: 12px; margin-bottom: 26px; color: #6d6b66; }
    .jcb-doc > header a { color: #2f6feb; text-decoration: none; font-weight: 600; }
    .jcb-doc > header code { font-size: 12.5px; }
    .jcb-doc > header .hint { font-size: 12.5px; margin-left: auto; }
    .jcb-markdown-body h1 { font-size: 32px; line-height: 1.2; letter-spacing: -.02em; }
    .jcb-markdown-body h2 { margin-top: 34px; border-bottom: 1px solid #e2e0da; padding-bottom: 6px; }
    .jcb-markdown-body h3 { margin-top: 26px; }
    .jcb-markdown-body a { color: #2f6feb; }
    .jcb-markdown-body a[data-source] { text-decoration-style: dotted; }
    .jcb-markdown-body code { background: #f0efea; border-radius: 4px; padding: 1px 5px;
      font: 12.5px ui-monospace, Consolas, monospace; }
    .jcb-markdown-body pre { background: #fff; border: 1px solid #e2e0da; border-radius: 9px;
      padding: 14px 16px; overflow: auto; }
    .jcb-markdown-body pre code { background: none; padding: 0; font-size: 12.5px; }
    .jcb-markdown-body table { border-collapse: collapse; width: 100%; margin: 16px 0; }
    .jcb-markdown-body th, .jcb-markdown-body td { border: 1px solid #e2e0da; padding: 7px 9px;
      text-align: left; vertical-align: top; }
    .jcb-markdown-body th { background: #f0efea; }
    .jcb-markdown-body blockquote { margin: 16px 0; padding: 4px 0 4px 16px; border-left: 3px solid #d8d5cd;
      color: #55524c; }
    .jcb-markdown-body li { margin: 3px 0; }
    .jcb-markdown-body img { max-width: 100%; }
    .jcb-doc-error { background: #fff3f0; border: 1px solid #e7b7ac; border-radius: 9px; padding: 14px 16px; }
  `
  document.head.append(style)
}

function renderShell(path) {
  const shell = document.createElement('main')
  shell.className = 'jcb-doc'
  shell.innerHTML = `<header><a href="?" title="Back to the generated app">&larr; reports</a>`
    + ` <code>${escapeHtml(path)}</code>`
    + `<span class="hint">Ctrl+click text, bold, a paragraph or a source link to open the IDE</span></header>`
    + `<article class="jcb-markdown-body"></article>`
  return shell
}

let currentSourcePath = ''
let changeStream = null
let reloadTimer = null

async function renderFile(path) {
  installStyles()
  document.body.classList.add('jcb-markdown')
  const shell = renderShell(path)
  const article = shell.querySelector('article')
  currentSourcePath = path
  document.body.replaceChildren(shell)
  document.title = path.split('/').at(-1) || path
  try {
    const markdown = await fetchSource(path)
    article.innerHTML = renderMarkdown(markdown, path)
    article.addEventListener('click', onDocumentClick)
    scrollToHash()
    subscribeToChanges()
  } catch (error) {
    article.innerHTML = `<div class="jcb-doc-error"><strong>Could not read ${escapeHtml(path)}.</strong>`
      + `<p>${escapeHtml(error.message)}</p><p>Open the raw source instead: `
      + `<a href="${escapeAttr(sourceUrlFor(path))}">${escapeHtml(path)}</a></p></div>`
  }
}

async function renderSource(path) {
  installStyles()
  document.body.classList.add('jcb-markdown')
  const shell = renderShell(path)
  const article = shell.querySelector('article')
  currentSourcePath = path
  document.body.replaceChildren(shell)
  document.title = path.split('/').at(-1) || path
  article.innerHTML = '<p class="jcb-doc-error">Reading source…</p>'
  try {
    const source = await fetchSource(path)
    article.innerHTML = '<p class="jcb-source-note">This is not Markdown; showing the raw source.</p>'
      + `<pre class="jcb-source-code"><code>${escapeHtml(source)}</code></pre>`
  } catch (error) {
    article.innerHTML = `<div class="jcb-doc-error"><strong>Could not read ${escapeHtml(path)}.</strong>`
      + `<p>${escapeHtml(error.message)}</p></div>`
  }
}

function subscribeToChanges() {
  closeChangeStream()
  if (typeof EventSource !== 'function') return
  const token = tokenFromLocation()
  const url = `/api/v1/events${token ? `?token=${encodeURIComponent(token)}` : ''}`
  try {
    changeStream = new EventSource(url)
  } catch {
    return
  }
  changeStream.addEventListener('change', () => {
    // A project file changed. Re-render on the next tick so a save that emits several events is one
    // render; the Markdown can depend on more than its own bytes (examples, assets, configuration).
    if (!currentSourcePath) return
    clearTimeout(reloadTimer)
    reloadTimer = setTimeout(reloadCurrent, 80)
  })
}

function closeChangeStream() {
  if (changeStream) {
    changeStream.close()
    changeStream = null
  }
  clearTimeout(reloadTimer)
  reloadTimer = null
}

async function reloadCurrent() {
  const path = currentSourcePath
  const article = document.querySelector('.jcb-markdown-body')
  if (!path || !article) return
  const anchor = captureScrollAnchor()
  try {
    const markdown = await fetchSource(path)
    if (path !== currentSourcePath) return
    article.innerHTML = renderMarkdown(markdown, path)
    restoreScroll(anchor)
  } catch (error) {
    article.innerHTML = `<div class="jcb-doc-error"><strong>Could not refresh ${escapeHtml(path)}.</strong>`
      + `<p>${escapeHtml(error.message)}</p></div>`
    restoreScroll(anchor)
  }
}

/** Remember the source segment near the viewport, not just the pixel offset. */
function captureScrollAnchor() {
  const y = window.scrollY
  const sampleY = Math.min(140, window.innerHeight / 3)
  const element = document.elementFromPoint(24, sampleY)?.closest?.('[data-md-line]')
  return {
    y,
    line: element?.dataset.mdLine || null,
    offset: element ? element.getBoundingClientRect().top : 0,
  }
}

/** Restore the same source segment to its previous viewport position, falling back to the pixel offset. */
function restoreScroll(anchor) {
  requestAnimationFrame(() => {
    if (anchor.line) {
      const target = document.querySelector(`[data-md-line="${CSS.escape(anchor.line)}"]`)
      if (target) {
        window.scrollTo(0, window.scrollY + target.getBoundingClientRect().top - anchor.offset)
        return
      }
    }
    window.scrollTo(0, anchor.y)
  })
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', closeChangeStream, { once: true })
}

async function fetchSource(path) {
  let lastError = 'source request failed'
  for (const token of tokenCandidates()) {
    const response = await fetch(sourceUrlFor(path, token),
      { headers: token ? { 'X-WebView-Token': token } : {} })
    if (response.ok) {
      if (token) {
        activeToken = token
        try {
          localStorage.setItem('jcodebuddy.token', token)
        } catch {
          // storage may be unavailable; the active token still works for this page
        }
      }
      return response.text()
    }
    lastError = `source request failed: HTTP ${response.status}`
    if (response.status !== 401 && response.status !== 403) break
  }
  throw new Error(lastError)
}

function onDocumentClick(event) {
  const modified = event.ctrlKey || event.metaKey
  const link = event.target.closest?.('a')
  if (link) {
    if (!link.dataset.source) return
    // A Markdown link is app navigation on a normal click; a source link is IDE navigation. Letting a
    // normal click on `/source/...` navigate the JCEF panel is what produced the ERR_ABORTED entries:
    // there is no source viewer in the app, and the panel should not try to become one.
    if (link.dataset.doc && !modified) {
      // In hash mode a normal click does not load a document. Render the target here and let the
      // history entry move; this is the same in-place path the host's navigation hook uses.
      if (location.hash && new URLSearchParams(location.hash.replace(/^#/, '')).has('openFile')) {
        event.preventDefault()
        const target = normalizePath(link.dataset.source)
        if (target) {
          renderTarget(target)
          history.pushState({}, '', appUrlFor(target, link.dataset.fragment || ''))
        }
      }
      return
    }
    event.preventDefault()
    openSource(link.dataset.source, link.dataset.fragment || '')
    return
  }
  if (!modified) return
  const segment = event.target.closest?.('[data-md-line]')
  if (!segment || !currentSourcePath) return
  event.preventDefault()
  openSource(currentSourcePath, `L${segment.dataset.mdLine}`)
}

async function openSource(path, fragment) {
  let line = lineFromFragment(fragment)
  if (!line && fragment) {
    try {
      line = { line: approximateLine(await fetchSource(path), fragment), column: 1 }
    } catch {
      line = { line: 1, column: 1 }
    }
  }
  const at = line || { line: 1, column: 1 }
  if (typeof window.openFile === 'function') {
    window.openFile(path, at.line, at.column || 1)
  } else {
    renderSource(path)
  }
}

function scrollToHash() {
  if (!location.hash) return
  let id
  try {
    id = decodeURIComponent(location.hash.slice(1))
  } catch {
    id = location.hash.slice(1)
  }
  document.getElementById(id)?.scrollIntoView({ block: 'start' })
}

function renderTarget(path) {
  if (isMarkdownPath(path)) {
    renderFile(path)
  } else {
    renderSource(path)
  }
}

function installNavigationHook() {
  if (typeof window === 'undefined' || typeof window.jcodebuddyNavIntercept === 'function') return
  // The host calls this only when it is already showing this app. A true result means the app has
  // rendered the target and the host should change the URL without loading a document.
  window.jcodebuddyNavIntercept = (target) => {
    if (!target || target.kind !== 'file') return false
    const path = normalizePath(target.path)
    if (!path) return false
    renderTarget(path)
    return true
  }
}

function boot() {
  tokenFromLocation()
  const openFile = readOpenFile()
  if (!openFile) return
  const path = normalizePath(openFile)
  if (!path) return
  if (path.startsWith(APP_PREFIX) && path.endsWith('.html')) {
    location.replace(path.slice(APP_PREFIX.length))
    return
  }
  if (isMarkdownPath(path)) {
    renderFile(path)
    return
  }
  // Anything else is shown as source inside the app. Navigating the panel to the raw `/source/` route
  // was an ERR_ABORTED card in JCEF, and it is not a page the tool window can restore.
  renderSource(path)
}

if (typeof document !== 'undefined') {
  installNavigationHook()
  // Hash routing changes location without a document load; render the routed document here. The same
  // handler covers Back/Forward, which fire popstate as well as hashchange.
  if (typeof window !== 'undefined') {
    const onLocationChange = () => {
      const path = readOpenFile()
      if (path) renderTarget(normalizePath(path))
    }
    window.addEventListener('hashchange', onLocationChange)
    window.addEventListener('popstate', onLocationChange)
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true })
  } else {
    boot()
  }
}
