/**
 * Navigation: the one call a page makes, and the fallback for a page that has no host.
 *
 * <p>This is [DEC-049](../../../doc-hipster-entity/architecture/decisions/DEC-049.md) decisions 1 and 5, and
 * root `AGENTS.md` § 2's standalone rule. The page calls **`window.openFile(path, line, column)`** — the
 * frozen, versioned contract the webview hosts install (`InjectedBridge`) — and it does **not** learn which
 * host it is in: the transport under that call is the host's business (an image beacon to `/open` for
 * `webviewd`, a `JBCefJSQuery` call for JetBrains, `postMessage` for a mirroring host).</p>
 *
 * <p>Opened as a plain `file://` document there is no injected bridge, so there is no `window.openFile` at
 * all. That is the normal case rather than a failure: the function below returns false and the page says
 * navigation needs a host, which is what keeps the page usable with nothing running.</p>
 */

/** Whether this page was served by a host that injected the bridge. */
export function canNavigate(scope = globalThis) {
  return typeof scope?.openFile === 'function'
}

/**
 * Asks the host to open a location, and says whether it was asked.
 *
 * @param {{path: string, line: number}|null|undefined} location a joined location (see `locations.js`)
 * @param {object} [scope] the object carrying the bridge — `globalThis` in the page, a stub in a test
 * @returns {boolean} true when the host was called; false when there is no bridge or nothing to open
 */
export function openLocation(location, scope = globalThis) {
  const path = location?.path
  if (!path || !canNavigate(scope)) {
    return false
  }
  const line = Number.isFinite(location.line) && location.line > 0 ? location.line : 1
  // The third argument is the frozen contract's column, and 1 is its own default: the join knows a
  // declaration's line, and a column this page invented would be a guess (DEC-016).
  scope.openFile(path, line, 1)
  return true
}

/**
 * A JSON-encoded location for a DOM attribute, or null when there is none.
 *
 * <p>The page's own parts (a block, a sidebar row) find their target through the DOM rather than through a
 * closure per element, and nodditor re-creates blocks through its factories on undo/redo — so the target has
 * to survive being written on an element.</p>
 */
export function locationAttribute(location) {
  return location?.path ? JSON.stringify({ path: location.path, line: location.line ?? 1 }) : null
}

/** The location a DOM element carries, or null — the read half of {@link locationAttribute}. */
export function locationOfElement(element) {
  const raw = element?.getAttribute?.('data-location')
  if (!raw) {
    return null
  }
  try {
    const parsed = JSON.parse(raw)
    return parsed?.path ? { path: parsed.path, line: parsed.line ?? 1 } : null
  } catch {
    return null
  }
}
