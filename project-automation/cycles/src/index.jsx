import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'
import { NodeEditor } from '@jsx6/nodditor'

void NodeEditor

/** The dependency-cycle report: a jsx6 view computed from the current class indexes. */
const initialMode = (() => {
  try {
    const mode = new URLSearchParams(location.search).get('mode')
    return ['classes', 'packages', 'module'].includes(mode) ? mode : 'classes'
  } catch {
    return 'classes'
  }
})()

const initialNode = (() => {
  try {
    return new URLSearchParams(location.search).get('node') || null
  } catch {
    return null
  }
})()

const $query = signal('')
const $selected = signal(0)
const $error = signal('')
const $model = signal(null)
const $raw = signal(null)
const $mode = signal(initialMode)
const $view = signal(initialNode ? 'dependencies' : 'cycles')
const $selectedNode = signal(initialNode)
const $threshold = signal(10)
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

const pageToken = (() => {
  try {
    return localStorage.getItem('jcodebuddy.token')
      || new URLSearchParams(location.search).get('token')
      || ''
  } catch {
    return ''
  }
})()

function encodePath(path) {
  return path.replace(/\\/g, '/').split('/').map((segment) => encodeURIComponent(segment)).join('/')
}

/** Metadata and sources live in the host's read-only /source namespace, not under /app. */
function sourceUrlFor(path) {
  const query = pageToken ? `?token=${encodeURIComponent(pageToken)}` : ''
  return `/source/${encodePath(path)}${query}`
}

function indexPath(index) {
  const normalized = String(index).replace(/\\/g, '/').replace(/^\/+/, '')
  return normalized.startsWith('source/') ? normalized.slice('source/'.length) : normalized
}

async function fetchIndex(index) {
  const relative = indexPath(index)
  const classesUrl = sourceUrlFor(relative)
  const headers = pageToken ? { 'X-WebView-Token': pageToken } : {}
  const classesResponse = await fetch(classesUrl, { cache: 'no-store', headers })
  if (!classesResponse.ok) {
    throw new Error(`${classesUrl}: HTTP ${classesResponse.status}`)
  }
  const indexJson = await classesResponse.json()
  if (!indexJson.classes || typeof indexJson.classes !== 'object') {
    throw new Error(`${relative} has no classes object`)
  }
  const importsPath = relative.replace(/[^/]+$/, 'imports.json')
  let imports = {}
  try {
    const importsResponse = await fetch(sourceUrlFor(importsPath), { cache: 'no-store', headers })
    if (importsResponse.ok) imports = (await importsResponse.json()).imports ?? {}
  } catch {
    // imports.json is optional; sources can still be shown without import edges.
  }
  const marker = relative.indexOf('/.jcodebuddy/')
  const modulePrefix = marker >= 0 ? relative.slice(0, marker) : ''
  const moduleName = indexJson.module || modulePrefix.split('/').filter(Boolean).pop() || 'module'
  return { index: indexJson, imports, modulePrefix, moduleName }
}

function findSimpleCycles(component, edges, limit = 300) {
  const order = new Map(component.map((fqn, index) => [fqn, index]))
  const loops = []
  let truncated = false
  const visit = (start, current, path, visited) => {
    for (const target of edges.get(current) || []) {
      const targetIndex = order.get(target)
      if (targetIndex === undefined) continue
      if (target === start) {
        if (path.length > 1) {
          loops.push([...path])
          if (loops.length >= limit) {
            truncated = true
            return true
          }
        }
        continue
      }
      if (targetIndex < order.get(start) || visited.has(target)) continue
      visited.add(target)
      path.push(target)
      if (visit(start, target, path, visited)) return true
      path.pop()
      visited.delete(target)
    }
    return false
  }
  for (let index = 0; index < component.length && !truncated; index++) {
    const start = component[index]
    visit(start, start, [start], new Set([start]))
  }
  return { loops, truncated }
}

function buildLayers(components, edges) {
  const componentOf = new Map()
  components.forEach((component, index) => component.forEach((node) => componentOf.set(node, index)))
  const dependencies = components.map(() => new Set())
  const dependents = components.map(() => new Set())
  for (const [from, targets] of edges) {
    for (const to of targets) {
      const a = componentOf.get(from)
      const b = componentOf.get(to)
      if (a === undefined || b === undefined || a === b) continue
      dependencies[b].add(a)
      dependents[a].add(b)
    }
  }
  const remaining = dependencies.map((set) => set.size)
  let ready = components.map((_, index) => index).filter((index) => remaining[index] === 0)
  const layers = []
  while (ready.length > 0) {
    layers.push(ready)
    const next = []
    for (const component of ready) {
      for (const dependent of dependents[component]) {
        remaining[dependent]--
        if (remaining[dependent] === 0) next.push(dependent)
      }
    }
    ready = next
  }
  return layers.map((layer) => layer.flatMap((index) => components[index]))
}

function groupKey(node, mode) {
  if (mode === 'module') return node.module
  if (mode === 'packages') return `${node.module}:${node.package || '(default)'}`
  return node.fqn
}

function groupLabel(node, mode) {
  if (mode === 'module') return node.module
  if (mode === 'packages') return node.package || '(default)'
  return node.fqn
}

function transitiveCounts(nodes, edges) {
  const order = new Map([...nodes.keys()].map((key, index) => [key, index]))
  const adjacency = [...nodes.keys()].map((key) => [...(edges.get(key) || [])]
    .map((target) => order.get(target)).filter((index) => index !== undefined))
  const marks = new Int32Array(nodes.size)
  const counts = new Map()
  let stamp = 0
  const stack = []
  for (const [start, startIndex] of order) {
    stamp++
    stack.length = 0
    for (const target of adjacency[startIndex]) stack.push(target)
    let count = 0
    while (stack.length > 0) {
      const current = stack.pop()
      if (marks[current] === stamp) continue
      marks[current] = stamp
      if (current === startIndex) continue
      count++
      for (const target of adjacency[current]) stack.push(target)
    }
    counts.set(start, count)
  }
  return counts
}

function buildModel(models, title, mode = 'classes') {
  const nodes = new Map()
  const byFile = new Map()
  for (const model of models) {
    for (const [fqn, entry] of Object.entries(model.index.classes)) {
      const node = {
        fqn,
        module: model.moduleName,
        modulePrefix: model.modulePrefix,
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

  const edgeWeights = new Map()
  for (const [from, targets] of edges) for (const to of targets) edgeWeights.set(`${from}\u0000${to}`, 1)
  const weightOf = (from, to) => edgeWeights.get(`${from}\u0000${to}`) || 1

  let graphNodes = nodes
  let graphEdges = edges
  if (mode !== 'classes') {
    const classToGroup = new Map()
    graphNodes = new Map()
    for (const node of nodes.values()) {
      const key = groupKey(node, mode)
      classToGroup.set(node.fqn, key)
      const existing = graphNodes.get(key)
      if (existing) {
        existing.members++
      } else {
        graphNodes.set(key, {
          fqn: key,
          label: groupLabel(node, mode),
          module: mode === 'module' ? node.module : '',
          kind: mode,
          members: 1,
        })
      }
    }
    graphEdges = new Map([...graphNodes.keys()].map((key) => [key, new Set()]))
    const groupWeights = new Map()
    for (const [from, targets] of edges) {
      const fromKey = classToGroup.get(from)
      for (const to of targets) {
        const toKey = classToGroup.get(to)
        if (!fromKey || !toKey || fromKey === toKey) continue
        graphEdges.get(fromKey).add(toKey)
        const key = `${fromKey}\u0000${toKey}`
        groupWeights.set(key, (groupWeights.get(key) || 0) + 1)
      }
    }
    for (const [key, weight] of groupWeights) edgeWeights.set(key, weight)
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
    for (const target of graphEdges.get(node) || []) {
      if (!graphNodes.has(target)) continue
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
  for (const node of graphNodes.keys()) if (!indexes.has(node)) visit(node)

  const cycles = components
    .filter((component) => component.length > 1)
    .map((component) => {
      const members = new Set(component)
      const internal = []
      for (const from of component) {
        for (const to of graphEdges.get(from) || []) {
          if (members.has(to)) internal.push({ from, to, weight: weightOf(from, to) })
        }
      }
      const { loops, truncated } = findSimpleCycles(component, graphEdges)
      const participation = new Map()
      for (const loop of loops) {
        for (let at = 0; at < loop.length; at++) {
          const from = loop[at]
          const to = loop[(at + 1) % loop.length]
          const key = `${from}\u0000${to}`
          participation.set(key, (participation.get(key) || 0) + 1)
        }
      }
      return {
        nodes: component.sort().map((key) => {
          const node = graphNodes.get(key)
          return {
            fqn: key,
            label: node.label || key,
            module: node.module,
            kind: node.kind,
            line: node.line || 0,
            members: node.members,
          }
        }),
        edges: internal,
        cutEdges: [...internal]
          .map((edge) => ({ ...edge, loops: participation.get(`${edge.from}\u0000${edge.to}`) || 0 }))
          .sort((left, right) => left.weight - right.weight || left.loops - right.loops),
        loops,
        loopsTruncated: truncated,
      }
    })
    .sort((left, right) => right.nodes.length - left.nodes.length)

  const fanIn = new Map([...graphNodes.keys()].map((key) => [key, 0]))
  for (const targets of graphEdges.values()) for (const target of targets) fanIn.set(target, (fanIn.get(target) || 0) + 1)
  const rank = (map) => [...map].sort((left, right) => right[1] - left[1]).slice(0, 20)
    .map(([key, count]) => ({ fqn: key, label: graphNodes.get(key)?.label || key, count }))

  const componentOf = new Map()
  components.forEach((component, componentIndex) => {
    for (const key of component) componentOf.set(key, componentIndex)
  })
  const boundary = []
  for (const [from, targets] of graphEdges) {
    for (const to of targets) {
      if (componentOf.get(from) === componentOf.get(to)) continue
      boundary.push({
        from,
        to,
        fromLabel: graphNodes.get(from)?.label || from,
        toLabel: graphNodes.get(to)?.label || to,
        weight: weightOf(from, to),
      })
    }
  }
  boundary.sort((left, right) => left.weight - right.weight)
  const layers = buildLayers(components, graphEdges).map((layer) => (
    layer.map((key) => ({ fqn: key, label: graphNodes.get(key)?.label || key }))
  ))
  const transitive = transitiveCounts(graphNodes, graphEdges)
  const hubs = [...graphNodes.keys()].map((key) => {
    const node = graphNodes.get(key)
    return {
      fqn: key,
      label: node.label || key,
      kind: node.kind,
      module: node.module,
      direct: graphEdges.get(key)?.size || 0,
      transitive: transitive.get(key) || 0,
    }
  }).sort((left, right) => right.transitive - left.transitive || right.direct - left.direct)
  const labels = Object.fromEntries([...graphNodes].map(([key, node]) => [key, node.label || key]))
  const adjacency = Object.fromEntries([...graphEdges].map(([key, targets]) => [key, [...targets]]))

  return {
    title,
    mode,
    unit: mode === 'classes' ? 'types' : mode === 'module' ? 'modules' : 'packages',
    summary: {
      types: graphNodes.size,
      edges: [...graphEdges.values()].reduce((total, targets) => total + targets.size, 0),
      cycles: cycles.length,
      largest: cycles[0]?.nodes.length ?? 0,
    },
    cycles,
    fanIn: rank(fanIn),
    fanOut: rank(new Map([...graphEdges].map(([key, targets]) => [key, targets.size]))),
    boundary: boundary.slice(0, 40),
    layers,
    hubs,
    labels,
    adjacency,
  }
}

function setMode(mode) {
  $mode(mode)
  $selected(0)
  $selectedNode(null)
  try {
    const url = new URL(location.href)
    url.searchParams.set('mode', mode)
    history.replaceState(null, '', url)
  } catch {
    // The page still works when history is unavailable.
  }
  if ($raw()) {
    const next = buildModel($raw(), window.CYCLES_TITLE || 'Dependency cycles', mode)
    $model(next)
    $stale(false)
  }
  render()
}

function setView(view) {
  $view(view)
  try {
    const url = new URL(location.href)
    if (view === 'dependencies' && $selectedNode()) url.searchParams.set('node', $selectedNode())
    else url.searchParams.delete('node')
    history.replaceState(null, '', url)
  } catch {
    // The page still works when history is unavailable.
  }
  render()
}

function showNode(fqn) {
  $view('dependencies')
  $selectedNode(fqn)
  try {
    const url = new URL(location.href)
    url.searchParams.set('node', fqn)
    history.replaceState(null, '', url)
  } catch {
    // The page still works when history is unavailable.
  }
  render()
}

function setThreshold(value) {
  const number = Number.parseInt(value, 10)
  $threshold(Number.isFinite(number) && number >= 0 ? number : 0)
  render()
}

function cycleSignature(model) {
  const cycles = model.cycles.map((cycle) => cycle.nodes.map((node) => node.fqn).join(',')).join('|')
  const hubs = model.hubs.map((hub) => `${hub.fqn}:${hub.transitive}:${hub.direct}`).join(',')
  return cycles + '|' + hubs
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
    $raw(models)
    const next = buildModel(models, window.CYCLES_TITLE || 'Dependency cycles', $mode())
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
  for (const host of document.querySelectorAll('.cycle-graph:popover-open')) {
    try { host.hidePopover() } catch { /* already closed */ }
  }
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
  return cycle.nodes.some((node) => `${node.fqn} ${node.label} ${node.module}`.toLowerCase().includes(needle))
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

function simpleName(fqn) {
  const at = fqn.lastIndexOf('.')
  return at < 0 ? fqn : fqn.slice(at + 1)
}

function cycleBlock(node) {
  const block = document.createElement('div')
  block.className = 'ne-block cycle-graph-block'
  block.title = node.fqn
  const title = document.createElement('div')
  title.className = 'cycle-graph-title'
  title.setAttribute('ne-drag', '')
  title.textContent = node.label || simpleName(node.fqn)
  const meta = document.createElement('div')
  meta.className = 'cycle-graph-meta'
  meta.textContent = `${node.kind} · ${node.module}`
  meta.setAttribute('ne-nodrag', '')
  for (const [name, side, direction] of [
    ['lin', 'left', 'in'],
    ['lout', 'left', 'out'],
    ['rin', 'right', 'in'],
    ['rout', 'right', 'out'],
    ['tin', 'top', 'in'],
    ['tout', 'top', 'out'],
    ['bin', 'bottom', 'in'],
    ['bout', 'bottom', 'out'],
  ]) {
    const port = document.createElement('b')
    port.className = `cycle-graph-port cycle-graph-port-${side} cycle-graph-port-${direction}`
    port.setAttribute('ncid', name)
    port.setAttribute('ne-connect', direction)
    block.append(port)
  }
  block.append(title, meta)
  return block
}

function wireGraph(editor, cycle) {
  if (editor.__cycleKey === cycle.nodes.map((node) => node.fqn).join('|')) return
  editor.__cycleKey = cycle.nodes.map((node) => node.fqn).join('|')
  const visible = cycle.nodes.slice(0, 60)
  const ids = new Map()
  visible.forEach((node, index) => {
    const id = `c${index}`
    ids.set(node.fqn, id)
    editor.add(cycleBlock(node), id, { pos: [24, 24], type: 'CycleType' })
  })

  // Nodditor's ResizeObserver publishes each block's measured `size`; lay the boxes out from
  // those sizes, not from an assumed fixed width/height, and only then draw the lines.
  const apply = (attempt = 0) => {
    if (!editor.isConnected) return
    const entries = [...ids].map(([fqn, id], index) => ({
      fqn,
      id,
      index,
      data: editor.getBlockData(id),
    }))
    const ready = entries.every(({ data }) => data?.size?.[0] > 0 && data?.size?.[1] > 0)
    if (!ready && attempt < 40) {
      requestAnimationFrame(() => apply(attempt + 1))
      return
    }
    const gapX = 46
    const gapY = 36
    const margin = 24
    const columnWidth = [0, 0]
    const rowHeight = []
    for (const entry of entries) {
      const [width, height] = entry.data?.size?.every?.((value) => value > 0)
        ? entry.data.size
        : [220, 64]
      const column = entry.index % 2
      const row = Math.floor(entry.index / 2)
      columnWidth[column] = Math.max(columnWidth[column], width)
      rowHeight[row] = Math.max(rowHeight[row] || 0, height)
    }
    const positions = new Map()
    for (const entry of entries) {
      const column = entry.index % 2
      const row = Math.floor(entry.index / 2)
      const x = margin + (column === 0 ? 0 : columnWidth[0] + gapX)
      const y = margin + rowHeight.slice(0, row).reduce((sum, height) => sum + height + gapY, 0)
      positions.set(entry.id, [x, y])
      editor.setPos(entry.id, [x, y])
    }

    const connected = new Set()
    for (const edge of cycle.edges) {
      const from = ids.get(edge.from)
      const to = ids.get(edge.to)
      if (!from || !to || from === to) continue
      const key = `${from}>${to}`
      if (connected.has(key)) continue
      connected.add(key)
      const fromPos = positions.get(from) || [0, 0]
      const toPos = positions.get(to) || [0, 0]
      const fromSize = editor.getBlockData(from)?.size || [220, 64]
      const toSize = editor.getBlockData(to)?.size || [220, 64]
      const dx = (toPos[0] + toSize[0] / 2) - (fromPos[0] + fromSize[0] / 2)
      const dy = (toPos[1] + toSize[1] / 2) - (fromPos[1] + fromSize[1] / 2)
      let fromPort
      let toPort
      if (Math.abs(dx) >= Math.abs(dy)) {
        const forward = dx >= 0
        fromPort = forward ? 'rout' : 'lout'
        toPort = forward ? 'lin' : 'rin'
      } else {
        const down = dy >= 0
        fromPort = down ? 'bout' : 'tout'
        toPort = down ? 'tin' : 'bin'
      }
      try {
        editor.addConnectorFromTo(`${from}/${fromPort}`, `${to}/${toPort}`)
      } catch {
        // A pair nodditor refuses (e.g. an already connected pair) must not break the report.
      }
    }
  }
  apply()
}

function createGraphHost(cycle) {
  const host = document.createElement('div')
  host.className = 'cycle-graph'
  host.setAttribute('popover', 'manual')
  host.setAttribute('role', 'dialog')
  host.setAttribute('aria-label', 'Cycle graph')
  const editor = document.createElement('jsx6-nodditor')
  editor.className = 'cycle-graph-editor'
  const legend = document.createElement('div')
  legend.className = 'cycle-graph-legend'
  legend.innerHTML = '<span><i class="in"></i>in · used by</span>'
    + '<span><i class="out"></i>out · depends on</span>'
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'cycle-graph-maximize'
  button.title = 'Maximize graph'
  button.textContent = '⛶ Maximize'
  const isOpen = () => host.matches(':popover-open') || host.classList.contains('cycle-graph-maximized')
  // JCEF's windowed/OSR surface does not reliably repaint the whole view when a popover enters the
  // browser's top layer. Keep the same element, but maximize it in normal layout there; Chrome gets
  // the real Popover API path. The bridge is installed after the first load, so detect it when the
  // button is used rather than when the graph is created.
  const jcefHost = () => typeof window.openFile === 'function'
    || typeof window.__jcbWebViewBridge === 'number'
  const update = () => {
    button.textContent = isOpen() ? '✕ Close' : '⛶ Maximize'
    button.title = isOpen() ? 'Close graph' : 'Maximize graph'
    button.setAttribute('aria-expanded', String(isOpen()))
  }
  let onKey
  let onDocumentClick
  const close = () => {
    if (typeof host.hidePopover === 'function') {
      try { host.hidePopover() } catch { /* already closed */ }
    }
    host.classList.remove('cycle-graph-maximized')
    if (onKey) document.removeEventListener('keydown', onKey)
    if (onDocumentClick) document.removeEventListener('click', onDocumentClick, true)
    onKey = null
    onDocumentClick = null
    update()
  }
  const open = () => {
    if (!jcefHost() && typeof host.showPopover === 'function') {
      try { host.showPopover() } catch { /* already open */ }
    } else {
      host.classList.add('cycle-graph-maximized')
    }
    onKey = (event) => { if (event.key === 'Escape') close() }
    onDocumentClick = (event) => { if (!host.contains(event.target)) close() }
    document.addEventListener('keydown', onKey)
    document.addEventListener('click', onDocumentClick, true)
    update()
  }
  button.addEventListener('click', (event) => {
    event.stopPropagation()
    if (isOpen()) close()
    else open()
  })
  host.addEventListener('toggle', update)
  legend.append(button)
  host.append(editor, legend)
  customElements.whenDefined('jsx6-nodditor').then(() => {
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (editor.isConnected) wireGraph(editor, cycle)
    }))
  })
  update()
  return host
}

function CycleGraph({ cycle }) {
  return createGraphHost(cycle)
}

function DependencyReport({ model, hub }) {
  const hubByFqn = new Map(model.hubs.map((entry) => [entry.fqn, entry]))
  const direct = model.adjacency[hub.fqn] || []
  const visible = [hub, ...direct.slice(0, 59)]
    .map((entry) => (typeof entry === 'string' ? hubByFqn.get(entry) : entry))
    .filter(Boolean)
  const visibleFqns = new Set(visible.map((entry) => entry.fqn))
  const edges = []
  for (const node of visible) {
    for (const target of model.adjacency[node.fqn] || []) {
      if (visibleFqns.has(target)) edges.push({ from: node.fqn, to: target })
    }
  }
  const graph = {
    nodes: visible.map((entry) => ({
      fqn: entry.fqn,
      label: entry.label,
      kind: entry.kind,
      module: entry.module,
      line: 0,
    })),
    edges,
  }
  const seen = new Set([hub.fqn])
  const stack = [...direct]
  const transitive = []
  while (stack.length > 0 && transitive.length < 200) {
    const current = stack.pop()
    if (seen.has(current)) continue
    seen.add(current)
    transitive.push(current)
    for (const target of model.adjacency[current] || []) if (!seen.has(target)) stack.push(target)
  }
  return (
    <>
      <h3>{hub.label}</h3>
      <p class="muted">
        <b>{hub.transitive}</b> transitive dependencies · <b>{hub.direct}</b> direct · {hub.kind} · {hub.module || model.unit}
      </p>
      <div class="cycle-report">
        <div class="cycle-graph-panel">
          <CycleGraph cycle={graph} />
          {direct.length > 59
            ? <p class="muted">Graph shows the first 59 of {direct.length} direct dependencies.</p>
            : null}
        </div>
        <div class="cycle-stats">
          <h4>Direct dependencies</h4>
          {direct.length === 0
            ? <p class="muted">No resolved dependencies.</p>
            : direct.map((target) => <div class="dependency-row">{model.labels[target] || target}</div>)}
          <h4>Transitive dependencies</h4>
          {transitive.length === 0
            ? <p class="muted">No transitive dependencies.</p>
            : transitive.slice(0, 50).map((target) => <div class="dependency-row">{model.labels[target] || target}</div>)}
          {seen.size > 50
            ? <p class="muted">Showing 50 of {hub.transitive} reachable dependencies.</p>
            : null}
        </div>
      </div>
    </>
  )
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
  const labelOf = new Map((cycle?.nodes || []).map((node) => [node.fqn, node.label]))
  const view = $view()
  const threshold = $threshold()
  const selectedHub = model.hubs.find((entry) => entry.fqn === $selectedNode())
  const hubQuery = query.toLowerCase()
  const hubs = model.hubs.filter((entry) => entry.transitive >= threshold
    && (!hubQuery || `${entry.fqn} ${entry.label} ${entry.module}`.toLowerCase().includes(hubQuery)))

  return (
    <main>
      <div class="head">
        <h1>{model.title}</h1>
        <div class="head-actions">
          <label class="mode">Group by
            <select value={model.mode} onchange={(event) => setMode(event.target.value)}>
              <option value="classes" selected={model.mode === 'classes'}>classes</option>
              <option value="packages" selected={model.mode === 'packages'}>packages</option>
              <option value="module" selected={model.mode === 'module'}>modules</option>
            </select>
          </label>
          <button type="button" class={'refresh' + ($stale() ? ' attention' : '')}
            disabled={$loading() || $checking()}
            title={$stale() ? 'The metadata contains different cycles; click to apply them' : 'Re-read metadata'}
            onclick={() => load(true)}>
            {$loading() ? 'Refreshing…' : $checking() ? 'Checking metadata…'
              : $stale() ? 'Cycles changed — refresh' : 'Refresh metadata'}
          </button>
        </div>
      </div>
      {$error() ? <p class="error">Refresh failed: {$error()} — showing the previous metadata.</p> : null}
      <p class="lead">{model.mode === 'classes' ? 'Class-level' : 'Group-level'} strongly connected components from the committed class index. A cycle means these units cannot be compiled in separate batches without stubs or an architectural break.</p>
      <section class="summary">
        <div class="card"><b>{String(model.summary.types)}</b><span>{model.unit}</span></div>
        <div class="card"><b>{String(model.summary.edges)}</b><span>resolved edges</span></div>
        <div class="card"><b>{String(model.summary.cycles)}</b><span>cycles</span></div>
        <div class="card"><b>{String(model.summary.largest)}</b><span>largest cycle</span></div>
      </section>
      <section class="layout">
        <div class="panel">
          <div class="view-tabs">
            <button type="button" class={view === 'cycles' ? 'active' : ''} onclick={() => setView('cycles')}>Cycles</button>
            <button type="button" class={view === 'dependencies' ? 'active' : ''} onclick={() => setView('dependencies')}>Dependencies</button>
          </div>
          <input id="filter" value={query} placeholder="filter by type, package or module…"
            oninput={(event) => setQuery(event.target.value)} />
          {view === 'cycles'
            ? <div class="cycle-list">
                {cycles.length === 0
                  ? <p class="muted">No matching cycles.</p>
                  : cycles.map(({ cycle: item, index }) => (
                      <button type="button" class={'cycle' + (index === selected ? ' selected' : '')}
                        onclick={() => selectCycle(index)}>
                        <b>{item.nodes.length} {model.unit}</b>
                        <small>{item.nodes.slice(0, 2).map((node) => node.label).join(', ')}</small>
                      </button>
                    ))}
              </div>
            : <>
                <label class="threshold">Transitive dependencies ≥
                  <input type="number" min="0" step="1" value={threshold}
                    oninput={(event) => setThreshold(event.target.value)} />
                </label>
                <div class="hub-list">
                  {hubs.length === 0
                    ? <p class="muted">No matching units at this threshold.</p>
                    : hubs.slice(0, 200).map((entry) => (
                        <button type="button" class={'hub-item' + (entry.fqn === $selectedNode() ? ' selected' : '')}
                          onclick={() => showNode(entry.fqn)}>
                          <b>{entry.label}</b>
                          <small>{entry.transitive} transitive · {entry.direct} direct</small>
                        </button>
                      ))}
                </div>
              </>}
        </div>
        <div class="panel">
          {view === 'dependencies'
            ? selectedHub
              ? <DependencyReport model={model} hub={selectedHub} />
              : <p class="muted">Select a unit to inspect its direct and transitive dependencies.</p>
            : cycle
              ? <>
                <h3>Cycle {selected + 1} — {cycle.nodes.length} {model.unit}</h3>
                <div class="cycle-report">
                  <div class="cycle-graph-panel">
                    <CycleGraph cycle={cycle} />
                    {cycle.nodes.length > 60
                      ? <p class="muted">Graph shows the first 60 of {cycle.nodes.length} types.</p>
                      : null}
                  </div>
                  <div class="cycle-stats">
                    <p><b>{cycle.edges.length}</b> internal dependencies · <b>{cycle.loops.length}</b> simple loop{cycle.loops.length === 1 ? '' : 's'}{cycle.loopsTruncated ? '+' : ''}</p>
                    <h4>Interconnection</h4>
                    {cycle.nodes.map((node) => {
                      const outgoing = cycle.edges.filter((edge) => edge.from === node.fqn)
                        .map((edge) => labelOf.get(edge.to) || edge.to)
                      const incoming = cycle.edges.filter((edge) => edge.to === node.fqn)
                        .map((edge) => labelOf.get(edge.from) || edge.from)
                      return (
                        <div class="cycle-interconnection">
                          <b>{node.label}</b>
                          <span>→ {outgoing.length ? outgoing.join(', ') : 'none'}</span>
                          <span>← {incoming.length ? incoming.join(', ') : 'none'}</span>
                        </div>
                      )
                    })}
                    <h4>Loops</h4>
                    {cycle.loops.length === 0
                      ? <p class="muted">No simple loops (unexpected for an SCC).</p>
                      : cycle.loops.slice(0, 8).map((loop) => (
                          <div class="cycle-loop">{(loop.map((key) => labelOf.get(key) || key)).join(' → ')} → {labelOf.get(loop[0]) || loop[0]}</div>
                        ))}
                    {cycle.loops.length > 8
                      ? <p class="muted">…and {cycle.loops.length - 8} more{cycle.loopsTruncated ? ' (capped)' : ''}.</p>
                      : null}
                    <h4>Cut candidates</h4>
                    <p class="muted">Low-weight internal edges first; an interface or a split at one of these should remove the cycle.</p>
                    {cycle.cutEdges.slice(0, 6).map((edge) => (
                      <div class="cycle-loop">
                        {labelOf.get(edge.from) || edge.from} → {labelOf.get(edge.to) || edge.to}
                        <span class="kind"> · weight {edge.weight} · {edge.loops} loop{edge.loops === 1 ? '' : 's'}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </>
            : <p class="muted">Select a cycle.</p>}
        </div>
      </section>
      <section class="analysis">
        <div class="panel">
          <h3>Low-weight grouping boundaries</h3>
          <p class="muted">Thin cross-group edges are the easiest places to introduce an interface or move a type into a separate module. They are not cycles themselves.</p>
          {model.boundary.length === 0
            ? <p class="muted">No cross-group edges.</p>
            : model.boundary.slice(0, 14).map((edge) => (
                <div class="boundary-row">
                  <span>{edge.fromLabel} → {edge.toLabel}</span>
                  <b>{edge.weight}</b>
                </div>
              ))}
        </div>
        {model.mode === 'classes'
          ? null
          : <div class="panel">
              <h3>Compile layers</h3>
              <p class="muted">Strongly connected groups compile together; each next layer can start after the previous one. Layers are parallel opportunities for a Maven split.</p>
              {model.layers.map((layer, index) => (
                <div class="layer-row">
                  <b>layer {index + 1}</b>
                  <span>{layer.length} group{layer.length === 1 ? '' : 's'}: {layer.slice(0, 8).map((group) => group.label).join(', ')}{layer.length > 8 ? '…' : ''}</span>
                </div>
              ))}
            </div>}
      </section>
      <section class="hubs">
        <div class="panel">
          <h3>Highest fan-in</h3>
          {model.fanIn.map((entry) => (
            <div class="hub">
              <a href="#" onclick={(event) => { event.preventDefault(); showNode(entry.fqn) }}>{entry.label}</a>
              <b>{String(entry.count)} in</b>
            </div>
          ))}
        </div>
        <div class="panel">
          <h3>Highest fan-out</h3>
          {model.fanOut.map((entry) => (
            <div class="hub">
              <a href="#" onclick={(event) => { event.preventDefault(); showNode(entry.fqn) }}>{entry.label}</a>
              <b>{String(entry.count)} out</b>
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
