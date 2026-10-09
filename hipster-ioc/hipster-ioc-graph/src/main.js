/**
 * The page's entry: read the generator's graph, render the shell, and drive the editor.
 *
 * The order matters and is nodditor's own ("render → inspect → wire"): the shell is inserted first,
 * the blocks are added through `loadGraph` so each block's connectors exist, and only then is a line
 * attached per dependency. `addConnectorFromTo` is deliberately **not** used — this is data, and the
 * tolerant `loadLines` reports what it could not attach instead of throwing on the first bad endpoint.
 */
import { insert } from '@jsx6/jsx6'
import { NodeEditor } from '@jsx6/nodditor'

import contexts from './contexts.js'
import { GraphShell } from './host.jsx'
import { typeMap } from './blocks.js'
import { layout, readGraph } from './model.js'

async function boot() {
  const mount = document.getElementById('graph-root')
  if (!mount) {
    return
  }

  // The generator's JSON arrives as a module the build wrote (`./contexts.js`), not as a fetch: see
  // `build.js` for the measured reason (`file://` refuses a sibling fetch, and DEC-027 requires this
  // page to be usable standalone — no host, no server, nothing to start).
  const graph = readGraph(contexts)
  const select = (context) => {
    editor?.selectBlocks?.([context.id])
    editor?.fit?.()
  }

  // Built here rather than in JSX: see `host.jsx` for the measured reason (a custom element's
  // constructor must not be given attributes or children). Holding the reference is also what the
  // render-inspect-wire order needs.
  const editor = document.createElement('jsx6-nodditor')
  editor.className = 'NodeEditor'
  editor.setAttribute('tabindex', '0')

  insert(mount, <GraphShell contexts={graph.contexts} onSelect={select} editor={editor}
    status={summarise(graph)} />)

  editor.typeMap = typeMap
  const blocks = layout(graph.contexts)
  editor.loadGraph({ blocks, lines: [] })

  // The lines, after the blocks are in the DOM: `loadLines` is the tolerant path, so an endpoint that
  // never rendered is *reported* rather than thrown.
  const wired = editor.loadLines(graph.lines.map((line) => [`${line.from}/out`, `${line.to}/in`]))
  // The status line is the signal the shell published; writing it updates one text node.
  globalThis.__HIPSTER_IOC_STATUS__?.(summarise(graph, wired))
}

/** One line of status: what the page read, and what it could not attach. */
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
  return parts.join(' — ')
}

// `ContextBlock` and `typeMap` live in `blocks.js`; the editor needs only the map, which is why the
// block builder is not imported here (a bundler keeps it through the map).
export { }

boot()
