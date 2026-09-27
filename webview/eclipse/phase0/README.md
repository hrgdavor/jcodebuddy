# `webview/eclipse/phase0` — the apparatus for the Eclipse facts

Nothing here is production code and nothing in the build reads it. It exists so that the experiments in
[`../PHASE0-ECLIPSE-FINDINGS.md`](../PHASE0-ECLIPSE-FINDINGS.md) are one command rather than a morning of
guessing — the same role [`../../zed/phase0/`](../../zed/phase0/README.md) played for Zed. **The Phase 0 gate
passed on this machine on 2026-09-27**; the measured results are committed in
[`../PHASE0-ECLIPSE-FINDINGS.md`](../PHASE0-ECLIPSE-FINDINGS.md) and the raw output in
[`probe-run.txt`](probe-run.txt).

| File | What it is |
| --- | --- |
| `probe.mjs` | Bun script: resolves the pinned Eclipse bundles (SWT `3.135.0`, `org.eclipse.ui` `3.209.100`, `org.eclipse.ui.browser` `3.9.200` — see `../PLAN-eclipse-host.md` § 5.1.1) into `target/`, compiles `SwtProbe.java` with `javac` from `JAVA_HOME`, runs it with those jars on the classpath, and prints one line per experiment |
| `SwtProbe.java` | The probe itself: creates a `Display` without a workbench, creates a `Browser` with `SWT.EDGE`, reports `getBrowserType()` and the `org.eclipse.swt.browser.EdgeVersion` property SWT sets itself, registers a `BrowserFunction` that reports its own thread and argument (including a throw and a dispose/re-register cycle), loads a `data:` splash, a loopback page and a `file://` page, and — with a `java.net` server started by the script — prints the `Origin` each page sends |
| `probe-run.txt` | The raw output of the 2026-09-27 run, committed as the observation record (`probe-run.log` is kept in `target/`, which is ignored) |

## Why it is Bun and not a shell script

`AGENTS.md` § 2: anything an agent writes to run or check something is a `.js` file run with `bun`, never a
`.ps1`, `.bat`, `.cmd` or `.sh` — a check that only runs in one shell on one operating system is invisible wiring
for the workflow. The one exception in this repository is a wrapper Gradle itself generates, and nothing here
needs one.

## Running it

```console
bun webview/eclipse/phase0/probe.mjs
```

It needs a desktop session: it opens and closes short-lived windows, so it is a person-at-a-machine tool, not a
CI job. It writes only into `target/` (ignored) and reads the Eclipse jars from the local Maven repository, so
nothing it does has to be cleaned up afterwards. `SwtProbe` logs JDK 25's restricted-native-access warnings for
SWT's `System.loadLibrary` (JNA is embedded in the SWT win32 jar); they are informational, and
`--enable-native-access=ALL-UNAMED` silences them. The probe's own window, its `HttpServer` and its `Display` are
disposed at the end; nothing is left running.
