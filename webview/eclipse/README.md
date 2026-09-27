# `webview/eclipse` — the Eclipse IDE host

**Status: Phases 1–3 implemented, 2026-09-27.** The plugin module, `webview-eclipse/`, is in the reactor and is
built and unit-tested by the repository's gate — 58 headless tests: the bundle's content and manifest
completeness, the injected bridge's shape, the navigator, the editor host and its document-editor seam, and the
HTTP bridge (port claim, served pages, preferences, and the write verbs) plus the shared conformance vectors.
The dropins install and the observed gates against a 2026-09 train of Eclipse have not been observed yet — they
need a real IDE and a human, per
[`../doc/ide-observation-checklist.md`](../doc/ide-observation-checklist.md) (§ 1a and the § 2a row for
2026-09-27 record what is outstanding). Phase 0 was measured on
2026-09-27 — see [`PHASE0-ECLIPSE-FINDINGS.md`](PHASE0-ECLIPSE-FINDINGS.md).

The plan is [`../PLAN-eclipse-host.md`](../PLAN-eclipse-host.md). It is the file to read before touching anything
here; this README exists so the folder has an entry point, so every link in the plan resolves, and so the four
facts below are stated where someone about to build the plugin will meet them.

```
eclipse/
  README.md                     this file
  PLATFORM-REFERENCE.md         what the Eclipse sources say, cited — the plan's reference half (R1…R22)
  PHASE0-ECLIPSE-FINDINGS.md    Phase 0's measured answers (run 2026-09-27)
  phase0/                       the Phase 0 apparatus (see its README), no production code
  webview-eclipse/              the plugin module (Phase 1)
```

## What this host is, in one paragraph

An Eclipse IDE plugin that renders a generated page in an SWT `Browser`, injects the frozen
`window.openFile(path, line, column)` contract through a `BrowserFunction`, answers `GET /open`, `GET /health`,
`/file/`+`/page/` and the token-only `/api/v1/` write verbs on loopback (port 18883 requested by convention,
claimed through `webview-core`'s `HostPortClaim`, and **off until a configuration names a port**), serves its
own pages from `/page/` so the page and the API are same-origin, and applies a page's edit to the editor's own
buffer inside one `IRewriteTarget` compound change — so one `Ctrl+Z` takes it back and the file on disk is
untouched — with core's `EditService` as the disk half, journalled to **persistent** checkpoints so an undo
survives a restart of the workbench. It reuses `webview-core` for every rule about authorization, CORS, rate
limiting, the path jail, the port claim and the write conversation; the plugin owns only what is
Eclipse-specific.

## Turning it on

The bridge is **off** until a port is named (E17): **Window → Preferences → JCodeBuddy → WebView** holds the
port (`hr.hrg.eclipse.webview.port`, 18883 by convention) and an optional token override
(`hr.hrg.eclipse.webview.token`). A workbench with no port preference opens no socket and writes no descriptor
and no token. Without an override, the token is generated once into the served project's
`.jcodebuddy/webview/token` and reused across restarts; it is host state, never configuration. Changing the
port or the token restarts the running bridges without a workbench restart. The bridge serves the project of
the file the view shows — one bridge per project, first bind wins (DEC-033).

## What it declares, and what backs each claim

The `/health` capabilities are the attached workbench's — plus one the process itself serves, `serveFile`,
because the `/file/` and `/page/` routes exist whenever the bridge runs, editor or not (the plan's E12). Every
one is covered by a headless test or is an outstanding observation — nothing is declared on hope (the plan's
criterion 14):

| Capability | State | Backed by |
| --- | --- | --- |
| `open` — caret lands on the line and column | declared while a workbench page exists | routing, the path jail and the rate rule: `EclipseHttpBridgeTest`, `EclipseNavigatorTest`; the caret landing itself is an outstanding **observation** (checklist § 1a) |
| `select` — a span in an already-open editor | the same | the same split |
| `edit` — a buffer edit in one compound change, never a save | the same | the seam and its routing: `EclipseEditorHostTest`, `DocumentBufferEditorTest`, `EclipseWriteApiTest`; the unsaved change and the single `Ctrl+Z` are an outstanding **observation** (checklist § 2a) |
| `serveFile` — `/file/` and `/page/` with the bridge injected | declared always, headless included | the routes and the headers they answer with: `EclipseHttpBridgeTest` (served bytes, `X-WebView-Digest`, `ETag`, the token rule on pages) |
| `reveal` | **absent, not missing** | not observed through a supported route; the rule is declare-because-implemented-and-observed, and the JetBrains host makes the same call |
| `watch` | **absent, not missing** | no SSE route is planned; `/api/v1/events` answers 404 with that reason rather than holding a page open |

The disk half of the write contract (`target: "disk"`, and the shared surface's documented fallback when the
platform holds no buffer for a path) is core's `EditService` with the persistent checkpoint journal under
`.jcodebuddy/webview/checkpoints/`; `EclipseWriteApiTest` asserts the Phase 3 gate headlessly — a stale digest
changes no bytes, an outside path is refused, and `/undo` restores the bytes exactly **after the bridge closed
and a fresh one started**.

## The four facts to read before writing a line of it

1. **The JetBrains design does not transfer.** `webview-jetbrains` embeds JCEF, and there is no supported
   JCEF-in-Eclipse route: SWT has no `SWT.CHROMIUM` style, and the Eclipse Foundation's 2017 FEEP-funded CEF/SWT
   integration was never funded to completion. This host is an **SWT `Browser` with `SWT.EDGE`
   (WebView2/Chromium)** — the Windows default since SWT 4.35. Sources:
   [`PLATFORM-REFERENCE.md`](PLATFORM-REFERENCE.md) R2, R3, R5; the decision is the plan's E2.
2. **OSGi cannot see a plain jar.** `webview-core` is a Maven artifact, not a bundle, so the plugin **unpacks it
   and Gson into its own jar** at `process-classes`, with the jar goal at `process-test-classes` because the
   Phase 1 gate is `clean test`, which never reaches `package`, and the jar-content test needs the jar to exist by
    test time; it keeps `Bundle-ClassPath: .` — one file
   to install, the
   same shape `jwa-sidecar` takes. Not `maven-shade-plugin`: it rebuilds the jar, which would take the
   committed manifest with it unless every header were duplicated into a transformer — two sources of truth for
   the bundle's identity. The module's README records what is bundled and its licence (Gson, Apache-2.0).
3. **The token is host state, not configuration.** It is generated into the served project's
   `.jcodebuddy/webview/token` and published as a path, never as a value in `host.json` and never in
   `.jcodebuddy/conf/`. Preferences hold only configuration (port, allowed origins, `EdgeDataDir`).
4. **Java 21 is the floor, JDK 25 is the toolchain.** Eclipse 4.41 requires Java 21+, so the module compiles with
   `release 21` and declares `Bundle-RequiredExecutionEnvironment: JavaSE-21` even though the repository builds
   with JDK 25. A bundle demanding JavaSE-25 would not load in a stock Eclipse (R21).

## Building and installing it

The module is a Maven reactor module (the root POM's `<modules>`), so it builds with the repository's launcher.
The only local install step is `webview-core` itself, when it has changed — `unpack-dependencies` cannot unpack
a reactor artifact that has not been packaged yet (MDEP-98, observed 2026-09-27), so the module gate resolves
core from the local repository:

```console
bun scripts/mvn-jdk25.js -o -pl webview/core/webview-core -am install
bun scripts/mvn-jdk25.js -o -pl webview/eclipse/webview-eclipse clean test
bun scripts/mvn-jdk25.js -o -pl webview/eclipse/webview-eclipse package
```

Install it by copying the built bundle into `<eclipse>/dropins/`. Which layout p2 accepts in 4.41 is not
documented to the letter (R21); no install has been observed yet (2026-09-27), so Phase 1 records the one
that worked — either the bare jar or
`dropins/webview/plugins/hr.hrg.eclipse.webview_<version>.jar` — in the plan's Phase 1 record and here. The
bundle version is the manifest's `Bundle-Version` (`1.0.0`), independent of the Maven version; if a replaced jar
is not picked up after a restart, start Eclipse with `-clean` once, which is the platform's own remedy for a
stale bundle cache.

## Cross-references

- the plan: [`../PLAN-eclipse-host.md`](../PLAN-eclipse-host.md)
- what the Eclipse sources say, cited: [`PLATFORM-REFERENCE.md`](PLATFORM-REFERENCE.md)
- the product, and the other hosts: [`../README.md`](../README.md)
- what a host must implement: [`../doc/webview-host-api.md`](../doc/webview-host-api.md)
- how an IDE claim becomes an *observed* claim: [`../doc/ide-observation-checklist.md`](../doc/ide-observation-checklist.md)
