# `webview/conformance` — the decisions every host must agree on

```
bridge-decisions.json    the vectors: origin allow-list, CORS emission, rate-limit windows
location-fragments.json  the vectors: how a link names a position inside a file (a line, a range, a declaration,
                         a region with a scope modifier, a JSON key path)
README.md                this file
```

The three hosts were written independently and disagreed about the same policy. Two of the disagreements were
security bugs, not style:

| Host                | Empty allow-list                         | Absent `Origin` header | CORS on the state-changing route |
| ------------------- | ---------------------------------------- | ---------------------- | -------------------------------- |
| `webview-jetbrains` | denies everyone                          | denied                 | sent to an allowed origin only   |
| `webview-vscode`    | denied only *if* an `Origin` was present | **allowed through**    | `/file/` sent `*`                |
| `jwa-sidecar`       | no allow-list at all                     | **allowed through**    | **sent `*` to everyone**         |

The rule now lives in `webview-core` (`AllowedOrigins`, and the same table is what `HttpBridge.ts` implements),
and these vectors are what keeps the hosts from drifting apart again: a decision is written down once, and every
side asserts against it. `webviewd` and the Eclipse host were written after the vectors existed and were born
bound by them — `webviewd` through the core classes the vectors describe, the Eclipse module by asserting the
vectors against a live bridge of its own.

## The vector shapes

| Key              | Fields                                       | Meaning |
| ---------------- | -------------------------------------------- | ------- |
| `healthKeys`     | `keys`                                       | the keys of the `GET /health` document, **in order**: `plugin`, `port`, `allowedOrigins`, `tokenRequired`, `bridgeVersion`, `capabilities`, `ide`, `project`. Java's `HostHealth`, TypeScript's `healthDocument()` and both test suites assert against this one list, so a host cannot add a key the others do not answer with. The last two are what the port claim reads (DEC-033): `ide` names the editor and `project` names the directory that endpoint serves, which is how a host whose port is taken tells "another host for my project" (skip) from "a stranger" (take the next port). |
| `allowedOrigins` | `allowed`, `origin`, `expected`              | `AllowedOrigins.of(allowed).allows(origin)` must equal `expected`. A JSON `null` origin is an absent header. |
| `cors`           | `allowed`, `origin`, `sends`, `echoes`       | whether a host may emit `Access-Control-Allow-Origin` at all, and that it echoes the caller's own origin (never `*`, never a constant) |
| `rateLimit`      | `limit`, `windowMillis`, `tryAt`, `expected` | `tryAt[i]` is the millisecond offset the injected clock is set to before call `i`; `expected[i]` is what `RateLimiter.tryAcquire()` must return. Offsets are absolute from the start of the run, and a refused call is not recorded. |

## The location fragment vectors

`location-fragments.json` is a second vector file, for a different kind of rule: not host policy, but the grammar a
page and a host must read the same way. `path` and `fragment` are the input — a link's target file and the part
after its `#` — and `expect` is the location it names, or `null` when the fragment is not a location at all (a
heading anchor, a scheme-like fragment, a malformed position).

Its shape is `{ "shape": "location-fragments", "cases": [ { "why", "path", "fragment", "expect" } ] }`, and its
grammar is stated in the file itself, beside the cases, because the cases only make sense as instances of it.

It exists for the same reason as the file above it. A page renders the link; a host resolves it; if the two disagree
about `#region:++add`, one of them navigates somewhere wrong. Two readers assert against this file today —
`scripts/webview-location` (JavaScript, in `scripts/`, where the dependency-free renderers live) and
`webview/core/webview-core` (Java, the module every host builds on) — and, as above, **neither generates it**.
## Who reads it

| Reader                            | How |
| --------------------------------- | --- |
| `webview/core/webview-core`       | `ConformanceVectorsTest` loads the JSON with Gson and asserts `AllowedOrigins`, the CORS decision, `RateLimiter` and the `/health` key list produce the expected answers |
| `webview/webview-vscode`          | `src/test/BridgePolicy.test.js` loads the same file and asserts `BridgePolicy` produces the same answers, with no VS Code and no dependencies: `npm run test:unit` |
| `webview/eclipse/webview-eclipse` | `EclipseConformanceVectorsTest` loads the same file and asserts a **live** Eclipse bridge against it — the origin allow-list, the CORS decision, the rate-limit windows and the `/health` key list — so the fifth host is checked end to end, not only through the shared classes |

No reader *generates* the file. A vector is a claim about behaviour, and generating it from one
implementation would only prove that implementation agrees with itself.

## Adding a case

Add the vector first, then make every host pass it. If a host cannot — because the platform genuinely cannot
express the case — say so in that host's README rather than deleting the vector.
