# IDE observation checklist — the buffer-edit claim

The two claims in this product that **no test in this repository can make for you**:

- **gate (d), JetBrains**: an edit applied through the plugin's write API appears in the IDE's own editor as an
  **unsaved** change, the file on disk is untouched, and one `Ctrl+Z` takes it back;
- **the same claim for VS Code**, through `vscode.workspace.applyEdit`.

Everything up to the platform call is implemented and unit-tested (core's `WriteSurface`; the plugin's
`DocumentEdits` + `WriteCommandEditor`; VS Code's `BridgePolicy` decisions; 30 Gradle tests and 161 unit
assertions). What remains is the one thing a test in this repository cannot observe: **the IDE**. Both are
recorded as *implemented and unit-tested*, never as *observed*, until someone does this and reports it.

For comparison, gate (e) — the same claim over LSP for Zed — was observed on 2026-09-25 and is recorded as such
in [`webview-edit-api.md`](webview-edit-api.md) § 6. This checklist exists to make the other two equally cheap.

**Nothing here writes to your IDE's real settings.** The JetBrains path runs a *sandbox* IDE under the plugin's
`build/idea-sandbox/`, and the VS Code path runs an Extension Development Host window. Neither touches the Zed
settings or the settings of your installed IDEs.

---

## 0. Prepare a file to edit (once, for both)

Keep it out of the repository, so nothing observed can end up in a commit:

```powershell
New-Item -ItemType Directory -Force D:\tmp\obs | Out-Null
Set-Content -Path D:\tmp\obs\Sample.java -Value @'
class Sample {
    int x = 1;
}
'@
```

The needle `int x = 1;` must be on one line; the script refuses one that spans lines.

**Windows blocks unsigned scripts**, so run the observation with a per-process bypass rather than changing the
machine's policy:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
```

or invoke it in a child shell: `powershell -ExecutionPolicy Bypass -File .\webview\tools\observe-edit-host.ps1 …`.

> **The script is self-tested.** Running it against `webviewd --host none` on 2026-09-25 produced the expected
> `/health`, a 200 proposal with a `unifiedDiff`, a 200 `applyEdit` that wrote the file itself (that host has no
> editor, so it says `DISK CHANGED` and tells you there is nothing unsaved to look at), a 403 with the token
> message, and a 409 `stale` carrying the current digest. So a surprising result below is about the IDE, not
> about the script's arithmetic.

---

## 1. JetBrains (gate (d))

1. **Launch a sandbox IDE with the plugin.** JDK 25 comes from `org.gradle.java.home` in
   `gradle.properties`; the first run downloads the 2026.2.3 platform.

   ```powershell
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

   ```powershell
   cd D:\wrk\java\jcodebuddy
   .\webview\tools\observe-edit-host.ps1 -Port 18881 -Token obs-token `
       -File D:\tmp\obs\Sample.java -Find 'int x = 1;' -Replace 'int x = 42;' -CheckRefusals
   ```

5. **Look at the IDE, and do not save:** the editor should show `int x = 42;` with the tab marked modified, and
   one `Ctrl+Z` should restore `int x = 1;` and leave the tab clean.

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

## 2. VS Code (the same claim)

1. **Compile the extension:**

   ```powershell
   cd D:\wrk\java\jcodebuddy\webview\webview-vscode
   npm install          # only if node_modules is missing
   npm run compile
   ```

2. **Launch an Extension Development Host** with that folder loaded:

   ```powershell
   code --extensionDevelopmentPath="D:\wrk\java\jcodebuddy\webview\webview-vscode" D:\tmp\obs
   ```

3. **Turn the token on.** In the new window: `Ctrl+,` → search `webviewExplorer` → set **Token** to `obs-token`
   (port `18882` is the default). Changing it restarts the bridge.

4. **Open `Sample.java` in that window.** This host edits *open documents*, so a closed file answers
   `409 no-buffer-edit` rather than editing anything.

5. **Drive the observation:**

   ```powershell
   .\webview\tools\observe-edit-host.ps1 -Port 18882 -Token obs-token `
       -File D:\tmp\obs\Sample.java -Find 'int x = 1;' -Replace 'int x = 42;' -CheckRefusals
   ```

6. **Look at the editor, and do not save:** the replacement visible, tab dirty, one `Ctrl+Z` back to `int x = 1;`.

### What the output should say

| Step | Expected |
| --- | --- |
| `/health` | `"plugin": "vscode-webview-explorer"`, `"tokenRequired": true`, `"capabilities": ["edit","open","serveFile"]` |
| `/api/v1/diff` | **409 `no-disk-write`** — by design: this host has no bytes of its own to diff, and its README says so |
| `/api/v1/applyEdit` | 200, `"applied": true`, `"target": "buffer"` |
| `/undo`, `/redo` | **409 `no-disk-write`** — that history belongs to a host that owns the file |
| disk digest after | identical to before |
| wrong token | 403 |
| stale digest | 409 `"reason": "stale"` |

---

## 3. When something else happens

Diagnose before reporting; the interesting outcomes are the ones *not* in the tables above.

| Symptom | Likely cause |
| --- | --- |
| `no host answered on …/health` | JetBrains: no port set in Settings, or no project open (the bridge is a *project* service). VS Code: the extension is not activated in that window, or the port is taken by something else. |
| `403 Forbidden: the token is required…` | The token is empty in the IDE, or the `-Token` differs from it. |
| `409 no-buffer-edit` | VS Code: the file is not open in that window. JetBrains: the platform has no document for the path (a binary file, or a path the IDE does not know). |
| `409 stale` | The bytes changed between the script's read and its request — most likely a save in the IDE. Re-run. |
| `404 not-found` (VS Code) | Path mismatch: pass the absolute path, and prefer a path without a symlink. |
| 200 with `target: "buffer"` but **nothing visible** in the editor | The most valuable report: the document changed without a visible editor. Say so, and include the response body. |

---

## 4. What to report back

Paste this, filled in — it is what becomes the dated verification note (plan § 8, criterion 8):

```
host:            JetBrains 2026.2.3 (runIde sandbox)   |   VS Code <version>
script output:   <the whole paste, including the health document and the disk digests>
visual 1:        the replacement is visible in the editor and the tab is marked modified — yes / no / partial
visual 2:        one Ctrl+Z restores the original text and the tab goes clean — yes / no / partial
saved anything?  no (if yes, say what happened to the file on disk)
anything else:   <e.g. an unexpected status, a dialog, a log line, a delay>
```

With that in hand the note goes into [`webview-edit-api.md`](webview-edit-api.md) § 6 and the plan's Phase 3
record, dated and version-named, and the claim stops being "implemented and unit-tested" and becomes "observed".
