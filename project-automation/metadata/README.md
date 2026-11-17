# `project-automation/metadata` — published-report contract

This folder is the shared **build-side** contract for live metadata reports. It is dev-time code under
`project-automation/` and is never installed or shipped as an application artifact.

- `publish.js` splits a report model into a catalog, manifest, index and immutable shard blobs.
- `server.js` is the standalone read-only host: static page + metadata API + targeted SSE.
- `metadata.test.js` covers atomic publication, content-addressed shards and a live SSE change.

A host may implement the same wire contract independently (the Java `webviewd` adapter does not share
this JavaScript source); the build output and event shape are what must agree.

## Published layout

```text
<metadata root>/
  catalog.json
  <report>/
    manifest.json          report id, generation, index/page pointers
    index.json             small index: stats/linkBase + ordered shard hashes
    model.json             full-model static fallback
    blobs/<sha256>.json    immutable shard content
    index.html
    browser.js             page bundle (jsx6 externalized)
    assets/jsx6/<hash>/    shared, content-addressed jsx6 libraries
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

`startMetadataServer({ root, host, port })` serves one page/report directory. It polls only
`manifest.json`, and refuses paths outside the directory. `serve.js` in the entity-browser page is a thin
wrapper around it.

## Tests

```sh
bun test project-automation/metadata
```
