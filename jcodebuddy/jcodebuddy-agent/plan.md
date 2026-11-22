# JCodeBuddy Agent plan

The agent is the headless coordinator between the fs-meta watcher, the tool catalog and the webview.

## Current architecture

- `ProjectWatcher` subscribes to fs-meta and passes changed paths to `AgentTools.onChanged`.
- `Tool` implementations return `ToolProposal` values; they never write.
- `AgentTools` groups proposals into change batches, tracks per-file applied/reverted state, and
  exposes the review/active modes.
- `WriteCoordinator` is the only writer: per-file lock, hash check, atomic move, revert and audit.
- The jsx6 dashboard shows batches with jsdiff + microlighter diffs; a live dashboard is the explicit
  tools' heartbeat.
- Permanent refusals are stored per stable case in `.jcodebuddy/conf/tool-refusals.json`.

## Tool modules

- `jcodebuddy-tool-builder`
- `jcodebuddy-tool-java`
- `jcodebuddy-tool-hipster-ioc`
- `jcodebuddy-tool-hipster-entity`

## Related

- [Tool integration and rename plan](../../plans/jcodebuddy-agent-integration-and-rename.md)
- [README](README.md)
