# webview — one product, four hosts

A page of HTML can navigate and edit a project: put `data-open="src/main/java/A.java"` and `data-line="14"` on
an element and a click opens that file in the editor with the caret on line 14; ask the host over HTTP and the
change lands in the editor's buffer, unsaved, in the editor's own undo stack. That is the whole feature — one
function, two attributes, and a small write API. No framework, no build step, no network at runtime.

The same contract is implemented by **four hosts**, which is why this folder holds one shared core rather than
four copies of one security model:

| Host | What it is | Reaches the editor by | Asks for | Publishes |
| --- | --- | --- | --- | --- |
| [`webview-jetbrains`](webview-jetbrains/README.md) | JetBrains plugin: a JCEF tool window, plus an HTTP fallback for a browser | its own IDE APIs | 18881 (`webview.explorer.port`) | the port it bound, in `.jcodebuddy/webview/host.json` |
| [`webview-vscode`](webview-vscode/README.md) | VS Code extension: a webview view, plus an HTTP fallback and a file server for the view | `vscode.window`/`workspace` | 18882 (`webviewExplorer.port`) | the port it bound, in `.jcodebuddy/webview/host.json` |
| [`core/webviewd`](core/README.md) | **the standalone host**: a page server with no editor of its own, for any browser | a CLI adapter, an LSP sidecar, or nothing | ephemeral (`--port 0`) | the port it bound, in `.jcodebuddy/webview/host.json` |
| [`jwa-sidecar`](jwa-sidecar/README.md) | an LSP server (also the JWA addon host) | `window/showDocument`, `workspace/applyEdit` | 7979 (`jwa.sidecar.jumpPort`) | the port it bound, once the client says where the project is |

**The listed port is a request, not an address.** Every host publishes the port it actually bound in the
served project's `.jcodebuddy/webview/host.json`, and moves to the next free port when something unrelated
holds the requested one. When the port is held by a host that already serves **the same project**, the second
host opens no endpoint at all rather than starting a second bridge for one project — two IDEs on one project
is normal, and a page that finds two bridges has no rule for choosing.

Two things a project can add, and they are different files on purpose:

- a **default** for the port, committed as `.jcodebuddy/conf/webview.json` (`{ "port": 18882 }`) and
  **optional**, so a fresh clone starts somewhere sensible with no IDE configuration;
- the port this **checkout is currently on**, in `.jcodebuddy/webview/host.json` — local, never in git, and
  what the next start asks for, so a checkout that had to take the next free port keeps it. The same file
  carries the **pin**: set `"sticky": true` there, or once with `webviewd --sticky`, and the port is never
  moved — if something else holds it, the host opens nothing and reports an error. That is what a bookmark, a
  firewall rule or a second screen needs. A pin is per worktree, and it is cleared with `--no-sticky`.

See [`doc/webview-host-api.md`](doc/webview-host-api.md) § 4a; the decision is DEC-033.

Zed is a fifth *client* rather than a host: [`zed/webview-zed-dev-extension`](zed/webview-zed-dev-extension/README.md)
registers the sidecar as a language server, because Zed accepts only language servers an extension declares
(Phase 0 measured that a settings-only entry is ignored).

```
webview/
  README.md                      <- this file: the product, and which file to read
  PLAN-webview-suite.md          the plan and its implementation record, phase by phase
  check-links.mjs                verifies every relative link in this folder
  kit/                           THE CONSUMER HALF — what another project copies (see kit/README.md)
    README.md                    what the kit is, what to copy, and the premise that a host is available
    doc/
      contract.md                the FROZEN navigation contract: openFile, data-*, /open, /health, security
      page-authoring.md          how to build a page: shapes, the client ladder, highlighting, verification
      edit-api.md                the write contract: propose, apply, undo, redo, events
      host-in-this-project.md    the host is available: discovery, authorisation, the security model
    scripts/
      check-pages.mjs            the verifier a project keeps: every link resolves before it is committed
      check-docs.mjs             every relative link in the kit's own documents resolves
    examples/                    the two runnable page shapes, the clients, the smoke tests (see its README)
  doc/                           THE PRODUCER HALF — host authors and this repository's own records
    webview-host-api.md          what a host must implement, and how a page discovers what it can do
    ide-observation-checklist.md the two claims a test cannot make here, and how to check them by hand
  core/
    README.md                    what the shared core is and why it exists
    webview-core/                Maven `hr.hrg.webview.core` (JDK 25): injected bridge, payload parser, origin
                                 allow-list, rate limiter, path jail, EditorHost SPI, Navigator, EditService,
                                 CheckpointStore (journalled), WriteSurface
    webviewd/                    Maven `hr.hrg.webviewd`: the standalone host — /health, /open, /file/, /page/,
                                 /api/v1/*, the descriptor and manifest, adapters (lsp, zed-cli, null)
  conformance/
    bridge-decisions.json        the authorization / CORS / rate-limit vectors every host asserts against
    README.md                    who reads it, and why neither side generates it
  jwa-sidecar/                   the LSP transport, and the JWA side (annotations, Sync Builder code action)
  webview-jetbrains/             IntelliJ Platform plugin (Gradle, IntelliJ Platform 2026.2.3)
  webview-vscode/                the VS Code extension (TypeScript, no bundler)
  zed/                           Phase 0 findings and the Zed dev extension
  tools/
    observe-edit-host.js         drives one buffer-edit observation against any host, and prints the evidence
```

---

## Which file to read

**Building a page, or generating one?** Read the [`kit/`](kit/README.md) — it is the half of this folder
that is written for a project that only *consumes* webview, and every document in it can be copied into
that project as it stands. **Building or changing a host, or working on this repository's own webview
modules?** Read the rest of this table.

| You want to… | Read |
| --- | --- |
| write a page that navigates code | [`kit/doc/page-authoring.md`](kit/doc/page-authoring.md) |
| get the exact navigation contract | [`kit/doc/contract.md`](kit/doc/contract.md) — frozen, page-side normative |
| **edit a file from a page** | [`kit/doc/edit-api.md`](kit/doc/edit-api.md) — diff first, then apply; `target: buffer|disk` |
| make webview available in a project | [`kit/doc/host-in-this-project.md`](kit/doc/host-in-this-project.md) |
| copy the whole consumer half | [`kit/README.md`](kit/README.md) |
| copy a working page | [`kit/examples/`](kit/examples/README.md) — start with [`self-contained/index.html`](kit/examples/self-contained/index.html) |
| see a page that edits | [`kit/examples/with-assets/pages/edit-demo.html`](kit/examples/with-assets/pages/edit-demo.html) |
| use the JetBrains plugin | [`webview-jetbrains/README.md`](webview-jetbrains/README.md) — `Ctrl+Alt+Shift+W`, right-click an `.html` file → **Open in WebView Explorer** |
| use the VS Code extension | [`webview-vscode/README.md`](webview-vscode/README.md) |
| serve a page from a host that needs no editor | [`core/README.md`](core/README.md) — `webviewd --project . --port 0` |
| **implement a new host** | [`doc/webview-host-api.md`](doc/webview-host-api.md), plus [`core/README.md`](core/README.md) and the [`conformance/`](conformance/README.md) vectors |
| drive an editor that has no plugin | [`jwa-sidecar/README.md`](jwa-sidecar/README.md) — LSP, `window/showDocument` |
| verify the IDE claims yourself | [`doc/ide-observation-checklist.md`](doc/ide-observation-checklist.md) — one command, two visual facts |
| see what is planned and what was measured | [`PLAN-webview-suite.md`](PLAN-webview-suite.md) |

## One contract, one implementation of the security model

The navigation contract is frozen in [`kit/doc/contract.md`](kit/doc/contract.md); the write contract is
in [`kit/doc/edit-api.md`](kit/doc/edit-api.md). What is *not* the contract — which origins may call, how
often, which paths may be opened or written, and who checks a digest — was implemented several times and
disagreed, twice in a way that let any page in the user's browser drive the editor. It now lives once, in
[`core/webview-core`](core/webview-core), with the disagreements recorded as vectors in
[`conformance/`](conformance/README.md) that the Java hosts and the TypeScript host all assert against, and the
write *conversation* (routing, statuses, bodies) lives once in `WriteSurface`, which `webviewd` and the
JetBrains bridge both call.

Never assume a port: put it in `data-bridge-port`, and use `GET /health` to find out whether a bridge is really
there and what it can do. Each host answers with the same document shape (`plugin`, `port`, `allowedOrigins`,
`tokenRequired`, `bridgeVersion`, `capabilities`), and `.well-known/webview.json` adds the descriptor: which
verbs exist and how precisely the attached editor can reach a line (`exact`, `file-only`, `none`).

Authorisation is in [`kit/doc/contract.md`](kit/doc/contract.md) § 3.4: an allowed `Origin` **or** a token
for navigation; **the token alone** for every state-changing route, because any page in the reader's browser can
share an origin rule while only a page this host served can hold the secret.

## What is implemented, and what has actually been observed

The distinction matters here and is kept everywhere in this folder: a claim is *implemented and unit-tested*,
or *observed on a named build and date*, never "works" without one of the two.

| Capability | State |
| --- | --- |
| Navigation (page → editor caret) | observed in JetBrains, VS Code and Zed |
| Buffer edit (page → unsaved change in the editor's undo stack) | observed in **JetBrains** (2026-09-26) and **VS Code** (2026-09-26); **Zed** observed 2026-09-25 over LSP |
| Disk edit (digest-guarded, atomic, journalled undo) | `webviewd`, unit-tested; the page-side flow is exercised against it by `examples/webview-client.test.mjs` |
| File serving and page serving | `webviewd` (`/file/`, `/page/` with the bridge injected, `X-WebView-Digest`) |
| Code actions (JWA "Sync Builder") | implemented **and tested** (5 tests, 2026-09-26): offered on a record's name, the command is advertised in `executeCommandProvider` and handled, and the generated edits reach the editor as `workspace/applyEdit` |
| Zed `process:exec` (launching the host from the extension) | **dropped, not missing**: a sidecar the extension spawns is not Zed's language server, so it has no editor attached (the sidecar answers `/health` with an empty capability list in exactly that state). Zed spawns the sidecar when it opens a Java file, which is the one-step cold start |
| The `jwa-sidecar.txt` addon-file mechanism | **withdrawn** by [DEC-031](../doc-hipster-entity/architecture/decisions/DEC-031-project-automations-are-living-code.md): never implemented, and replaced by the model where an automation is a module in *your* project — generated as a stub or copied from an example — with no classloader |

## Verifying

Scripts are Bun JavaScript (`AGENTS.md` § 2): run them with `bun`. Java steps pin JDK 25 themselves.

```console
# the shared core: 161 tests — the write surface, checkpoints, the jail, the allow-list, navigation, and
# the shared contracts: the /health identity (HostHealth), the port claim (HostPortClaim) and the
# project's committed port preference (HostConfig)
bun scripts/mvn-jdk25.js -o -pl webview/core/webview-core -am test
# the standalone host: 58 tests — routes, the descriptor, adapters, the write API, the --sticky flags
bun scripts/mvn-jdk25.js -o -pl webview/core/webviewd -am test
# the LSP sidecar: 24 tests — jump positions, applyEdit over LSP, the HTTP surface, the published port
bun scripts/mvn-jdk25.js -o -pl webview/jwa-sidecar -am test
# the JetBrains plugin: 30 tests (Gradle, IntelliJ Platform 2026.2.3)
cd webview/webview-jetbrains && ./gradlew.bat test
# the VS Code host's decisions, against the shared vectors, with no VS Code download: 275 assertions
cd webview/webview-vscode && npm run test:unit
# and the same host in a real VS Code: the buffer edit, unsaved, with the editor's own undo (9 tests)
cd webview/webview-vscode && npm test

# the page side
bun webview/kit/examples/webview-client.test.mjs  # the client against a live headless webviewd (the write verbs)
node webview/kit/examples/smoke-test.mjs          # every page in a real Chromium: parse, highlight, links resolve
node webview/kit/scripts/check-pages.mjs          # every link resolves, offline, with no browser needed
node webview/kit/scripts/check-docs.mjs           # every relative link in the kit's documents resolves
node webview/tools/check-pages.test.mjs           # and the checker itself fails when it should (21 assertions)
bun webview/tools/check-capabilities.js           # capability honesty: declared ⇒ served, undeclared ⇒ refused
bun webview/tools/check-port-claim.js             # two live hosts: one bridge per project, and where a port goes
node webview/check-links.mjs                      # every relative link in this folder resolves
```

`webview-client.test.mjs` starts `webviewd` itself and drives the same functions a browser page calls — nothing
is mocked — so a verb that stops working fails the run rather than being discovered by a reader.
`smoke-test.mjs` drives a real Chromium over the DevTools Protocol, with no dependencies, and skips the browser
half if no Chromium is installed; it reports the highlighted block and token-category counts per page, so a
grammar that silently stops loading fails the run rather than looking fine.

`kit/scripts/check-pages.mjs` is the browser-less half of the same five checks, and it is the one a consuming
project keeps in its build: it resolves every `data-open` through the page's own link base, asserts that a
claimed `data-member` really is on the claimed line, and refuses an absolute path or a remote asset in the
artifact. `tools/check-pages.test.mjs` is its own test — fixture sites that are wrong in each of those ways,
because a verifier that silently passes is worse than none.

`node scripts/check-repo-links.mjs` from the repository root additionally checks every relative link in every
Markdown file of the repository, which covers the links *out* of this folder.

## Two page shapes, one contract

| | [`kit/examples/self-contained/`](kit/examples/self-contained/index.html) | [`kit/examples/with-assets/`](kit/examples/with-assets/index.html) |
| --- | --- | --- |
| Files | 1 | 4 assets + one file per page |
| Travels alone (attachment, CI artifact) | yes | needs the folder |
| Several pages share a shell and client | no | yes |
| A generator regenerates one page without touching the rest | no | yes |

Both are offline: no CDN, no bundler, no `node_modules` at view time. Both keep absolute paths out of the
artifact. Both highlight JavaScript, Java, JSON, HTML and Markdown with
[microlighter](https://github.com/davatron5000/microlighter) — inlined in the self-contained page, shared as an
asset in the other.

## Licence and provenance

`kit/examples/*/assets/microlighter.js` and its inlined copy are
[microlighter](https://github.com/davatron5000/microlighter) 2.2.0, MIT, © Dave Rupert, with TextMate grammars
adapted from Microsoft VS Code (MIT). Four documented changes adapt it to a page with no module loader; the list
is in the file header and in [`kit/doc/page-authoring.md`](kit/doc/page-authoring.md) § 7.1.
