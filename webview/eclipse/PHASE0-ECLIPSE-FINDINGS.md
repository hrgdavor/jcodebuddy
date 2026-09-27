# Phase 0 — the Eclipse facts, measured

**Status: RUN 2026-09-27.** `bun webview/eclipse/phase0/probe.mjs` (see [`phase0/`](phase0/README.md) and the raw
output in [`phase0/probe-run.txt`](phase0/probe-run.txt)). Every result below is **observed** on this
machine, one line per experiment, failures included. What it amends in the plan is listed at the end, in the same
commit.

**Build under test:** SWT `org.eclipse.swt.win32.win32.x86_64` **3.135.0**, `org.eclipse.ui` **3.209.100**,
`org.eclipse.ui.browser` **3.9.200** (all from Maven Central, the 2026-09 train); JDK **25.0.1**; Windows 11,
amd64. **Engine actually in use:** the WebView2 runtime **154.0.4258.37**, user agent
`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36 Edg/154.0.0.0`.
**Eclipse found on the machine (F):** `D:\programs\eclipse\eclipse.exe`; `plugins/org.eclipse.core.runtime_*.jar`
versions **3.33.0.v20250206-0919, 3.34.0.v20250711-1249, 3.34.100.v20251111-1421, 3.34.200.v20251220-0953** — a
2025-12-generation build, **older than the 2026-09 / 4.41 the observed gate names**, so the observed gate is still
pending with a human even though an install exists.

## The experiments, as measured

**E — workbench-less `Display`.** `new Display()` succeeded in a process with no Eclipse workbench (JDK 25,
Windows 11); the rest of the probe ran inside it. SWT can run outside a workbench, so the Phase 1 unit suite may
touch the SWT classes (still: no CI display, R22 — the E10 fakes stay for headless runs).

**A1 — the engine report for `SWT.EDGE`.** `Browser.getBrowserType()` returned **`edge`** (a `String`). There is
**no `EdgeVersion` class** in SWT 3.135.0: the version is the system property
`org.eclipse.swt.browser.EdgeVersion`, and **SWT sets it itself** after creating the first Edge browser — it
reports the engine actually in use, it is an output, not an input. Measured value: **154.0.4258.37**. The user
agent confirms a Chromium engine, `Edg/154.0.0.0`. **Edge was obtained**, resolving R3's "which document is right"
in favour of Edge on this machine. (Input properties `EdgeDir` / `EdgeArgs` / `EdgeLanguage` still belong before
the first SWT.EDGE browser; the version property must not be read before that point.) The `SWT.NONE` half of
question A is **not measured**: the probe constructs only `SWT.EDGE` — the style E2 passes — and E2 never passes
`SWT.NONE`.

**A2 — the WebView2 runtime hidden. Not run.** There is no non-invasive way to hide the installed WebView2
runtime without modifying system state (registry/COM), and the probe forbids that. **R4 stays unsettled**: the
contradiction between "silent IE fallback" and "thrown `ERROR_NOT_IMPLEMENTED`" is still only a documentation
conflict. The host therefore keeps E2's degraded-state panel covering both shapes, and the README's install steps
stay as written.

**B — the `BrowserFunction` contract.**
- *Registration and call.* `jcbProbe` (registered before the first `setUrl`) was present on the first document:
  `return jcbProbe('B1-echo')` through `evaluate` returned **`echo:B1-echo`** — the value reached Java **synchronously**
  (no deadlock), and Java's `function(Object[])` saw exactly the one string `B1-echo`, called on thread
  **`main`** — the UI thread that owns the `Display`. The narrow-typed, UI-thread rules (R7, R10) hold.
- *Throw.* When the Java function threw `IllegalStateException("probe throw")`, the JavaScript side saw
  `Error: probe throw` — the failure reaches the page as a JavaScript `Error` with the thrown message; `evaluate`
  maps it to `SWTException ERROR_FAILED_EVALUATE` (caught by the probe's `evalSafe`).
- *Dispose.* After `dispose()` and a new document, `typeof jcbProbe` was **`undefined`** — the function left the
  new document.
- *Re-registration.* Re-registered, then a new document: `return jcbProbe('B4-echo')` returned **`echo:B4-echo`**
  and Java saw the argument. **Dispose + re-register per document works** — R8's model holds, E3 stands.
- *Measured nuance.* Edge's `evaluate` wraps the script in `(function(){ try { … } catch { … } })()`, so the script
  must **`return`** the value; a bare expression evaluates to `undefined` and Java sees `null`. (The probe's first
  run returned `null` on every value read for exactly this reason.)

**C — `ProgressListener.completed` and `evaluate`.**
- `data:` splash: `completed` fired; `getUrl()` reported **empty** for the `data:` URL at completion, but the page
  rendered (`document.body.innerText` = `splash`). The splash path works, and a ready latch must not depend on
  `getUrl()` for `data:` URLs.
- `http://127.0.0.1:<port>/`: `completed` fired with the URL, and `evaluate` works after it.
- `file:///…`: `completed` fired with the URL.

**D — origins.**
- A same-origin page (served from the loopback) fetched `location.origin + '/origin'` and the server saw **no
  `Origin` header** — a same-origin `GET` sends none.
- A `file://` page fetched `http://127.0.0.1:<port>/origin` and the **fetch succeeded** (status 200, against a
  permissive `Access-Control-Allow-Origin: *` reply); the server saw the header **`Origin: null`** (a `file:`
  origin is serialised as the string `"null"`), and the page's `window.origin` is `null`.
- **Consequence for E12:** a `file://` page *does* reach loopback, and a permissive server answers it. A host that
  enforces an allow-list will not list `"null"` as an allowed origin, so a `file:` page still needs the token on
  protected routes. The host policy must name all three cases: no `Origin` (same-origin `GET`), `Origin: null`
  (`file:`), and a real origin (cross-origin). The plan's "its `fetch` to loopback is CORS-blocked" is amended to
  match this.

## What this amends in the plan (same commit)

- **§ 3 E2** — A1 measured: `SWT.EDGE` gives Edge with runtime 154.0.4258.37; A2 not run, so **R4 stays
  unconfirmed** and E2's dual degraded-state handling stands.
- **§ 3 E12** — a `file://` page reaches loopback (observed, `Origin: null`, status 200); Phase 1's
  `file://` loading is validated, and the token remains the `file:` page's path to protected routes.
- **§ 5.2 A** — `org.eclipse.swt.browser.EdgeVersion` is the property SWT sets itself (output), not an input
  switch.
- **`PLATFORM-REFERENCE.md` R11** — `EdgeVersion` is a system property SWT sets after the first Edge browser, not a
  class, and an output rather than an input.
- **`PLATFORM-REFERENCE.md` R9** — Edge's `evaluate` script must `return` its value.
- **`PLAN-webview-suite.md` § 4 and § 7** — cross-links to this plan, as the Phase 0 gate requires.

## Notes

- The probe logged JDK 25's restricted-native-access warnings for SWT's `System.loadLibrary` (JNA is embedded in
  the SWT win32 jar); they are informational. `--enable-native-access=ALL-UNAMED` silences them.
- The probe's own window, the `HttpServer` on `127.0.0.1:0`, and the `Display` are all disposed at the end; nothing
  is left running.
