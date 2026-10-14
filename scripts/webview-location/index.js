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
 * searching, and its `lib/section.mjs` owns the spelling of a reference: the path segments, the `-`/`+`/`++` scope as
 * a whole trailing segment, the line ranges and columns, the normalisation, and the errors. This module asks it
 * (`parseSelectors`) and adds only the classification a page needs — which of the implementation's readings this
 * contract can name, and the `.json` key path that JSON's own lack of comments forces — plus the summary a link's
 * tooltip shows. An internal spelling of the same grammar is what this module used to carry, and it was removed: a
 * second implementation drifts, and the reference wins.
 *
 * The claims are pinned by `webview/conformance/location-fragments.json`, which this module and `webview-core`'s
 * `LocationFragment` (Java) both assert against: one rule in two languages is only one rule while both are checked
 * against the same claims.
 *
 * The dependency is the one exception to `scripts/`'s dependency-free rule — see root `AGENTS.md` § 2 — and it is
 * satisfied from a local build of the package until 1.1.0 is on the registry: see this directory's `README.md`.
 */

import { SectionReferenceError, parseSelectors } from '@hrg/inject-examples/lib/section.mjs'

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

  // The reference itself is the reference implementation's to read — its path segments, its scope, its line ranges and
  // columns. It throws on a spelling that is not a reference at all ('+add', '++add', 'L0', 'L58-L42', 'L42-'), which
  // is this module's `null`: a malformed reference is not a location. Any other failure is a real one and travels.
  // This used to carry its own 'L<line>'-shaped regexes, which is the drift the reference exists to end (2026-10-13).
  let reference = null
  try {
    reference = parseSelectors(text)
  } catch (error) {
    if (!(error instanceof SectionReferenceError)) {
      throw error
    }
  }
  const selectors = reference?.selectors ?? []

  // An explicit position always wins: a page that wrote a line number meant that line. The implementation read it, so
  // this only carries its answer into this contract's kinds — a line, and the column it starts at when one was
  // written (`#L42:12`), because a caret is placed with both.
  if (selectors.length === 1 && selectors[0].kind === 'lines') {
    const { from, to } = selectors[0]
    if (from == null) {
      return null
    }
    const column = from.column ?? 1
    // A range carries the column it starts at. Its END column is not carried: a host places one caret and selects a
    // line span, so `#L42:12-L44:3` lands at (42, 12) and runs to the end of line 44 as far as the editor can.
    const location = to == null
      ? { kind: 'line', line: from.line }
      : { kind: 'range', from: from.line, to: to.line }
    // The column is written only when one was named, so a location without one keeps exactly the shape it had.
    if (column > 1) {
      location.column = column
    }
    return location
  }

  // A colon is never part of a reference: a fragment that carries one is scheme-like, and is reported as an ordinary
  // link rather than as a member nobody named that. Only reached when the implementation read no position above,
  // which is why `#L42:12` is a location and `mailto:dev@example.com` is not.
  if (text.includes(':')) {
    return null
  }

  // In .json there are no declarations to find, so any bare reference is the JSON rule's key paths - checked before
  // the reference's path grammar, because a leading '-' is a legal key character there and because a comma is a key
  // list here while the reference reads it as a list of selectors.
  if (isJson(path)) {
    return jsonKeys(text)
  }
  if (reference == null) {
    return null
  }

  // What is left is what this contract can name: ONE path selector. A list names several places and an envelope is a
  // property of a pair - neither is one location here, so a link carrying one falls back to the file (rule 2) rather
  // than being guessed at.
  if (selectors.length !== 1 || selectors[0].kind !== 'path') {
    return null
  }
  const name = selectors[0].segments.join('/')
  if (name === '') {
    return null
  }
  const scope = MODIFIERS[selectors[0].scope]
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
  // A position is a line and, when one was written, the column it starts at - the same sentence the Java reader
  // writes, so a tooltip does not depend on which language asked.
  const atColumn = location.column > 1 ? `, column ${location.column}` : ''
  switch (location.kind) {
    case 'line':
      return `line ${location.line}${atColumn}`
    case 'range':
      return `lines ${location.from}–${location.to}${atColumn}`
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
