# IDE observation checklist — the buffer-edit claim

The three claims in this product that **no test in this repository can make for you**:

- **gate (d), JetBrains**: an edit applied through the plugin's write API appears in the IDE's own editor as an
  **unsaved** change, the file on disk is untouched, and one `Ctrl+Z` takes it back;
- **the same claim for VS Code**, through `vscode.workspace.applyEdit`;
- **the same claim for Eclipse** (§ 1a), through one `IRewriteTarget` compound change — plus the caret claim
  the Eclipse Phase 1 gate needs: a click on `data-open`/`data-line` lands the caret on the named line.

Everything up to the platform call is implemented and unit-tested (core's `WriteSurface`; the plugin's
`DocumentEdits` + `WriteCommandEditor`; VS Code's `BridgePolicy` decisions; the Eclipse module's
`DocumentBufferEditor` seam and HTTP write routes; 25 Gradle tests, 173 core tests and 58 Eclipse-module
tests). What remains is the one thing a test in this repository cannot observe: **the IDE**. All three are
recorded as *implemented and unit-tested*, never as *observed*, until someone does this and reports it —
JetBrains and VS Code have been observed (§ 2a); Eclipse has not.

For comparison, gate (e) — the same claim over LSP for Zed — was observed on 2026-09-25 and is recorded as such
in the observation table of [`README.md`](../README.md) § "What is implemented, and what has actually been
observed". This checklist exists to make the other two equally cheap.

**Nothing here writes to your IDE's real settings.** The JetBrains path runs a *sandbox* IDE under the plugin's
`build/idea-sandbox/`, and the VS Code path runs an Extension Development Host window. Neither touches the Zed
settings or the settings of your installed IDEs.

---

## 0. Prepare a file to edit (once, for both)

Keep it out of the repository, so nothing observed can end up in a commit:

```console
New-Item -ItemType Directory -Force D:\tmp\obs | Out-Null
Set-Content -Path D:\tmp\obs\Sample.java -Value @'
class Sample {
    int x = 1;
}
'@
```

The needle `int x = 1;` must be on one line; the script refuses one that spans lines.

**The tool is Bun JavaScript**, per `AGENTS.md` § 2: scripts and tests in this repository are `.js` files run with
Bun — never PowerShell or shell — so they work on every OS and nobody has to think about execution policy. The
only prerequisite is Bun itself (`bun --version`). There is deliberately no `.ps1` twin: two ways to run a check
is one way to run it wrong, and the earlier PowerShell version of this script was replaced rather than kept
alongside.

> **The script is self-tested.** Running it against `webviewd --host none` on 2026-09-26 produced the expected
> `/health`, a 200 proposal with a `unifiedDiff`, a 200 `applyEdit` that wrote the file itself (that host has no
> editor, so it says `DISK CHANGED` and tells you there is nothing unsaved to look at), a 403 with the token
> message, and a 409 `stale` carrying the current digest. So a surprising result below is about the IDE, not
> about the script's arithmetic.

---

## 1. JetBrains (gate (d))

1. **Launch a sandbox IDE with the plugin.** JDK 25 comes from `org.gradle.java.home` in
   `gradle.properties`; the first run downloads the 2026.2.3 platform.

   ```console
   cd D:\wrk\java\jcodebuddy\webview\webview-jetbrains
   .\gradlew.bat runIde
   ```

   > `webview-core` must be in `mavenLocal` for this build: `mvn -pl webview/core/webview-core install` from the
   > repository root, which the plugin's `build.gradle.kts` says in as many words.

2. **In the sandbox IDE:** `File → Open` → `D:\tmp\obs`. Open `Sample.java` in the editor so the change is
   visible where it lands.

3. **Turn the bridge on.** `File → Settings → Tools → WebView Explorer` (the plugin's own settings page) and set:

   | Field | Value |
   | --- | --- |
   | Port | `18881` |
   | Token | `obs-token` |

   Apply. **The bridge is off until a port is set** — that is deliberate, not a fault: a plugin that opened a
   listening socket by default would be a surprise, and the settings label tells you which state you are in.

4. **Drive the observation:**

   ```console
   cd D:\wrk\java\jcodebuddy
   bun run webview/tools/observe-edit-host.js --port 18881 --token obs-token --file D:\tmp\obs\Sample.java --find "int x = 1;" --replace "int x = 42;" --checkRefusals --watchSeconds 20
   ```

   > Kept on **one line** deliberately. A continuation character belongs to a shell — `^` to cmd.exe, a backtick
   > to PowerShell, `\` to POSIX shells — and a command copied between notes often arrives with the wrong one,
   > which Bun then receives as a positional argument. The script now detects exactly that and says so instead of
   > printing a parser error.

5. **Look at the IDE, and do not save:** the editor should show `int x = 42;` with the tab marked modified, and
   one `Ctrl+Z` should restore `int x = 1;` and leave the tab clean.

### The autosave caveat — read this before concluding anything

The first JetBrains observation (2026-09-26) looked at first like a failure: the file on disk ended up containing
`42`. It was not. The host's answer was `"target": "buffer"` and the disk was **unchanged** at that moment — the
bytes moved later, because **IntelliJ saves modified documents itself**: *"Save files on frame deactivation"* and
*"Save files automatically if application is idle for 15 sec"* are both on by default, and running this script
from a terminal *is* a frame deactivation. That is the editor saving, which is exactly what the contract's own
`detail` describes: *the file on disk is unchanged until the editor saves*.

So:

- `--watchSeconds 20` re-samples the file and says whether the bytes moved after the host answered. A change
  **only** in that second sample is the IDE's save, not the host's write.
- To watch the genuinely unsaved state, turn autosave off first: `Settings → Appearance & Behavior → System
  Settings` → uncheck *Save files on frame deactivation* and *Save files automatically if application is idle*.
- Either way, what identifies the buffer path is the **response** (`"target": "buffer"`) plus the disk being
  unchanged when the host answered — not the final state of the file minutes later.

### What the output should say

| Step | Expected |
| --- | --- |
| `/health` | `"plugin": "hr.hrg.jetbrains.webview"`, `"tokenRequired": true`, `"capabilities": ["edit","open","select"]` |
| `/api/v1/diff` | 200, `"applied": false`, a `unifiedDiff` — a proposal writes nothing |
| `/api/v1/applyEdit` | 200, `"applied": true`, `"target": "buffer"`, a `digest`, and a `detail` saying the file on disk is unchanged until the editor saves |
| disk digest after | **identical** to before (the script says DISK UNCHANGED) |
| wrong token | 403 `the token is required for state-changing routes` |
| stale digest | 409 `"reason": "stale"` |

---

## 1a. Eclipse (the same claim, plus the caret)

The Eclipse host is **implemented and headless-tested** (58 tests, 2026-09-27) and **not yet observed**:
there is no supported headless SWT, so everything below needs a real workbench and a human. Two claims are
observed in one run: the Phase 1 caret landing and the Phase 3 buffer edit.

1. **Install the bundle.** Build it (`bun scripts/mvn-jdk25.js -o -pl webview/eclipse/webview-eclipse package`,
   with `webview-core` installed into the local repository first) and copy it into `<eclipse>/dropins/` as
   `hr.hrg.eclipse.webview_1.0.0.jar` — [`../eclipse/README.md`](../eclipse/README.md) records the two layouts
   p2 may accept and the `-clean` remedy. **Record which layout your Eclipse 4.41 took.**
2. **Open the observed project.** Launch Eclipse (4.41, the 2026-09 train), import or open `D:\tmp\obs` as a
   project, and open `Sample.java` in an editor — the buffer edit only lands in a document the platform
   already holds. Open the **WebView** view (Window → Show View) once, so the injected-bridge path exists too.
3. **Turn the bridge on.** Window → Preferences → JCodeBuddy → WebView: port `18883`, token `obs-token`.
   Apply — the running bridges restart without a workbench restart. **The bridge is off until a port is
   named** (E17): before this step, verify nothing listens (`Get-NetTCPConnection -LocalPort 18883` finds
   nothing), and verify the same after quitting Eclipse at the end of the run.
4. **The caret claim (Phase 1's gate):** with `Sample.java` closed or on another line, ask for a position —
   a `data-open`/`data-line` click in a page the view serves, or directly:
   `Invoke-WebRequest "http://127.0.0.1:18883/open?filePath=<abs path>&line=2&column=9&token=obs-token"`.
   The editor opens with the caret on line 2, column 9. Record the Eclipse, SWT and Edge/WebView2 versions
   (`Browser.getBrowserType()` was measured as `EDGE` in Phase 0; confirm it in the running product).
5. **Drive the buffer-edit observation:**

   ```console
   bun run webview/tools/observe-edit-host.js --port 18883 --token obs-token --file D:\tmp\obs\Sample.java --find "int x = 1;" --replace "int x = 42;" --checkRefusals --watchSeconds 20
   ```

6. **Look at the editor, and do not save:** `int x = 42;` visible, tab marked modified, and **one** `Ctrl+Z`
   restores `int x = 1;` and leaves the tab clean. Eclipse does not autosave editor buffers by default the
   way IntelliJ does; the exact 4.41 preference set is unconfirmed, so let `--watchSeconds 20` attribute a
   *later* disk change to the platform rather than to the host.
7. **Quit Eclipse** and confirm port 18883 has no listener left (E17), and that
   `D:\tmp\obs\.jcodebuddy\webview\host.json` is still there — the record outlives the host (DEC-033).

### What the output should say

| Step | Expected |
| --- | --- |
| `/health` | `"plugin": "hr.hrg.eclipse.webview"`, `"ide": "Eclipse"`, `"tokenRequired": true`, `"capabilities": ["edit","open","select","serveFile"]` |
| `/api/v1/diff` | 200, `"applied": false`, a `unifiedDiff` — a proposal writes nothing |
| `/api/v1/applyEdit`, file **open** in an editor | 200, `"applied": true`, `"target": "buffer"`, a `digest`, and the `detail` saying the disk is unchanged until the editor saves |
| `/api/v1/applyEdit`, file **not open** | 200, `"applied": true` **on disk** — the shared surface's documented fallback when the platform holds no buffer for the path; `/api/v1/undo` takes it back, even after an Eclipse restart (the checkpoint is persistent) |
| disk digest after a buffer apply | **identical** to before (DISK UNCHANGED) |
| wrong token | 403 `the token is required for state-changing routes` |
| stale digest | 409 `"reason": "stale"` |

---

## 2. VS Code (the same claim)

1. **Compile the extension:**

   ```console
   cd D:\wrk\java\jcodebuddy\webview\webview-vscode
   npm install          # only if node_modules is missing
   npm run compile
   ```

2. **Launch an Extension Development Host** with that folder loaded:

   ```console
   code --extensionDevelopmentPath="D:\wrk\java\jcodebuddy\webview\webview-vscode" D:\tmp\obs
   ```

3. **Turn the token on.** In the new window: `Ctrl+,` → search `webviewExplorer` → set **Token** to `obs-token`
   (port `18882` is the default). Changing it restarts the bridge.

4. **Open `Sample.java` in that window.** This host edits *open documents*, so a closed file answers
   `409 no-buffer-edit` rather than editing anything.

5. **Drive the observation:**

   ```console
   bun run webview/tools/observe-edit-host.js --port 18882 --token obs-token --file D:\tmp\obs\Sample.java --find "int x = 1;" --replace "int x = 42;" --checkRefusals --watchSeconds 20
   ```

6. **Look at the editor, and do not save:** the replacement visible, tab dirty, one `Ctrl+Z` back to `int x = 1;`.

### What the output should say

| Step | Expected |
| --- | --- |
| `/health` | `"plugin": "vscode-webview-explorer"`, `"tokenRequired": true`, `"capabilities": ["edit","open","serveFile"]` |
| `/api/v1/diff` | **409 `no-disk-write`** — by design: this host has no bytes of its own to diff, and its README says so |
| `/api/v1/applyEdit` | 200, `"applied": true`, `"target": "buffer"` |
| `/undo`, `/redo` | **409 `no-disk-write`** — that history belongs to a host that owns the file |
| disk digest when the host answered | identical to before |
| wrong token | 403 |
| stale digest | 409 `"reason": "stale"` |

The autosave caveat from § 1 applies here too: a VS Code window can save on focus change, so give
`--watchSeconds 20` and attribute a *later* change to the editor, not the host.

---

## 2a. Recorded observations

| Host | Date | Result |
| --- | --- | --- |
| **JetBrains** 2026.2.3, `runIde` sandbox, commit `bc24ee6` | 2026-09-26 | **Observed.** `"target": "buffer"`; disk unchanged when the host answered; the replacement appeared in the editor; the IDE's own undo restored the original text. The one disk write was IntelliJ's autosave on frame deactivation — the editor saving, which the contract allows. Reported by the maintainer, who checked the save behaviour himself. |
| **VS Code** (Extension Development Host, commit `51950eb`) | 2026-09-26 | **Observed.** The buffer edit landed: the editor showed the new text, the buffer was **not saved**, and the editor's own undo/redo moved it back and forth. Two host-side fixes made the run possible at all: `webviewExplorer.token` had to exist before any write route could be exercised (they refused everything with 403), and the extension needed `activationEvents: ["onStartupFinished"]` — without it the extension never activated, so port 18882 was closed with nothing to explain it. |
| **Eclipse** 4.41 (2026-09 train), dropins install, module as of commit `d7c5fb7` | 2026-09-27 | **Not observed — outstanding.** The headless half passed on 2026-09-27 (58 module tests: routes, statuses, the persistent-checkpoint undo across a host restart). The caret landing, the unsaved buffer edit and the single `Ctrl+Z` need a real workbench and a human — § 1a is the run sheet, and this row becomes the dated observation when someone does it. |

Recorded in this file's own § 2a, and summarised in [`README.md`](../README.md) § "What is implemented, and what
has actually been observed". The plan's Phase 3 record keeps the sequence.

---

## 3. When something else happens

Diagnose before reporting; the interesting outcomes are the ones *not* in the tables above.

| Symptom | Likely cause |
| --- | --- |
| `no host answered on …/health` | JetBrains: no port set in Settings, or no project open (the bridge is a *project* service). VS Code: the extension is not activated in that window, or the port is taken by something else. Eclipse: no port preference and no committed project default — the bridge is off until a port is named (E17) — or the workspace's project location is not the directory you pointed `--file` at. |
| `403 Forbidden: the token is required…` | The token is empty in the IDE, or the `-Token` differs from it. |
| `409 no-buffer-edit` | VS Code: the file is not open in that window. JetBrains: the platform has no document for the path (a binary file, or a path the IDE does not know). Eclipse: the workbench has no page at all, so no edit capability is declared — a *per-path* "no open document" is **not** a 409 there; see the next row. |
| 200 with `"applied": true` **on disk** when you expected the buffer (Eclipse) | The file was not open in an editor, so no platform document existed to carry the change and the shared `WriteSurface` took its documented disk fallback. Open the file in an editor and rerun for the buffer path; the disk write is undoable with `/api/v1/undo` — even after a restart of the workbench, because the Eclipse checkpoint is persistent. |
| `409 stale` | The bytes changed between the script's read and its request — most likely a save in the IDE. Re-run. |
| `404 not-found` (VS Code) | Path mismatch: pass the absolute path, and prefer a path without a symlink. |
| 200 with `target: "buffer"` but **nothing visible** in the editor | The most valuable report: the document changed without a visible editor. Say so, and include the response body. |

---

## 4. What to report back

Paste this, filled in — it is what becomes the dated verification note (plan § 8, criterion 8):

```
host:            JetBrains 2026.2.3 (runIde sandbox)   |   VS Code <version>   |   Eclipse <version, train> (dropins, layout p2 took)
script output:   <the whole paste, including the health document and the disk digests>
visual 0:        (Eclipse only) the caret landed on the requested line and column — yes / no / partial
visual 1:        the replacement is visible in the editor and the tab is marked modified — yes / no / partial
visual 2:        one Ctrl+Z restores the original text and the tab goes clean — yes / no / partial
saved anything?  no (if yes, say what happened to the file on disk)
anything else:   <e.g. an unexpected status, a dialog, a log line, a delay>
```

With that in hand the note goes into § 2a of this file and the plan's Phase 3
record, dated and version-named, and the claim stops being "implemented and unit-tested" and becomes "observed".
