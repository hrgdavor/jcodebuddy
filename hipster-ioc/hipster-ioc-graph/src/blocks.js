/**
 * The blocks and the stylesheet's own names: plain DOM, because nodditor's contract is about
 * **elements**, not components (its README: "a block is *any* element: no base class, no lifecycle
 * hook, and no jsx6 state is involved").
 *
 * A block carries `class="ne-block"` (the geometry is attached to it), a `ne-drag` title, and one
 * connector per side with `ncid` plus `ne-connect`. The editor adds `nid`, the ARIA attributes and the
 * transform itself, so nothing here positions the block.
 */

/** One connector: an element with a unique `ncid` inside the block and its `ne-connect` side. */
function port(ncid, direction) {
  const element = document.createElement('b')
  element.className = `graph-port graph-port-${direction}`
  element.setAttribute('ncid', ncid)
  element.setAttribute('ne-connect', direction)
  return element
}

/**
 * A context block: title (the drag handle), the implementation as a subtitle, and the beans as rows.
 *
 * @param {{context: object}} data the saved entry, handed back by the factory on `loadGraph`
 */
export function ContextBlock(data) {
  const context = data.context ?? data
  const root = document.createElement('div')
  root.className = 'ne-block graph-block'

  const title = document.createElement('div')
  title.className = 'graph-block-title'
  title.setAttribute('ne-drag', '')
  title.append(port('in', 'in'))
  const label = document.createElement('span')
  label.className = 'graph-block-name'
  label.textContent = context.simpleName || context.id
  label.title = context.id
  title.append(label, port('out', 'out'))

  const subtitle = document.createElement('div')
  subtitle.className = 'graph-block-subtitle'
  subtitle.textContent = context.implementation ? `implements ${context.implementation}` : ''
  subtitle.hidden = !context.implementation

  const body = document.createElement('div')
  body.className = 'graph-block-body'
  if (context.beans.length === 0) {
    const empty = document.createElement('div')
    empty.className = 'graph-block-empty'
    empty.textContent = 'no beans recorded'
    body.appendChild(empty)
  }
  for (const bean of context.beans) {
    const row = document.createElement('div')
    row.className = 'graph-block-row'
    // `ne-item` is what the editor aligns its selection menu to.
    row.setAttribute('ne-item', '')
    const name = document.createElement('span')
    name.className = 'graph-bean-name'
    name.textContent = bean.name
    const type = document.createElement('span')
    type.className = 'graph-bean-type'
    type.textContent = bean.type
    row.append(name, type)
    if (bean.createdBy) {
      row.title = `created by ${bean.createdBy}`
    }
    body.appendChild(row)
  }

  root.append(title, subtitle, body)
  return root
}

/** The factories `loadGraph` and undo/redo rebuild through: one fresh element per call. */
export const typeMap = {
  Context: (data) => ContextBlock(data),
}
