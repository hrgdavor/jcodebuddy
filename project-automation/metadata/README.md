# `project-automation/metadata` — published-report contract

This folder is the shared **build-side** contract for live metadata reports. It is dev-time code under
`project-automation/` and is never installed or shipped as an application artifact.

- `publish.js` splits a report model into a catalog, manifest, index and immutable shard blobs. The default
collection is `types`; `collection`/`collectionKey` let another report publish an array or a differently
named map (for example `contexts`). The browser client accepts the same `collection` option.
- `server.js` is the standalone read-only host: static page + metadata API + targeted SSE.
- `metadata.test.js` covers atomic publication, content-addressed shards and a live SSE change.

A host may serve the static `/reports`, `/source` and `/lib` files and proxy the metadata transport to a
project-automation backend. The Java `webviewd` host does not implement the catalog/index/SSE contract;
that belongs to this JS side. The build output and event shape are what the backend and page share.

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

## Change-event size

One generation change produces **one** SSE event. Do not emit one event per changed type, file or metadata
entry.

- `changedShards` is O(number of changed shards), at most 512 indexes.
- `indexChanged` is a boolean; when true the page fetches the small `index.json` whole.
- The large collection is never in the event and is never re-sent as a whole.

The shard is the granularity. The publisher uses between 64 and 512 deterministic hash buckets, growing with
the collection size (roughly one bucket per eight entries); changing three types names at most three
buckets, not 1000 entries. The small index is deliberately sent whole when `indexChanged` is true — there
is no per-entry metadata event.

If a consumer needs finer UI updates than a shard, the next step is to compare the fetched old/new shard and
update only the changed keys, not to make the wire event per-entry. That is a page concern and does not
change this contract.

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

The shard is the granularity. The publisher uses between 64 and 512 deterministic hash buckets, growing with
the collection size (roughly one bucket per eight entries); changing three types names at most three
buckets, not 1000 entries. The small index is deliberately sent whole when `indexChanged` is true — there
is no per-entry metadata event.

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
/events            metadata change SSE
/api/tools         the project's live tool descriptors (DEC-053)
/api/tools/<tool>/call    one call forwarded to the tool's Unix socket
/api/tools/<tool>/events  that tool's event stream as SSE
```

The app directory is derived and rebuildable, and `/app/` is the default URL a webview should open. The
server polls only `manifest.json`, refuses paths outside the project, and serves `/source` and `/lib`
through the same path jail.

The tool routes let a page served here talk to the compiler's socket, the same way it would through
`webviewd` or the JetBrains host. The list is open, like the rest of this read-only server; a call or a stream
requires the token a webview host published in `.jcodebuddy/webview/token` (or the `toolToken` option), in an
`X-WebView-Token` header or the query. With no token the server answers `403` for those two routes and says
why — a state-changing route with no secret is not worth the convenience.

## Tests

```sh
bun test project-automation/metadata
```
