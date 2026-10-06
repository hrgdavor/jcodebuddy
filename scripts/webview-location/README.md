# `scripts/webview-location` — where a link points inside a file

One dependency-free module, `index.js`, that turns a link's target file and fragment into a **location**: a line, a
line range, a declaration by name, a region with a scope modifier, or a `.json` key path — or `null` when the
fragment is not a location at all.

```js
import { parseLocation, locationSummary } from './scripts/webview-location/index.js'

parseLocation('src/main/java/com/hrg/bla/SomeFile.java', 'someMethod')
// { kind: 'member', name: 'someMethod' }

parseLocation('src/main/java/com/hrg/bla/SomeFile.java', 'region:++toString')

// The same, without the prefix — it is never required, only allowed:
parseLocation('src/main/java/com/hrg/bla/SomeFile.java', '++toString')
// { kind: 'region', name: 'toString', scope: '++' }
// { kind: 'region', name: 'toString', scope: '++' }

parseLocation('doc/usage.md', 'install')
// null — a heading anchor is the page's own business

locationSummary(parseLocation('package.json', 'region:name,scripts.test'))
// 'keys name, scripts.test'   (for a link's tooltip)
```

A bare name is the same reference whether the thing it names is a declaration or a `#region` directive: the host
tries the declaration, then the region. The prefix that used to be the only way to say "region" is still allowed, and
never required. In a document a bare name is also a heading anchor, and the page's own scroll wins — a renderer knows
its headings, so it only asks a host when the name is not one.

**The grammar is not defined here.** It is defined by `webview/conformance/location-fragments.json`, whose cases are
hand-written claims, and this module is one of two readers that must satisfy them — the other is
`webview/core/webview-core`'s Java implementation, because a page renders the link and a host resolves it, and a rule
that lives in two languages is only one rule while both are checked against the same claims. Read the vectors file
for the full grammar; read `webview/conformance/README.md` for why it works that way.

```sh
bun test scripts/webview-location
```

The module is a library, not a script, and it lives in `scripts/` because that is where this repository's
dependency-free tooling belongs (AGENTS.md § 1).

**Who reads it, after 2026-10-04:** `webview-core`'s Java implementation is the *host* half, and it is the one a click
depends on; this module is the same grammar for **tools** — a link checker, a `--verify` pass, anything that wants to
classify a link without a host running. The pages that render links deliberately do **not** import it: a page puts the
whole target on `data-open` and passes it through, because it needs to know nothing about what a fragment means (the
maintainer's rule; see [`webview/README.md`](../../webview/README.md), "Pointing at a place inside a file").