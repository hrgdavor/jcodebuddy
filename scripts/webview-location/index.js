/**
 * Where a link points, INSIDE a file — the whole location grammar in one dependency-free module.
 *
 * A generated page links to source, and a link that can only say "line 42" cannot say what the documentation
 * actually means. The repository's own docs are written with `@hrg/inject-examples`, whose markers name a real file
 * and a location inside it — a region, or a declaration by name, with a scope modifier — because a sample has to come
 * from somewhere. This module reads those spellings, and the ones that are only a location (a method name, a line
 * range), so a page can hand a host something better than a guessed line number.
 *
 * The contract this serves is `webview/kit/doc/contract.md`: `data-open` carries the path, `data-fragment` carries
 * the fragment this module parses, and `data-member` stays what the contract says it is — display metadata, never a
 * routing key.
 *
 * The grammar is pinned by `webview/conformance/location-fragments.json`, which the host side
 * (`webview-core`'s `LocationFragment`) asserts against as well: one rule in two languages is only one rule while
 * both are checked against the same claims.
 */

/** The kinds a fragment can be. `null` means "not a location" — an ordinary link. */
export const LOCATION_KINDS = ['line', 'range', 'region', 'json', 'member']

/** Extensions whose bare fragment is a heading anchor rather than a declaration. */
const DOCUMENT_EXTENSIONS = new Set(['md', 'markdown', 'html', 'htm'])

const isJson = (path) => /\.json$/i.test(String(path ?? ''))
const isDocument = (path) => DOCUMENT_EXTENSIONS.has(extensionOf(path))

function extensionOf(path) {
  const match = /\.([a-z0-9]+)$/i.exec(String(path ?? ''))
  return match ? match[1].toLowerCase() : ''
}

/**
 * Parse a fragment into a location, or `null` when it is not one.
 *
 * @param path     the file the link points at; its type decides which rule a `region:` reference follows, exactly as
 *                 it does for inject-examples (`.json` has no comments to hang a region on, so its reference is a
 *                 list of dotted key paths)
 * @param fragment the part after `#`; one leading `#` is tolerated
 * @returns {{kind: string, line?: number, from?: number, to?: number, name?: string, scope?: string, keys?: string[]}|null}
 */
export function parseLocation(path, fragment) {
  if (typeof fragment !== 'string') {
    return null
  }
  // A page may hand over the fragment with its '#' or without; both mean the same thing.
  const text = fragment.startsWith('#') ? fragment.slice(1) : fragment
  if (text === '') {
    return null
  }

  // An explicit position always wins: a page that wrote a line number meant that line.
  const range = /^L(\d+)-L(\d+)$/.exec(text)
  if (range) {
    return { kind: 'range', from: Number(range[1]), to: Number(range[2]) }
  }
  const line = /^L(\d+)$/.exec(text)
  if (line) {
    return { kind: 'line', line: Number(line[1]) }
  }

  // A fragment that starts like a position and is not one ('L42-') is a page's mistake, not a declaration called
  // 'L42-' - the same reasoning that makes '+++add' malformed rather than a region called '+add'.
  if (/^L\d/.test(text)) {
    return null
  }

  if (text.startsWith('region:')) {
    const reference = text.slice('region:'.length).trim()
    if (reference === '') {
      return null
    }
    if (isJson(path)) {
      const keys = reference.split(',').map((key) => key.trim()).filter((key) => key !== '')
      return keys.length ? { kind: 'json', keys } : null
    }
    // The scope modifier comes off the front, and only the three spellings inject-examples defines are modifiers: a
    // name may not start with '-' or '+' afterwards, which is what makes '+++add' malformed rather than a region
    // called '+add'.
    let scope = ''
    let name = reference
    if (reference.startsWith('++')) {
      scope = '++'
      name = reference.slice(2)
    } else if (reference.startsWith('+')) {
      scope = '+'
      name = reference.slice(1)
    } else if (reference.startsWith('-')) {
      scope = '-'
      name = reference.slice(1)
    }
    if (name === '' || name.startsWith('+') || name.startsWith('-')) {
      return null
    }
    return { kind: 'region', name, scope }
  }

  // 'region:' is the only prefix this grammar owns. Anything else with a colon is a scheme-like fragment, and a
  // fragment that looks like one but is not (a mis-cased 'REGION:') is a mistake to report as an ordinary link
  // rather than a member nobody named that.
  if (text.includes(':')) {
    return null
  }

  // A bare fragment is a declaration only where there is a file type to tell one from a heading anchor: in a
  // document the page scrolls to the heading itself, and with no path at all there is nothing to decide on.
  if (!isDocument(path) && String(path ?? '') !== '') {
    return { kind: 'member', name: text }
  }

  return null
}

/** A short human description of a location, for a link's tooltip. */
export function locationSummary(location) {
  if (!location) {
    return ''
  }
  switch (location.kind) {
    case 'line':
      return `line ${location.line}`
    case 'range':
      return `lines ${location.from}–${location.to}`
    case 'member':
      return `member ${location.name}`
    case 'region': {
      const scope =
        location.scope === '-'
          ? ' (body only)'
          : location.scope === '+'
            ? ' (with annotations)'
            : location.scope === '++'
              ? ' (with annotations and doc comment)'
              : ''
      return `region ${location.name}${scope}`
    }
    case 'json':
      return `keys ${location.keys.join(', ')}`
    default:
      return ''
  }
}
