# `webview/eclipse` — the Eclipse IDE host

**Status: Phase 1 implemented, 2026-09-27.** The plugin module, `webview-eclipse/`, is in the reactor and is
built and unit-tested by the repository's gate; the dropins install and the observed gates against a 2026-09
train of Eclipse have not been observed yet — they need a real IDE and a human, per
[`../doc/ide-observation-checklist.md`](../doc/ide-observation-checklist.md). Phase 0 was measured on
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

## What this host will be, in one paragraph

An Eclipse IDE plugin that renders a generated page in an SWT `Browser`, injects the frozen
`window.openFile(path, line, column)` contract through a `BrowserFunction`, answers `GET /open`, `GET /health`
and `/file/`+`/page/` on loopback (port 18883 requested, claimed through `webview-core`'s `HostPortClaim`, and
**off until a configuration names a port**), serves its own pages from `/page/` so the page and the API are
same-origin, and applies a page's edit to the editor's own buffer inside one `IRewriteTarget` compound change —
so one `Ctrl+Z` takes it back and the file on disk is untouched. It reuses `webview-core` for every rule about
authorization, CORS, rate limiting, the path jail, the port claim and the write conversation; the plugin owns
only what is Eclipse-specific.

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
