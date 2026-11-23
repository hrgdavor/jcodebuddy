# jcodebuddy-tool-api

The reusable tool contract and single-writer coordinator for JCodeBuddy tools.

A tool **previews**; it does not write. The caller reviews the `ToolProposal`, then applies each
`FileProposal` through `WriteCoordinator`.

```java
ToolProposal proposal = tool.preview(new ToolRequest(root, file, line, Map.of()));
ApplyResult result = new WriteCoordinator(root).apply(proposal.files().get(0));
```

## Why

- one place for stale checks, atomic writes, locks and audit;
- a tool cannot accidentally overwrite an editor change between preview and apply;
- an apply can refuse or report a conflict instead of retrying behind the user's back;
- the same contract serves the agent, entity/IoC adapters and future build callers.

## WriteCoordinator

- per-file OS lock under `.jcodebuddy/agent/locks/`;
- SHA-256 `beforeHash` check before writing;
- temp file + atomic move;
- reject reverts only when the current content still equals the proposal's after-hash;
- audit snapshots under `.jcodebuddy/agent/audit/`;
- an owner map under `.jcodebuddy/agent/owners.properties`; a file owned by
  `hipster-entity-tooling` is refused to the generic builder, and vice versa.

The source file remains the source of truth; the audit and owner map are evidence, not configuration.

## Batches and permanent refusal

The agent groups proposals into change batches. A tool may implement `caseKey(ToolRequest)` and
`supportsPermanentRefusal()`: the case key is stable across rebuilds (for example
`builder:src/main/java/.../Order.java`), and a rejected case can be recorded in
`.jcodebuddy/conf/tool-refusals.json`. A tool whose output cannot be skipped per case leaves the
feature off.

`ToolProposal` files carry `beforeContent` and `afterContent`; the UI diffs them with jsdiff and
microlighter, and applies/reverts a file, a proposal or the whole batch through the coordinator.

A proposal also carries a semantic `summary` and optional `details`. The tool authors them because it knows
what it changed — for example `Record components: removed 2, added 1` with one detail line per removed,
added or retyped component. The batch catalog shows the summary; the review page shows the detail list.
Tools that do not supply one get a generic file-count fallback.
