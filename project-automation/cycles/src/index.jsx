import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'

/** The dependency-cycle report: a jsx6 view computed from the current class indexes. */
const $query = signal('')
const $selected = signal(0)
const $error = signal('')
const $model = signal(null)
const $loading = signal(false)
const $checking = signal(false)
const $stale = signal(false)

function normalizeType(type) {
  if (!type) return ''
  return String(type).trim()
    .replace(/<.*>/g, '')
    .replace(/\[\]/g, '')
    .replace(/^@[\w.]+\s*/, '')
    .replace(/\s+/g, ' ')
}

async function fetchIndex(url) {
  const classesUrl = new URL(url, location.href)
  const classesResponse = await fetch(classesUrl, { cache: 'no-store' })
  if (!classesResponse.ok) throw new Error(`${classesUrl.pathname}: HTTP ${classesResponse.status}`)
  const index = await classesResponse.json()
  if (!index.classes || typeof index.classes !== 'object') {
    throw new Error(`${classesUrl.pathname} has no classes object`)
  }
  const importsUrl = new URL('imports.json', classesUrl)
  let imports = {}
  try {
    const importsResponse = await fetch(importsUrl, { cache: 'no-store' })
    if (importsResponse.ok) imports = (await importsResponse.json()).imports ?? {}
  } catch {
    // imports.json is optional; sources can still be shown without import edges.
  }
  const moduleDir = new URL('../../', classesUrl)
  const moduleName = index.module || moduleDir.pathname.split('/').filter(Boolean).pop() || 'module'
  return { index, imports, moduleDir, moduleName }
}

function buildModel(models, title) {
  const nodes = new Map()
  const byFile = new Map()
  for (const model of models) {
    for (const [fqn, entry] of Object.entries(model.index.classes)) {
      const node = {
        fqn,
        module: model.moduleName,
        moduleDir: model.moduleDir,
        path: entry.path || '',
        line: entry.line || 0,
        kind: entry.kind || 'type',
        package: fqn.includes('.') ? fqn.slice(0, fqn.lastIndexOf('.')) : '',
        imports: model.imports[entry.path] || [],
        entry,
      }
      nodes.set(fqn, node)
      const key = `${model.moduleName}|${entry.path}`
      if (!byFile.has(key)) byFile.set(key, [])
      byFile.get(key).push(node)
    }
  }

  const edges = new Map([...nodes.keys()].map((fqn) => [fqn, new Set()]))
  const resolveName = (name, from) => {
    const type = normalizeType(name)
    if (!type) return null
    if (nodes.has(type)) return type
    const simple = type.includes('.') ? type.slice(type.lastIndexOf('.') + 1) : type
    for (const imported of from.imports) {
      const match = /^import\s+(?:static\s+)?([\w.]+)\s*;/.exec(imported)
      if (!match) continue
      if (match[1] === type || match[1].endsWith('.' + simple)) return nodes.has(match[1]) ? match[1] : null
    }
    const samePackage = `${from.package}.${simple}`
    return nodes.has(samePackage) ? samePackage : null
  }

  for (const fileNodes of byFile.values()) {
    const from = fileNodes[0]
    for (const imported of from.imports) {
      const match = /^import\s+(?:static\s+)?([\w.]+)\s*;/.exec(imported)
      if (match && nodes.has(match[1])) {
        for (const node of fileNodes) if (node.fqn !== match[1]) edges.get(node.fqn).add(match[1])
      }
    }
    for (const node of fileNodes) {
      for (const relation of node.entry.relations || []) {
        const target = resolveName(relation.name, node)
        if (target && target !== node.fqn) edges.get(node.fqn).add(target)
      }
      for (const member of node.entry.members || []) {
        const target = resolveName(member.type, node)
        if (target && target !== node.fqn) edges.get(node.fqn).add(target)
        for (const parameter of member.parameters || []) {
          const parameterTarget = resolveName(parameter.type, node)
          if (parameterTarget && parameterTarget !== node.fqn) edges.get(node.fqn).add(parameterTarget)
        }
      }
    }
  }

  let index = 0
  const indexes = new Map()
  const low = new Map()
  const onStack = new Set()
  const stack = []
  const components = []
  const visit = (node) => {
    indexes.set(node, index)
    low.set(node, index)
    index++
    stack.push(node)
    onStack.add(node)
    for (const target of edges.get(node) || []) {
      if (!nodes.has(target)) continue
      if (!indexes.has(target)) {
        visit(target)
        low.set(node, Math.min(low.get(node), low.get(target)))
      } else if (onStack.has(target)) {
        low.set(node, Math.min(low.get(node), indexes.get(target)))
      }
    }
    if (low.get(node) === indexes.get(node)) {
      const component = []
      let current
      do {
        current = stack.pop()
        onStack.delete(current)
        component.push(current)
      } while (current !== node)
      components.push(component)
    }
  }
  for (const node of nodes.keys()) if (!indexes.has(node)) visit(node)

  const cycles = components
    .filter((component) => component.length > 1)
    .map((component) => ({
      nodes: component.sort().map((fqn) => {
        const node = nodes.get(fqn)
        return {
          fqn,
          module: node.module,
          kind: node.kind,
          line: node.line,
          source: new URL(node.path, node.moduleDir).pathname,
        }
      }),
    }))
    .sort((left, right) => right.nodes.length - left.nodes.length)

  const fanIn = new Map([...nodes.keys()].map((fqn) => [fqn, 0]))
  for (const targets of edges.values()) for (const target of targets) fanIn.set(target, (fanIn.get(target) || 0) + 1)
  const rank = (map) => [...map].sort((left, right) => right[1] - left[1]).slice(0, 20)
    .map(([fqn, count]) => ({ fqn, count }))
  return {
    title,
    summary: {
      types: nodes.size,
      edges: [...edges.values()].reduce((total, targets) => total + targets.size, 0),
      cycles: cycles.length,
      largest: cycles[0]?.nodes.length ?? 0,
    },
    cycles,
    fanIn: rank(fanIn),
    fanOut: rank(new Map([...edges].map(([fqn, targets]) => [fqn, targets.size]))),
  }
}

function cycleSignature(model) {
  return model.cycles.map((cycle) => cycle.nodes.map((node) => node.fqn).join(',')).join('|')
}

async function load(apply = true) {
  if ($loading() || $checking()) return
  if (apply) $loading(true)
  else $checking(true)
  $error('')
  render()
  try {
    const params = new URLSearchParams(location.search)
    const indexes = params.getAll('index').length
      ? params.getAll('index')
      : window.CYCLES_INDEXES || []
    if (indexes.length === 0) throw new Error('no class indexes configured')
    const models = await Promise.all(indexes.map(fetchIndex))
    const next = buildModel(models, window.CYCLES_TITLE || 'Dependency cycles')
    if (apply) {
      $model(next)
      $stale(false)
      document.title = next.title
    } else if ($model()) {
      $stale(cycleSignature(next) !== cycleSignature($model()))
    }
  } catch (error) {
    $error(String(error))
  } finally {
    $loading(false)
    $checking(false)
    render()
  }
}

function render() {
  const page = document.getElementById('page')
  page.replaceChildren()
  insert(page, <App />)
}

function focusFilter() {
  requestAnimationFrame(() => {
    const input = document.getElementById('filter')
    if (input) {
      input.focus()
      input.setSelectionRange(input.value.length, input.value.length)
    }
  })
}

function setQuery(value) {
  $query(value)
  render()
  focusFilter()
}

function matches(cycle, query) {
  if (!query) return true
  const needle = query.toLowerCase()
  return cycle.nodes.some((node) => `${node.fqn} ${node.module} ${node.source}`.toLowerCase().includes(needle))
}

function selectCycle(index) {
  $selected(index)
  const model = $model()
  if (model?.cycles[index]) {
    try {
      history.replaceState(null, '', `#cycle=${index + 1}`)
    } catch {
      // The page still works when history is unavailable.
    }
  }
  render()
}

function App() {
  const model = $model()
  if (!model) {
    return $error()
      ? <main><p class="error">The cycle report could not load: {$error()}</p></main>
      : <main><p class="muted">Loading dependency cycles…</p></main>
  }
  const selected = Math.min($selected(), Math.max(0, model.cycles.length - 1))
  const query = $query()
  const cycles = model.cycles
    .map((cycle, index) => ({ cycle, index }))
    .filter(({ cycle }) => matches(cycle, query))
  const cycle = model.cycles[selected]

  return (
    <main>
      <div class="head">
        <h1>{model.title}</h1>
        <button type="button" class={'refresh' + ($stale() ? ' attention' : '')}
          disabled={$loading() || $checking()}
          title={$stale() ? 'The metadata contains different cycles; click to apply them' : 'Re-read metadata'}
          onclick={() => load(true)}>
          {$loading() ? 'Refreshing…' : $checking() ? 'Checking metadata…'
            : $stale() ? 'Cycles changed — refresh' : 'Refresh metadata'}
        </button>
      </div>
      {$error() ? <p class="error">Refresh failed: {$error()} — showing the previous metadata.</p> : null}
      <p class="lead">Class-level strongly connected components from the committed class index. A cycle means these types cannot be compiled in separate batches without stubs or an architectural break.</p>
      <section class="summary">
        <div class="card"><b>{model.summary.types}</b><span>types</span></div>
        <div class="card"><b>{model.summary.edges}</b><span>resolved edges</span></div>
        <div class="card"><b>{model.summary.cycles}</b><span>cycles</span></div>
        <div class="card"><b>{model.summary.largest}</b><span>largest cycle</span></div>
      </section>
      <section class="layout">
        <div class="panel">
          <input id="filter" value={query} placeholder="filter by type, package or module…"
            oninput={(event) => setQuery(event.target.value)} />
          <div class="cycle-list">
            {cycles.length === 0
              ? <p class="muted">No matching cycles.</p>
              : cycles.map(({ cycle: item, index }) => (
                  <button type="button" class={'cycle' + (index === selected ? ' selected' : '')}
                    onclick={() => selectCycle(index)}>
                    <b>{item.nodes.length} type{item.nodes.length === 1 ? '' : 's'}</b>
                    <small>{item.nodes.slice(0, 2).map((node) => node.fqn).join(', ')}</small>
                  </button>
                ))}
          </div>
        </div>
        <div class="panel">
          {cycle
            ? <>
                <h3>Cycle {selected + 1} — {cycle.nodes.length} types</h3>
                {cycle.nodes.map((node) => (
                  <div class="cycle-node">
                    <a href={node.source} data-open={node.source}>{node.fqn}</a>
                    <span class="kind"> · {node.module} · {node.kind} · line {node.line}</span>
                  </div>
                ))}
              </>
            : <p class="muted">Select a cycle.</p>}
        </div>
      </section>
      <section class="hubs">
        <div class="panel">
          <h3>Highest fan-in</h3>
          {model.fanIn.map((entry) => (
            <div class="hub">
              <a href="#" onclick={(event) => { event.preventDefault(); setQuery(entry.fqn) }}>{entry.fqn}</a>
              <b>{entry.count} in</b>
            </div>
          ))}
        </div>
        <div class="panel">
          <h3>Highest fan-out</h3>
          {model.fanOut.map((entry) => (
            <div class="hub">
              <a href="#" onclick={(event) => { event.preventDefault(); setQuery(entry.fqn) }}>{entry.fqn}</a>
              <b>{entry.count} out</b>
            </div>
          ))}
        </div>
      </section>
    </main>
  )
}

insert(document.getElementById('page'), <p class="muted">Loading dependency cycles…</p>)
load(true)
window.addEventListener('jcodebuddy:metadata-changed', () => load(false))
