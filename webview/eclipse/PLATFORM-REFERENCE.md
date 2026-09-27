# What the Eclipse sources say — the reference behind the plan

**Status: reference material, 2026-09-27.** Every item below is **documented** — a named source says so — and
nothing here is **observed**. Observations live in [`PHASE0-ECLIPSE-FINDINGS.md`](PHASE0-ECLIPSE-FINDINGS.md),
which now carries the Phase 0 measurement (run 2026-09-27); two items still contradict each other and are marked
where they do (R3/R4).

This file exists so [`../PLAN-eclipse-host.md`](../PLAN-eclipse-host.md) stays an *argument* rather than a
citation list: the plan names a finding, this file carries its source. Read the plan first.

The reading was done against the **Eclipse 4.41 javadoc** (`help.eclipse.org/latest`), **SWT's source and its own
readme** on `master`, Eclipse Foundation release notes and mailing lists, and **Tycho's documentation**, on
2026-09-27.

---

## 1. The browser engine

**R1 — The Eclipse Platform is on Maven Central, at current versions.**
`org.eclipse.platform:org.eclipse.swt.win32.win32.x86_64` lists `3.135.0` as `release` with
`lastUpdated 20260907074619` ([metadata](https://repo1.maven.org/maven2/org/eclipse/platform/org.eclipse.swt.win32.win32.x86_64/maven-metadata.xml));
`org.eclipse.ui` is `3.209.100` and `org.eclipse.ui.browser` `3.9.200` with the same `lastUpdated`
([ui](https://repo1.maven.org/maven2/org/eclipse/platform/org.eclipse.ui/maven-metadata.xml),
[ui.browser](https://repo1.maven.org/maven2/org/eclipse/platform/org.eclipse.ui.browser/maven-metadata.xml)).
That is the 2026-09 train, and it is why a plain Maven build is possible at all.

**R2 — SWT has five browser styles, and none of them is Chromium.** `NONE`, `WEBKIT`, `MOZILLA`, `EDGE`, `IE`
([`SWT` javadoc](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/SWT.html));
there is no `SWT.CHROMIUM`. `SWT.MOZILLA` and `Browser.getWebBrowser()` are `@Deprecated(forRemoval=true,
since="2026-09")`, so XULRunner is not an option either. On cocoa and GTK, `SWT.NONE`/`SWT.WEBKIT` mean the
system WebKit.

**R3 — Edge/WebView2 has been the Windows default since SWT 4.35 (2025-03).** SWT's win32 `BrowserFactory` is
`if ((style & SWT.IE) != 0) return new IE(); return new Edge();`, and the Eclipse FAQ says *"Since Eclipse/SWT
4.35 (2025-03) Edge is used as the default browser in SWT"*
([FAQ](https://raw.githubusercontent.com/eclipse-platform/eclipse.platform/master/docs/FAQ/FAQ_How_do_I_use_Edge-IE_as_the_Browser's_underlying_renderer.md),
[`BrowserFactory.java`](https://raw.githubusercontent.com/eclipse-platform/eclipse.platform.swt/master/bundles/org.eclipse.swt/Eclipse%20SWT%20Browser/win32/org/eclipse/swt/browser/BrowserFactory.java)).
`SWT.EDGE` exists since **4.19**; `-Dorg.eclipse.swt.browser.DefaultType=edge` changes the default globally; the
default can be forced back to IE with the style flag. **The SWT readme still says the Windows default is
Internet Explorer** — stale prose, contradicted by the source and the FAQ. This contradiction is why the plan
asks the runtime (`Browser.getBrowserType()`) instead of trusting either document.

**R4 — A missing WebView2 runtime is documented two contradictory ways.** [`Readme.WebView2.md`](https://raw.githubusercontent.com/eclipse-platform/eclipse.platform.swt/master/bundles/org.eclipse.swt/Readme.WebView2.md)
says the `Browser` *"will automatically fall back to the Internet Explorer backend"*; the constructor/javadoc
reading of SWT's source says it throws `SWT.ERROR_NOT_IMPLEMENTED`, and that SWT shows a one-time "Default
browser engine not available" message box offering an IE fallback for the session, suppressible with
`-Dorg.eclipse.swt.browser.DisableWebViewUnavailableDialog=true`. **Which one happens is not settled here** —
it is experiment A in [`PHASE0-ECLIPSE-FINDINGS.md`](PHASE0-ECLIPSE-FINDINGS.md). The same readme documents that
a *stable* Edge installation does **not** provide the WebView2 component.

**R5 — There is no supported JCEF/CEF route in Eclipse.** SWT has no style for it (R2), and the Eclipse
Foundation's CEF/SWT effort — the FEEP-funded "Chromium in SWT" work of 2017 — never landed because funding was
not reached ([announcement and outcome](https://blogs.eclipse.org/post/mika%C3%ABl-barbero/chromium-eclipse-swt-integration-0)).
The remaining route is an out-of-tree bridge such as [equodev/chromium](https://github.com/equodev/chromium),
which means shipping CEF natives per OS and architecture and owning their upgrades. JCEF itself is a
JetBrains/CEF library, not an Eclipse one. **This is the finding that says the IntelliJ design does not
transfer.**

**R6 — `Browser` and `BrowserFunction` live in the `org.eclipse.swt` bundle**, in
`bundles/org.eclipse.swt/Eclipse SWT Browser/common/…`, not in a platform fragment — so a plugin compiled against
the Central artifact can use them. A view that embeds its own browser needs `Require-Bundle: org.eclipse.swt`
only. `org.eclipse.ui.browser` is a *different* thing: the platform's own internal Web Browser view
(`org.eclipse.ui.browser.view`, plus `IWorkbenchBrowserSupport` and the `org.eclipse.ui.browserSupport` extension
point). This host does **not** use it, because a host that does not own the `Browser` cannot install a
`BrowserFunction`. Whether `org.eclipse.ui.browser` is installed at all depends on the Eclipse package.

**R7 — `BrowserFunction`'s two construction forms.** `new BrowserFunction(browser, name)` (since 3.5) is visible
in the top-level window **and all child frames**; `new BrowserFunction(browser, name, top, frameNames)` (since
3.8) restricts that scope. The method to override is `public Object function(Object[] arguments)`
([javadoc](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/browser/BrowserFunction.html)).
Registration is implicit in construction.

**R8 — `BrowserFunction`'s sharp edges.** From the same javadoc and SWT's WebView2 readme:
* it is invoked on the **SWT UI thread** — the `@throws` list admits only `ERROR_THREAD_INVALID_ACCESS` and
  `ERROR_FUNCTION_DISPOSED`, and SWT widgets may only be touched from the thread that created them;
* arguments and returns are limited to `null`, `Double`, `String`, `Boolean` and arrays of those; **an
  unsupported argument type makes the whole invocation fail and `function()` is never called**;
* it must be **disposed explicitly**, and the javadoc recommends doing it in a `LocationListener.changed`
  handler, because functions are re-injected per document; disposing the `Browser` disposes all of its
  functions;
* on WebView2, SWT registers functions through `AddScriptToExecuteOnDocumentCreated`, so a registered function
  exists in every future document **before** the page's own scripts run.

**R9 — `ProgressListener.completed` is the documented "page is ready" hook, and it is narrower than it sounds.**
Under WebView2 it *"fires for the top level document only"*, and matches `DOMContentLoaded` on Edge 88+ (`load`
before that); `ProgressListener.changed` is **unsupported**; `evaluate()` and `getText()` **throw** inside
`LocationListener.changing` and `OpenWindowListener.open`, because those handlers must return values
synchronously; `execute(String)` is always asynchronous, while `evaluate(String)` is synchronous with a fixed
return mapping (`Double`, `String`, `Boolean`, `Object[]`, `null`) and throws `ERROR_INVALID_RETURN_VALUE` /
`ERROR_FAILED_EVALUATE` otherwise. Two source facts the readme does not spell out, from [`Edge.java`](https://github.com/eclipse-platform/eclipse.platform.swt/blob/master/bundles/org.eclipse.swt/Eclipse%20SWT%20Browser/win32/org/eclipse/swt/browser/Edge.java): `evaluateInternal` wraps the script in `(function() { try { <script> } catch (e) { return '<ERROR_ID>' + e.message; } })()`, so the script must **`return`** its value — a bare expression comes back as `null`; and when called from inside a WebView2 callback (`inCallback > 0`), `evaluate` runs the script without waiting for completion and returns `null` — fire-and-forget inside a handler
([readme](https://raw.githubusercontent.com/eclipse-platform/eclipse.platform.swt/master/bundles/org.eclipse.swt/Readme.WebView2.md),
[`ProgressListener`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/browser/ProgressListener.html),
[`Browser`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/browser/Browser.html)).
The lambda adapter helpers (`completedAdapter`, `changedAdapter`) exist since SWT 3.107. Consequences: inject
from `completed`; an in-page fragment change or an iframe is **not** a reload.

**R10 — WebView2 serializes its callbacks and timeboxes its operations.** A handler that waits on another handler
**deadlocks**; SWT detects it and throws `ERROR_FAILED_EVALUATE` with `[WebView2: deadlock detected]`. Operations
are bounded by `-Dorg.eclipse.swt.internal.win32.Edge.timeout` (default 5000 ms), and long operations are
documented as taking "rather long" (same readme).

**R11 — WebView2's user-data directory is per *application*, not per window.** All instances in an application —
and all instances of the same application — share it; the default is
`%LOCALAPPDATA%\<AppName>\WebView2`, or in an Eclipse product the workspace's
`.metadata/.plugins/org.eclipse.swt/EBWebView`; it can be redirected per process with
`-Dorg.eclipse.swt.browser.EdgeDataDir=…`. The input properties `EdgeDir` (must point at the directory
holding `msedgewebview2.exe`, not `msedge.exe`), `EdgeArgs` and `EdgeLanguage` **must be set before the first
`SWT.EDGE` browser is created**. `EdgeVersion` is **not an input**: it is the system property
`org.eclipse.swt.browser.EdgeVersion` that SWT sets **itself** after creating the first Edge browser — `Edge`
reads `ICoreWebView2Environment.get_BrowserVersionString()` and stores it (`System.setProperty`, [`Edge.java`](https://github.com/eclipse-platform/eclipse.platform.swt/blob/master/bundles/org.eclipse.swt/Eclipse%20SWT%20Browser/win32/org/eclipse/swt/browser/Edge.java)) — reporting the engine actually in use. It is an **output**, not a setting to pass, and was measured **154.0.4258.37** on this machine ([`PHASE0-ECLIPSE-FINDINGS.md`](PHASE0-ECLIPSE-FINDINGS.md), run 2026-09-27).

**R12 — JavaScript is enabled by default** (`Browser.setJavascriptEnabled(boolean)` defaults to `true`) and is
required for `BrowserFunction` and for mouse events on Edge (same readme).

## 2. The workspace, the editor, and the verbs

**R13 — Opening a file at a line is three documented calls.** `IDE.openEditor(page, file, true)` — one of several
overloads, all throwing `PartInitException` and possibly returning `null` when the user cancels or an external
editor opens, so the result must be null-checked; then `IEditorPart.getAdapter(ITextEditor.class)` rather than a
cast, because a part may wrap a text editor; then
`editor.selectAndReveal(document.getLineOffset(line - 1) + (column - 1), 0)` (`setHighlightRange` is the
alternative when the cursor should not move). The offset arithmetic is `IDocument.getLineOffset` /
`getLineOfOffset` / `getLineInformation`
([`IDE`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/ide/IDE.html),
[`ITextEditor`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/ITextEditor.html)).

**R14 — Path → `IFile` has two traps.** `IWorkspaceRoot.getFileForLocation(IPath)` returns `null` when the
location is not under an existing project **and ignores linked resources and their children**; the URI variants
(`findFilesForLocationURI`, `findContainersForLocationURI`) are the ones that see linked resources, while the
`IPath`-based `findFilesForLocation`/`findContainersForLocation` are **deprecated in 4.41**. A file genuinely
outside the workspace has no `IFile` at all — the alternatives are
`IDE.openEditor(page, URI, editorId, activate)` or `openEditorOnFileStore`. `IResource.isLinked()`,
`getRawLocation()` and `getLocationURI()` are what describe a link. Second trap: the workspace namespace
preserves case while the file system may not, so a string-prefix check is not a path jail — and **`IPath` does
not resolve symlinks**, while `java.nio.file.Path.toRealPath()` does
([`IWorkspaceRoot`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/resources/IWorkspaceRoot.html)).

**R15 — Eclipse has no notion of "the one open project".** A workspace holds many projects, whose locations may
be anywhere (`IProject.getLocation()` is absolute and not necessarily under the workspace root; `getLocationURI()`
handles non-local file systems). The workbench page's input is the `IWorkspaceRoot`, not a project
([`CommonNavigator.getInitialInput`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/navigator/CommonNavigator.html)
documents the IDE case). So the owning project must be resolved per request — most naturally from the file path.
The workspace's own metadata area "should be accessed only by the Platform or via Platform API calls".

**R16 — A batch of document changes is one undo step only inside a compound change.**
`IDocument.replace(offset, length, text)` changes the buffer and dirties the editor; wrapping a batch in
`IRewriteTarget.beginCompoundChange()` / `endCompoundChange()` makes the whole batch **one** undo command — the
javadoc of `begin` says it "tells the undo manager to fold all subsequent changes into one single undo command",
and after `end` "all subsequent changes are considered to be individually undo-able". The target comes from the
viewer (`ITextViewer.getRewriteTarget()`). Programmatic undo/redo is `ITextOperationTarget.doOperation(UNDO|REDO)`
after `canDoOperation(...)`. `org.eclipse.text.edits.TextEdit.apply(IDocument)` is the other documented batch
route. `IDocumentExtension4.startRewriteSession(...)` is real but the low-level form, and JDT's
`CompilationUnitDocumentProvider` is internal wiring rather than a client edit API
([`IRewriteTarget`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/jface/text/IRewriteTarget.html),
[`ITextOperationTarget`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/jface/text/ITextOperationTarget.html)).

**R17 — Buffer versus disk, and what saves later.** Editing the `IDocument` changes the in-memory buffer only:
the part becomes dirty and nothing is written. Explicit saves are `IWorkbenchPage.saveEditor(...)`,
`saveAllEditors(...)`, `IDE.saveAllEditors(...)`, `IEditorPart.doSave(...)`, `IWorkspace.save(...)`. **The exact
autosave preference/API in 4.41 is not confirmed**, and focus loss is standard Eclipse behaviour rather than a
documented save trigger. Writing to disk instead means `IFile.setContents(...)` plus `IResource.refreshLocal(...)`,
batched with `IWorkspace.run(ICoreRunnable, ISchedulingRule, int flags, IProgressMonitor)` and
`IWorkspace.AVOID_UPDATE`; `IWorkspace.validateEdit(IFile[], Object)` is the documented hook for asking the UI
whether an externally modified file may be changed. **Hazard:** `IFile.setContents` on a file that is open in an
editor fights that editor's buffer and can lose the reader's unsaved work.

**R18 — Reveal in the Project Explorer is supported public API; the Package Explorer is not.**
`CommonNavigator` (bundle `org.eclipse.ui.navigator`) implements `org.eclipse.ui.part.ISetSelectionTarget` and
declares `selectReveal(ISelection)`, which "sets the selection … and expands nodes if necessary" and can activate
plug-ins as a side effect; its concrete subclass is
`org.eclipse.ui.navigator.resources.ProjectExplorer`. So:
`page.showView("org.eclipse.ui.navigator.ProjectExplorer")`, then `ISetSelectionTarget.selectReveal(new
StructuredSelection(ifile))`. `CommonNavigator.show(ShowInContext)` is what the "Show In" menu uses. The JDT
Package Explorer is a different view id whose reveal goes through internal API, and
`IPageLayout.ID_PROJECT_EXPLORER` is a layout constant for *placing* the view, not a guaranteed runtime instance
([`CommonNavigator`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/navigator/CommonNavigator.html),
[`ISetSelectionTarget`](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/part/ISetSelectionTarget.html)).

## 3. Threads and the platform

**R19 — Which thread, and which job.** Anything touching SWT widgets, `Browser`, or the editor UI
(`selectAndReveal`, `showView`, `selectReveal`, `setFocus`) must run on the SWT UI thread that created the
widget, or SWT raises `SWTException ERROR_THREAD_INVALID_ACCESS`; that means `Display.asyncExec`/`syncExec`.
Blocking work — a loopback server's accept/serve loop, disk I/O, computation — must not run there: the platform
shape is `org.eclipse.core.runtime.jobs.Job` (`IStatus run(IProgressMonitor)`, `schedule()`, `cancel()`,
`setRule(...)`, families via `belongsTo`), with workspace modifications under an `ISchedulingRule` from
`IWorkspace.getRuleFactory()` or inside `IWorkspace.run(...)`, and UI-driven multi-step work wrapped in
`IRunnableWithProgress` (for example `WorkspaceModifyOperation`) run through
`IWorkbenchWindow.run(...)` ([Jobs](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/runtime_jobs.htm)).

**R20 — Nothing in Eclipse or OSGi blocks a plugin from opening a loopback socket.** It is plain `java.net`; the
`SecurityManager` that might once have interfered is deprecated for removal in modern JDKs and ignored by
default; `plugin.xml`/`MANIFEST.MF` impose no runtime network restriction. The only install-time gate is p2's
trust prompt for unsigned content. Rendering a generated page in a view is equally unremarkable —
`new Browser(parent, SWT.EDGE)` plus `setUrl(...)` (R2, R12).

## 4. Packaging, releases and testing

**R21 — Eclipse 4.41 is the 2026-09 release (GA 2026-09-09), and Java 21 or greater is required.** The 4.39
(2026-03) download page states *"Java-21 or greater is required"*
([4.39 downloads](https://download.eclipse.org/eclipse/downloads/drops4/R-4.39-202602260420/)); 4.40 is 2026-06
and 4.41 is 2026-09. The package most Java developers use is *Eclipse IDE for Java Developers*; *Eclipse IDE for
Eclipse Committers* is the one that also carries PDE. New bundles install through a **p2 update site**
(*Help → Install New Software*, or the Marketplace, which is a p2 site behind a client); the `dropins/` folder is
still scanned at startup, but such a bundle does not participate in dependency resolution or updates, and the
precise 4.41 dropins semantics are **not confirmed** in current documentation.

**R22 — Testing an Eclipse plugin headlessly, and building one.** There is **no supported headless SWT**; Tycho's
guidance is that plain JUnit through `surefire` in an `eclipse-plugin` project is the headless fast path, while
the `eclipse-test-plugin` packaging "is not recommended for new designs"
([Testing Bundles](https://tycho.eclipseprojects.io/doc/latest/TestingBundles.html)). SWTBot is alive but its
last verifiable release (4.2.x, 2024) predates the trains this plan pins, so its currency is **unconfirmed**.
Tycho itself is at **5.0.4** (docs 2026-08-16) with 6.0.0 unreleased; packaging `eclipse-plugin`; the lifecycle
comes from a Maven extension; a target platform is either a p2 repository such as
`https://download.eclipse.org/releases/2026-09` or a `.target` file
([Tycho docs](https://tycho.eclipseprojects.io/doc/latest/), [target platform](https://tycho.eclipseprojects.io/doc/latest/TargetPlatform.html)).
A plain Maven/bnd build can produce a valid OSGi bundle but **cannot** resolve `Require-Bundle` against a p2
target platform, nor build features or update sites.

---

## Unconfirmed, or true only of particular versions

Nothing below may be depended on without a measurement (the pattern is
[`PHASE0-ECLIPSE-FINDINGS.md`](PHASE0-ECLIPSE-FINDINGS.md)).

* **Unconfirmed:** which of R4's two behaviours a missing WebView2 runtime produces on SWT `3.135.0`.
* **Unconfirmed:** the exact `dropins/` layout 4.41 accepts (R21), and the exact autosave preference/API in 4.41
  (R17).
* **Unconfirmed:** whether a `Browser` may be created in a non-primary Eclipse window, and whether any
  restriction applies there.
* **Unconfirmed:** SWTBot's current release and recommendation status (R22).
* **Unconfirmed by Eclipse docs, and therefore inherited from Chromium:** what `Origin` a `file://` document
  sends to loopback and whether its `fetch`/XHR succeeds (R14's neighbourhood). Eclipse documents nothing about
  it; the behaviour is the browser's opaque-origin rule.
* **Not an Eclipse API at all:** `AbstractUiTestCase` — a name that appears in some Eclipse-adjacent advice and
  does not exist in the Platform API. The supported shapes are R22's two.
* **Version-dependent:** the Edge default (R3) holds from SWT 4.35; `SWT.EDGE` from 4.19; the lambda adapters
  from 3.107; `setUrl(url, postData, headers)` and cookie access need Edge/WebView2 88+; `SWT.MOZILLA` is
  deprecated for removal since 2026-09 (R2); the URI-based `IWorkspaceRoot` finders are current while the
  `IPath`-based ones are deprecated in 4.41 (R14).
