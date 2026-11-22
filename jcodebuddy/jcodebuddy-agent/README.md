# JCodeBuddy Agent

The agent watches a project through the **fs-meta** daemon, runs JCodeBuddy code tools, and publishes
their proposals and change batches through a **jsx6 dashboard**, a local HTTP server and the tool socket
the webview host proxies.

There is no terminal UI. The process runs headless and is stopped with Ctrl+C or the host's process
control.

## What it does

- **fs-meta watching:** one daemon subscription per project root; no private `WatchService`.
- **Proposal-first tools:** a tool returns before/after text and hashes; it never writes.
- **Change batches:** one fs-meta event groups its proposals; every file has a jsdiff line diff with
  microlighter syntax colour, and can be applied or reverted individually or as a whole batch.
- **Review and active modes:** `review` waits for accept; `active` applies through the coordinator and
  keeps the batch revertible.
- **Permanent refusal:** a tool with a stable case key can remember "do not propose this again" in
  `.jcodebuddy/conf/tool-refusals.json`.
- **OpenRewrite source model:** Java parsing and queries use OpenRewrite LSTs; generated text is
  spliced through `SourceSplicer` (DEC-030). There is no JavaParser.
- **Live tool socket:** `jcodebuddy-agent` is published under `.jcodebuddy/ipc/`; the same
  `AgentMethods` object serves `/rpc` and the host proxy.

## Tools

| Tool             | Proposal source                    | UI             |
| ---------------- | ---------------------------------- | -------------- |
| `builder`        | OpenRewrite `ClassMemberProcessor` | `builder.html` |
| `record-builder` | `BuilderTransformationEngine`      | catalog        |
| `accessors`      | OpenRewrite `ClassMemberProcessor` | catalog        |
| `constructor`    | OpenRewrite `ClassMemberProcessor` | catalog        |
| `rename`         | `SourceReader` / `TreeQueries`     | catalog        |
| `hipster-ioc`    | `IocGeneration.render`             | catalog        |
| `hipster-entity` | temporary-source entity pass       | catalog        |

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
  "tools": {
    "builder": { "activation": "explicit", "mode": "review", "ui": "standalone" },
    "hipster-ioc": { "activation": "auto", "mode": "review" },
    "hipster-entity": { "activation": "disabled" }
  }
}
```

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

- per-file jsdiff line diffs, coloured by microlighter;
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
- generator ownership in `.jcodebuddy/agent/owners.properties`.
