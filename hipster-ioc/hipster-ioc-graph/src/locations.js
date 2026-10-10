/**
 * The join: a generator FQN and its project-relative source location.
 *
 * <p>This is plan step 3.11a and [DEC-049](../../../doc-hipster-entity/architecture/decisions/DEC-049.md)
 * decision 2–4. The page's model names contexts and beans by fully qualified name, and
 * `.jcodebuddy/index/classes.json` (DEC-029) is the file that turns an FQN into a place: one row per type the
 * module compiles, keyed by FQN, carrying its `path` (module-relative, forward slashes) and the declaration's
 * `line`. The join happens **here, at build time**, because the built page is one self-contained file and a
 * `file://` document may not fetch a sibling (measured in step 3.8) — so the page keeps working with nothing
 * running, which is root `AGENTS.md` § 2's standalone rule.</p>
 *
 * <p>Two rules decide what this module does with what it finds, and both come from existing decisions:</p>
 * <ul>
 *   <li><b>Report, never guess</b> (DEC-040 D4; DEC-016's "never a guessed position"): an FQN the index does
 *       not know is listed in {@link joinLocations}'s `locationless` result instead of being dropped or
 *       matched by simple name. `ObjectMapper` is the real case this exists for: `contexts.json` records a
 *       bean's type as the *simple* name written in the context's own source, and the index is keyed by FQN,
 *       so the bean is named in the page and not navigable — which the page then says.</li>
 *   <li><b>The link a host resolves is project-relative</b> (`Navigator` confines every navigation to the
 *       project root, so a module-relative path is refused as "not a usable path" the moment the module is not
 *       the project). The index carries the path relative to the **module**, so the module's own directory
 *       prefixes it: `hipster-ioc/hipster-ioc-test/` + `src/test/java/…`. The module directory comes from the
 *       build (`--project`, or the module path it already knows), and a caller that cannot say where the
 *       project root is gets an <b>error</b> rather than a path silently aimed at the wrong file.</li>
 * </ul>
 */

/** The class-index format this reader understands. A newer file is refused rather than half-read. */
export const INDEX_FORMAT = 1

/**
 * The one path a host can resolve: the module's `path`, prefixed by the module's directory inside the project.
 *
 * @param {string|undefined} moduleDir the module directory relative to the project root (`hipster-ioc/hipster-ioc-test`), or '' when the module is at the root
 * @param {string|undefined} filePath the index row's module-relative path
 * @returns {string|null} a forward-slashed, project-relative path, or null when there is nothing to join
 */
export function projectPath(moduleDir, filePath) {
  const file = String(filePath ?? '').replaceAll('\\', '/').replace(/^\/+/, '')
  if (file === '') {
    return null
  }
  const dir = String(moduleDir ?? '').replaceAll('\\', '/').replace(/^\/+|\/+$/g, '')
  return dir === '' ? file : `${dir}/${file}`
}

/**
 * The class-index document's rows, keyed by FQN.
 *
 * @param {object} json the parsed `classes.json`
 * @returns {Record<string, object>}
 * @throws when the file is a format this reader does not know — a half-understood index is how a page ends
 *         up pointing at lines that moved
 */
export function classRows(json) {
  const format = json?.format
  if (format !== INDEX_FORMAT) {
    throw new Error(`classes.json is format ${format}, this reader knows ${INDEX_FORMAT}`)
  }
  const classes = json?.classes
  if (classes === null || typeof classes !== 'object') {
    throw new Error('classes.json carries no classes object')
  }
  return classes
}

/**
 * The location of one FQN, or null when the index does not know it.
 *
 * @param {Record<string, object>} rows the class index, keyed by FQN
 * @param {string} moduleDir the module directory inside the project
 * @param {string|undefined} fqn
 * @returns {{path: string, line: number}|null}
 */
export function locationOf(rows, moduleDir, fqn) {
  if (!fqn) {
    return null
  }
  const row = rows[fqn]
  const path = projectPath(moduleDir, row?.path)
  if (path === null) {
    return null
  }
  const line = Number.isFinite(row?.line) && row.line > 0 ? row.line : 1
  return { path, line }
}

/**
 * Joins every name the graph projection carries to a place in the project.
 *
 * @param {{contexts?: Array}} contexts the generator's `contexts.json`
 * @param {Record<string, object>} rows the class index (see {@link classRows})
 * @param {{moduleDir?: string}} [options] where the module sits inside the project root
 * @returns {{locations: Record<string, {path: string, line: number}>, locationless: string[]}}
 */
export function joinLocations(contexts, rows, { moduleDir = '' } = {}) {
  const locations = {}
  const locationless = []
  /** One entry per unresolved FQN, with the first owner that asked for it — a repeated name is one absence. */
  const reported = new Set()
  const report = (fqn, owner, what) => {
    const name = String(fqn ?? '')
    if (name === '' || name in locations || reported.has(name)) {
      return
    }
    reported.add(name)
    locationless.push(`${name} (${what} of ${owner})`)
  }
  const place = (fqn, owner, what) => {
    if (fqn === undefined || fqn === null || fqn === '') {
      // Nothing was named, so nothing is missing: a context may declare no implementation, and an empty entry
      // in `locationless` would read as a defect in the index rather than as an absent fact.
      return null
    }
    const at = locationOf(rows, moduleDir, fqn)
    if (at === null) {
      report(fqn, owner, what)
      return null
    }
    locations[fqn] = at
    return at
  }

  for (const context of Array.isArray(contexts?.contexts) ? contexts.contexts : []) {
    const id = String(context.context ?? '')
    context.location = place(id, id, 'context')
    for (const bean of Array.isArray(context.beans) ? context.beans : []) {
      // A bean's type may be a simple name, and the index cannot answer one: that is the reported case, not
      // an error (DEC-049 decision 4 — the FQN belongs in the generator's projection, not in a page guess).
      bean.location = place(bean.type, id, `bean ${bean.name ?? ''}`)
    }
    context.implementationLocation = place(context.implementation, id, 'implementation')
  }
  return { locations, locationless }
}
