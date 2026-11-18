# Hosting and updating metadata reports

This is the user-facing guide for the served metadata contract implemented by
[`plans/metadata-web-reports.md`](../plans/metadata-web-reports.md). It covers the entity browser as the
first consumer; additional reports use the same build output and page client.

## What is served

A report build writes a page directory and a metadata directory. The page shell is static; the metadata is
replaceable:

```text
<project>/.jcodebuddy/metadata/
  catalog.json
  browser/
    index.html
    manifest.json
    index.json
    blobs/<sha256>.json
    assets/jsx6/<hash>/...
    model.json                 # static fallback
```

- `index.json` is small: report identity, stats/link base, and ordered shard hashes.
- `blobs` holds immutable shard content. The entity browser shards by type/FQN.
- `manifest.json` names the current generation and index.
- `catalog.json` lists the reports available to a host.
- `model.json` remains for a page or server that cannot use the live contract.

A page loads the manifest/index/shards, renders, and subscribes to SSE. A changed metadata generation sends
only the shard ids that changed; the page fetches those blobs and keeps its UI state. It does not rebuild or
reload the page.

## Build

For the entity browser:

```sh
bun project-automation/entity-browser/src_build/build.js
```

The default output is `<link-base>/.jcodebuddy/metadata/browser/`. The build also creates the shared
`assets/jsx6/<hash>/` directory and an import map in `index.html`; jsx6 is not re-bundled into `browser.js`.

Metadata-only changes do not require rebuilding page code. Re-run the report's build/pass when the model
changes; the page receives the new generation.

## Serve

### Standalone

```sh
cd <page directory>
bun serve.js
```

The standalone server prints its URL. It provides:

```text
GET /api/v1/metadata/catalog
GET /api/v1/metadata/<report>/index
GET /api/v1/metadata/events?report=<report>
GET /page/...
```

`serve.js` is read-only for reports. Catalog/index responses have `ETag`; blobs are immutable.
If the manifest is absent, the page falls back to `model.json`.

### webviewd

Start `webviewd` for the project (or use an existing running host), then open the page through its
`/page/<token>/...` URL. `webviewd` implements the same catalog/index/SSE contract under
`/api/v1/metadata`, uses the project jail and origin/token rules, and resolves the report directory through
`catalog.json`.

The page's metadata events URL is absolute:

```text
/api/v1/metadata/events?report=<report>
```

## Page contract

A report page uses the shared client:

```js
import { loadMetadata } from './metadata-client.js'

const metadata = await loadMetadata({
  onChange(next) {
    // merge/re-render; preserve selection and search state
  },
})
```

The client:

- loads `manifest.json` and `index.json`;
- fetches every shard on first load;
- opens `EventSource` for the report;
- on `changedShards`, fetches only those blobs;
- on `indexChanged`, reloads the small index;
- falls back to `model.json` when no manifest exists.

A report author should put large, independently identifiable data in a shard-able collection. The current
publisher shards a collection by a stable key (the entity browser uses FQNs). An SSE event never carries a
large unchanged collection.

## Static/offline fallback

`model.json` is still written. A page opened as `file://`, or served without the metadata API, can use the
fallback if its loader supports it; the entity browser reports the fetch failure instead of rendering a
blank page. The old self-contained graph page remains an independent `file://` report until it is migrated.

## Verification

```sh
# entity-browser tests (includes shard/client/static fallback tests)
cd project-automation/entity-browser && bun test

# publisher + SSE + ETag
bun test project-automation/metadata/metadata.test.js

# webviewd metadata API
mvn -o -pl :webviewd -am test \
  '-Dtest=MetadataApiTest' '-Dsurefire.failIfNoSpecifiedTests=false'

# real headless Chrome: model-only change must update without reloading
bun scripts/check-metadata-live-page.js
```

The headless script needs a built page and Chrome/Edge; it skips with exit 0 when either is absent.

## Security and limits

- The host is read-only for reports; it never writes model/manifest/catalog.
- Metadata paths are project-jailed; the catalog is the authority for a report directory.
- Catalog/index reads keep the host's origin/token rules.
- The metadata stream is separate from the existing project file-change stream.
- A host without the metadata files can still serve static pages; live updates are unavailable rather than
  silently stale.

## Migration notes

- Entity browser is migrated.
- The IOC graph now has a live `live.html` built from the same manifest/index/SSE contract; `graph.html`
  remains the self-contained `file://` fallback. The graph still carries its nodditor runtime in its own
  bundle because it is a self-contained graph editor.
- Other reports can adopt `publishReport` and `loadMetadata` without a new server: the on-disk contract and
  event shape are the shared part.
- `model.json` remains the static fallback for a page without the live host.
