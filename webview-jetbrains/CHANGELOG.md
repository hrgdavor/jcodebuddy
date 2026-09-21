# WebView Explorer Changelog

## [Unreleased]
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
