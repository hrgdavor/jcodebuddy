/**
 * Where a link points, INSIDE a file — the page half of the location contract.
 *
 * A generated page links to source, and a link that can only say "line 42" cannot say what the documentation
 * actually means. The repository's own docs are written with `@hrg/inject-examples` markers, whose references name a
 * real file and a location inside it — a region or a declaration by name, with a scope modifier — because a sample
 * has to come from somewhere. This module classifies those references for the tools that read a link without a host
 * running.
 *
 * **The reference grammar is not defined here.** `@hrg/inject-examples` is the reference implementation of section
 * searching, and its `lib/section.mjs` owns the spelling of a reference: the path segments, the `-`/`+`/`++` scope,
 * the normalisation of a leading or trailing modifier, and the errors. This module asks it (`parseReference`) and
 * adds only the two rules that library's module boundary deliberately excludes — an explicit `L42`/`L42-L58`
 * position, and a `.json` key path — plus the classification a page needs. An internal spelling of the same grammar
 * is what this module used to carry, and it was removed: a second implementation drifts, and the reference wins.
 *
 * The claims are pinned by `webview/conformance/location-fragments.json`, which this module and `webview-core`'s
 * `LocationFragment` (Java) both assert against: one rule in two languages is only one rule while both are checked
 * against the same claims.
 *
 * The dependency is the one exception to `scripts/`'s dependency-free rule — see root `AGENTS.md` § 2 — and it is
 * satisfied from a local build of the package until 1.1.0 is on the registry: see this directory's `README.md`.
 */

import { SectionReferenceError, parseReference } from '@hrg/inject-examples/lib/section.mjs'

/** The kinds a fragment can be. `null` means "not a location" — an ordinary link. */
export const LOCATION_KINDS = ['line', 'range', 'region', 'json', 'member']

const isJson = (path) => /\.json$/i.test(String(path ?? ''))

/** The JSON rule's reference: dotted key paths, comma-separated, because the file's type decides the rule. */
function jsonKeys(reference) {
  const keys = reference.split(',').map((key) => key.trim()).filter((key) => key !== '')
  return keys.length ? { kind: 'json', keys } : null
}

/** The scope modifier this contract spells for a reference scope; `''` is a plain name. */
const MODIFIERS = { body: '-', annotated: '+', documented: '++' }

/**
 * Parse a fragment into a location, or `null` when it is not one.
 *
 * @param path     the file the link points at; its type decides which rule a reference follows, exactly as
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
  // 'L42-' - the reference implementation refuses it for the same reason, and the answer must not differ.
  if (/^L\d/.test(text)) {
    return null
  }

  // A colon is never part of a reference: a fragment that carries one is scheme-like, and is reported as an ordinary
  // link rather than as a member nobody named that.
  if (text.includes(':')) {
    return null
  }

  // In .json there are no declarations to find, so any bare reference is the JSON rule's key paths - checked
  // before the reference grammar, because a leading '-' is a legal key character and not a modifier there.
  if (isJson(path)) {
    return jsonKeys(text)
  }

  // The reference itself is the reference implementation's to read. It throws on a spelling that is not a
  // reference at all ('+++add', '--add', '++'), which is this module's `null`: a malformed reference is not a
  // location. Any other failure is a real one and travels.
  let reference
  try {
    reference = parseReference(text)
  } catch (error) {
    if (error instanceof SectionReferenceError) {
      return null
    }
    throw error
  }

  const name = reference.segments.join('/')
  const scope = MODIFIERS[reference.scope]
  if (scope !== undefined) {
    return { kind: 'region', name, scope }
  }

  // A bare name is a declaration OR a `#region <name>` directive: the host tries the declaration and then the
  // region, because a link should not have to know which one the author wrote. A name that is neither is caught
  // there. In a document it is a heading anchor as well, and the page's own scroll wins: a renderer knows its
  // headings, so it only asks a host when the name is not one - which is why a name needs a file type at all.
  if (String(path ?? '') !== '') {
    return { kind: 'member', name }
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
