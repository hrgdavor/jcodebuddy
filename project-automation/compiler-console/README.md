# Compiler console page

The served page of the IntelliJ incremental compiler: the same log and the same buttons as the plugin's
**JCodeBuddy Build** tool window, reached over HTTP instead of inside the IDE.

It is a **default solo page per tool** (DEC-053): the plugin publishes its state and commands on a Unix
socket under the project's `.jcodebuddy/ipc/`, a host proxies that socket at `/api/tools`, and this page is
one static bundle that talks to the proxy. Nothing here knows about IntelliJ.

## Build

From the repository root:

```sh
bun project-automation/compiler-console/src_build/build.js --link-base .
```

That writes, into the project's derived and git-ignored output:

```text
<project>/.jcodebuddy/app/incremental-build.html
<project>/.jcodebuddy/app/incremental-build.js   (+ .map)
```

`/app/incremental-build.html` is served by the host with no route change: `/app/` maps to
`.jcodebuddy/app/` in every webview host (`webview/doc/webview-app-contract.md`).

## Open it

```text
http://127.0.0.1:<port>/app/incremental-build.html
```

- The page reads the host token from `localStorage` (key `jcodebuddy.token`). A standalone first visit may
  carry `?token=<host token>`; the page stores it and removes it from the address bar, so later links and a
  reload work without it. The IDE host injects the same key before the page's scripts run, so in the
  WebView Explorer the URL needs no token at all.
- The host must serve DEC-053's proxy. **All three do now**: `webviewd`, the JetBrains **WebView Explorer**
  host's HTTP bridge, and the standalone Bun metadata server (`project-automation/metadata`, with the token
  from `.jcodebuddy/webview/token`). Each serves `GET /api/tools`, `POST /api/tools/<tool>/call` and
  `GET /api/tools/<tool>/events` with the same statuses and frame, so the page is the same in every host.
- Without a token anywhere the page says so and disables its buttons; it does not render an empty log
  pretending everything is fine.

## What it shows and does

The header carries the tool state (`enabled`, `running`, `watch`, batch marker); the status line carries the
retry count, the files with diagnostics and the fs-meta daemon root; the footer names the build log file and
its retained line count.

| button             | tool method           | effect                                                    |
| ------------------ | --------------------- | --------------------------------------------------------- |
| Rebuild            | `build.rebuild`       | compiles every source root again                          |
| Stop Watch         | `build.watch.stop`    | drops the fs-meta subscription, keeps the loop            |
| Start Watch        | `build.watch.start`   | re-subscribes and replays from the batch marker           |
| Refresh Errors     | `build.refreshErrors` | restarts highlighting and clears stale Project-view marks |
| Refresh Roots      | `build.refreshRoots`  | re-scans IntelliJ's module model                          |
| Detailed Classpath | `build.listClasspath` | writes every module classpath entry to the build log      |
| List Roots         | `build.roots`         | shows the source roots and outputs                        |
| Diagnostics        | `build.diagnostics`   | shows the current compiler diagnostics                    |
| Clear View         | —                     | clears this page's panes; the host keeps its log buffer   |

The log is **pushed**, not polled: `build.log` is fetched once for the retained tail, then the host's SSE
route (`GET /api/tools/<tool>/events`) streams `tool.event` notifications as they are written. The SSE `id` is
the line's sequence, so a reconnect resumes at the last rendered line (`Last-Event-ID` wins over the URL's
`since`), and a lower sequence than the page has seen means the tool restarted and the view reloads. Status is
still fetched every few seconds — it is small and not the thing the page exists for.

## Build rules it follows

- **jsx6**, from the local checkout (`JCODEBUDDY_JSX6_DIR` or `<repo>/.jsx6`), not a pinned copy — root
  AGENTS.md § 2. The page is interactive (live log, commands), so DEC-027's amendment puts it in the jsx6
  class, not the vanilla single-file class.
- **Bun renders it**: no Java generator emits HTML.
- **Output is derived**: `.jcodebuddy/app/` is ignored output (DEC-026), never committed.
- The build is private dev-time tooling under `project-automation/` (AGENTS § 1.1): never installed, never
  deployed, and nothing depends on it. The page it produces is a static file.

## Build notes

- `esbuild` is a dev dependency of this package (`bun install`); the checkout's entity-browser already has
  one, and the script falls back to it so one build installs can serve both. `ESBUILD=<path>` overrides.
- The bundle **inlines jsx6** because `/app/<tool>.html` is one URL level deep, while a report at
  `/reports/<id>/` is two: the report's shared `/lib/jsx6/...` import map would point at the wrong place. One
  tool page is one self-contained bundle.
