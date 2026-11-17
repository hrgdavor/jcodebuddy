import { insert } from '@jsx6/jsx6'
import { filterTypes, selectedInitial } from './model.js'
import { bridgeLabel, copyText, openLocation } from './bridge.js'

/**
 * The entity browser page.
 *
 * <p>It is a reader of `.jcodebuddy/index/classes.json` (DEC-029): it parses no Java and derives no
 * member. The build has already resolved every type name the index could resolve, and verified every
 * source line the index recorded; this file only decides what to show.</p>
 *
 * <p>The model arrives as `./model.json` over HTTP. That is deliberate: the page is served by the
 * JetBrains WebView Explorer (or `serve.js`) rather than opened as a `file://` document, so the bundle
 * and the model stay separate files and a model-only rebuild does not need a new bundle. A page opened
 * from `file://` cannot fetch a sibling JSON (a null origin), so it says so instead of rendering
 * nothing.</p>
 *
 * <p>State is a plain object rather than a tree of signals because the page replaces whole regions on
 * every change (a keystroke filters the list, a click opens one entity). jsx6 builds each region's
 * DOM, which is the part of the stack this page needs; every click still goes through the frozen
 * navigation contract.</p>
 */

function renderLoadError(error) {
  document.title = 'Entity browser — model not loaded'
  const box = document.createElement('div')
  box.style.cssText = 'font:14px/1.6 system-ui,sans-serif;max-width:720px;margin:12vh auto;padding:0 24px;color:#1c1c1a'
  const title = document.createElement('h1')
  title.textContent = 'The entity model could not be loaded'
  const reason = document.createElement('p')
  reason.textContent = `Fetching ./model.json failed: ${error instanceof Error ? error.message : String(error)}`
  const why = document.createElement('p')
  why.textContent = 'This page fetches its JSON model, which a file:// page is not allowed to do. '
    + 'Open it through the JetBrains WebView Explorer (right-click the HTML file in the Project view), '
    + 'or serve its directory: bun entity-browser/src_build/serve.js --dir <page directory>.'
  box.append(title, reason, why)
  document.body.replaceChildren(box)
}

async function loadModel() {
  try {
    const response = await fetch('./model.json', { cache: 'no-store' })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} for ${response.url}`)
    }
    return await response.json()
  } catch (error) {
    renderLoadError(error)
    throw error
  }
}

const model = await loadModel()

// The one-time capability segment of a plugin-served URL. It authenticates the host's `/open` route
// for a page opened in an ordinary browser, where no `window.openFile` was injected.
const pageToken = (() => {
  const match = String(location.pathname || '').match(/\/page\/([^/]+)\//)
  return match ? decodeURIComponent(match[1]) : ''
})()

// Which port the page's source links talk to. The injected `window.openFile` wins in the IDE; a page
// served by the plugin can also discover the host's actual (possibly ephemeral) port from /health on
// its own origin. A standalone `serve.js` has no /health, so the page honestly falls back to clipboard.
let bridgePort = model.bridgePort

async function detectBridgePort() {
  if (typeof window.openFile === 'function') {
    return
  }
  try {
    const response = await fetch('/health', { cache: 'no-store' })
    if (!response.ok) {
      bridgePort = 0
      return
    }
    const health = await response.json()
    bridgePort = Number.isInteger(health.port) && health.port > 0 ? health.port : 0
  } catch {
    bridgePort = 0
  }
}

await detectBridgePort()

const INLINE_VALUE_LIMIT = 40

function hashFqn() {
  try {
    const raw = decodeURIComponent((location.hash || '').replace(/^#/, ''))
    return raw || null
  } catch {
    return null
  }
}

const state = {
  query: '',
  scope: 'records',
  selected: selectedInitial(model, hashFqn()),
  trail: [],
}

function updateHash(fqn) {
  const next = `#${encodeURIComponent(fqn)}`
  try {
    if (location.hash !== next) {
      history.replaceState(null, '', next)
    }
  } catch {
    location.hash = encodeURIComponent(fqn)
  }
}

function typeCountLabel(row) {
  if (row.kind === 'record') {
    return `${row.components.length} component${row.components.length === 1 ? '' : 's'}`
  }
  if (row.kind === 'enum') {
    return `${row.enumConstants.length} value${row.enumConstants.length === 1 ? '' : 's'}`
  }
  const members = row.fields.length + row.methods.length + row.constructors.length
  return `${members} member${members === 1 ? '' : 's'}`
}

/** The type expression with each locally resolved name as a button that drills down. */
function TypeText({ type, onOpen }) {
  return (
    <code class="type" title={type.text}>
      {type.parts.map((part, index) =>
        part.target ? (
          <button
            key={`${part.text}-${index}`}
            type="button"
            class="type-link"
            title={`Open ${part.target}`}
            onclick={() => onOpen(part.target)}
          >
            {part.display ?? part.text}
          </button>
        ) : part.ambiguous ? (
          <span class="type-ambiguous" title="This simple name matches more than one indexed type; qualify it in the source to link it.">
            {part.display ?? part.text}
          </span>
        ) : (
          <span>{part.display ?? part.text}</span>
        ),
      )}
    </code>
  )
}

function Annotations({ annotations }) {
  // Always a host element, never null/"" — a function component returning a primitive is inserted as
  // the string `[object Object]` by the current jsx6 runtime. An empty span is invisible.
  return (
    <span class="annotations">
      {annotations?.length
        ? annotations.map((annotation) => (
          <span class="annotation" title="declared on this member">{annotation.text}</span>
        ))
        : null}
    </span>
  )
}

/** One line of the record's structure: the component, its type, its inline enum values, its line. */
function ComponentRow({ component, index, row, onOpen, onSource }) {
  return (
    <tr>
      <td class="num">{index + 1}</td>
      <td class="component">
        <code class="member-name">{component.name}</code>
        <Annotations annotations={component.annotations} />
      </td>
      <td class="type-cell">
        <TypeText type={component.type} onOpen={onOpen} />
        {component.enum?.inline ? (
          <span class="enum-inline" title={`${component.enum.fqn} — all ${component.enum.constants.length} values`}>
            {' ('}
            {component.enum.constants.join(', ')}
            {')'}
          </span>
        ) : component.enum ? (
          <button
            type="button"
            class="enum-more"
            title={`Open ${component.enum.fqn}`}
            onclick={() => onOpen(component.enum.fqn)}
          >
            {component.enum.constants.length} values →
          </button>
        ) : null}
      </td>
      <td class="where">
        {component.sourceLine > 0 ? (
          <button
            type="button"
            class="linelink"
            title={`Open ${row.path}:${component.sourceLine}`}
            data-open={row.path}
            data-line={component.sourceLine}
            data-member={component.name}
            data-role="record-component"
            onclick={() => onSource({ path: row.path, line: component.sourceLine, member: component.name, role: 'record-component' })}
          >
            :{component.sourceLine}
          </button>
        ) : (
          <span class="muted">no line</span>
        )}
      </td>
    </tr>
  )
}

function MemberSection({ title, members, row, onOpen, onSource }) {
  return (
    <section class="members">
      <h2>
        {title} <span class="count">{members.length}</span>
      </h2>
      <table class="member-table">
        <tbody>
          {members.map((member) => (
            <tr>
              <td class="member-name-cell">
                <code class="member-name">{member.signature}</code>
                <Annotations annotations={member.annotations} />
              </td>
              <td class="member-type-cell">
                <TypeText type={member.type} onOpen={onOpen} />
              </td>
              <td class="where">
                {member.sourceLine > 0 ? (
                  <button
                    type="button"
                    class="linelink"
                    title={`Open ${row.path}:${member.sourceLine}`}
                    data-open={row.path}
                    data-line={member.sourceLine}
                    data-member={member.name}
                    data-role={member.kind}
                    onclick={() => onSource({ path: row.path, line: member.sourceLine, member: member.name, role: member.kind })}
                  >
                    :{member.sourceLine}
                  </button>
                ) : (
                  <span class="muted">no line</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

/** The enum answer in one line, exactly where the reader is looking. */
function EnumValues({ row, onSource }) {
  if (!row.enumConstants.length) {
    return <p class="note">No enum constants are recorded in the class index.</p>
  }
  const shown = row.enumConstants.slice(0, INLINE_VALUE_LIMIT)
  return (
    <section class="enum-values-block">
      <h2>Values <span class="count">{row.enumConstants.length}</span></h2>
      <div class="enum-values">
        <code class="enum-name">{row.name}</code>
        <span class="enum-paren">(</span>
        {shown.map((constant, index) => (
          <span class="enum-value">
            {constant.name}
            {index < shown.length - 1 ? <span class="comma">, </span> : null}
          </span>
        ))}
        {row.enumConstants.length > shown.length ? (
          <span class="muted"> … +{row.enumConstants.length - shown.length} more</span>
        ) : null}
        <span class="enum-paren">)</span>
      </div>
      <table class="member-table enum-table">
        <thead>
          <tr>
            <th>value</th>
            <th>line</th>
          </tr>
        </thead>
        <tbody>
          {row.enumConstants.map((constant) => (
            <tr>
              <td>
                <code class="member-name">{constant.name}</code>
                <Annotations annotations={constant.annotations} />
              </td>
              <td class="where">
                {constant.sourceLine > 0 ? (
                  <button
                    type="button"
                    class="linelink"
                    title={`Open ${row.path}:${constant.sourceLine}`}
                    onclick={() => onSource({ path: row.path, line: constant.sourceLine, member: constant.name, role: 'enum-constant' })}
                  >
                    :{constant.sourceLine}
                  </button>
                ) : (
                  <span class="muted">no line</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Relations({ row, onOpen }) {
  // A host element even when empty, for the same reason as Annotations above.
  return (
    <div class="relations">
      {row.relations.map((relation) => (
        <span class="relation">
          <span class="rel-kind">{relation.kind}</span>{' '}
          {relation.target ? (
            <button type="button" class="type-link" onclick={() => onOpen(relation.target)}>
              {relation.name}
            </button>
          ) : (
            <code>{relation.name}</code>
          )}
        </span>
      ))}
    </div>
  )
}

function Dependencies({ row, onOpen }) {
  return (
    <section class="deps">
      <h2>
        Depends on <span class="count">{String(row.dependencies.length)}</span>
      </h2>
      {row.dependencies.length ? (
        <div class="chips">
          {row.dependencies.map((dependency) => (
            <button
              type="button"
              class={`dep dep-${dependency.kind}`}
              title={`Open ${dependency.fqn}`}
              onclick={() => onOpen(dependency.fqn)}
            >
              <span class="dep-name">
                {dependency.name}
                {dependency.enum?.inline ? (
                  <span class="dep-values">
                    {' ('}
                    {dependency.enum.constants.join(', ')}
                    {')'}
                  </span>
                ) : null}
              </span>
              <span class="dep-kind">
                {dependency.enum && !dependency.enum.inline
                  ? `${dependency.enum.constants.length} values`
                  : dependency.kind === 'record' && dependency.componentCount
                    ? `record · ${dependency.componentCount} components`
                    : dependency.kind}
              </span>
            </button>
          ))}
        </div>
      ) : (
        <p class="note">No other indexed type is reached from this declaration.</p>
      )}
      {row.externalDependencies.length ? (
        <div class="external">
          <span class="external-label">external</span>
          {row.externalDependencies.map((name) => (
            <code class="external-type" title="Not in the indexed modules">{name}</code>
          ))}
        </div>
      ) : null}
    </section>
  )
}

function Header({ row, onOpen, onSource }) {
  return (
    <header class="detail-head">
      <div class="kindline">
        <span class={`kind kind-${row.kind}`}>{row.kind}</span>
        {row.generated ? <span class="badge generated">generated</span> : null}
        <span class="package">{row.package}</span>
        <span class="module">module {row.module}</span>
      </div>
      <h1>{row.name}</h1>
      <div class="fqn">
        <code>{row.fqn}</code>
      </div>
      <div class="head-actions">
        {row.sourceAvailable && row.sourceLine > 0 ? (
          <button
            type="button"
            class="btn primary"
            data-open={row.path}
            data-line={row.sourceLine}
            data-member={row.simpleName}
            data-role="type"
            onclick={() => onSource({ path: row.path, line: row.sourceLine, member: row.simpleName, role: 'type' })}
          >
            Open source
          </button>
        ) : (
          <span class="muted">source line not verified</span>
        )}
        <button
          type="button"
          class="btn"
          title="Copy the fully qualified name"
          onclick={() => copyName(row.fqn)}
        >
          Copy FQN
        </button>
        <code class="source-path" title={row.path}>
          {row.path}
          {row.sourceLine > 0 ? `:${row.sourceLine}` : ''}
        </code>
      </div>
      <Relations row={row} onOpen={onOpen} />
    </header>
  )
}

function RecordDetail({ row, onOpen, onSource }) {
  return (
    <section class="structure">
      <h2>
        Structure <span class="count">{row.components.length}</span>
      </h2>
      {row.components.length ? (
        <table class="structure-table">
          <thead>
            <tr>
              <th class="num">#</th>
              <th>component</th>
              <th>type</th>
              <th class="where">line</th>
            </tr>
          </thead>
          <tbody>
            {row.components.map((component, index) => (
              <ComponentRow
                component={component}
                index={index}
                row={row}
                onOpen={onOpen}
                onSource={onSource}
              />
            ))}
          </tbody>
        </table>
      ) : (
        <p class="note">This record declares no components.</p>
      )}
    </section>
  )
}

function OtherDetail({ row, onOpen, onSource }) {
  return (
    <>
      {row.kind === 'enum' ? <EnumValues row={row} onSource={onSource} /> : null}
      {row.fields.length ? <MemberSection title="Fields" members={row.fields} row={row} onOpen={onOpen} onSource={onSource} /> : null}
      {row.methods.length ? <MemberSection title="Methods" members={row.methods} row={row} onOpen={onOpen} onSource={onSource} /> : null}
      {row.constructors.length ? <MemberSection title="Constructors" members={row.constructors} row={row} onOpen={onOpen} onSource={onSource} /> : null}
      {row.kind !== 'enum' && row.nested.length ? (
        <MemberSection title="Nested types" members={row.nested} row={row} onOpen={onOpen} onSource={onSource} />
      ) : null}
      {!row.fields.length && !row.methods.length && !row.constructors.length && row.kind !== 'enum' ? (
        <p class="note">No members are recorded in the class index.</p>
      ) : null}
    </>
  )
}

function Detail({ row, trail, onBack, onOpen, onSource }) {
  return (
    <div class="detail">
      <nav class="crumbs" aria-label="Drill-down path">
        <button
          type="button"
          class={`back${trail.length === 0 ? ' disabled' : ''}`}
          title="Back one step"
          onclick={onBack}
        >
          ← Back
        </button>
        <span class="crumb-root">{row.kind === 'record' ? 'records' : 'types'}</span>
        {trail.map((fqn, index) => (
          <span class="crumb">
            <span class="crumb-sep">/</span>
            <button type="button" onclick={() => onOpen(fqn, { jump: true, trailIndex: index })}>
              {model.types[fqn]?.name ?? fqn}
            </button>
          </span>
        ))}
        <span class="crumb current">
          <span class="crumb-sep">/</span>
          <span>{row.name}</span>
        </span>
      </nav>
      <Header row={row} onOpen={onOpen} onSource={onSource} />
      {row.kind === 'record' ? (
        <RecordDetail row={row} onOpen={onOpen} onSource={onSource} />
      ) : (
        <OtherDetail row={row} onOpen={onOpen} onSource={onSource} />
      )}
      <Dependencies row={row} onOpen={onOpen} />
    </div>
  )
}

function TypeList({ rows, selected, onSelect }) {
  if (!rows.length) {
    return <div class="empty">No type matches.</div>
  }
  return (
    <ul class="type-list">
      {rows.map((row) => (
        <li>
          <button
            type="button"
            class={`type-row${row.fqn === selected ? ' selected' : ''}`}
            onclick={() => onSelect(row.fqn)}
          >
            <span class="type-row-name">{row.name}</span>
            <span class="type-row-meta">
              <span class={`mini-kind mini-${row.kind}`}>{row.kind}</span>
              <span>{typeCountLabel(row)}</span>
            </span>
            <span class="type-row-package">{row.package}</span>
          </button>
        </li>
      ))}
    </ul>
  )
}

function Shell() {
  return (
    <div class="shell">
      <aside class="sidebar">
        <div class="brand">
          <h1>Entity browser</h1>
          <div class="stats">
            <b>{model.stats.records}</b> records
            <span class="dot">·</span>
            <b>{model.stats.types}</b> types
            <span class="dot">·</span>
            <b>{model.stats.modules}</b> module{model.stats.modules === 1 ? '' : 's'}
          </div>
        </div>
        <div class="search">
          <input
            id="search"
            type="search"
            autocomplete="off"
            spellcheck="false"
            placeholder="Search records, components, enum values…"
            aria-label="Search records"
          />
          <kbd>/</kbd>
        </div>
        <div class="scope" role="group" aria-label="Search scope">
          <button id="scope-records" type="button" class="scope-btn active">records</button>
          <button id="scope-all" type="button" class="scope-btn">all types</button>
        </div>
        <div id="result-count" class="result-count"></div>
        <div id="results" class="results"></div>
        <footer class="sidebar-foot">
          <span id="bridge-pill" class="bridge-pill">{bridgeLabel(bridgePort)}</span>
          <span class="build">built from the class index</span>
        </footer>
      </aside>
      <main id="detail" class="detail-pane" aria-live="polite"></main>
      <div id="notice" class="notice" role="status"></div>
    </div>
  )
}

insert(document.body, <Shell />)

// The tab / JCEF tool-window title is the build's --title, not a constant in the shell.
document.title = model.title

const results = document.getElementById('results')
const detail = document.getElementById('detail')
const search = document.getElementById('search')
const count = document.getElementById('result-count')
const notice = document.getElementById('notice')
const scopeRecords = document.getElementById('scope-records')
const scopeAll = document.getElementById('scope-all')

let noticeTimer = null

function notify(text) {
  if (!text) {
    return
  }
  notice.textContent = text
  notice.classList.add('show')
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => notice.classList.remove('show'), 3200)
}

async function copyName(text) {
  const ok = await copyText(text)
  notify(ok ? `Copied ${text}` : `Could not copy ${text}`)
}

function renderList() {
  const rows = filterTypes(model, state.query, state.scope)
  count.textContent = state.query.trim()
    ? `${rows.length} of ${state.scope === 'records' ? model.stats.records : model.stats.types} ${state.scope === 'records' ? 'records' : 'types'}`
    : `${rows.length} ${state.scope === 'records' ? 'records' : 'types'}`
  results.replaceChildren(
    <TypeList
      rows={rows}
      selected={state.selected}
      onSelect={(fqn) => select(fqn, { push: false })}
    />,
  )
}

function renderDetail() {
  const row = state.selected ? model.types[state.selected] : null
  if (!row) {
    detail.replaceChildren(<div class="empty">Select a type.</div>)
    return
  }
  detail.replaceChildren(
    <Detail
      row={row}
      trail={state.trail}
      onBack={() => back()}
      onOpen={(fqn, options) => select(fqn, options ?? { push: true })}
      onSource={(target) => {
        const result = openLocation(target, bridgePort, {
          linkBase: model.linkBase,
          href: location.href,
          pageToken,
        })
        notify(result.message)
      }}
    />,
  )
}

function updateScopeButtons() {
  scopeRecords.classList.toggle('active', state.scope === 'records')
  scopeAll.classList.toggle('active', state.scope === 'all')
}

function select(fqn, { push = true, jump = false, trailIndex } = {}) {
  if (!model.types[fqn]) {
    return
  }
  if (jump && typeof trailIndex === 'number') {
    state.trail = state.trail.slice(0, trailIndex)
  } else if (push && state.selected && state.selected !== fqn) {
    state.trail = [...state.trail, state.selected]
  } else if (!push) {
    state.trail = []
  }
  state.selected = fqn
  updateHash(fqn)
  renderDetail()
  renderList()
}

function back() {
  if (!state.trail.length) {
    return
  }
  const trail = [...state.trail]
  const previous = trail.pop()
  state.trail = trail
  state.selected = previous
  updateHash(previous)
  renderDetail()
  renderList()
}

search.addEventListener('input', () => {
  state.query = search.value
  renderList()
})

search.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    const rows = filterTypes(model, state.query, state.scope)
    if (rows[0]) {
      select(rows[0].fqn, { push: false })
    }
  } else if (event.key === 'Escape') {
    search.value = ''
    state.query = ''
    renderList()
  }
})

scopeRecords.addEventListener('click', () => {
  state.scope = 'records'
  updateScopeButtons()
  renderList()
})

scopeAll.addEventListener('click', () => {
  state.scope = 'all'
  updateScopeButtons()
  renderList()
})

document.addEventListener('keydown', (event) => {
  const active = document.activeElement?.tagName
  if (event.key === '/' && active !== 'INPUT' && active !== 'TEXTAREA') {
    event.preventDefault()
    search.focus()
    search.select()
  }
})

window.addEventListener('hashchange', () => {
  const fqn = hashFqn()
  if (fqn && model.types[fqn] && fqn !== state.selected) {
    state.trail = []
    state.selected = fqn
    renderDetail()
    renderList()
  }
})

// The tool window may restore a page without a hash; keep the sidebar and the detail in step.
if (state.selected) {
  updateHash(state.selected)
}
renderList()
renderDetail()
