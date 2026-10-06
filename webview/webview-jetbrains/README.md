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
- **JavaScript bridge** — every loaded page gets `window.openFile(path, line, column)`, which opens the file in an editor, puts the caret on the line and centers it. This is the path the generated entity index uses ([DEC-027 § 7](../../doc-hipster-entity/architecture/decisions/DEC-027.md)).
- **HTTP bridge (fallback)** — `GET /open?filePath=…&line=…&column=…` for a browser that does not have the injected function. Off until a port is configured, loopback-only, and denied unless the caller is authorized; see [HTTP bridge](#http-bridge).
- **IDE-styled scrollbars** and a `data:` splash page on first open.
- **Persistent state** — remembers the last opened URL per project.

## Requirements

|                                 |                                                                                   |
| ------------------------------- | --------------------------------------------------------------------------------- |
| JDK                             | **25** — the project pins `org.gradle.java.home` and compiles with `--release 25` |
| Gradle                          | 9.2.1 (via the wrapper)                                                           |
| IntelliJ Platform               | 2026.2.3, `since-build` 262                                                       |
| IntelliJ Platform Gradle Plugin | 2.19.0                                                                            |

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

> **`webview-core` must be installed first**: the injected script, its parser, the allow-list and the rate
> limiter live in `webview/core/webview-core`; run its `mvn install` before `./gradlew test`.

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

**The port you configure is a request.** With no `webview.explorer.port` setting and no `-D`, the port this
checkout is currently on (`.jcodebuddy/webview/host.json` — local, never in git) is asked for first, and
failing that the project's committed default `.jcodebuddy/conf/webview.json` (`{ "port": 18882 }`, optional)
is used, so a cloned project starts a bridge without anyone configuring their IDE; that file is tracked,
which is the point. The bridge publishes the port it actually bound in the same `host.json` (`port`, `ide`,
`project`, `plugin`, `pid`, `sticky`, `capabilities` — never the token itself), and `GET /health` says the
same things:

- the requested port is free → the bridge serves on it;
- it is held by an unrelated application, or by a bridge serving a **different** project → the bridge takes
  the next free port, up to twenty, and says so in the log and in the settings pane;
- it is held by a bridge that already serves **this** project — a second IDE window on the same folder → the
  bridge opens **nothing**. One bridge per project is the rule: a page that finds two has no way to choose,
  and an edit landing in the other IDE's buffer is worse than a page that cannot edit. The settings pane says
  which IDE is serving, and where its port is published;
- **the port is pinned** (`"sticky": true` in that `host.json`) and something else holds it → the bridge opens
  **nothing** and shows an error. A pinned port is never moved: it was chosen on purpose (a bookmark, a
  firewall rule, a second screen), so moving would break the reason it was pinned. Set the pin by editing the
  file or once with `webviewd --sticky` in the project.

**The record outlives the bridge.** Stopping the bridge — or closing the IDE — leaves `host.json` in place,
because it is the checkout's port rather than the process's: that is what the next start asks for, and where
the pin lives. Delete the file to go back to the project's default.

Both behaviours come from `webview-core`'s `HostPortClaim`, shared with the other four hosts — the Eclipse
bridge and `webviewd` call it directly, and VS Code's `BridgePolicy.decidePort` asserts the same decisions
from the shared conformance vectors ([`../doc/webview-host-api.md`](../doc/webview-host-api.md) § 4a).

Notes on the fallback:

- The bridge is **not needed** for pages inside the WebView Explorer tool window; they get the injected
  function instead.
- A browser sends no usable `Origin` header for a `file:` page, and a hidden iframe from a `file:` page may
  send none at all. If you need the fallback from a local file, set a **token** — it is the only
  authorization that does not depend on the `Origin` header.
- A request whose file does not resolve answers `404`, and the plugin logs the path; a wrong link is never
  silently swallowed.
- Navigations are rate limited (20 per 20 seconds) across both entry points.

`/health` reports what the bridge is doing, in the document every host answers with (`HostHealth` in
`webview-core`, so the shape cannot drift between the hosts):

```json
{"plugin":"hr.hrg.jetbrains.webview","port":18881,"allowedOrigins":1,"tokenRequired":false,
 "bridgeVersion":1,"capabilities":["open","select"],"ide":"IntelliJ IDEA",
 "project":"D:/wrk/java/jcodebuddy"}
```

`capabilities` is empty while no project editor is available, and `allowedOrigins: 0` means every caller is
refused. `bridgeVersion` is the same number a page sees as `window.__jcbWebViewBridge`. `ide` is the product
name of the IDE you are actually in — "IntelliJ IDEA", "PyCharm", "RustRover" — read from the platform, and
`project` is the directory this endpoint serves; a second host reads both to tell "another bridge for my
project" from a stranger.

## JCEF debugging

Open the Registry (Help → Find Action → "Registry"), set `ide.browser.jcef.debug.port` to `9222`, and
restart. Chrome DevTools can then attach at <http://localhost:9222> to inspect the page inside the tool
window. `runIde` already passes `-Dide.browser.jcef.debugPort=9222`, `-Dide.browser.jcef.enabled=true` and
`-Dide.browser.jcef.gpu.disable=true`, plus the bridge port and `allowedOrigins=file://` so the HTTP
fallback can be exercised from the sandbox.

## The Java language server (the JWA sidecar)

Since step 3.0q this plugin also runs `webview/jwa-sidecar` as the project's **Java language server**, through
the platform's `platform.lsp.serverSupportProvider` extension point. That capability came from the earlier
`webview/intellij-jwa` attempt, whose registration was the only place it existed; the Zed extension does the same
thing for Zed.

Build the sidecar once with
`bun scripts/mvn-jdk25.js -pl webview/jwa-sidecar -am package`, and it is found automatically. Discovery order:
a per-project property, an IDE-wide property, the copy bundled in the installed plugin, then the development build
under the project's base path. Nothing found means the language server reports an error naming every path it
looked in; the tool window is unaffected.

| Setting (`PropertiesComponent`)    | Meaning                                                                  |
| ---------------------------------- | ------------------------------------------------------------------------ |
| `webview.explorer.sidecarJarPath`  | The sidecar JAR. Empty means: bundled, then `<project base path>/webview/jwa-sidecar/target/jwa-sidecar.jar` |
| `webview.explorer.sidecarJavaHome` | The JDK to run it with. Empty means `JAVA_HOME`, then `java` from `PATH` |

These are properties rather than settings-page fields deliberately, and the earlier attempt's knob was the same
kind: a half-filled settings page would be worse than two documented properties. `SidecarLaunch` holds the rules
and `SidecarLaunchTest` asserts each fallback chain without an IDE.

| Name                                                                   | Kind                              | Refactor-sensitive?                      | Why                                                                                         |
| ---------------------------------------------------------------------- | --------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------- |
| `hr.hrg.watch2.sidecar.SidecarApp`                                     | the class the descriptor launches | **no — an external contract**            | it is the sidecar artifact's own entry point; renaming the Java class without this string breaks the launch |
| `jwa-sidecar.jar`                                                      | the artifact's `finalName`        | **no — an external contract**            | the name a built sidecar is packaged under, and the name the earlier IDE clients looked for |
| `webview.explorer.sidecarJarPath` / `webview.explorer.sidecarJavaHome` | property keys                     | **no — an explicit configuration label** | a developer types them; an IDE rename must never touch them                                 |

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

- [`webview/kit/doc/page-authoring.md`](../kit/doc/page-authoring.md) — how to build a page that
  navigates a project: the client ladder, the two link-base spellings, offline syntax highlighting,
  verification and troubleshooting.
- [`webview/kit/`](../kit/README.md) — the consumer half: the frozen contract, the authoring guide, the
  write contract, and the runnable examples. This is what a project that only builds pages copies.
- [`webview/kit/examples/`](../kit/examples/README.md) — the same navigator as one self-contained file and as a
  page + `assets/` folder, with a smoke test that verifies every link.
- [`plan.reimplement.md`](plan.reimplement.md) — the rewrite plan and the findings behind the current
  design, including the JCEF dependency bug (F8).
- [`scripts/entity-html/README.md`](../../scripts/entity-html/README.md) — the renderer whose links this
  plugin opens.
- [`doc-hipster-entity/architecture/decisions/DEC-027.md`](../../doc-hipster-entity/architecture/decisions/DEC-027.md) § 7 —
  the link contract.

## License

MIT License (or as specified in the LICENSE file).

## Markdown files, rendered by the IDE

A `.md` file opens in the tool window as a clickable page, without any manual rendering step:

- **From the Project view, an editor or an editor tab**: right-click the file and pick **Open in WebView Explorer**
  (the action offers itself for `.md`, `.markdown` and `.html` alike).
- **From the address bar**: paste a path to a `.md` file, or the `file:` URL a browser gives you.

What happens then: the IDE reads the file, substitutes it into one **self-contained** HTML page and writes that page
into the project's `.jcodebuddy/webview/markdown-view/` (derived state, ignored by git), then loads it. The page
renders the Markdown with this repository's own renderer and colours code fences with **microlighter** - which has a
**Java** grammar and no Kotlin one, so Kotlin fences stay plaintext on purpose (DEC-043).

**Saving re-renders in place.** The page exposes `window.__renderMarkdown()`, so a save of the file on screen pushes
the new text into the live page - no reload, so the reader keeps their scroll position. Only a save counts: the
listener asks `VFileEvent.isFromSave()`, so the page does not flicker while somebody types. `MarkdownView` in
`toolWindow/` owns the routing and the listener.

**Links in the rendered page navigate the IDE**, including to a method or a region: the page writes the whole
location into `data-open` (plan step 9.7) and the injected bridge resolves it through the shared core. **A file
outside the project is refused** - by the address bar, by the action and by the page's links alike (2026-10-04).

**Configuring the generator — optional, per project.** The page comes from the one **embedded in the plugin** unless
this project points somewhere else. That serves two different purposes: **developing the renderer** (point at a
checkout's `markdown-view/page.js` and see your edits in the IDE without rebuilding the plugin) and **customising**
the output (your own generator, or a page you built yourself).

Two spellings are accepted, because they are the two things a person has in hand:

- a **`.js` generator** — run with Bun and asked for an inlined template (`bun run <path> <out> inlined --template`),
  which is what the project's own `page.js` is;
- an **`.html` page** — read as the template directly.

Either way it must carry the view-data marker (`__MARKDOWN_VIEW_DATA__`), or the setting is **refused**: a log
line says so and the embedded page is used. A page without the marker would render nothing and say nothing, which is
worse than falling back.

Resolution is the same shape as the port's, and for the same reason — what this checkout decided, then what the
project committed, then the embedded default:

1. the project's IDE setting (`PluginStateService`), for a person's own override;
2. the project's committed `.jcodebuddy/conf/webview.json`:

   ```json
   { "markdownGenerator": "tools/my-markdown-page.js" }
   ```

   which is how a team shares a customisation, and it is the same file the port default lives in;
3. otherwise the page the Gradle task `markdownPage` builds into the plugin.

The configured generator is resolved through the same `PathResolver` as every other path this plugin touches, so a
generator outside the project root is refused (2026-10-04).
**The build needs Bun.** `./gradlew buildPlugin` first runs `markdownPage`, which invokes
`markdown-view/page.js` to build that page - one source of truth, and the plugin ships the result, so the IDE
never needs a bundler, a server or `node_modules` at view time. Bun is already this repository's tooling for every
script and check, so this adds no new kind of dependency; a machine without it fails the build with the command that
fixes it rather than shipping a Markdown view that cannot render.
