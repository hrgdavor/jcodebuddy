# `scripts/webview-location` — where a link points inside a file

One module, `index.js`, that turns a link's target file and fragment into a **location**: a line, a line range, a
declaration by name, a region with a scope modifier, or a `.json` key path — or `null` when the fragment is not a
location at all.

```js
import { parseLocation, locationSummary } from './scripts/webview-location/index.js'

parseLocation('src/main/java/com/hrg/bla/SomeFile.java', 'someMethod')
// { kind: 'member', name: 'someMethod' }

parseLocation('src/main/java/com/hrg/bla/SomeFile.java', '++toString')
// { kind: 'region', name: 'toString', scope: '++' }

parseLocation('doc/usage.md', 'install')
// { kind: 'member', name: 'install' } — a document's bare name is its own heading anchor first

locationSummary(parseLocation('package.json', 'name,scripts.test'))
// 'keys name, scripts.test'   (for a link's tooltip)
```

A bare name is the same reference whether the thing it names is a declaration or a `#region` directive: the host
tries the declaration, then the region. In a document a bare name is also a heading anchor, and the page's own scroll
wins — a renderer knows its headings, so it only asks a host when the name is not one.

**The reference grammar is not implemented here.** `@hrg/inject-examples` is the reference implementation of section
searching: its `lib/section.mjs` owns the spelling of a reference — the path segments, the `-`/`+`/`++` scope, the
normalisation of a leading or a trailing modifier, and the errors — and this module **asks it** (`parseReference`)
instead of spelling a second copy. What is left here is only what that library's module boundary deliberately
excludes: an explicit `L42`/`L42-L58` position, a `.json` key path, and the classification a page needs. Any internal
implementation of section searching complies with the reference; a disagreement is a bug here, never there.

**The claims** are `webview/conformance/location-fragments.json`, written by hand, and this module is one of two
readers that must satisfy them — the other is `webview/core/webview-core`'s `LocationFragment`, which asks the Java
port of the same implementation (`hr.hrg.inject:inject-examples`). Read the vectors file for the full grammar; read
`webview/conformance/README.md` for why it works that way.

```sh
bun test scripts/webview-location
```

**The dependency, and the exception that allows it (recorded 2026-10-11).** This is the one thing under `scripts/`
that imports a package: root `AGENTS.md` § 2 keeps this directory's tooling dependency-free, and the exception is
written there. It is not a convenience — the alternative is the second grammar the exception exists to delete. The
module still has no `package.json` of its own, no `node_modules` under `scripts/`, no bundler, no CDN and no network
at view time; it uses the repository's root dependency set.

**Until `@hrg/inject-examples` 1.1.0 is published, the subpath comes from a local build.** `lib/section.mjs` first
ships in 1.1.0, and the registry still has 1.0.1, so `npm ci` alone cannot satisfy the import yet — the same posture
the Java matcher is consumed in from `~/.m2` until it reaches Maven Central:

```sh
# in the inject-examples checkout
npm pack --pack-destination <dir>

# in this checkout
npm install --no-save --no-package-lock <dir>/hrg-inject-examples-1.1.0.tgz
```

`--no-save --no-package-lock` is deliberate: the declared range already admits 1.1.0, so a consumer's committed
`package.json` and lockfile stay valid for everyone else, and the publish is the event that ends the local install.

**Who reads it:** `webview-core`'s Java implementation is the *host* half, and it is the one a click depends on; this
module is the same classification in JavaScript for **tools** — a link checker, a `--verify` pass, anything that wants
to classify a link without a host running. The pages that render links deliberately do **not** import it: a page puts
the whole target on `data-open` and passes it through, because it needs to know nothing about what a fragment means
(the maintainer's rule; see [`webview/README.md`](../../webview/README.md), "Pointing at a place inside a file").
