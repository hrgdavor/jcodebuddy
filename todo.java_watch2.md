# TODO - Java Watch 2

> **Status 2026-10-01 — a scratch list, reconciled against the tree.** Each item below now carries what
> the code says. The one item that left this file (incremental LSP updates) is done; two more are done and
> ticked; the rest are open and are scheduled in [`plans/unified-plan.md`](plans/unified-plan.md).

## Immediate Priority
- [ ] Implement Remote Jump Front-end: Update Agent Web UI to send GET /jump requests to Sidecar.
      — **open**: the sidecar's `/jump` endpoint, its token/origin gate and loopback bind all exist
      (Phase 3 of [`jwa-sidecar/plan.md`](webview/jwa-sidecar/plan.md)), but the agent's web UI never
      calls it: `jcodebuddy/java-watch-agent/src/main/resources/web/` contains no `jump` and no `7979`.
- [ ] Refine Configuration: Add support for custom indentation (tabs/spaces) in toolsets.
      — **open**: the indent is a constructor argument — `BuilderTransformationEngine(String indent)`,
      `RecordBuilderProcessor(String indent)` — and nothing reads the client's `tabSize`/`insertSpaces`.
- [x] Incremental Improvements: Support incremental updates in LSP more robustly.
      — **done** in `webview/jwa-sidecar`: `JwaLanguageServer` advertises
      `TextDocumentSyncKind.Incremental` and `JwaTextDocumentService.didChange` applies the change
      through `applyIncrementalChange`.

## Future Plans
- [ ] Configurable delay for delete events (debouncing).
      — **unresolved**: a debounce exists and is configurable (`BatchedFileWatcher` trailing-edge,
      `ManagedFileWatcher` per-file, `java-watch-scp` `watch_delay_ms`), but there is no *delete-specific*
      delay; whether one is wanted was never decided.
- [ ] Memory-based LocalDB (optional) to reduce disk I/O.
      — **open**: `local_db` today decides *where the `.scpdb` file lives* (local machine vs remote), not
      whether the store is kept in memory.
- [x] Support Wyhash for better performance (preparing for Zig integration).
      — **done**: `hr.hrg.wyhash:wyhash:1.0.0` is a dependency of `java-watch-core` and is used by
      `ChecksumDatabase`, `MetadataCache` and `AuditManager`; `hipster-entity-tooling` vendors
      `Wyhash64` as `ContentHash` (DEC-029 § 4), with golden vectors pinned by `ContentHashTest`.
- [x] Configurable text extensions for SCP hashing.
      — **done**: `text_extensions` in
      [`WatchScpConfig`](watch/java-watch-scp/src/main/java/hr/hrg/watch2/scp/WatchScpConfig.java), documented
      in [`DOCUMENTATION.md`](watch/java-watch-scp/DOCUMENTATION.md).

## Zig Integration
- [ ] Port core hashing logic to Zig for learning and high-performance cross-platform builds.
      — **not started**: there is no `*.zig` file anywhere in the repository.
