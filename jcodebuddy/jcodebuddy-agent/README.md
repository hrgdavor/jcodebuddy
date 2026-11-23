# JCodeBuddy Agent

The agent watches a project through the **fs-meta** daemon, runs JCodeBuddy code tools, and publishes
their proposals and change batches through a **jsx6 dashboard**, a local HTTP server and the tool socket
the webview host proxies.

There is no terminal UI. The process runs headless and is stopped with Ctrl+C or the host's process
control.

## What it does

- **fs-meta watching:** one daemon subscription per project root; no private `WatchService`.
- **Proposal-first tools:** a tool returns before/after text and hashes; it never writes.
- **Change batches:** the catalog shows one summary row per fs-meta batch — tool, proposal count, file
  count and applied state. **Open batch** opens the review page with files on the left and a side-by-side
  jsdiff before/after on the right, with word-level highlights, microlighter syntax colour, and
  **Changes only** / **Whole file** modes. Files, proposals and whole batches can be applied or reverted.
  After an apply or revert the agent posts the written paths to the host's loopback-only
  `/api/v1/refresh`, so an open IntelliJ document reloads instead of presenting a stale buffer on the next
  keystroke. An unapplied batch is discarded when its file changes again; an undo that leaves the tool with no
  proposal discards the old batch and creates no replacement. Unchanged batches are not re-rendered, so
  scrolling stays put.
- **Review and active modes:** `review` waits for accept; `active` applies through the coordinator and
  keeps the batch revertible. Automatic record tooling is opt-in per tool: the record builder requires
  `GenerateBuilder`, and record/interface sync requires `GenerateRecordInterfaceSync`; unmarked records and
  interfaces are left untouched.
- **Permanent refusal:** a tool with a stable case key can remember "do not propose this again" in
  `.jcodebuddy/conf/tool-refusals.json`.
- **OpenRewrite source model:** Java parsing and queries use OpenRewrite LSTs; generated text is
  spliced through `SourceSplicer` (DEC-030). There is no JavaParser.
- **Live tool socket:** `jcodebuddy-agent` is published under `.jcodebuddy/ipc/`; the same
  `AgentMethods` object serves `/rpc` and the host proxy.

## Tools

| Tool                    | Proposal source                                                                                  | UI             |
| ----------------------- | ------------------------------------------------------------------------------------------------ | -------------- |
| `builder`               | OpenRewrite `ClassMemberProcessor`                                                               | `builder.html` |
| `record-builder`        | `BuilderTransformationEngine`                                                                    | catalog        |
| `record-interface-sync` | interface defines record components; creates a missing record and syncs the record header (requires `GenerateRecordInterfaceSync`) | catalog |
| `accessors`             | OpenRewrite `ClassMemberProcessor`                                                               | catalog        |
| `constructor`           | OpenRewrite `ClassMemberProcessor`                                                               | catalog        |
| `rename`                | `SourceReader` / `TreeQueries`                                                                   | catalog        |
| `hipster-ioc`           | `IocGeneration.render`                                                                           | catalog        |
| `hipster-entity`        | temporary-source entity pass (`@GenerateEntity`, `@View`)                                        | catalog        |

## Build

```bash
bun run web/src_build/build.js
mvn -o -pl jcodebuddy/jcodebuddy-agent -am package
```

The jsx6 assets are built into `src/main/resources/web/` and copied into the shaded jar. The agent also
installs them into `<project>/.jcodebuddy/app/`.

## Run

Run `hr.hrg.watch2.agent.WatchAgent` from the project root. It:

1. loads or creates `.watch_agent.conf`;
2. generates a web password when standalone access is enabled and no password is set;
3. attaches to the fs-meta daemon serving the project, or reports the `fsmeta root add` command;
4. publishes `.jcodebuddy/ipc/jcodebuddy-agent.{sock,json}`;
5. installs `.jcodebuddy/app/jcodebuddy-agent.html`;
6. starts the standalone HTTP server when enabled;
7. waits for Ctrl+C.

## Configuration

`.watch_agent.conf` holds the standalone host settings:

```json
{
  "webEnabled": true,
  "webPort": 6666,
  "webUser": "jcodebuddy_agent",
  "webPassword": "change-me"
}
```

Tool activation and UI mode are per tool in `.jcodebuddy/conf/agent.json`:

```json
{
  "annotations": {
    "module": "core",
    "sourceRoot": "core/src/main/java",
    "package": "demo.playground.gen"
  },
  "tools": {
    "builder": { "activation": "explicit", "mode": "review", "ui": "standalone" },
    "hipster-ioc": { "activation": "auto", "mode": "review" },
    "hipster-entity": { "activation": "disabled" }
  }
}
```

`annotations` names the project-local annotation module, source root and package. Each tool contributes its
own generated type source in that package (`GenerateBuilder.java`, `GenerateRecordInterfaceSync.java`, ...);
the agent writes it when missing and rewrites it when the tool changes or adds a type. A marker source may
be an annotation, a record or another type the tool needs. Configuration values — record instances or static
fields — are ordinary project code and are never generated. Record tools fail with a configuration error
when the general annotation location is absent. The agent also refuses to start in that case: it prints the
missing configuration and an example `annotations` block, then exits with status `2`. The generated types
live in ordinary project source (normally one core module), so no POM needs a dependency just to import
them.

- `explicit`: only a request from the live webview starts it; a live dashboard is the heartbeat.
- `auto`: proposals are produced without a live dashboard.
- `disabled`: the tool refuses previews.
- `mode: review`: proposals wait for accept.
- `mode: active`: proposals are applied immediately and remain revertible.
- `ui: standalone | integrated | both` is read by project-automation.

A missing entry is `explicit`/`review`. The full tool config is the tracked project decision; the
per-checkout web port/password is not.

## Dashboard

- `/` serves the jsx6 catalog and change batches.
- `/rpc` serves the shared `AgentMethods`.
- `/jump` forwards an editor jump to the webview sidecar with the host token.
- `?token=` is the first-visit host token; it is stored for later calls.

The catalog lists tools and the current batches. Each batch shows:

- a batch catalog with counts only, and a dedicated batch review page with a file list and
  per-file side-by-side jsdiff before/after, with word-level changes highlighted and microlighter
  syntax colour;
- `Apply file` / `Revert file`;
- `Open file` (host `window.openFile` or the `/jump` relay);
- `Apply batch` / `Revert batch`;
- `Accept proposal` and `Reject & skip future` when the tool supports permanent refusal.

Microlighter is used only as the code-colour layer. If the browser lacks the CSS Custom Highlight API,
the diff remains readable and uncoloured.

## Methods

`AgentMethods` exposes:

- `agent.status`;
- `tool.list`, `tool.preview`, `tool.batches`;
- `tool.apply`, `tool.reject`, `tool.applyFile`, `tool.revertFile`;
- `tool.applyBatch`, `tool.revertBatch`.

A tool preview never writes. `WriteCoordinator` is the single writer for every apply/revert.

## Coordination

`WriteCoordinator` provides:

- a per-file OS lock under `.jcodebuddy/agent/locks/`;
- a SHA-256 `beforeHash` stale check;
- atomic temp-file replacement;
- revert only when the current file is still the proposal's after-state;
- audit snapshots under `.jcodebuddy/agent/audit/`;
- **opt-in** generator ownership in `.jcodebuddy/agent/owners.properties`. Ordinary code tools claim no
  owner and may edit the same file; only a tool that genuinely needs exclusive access records an owner.
