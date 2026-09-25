# `webview/core` — the host-neutral half of the webview product

A page that navigates a project must behave the same whether it is rendered by a JetBrains tool window,
a VS Code webview, a browser window beside ZED, or a headless `webviewd`. Everything that decision
depends on but that is *not* about one editor lives here.

```
webview/core/webview-core/     Maven module, JDK 25: hr.hrg.webview.core
webview/core/README.md         this file
```

## What is in the module

| Type | Responsibility |
| --- | --- |
| `InjectedBridge` | the exact script that defines `window.openFile` and `window.__jcbWebViewBridge`, with the host's transport passed in as `{}` |
| `BridgeMessage` | parses the JSON that script sends; ignores unknown kinds so a newer page cannot break an older host |
| `AllowedOrigins` | the origin allow-list, where **an empty list denies everyone** |
| `RateLimiter`, `Clock` | the sliding window shared by every transport, with an injectable clock so policy is tested by moving time, not sleeping |
| `UrlNormalizer` | address-bar text (or a `#L42` URL) → what to load, with the file-existence probe injected |
| `PathResolver`, `PathResolution` | turns a page's path into an absolute one, and refuses anything that escapes the project root (the *path jail*) |
| `EditorHost`, `NullHost` | what a host must implement to be driven (open, reveal, select, capabilities), and the host that drives nothing |
| `Navigator`, `NavigationOutcome` | the one place a navigation request becomes a host call: rate limit → resolve → jail → host, returning *why* a request was refused rather than a bare false |
| `PageServer` | serves one project file to a page, and decides which files a page may read — the route that used to exist only in the VS Code host and answered `Access-Control-Allow-Origin: *` |
| `HostHealth` | the document every host answers `GET /health` with, so a page reads the same keys from all of them |
| `TextRange` | a one-based span, so nothing converts at the boundary |

## Why it exists

Before this module the same security model existed three times and disagreed with itself:

| Host | Auth | CORS on the state-changing route |
| --- | --- | --- |
| `webview/webview-jetbrains` (`HttpBridgeService`) | empty allow-list denies; token or allowed origin | sent to an allowed origin only |
| `webview/webview-vscode` (`HttpBridge.ts`) | `allowedOrigins` list, empty denies *only when an `Origin` is present* | `/file/` answers `Access-Control-Allow-Origin: *` |
| `jwa-sidecar` (`SidecarApp`) | none | `Access-Control-Allow-Origin: *` on `/jump` |

Two of the three would therefore let any page in the user's browser drive the editor. The module makes
the safe behaviour the only one a host can get by accident: `AllowedOrigins` cannot be forgotten, the
jail is inside `PathResolver`, and the rate limit is inside `Navigator`, so a host that uses them cannot
implement a weaker variant of either.

**And the shape of `/health` differed too** — four keys in the two IDE hosts, three in the sidecar, none of
them saying what the host could actually do. `HostHealth` fixes that, and
`HostHealthParityTest` fails if a host stops using it.

## Consumers

| Consumer | How |
| --- | --- |
| `webview/webview-jetbrains` | Gradle dependency on `hr.hrg.jcodebuddy:webview-core`; its `NavigatorService` is the `EditorHost`, its HTTP bridge builds `/health` with `HostHealth`, and the bridge types come from here |
| `webview/jwa-sidecar` | Maven dependency; the LSP sidecar's `/jump` uses `AllowedOrigins` + `RateLimiter` + `PathResolver`, and its `/health` uses `HostHealth` |
| `webview/webview-vscode` | cannot consume a jar: it is checked against the same decision tables in `webview/conformance/bridge-decisions.json` (origins, CORS, rate limit, and the `/health` key list), which the Java tests and the TypeScript tests both read |

The TypeScript host keeps its own {@code /file/} implementation — it cannot call a Java class — but not its
own *decisions*: `PageServerTest` covers the Java route and `BridgePolicy.test.js` covers the decode-and-jail
half of the TypeScript one, against the same rules.

## The `/health` document

```json
{"plugin":"hr.hrg.jetbrains.webview","port":18881,"allowedOrigins":1,"tokenRequired":false,
 "bridgeVersion":1,"capabilities":["open","select"]}
```

* The first four keys are the originals and keep their names and types: a page may already read them.
* `bridgeVersion` lets a page tell an old bridge from a new one — it is `InjectedBridge.VERSION`, the same
  number the page sees as `window.__jcbWebViewBridge`.
* `capabilities` is what the host can do **right now**, sorted. An empty array is the honest answer for a
  host with no editor attached, and it is how a page decides which rung of its fallback ladder to use
  instead of calling a verb and watching it fail.
* `allowedOrigins: 0` means every caller is refused — the closed default, visible to a page.

## Building

```bash
# JDK 25: the parent POM compiles with --release 25, and the shell default java is 8 here
JAVA_HOME="C:/Program Files/Java/jdk-25" mvnd -q -pl webview/core/webview-core -am verify
```

The module has no dependency on the IntelliJ Platform or on any IDE, so its tests run in the Maven
reactor in a second, with no sandbox IDE and no Gradle.
