/**
 * The graph model: a **projection** of the generator's `contexts.json` (DEC-037's "a consumer owns its
 * domain shape"), with no Java parsing and no second index.
 *
 * The generator writes each context's name, its implementation, its dependencies and its beans. This
 * module turns that into the two things a page needs — blocks and the lines between them — and it
 * reports what it could not use rather than inventing it: a dependency naming a context that is not in
 * the file is kept in `missing`, because a line to nowhere is worse than a named absence.
 *
 * Since step 3.11a the build has already joined each name to a source location
 * ([DEC-049](../../../doc-hipster-entity/architecture/decisions/DEC-049.md)), so this module **carries**
 * those locations through instead of deriving them: `location` per context and per bean,
 * `implementationLocation`, and the build's own `locationless` list of names the class index could not
 * answer — reported on the page, never silently dropped (DEC-040 D4).
 */

/** A fully-qualified name's simple form. */
export function simpleName(fqn) {
  const dot = String(fqn ?? '').lastIndexOf('.')
  return dot < 0 ? String(fqn ?? '') : String(fqn).slice(dot + 1)
}

/** The joined location the build attached to a name, or null — a plain object, never a guessed one. */
function locationOf(value) {
  const path = value?.path
  return path ? { path: String(path), line: Number.isFinite(value.line) ? value.line : 1 } : null
}

/**
 * Reads the generator's JSON (and the build's join) into the page's model.
 *
 * @param {object} json the parsed `contexts.js` module: `{contexts, locations, locationless}`
 * @returns {{contexts: Array, missing: Array<string>, locationless: Array<string>, lines: Array<object>}}
 */
export function readGraph(json) {
  // The build inlines `{contexts: [...], locations, locationless}`; a raw `contexts.json` handed in
  // directly (a test, a tool) has the same array one level down under its own `contexts` key. Both are
  // accepted rather than one — the caller should not have to know which it holds.
  const list = Array.isArray(json?.contexts) ? json.contexts : Array.isArray(json?.contexts?.contexts) ? json.contexts.contexts : []
  const locations = json?.locations && typeof json.locations === 'object' ? json.locations : {}
  const contexts = list.map((context, index) => ({
    id: String(context.context ?? `context-${index}`),
    simpleName: simpleName(context.context),
    implementation: String(context.implementation ?? ''),
    location: locationOf(context.location) ?? locationOf(locations[context.context]),
    implementationLocation: locationOf(context.implementationLocation)
      ?? locationOf(locations[context.implementation]),
    dependencies: Array.isArray(context.dependencies) ? context.dependencies.map(String) : [],
    beans: Array.isArray(context.beans) ? context.beans.map((bean) => ({
      name: String(bean.name ?? ''),
      type: String(bean.type ?? ''),
      createdBy: String(bean.createdBy ?? ''),
      location: locationOf(bean.location) ?? locationOf(locations[bean.type]),
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
  const locationless = Array.isArray(json?.locationless) ? json.locationless.map(String) : []
  return { contexts, missing, locationless, lines }
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
