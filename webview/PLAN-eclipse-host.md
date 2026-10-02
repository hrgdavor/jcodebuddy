# Plan — an Eclipse IDE host for `webview` (a fifth host, built on `webview-core`)

Status: **Phases 0–4 implemented, 2026-09-27.** Phase 0 has been **run** on this machine — the measured results
are in [`eclipse/PHASE0-ECLIPSE-FINDINGS.md`](eclipse/PHASE0-ECLIPSE-FINDINGS.md) (SWT 3.135.0, Edge runtime
154.0.4258.37; A2 and the `SWT.NONE` half of A recorded as not measured). Phase 1a (`DocumentEdits` promoted
into `webview-core`), Phase 1 (commit `ee50637`), Phase 2 (`835f7a5`), Phase 3 (`d7c5fb7`) and Phase 4 (the
documents sweep, same day) are **implemented**; their headless gates ran green from the agent session on this
machine — the record below lists each. Findings are cited as
`R<n>` into [`eclipse/PLATFORM-REFERENCE.md`](eclipse/PLATFORM-REFERENCE.md) (what the Eclipse sources say,
22 items with sources); § 5.2 is the measured half, and its results sit in the findings file. Nothing here is
marked *observed* for the IDE gates, because the 2026-09 / 4.41 build those gates name is not on this machine —
an older install exists (`D:\programs\eclipse`, core runtime 3.34.200, a 2025-12 generation), and it is not the
build the gate claims (§ 9, "the machine"). The outstanding observations are enumerated in
[`doc/ide-observation-checklist.md`](doc/ide-observation-checklist.md) § 1a and § 2a.

**Open, and scheduled (2026-10-01).** **Phase 5** (the Tycho/p2 update site) is not started and is gated on
**Q2**, which is still open; **Q8** stays open too — no `EdgeDataDir` and no allowed-origins preference, so
R11's contention is documented rather than configurable (the deviation recorded below). Nothing else in this
plan is unfinished. Those two, together with the human-observation gates, are steps 5.4 and 8.2 of
[`../plans/unified-plan.md`](../plans/unified-plan.md).

Measurement words, used precisely, as everywhere in this folder: **documented** = a named source says so;
**implemented** = code exists and its tests pass; **observed** = someone ran it against that host on the build
named and reported the result.

> **Implementation record — Phases 1–4, 2026-09-27.**
>
> | Phase | Shipped | Commit |
> | --- | --- | --- |
> | 1 | the Maven module (`release 21`, committed `MANIFEST.MF` + `plugin.xml`, the Require-Bundle completeness test, the assembled-bundle test), `WebViewPart` + `InjectedBridge` (`BrowserFunction`), `WorkspaceFiles`, `EclipseEditorHost`, `EclipseNavigator` | `ee50637` |
> | 2 | `EclipseHttpBridge` (loopback-only, the claim through `HostPortClaim`, the descriptor left in place, `/open`, `/file/`+`/page/` through core's `PageServer`, CORS/OPTIONS, rate limit), `UiThreadHost`, the preferences (enabled, splash, port, token override — off until a port is named, E17), `HostHealth.PLUGIN_ECLIPSE`/`IDE_ECLIPSE`, five entries in `HostHealthParityTest.hosts()`, `EclipseConformanceVectorsTest` | `835f7a5` |
> | 3 | the `EclipseDocumentEditor` seam, `DocumentBufferEditor` (one `IRewriteTarget` compound change, never saves — R16/R17), `CAP_EDIT` with dynamic availability, the write verbs through core's `WriteSurface` + `EditService` with a **persistent** `CheckpointStore` under `.jcodebuddy/webview/checkpoints/` (undo survives a host restart; one rate budget for navigation and writes), token-only state-changing routes (D8), `/api/v1/events` → 404 (no `watch` declared) | `d7c5fb7` |
> | 4 | this record, the DEC-033 amendment + index row, and the documents sweep of § 7 Phase 4's table | this commit |
>
> **Gates observed headlessly** (this machine, from the agent session, 2026-09-27): the eclipse module
> **58/58**; `webview-core` **173/173** (`HostHealthParityTest` re-reads the new Eclipse sources);
> `webviewd` **58/58**; the repository gate `bun scripts/mvn-jdk25.js` **BUILD SUCCESS**;
> `webview/tools/check-port-claim.js` **24/24** (with `WEBVIEWD_JAVA` pinned to JDK 25). The module gate runs
> **without `-am`** and with `clean` (MDEP-98, as the Phase 1 gate's correction says), installing
> `webview-core` into the local repository first.
>
> **Gates still outstanding — they need Eclipse 4.41 and a human**, and are claimed nowhere as observed:
> the caret landing (Phase 1), the live two-hosts-on-one-project claim and the browser-beside-Eclipse page
> (Phase 2 gates (a)/(d)/(e)), the unsaved buffer edit and the single `Ctrl+Z` (Phase 3), and which dropins
> layout p2 accepts (Q2). Run sheet: [`doc/ide-observation-checklist.md`](doc/ide-observation-checklist.md) § 1a.
>
> **Deviations, recorded honestly.** (1) § 11.6's literal "no document for the path → 409 `no-buffer-edit`"
> cannot arise from the shared `WriteSurface` for a Java host: a per-path refusal is the seam's `false`, and
> the surface's documented disk fallback follows for target `auto`/`buffer`; the 409 is produced only where
> the host declares no `edit` capability at all (headless). E8's "the plugin decides the routing" is honoured
> as "the seam decides what the host can carry; `WriteSurface` decides the routing and the statuses".
> (2) E16's preference list is larger than what Phase 2 shipped: there is no `EdgeDataDir` and no
> allowed-origins preference (the allow-list is the served page's own origin), so Q8 stays open and R11's
> contention is documented rather than configurable. (3) The reveal capability is absent, not missing —
> the JetBrains precedent (§ 8's rule).

---

## Read this first

**What will be built.** An Eclipse IDE plugin (`webview/eclipse/webview-eclipse`) that renders generated pages
in an SWT `Browser`, injects the frozen `window.openFile` contract through a `BrowserFunction`, answers `/health`,
`/open` and `/file/`+`/page/` on loopback, claims a port through `webview-core` like every other host, and lands
a page's edit in the editor's own buffer so one `Ctrl+Z` takes it back. Full host parity was chosen in this
session.

**The five facts everything else follows from.**

1. **The JetBrains design does not transfer.** There is no `SWT.CHROMIUM` and no supported JCEF-in-Eclipse route
   (the 2017 CEF/SWT work was never funded) — R2, R5.
2. **What happens without the WebView2 runtime is documented two contradictory ways** (a silent IE fallback, or a
   thrown `SWT.ERROR_NOT_IMPLEMENTED`), and SWT's readme still claims IE is the Windows default that the FAQ and
   `BrowserFactory` moved to Edge in 4.35 — R3, R4. Hence Phase 0 measures, and the host asks
   `getBrowserType()`.
3. **OSGi cannot see a plain jar.** `webview-core` is not a bundle, so the plugin must embed it or bundle it.
   This plan embeds — E15. Without that decision the bundle compiles and fails at runtime with
   `NoClassDefFoundError`, the same shape as the JetBrains missing-`<depends>` bug.
4. **Eclipse has no "current project"**, and a workspace root is not a directory of projects — so the host binds
   per `IProject` and reports that project's location, or the port-claim rule compares the wrong strings —
   R15, E7.
5. **A batch of `IDocument` changes is one undo step only inside `IRewriteTarget.begin/endCompoundChange()`** —
   R16, E8.

**The four decisions a reviewer should argue with** (the rest are mechanical): **E4** (the host is a Maven
reactor module, unlike the other two editor hosts), **E7** (binding per project), **E12** (serve the pages
same-origin rather than loading them from `file://`), and **E15** (embed core+Gson in the bundle). Each states its
alternative and its cost; § 10 carries them as questions with their current state.

**What this needs from you (the maintainer).**

| Need                                        | Why                                                                                        | When           |
| ------------------------------------------- | ------------------------------------------------------------------------------------------ | -------------- |
| A normal shell — not an agent sandbox       | this session started in a sandbox that refused `mvn`, `bun`, `node` and `git status` (`Access is denied`; they write outside the workspace); the file policy has since been lifted to full access, and the Phase 0 and 1a gates ran in this shell | every phase |
| An Eclipse IDE, 2026-09 / 4.41, on Java 21+ | the *observed* gates: the view, the caret, the buffer edit, the dropins install; the machine has an older install (`D:\programs\eclipse`, core runtime 3.34.200, a 2025-12 generation) that does not satisfy this gate | Phase 1 onward |
| ~½ day per observation, three times         | navigation (Phase 1), browser fallback and port behaviour (Phase 2), buffer edit (Phase 3) | Phases 1–3     |
| Nothing for Phase 0's *code*                | the probe needs only Maven Central bundles and a desktop session                           | Phase 0        |

**What this plan deliberately does not do.** It does not add an Eclipse row to any host table, capability matrix
or support matrix. Those tables use *implemented* and *observed* precisely, and a row for a host that does not
exist is the dishonesty the rest of this folder exists to prevent
([`doc/webview-host-api.md`](doc/webview-host-api.md) § 8). The table edits are Phase 4, in one commit with the
host that makes them true. The plan's own files do exist: [`eclipse/README.md`](eclipse/README.md),
[`eclipse/PLATFORM-REFERENCE.md`](eclipse/PLATFORM-REFERENCE.md) and
[`eclipse/PHASE0-ECLIPSE-FINDINGS.md`](eclipse/PHASE0-ECLIPSE-FINDINGS.md), the last now carrying the Phase 0
measurement.

**Where the plan lives.** Next to the product it plans, following [`PLAN-webview-suite.md`](PLAN-webview-suite.md)
rather than the root `plans/<project>/` convention. No rule fixes a plan's location (the JetBrains
`plan.reimplement.md` raises the question and leaves it open), so the choice is stated here and the plan is
cross-linked from `PLAN-webview-suite.md` § 4 and § 7 in Phase 0.

**Scope.** Exactly one thing: an Eclipse IDE desktop plugin. **Not** in scope: Eclipse Theia / Eclipse Che (a
VS Code-API runtime, closer to `webview-vscode`), Eclipse RAP or any browser-hosted Eclipse, and Eclipse JDT LS
over LSP — `jwa-sidecar` already covers the LSP tier. If the request meant one of those, this is the wrong
artifact.

**Written against.** JDK 25 (`C:\Program Files\Java\jdk-25`; the repository's launcher is
`bun scripts/mvn-jdk25.js`); **Eclipse 4.41 / the 2026-09 release train** (GA 2026-09-09, Java 21+ — R21); the
Eclipse bundles from Maven Central (SWT `3.135.0`, `org.eclipse.ui` `3.209.100`, `org.eclipse.ui.browser`
`3.9.200` — R1); and `webview-core` as it stands after the Phase 3 records in
[`PLAN-webview-suite.md`](PLAN-webview-suite.md).

---

## 1. The goal in one paragraph

A developer who lives in Eclipse should get what a JetBrains or VS Code user already has: a generated HTML page
that opens in the IDE, where a click on a field puts the caret on that member's source line, an edit proposed by
the page lands in the editor's buffer unsaved and is undone with `Ctrl+Z`, and a page opened in an ordinary
browser can reach the same IDE over loopback. The page contract is frozen and stays frozen; what is new is one
more implementation of `EditorHost` and one more server that publishes the port it bound. Eclipse is the first
host that has to be *built* rather than *adapted*: this repository contains no Eclipse plugin, no `org.eclipse`
IDE dependency in any POM (only ECJ, LSP4J and JGit), and no Eclipse IDE is installed on the machine (§ 9).

## 2. Where the code is today, and what is reused

The Eclipse host is mostly wiring, because Phase 1 of the suite plan already extracted the parts that had been
duplicated three times. Nothing below is copied; the point of each row is *which* core class the Eclipse plugin
delegates to.

| Piece                                                                                       | Location                                                            | What the Eclipse host does with it                                               |
| ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `InjectedBridge`                                                                            | `webview/core/webview-core/.../InjectedBridge.java`                 | the script text and the `window.__jcbWebViewBridge` marker; the transport closure is Eclipse's (a `BrowserFunction`). Its `VERSION` is the `bridgeVersion` `/health` must report |
| `BridgeMessage`                                                                             | same module                                                         | parses the JS payload as JSON — the plugin never string-splits a path            |
| `PathResolver`, `PathResolution`                                                            | same module                                                         | the path jail: a page's relative or absolute spelling becomes an absolute path inside the project, **with symlinks resolved** — which matters more here than anywhere else, because Eclipse's `IPath` does not resolve them (R14) |
| `Navigator`, `NavigationOutcome`                                                            | same module                                                         | turns an authorized request into `EditorHost.openFileAt`, owns the rate limit (20 per 20 s) and the 404-vs-refused distinction |
| `AllowedOrigins`, `RateLimiter`, `Clock`                                                    | same module                                                         | authorization and the limiter, shared by the injected and HTTP entry points. The CORS **header emission** decision stays with the host, as in every host |
| `HostHealth`                                                                                | same module                                                         | the `/health` document — byte-identical across hosts with equal abilities — plus the `PLUGIN_*`/`IDE_*` constants and `defaultIdeFor`. The Eclipse host adds its own constants rather than string literals (E13) |
| `HostPortClaim`, `HostConfig`, `HostDescriptor`                                             | same module                                                         | which port the host takes, when it takes none, and where it publishes it (DEC-033). The host binds its socket **inside** the claim, as the JetBrains service does |
| `EditService`, `CheckpointStore`, `SourceDigest`, `UnifiedDiff`, `EditableText`, `TextEdit` | same module                                                         | the disk half of the write contract, if the Eclipse host offers disk writes at all (Q4). `CheckpointStore.persistent(...)` exists and this plan uses it (E8) |
| `WriteSurface`                                                                              | same module                                                         | the write *conversation*: statuses, bodies, `target: auto\|buffer\|disk`, `dryRun`. It is constructed as `WriteSurface(EditService, EditorHost)`, so the buffer path is **the host's own `applyEdit`** — which is why the Eclipse host needs no new core seam beyond `DocumentEdits` |
| `EditorHost`                                                                                | same module                                                         | the interface the Eclipse plugin implements: `name`, `isAvailable`, `capabilities`, `openFileAt`, `reveal`, `select`, `applyEdit` |
| `PageServer`                                                                                | same module                                                         | `/file/` and `/page/` — the Eclipse view loads its pages through this (E12), so the page and the API are same-origin. It carries the `X-WebView-Digest` header the JetBrains host does not |
| **`DocumentEdits`**                                                                         | **`webview-jetbrains`** today                                       | it belongs to the *product*, not to the JetBrains plugin: a 34-line pure function over core's `EditableText` with no IDE types. Phase 1a promotes it into `webview-core` and makes both IDE hosts delegate, rather than copying a rule about where an edit lands |
| conformance vectors                                                                         | `webview/conformance/bridge-decisions.json`                         | the Eclipse-side test asserts `AllowedOrigins`, the CORS decision, `RateLimiter` and the `healthKeys` list against the same file, like `ConformanceVectorsTest`. The file has no host list: a host is covered by **reading** it |
| observation tool                                                                            | `webview/tools/observe-edit-host.js`                                | host-agnostic (`--port`, `--token`, `--file`, `--find`, `--replace`, `--checkRefusals`, `--watchSeconds`): it drives the Eclipse host exactly as it drives JetBrains and VS Code, and it is what turns a claim into an observation |
| **two hard-coded lists and one comment**                                                    | `HostHealthParityTest`, `MigrationCompletenessTest`, root `pom.xml` | a fifth host not added to them **goes unchecked, or is silently unswept** — § 13 |

**The one change to existing code.** `DocumentEdits` moves from
`webview/webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/bridge/DocumentEdits.java` into `webview-core`
(same body, package `hr.hrg.webview.core`, javadoc re-pointed), its five `DocumentEditsTest` cases move with it,
and the plugin calls the core class. This is AGENTS § 1.1's "promote what was never project-specific" move: two
hosts need the identical function, and a copy is how the buffer and disk halves of one contract start disagreeing
about where an edit goes. Nothing else in core changes except two constants and one test list (E13, § 13).

## 3. Decisions

The `Status` column uses three words and nothing else: **proposed** (nothing measured — the owning phase must
confirm it), **rests on R…** (its reason is a documented finding, cited by id), and **corrected 2026-09-27**
(this review changed it — those are the four worth reading twice).

| #   | Decision                                                | Choice                                                                                     | Why | Status                                                     |
| --- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------ | --- | ---------------------------------------------------------- |
| E1  | What "Eclipse host" means                               | an Eclipse IDE plugin: an SWT `Browser` view plus a loopback HTTP bridge, the shape of `webview-jetbrains` | the user's answer in this session; it is also the only shape that gives Eclipse a *page* rather than an LSP channel a page cannot render | proposed; rests on R5 |
| E2  | Embedded engine                                         | create the `Browser` with **`SWT.EDGE`** explicitly, then **ask** `Browser.getBrowserType()` and `org.eclipse.swt.browser.EdgeVersion` what was obtained, and treat anything that is not `edge` as a reported, degraded state | `SWT.NONE` on Windows has *already* meant Edge since SWT 4.35, so `SWT.EDGE` is explicitness plus support back to 4.19 (R3). What happens **without** the WebView2 runtime is documented two ways (R4) and both are bad: IE breaks the pages' modern JavaScript, a thrown constructor means no view. The host never assumes; it reports | rests on R2, R3; **R4 is still unconfirmed — Phase 0's A2 was not run; A1 measured `edge` with runtime 154.0.4258.37 on this machine** |
| E3  | How the page talks to Java                              | a **`BrowserFunction`** — a real JS function backed by a Java method — with `InjectedBridge.script(transport)` supplying `window.openFile` on top of it | the platform's supported Java↔JS channel; no socket needed; visible in the top window and all child frames; on WebView2 SWT registers it *before page scripts run* on every future document (R7, R8). Its restrictions shape the bridge: UI thread, narrow argument list, explicit `dispose()` | rests on R7, R8 |
| E4  | Build **and reactor membership**                        | **plain Maven**, module `webview/eclipse/webview-eclipse`, Eclipse bundles `provided`-scope from Maven Central; **listed in the root POM's `<modules>`**, unlike the two existing editor hosts | the repo's toolchain is `bun scripts/mvn-jdk25.js` and the gate runs with `-o`; Tycho resolves against a **p2** target platform and exists to build features and update sites, which is packaging (Phase 5) — R22. The root POM's comment explains that `webview-jetbrains` and `webview-vscode` are absent **because they are not Maven builds**; that reason does not apply, and the reactor already resolves third-party artifacts (`org.eclipse.jdt:ecj` in `java-watch-run`, LSP4J, JGit, Jackson), so Eclipse Platform bundles are not a new class of dependency. Membership also avoids the documented "install `webview-core` to `mavenLocal` first" footgun the Gradle host has. The cost is paid in the same commit: the `<modules>` comment, the root README's module count, and `MigrationCompletenessTest.MODULES` (§ 13) | **corrected 2026-09-27** (membership); rests on R1, R22 |
| E5  | Distribution, first release                             | install by **copying the built bundle into `<eclipse>/dropins/`**, documented step by step | no feature, no `category.xml`, no p2 metadata. A p2 update site is the *supported* route (R21) and is Phase 5, optional, with its cost stated there. The exact dropins layout in 4.41 is unconfirmed, so Phase 1 tries the simple form and, if p2 ignores it, `dropins/webview/plugins/<symbolicName>_<version>.jar`, and **records which one worked** | rests on R21 (unconfirmed detail) |
| E6  | The port                                                | request **18883** (JetBrains 18881, VS Code 18882), resolved and claimed through `HostPortClaim`/`HostConfig`, published in the served project's `.jcodebuddy/webview/host.json` | one more host needs one more conventional request; the claim rule is not re-implemented, and the listed port is a *request* (DEC-033) | proposed |
| E7  | Which "project" a host serves                           | the bridge is **per Eclipse project** (`IProject`), bound to the project that owns the page being viewed; first bind wins and is logged; `/health` names that project's directory | a workspace is a *set* of projects whose locations may be anywhere, and the workbench page's input is the **workspace root**, not a project (R15). `HostPortClaim` decides "another host for **my** project" by comparing `project`, so a workspace-root answer would collide with `webviewd` and lie about what it serves | rests on R15 |
| E8  | Write verbs                                             | the change goes to the **editor's buffer** by default (`target: buffer`), inside one `IRewriteTarget` compound change so one `Ctrl+Z` takes it back; disk writes delegate to core's `EditService` with **persistent** checkpoints under `.jcodebuddy/webview/checkpoints/`; a file the platform holds no document for is **refused** with `409 no-buffer-edit` | same contract and statuses as the other hosts: `WriteSurface` decides, not the plugin. `IRewriteTarget` is the documented one-undo-step route (R16). The refusal rule is the capability rule's second half — "impossible ⇒ refused with a reason" — and is what VS Code already does for an unopened file. Persistent checkpoints are a deliberate improvement on the JetBrains host's in-memory store, whose consequence `doc/webview-host-api.md` § 8 already documents | rests on R16; the UI-thread half on R19 |
| E9  | `plugin.xml`, DEC-019, and the dispatcher               | `plugin.xml` stays a **thin registration** — a view, a preference page, a startup hook, commands — each naming one concrete class; the program flow from the view to `Navigator` to `EditorHost` is direct Java calls; `/api/v1/*` routes by a **`switch` with a direct call per case**, never a `Map<String, Method>` or a scanned registry | extension points are declarations the platform requires, exactly as the JetBrains `plugin.xml` is; DEC-019 protects that the *wiring* is committed, navigable Java — and it names REST dispatchers explicitly. `HttpBridgeService.handleWrite`'s `switch` is the precedent (§ 6 has the extension-point table) | proposed |
| E10 | Tests without an IDE                                    | IDE types sit behind narrow interfaces (`WorkspaceFiles`, `EclipseDocumentEditor`) with hand-written fakes; the pure parts (message→request, capability declaration, manifest *and bundle contents*, the import→bundle map, port-claim wiring) are unit-tested; anything needing a workbench or a `Display` is an **observation** | matches the JetBrains discipline (30 tests, no IDE) and keeps "implemented and unit-tested" honest. There is no supported headless SWT (R22), so this is a constraint, not a preference | rests on R22 |
| E11 | Is this a `project-automation` module                   | **no.** It is an IDE plugin, and `webview-core` remains the only shared implementation     | AGENTS § 1.1 governs `project-automation` modules; this is not one, and the plugin must never become a dependency of another module. The repo-wide `ProjectAutomationIsolationTest` walks every POM and enforces the neighbourhood rules (§ 9) | proposed |
| E12 | How the view loads a page                               | in the finished host: through the host's own loopback server — `http://127.0.0.1:<port>/page/<path>?token=…` via core's `PageServer` — so page and API are **same-origin**, and `serveFile` joins the capability list. A `file://` page is still injected for, but is not how the host's own pages arrive | a `file://` document is an opaque origin: it sends `Origin: null` to loopback, and a plain `fetch` **does reach** the loopback server (Phase 0 D measured status 200; a permissive `Access-Control-Allow-Origin: *` answers it), but an empty allow-list still denies it, so a `file:` page needs the token. Serving the page removes the problem instead of documenting it; carrying the token in the loaded URL is what lets the same-origin page call the **token-only** write routes, and it is what `webviewd` does. **Phase 1 has no server**, so it loads pages from a path or URL (`file://`) — bridge injection is what makes navigation work there — and Phase 2 adds serving | **corrected 2026-09-27** (the Phase 1 half, and Phase 0 D's measurement); rests on R14's neighbourhood, which Eclipse does not document |
| E13 | How the host is named                                   | through core's constants: **`HostHealth.PLUGIN_ECLIPSE`** and **`IDE_ECLIPSE`** (the fallback for `defaultIdeFor`), plus the `ide` value read from the platform's product name (`Platform.getProduct().getName()`, `IDE_ECLIPSE` when there is none) | `HostHealth` owns the identity vocabulary for every host, and `HostHealthParityTest` fails a host that writes a capability key or its identity inline (§ 13). A string literal in the plugin would be the drift that test exists to catch | proposed |
| E14 | Helper scripts                                          | any script this work needs is a **Bun `.js`** run as `bun <path>` — so far only the Phase 0 probe. No `.ps1`, `.bat`, `.cmd`, `.sh` or `.fish`, not even "just this once" | AGENTS § 2; a check that runs in one shell on one OS is invisible wiring for the workflow. `GateContractTest` scans only `scripts/`, so a violation next to this host would not be caught by the build — the rule still binds | proposed |
| E15 | How `webview-core` and Gson reach the plugin at runtime | **embed them in the bundle**: `maven-dependency-plugin:unpack-dependencies` copies `webview-core` and `gson` into `target/classes` at `prepare-package`, then `maven-jar-plugin` builds the jar with the **committed** manifest and `Bundle-ClassPath: .`; the embedded packages are **not exported**, and the unpack excludes `META-INF/**`, `module-info.class` and signature files | OSGi does not put a plain jar on a bundle's classpath: `Import-Package` resolves against exported packages of installed bundles, and `webview-core` is not a bundle (R6). Embedding keeps the install to **one file** and keeps the platform's own `com.google.gson` bundle out of a version negotiation. **Not `maven-shade-plugin`**: it rebuilds the jar, and would take the committed manifest with it unless every header were duplicated into a manifest transformer — two sources of truth for the bundle's identity. Not exporting the embedded packages is what keeps them private, so a future platform bundle exporting `com.google.gson` cannot collide with ours. The alternative (give `webview-core` a bnd manifest, install it as a second bundle, `Require-Bundle` it) is cleaner OSGi but makes core a *published* bundle with an export contract and the install two files that must match versions. The README records what is bundled and its licence (Gson, Apache-2.0) | **corrected 2026-09-27** (the mechanism); rests on R6, R22 |
| E16 | Where the token lives                                   | **in host state, not configuration**: the host generates the secret into `<project>/.jcodebuddy/webview/token` and publishes its path in `host.json`'s `tokenPath`; preferences hold only configuration (port default, allowed origins, `EdgeDataDir`, an optional explicit token override) | AGENTS § 2 and DEC-032 are explicit that a token is host state — "a fact about one checkout on one machine" — belonging in the served project's `.jcodebuddy/webview/`, never in `conf/` or a `config` file. A deliberate improvement on both existing hosts: JetBrains keeps it in IDE workspace state (and writes `tokenPath: ""`), VS Code in editor settings. The Eclipse host does what `webviewd` does, and a preference can still override it for a caller who wants a fixed secret | **corrected 2026-09-27** |
| E17 | When the endpoint is on                                 | **off until a port is named** — the fallback is `-1` (the JetBrains convention), and the host starts when an explicit preference, this checkout's current port, or the project's committed `.jcodebuddy/conf/webview.json` names one | an IDE plugin that opens a listening socket merely because it was installed is a surprise, and the JetBrains README says so in as many words. The committed project default is exactly the mechanism that turns it on for a project without anyone configuring an IDE, so "off by default" costs a fresh clone nothing but a file in the repo it cloned | **corrected 2026-09-27** |

## 4. Target layout

```
webview/
  PLAN-eclipse-host.md              this file — the argument and the phases
  eclipse/
    README.md                       DELIVERED as a stub: the four facts, and the build/install steps to come
    PLATFORM-REFERENCE.md           DELIVERED: what the Eclipse sources say (R1–R22), cited
    PHASE0-ECLIPSE-FINDINGS.md      DELIVERED as a stub: "NOT RUN", the six experiments, the gate
    phase0/
      README.md                     DELIVERED as a stub: what the apparatus will be and how it runs
      probe.mjs                     Phase 0: resolves the jars, compiles and runs SwtProbe, prints the facts
      SwtProbe.java                 Phase 0: the probe itself
    webview-eclipse/
      pom.xml                       JDK 25 toolchain, `release 21`, `provided` Eclipse bundles, unpack step
      src/main/resources/META-INF/MANIFEST.MF   the committed OSGi identity (E15)
      src/main/resources/plugin.xml             the thin registration (extension points → classes)
      src/main/java/hr/hrg/eclipse/webview/
        WebViewPlugin.java            AbstractUIPlugin: owns the per-project bridges, stops them on shutdown
        view/WebViewPart.java         the ViewPart: owns the browser, the address bar and the toolbar
        view/BrowserFactory.java      creates the Browser with SWT.EDGE and reports the engine it got
        view/UnsupportedEnginePanel.java  what is shown when the engine is not Edge (R4)
        view/SplashPage.java          the data: page shown before anything is opened
        bridge/EclipseBridge.java     registers the BrowserFunction, injects the script, parses messages
        bridge/EclipseNavigator.java  the ONE place a path becomes an open editor
        bridge/EclipseEditorHost.java implements core's EditorHost (name, availability, capabilities, verbs)
        bridge/EclipseDocumentEditor.java  the seam: apply an edit to a document, in one undo step
        bridge/DocumentBufferEditor.java  the Eclipse implementation of that seam (E8)
        bridge/WorkspaceFiles.java    interface: path → IFile (and the URI variants), plus the Eclipse impl
        http/EclipseHttpBridge.java   the loopback endpoint: /open, /health, /file/, /page/, CORS, token, limit
        http/BridgeStartup.java       starts the bridge for a project, claims the port, publishes the record
        actions/OpenInWebViewAction.java  "Open in WebView" for an .html file, plus its handler
        prefs/WebViewPreferences.java project-scoped preferences (port, origins, EdgeDataDir)
        prefs/WebViewPreferencePage.java the form, under a WebView category
      src/test/java/…               the tests that need no workbench
```

`actions/` is not decoration: without it a reader has no way to open a page, and the JetBrains host's equivalent
(a context action plus a key binding) is the part a user actually meets.

## 5. The findings this plan rests on

### 5.1 Documented — the load-bearing ones

The full cited list is [`eclipse/PLATFORM-REFERENCE.md`](eclipse/PLATFORM-REFERENCE.md), which carries 22 items
including the ones that decided nothing here. Only the nine below decide something in this plan; `R<n>` ids are that
file's.

| Finding                                                                                           | Reference                                                                               | What it decides here                                                                            |
| ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Edge/WebView2 is the Windows default since SWT 4.35, and SWT's own readme still says IE           | R2, R3                                                                                  | E2 — pass `SWT.EDGE` explicitly and then ask what you got, rather than trusting either document |
| A missing WebView2 runtime is documented two contradictory ways                                   | R4 *(unconfirmed — Phase 0's A2 was not run; A1 measured Edge present on this machine)* | E2's degraded-state handling, and the README's install steps                                    |
| There is no `SWT.CHROMIUM` and no supported JCEF route                                            | R2, R5                                                                                  | E1/E2 — the whole host shape; the JetBrains design does not transfer                            |
| `BrowserFunction` is UI-thread, narrow-typed, and must be disposed and re-registered per document | R7, R8                                                                                  | E3, the bridge's shape, and "one JSON string, never structured arguments"                       |
| `completed` fires for the top-level document only, and `evaluate` throws inside two handlers      | R9                                                                                      | When the bridge is injected; never evaluate from `LocationListener.changing`/`OpenWindowListener.open` |
| WebView2 serializes callbacks: blocking one deadlocks the handler                                 | R10                                                                                     | The function returns promptly; slow work goes to a `Job` (R19)                                  |
| Eclipse has no "current project", and `IPath` is neither a jail nor symlink-aware                 | R14, R15                                                                                | E7 (bind per `IProject`), and why the jail stays in core                                        |
| One undo step needs a compound change; reveal is supported only via `ISetSelectionTarget`         | R16, R18                                                                                | E8; Phase 4's `reveal` claim                                                                    |
| There is no headless SWT, and Eclipse 4.41 requires Java 21                                       | R21, R22                                                                                | E10's fakes; `release 21` and `JavaSE-21` in the manifest                                       |

### 5.2 To measure in Phase 0 (nothing may depend on these yet)

| #   | Question | Why it decides something                                                           |
| --- | -------- | ---------------------------------------------------------------------------------- |
| A   | With SWT `3.135.0` here, what does `new Browser(shell, SWT.EDGE).getBrowserType()` return, what does the `org.eclipse.swt.browser.EdgeVersion` property (which SWT sets itself after the first Edge browser) report, and what does `SWT.NONE` give? Then, with the WebView2 runtime hidden (`EdgeDir` pointed at nothing): throw, dialog, or IE fallback? | R3 and R4 disagree in writing. A thrown constructor changes the view's shape (a panel instead of a browser) and the README's install instructions |
| B   | Is `BrowserFunction.function()` called on the SWT UI thread, can its return reach JavaScript synchronously, what happens when it throws, and does a document after `dispose()` + re-register get the function back? | decides whether the navigator marshals at all, how a failure reaches the page (the injected script has no error channel today), and whether the bridge re-registers per document as R8 says |
| C   | Does `ProgressListener.completed` fire for `http://127.0.0.1:<port>/…`, for `file:///…`, and for the `data:` splash? Does `evaluate()` work from that handler? | decides when the bridge is injected and whether the splash needs re-injection (R9) |
| D   | What `Origin` does a `file://` page send to loopback, does a plain `fetch` succeed, and what does a `/page/`-served page do instead? | E12 assumes same-origin is right and that a `file:` page needs a token; this measures both halves rather than inheriting Chromium folklore into an Eclipse document |
| E   | Can a `Browser` — or even `new Display()` — be created in a **unit-test** process with no workbench, and does it succeed in a normal user session? | decides whether any Eclipse-side test may touch SWT (E10 expects not), and whether the probe must be a person-at-a-desktop tool |
| F   | On the installed Eclipse: version, product name (`Platform.getProduct().getName()`), and that it runs on the repository's JDK 25. | R21 says Java 21+; the *installed* product must be named for `ide` in `/health`, and "never blank" is a rule (E13) |

**Phase 0's apparatus is small and committed**, per E14: `webview/eclipse/phase0/probe.mjs` resolves the Eclipse
bundles into `target/` with the repository's Maven launcher, compiles `SwtProbe.java` with `javac` from
`JAVA_HOME`, runs it with the jars on the classpath, and prints one line per experiment. It opens and closes
short-lived windows, so a person runs it at a desktop. Its README is already committed and says what it will be.

## 6. The verbs, the capability claims, and the Eclipse API behind each

**What the host will declare, and what proves it.** The capability rule is two-directional — *declared ⇒ served*
and *impossible ⇒ refused with a reason* — so every claim names its proof.

| Capability          | Declared when                                     | Proven by                                                        |
| ------------------- | ------------------------------------------------- | ---------------------------------------------------------------- |
| `open`, `select`    | a workbench page exists (`isAvailable()`)         | unit test: the capability set equals the declared constants; **observation**: the caret lands on the named line (Phase 1) and a range is selected (Phase 2) |
| `edit`              | the same, and only for a file the platform holds a document for; otherwise the request is refused `409 no-buffer-edit` | unit test: the editor seam returns false for an unknown path; **observation**: the edit is unsaved and one `Ctrl+Z` undoes it (Phase 3) |
| `serveFile`         | E12 lands — the host serves `/file/` and `/page/` | the capability-honesty run + the Eclipse-side conformance test   |
| *(absent)* `reveal` | only if Phase 4 implements and observes it        | the same rule that keeps `reveal` off the JetBrains list today   |
| *(absent)* `watch`  | never — no SSE route is planned                   | not declared ⇒ a page asking for it is refused, not left waiting |

`isAvailable()` is false when there is no workbench page (a plugin loaded into a headless product), and then the
capability set is empty — the honest answer, and the same one `webviewd`'s `NullHost` gives.

| Verb               | Eclipse API                                                       | Notes                                                                       |
| ------------------ | ----------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `open`             | `ResourcesPlugin.getWorkspace().getRoot()`, the **URI** lookup for linked resources •, then `IDE.openEditor(page, file, true)`, then `getAdapter(ITextEditor.class)` and `selectAndReveal(document.getLineOffset(line - 1) + (column - 1), 0)` | null-check the part (R13); refuse a path outside the project in core **before** this call; return **false** when no `ITextEditor` adapter exists, which makes the caller answer 404 rather than a silent no-op (`EditorHost.openFileAt`'s javadoc); `lineNavigation` is `exact` only after the caret is observed |
| `select`           | the same editor, with an `ITextSelection` for the range's offsets | core's `TextRange` is one-based line/column; the conversion lives in one method, tested against `EditableText` |
| `edit` → buffer    | `IDocument.replace(...)` inside `IRewriteTarget.beginCompoundChange()`/`endCompoundChange()` on the UI thread, text computed by core's `DocumentEdits`; no `IDocument` for the path ⇒ `409 no-buffer-edit` | one compound change is what makes one `Ctrl+Z` restore the original (R16); the plugin must not save (E8, R17) |
| `edit` → disk      | core's `EditService` + `CheckpointStore.persistent(...)` (digest, atomic write, journalled checkpoints) | offered only if Q4 answers as this plan expects. Never `IFile.setContents` on a file that is open in an editor — it fights the buffer and can lose the reader's unsaved work (R17) |
| `diff`             | core's `UnifiedDiff` in the answer body                           | showing it in Eclipse's compare view is a possible extra, not a requirement |
| `undo`/`redo`      | core's `CheckpointStore` (the host's history), while the editor's own `Ctrl+Z` (or `ITextOperationTarget.doOperation(UNDO)`) remains the reader's | two different histories, which the contract already distinguishes (R16) |
| `reveal` (Phase 4) | `page.showView("org.eclipse.ui.navigator.ProjectExplorer")` then `ISetSelectionTarget.selectReveal(new StructuredSelection(ifile))` | supported public API, unlike the JDT Package Explorer route; `IPageLayout.ID_PROJECT_EXPLORER` is a layout constant, not a guaranteed view instance; the call can activate plug-ins (R18) |
| `/health`          | core's `HostHealth` with `PLUGIN_ECLIPSE`, the product name (E13), the served project's directory, and `bridgeVersion` = `InjectedBridge.VERSION` | the key list is the shared `healthKeys` vector; `HostHealthParityTest` keeps it honest once the host is in its list (§ 13) |
| port               | `HostPortClaim` + `HostConfig` + `HostDescriptor` + the generated token (E16), published under `.jcodebuddy/webview/` | E6/E7/E17: 18883 is requested *when enabled*, `0` is never used (an IDE host is not ephemeral), and a second host for the same project opens nothing |

**The extension points, and the class each names** (this is what makes the wiring navigable — E9). Eclipse
requires these declarations; none is a registry that *is* the program's flow.

| Extension point                                  | Class                         | Purpose                                                                          |
| ------------------------------------------------ | ----------------------------- | -------------------------------------------------------------------------------- |
| `org.eclipse.ui.views`                           | `view.WebViewPart`            | the page view (allowMultiple = true, so two pages can be shown)                  |
| `org.eclipse.ui.preferencePages`                 | `prefs.WebViewPreferencePage` | the settings form (port, origins, `EdgeDataDir`) under a `WebView` category      |
| `org.eclipse.ui.startup`                         | `http.BridgeStartup`          | starts the endpoint for the project, claims the port, publishes the record — so a page in a browser works with the view closed |
| `org.eclipse.ui.commands` + `handlers` + `menus` | `actions.OpenInWebViewAction` | "Open in WebView" on an `.html` file in the Project Explorer and the editor menu |
| `org.eclipse.ui.bindings`                        | the same handler              | a key binding, the analogue of the JetBrains `Ctrl+Alt+Shift+W`                  |
| `enabledWhen` on the command                     | —                             | visibility restricted to `.html`/`.htm` resources, so the action does not appear where it cannot work |

## 7. Phases

Each phase ends in a gate that can fail. No phase changes the frozen contract, and no phase adds a document
claim before the artifact exists.

### Phase 0 — measure Eclipse before writing a host (½–1 day, no production code)

Run § 5.2 A–F with `webview/eclipse/phase0/probe.mjs` and replace the stub in
[`eclipse/PHASE0-ECLIPSE-FINDINGS.md`](eclipse/PHASE0-ECLIPSE-FINDINGS.md) with the measured results. Cross-link
this plan from `PLAN-webview-suite.md` § 4 and § 7 in the same commit, since the folder now exists.

- **Gate:** the findings file names the Eclipse/SWT/Edge versions and the observed result of every experiment,
  including the failures. A failure changes a decision in § 3 — E2 if the engine is not Edge, E3 if
  `BrowserFunction` cannot be re-injected per document as R8 says, E12 if a `file://` page turns out to reach
  loopback fine, E15 only if the embedded classes cannot be seen from the bundle — and this plan is amended in
  the same commit rather than left to drift. This mirrors the Zed Phase 0, which earned its keep by finding that
  a settings-only language server is ignored.

**Gate passed 2026-09-27** — the findings file carries the versions and every experiment's result, including
the not-run A2 and the unmeasured `SWT.NONE` half of A, and the suite cross-links are in this commit. The
measurement changed E12 (a `file://` page does reach loopback against `Access-Control-Allow-Origin: *` — the
allow-list/token rule stands) and the reference's R11 (`EdgeVersion` is a system property SWT sets itself, an
output, not an input) and R9 (`evaluate`'s scripts must `return` their value).

### Phase 1a — promote `DocumentEdits` into `webview-core` (½ day)

`webview-jetbrains`' `DocumentEdits` (34 lines, no IDE types) moves into `webview-core`; its five tests move to
`webview-core/src/test`; the plugin delegates.

- **Gate:** `bun scripts/mvn-jdk25.js -o -pl webview/core/webview-core -am test` green with the five cases in
  their new home, and the JetBrains Gradle suite still green (30 tests) after the delegation — those tests are
  the proof the move changed no behaviour.

**Gate passed 2026-09-27** — core green with the five `DocumentEdits` cases in their new home (173 tests
total); the JetBrains `gradlew.bat test` suite is 25/25 after the delegation — the plan's "30 tests" is the
25 remaining there plus the 5 promoted to core.

### Phase 1 — the module, the bundle, the view, the injected bridge (3–4 days)

The `webview/eclipse/webview-eclipse` Maven module (E4, `release 21`), the **committed** `MANIFEST.MF` and
`plugin.xml`, the unpack step that puts core and Gson in the bundle (E15), `WebViewPlugin` with the activator and
shutdown (E17, § 9), the `ViewPart` with a `SWT.EDGE` browser and `BrowserFactory`'s engine report (E2),
`UnsupportedEnginePanel`, the splash, `EclipseBridge` (a `BrowserFunction` + core's `InjectedBridge`, disposed
and re-registered per document, UI thread, one JSON string argument), `WorkspaceFiles` + `EclipseNavigator` +
`EclipseEditorHost` with capabilities `["open","select"]`, the `actions/` entry point and its binding, and the
dropins install steps in [`eclipse/README.md`](eclipse/README.md). Pages are loaded from a path or URL
(`file://`) here, because the server arrives in Phase 2 (E12).

Registering the module is part of the phase: the root POM's `<modules>` entry **and** the comment above it (which
currently explains that the editor hosts are absent because they are not Maven builds), the root README's module
count, `MigrationCompletenessTest.MODULES`, and the already-stale module map (§ 13).

- **The manifest, hand-written and committed (E15).** `maven-jar-plugin` with the explicit manifest rather than a
  generated one, so the bundle's identity is reviewable in a diff: `Bundle-ManifestVersion: 2`,
  `Bundle-SymbolicName: hr.hrg.eclipse.webview;singleton:=true`, `Bundle-Version: 1.0.0` (independent of the
  Maven version, and the number p2 shows in `dropins`), `Bundle-Name`, `Bundle-Vendor`,
  `Bundle-RequiredExecutionEnvironment: JavaSE-21`, `Bundle-ClassPath: .`, `Bundle-Activator:
  hr.hrg.eclipse.webview.WebViewPlugin`, no `Export-Package` (the embedded packages stay private), and
  `Require-Bundle` for the platform bundles the code touches. bnd is the alternative and would compute
  `Import-Package` for us; it is not used because nothing here needs computed imports and a generated manifest is
  one more thing that cannot be reviewed.
- **Gate (unit):** `webview-core` is installed into the local repository first (`bun scripts/mvn-jdk25.js -o
  -pl webview/core/webview-core -am install` — a changed library module is reinstalled locally), and the
  module's suite is green under `bun scripts/mvn-jdk25.js -o -pl webview/eclipse/webview-eclipse clean test`,
  and it includes: the injected
  script carries the core marker and sends exactly one string argument (so R8's narrow argument rule cannot be
  broken by accident); a path containing quotes and the text `"line":` parses through core's `BridgeMessage`; the
  capability set equals the declared constants; **`Require-Bundle` is complete** — a test scans the module's
  `import org.eclipse.*` statements and asserts every package is mapped to a bundle that appears in the manifest,
  which turns the JCEF-class runtime failure into a build failure; **the built jar carries the committed manifest
  headers, contains `hr/hrg/webview/core/HostHealth.class` and Gson's `Gson.class`, and contains no
  `module-info.class`** (E15's two failure modes); and `plugin.xml` names exactly the classes that exist on the
  classpath — the Eclipse analogue of the JetBrains `PluginDescriptorTest`. **Corrected 2026-09-27
   (observed):** the original gate's `-am` cannot be green — `unpack-dependencies` refuses to unpack a reactor
   artifact that has not been packaged yet (MDEP-98): under `-am`, `webview-core` resolves to the reactor's
   `target/classes` directory, and the `process-classes` unpack fails. The gate therefore resolves
   `webview-core` from the local repository (installed first, as the repository rule for a changed library
   module requires), and it carries `clean`, because without it the incremental compile silently compiles only
   the stale subset. A full-reactor `install` still works: there, core is packaged before eclipse's
   `process-classes`, so the same POM passes.
- **Gate (repository):** the recorded gate still passes — `bun scripts/mvn-jdk25.js`. What that does and does
  not prove: the gate's module list is `scripts/lib/gate.js`'s `GATE_MODULES` (the hipster-entity set plus
  `jcodebuddy-core`), so it does **not** build the Eclipse module. What it does is run the repo-wide sweeps that
  now see it — `MigrationCompletenessTest` (sources parsed, positions and types checked),
  `ProjectAutomationIsolationTest` (every POM, including the new one) and `GeneratorGuardTest`.
- **Gate (observed):** the maintainer installs the bundle via `dropins/` (E5 — recording **which layout p2
  accepted**), opens a generated page in the view, clicks a field, and **the caret lands on the named line** —
  after which the support matrix may say "observed" for Eclipse navigation, with the Eclipse, SWT and Edge
  versions and the date. The run reports `Browser.getBrowserType()` and `EdgeVersion` (so the README's engine
  claim is measured too) and confirms that quitting Eclipse leaves no listening socket behind (E17).

### Phase 2 — the HTTP bridge, the served page, the port claim, parity (2–3 days)

`EclipseHttpBridge`: `GET /open`, `GET /health`, `GET /file/`+`/page/` through core's `PageServer` (E12),
`OPTIONS` with `Access-Control-Allow-Headers: X-WebView-Token`, core's `AllowedOrigins` and `RateLimiter`,
loopback only, the token generated into `.jcodebuddy/webview/token` and its path published (E16), the port
claimed through `HostPortClaim` and published by `HostDescriptor` — which is **left in place** when the host
stops, because it is the checkout's port record (DEC-033), and removed only when it is ours (the `pid` check).
The socket work runs in a `Job`, never on the UI thread (R19); `/api/v1/*` is a `switch` with a direct call per
case (E9); the view switches its page loading to `/page/<path>?token=…` so a same-origin page can call the write
routes (E12). This phase also adds the host to the lists that would otherwise leave it unchecked:
`HostHealth.PLUGIN_ECLIPSE`/`IDE_ECLIPSE` with the `defaultIdeFor` case, and `HostHealthParityTest.hosts()`
(§ 13) — that test reads each host's source and asserts the authorization check precedes the action, so the
bridge's *shape*, not only its behaviour, is what it checks.

- **Gate (a):** two live hosts on one project — the Eclipse host and `webviewd` — produce the rule in
  `doc/webview-host-api.md` § 4a: one of them opens no endpoint and says who is serving. Run
  `bun webview/tools/check-port-claim.js` and add the Eclipse case where it can drive a host; the IDE half is
  observed by hand, as the JetBrains and VS Code halves are.
- **Gate (b):** the Eclipse-side test asserts the conformance vectors (`bridge-decisions.json`) — the origin
  allow-list, the CORS decision, the rate-limit windows and the `healthKeys` list — so the fifth host cannot
  drift from the other four.
- **Gate (c):** `HostHealthParityTest` passes **with the Eclipse host in its `hosts()` list**, and
  `reportsWhatItChecked` reflects five entries rather than three.
- **Gate (d):** `bun webview/tools/observe-edit-host.js --port 18883 --token …` against the running IDE reports
  the Eclipse `/health` document with `plugin`, `ide` and `project` filled in, and a 403 for a wrong token.
- **Gate (e):** a page served by `/page/` from a plain browser beside Eclipse navigates the IDE — the same claim
  the other hosts make, and the one that proves E12's same-origin choice did not cost the browser fallback.
- **Gate (f):** the endpoint is **off** in a fresh workspace with no preference and no committed default (E17),
  and turns on when `.jcodebuddy/conf/webview.json` names a port — the two halves of E17, measured.
- **Open at the end:** Q3, which Phase 0 D and gate (e) together answer.

### Phase 3 — the write verbs (2–3 days)

`EclipseDocumentEditor` (the seam) and `DocumentBufferEditor` (the Eclipse implementation) apply core's
`DocumentEdits` inside one `IRewriteTarget` compound change on the UI thread, never saving, and refuse with
`409 no-buffer-edit` when the platform holds no document for the path (E8); `WriteSurface` decides the routing
and the statuses; disk writes go to core's `EditService` with `CheckpointStore.persistent(...)` under
`.jcodebuddy/webview/checkpoints/` (if Q4 says yes); `/api/v1/applyEdit|diff|undo|redo` require the token, not
merely an allowed origin.

- **Gate:** the four sub-gates the suite plan's Phase 3 already names, for Eclipse: a stale digest is refused
  with `409` and no byte changes; an applied edit is undone byte-for-byte by `/undo` **after a restart** (the
  checkpoint is persistent — the part the JetBrains host does not claim); a path outside the project is refused
  here too; and the **observed** one — the replacement appears in the Eclipse editor as an unsaved change and
  **one `Ctrl+Z` restores the original** (`observe-edit-host.js --checkRefusals --watchSeconds 20`, recorded in
  `ide-observation-checklist.md` § 2a with the Eclipse version). The Eclipse analogue of the JetBrains autosave
  caveat must be checked in the same run: Eclipse saves on build and on explicit save actions, and R17 records
  that the exact autosave preference in 4.41 is unconfirmed — so a *later* disk change is attributed to the
  editor, never to the host.

### Phase 4 — `reveal`, the decision record, and the document sweep (1 day)

- `reveal` only if it works through the supported route (§ 6) **and is observed**; otherwise it stays absent from
  the capability list and the README says why — the JetBrains precedent, not a gap.
- **The decision record comes first here, per `doc/AGENTS.md`.** A fifth host changes a *runtime contract* (a new
  `plugin` identity, a new port, a new row in the port-claim table) and possibly a *module boundary* (E4), so this
  phase amends [DEC-033](../doc-hipster-entity/architecture/decisions/DEC-033.md) — whose text says "Four hosts
  can serve one project over loopback" and whose context table lists the four — and its row and Notes in the
  [decisions index](../doc-hipster-entity/architecture/decisions/README.md), which enumerates "Every host
  (webviewd, JetBrains, VS Code, jwa-sidecar)". The module-boundary half is recorded there too, or as a new DEC
  if the reviewer judges it separate.
- One commit updates every document that enumerates hosts, all of them incomplete today by construction:

| File                                                                             | What is stale                                                                                     |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| [`webview/README.md`](README.md)                                                 | the title "four hosts", the host table, the folder map, "Zed is a fifth *client*", the "Which file to read" table, the "implemented and observed" table, and the per-host **Verifying** block |
| [`webview/doc/webview-host-api.md`](doc/webview-host-api.md)                     | § 1's "Implemented in" column, § 2's `plugin` and `ide` examples, and **§ 8's support matrix**, which needs an Eclipse column (and its prose, which names hosts) |
| [`webview/doc/ide-observation-checklist.md`](doc/ide-observation-checklist.md)   | a new § 1a for Eclipse, a dated § 2a row, § 3's symptom table, and § 4's report template, which hard-codes the two hosts |
| [`webview/core/README.md`](core/README.md)                                       | the **Consumers** table (a reactor module, not `mavenLocal`), and the "three times" history       |
| [`webview/conformance/README.md`](conformance/README.md)                         | "The three hosts", and the **"Who reads it"** table, which must name the Eclipse test as a reader |
| [`webview/kit/doc/contract.md`](kit/doc/contract.md) § 3.1                       | the port table gains an Eclipse row with 18883 and its setting name — a completeness edit to a frozen document, not a contract change |
| [`webview/kit/doc/host-in-this-project.md`](kit/doc/host-in-this-project.md) § 1 | the "which host is the right answer" table                                                        |
| [`webview/webview-jetbrains/README.md`](webview-jetbrains/README.md), [`webview-vscode/README.md`](webview-vscode/README.md), [`jwa-sidecar/README.md`](jwa-sidecar/README.md) | "the other three hosts" and the sibling-host prose |
| [`doc/architecture/module-map.md`](../doc/architecture/module-map.md)            | **already stale**: its tree names a root-level `jwa-sidecar` and omits `webview-core` and `webviewd`; this phase brings it up to date for the whole product — the tree, the dependency table, and the JUnit Strategy table (the new module is JUnit 5) |
| [`PLAN-webview-suite.md`](PLAN-webview-suite.md)                                 | § 2's host table, § 4's layout tree, § 8's criterion 1, § 9's risks, § 11's commands — plus the dated implementation record for this work |
| root `README.md`, root `pom.xml`                                                 | the module count in the README's gate story, and the `<modules>` comment written in Phase 1       |
| `scripts/entity-html/README.md`                                                  | `--bridge-port` documents the JetBrains default 18881 as *the* bridge port                        |
| `zed/`, [`PHASE0-ZED-FINDINGS.md`](PHASE0-ZED-FINDINGS.md)                       | the "one product, four hosts" framing and plan cross-references                                   |

- **Gate:** `node webview/check-links.mjs` and `node scripts/check-repo-links.mjs` pass; every new claim is a
  capability the honesty run covers, or a dated, version-named observation; and DEC-033 plus the index no longer
  say "four hosts" anywhere.

### Phase 5 — distribution as an installable update site (optional, 1–2 days)

A Tycho build (R22) of a feature + p2 update site (`category.xml`) so the host installs through
*Help → Install New Software* rather than by copying a jar.

- **Why it is last and optional:** it introduces p2 resolution and a second build system for a packaging benefit
  only, and it must never become the thing that makes the host buildable (E4/E5). If taken, it stays a separate
  Maven profile so the ordinary offline test loop keeps working.
- **Gate:** a clean Eclipse installs the host from the generated site with `dropins/` empty, and the Phase 1
  navigation observation repeats against that install.

## 8. Acceptance criteria

1. `webview/eclipse/webview-eclipse` builds with the repository's launcher, and its tests need no workbench and
   no Eclipse installation.
2. A page in the Eclipse view: a click lands the caret on the named line and column — **observed**, dated, with
   the Eclipse/SWT/Edge versions. The engine in use is reported, not assumed.
3. A page in an ordinary browser reaches the same IDE through `GET /open` on loopback, with the same
   authorization rules as every other host, and a wrong token is `403`.
4. The Eclipse host's `/health` is the shared document: same keys, in the shared order, with `plugin`, `ide` and
   `project` naming this host, this IDE and the served project.
5. One bridge per project: with a second host already serving that project, the Eclipse host opens no endpoint
   and says who is serving; the pin (`sticky`) is honoured and never moved; the endpoint is **off** unless the
   configuration names a port (E17).
6. An edit from a page lands in the editor's buffer **unsaved**, is one `Ctrl+Z` to undo, and the file on disk is
   untouched when the host answers; a path with no platform document is refused `409 no-buffer-edit`.
7. No second implementation of the security model exists: no allow-list, no CORS decision, no rate limiter and no
   path jail in the plugin — it calls core's, and a test asserts the shared vectors.
8. The generated token lives in the served project's `.jcodebuddy/webview/`, is published as a path and never as
   a value inside `host.json`, and is not written into `.jcodebuddy/conf/`; an explicit override, if a user sets
   one, is the platform's own preference mechanism and the README says so (E16).
9. `DocumentEdits` exists once, in `webview-core`, and both IDE hosts delegate to it.
10. The Eclipse design is the SWT one, not the JetBrains one: no JCEF, no `SWT.CHROMIUM` (R2, R5), and the
    `BrowserFunction` bridge respects the UI-thread, narrow-argument and dispose-per-document rules (R7, R8).
11. The built bundle is self-contained and honest about itself: it carries core's classes and Gson, exports
    neither, contains no `module-info.class`, and its committed manifest's `Require-Bundle` is complete — proven
    by a build-time test, not by a runtime `NoClassDefFoundError` (E15).
12. Quitting Eclipse leaves no listening socket, and the port record stays behind as the checkout's record
    (E17, DEC-033).
13. The host is *covered*, not merely present: it appears in `HostHealthParityTest.hosts()`,
    `MigrationCompletenessTest.MODULES`, the root POM's `<modules>`, and core's identity constants (§ 13).
14. Every capability claim in the Eclipse README is covered by a test or a dated, version-named observation.

## 9. Risks

| Risk                                                                                   | Impact                                                                                    | Mitigation                                                                          |
| -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| **OSGi cannot see `webview-core`** (E15)                                               | the bundle compiles and dies at runtime with `NoClassDefFoundError` for every core class — the shape of the JetBrains missing-`<depends>` bug | unpack core and Gson into the bundle, mark the embedded packages private, and make it a **build-time assertion**: the jar must contain `HostHealth.class` and `Gson.class`, and no `module-info.class` |
| **A missing `Require-Bundle` entry**                                                   | a `NoClassDefFoundError` at the first use of an API, visible only in a running IDE        | the Phase 1 import→bundle mapping test: every `import org.eclipse.*` in the module must map to a bundle in the manifest. This is the one mitigation that costs nothing and catches the class of bug that cost the JetBrains host a session |
| **A missing WebView2 runtime, documented two contradictory ways** (R4)                 | either the pages break under IE, or the view cannot be created at all                     | E2: ask for `SWT.EDGE`, read `getBrowserType()` and `EdgeVersion`, show `UnsupportedEnginePanel` naming the runtime and the fix. Phase 0 A replaces the guess with a measurement |
| **The SWT readme's "IE is the default on Windows" is stale** (R3)                      | a plan that trusts it configures the wrong thing, or supports a version range it need not | the FAQ and `BrowserFactory` agree Edge is the default from 4.35; `SWT.EDGE` is passed explicitly for 4.19+, and the README records versions rather than a claim |
| **Someone ports the JetBrains JCEF design** (R5)                                       | days lost to a route that does not exist                                                  | R5 states it, E1/E2 encode the SWT answer, and [`eclipse/README.md`](eclipse/README.md) repeats it for the next reader |
| `ProgressListener.completed` matches `DOMContentLoaded` and fires only for the top-level document (R9) | the bridge is installed early, or a fragment change never re-injects      | inject from `completed`, keep `window.openFile` idempotent, re-register the `BrowserFunction` per document, and never evaluate from `LocationListener.changing`/`OpenWindowListener.open` |
| **A `BrowserFunction` that blocks deadlocks the WebView2 handler** (R10)               | a hung IDE and `ERROR_FAILED_EVALUATE [WebView2: deadlock detected]`                      | the function returns promptly, does no I/O, never waits on `evaluate()`; slow work goes to a `Job` (R19) |
| **WebView2 shares one user-data directory per application** (R11)                      | two Eclipse instances on one workspace contend; a second sees the first's cache           | expose `EdgeDataDir` as a preference/VM argument, document the default location, and name it in the install steps. Its *scope* is Q8 |
| **An unsupported `BrowserFunction` argument type fails silently** (R8)                 | a click that does nothing, with nothing in the log                                        | the injected script sends exactly one JSON string and the Phase 1 test asserts that shape; the host logs every received message at diagnostic level |
| **The endpoint outlives the IDE** (E17)                                                | a listening socket for a project nobody is editing, and a stale `pid` in the record       | `WebViewPlugin.stop()` stops every bridge; the descriptor is left deliberately (it is the checkout's port record, DEC-033) and liveness is decided by probing the port, never by the file. Phase 1 observes that quitting leaves nothing listening |
| **Eclipse has no "current project"** (R15)                                             | a host serving the workspace root collides with `webviewd` and reports a `project` that is not a project | E7: resolve the owning `IProject` per request, publish its location, and measure the rule with `check-port-claim.js` |
| **`getFileForLocation` ignores linked resources, and the `IPath`-based finders are deprecated in 4.41** (R14) | a page link into a linked folder answers 404, or the code compiles against deprecated API | use the URI lookup for linked resources and keep the jail in core; `WorkspaceFiles` is the single place this mapping lives, so a change is one method and its test |
| **A fifth host slips through the two hard-coded lists** (§ 13)                         | `HostHealthParityTest` never checks it (its own comment warns of this) and `MigrationCompletenessTest` **silently** stops sweeping its sources, because it skips a module whose directory is missing — the failure that hid `jwa-sidecar`'s move | Phase 1 and Phase 2 name both lists in their gates; § 13 is the checklist |
| **The new POM trips a repository-wide POM rule**                                       | a gate failure in an unrelated module, with a message about XML                           | `ProjectAutomationIsolationTest` walks every POM: no `<distributionManagement>` or `altDeploymentRepository`, no dependency on a `*project-automation*` artifact, and **no `--` inside any XML comment** (XML 1.0 § 2.5) — a comment like `--host` fails the build even though it is prose |
| **SWT cannot be created in a test process — there is no supported headless SWT** (R22) | a suite that passes locally and hangs or fails in CI                                      | E10: IDE types behind interfaces with fakes, no SWT in unit tests, and Phase 0 E measures even `new Display()` |
| **Tycho becomes necessary for Phase 5** (R22)                                          | the "one build system" claim in E4 quietly fails                                          | Phase 5 is optional and last, a separate profile; E4 records the flip condition (if Central's bundles cannot compile the code, Tycho moves first) |
| **A helper script appears in the wrong language** (E14)                                | the check runs on one OS only, and no gate catches it, because `GateContractTest` scans `scripts/` only | E14 states the rule for this folder; the probe and any later verifier are Bun `.js` |
| **This session cannot run the builds at all**                                          | gates cannot be run where the work is written                                             | **resolved 2026-09-27:** after the file policy was lifted, every headless gate ran from the agent session on this machine (the implementation record lists them); what still cannot run here are the *observed* gates — no Eclipse 4.41 and no human — and those stay **outstanding** until the maintainer reports them |
| **The machine has no Eclipse IDE and no p2 cache**                                     | the *observed* gates are blocked, not failed                                              | Phase 0's probe needs only Central bundles plus a desktop session; the IDE-side observations are explicitly the maintainer's, like the JetBrains/VS Code/Zed ones |

**The machine, recorded so the next reader does not re-discover it.** As of 2026-09-27: JDK 25 and Maven/mvnd are
present; `~/.m2/repository/org/eclipse/platform` holds only `org.eclipse.osgi`, so the SWT/UI bundles are a new
download; **no Eclipse IDE is installed** (only Zed; the JDKs present are 8, 17, 21, 24, 25 and GraalVM 25); and
the agent sandbox refuses to launch `mvn`, `bun`, `node` and `git status` (`Access is denied` — they write
outside the workspace), so no command in § 11 was executed while this plan was written. **Correction, later the
same day:** the file policy was lifted to full access while Phase 0 ran, and from Phase 1a onward every headless
command in § 11 was executed from the agent session on this machine — the two Eclipse-platform downloads
included. What the machine still does not have is the 2026-09 / 4.41 IDE and a human at it, so the *observed*
gates remain the maintainer's.

## 10. Open questions, with their current state

Each question names the phase that must settle it. `open` means nothing decides it yet; `answered` means a
decision above does, and the row says which half still needs a measurement.

| #   | Question | State                                                                                        |
| --- | -------- | -------------------------------------------------------------------------------------------- |
| Q1  | **Which Eclipse is the target — the 2026-09 train or the installed IDE?** This plan pins the 2026-09 train and Java 21 (R1, R21). Supporting 2025-03 or 2024-12 changes a build detail (the engine stays Edge only because `SWT.EDGE` is explicit) but is a decision about the supported range | **settled by construction, 2026-09-27** — Phases 1–3 built against the pinned 2026-09 train and Java 21; the supported *range* below it is untested and remains a release decision |
| Q2  | **Is a `dropins` install acceptable for the first release** (E5, R21), or is a p2 update site required from day one? If the latter, Phase 5 moves ahead of Phase 4 and the product pays for p2 (and Tycho) immediately | **open** — settle before Phase 5, and it decides whether Phase 5 is "optional"; the dropins install itself is also an outstanding *observation* (checklist § 1a, step 1: record which layout p2 accepted) |
| Q3  | **Does the view serve its pages, or load them from disk?** E12 says serve, same-origin, and `serveFile` is declared | **answered by E12 and built** — Phase 2 serves `/page/` with the injected bridge and declares `serveFile`; the measurement left is Phase 2's gate (e), a browser beside Eclipse |
| Q4  | **Does the Eclipse plugin own bytes on disk** (core's `EditService`, checkpoints, `/undo`), or refuse disk writes like `webview-vscode` and point a page at `webviewd`? The hazard is specific: writing a file that is open in an editor fights the buffer (R17), so "buffer only" is defensible rather than a shortfall | **settled yes, 2026-09-27 (Phase 3)** — the host owns both halves: the buffer half through the `EclipseDocumentEditor` seam (a refusal there falls through to disk), the disk half through core's `EditService` with a **persistent** `CheckpointStore`, so `/undo` survives a restart (headless-tested). R17 is met by the seam's rules: a buffer edit never saves, and one compound change means one `Ctrl+Z` |
| Q5  | **How is the host scoped to a project?** E7 binds per `IProject`. The alternative — one workspace-level bridge serving the workspace directory — is simpler but makes `project` a directory that is not a project (R15) | **answered by E7**; confirm in Phase 2's gate (a) |
| Q6  | **Does anything else in the repository need an Eclipse host to exist** (a generator that emits a page for Eclipse, an `intellij-jwa`-style client)? Nothing here references Eclipse beyond ECJ, LSP4J and JGit; if a consumer is coming, its requirements belong in Phase 3's write contract rather than bolted on after | **open, no known consumer** |
| Q7  | **Reactor module, or an out-of-reactor build like the other two editor hosts?** E4 chooses the reactor, because the root POM's reason for excluding the other two (they are not Maven builds) does not apply and the reactor already carries third-party artifacts. The alternative keeps the literal "editor hosts are not modules" reading and pays with a `mavenLocal` prerequisite | **answered by E4**; the alternative is recorded, and § 13 keeps the wiring honest either way |
| Q8  | **Should `EdgeDataDir` be a per-workspace or a per-project preference?** WebView2 shares one user-data directory per *application* (R11), so a per-project setting is a lie unless the host also passes a distinct directory per project | **still open — it did not block Phase 2**: the preference page shipped without `EdgeDataDir` (enabled, splash, port, token override), so the WebView2 default user-data directory applies and R11's two-instance contention stands; E16's preference list overstates what was built |

## 11. Verification commands

The IDE-side steps need a desktop and a human. The headless steps below all ran green from the agent session on
the implementing machine on 2026-09-27 — § 9's note about a sandbox refusing `mvn`/`bun`/`node` describes the
*planning* session, before the file policy was lifted. Java steps pin JDK 25 through the repository's launcher,
which is Bun JavaScript per AGENTS § 2. The commands are shell-neutral — no PowerShell-only cmdlets — because the
same block is used on whatever machine the maintainer has.

```console
# Phase 0 — the Eclipse facts, no IDE needed (opens and closes short-lived windows)
bun webview/eclipse/phase0/probe.mjs

# the module: its tests, then the assembled bundle. NO -am — under a reactor build, unpack-dependencies
# refuses the not-yet-packaged core (MDEP-98, the Phase 1 gate's correction); install core first when it changed:
bun scripts/mvn-jdk25.js -o -pl webview/core/webview-core -am install
bun scripts/mvn-jdk25.js -o -pl webview/eclipse/webview-eclipse clean test
bun scripts/mvn-jdk25.js -o -pl webview/eclipse/webview-eclipse clean package
# a human glance at what the jar claims and carries; the same assertions run as tests in the suite above
jar tf webview/eclipse/webview-eclipse/target/webview-eclipse-1.0-SNAPSHOT.jar

# the repository's recorded gate: it does NOT build the module, but it runs the sweeps that now see it
bun scripts/mvn-jdk25.js
bun scripts/mvn-jdk25.js -o -pl hipster-entity-tooling -am test

# the promoted DocumentEdits, and the plugin that now delegates to it
bun scripts/mvn-jdk25.js -o -pl webview/core/webview-core -am test
cd webview/webview-jetbrains && .\gradlew.bat test

# install into an Eclipse: copy the built bundle into dropins/ and restart, recording which layout p2 took
#   <eclipse>/dropins/hr.hrg.eclipse.webview_1.0.0.jar
#   or <eclipse>/dropins/webview/plugins/hr.hrg.eclipse.webview_1.0.0.jar
#   if a replaced jar is not picked up, start once with -clean

# one bridge per project, measured against two live hosts
bun webview/tools/check-port-claim.js
# the capability document, and the buffer-edit observation (Eclipse on 18883)
bun webview/tools/check-capabilities.js
bun run webview/tools/observe-edit-host.js --port 18883 --token obs-token --file D:\tmp\obs\Sample.java --find "int x = 1;" --replace "int x = 42;" --checkRefusals --watchSeconds 20

# the documents, after Phase 4
node webview/check-links.mjs
node scripts/check-repo-links.mjs
```

## 12. Suggested order of work, in one line

Phase 0 → Phase 1a → Phase 1 → Phase 2 → Phase 3 → Phase 4 → *(optional)* Phase 5.

Phase 0 is first because one Eclipse source contradicts another about the engine the whole host depends on, and
because `BrowserFunction`'s threading, re-injection and return semantics shape the bridge. Phase 1a is second
because it is the only change this work needs in code that already ships, and doing it first stops the Eclipse
plugin inventing a second answer to "where does line 2 column 1 land". Phase 1 is the largest single phase
because it carries the bundle's identity — the manifest, the embedded jars, the extension points, the activator —
which is the part that fails *silently* when it is wrong. Phases 2 and 3 deliver the browser fallback and the
write path, and Phase 4 is the decision record and the document sweep that turn claims into rows, in one commit
with the host that makes them true.

## 13. The lists and the comment a fifth host must join, or it is silently uncovered

Code and build only — the documentation sweep is § 7 Phase 4's table. The first row fails loudly if forgotten
(`HostHealthParityTest` says so in its own comment); the second fails **silently**, which is worse, and is exactly
how `jwa-sidecar`'s sources stopped being swept when it moved.

| Where                                                                                   | What it is                                                                                      | If the host is not added                                                     |
| --------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `webview/core/webview-core/src/test/java/hr/hrg/webview/core/HostHealthParityTest.java` | `hosts()` — the literal list of host sources (five entries since 2026-09-27)                    | the host's `/health` document and its authorization ordering are never checked; the test's own comment warns that a fourth host "added to the product without being added here would silently go unchecked" |
| `hipster-entity/hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/MigrationCompletenessTest.java` | `MODULES` (+ the `>250 files` floor), which **skips a module whose `src/main/java` is missing** | the new module's Java is never parsed, never position-checked and never compared against the LST — silently |
| `webview/core/webview-core/src/main/java/hr/hrg/webview/core/HostHealth.java`           | `PLUGIN_*`/`IDE_*` constants and `defaultIdeFor`                                                | the host's identity becomes a string literal in the plugin (E13), and the parity test fails a host that writes its identity or a capability key inline |
| root `pom.xml` `<modules>`, and the comment above the webview entries                   | membership and the explanation of who is absent and why                                         | the module is not built by the reactor, and the comment (which says the editor hosts are not Maven modules *because they are not Maven builds*) becomes false |
| root `README.md`                                                                        | the gate story's module count                                                                   | a stale number in the sentence explaining the recorded gate                  |
| `doc/architecture/module-map.md`                                                        | the module tree, the dependency table and the JUnit Strategy table — **already stale about `webview-core`, `webviewd` and the moved `jwa-sidecar`** | the repository's own map of its modules contradicts the reactor |
| every host enumeration in the documents — `webview/README.md`, `doc/webview-host-api.md` § 8, `doc/ide-observation-checklist.md`, `kit/doc/contract.md` § 3.1, `core/README.md`, `conformance/README.md`, and DEC-033 with its index row | the product's own description of itself | the documentation under-reports what ships; the full list is § 7 **Phase 4** |

**All of it joined, 2026-09-27.** `HostHealthParityTest.hosts()` lists five sources; `MigrationCompletenessTest`'s
`MODULES` carries `webview/eclipse/webview-eclipse`; `HostHealth` owns the `PLUGIN_ECLIPSE`/`IDE_ECLIPSE` constants
and the `defaultIdeFor` case; the root POM lists the module with the corrected comment; the root README's gate story
says 29 modules; `module-map.md`'s tree, dependency table and JUnit table are current for the whole product; and
the document enumerations were swept in Phase 4's commit.
