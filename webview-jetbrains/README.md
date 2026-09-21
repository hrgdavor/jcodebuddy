# WebView Explorer

<!-- Plugin description -->
A JetBrains IntelliJ Platform plugin that provides a JCEF-based WebView tool window for viewing and interacting with HTML files directly within your IDE.
<!-- Plugin description end -->

A JetBrains IntelliJ Platform plugin that provides a JCEF-based WebView tool window for viewing and interacting with HTML files directly within your IDE. Pages loaded in the tool window get
`window.openFile(path, line, column)` injected, so a generated HTML report can send the IDE to the exact
source line a reader clicks.

## Features

- **WebView tool window** — displays HTML files in a JCEF (Java Chromium Embedded Framework) browser inside the IDE.
- **Keyboard shortcut** — toggle the tool window with `Ctrl+Alt+Shift+W`.
- **Context menu integration** — right-click an `.html` file in the Project view, the editor or an editor tab and choose **Open in WebView Explorer**.
- **JavaScript bridge** — every loaded page gets `window.openFile(path, line, column)`, which opens the file in an editor, puts the caret on the line and centers it. This is the path the generated entity index uses ([DEC-027 § 7](doc-hipster-entity/architecture/decisions/DEC-027.md)).
- **HTTP bridge (fallback)** — `GET /open?filePath=…&line=…&column=…` for a browser that does not have the injected function. Off until a port is configured, loopback-only, and denied unless the caller is authorized; see [HTTP bridge](#http-bridge).
- **IDE-styled scrollbars** and a `data:` splash page on first open.
- **Persistent state** — remembers the last opened URL per project.

## Requirements

| | |
| --- | --- |
| JDK | **25** — the project pins `org.gradle.java.home` and compiles with `--release 25` |
| Gradle | 9.2.1 (via the wrapper) |
| IntelliJ Platform | 2026.2.3, `since-build` 262 |
| IntelliJ Platform Gradle Plugin | 2.19.0 |

JCEF must be available in the runtime you install into. The plugin declares
`<depends>com.intellij.modules.jcef</depends>`, so an IDE without the JCEF module will refuse to load it
rather than showing a broken tool window. If JCEF is present but cannot be initialised, the tool window
says so instead of staying empty.

> **`platformBundledModules` is not a runtime dependency.** `gradle.properties` configures the *compile*
> classpath; `META-INF/plugin.xml` must also declare `<depends>` for the module, or its classes are
> missing at runtime. Getting this wrong for JCEF surfaces as
> `Cannot find suitable constructor for class JcefToolWindowFactory`, which has nothing to do with
> constructors. `PluginDescriptorTest` guards the declaration.

## Development

```bash
./gradlew runIde        # start a sandbox IDE with the plugin installed
./gradlew test          # the unit test suite
./gradlew buildPlugin   # build the distributable zip
./gradlew verifyPlugin  # check compatibility against the recommended IDEs
```

The packaged plugin lands in `build/distributions/WebView Explorer-<version>.zip`.

### Using it

1. Open a project, then press `Ctrl+Alt+Shift+W` (or right-click an HTML file → **Open in WebView Explorer**).
2. Type an address or a file path in the address bar and press Enter. A path with a drive letter or a separator is treated as a local file, a bare host is treated as `https://`, and anything with a scheme is used as-is.
3. In a generated entity index, click a field to jump to that member's source line.

### Running in a real IDE

To install into your own IDE rather than the sandbox, use **Settings → Plugins → ⚙ → Install Plugin from
Disk…** and pick the zip `buildPlugin` produced, then restart.

## The `window.openFile` contract

The plugin injects, after every main-frame load:

```js
window.openFile = function (path, line, column) { … };   // line and column default to 1
window.__jcbWebViewBridge = 1;                            // lets a page detect the bridge
```

`path` may be an absolute file path or a `file:` URL. The message is JSON (`{kind, filePath, line, column}`)
and is parsed as JSON, so a path containing quotes or the text `"line":` cannot confuse it.
`scripts/entity-html/render.js` feature-detects the function, falls back to the HTTP bridge, and finally
copies the location to the clipboard.

## HTTP bridge

For a page opened outside the IDE (a plain browser), the plugin can open files over HTTP:

```text
GET http://127.0.0.1:<port>/open?filePath=<absolute path>&line=<n>&column=<n>
GET http://127.0.0.1:<port>/health
```

Configure it in **Settings → Tools → WebView Explorer**, or with VM options (Help → Edit Custom VM Options),
which is the form the renderer's `--bridge-port` default assumes:

```text
-Dwebview.explorer.port=18881
-Dwebview.explorer.allowedOrigins=file://
-Dwebview.explorer.token=<optional shared secret>
```

**It is off while the port is empty, it binds to `127.0.0.1` only, and it denies every caller until you
authorize one.** A caller is authorized by presenting the token (as an `X-WebView-Token` header or a
`token` query parameter) or an allowed `Origin`. With no token and no origins configured, `/open` answers
`403` — that is deliberate: the endpoint can open any file in your IDE, so it does not work by accident.

Notes on the fallback:

- The bridge is **not needed** for pages inside the WebView Explorer tool window; they get the injected
  function instead.
- A browser sends no usable `Origin` header for a `file:` page, and a hidden iframe from a `file:` page may
  send none at all. If you need the fallback from a local file, set a **token** — it is the only
  authorization that does not depend on the `Origin` header.
- A request whose file does not resolve answers `404`, and the plugin logs the path; a wrong link is never
  silently swallowed.
- Navigations are rate limited (20 per 20 seconds) across both entry points.

`/health` reports what the bridge is doing:

```json
{"plugin":"hr.hrg.jetbrains.webview","port":18881,"allowedOrigins":1,"tokenRequired":false}
```

## JCEF debugging

Open the Registry (Help → Find Action → "Registry"), set `ide.browser.jcef.debug.port` to `9222`, and
restart. Chrome DevTools can then attach at <http://localhost:9222> to inspect the page inside the tool
window. `runIde` already passes `-Dide.browser.jcef.debugPort=9222`, `-Dide.browser.jcef.enabled=true` and
`-Dide.browser.jcef.gpu.disable=true`, plus the bridge port and `allowedOrigins=file://` so the HTTP
fallback can be exercised from the sandbox.

## Project structure

```
src/main/java/hr/hrg/jetbrains/webview/
├── actions/
│   ├── OpenFileInWebViewAction.java     # "Open in WebView Explorer" context menu action
│   └── ToggleToolWindowAction.java      # Ctrl+Alt+Shift+W
├── bridge/
│   ├── AllowedOrigins.java              # which origins may call /open (empty denies all)
│   ├── BridgeMessage.java               # the JSON payload the injected script sends
│   ├── Clock.java                       # injectable clock for the rate limiter
│   ├── NavigatorService.java            # the one place a path becomes an open editor
│   ├── RateLimiter.java                 # 20 navigations / 20 s, shared by both entry points
│   ├── UrlNormalizer.java               # address bar text -> URL
│   └── WebViewBridge.java               # injects window.openFile, receives its messages
├── services/
│   ├── HttpBridgeService.java           # the /open and /health HTTP fallback
│   ├── HttpBridgeStartupActivity.java   # starts the bridge when the project opens
│   └── PluginStateService.java          # per-project state: lastUrl, port, origins, token
├── settings/
│   ├── WebViewSettingsComponent.java    # the form
│   └── WebViewSettingsConfigurable.java # project configurable, parent "tools"
└── toolWindow/
    ├── JcefToolWindowFactory.java       # creates the tool window content
    ├── SplashPage.java                  # the data: page shown before anything is opened
    ├── UnsupportedBrowserPanel.java     # what is shown when JCEF is unavailable
    ├── WebViewActions.java              # the four toolbar buttons
    ├── WebViewPanel.java                # owns the browser, address bar and toolbar
    ├── WebViewService.java              # project service that owns the panel
    └── WebViewToolWindow.java           # the tool window id

src/main/resources/META-INF/plugin.xml   # extensions and actions; identity comes from build.gradle.kts
src/test/java/…                          # 52 unit tests, no IDE required
```

`WebViewPanel` owns the browser and `WebViewService` owns the panel, so nothing else has to search the
Swing component tree to find the browser.

## Technical details

- **Plugin ID**: `hr.hrg.jetbrains.webview`
- **Tool window ID**: `WebView Explorer` (`WebViewToolWindow.ID`)
- **Vendor, version, description and change notes** are patched into `plugin.xml` by `patchPluginXml` from
  `intellijPlatform { pluginConfiguration { … } }` in `build.gradle.kts`
- **`until-build` is deliberately not set**, so the plugin stays compatible with IDEs newer than the
  `since-build` in `gradle.properties`

## Further reading

- [`plan.reimplement.md`](plan.reimplement.md) — the rewrite plan and the findings behind the current
  design, including the JCEF dependency bug (F8).
- [`scripts/entity-html/README.md`](../scripts/entity-html/README.md) — the renderer whose links this
  plugin opens.
- [`doc-hipster-entity/architecture/decisions/DEC-027.md`](../doc-hipster-entity/architecture/decisions/DEC-027.md) § 7 —
  the link contract.

## License

MIT License (or as specified in the LICENSE file).
