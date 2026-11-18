# `project-automation/metadata` — published-report contract

This folder is the shared **build-side** contract for live metadata reports. It is dev-time code under
`project-automation/` and is never installed or shipped as an application artifact.

- `publish.js` splits a report model into a catalog, manifest, index and immutable shard blobs. The default
collection is `types`; `collection`/`collectionKey` let another report publish an array or a differently
named map (for example `contexts`). The browser client accepts the same `collection` option.
- `server.js` is the standalone read-only host: static page + metadata API + targeted SSE.
- `metadata.test.js` covers atomic publication, content-addressed shards and a live SSE change.

A host may implement the same wire contract independently (the Java `webviewd` adapter does not share
this JavaScript source); the build output and event shape are what must agree.

## Published layout

```text
<project>/.jcodebuddy/
  app/                     default /app/ webview app, derived and rebuildable
  metadata/
    catalog.json
    lib/<bundle>/           shared project-automation assets (jsx6, ...)
    <report>/
      manifest.json         report id, generation, index/page pointers
      index.json            small index: stats/linkBase + ordered shard hashes
      model.json            full-model static fallback
      blobs/<sha256>.json   immutable shard content
      index.html
      browser.js            page bundle (jsx6 externalized)
```

The index is written after its shards, and the manifest after the index. A reader that sees a new
manifest can fetch a consistent index; a shard is never modified in place.

## SSE event

```json
{
  "report": "entity-browser",
  "generation": "sha256:...",
  "changedShards": [7, 19],
  "indexChanged": false
}
```

`changedShards` are indexes into `index.shards`. The page fetches only those blobs and replaces their
keys; `indexChanged` asks it to reload the small index for stats/title changes. Unchanged large maps are
not transferred.

Catalog and index responses carry an `ETag`; a conditional `If-None-Match` returns `304`. Blobs are
immutable and cache for a year.

## Host

`startMetadataServer({ root, host, port })` serves one page/report directory inside a site root:

```text
/                  catalog/landing
/app/              project's authored webview app (from .jcodebuddy/app)
/reports/<id>/...  report page + metadata
/source/...        project sources, read-only
/lib/...           shared project-automation bundles
```

The app directory is derived and rebuildable, and `/app/` is the default URL a webview should open. The
server polls only `manifest.json`, refuses paths outside the project, and serves `/source` and `/lib`
through the same path jail.

## Tests

```sh
bun test project-automation/metadata
```
