# `metadata/watch/` — watch-agent cache and audit trail

Written by the `java-watch-agent` module:

- `<toolSet>/metadata.db` — `MetadataCache`'s checksum/mtime cache, so a restart can
  detect offline changes without a full rescan.
- `audit/<toolSet>/<yyyyMMdd_HHmmss>_<action>/` — `AuditManager`'s trail of every
  applied edit: `manifest.json`, `summary.md`, and `before/` + `after/` snapshots of the
  touched files.

This is `.watch/metadata/...` under a new name; the old location is no longer written.
The cache is derived, so no migration is needed — it rebuilds on the next scan.
