# TODO - Java Watch 2

> **Status 2026-10-08 — every item is resolved: done, scheduled, or struck through with a reason.** The two
> "Immediate Priority" items were closed by plan steps 7.2 and 7.1 after this list was last reconciled, and the
> three open "Future Plans"/"Zig Integration" items were decided with the maintainer in step 7.6. Nothing here
> stays silently unchecked, which is that step's gate. **This is a record, not a work list**: the live schedule
> is [`plans/unified-plan.md`](plans/unified-plan.md), and a new item belongs there.

## Immediate Priority
- [x] Implement Remote Jump Front-end: Update Agent Web UI to send GET /jump requests to Sidecar.
      — **done** in plan step 7.2: `CommandServer` gained a `/jump` route that forwards to the sidecar and relays
      its answer verbatim, so the page posts to its own origin and the **server** presents `X-WebView-Token` (no
      token reaches a browser); the dashboard shows the sidecar's own outcome rather than an assumed success, and
      `RemoteJumpTest` asserts the token, the relay and the unreachable case.
- [x] Refine Configuration: Add support for custom indentation (tabs/spaces) in toolsets.
      — **done** in plan step 7.1: `ClientFormatting` reads `tabSize`/`insertSpaces` from
      `workspace/didChangeConfiguration` (the one LSP verb that carries them — a code action carries no
      `FormattingOptions`), and `syncBuilder` builds its engine with that indent; a two-space client produces
      two-space output, with `insertSpaces:false` producing a tab. Four spaces remains the default.
- [x] Incremental Improvements: Support incremental updates in LSP more robustly.
      — **done** in `webview/jwa-sidecar`: `JwaLanguageServer` advertises
      `TextDocumentSyncKind.Incremental` and `JwaTextDocumentService.didChange` applies the change
      through `applyIncrementalChange`.

## Future Plans
- [x] ~~Configurable delay for delete events (debouncing).~~ — **decided against (2026-10-08, plan step 7.6).**
      Deletes already ride the same trailing-edge window every other change does: `BatchedFileWatcher` documents
      that `DELETE` events land in `ChangeSet.deleted()` after the same `debounceMs`, measured from the *last*
      change. A delete-specific delay would therefore be a second knob over one behaviour with no measurement
      behind it, so the item is struck rather than scheduled. Reopen it only with a case the shared window
      demonstrably mis-batches.
- [x] ~~Memory-based LocalDB (optional) to reduce disk I/O.~~ — **decided against (2026-10-08, plan step 7.6).**
      The premise does not match what `local_db` does: it chooses *where the `.scpdb` file lives* (the local
      machine or a remote absolute path), not whether the store is kept in memory. A true in-memory mode would
      drop the cross-run state that is the database's purpose — the next run compares against it — and nothing in
      the tree measures the file I/O as a cost worth that. **Struck for the absence of a measurement, not as
      impossible**: reopen it with numbers.
- [x] Support Wyhash for better performance (preparing for Zig integration).
      — **done**: `hr.hrg.wyhash:wyhash:1.0.0` is a dependency of `java-watch-core` and is used by
      `ChecksumDatabase`, `MetadataCache` and `AuditManager`; `hipster-entity-tooling` vendors
      `Wyhash64` as `ContentHash` (DEC-029 § 4), with golden vectors pinned by `ContentHashTest`.
- [x] Configurable text extensions for SCP hashing.
      — **done**: `text_extensions` in
      [`WatchScpConfig`](watch/java-watch-scp/src/main/java/hr/hrg/watch2/scp/WatchScpConfig.java), documented
      in [`DOCUMENTATION.md`](watch/java-watch-scp/DOCUMENTATION.md).

## Zig Integration
- [x] ~~Port core hashing logic to Zig for learning and high-performance cross-platform builds.~~ — **struck
      (2026-10-08, plan step 7.6, on the maintainer's own reason).** The ecosystem uses **wyhash**, deliberately
      chosen for compatibility with the wider wyhash family — Zig's own `std.hash.Wyhash` among them — and that
      compatibility is the point. It is already delivered by *using the algorithm* rather than by porting code:
      `hr.hrg.wyhash:wyhash:1.0.0` is a dependency of `java-watch-core` (item above), and `hipster-entity-tooling`
      vendors `Wyhash64` as `ContentHash` with the algorithm pinned by golden vectors (DEC-029 § 4). A second
      implementation in another language would be maintained forever with no consumer, so there is nothing left
      for this item to deliver: the item was a means, and the end already exists.
