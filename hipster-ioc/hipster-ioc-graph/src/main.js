/**
 * The page's entry: read the generator's graph, render the shell, and drive the editor.
 *
 * The order matters and is nodditor's own ("render → inspect → wire"): the shell is inserted first,
 * the blocks are added through `loadGraph` so each block's connectors exist, and only then is a line
 * attached per dependency. `addConnectorFromTo` is deliberately **not** used — this is data, and the
 * tolerant `loadLines` reports what it could not attach instead of throwing on the first bad endpoint.
 *
 * Navigation (step 3.11a, DEC-049) is **one delegated listener** on the page root rather than a handler per
 * element: every navigable element carries `data-location`, which survives nodditor re-creating blocks
 * through its factories on undo/redo, and the listener asks the host through the frozen
 * `window.openFile` contract — or, on a `file://` page with no host, leaves the click inert and says so.
 */
import { insert } from '@jsx6/jsx6'
import { NodeEditor } from '@jsx6/nodditor'

import contexts from './contexts.js'
import { GraphShell } from './host.jsx'
import { typeMap } from './blocks.js'
import { layout, readGraph } from './model.js'
import { canNavigate, locationOfElement, openLocation } from './navigate.js'

async function boot() {
  const mount = document.getElementById('graph-root')
  if (!mount) {
    return
  }

  // The generator's JSON arrives as a module the build wrote (`./contexts.js`), not as a fetch: see
  // `build.js` for the measured reason (`file://` refuses a sibling fetch, and DEC-027 requires this
  // page to be usable standalone — no host, no server, nothing to start).
  // The placeholder in the built HTML is for the instant before the script runs; clearing it keeps it
  // from sitting above the shell forever.
  mount.textContent = ''

  const graph = readGraph(contexts)
  const select = (context) => {
    editor?.selectBlocks?.([context.id])
    editor?.fit?.()
  }

  // One listener for every navigable element in the page: the blocks, their implementations and their
  // beans, plus the sidebar's per-context "source" buttons. `closest` walks up from whatever was clicked,
  // so a click on a nested span still finds the element that carries the target.
  mount.addEventListener('click', (event) => {
    const target = event.target?.closest?.('[data-location]')
    const location = locationOfElement(target)
    if (location) {
      openLocation(location)
    }
  })

  // Built here rather than in JSX: see `host.jsx` for the measured reason (a custom element's
  // constructor must not be given attributes or children). The element is created **detached and
  // attribute-free**, so the upgrade runs when it is inserted — `createElement` with a `className`
  // already set makes the browser raise `The result must not have attributes` from its own upgrade step,
  // which leaves the element un-upgraded and `loadGraph` undefined. Setting the class afterwards is what
  // keeps the order legal, and holding the reference is what the render-inspect-wire order needs.
  const editor = document.createElement('jsx6-nodditor')

  insert(mount, <GraphShell contexts={graph.contexts} onSelect={select} editor={editor}
    navigable={canNavigate()} navHint={navHint(graph)} status={summarise(graph)} />)

  // Now the element is in the document and its class has run, so the attributes and the graph are set.
  editor.className = 'NodeEditor'
  editor.setAttribute('tabindex', '0')
  editor.typeMap = typeMap
  const blocks = layout(graph.contexts)
  editor.loadGraph({ blocks, lines: [] })

  // The lines, after the blocks are in the DOM: `loadLines` is the tolerant path, so an endpoint that
  // never rendered is *reported* rather than thrown.
  const wired = editor.loadLines(graph.lines.map((line) => [`${line.from}/out`, `${line.to}/in`]))
  // The status line is the signal the shell published; writing it updates one text node.
  globalThis.__HIPSTER_IOC_STATUS__?.(summarise(graph, wired))
}

/** One line of status: what the page read, and what it could not attach or place. */
function summarise(graph, wired) {
  const parts = [
    `${graph.contexts.length} context(s)`,
    `${graph.lines.length} dependency line(s)`,
  ]
  if (wired) {
    parts.push(`${wired.attached} attached`, `${wired.skipped} skipped`)
  }
  if (graph.missing.length > 0) {
    parts.push(`${graph.missing.length} unresolved: ${graph.missing.join(', ')}`)
  }
  if (graph.locationless.length > 0) {
    parts.push(`${graph.locationless.length} without a location: ${graph.locationless.join(', ')}`)
  }
  return parts.join(' — ')
}

/**
 * What the page says about navigation, which is a property of where it is open rather than of the graph: a
 * `file://` document has no injected bridge, so it reports that instead of offering a click that does
 * nothing (root `AGENTS.md` § 2's standalone rule).
 */
function navHint(graph) {
  const placeable = graph.contexts.filter((context) => context.location).length
  if (!canNavigate()) {
    return `standalone: ${placeable} of ${graph.contexts.length} context(s) have a source location — open this page through a host to navigate`
  }
  return `navigable: ${placeable} of ${graph.contexts.length} context(s) have a source location`
}

// `ContextBlock` and `typeMap` live in `blocks.js`; the editor needs only the map, which is why the
// block builder is not imported here (a bundler keeps it through the map).
export { }

boot()
