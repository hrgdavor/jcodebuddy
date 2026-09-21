# Plan — reimplement `webview-jetbrains` on the current IntelliJ Platform plugin template

Status: **implemented** (steps 1–10 done; see "Implementation record" below)
Scope: the entire `webview-jetbrains` module (`hr.hrg.jetbrains.webview`, "WebView Explorer")
Written against: IntelliJ Platform Gradle Plugin **2.19.0**, IntelliJ IDEA **2026.2.3**, Gradle **9.2.1**,
JDK **25** — all already pinned in this module's `gradle.properties` / `gradle/libs.versions.toml`.

Baseline verified while writing this plan: `.\gradlew.bat compileJava` succeeds on JDK 25 with the
committed sources. Step 0 below is therefore a manual smoke test, not a "does it build" question.
(Gradle needs write access to `C:\Users\hrg\.gradle` — outside this repository — so a sandboxed shell
must be widened before any Gradle command.)

> **Update — the plugin did not start at all, and the cause is fixed.** The committed
> `src/main/resources/META-INF/plugin.xml` was missing
> `<depends>com.intellij.modules.jcef</depends>`, so every JCEF type was unresolvable at runtime and
> the tool window failed with a misleading *"Cannot find suitable constructor"* error (see F8 in
> §2.3). One line was added and the fix was verified in a real `runIde` sandbox.

## Implementation record

All ten steps are done. What was built, and what proves it:

| Step | Outcome |
| --- | --- |
| 1 | Pure logic extracted: `BridgeMessage`, `UrlNormalizer`, `AllowedOrigins`, `RateLimiter` + injectable `Clock`, and `NavigatorService` as the single path→editor entry point |
| 2 | **52 unit tests** in 9 classes, `gradlew test` green; no IDE required |
| 3 | Identity moved into `intellijPlatform { pluginConfiguration { … } }`; vendor/URL fixed; catalog cleaned; Kotlin plugin and `withJcef.xml` removed; `until-build` deliberately absent |
| 4 | `withJcef.xml` deleted and replaced by the plain `<depends>com.intellij.modules.jcef</depends>` |
| 5 | `WebViewPanel` owns the browser, `WebViewService` owns the panel, `WebViewActions` names the four buttons, `JBCefScrollbarsHelper` applied, load errors shown in an error card, splash page added |
| 6 | `JcefBridgeNew` replaced by `WebViewBridge`: one `JBCefJSQuery`, one load handler, JSON parsing, disposed with the panel |
| 7 | HTTP bridge binds to loopback, denies when unauthenticated, accepts a token or an allowed origin, single rate limit, `/health`, `404` + log on an unresolvable path |
| 8 | Port validation reports instead of swallowing, bridge status line, optional token field |
| 9 | README rewritten (JDK 25, the JCEF `<depends>` rule, the bridge's security model), CHANGELOG entry |
| 10 | `clean test buildPlugin verifyPluginProjectConfiguration` green; `verifyPlugin` reports **Compatible** against IU-262.10968.63 and IU-263.5153.40 |

Acceptance criteria 1, 3, 4, 6 and 8 are verified as recorded in the table above and below. Criterion 2
(the report renders and a field link lands on the right line) and criterion 5 (degrading to the
clipboard path) were confirmed by the maintainer in a running IDE, not by an automated assertion: the
sandbox IDE exposed no CDP target for the tool window, so the live-browser check could not be completed
programmatically. Criterion 7's disposal path is covered by the single-owner design and the
`Content.setDisposer(panel)` wiring rather than by a leak assertion.

### A second defect the plan did not anticipate

The first implementation had no owner for "which page does the tool window show first". The factory
delivered a URL parked by **Open in WebView Explorer** and then loaded its own fallback — the splash
page — on top of it, so the file never rendered. The rule now lives in `PendingLoad` and is applied in
exactly one place (`WebViewService.register`), with `PendingLoadTest` pinning the ordering.


---

## 1. Why this document exists

`webview-jetbrains` is the module the rest of JCodeBuddy depends on for **one specific job**: it is how a
developer reads a generated HTML report from inside the IDE. `scripts/entity-html` renders
`.jcodebuddy/metadata/entity/index.html`, and `hipster-entity-example/codebuddy.md` §3.10 tells the reader
to right-click that file and choose **Open in WebView Explorer**. Link clicks in that page then have to
reach the exact source line, through `window.openFile(path, line, column)` (DEC-027 §7).

The module already uses the *modern* build plugin (2.19.0, not the legacy 1.x `org.jetbrains.intellij`),
so this is **not** a "port from the old build system" job. It is a job of:

1. closing the gaps between what is committed and what the current template/JCEF guidance expects, and
2. replacing the parts that were written as a prototype (`JcefBridgeNew`, the regex message parser, the
   unauthenticated HTTP bridge, duplicated load handlers) with one coherent, tested implementation.

The module has **zero test sources** while declaring `junit` + `opentest4j` and
`testFramework(TestFrameworkType.Platform)` — a reimplementation is the moment to fix that.

---

## 2. What the module does today — verified inventory

### 2.1 Source files (11 commits' worth of behaviour in 9 files)

| File | Role |
| --- | --- |
| `src/main/java/.../toolWindow/JcefToolWindowFactory.java` | Builds the whole UI: JCEF browser, address bar, back/forward/refresh/settings actions, load handler, initial HTML, `reloadWithFile` static entry point for the action |
| `src/main/java/.../toolWindow/JcefBridgeNew.java` | Injects `window.openFile(path,line,col)` on every load; parses the incoming message with **three regexes** |
| `src/main/java/.../services/HttpBridgeService.java` | `com.sun.net.httpserver` on `webview.explorer.port`; `/open` endpoint; Origin allow-list; 20-requests/20-s rate limiter; `navigateToFile` |
| `src/main/java/.../services/HttpBridgeStartupActivity.java` | `ProjectActivity` whose only statement forces the service to initialise so the port starts at project open |
| `src/main/java/.../services/PluginStateService.java` | `@State` in the **workspace** file: `lastUrl`, `port`, `allowedOrigins` |
| `src/main/java/.../settings/WebViewSettingsConfigurable.java` | Project `Configurable`, parent `tools` |
| `src/main/java/.../settings/WebViewSettingsComponent.java` | `FormBuilder` panel: port field, allowed-origins text area |
| `src/main/java/.../actions/ToggleToolWindowAction.java` | `Ctrl+Alt+Shift+W`, id `WebView Explorer` |
| `src/main/java/.../actions/OpenFileInWebViewAction.java` | Project view / editor / editor-tab popup, **HTML-only**, `file:///` + cache-busting `?v=<millis>` |

Resources: `META-INF/plugin.xml` (tool window, 2 project services, `postStartupActivity`,
`projectConfigurable`, 2 actions) and `META-INF/withJcef.xml` (a second, **unreferenced** copy of the
tool-window registration).

### 2.2 External contracts that must not break

| Contract | Consumer | Where it is stated |
| --- | --- | --- |
| `window.openFile(path, line, column)` injected into every loaded page | `scripts/entity-html/render.js` line 473 | DEC-027 §7 |
| `GET http://127.0.0.1:<port>/open?filePath=…&line=…&column=1` | `render.js` line 479, hidden-iframe fallback | `scripts/entity-html/README.md` `--bridge-port` (default `18881`) |
| VM options `-Dwebview.explorer.port`, `-Dwebview.explorer.allowedOrigins` | `README.md` §"VM options" | `HttpBridgeService.loadAllowedOrigins/startServerIfNeeded` |
| Tool window id `WebView Explorer`, action text "Open in WebView Explorer", `Ctrl+Alt+Shift+W` | `codebuddy.md` §3.10, `hipster-entity-example/README.md` | `plugin.xml` |
| "Open in WebView Explorer" appears for `.html` files in Project view, editor, editor tab popups | same | `OpenFileInWebViewAction` |

### 2.3 Defects and drift found while reading the code

| # | Finding | Evidence |
| --- | --- | --- |
| F1 | **The bridge message is parsed by regex, not JSON** — a path containing `"line":` or a quote breaks it; the three `find()` calls share one `Matcher`-per-pattern sequence that happens to work only because each pattern is matched once | `JcefBridgeNew.handleMessage` |
| F2 | **`System.out.println` as logging** in the bridge (4 call sites) instead of `Logger` | `JcefBridgeNew` |
| F3 | **Two independent load handlers on the same browser**, each calling `executeJavaScript`: one in `JcefBridgeNew` (injects the function), one in the factory (address bar + `lastUrl`). Ordering is implicit | `JcefBridgeNew` ctor, `JcefToolWindowFactory.createBrowserContent` |
| F4 | **The JS-to-plugin message shape is hand-rolled twice** — the injected function builds `JSON.stringify`, the Java side re-parses it with regexes; there is no shared record | same |
| F5 | **Rate limiting is applied twice** for one JCEF click — `navigateToFile` calls `checkRateLimit()`, and the HTTP path calls it in `GlobalHandler` before `OpenFileHandler` | `navigateToFile`, `GlobalHandler` |
| F6 | **An empty Origin allow-list is fail-open.** `loadAllowedOrigins` matches any Origin against an empty set; the guard at `/open` is `if (!isAllowed)`, and `isAllowed` is `origin != null && allowedOrigins.contains(...)` — so with no `allowedOrigins` configured (the default), *every* web page that can reach the port gets its `filePath` opened in the IDE. The `POST`-style CSRF surface is real: any local browser tab can drive it | `GlobalHandler`, `loadAllowedOrigins` |
| F7 | **The server binds to all interfaces** — `new InetSocketAddress(port)` resolves to `0.0.0.0:port`, so the `/open` endpoint is reachable from the LAN even though it is documented as `127.0.0.1` | `startServerIfNeeded` |
| F8 | **FIXED — no `com.intellij.modules.jcef` `<depends>`, which made the whole plugin fail to start.** The module was declared as a build-time `platformBundledModules = com.intellij.modules.jcef` only. `com.intellij.modules.jcef` is a **content module whose jar is not on the plugin classloader's parents unless it is declared with `<depends>`**, so `JBCefApp`, `JBCefBrowser`, `JBCefBrowserBase` and `JBCefJSQuery` — every one of them lives in `intellij.platform.ui.jcef.jar` — were unresolvable at runtime. `JcefToolWindowFactory` has method descriptors naming `JBCefBrowser`/`JBCefBrowserBase`, so JVM reflective member resolution failed and `MethodHandles.Lookup.findConstructor` reported *no* constructor, producing the misleading `Cannot find suitable constructor for class JcefToolWindowFactory, expected (), (CoroutineScope), (Application), or (Application, CoroutineScope)` — a no-arg constructor was present in the bytecode all along. **Confirmed fixed** by adding `<depends>com.intellij.modules.jcef</depends>` and re-running `runIde`: 0 occurrences of the error, tool window created. | `gradle.properties` vs `plugin.xml`; reproduced and fixed in a sandbox IDE |
| F9 | **`withJcef.xml` is dead.** Nothing references it; `plugin.xml` registers the tool window unconditionally, so the file expresses an intent (JCEF-only content) that the build does not implement | `withJcef.xml` vs `plugin.xml` |
| F10 | **No graceful degradation.** `addErrorContent` is good, but the *actions* remain enabled and the toolbar is gone; there is no `CefLoadHandler.onLoadError` handling, so a failed load is an empty tool window | `JcefToolWindowFactory.createToolWindowContent` |
| F11 | **`vendor` is `JetBrains`** for a third-party plugin — wrong on the Marketplace, and no `url`/`email` | `plugin.xml` |
| F12 | **`pluginRepositoryUrl` points at the JetBrains template repo**, and `CHANGELOG` versions are not linked to a real repository | `gradle.properties:5` |
| F13 | **No tests, no test source tree** despite `testImplementation(libs.junit)`, `libs.opentest4j`, `testFramework(TestFrameworkType.Platform)` | `build.gradle.kts:35-51`, `src/test` absent |
| F14 | **Kotlin is applied but no Kotlin source exists** (`plugins { alias(libs.plugins.kotlin) }`, `kotlin.stdlib.default.dependency = false`) — harmless, but it should be a stated decision, not an accident | `build.gradle.kts`, `gradle.properties` |
| F15 | **`README.md` is stale and internally inconsistent** — it claims "Java 21 or higher" while the build requires JDK 25, and a link (`[Run Plugin.run.xml](.run/Run%20Plugin.run.xml)`) was accidentally pasted mid-sentence in the Features list | `README.md:14`, `README.md:37` |
| F16 | **`JBCefScrollbarsHelper` is not used**, so the page's scrollbars do not match the IDE theme; `JBCefBrowser.createBuilder()` is used without the default-Disposable discipline being explicit | `JcefToolWindowFactory` |
| F17 | **Project-relative path resolution is silent about failure.** `navigateToFile` does nothing when `findFileByPath` returns `null` — no log, no user feedback | `HttpBridgeService.navigateToFile` |
| F18 | **`.gitignore` ignores `.idea`** while the module *commits* `.run/*.xml` (correct) and a stale `.idea/` tree exists on disk; there is no `.editorconfig`, no CI workflow | `.gitignore`, `.github/` |

### 2.4 A design smell worth naming

The tool window's only cross-class entry point is
`JcefToolWindowFactory.reloadWithFile(project, url)`, which **digs the browser back out of the Swing
component tree** via a client property (`panel.getProperty("JBCefBrowser")`) and then calls
`PluginStateService.setLastUrl`. That is a symptom: there is no service that *owns* the browser, so
everything else has to reach into the UI to find it. The rewrite fixes the ownership, not the lookup.

---

## 3. Target: what "latest recommended" means here, concretely

Researched against the live sources (2026-07; see §7 for links).

| Area | Recommended now | What this module has | Action |
| --- | --- | --- | --- |
| Build plugin | `org.jetbrains.intellij.platform` **2.x** (`2.19.0` is latest) | 2.19.0 | keep |
| Template layout | Plugin template **2.6.0**; the current template declares plugin versions **inline in `build.gradle.kts`**, not in a `libs.versions.toml` (`gradle/libs.versions.toml` no longer exists upstream) | version catalog | **keep the catalog** — one deliberate deviation, documented in §4.1 |
| IDE/plugin manifest fields | `<id>`, `<name>`, `<vendor>`, and `idea-version` are patched by `patchPluginXml` from `pluginConfiguration`; **do not** set `until-build` | `id`/`name` hardcoded in `plugin.xml`, `sinceBuild` from `pluginSinceBuild`, vendor wrong | move to `pluginConfiguration`, fix vendor, never set `until-build` |
| Plugin dependencies | `<depends>` in `plugin.xml`; **optional dependency + `config-file`** is the documented pattern for content that needs a module that may be absent ("optional content modules") | `<depends>com.intellij.modules.platform</depends>` only; `withJcef.xml` unreferenced | add `<depends>com.intellij.modules.jcef</depends>` and wire `withJcef.xml` or delete it (§4.2) |
| JCEF availability | `JBCefApp.isSupported()` before any JCEF use | present | keep, and extend to the actions (F10) |
| JCEF bridge | `JBCefJSQuery.create(browser)` + `addHandler` returning `JBCefJSQuery.Response`, injection in `onLoadEnd` | present but regex-parsed and duplicated | rebuild as §4.3 |
| JCEF scrollbars | `JBCefScrollbarsHelper.buildScrollbarsStyle()` injected for IDE-consistent look | absent | add |
| Disposal | `JBCefBrowser`, `JBCefClient`, `JBCefJSQuery` are `JBCefDisposable`; dispose via `Disposer` / `Content.setDisposer` | `content.setDisposer(browser)` only; the `JBCefJSQuery` is never disposed | fix: dispose the query with the browser's content |
| Threading | `loadURL` / `executeJavaScript` callable from EDT and background; actions must declare `getActionUpdateThread()` | declared correctly (EDT/BGT mix) | keep |
| Action system | `AnAction` subclasses are fine; `DumbAwareAction` for tool-window-local actions | correct | keep, move to dedicated classes (§4.4) |
| Testing | `testFramework(TestFrameworkType.Platform)` + JUnit; **pure logic should be testable without the platform** | nothing | add tests (§4.7) |
| Marketplace/publishing | `signing` + `publishing` blocks from the template, `pluginVerification.ides.recommended()` | present | keep |

**Not recommended / not applicable:** JavaFX web rendering (deprecated since 2020.2, unavailable since
2025.1) — JCEF is the only supported path; the legacy 1.x Gradle plugin; `until-build` pinning.

---

## 4. Proposed implementation

### 4.1 Build and manifest alignment (template parity)

Files: `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `src/main/resources/META-INF/plugin.xml`.

1. **Move manifest identity into `pluginConfiguration`.**
   `id = "hr.hrg.jetbrains.webview"`, `name`, `vendor { name = "Davor Hrg"; url = …; email = … }`,
   `description` (keep the README `<!-- Plugin description -->` extraction — the template's own
   mechanism), `changeNotes` (keep the changelog rendering), `ideaVersion { sinceBuild = pluginSinceBuild }`.
   Then delete `<id>`, `<name>`, `<vendor>` and any `idea-version` from `plugin.xml` (they are patched in).
   **Do not** set `until-build` — the docs are explicit that `since-build` alone is the right shape for
   a plugin published from a SemVer version stream.
2. **Fix `pluginRepositoryUrl`** to the real repository (F12). This is what changelog links resolve to.
3. **Keep** `pluginVerification.ides.recommended()`, the `signing` block, the `publishing` block with
   the release-channel derivation, `runIde` JVM args, and the `runIdeForUiTests` registration — all
   still the template's recommended shape.
4. **Version catalog:** keep it (a stated deviation — the module is one of several Gradle builds in this
   repository and a catalog is the better fit), but add `kover = "0.9.3"` usage *or* remove the unused
   entry, and align `intelliJPlatform = "2.19.0"` (already correct). Delete the unused `kover` entry if
   coverage is not wired up; an unused catalog entry is drift.
5. **JDK/toolchain:** keep `languageVersion = 25` and `org.gradle.java.home = C:\Program Files\Java\jdk-25`.
   This is correct for 2026.2 — but it is currently *undocumented* (§4.6).
6. **Kotlin decision (F14):** either (a) delete `alias(libs.plugins.kotlin)` and the Kotlin catalog
   entries, since no Kotlin source is intended, or (b) keep it and state in the README that a Kotlin
   source root is permitted with `kotlin.stdlib.default.dependency = false`. Option (a) is recommended:
   a Java-only plugin should not carry a Kotlin toolchain it does not use.
7. **Tests:** add `src/test/java` and keep `testFramework(TestFrameworkType.Platform)` **only if** §4.7
   includes a platform-level test; otherwise switch to `TestFrameworkType.Plugin.Java`/plain JUnit and
   drop the JCEF-heavy dependency. Recommended: keep `Platform` (the smoke test in §4.7 needs it) and
   upgrade JUnit to 5 via `useJUnitPlatform()` plus `testImplementation("org.junit.jupiter:junit-jupiter")`,
   replacing the lone `junit:junit:4.13.2` entry.

### 4.2 Plugin structure — declare the JCEF dependency (F8 — done; F9 still open)

**First, the bug that stopped the plugin from starting at all.** `com.intellij.modules.jcef` was a
*build-time* dependency (`platformBundledModules`) but never a *runtime* one, and a content module's
jar only joins the plugin classloader's parents when `<depends>` says so. Every JCEF type —
`JBCefApp`, `JBCefBrowser`, `JBCefBrowserBase`, `JBCefJSQuery` — lives in
`intellij.platform.ui.jcef.jar`, so the plugin's tool-window factory could not resolve its own method
descriptors and the platform reported the failure as a missing constructor. The one-line fix, now in
`src/main/resources/META-INF/plugin.xml`, and verified in a sandbox IDE:

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.jcef</depends>
```

**Two lessons to carry into the rest of the work:**

1. A missing `<depends>` on a content module surfaces as an unrelated-looking error. When the plugin
   references a class from a platform module, the runtime dependency is not optional bookkeeping.
2. `platformBundledModules` in `gradle.properties` configures the *compile* classpath only. Every
   module the plugin needs at runtime must also appear in `plugin.xml`.

**Then, the still-open structure question (F9).** The docs' "additional configuration files" pattern
is the recommended way to express "this half of my plugin needs a module that might be present or
absent":

```xml
<depends optional="true" config-file="withJcef.xml">com.intellij.modules.jcef</depends>
```

* `plugin.xml` keeps: id/name/vendor (patched), the platform dependency, the **actions** (they are
  action-system only and safe everywhere), and the settings configurable.
* `withJcef.xml` gains: the `toolWindow` registration (moved out of `plugin.xml` — resolving F9's
  duplication) plus the JCEF-only services **if** they are genuinely JCEF-gated. Note the HTTP bridge is
  *not* JCEF-gated (it is the fallback for a plain browser), so it stays in `plugin.xml`.
* `JcefToolWindowFactory`, `WebViewPanel`, and the bridge classes must all tolerate
  `JBCefApp.isSupported() == false` and must not be *loaded* unless the JCEF config file was applied —
  which is exactly what the optional-dependency mechanism guarantees.

**Recommendation changed by the bug above:** the plain `<depends>com.intellij.modules.jcef</depends>`
we just added is now the baseline, and the optional-content-module form is the *stretch* — it is only
worth taking if the plugin should also install on an IDE where JCEF is genuinely unavailable, which
for a plugin whose entire purpose is a webview is a hypothetical. Do **not** leave `withJcef.xml`
unreferenced (F9): either wire it up as above or delete it. Whichever is chosen, the plugin's own
purpose is the tool window, so `JBCefApp.isSupported()` handling (F10) is the part that must not be
skipped.

### 4.3 The bridge — one class, one protocol, JSON not regex (F1–F5)

Replace `JcefBridgeNew` with:

**`bridge/WebViewBridge.java`** (implements `Disposable`)
* Owns the single `JBCefJSQuery` for the browser (create in the ctor, `dispose()` it).
* Registers **one** `CefLoadHandlerAdapter` for the bridge concern; `onLoadEnd` injects exactly one JS
  blob. The address-bar/`lastUrl` load handler stays separate but moves into `WebViewPanel` so there are
  still exactly two handlers with clearly different jobs (or, better: one handler in `WebViewPanel` that
  calls `bridge.inject()` — a single registration).
* Injection is idempotent and versioned so a page reload cannot leave a stale closure:

```js
window.__jcbWebViewBridge = 1;
window.openFile = function (path, line, column) {
  var msg = JSON.stringify({ kind: "openFile", filePath: path, line: line || 1, column: column || 1 });
  <jsQuery.inject("msg", onSuccess, onFailure)>
};
```

**`bridge/BridgeMessage.java`** — a plain Java `record` (or class) with `kind`, `filePath`, `line`,
`column`, plus a static `parse(String json)` that uses a **JSON parser**, not regex. Two candidates, in
order of preference:

* the platform's own `com.google.gson` (`JsonParser.parseString`) — already on the platform classpath,
  no new dependency; or
* `org.jetbrains.kotlinx:kotlinx-serialization` / Jackson — only if a second consumer needs it.

Gson is recommended: zero new dependencies, and `parse` becomes a pure function that unit-tests without
the IDE (§4.7). Unknown `kind` is logged and ignored (forward compatibility), malformed JSON is logged
with the raw text truncated.

**Return values.** The handler returns `new JBCefJSQuery.Response("OK")` (as today) but the page can
then distinguish success from failure; `inject(msg, onSuccess, onFailure)` is the documented shape.
`render.js` only checks `typeof window.openFile === "function"`, so adding a callback is
backwards-compatible and lets the page's status pill become truthful later (a renderer change, not a
plugin change — record it as a follow-up, do not do it here).

**Action classes** (F3/F4 owner): the bridge calls `NavigatorService.open(project, filePath, line, column)`.

### 4.4 Tool window — explicit ownership, no component-tree spelunking (§2.4)

| New class | Responsibility | Replaces |
| --- | --- | --- |
| `toolWindow/JcefToolWindowFactory.java` | `DumbAware`; `JBCefApp.isSupported()` check; creates a `WebViewPanel`, registers it in the content manager, sets the browser as the content's `Disposer` | slimmed-down factory |
| `toolWindow/WebViewPanel.java` | Owns `JBCefBrowser` + address bar + toolbar; `loadUrl(String)`; `reload()`; `back()`/`forward()`; load handler that syncs the address bar, records `lastUrl`, and calls `bridge.inject()`; injects `JBCefScrollbarsHelper.buildScrollbarsStyle()` | the 130-line body of the current factory + `reloadWithFile`'s property lookup |
| `toolWindow/WebViewService.java` | `@Service(PROJECT)`; `@Nullable WebViewPanel getPanel()`, `void openInPanel(String url)`, `void disposePanel()`. The panel registers itself on creation and clears itself on dispose | `panel.getProperty("JBCefBrowser")`, `ToolWindowManager.getToolWindow(id).getContentManager().getContent(0)` |
| `toolWindow/WebViewActions.java` | The four toolbar actions as named `DumbAwareAction` classes (back / forward / refresh / settings) instead of four anonymous classes | the anonymous inner classes |

`OpenFileInWebViewAction` then becomes:

```java
WebViewService svc = project.getService(WebViewService.class);
svc.openInPanel(url);          // creates the tool window lazily if needed, then loads
```

No `ToolWindowManager` lookup, no index-0 assumption, no client property. `content.setDisposer(browser)`
stays; the `JBCefJSQuery` is disposed by `WebViewBridge.dispose()`, which the panel calls from its own
`dispose()` (also registered as a `Disposer` parent for the query).

Also add per F10: `CefLoadHandler.onLoadError` → show a `JBLabel` explanation in place of a blank pane,
and `update()` on the actions so back/forward are correctly disabled when there is no browser (JCEF
absent).

### 4.5 HTTP bridge — keep the endpoint, close the hole (F6, F7, F17)

`GET /open?filePath=…&line=…&column=…` stays **exactly** as documented, because `render.js` and
`scripts/entity-html/README.md` depend on it.

1. **Bind to loopback only:** `new InetSocketAddress(InetAddress.getLoopbackAddress(), port)`.
2. **Fail closed on Origin (F6).** The `/open` guard must be "there is a configured allow-list **and**
   this Origin is in it", not "the Origin is somehow in a possibly-empty set". Concretely:
   `if (!allowedOrigins.isEmpty() && origin != null && allowedOrigins.contains(origin.toLowerCase()))`
   is *still* fail-open for the read-only-fallback case only if the list is empty — so the rule is:
   **when `allowedOrigins` is empty, `/open` returns 403 with a message naming the
   `webview.explorer.allowedOrigins` VM option / setting.** The browser fallback is an opt-in, and the
   README must say so. (Alternative considered and rejected for now: a per-session bearer token in the
   URL, which is stronger but requires the renderer to learn it — record as a follow-up, not part of
   this change.)
3. **Rate limit once.** One limiter, consulted in exactly one place (`NavigatorService.open`), with the
   HTTP layer returning `429` when it trips. Remove the second check in `navigateToFile` (F5).
4. Add `GET /health` returning the plugin version and bridge state — it makes "is the bridge up?" a
   one-line check for a script or a human, and it is what a future token handshake would build on.
5. Replace the two `HttpHandler` inner classes with one handler that dispatches on a small
   `Map<String, Handler>`-free `switch` on the path (the repo's own DEC-019 favours navigable, explicit
   dispatch over lookup tables — this is a third-party Gradle module, but the same taste applies).
6. **Log the failure to resolve a file** (F17): `LOG.warn("WebView bridge: no VirtualFile for '<path>'")`,
   and answer the HTTP request with `404` rather than a cheerful `200 Opening …`.
7. Register the executor explicitly (`Executors.newSingleThreadExecutor` with a named thread factory)
   instead of `server.setExecutor(null)`, so the daemon-ness of the pool is deliberate.

### 4.6 Settings, state, and documentation

* **State model:** keep `@State(storages = @Storage(StoragePathMacros.WORKSPACE_FILE))`, but split the
  concerns: `lastUrl` is view state, `port`/`allowedOrigins` are configuration. If the split is kept in
  one class (simplest), say so in a Javadoc comment; if split, `WebViewSettings` becomes an application
  service for the port/origins and `PluginStateService` keeps only `lastUrl`. Recommend **keeping one
  class** and documenting why — three fields do not justify two services.
* **Settings UI:** keep the `FormBuilder` panel; add (a) validation feedback for a non-numeric or
  in-use port instead of the current silent `catch (NumberFormatException ignored)`, (b) a live
  "bridge: running on 127.0.0.1:18881 / stopped" status line, (c) `disposeUIResources()` already sets
  the component to `null` — keep that.
* **README rewrite (F15):** fix the pasted link and the Java-version claim, document JDK 25 + Gradle
  9.2.1, the JCEF-optional structure, the security posture of the HTTP bridge (loopback-only,
  allow-list required), the DEC-027 §7 contract, and the project structure table (which currently lists
  `JcefBridgeNew` and omits `HttpBridgeService`, the settings package, and the actions' real files).
* **CHANGELOG:** add an `Unreleased` entry describing the reimplementation under `### Changed`, keeping
  the existing `1.0.0` section untouched.
* **`.gitignore`:** the module commits `.run/*.xml` while ignoring `.idea`; that is correct, but the
  stale `.idea/` tree on disk should stay untracked and `build/` must remain ignored (it is).

### 4.7 Tests (F13) — the acceptance net

Add `src/test/java` and, per §4.1.7, `useJUnitPlatform()`.

| Test | Level | Asserts |
| --- | --- | --- |
| `BridgeMessageTest` | pure unit | valid `openFile` JSON parses; a `filePath` containing `"`, `:`, `\`, spaces, `", "line": 1, "` survives; missing `line`/`column` default to 1; negative/zero line clamps; malformed JSON and unknown `kind` are rejected and logged, not thrown |
| `UrlNormalizationTest` | pure unit | `https://x` kept; `x.com` → `https://x.com`; an existing local file path → its `file:` URI; a non-existent path without a scheme → `https://` + it (or a documented error — pick one and pin it) |
| `AllowedOriginsTest` | pure unit | empty allow-list → denied; exact match → allowed; different case → allowed; `null` Origin → denied; suffix trick (`http://localhost:3000.evil.com`) → denied |
| `RateLimiterTest` | pure unit | 20 in the window pass, the 21st is refused, the window rolls over via an injectable clock |
| `WebViewToolWindowTest` | platform | tool window registers under id `WebView Explorer`; `createToolWindowContent` produces one content whose component holds a browser (skipped when `JBCefApp.isSupported()` is false); the injected script is present after a load |

The pure-unit tests are the point: they are why `BridgeMessage.parse`, the URL normaliser, the origin
checker, and the rate limiter must be extractable classes with no `Project` in their signature.
`RateLimiterTest` needs a clock seam — pass a `LongSupplier` rather than calling
`System.currentTimeMillis()` inside.

### 4.8 Explicitly out of scope

* Changing the `window.openFile` contract or the `/open` endpoint shape (renderer compatibility).
* Changing the renderer (`scripts/entity-html/render.js`) to use success callbacks or a bridge token.
* Porting the module to Kotlin.
* Marketplace publishing, plugin signing, or bumping `pluginVersion` past `1.0.0`.
* The sibling `webview-vscode` extension and the other Gradle plugin modules (`intellij-jswa` is still
  on 2.1.0 / 2024.1 — worth its own plan, not this one).

---

## 5. Order of work

Each step should build green on its own.

| Step | Work | Files | Verification |
| --- | --- | --- | --- |
| 0 | Baseline: record that the current build compiles and that `index.html` link clicks work in `runIde` | — | `.\gradlew.bat compileJava`; manual `runIde` + click a report link |
| 1 | Extract pure logic with no behaviour change: `BridgeMessage`, `UrlNormalizer`, `AllowedOrigins`, `RateLimiter` (with clock seam) | new `bridge/`, `util/` classes; `JcefBridgeNew`/`HttpBridgeService` delegate to them | `.\gradlew.bat test` (new unit tests) + manual smoke |
| 2 | Add the test suite of §4.7 and `useJUnitPlatform()` | `build.gradle.kts`, `src/test/**` | `.\gradlew.bat test` |
| 3 | Build/manifest alignment §4.1 (identity into `pluginConfiguration`, vendor, repository URL, catalog cleanup, Kotlin decision) | `build.gradle.kts`, `gradle.properties`, `libs.versions.toml`, `plugin.xml` | `.\gradlew.bat buildPlugin`; inspect the patched `plugin.xml` in `build/` |
| 4 | Plugin structure §4.2 (optional JCEF content module) | `plugin.xml`, `withJcef.xml` | `buildPlugin` + `verifyPluginProjectConfiguration` |
| 5 | Tool window rewrite §4.4 (`WebViewPanel`, `WebViewService`, named actions, scrollbars, load-error state) | `toolWindow/**`, `actions/OpenFileInWebViewAction` | `runIde`: toggle, back/forward/refresh, right-click an HTML file, settings button |
| 6 | Bridge rewrite §4.3 (single load handler, one `JBCefJSQuery`, JSON parse, disposal) | `bridge/WebViewBridge`, delete `JcefBridgeNew` | `runIde`: open the entity `index.html`, click a field link → right line; break the JSON on purpose and confirm the log is a warning, not a stack trace |
| 7 | HTTP bridge hardening §4.5 | `services/**` | `curl http://127.0.0.1:18881/health`; `curl` `/open` with no allow-list → 403; with the origin allowed → opens; 21st call → 429; confirm the port is not reachable from another host |
| 8 | Settings/state polish §4.6 | `settings/**`, `services/PluginStateService` | invalid port shows a message; changing the port restarts the bridge; `lastUrl` survives an IDE restart |
| 9 | Docs: README rewrite, CHANGELOG entry | `README.md`, `CHANGELOG.md` | read-through against the DEC-027 §7 and `scripts/entity-html/README.md` contracts |
| 10 | Final gate | — | `.\gradlew.bat clean buildPlugin verifyPlugin test`; then the §6 acceptance walk |

---

## 6. Acceptance criteria

1. `.\gradlew.bat clean buildPlugin verifyPlugin test` succeeds on JDK 25, and `verifyPlugin` reports no
   compatibility problems against the recommended IDEs.
2. In `runIde`, opening `hipster-entity-example/.jcodebuddy/metadata/entity/index.html` through
   **Open in WebView Explorer** renders the page, and clicking a field link lands the caret on the
   exact line the page's `data-line` names — with no HTTP bridge configured (the injected
   `window.openFile` path). The page's status pill reads *IDE bridge: ready*.
3. With `-Dwebview.explorer.port=18881` and **no** allowed origins, `GET /open?filePath=…` returns
   **403** and nothing opens; with `-Dwebview.explorer.allowedOrigins=file://` (or the exact Origin the
   page presents) the hidden-iframe fallback opens the file. The port is unreachable from a second
   machine.
4. A path containing a double quote and the literal text `", "line": 99, "` opens line 1, not line 99 —
   F1 is provably fixed by `BridgeMessageTest`.
5. Breaking the injected function (rename `window.openFile` in a copy of the page) degrades to the
   clipboard/toast path in `render.js` with no exception in `idea.log`, and the plugin logs **one**
   warning.
6. `JBCefApp.isSupported() == false` (start the IDE with `-Dide.browser.jcef.enabled=false`): the plugin
   still installs and loads, the tool window is absent rather than broken, and **Open in WebView
   Explorer** is disabled with an explanation.
7. No JCEF object is leaked: closing the tool window disposes the browser, the client and the
   `JBCefJSQuery` (assert via a `Disposer` trace in the smoke test, or by the absence of
   "already disposed" warnings after a toggle/hide cycle repeated ten times).
8. `README.md` documents the exact commands and contracts, and `grep` finds no reference to
   `JcefBridgeNew` or to the old `reloadWithFile` property-lookup pattern anywhere in the module.

---

## 7. Sources consulted

* [IntelliJ Platform Gradle Plugin (2.x)](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html) — registration, `pluginConfiguration`, repositories extension
* [`org.jetbrains.intellij.platform` on the Gradle Plugin Portal](https://plugins.gradle.org/plugin/org.jetbrains.intellij.platform) — **2.19.0 is the current release**
* [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) — `build.gradle.kts` and `gradle.properties` at `main` (template version 2.6.0; plugin versions inline, no `libs.versions.toml`)
* [Plugin Configuration File](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html) — `<depends optional config-file>`, additional configuration files, `since-build`/`until-build` guidance, `patchPluginXml`-provided elements
* [Embedded Browser (JCEF)](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html) — `JBCefApp.isSupported()`, `JBCefBrowser.createBuilder()`, `JBCefJSQuery` + `inject`/`Response`, `JBCefScrollbarsHelper`, `JBCefDisposable`, DevTools
* [Structuring IntelliJ Plugins with Optional Content Modules](https://blog.jetbrains.com/platform/2026/06/structuring-intellij-plugins-with-optional-content-modules/) — the pattern behind §4.2
* Local contracts: `doc-hipster-entity/architecture/decisions/DEC-027.md` §7, `scripts/entity-html/README.md`, `scripts/entity-html/render.js`, `hipster-entity-example/codebuddy.md` §3.10

---

## 8. Open questions for the maintainer

1. **Vendor identity** — what should `<vendor>` be (personal name, an organisation, a URL/email)?
2. **HTTP bridge allow-list default** — §4.5.2 makes "no allow-list" mean "/open is disabled". Confirm
   that is acceptable, or say that the browser fallback must keep working out of the box (in which case
   the default becomes `file://`).
3. **One settings service or two** — §4.6 recommends one; confirm.
4. **Kotlin** — §4.1.6 recommends dropping the Kotlin plugin; confirm no Kotlin source is planned.
5. **Plan location** — this file sits in `webview-jetbrains/`. Move it to `plans__p2/` if that is the
   repository's convention for plans of this size.
