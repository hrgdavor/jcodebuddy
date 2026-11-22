# WebView Explorer Changelog

## [1.0.1]
### Added
- **Served HTML pages.** Right-clicking an HTML file now opens it over loopback HTTP instead of `file://`, so a page can fetch its own same-origin JSON and assets. The plugin starts a page server on demand (ephemeral port when no bridge port is configured) under `/page/<one-time-token>/<project-relative path>`.
- **Existing page server setting.** Settings → Tools → WebView Explorer → **Existing page server** accepts a base URL such as `http://127.0.0.1:18884/page/`; the project-relative page path is appended as the path mapping, and no local server is started.

## [Unreleased]
### Added
- **Optional SPA navigation.** An app can opt into hash routing with `.jcodebuddy/app/navigation.json` (`{ "mode": "hash" }`) and declare `window.jcodebuddyNavIntercept(target)`; the host then changes the URL with `history.pushState` and skips the document load while the app renders in place. The default Markdown handler opts into both.
- **File-change SSE and live Markdown.** The host serves `GET /api/v1/events` over the IDE's VFS; the generated `/app/` Markdown handler re-renders on any project-file change and preserves the reader's scroll position.
- **Home is responsive again.** It no longer calls `stopLoad()` (the asynchronous stop delayed the useful load), and the fallbacks run only when no load-start event arrived: 350 ms for the in-page fallback, 500 ms later for a browser reset. A 2.5 s watchdog catches a load that started but never completed. The normal Home path is just `loadURL`.
- **Home can recover by replacing the browser.** If both a direct load and an in-page `location.replace` fail, the panel creates a fresh JCEF browser on the same tool window and loads `/app/index.html`; a stuck CEF page can no longer wedge Home permanently.
- **Home is a direct navigation.** It no longer goes through the first-page/pending-load path; it stops any stuck load, navigates the browser directly, and falls back to an in-page `location.replace` if the page still refuses after 800 ms. The log records both steps.
- **Home always targets the bridge that is actually serving.** It no longer derives the origin from whichever page is in the browser (an old port, an asset, or a `file:` page), which made the button appear to work only on some URLs.
- **The error card no longer pins the panel.** Home/Reload now switch the CardLayout back to the browser; previously the card stayed in front even after a successful load.
- **Java and other non-Markdown files render inside the app.** `?openFile=...` no longer falls back to navigating the panel to the raw `/source/...` URL, which JCEF aborted with `ERR_ABORTED`.
- **A raw `/source/…` URL is never remembered.** Older builds let a source link navigate the panel there; JCEF aborts it and every restart opened on that error card. Source links now open the IDE, and a remembered source URL is forgotten at startup.
- **The tool window moves with the bridge URL.** When the port or page token changes, an already-open WebView panel is reloaded onto the current URL instead of keeping a dead port/token. Normal clicks on source links now open the IDE rather than navigating the panel to the raw `/source/...` route (the source of the `ERR_ABORTED` entries).
- **File-change SSE no longer starves the bridge.** The HTTP server used a single-thread executor, so the long-lived `/api/v1/events` response held the only worker and made `/app`, `/source` and `/health` time out. It now uses a cached daemon pool.
- **Configurable, sticky bridge port.** An empty port setting uses **18882**; a busy port offers **Use next free port**, **Open Settings**, or **Cancel**, and saving settings checks again. The actual port is written to `.jcodebuddy/webview/host.json`, back into the project setting, and the remembered `/app/` URL is rewritten to the current port and token.

### Fixed
- **The plugin did not start at all**: the tool window failed with `Cannot find suitable constructor for class JcefToolWindowFactory`. The cause was a missing runtime dependency — `com.intellij.modules.jcef` was configured as a compile-only `platformBundledModules` entry and never declared with `<depends>` in `plugin.xml`, so every JCEF type was unresolvable. `PluginDescriptorTest` now guards the declaration.
- **Open in WebView Explorer showed the splash page and never rendered the file.** The factory delivered the URL parked by the action and then loaded its own fallback on top of it. The first-page rule now lives in `PendingLoad` and is applied in one place; `PendingLoadTest` pins the ordering.
- The HTTP bridge refused nothing when no allowed origins were configured, so any local page could open files in the IDE; it also bound to all interfaces. It now binds to loopback only and denies every caller until a token or an allowed origin is configured.
- A path that could not be resolved was silently ignored; it is now logged and answered with `404`.
- One JCEF click was charged twice against the rate limiter.
- The JCEF-internal `about:blank` load is no longer reported as a page load failure.
- The plugin no longer claims `JetBrains` as its vendor, and the changelog no longer links to the plugin template's repository.

### Changed
- The JavaScript bridge parses its payload as JSON instead of with three regular expressions, so a path containing quotes or the text `"line":` can no longer be misparsed.
- The `JBCefJSQuery` and the load handler are owned by one class and disposed with the tool window; there is now a single load handler per browser.
- `JcefToolWindowFactory` no longer builds the whole UI, and `OpenFileInWebViewAction` no longer searches the tool window's Swing tree for the browser — `WebViewService` owns the panel.
- The identity fields (`id`, `name`, `vendor`, `description`, `change-notes`) are patched into `plugin.xml` from the Gradle `pluginConfiguration` block instead of being repeated by hand.
- The dead `META-INF/withJcef.xml` was removed; the JCEF dependency is a plain `<depends>`.
- `README.md` was rewritten: the JDK requirement is 25 (not 21), and the HTTP bridge's security model is documented.
- The Kotlin plugin was dropped; the plugin is Java only.
- `verifyPluginProjectConfiguration`, `test` and `buildPlugin` are the checked tasks; 52 unit tests were added where there were none.

## [1.0.0]
### Added
- JCEF-based WebView tool window for viewing HTML files
- Keyboard shortcut (Ctrl+Alt+Shift+W) to toggle WebView Explorer
- Context menu action to open HTML files from Project View, Editor, and Editor Tabs
- JavaScript bridge for IDE integration
