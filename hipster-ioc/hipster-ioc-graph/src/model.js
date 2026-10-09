/**
 * The graph model: a **projection** of the generator's `contexts.json` (DEC-037's "a consumer owns its
 * domain shape"), with no Java parsing and no second index.
 *
 * The generator writes each context's name, its implementation, its dependencies and its beans. This
 * module turns that into the two things a page needs — blocks and the lines between them — and it
 * reports what it could not use rather than inventing it: a dependency naming a context that is not in
 * the file is kept in `missing`, because a line to nowhere is worse than a named absence.
 */

/** A fully-qualified name's simple form. */
export function simpleName(fqn) {
  const dot = String(fqn ?? '').lastIndexOf('.')
  return dot < 0 ? String(fqn ?? '') : String(fqn).slice(dot + 1)
}

/**
 * Reads the generator's JSON into the page's model.
 *
 * @param {object} json the parsed `contexts.json`
 * @returns {{contexts: Array, missing: Array<string>, lines: Array<Array<string>>}}
 */
export function readGraph(json) {
  const list = Array.isArray(json?.contexts) ? json.contexts : []
  const contexts = list.map((context, index) => ({
    id: String(context.context ?? `context-${index}`),
    simpleName: simpleName(context.context),
    implementation: String(context.implementation ?? ''),
    dependencies: Array.isArray(context.dependencies) ? context.dependencies.map(String) : [],
    beans: Array.isArray(context.beans) ? context.beans.map((bean) => ({
      name: String(bean.name ?? ''),
      type: String(bean.type ?? ''),
      createdBy: String(bean.createdBy ?? ''),
    })) : [],
  }))

  const known = new Set(contexts.map((context) => context.id))
  const missing = []
  const lines = []
  for (const context of contexts) {
    for (const dependency of context.dependencies) {
      if (!known.has(dependency)) {
        // Reported, never drawn: a line to a block that does not exist is a picture of something that
        // is not there, which is the failure mode DEC-037's rule exists for.
        missing.push(`${context.id} -> ${dependency}`)
        continue
      }
      lines.push({ from: context.id, to: dependency })
    }
  }
  return { contexts, missing, lines }
}

/** Block positions on a grid, so the first render is stable and readable. */
export function layout(contexts, { columns = 3, x = 40, y = 40, dx = 340, dy = 280 } = {}) {
  return contexts.map((context, index) => ({
    id: context.id,
    type: 'Context',
    pos: [x + (index % columns) * dx, y + Math.floor(index / columns) * dy],
    context,
  }))
}
