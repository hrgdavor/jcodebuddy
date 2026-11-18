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
    model.json                 # static fallback
  lib/jsx6/<hash>/             # shared page libraries
```

The host presents a site root, not a report root:

```text
/                         catalog/landing page
/app/                     default authored webview app
/reports/<id>/...         report page + metadata
/source/...               project sources, read-only
/lib/...                  project-automation bundles/shared assets
/api/v1/metadata/...      catalog/index/SSE
```

`/app/` is the default URL a webview should open. The app folder is derived, rebuildable output at
`<project>/.jcodebuddy/app/` (ignored by DEC-026); a webview build copies its runnable app there. The app
may load `/lib/...` and read `/source/...`, and may use the host's editor routes:

- `GET /source/<project-relative-path>` — source text and context;
- `GET /api/v1/open?filePath=...&line=...&column=...` — jump in the editor;
- `POST /api/v1/applyEdit` / `diff` / `undo` / `redo` — guarded editor edits (webviewd);
- OpenRewrite/refactor recipes need a host route and implementation before an app can call them; the app
  should report that capability as unavailable rather than fake it.

### Own server or a project-automation server

The IDE integration API and the project-automation API are different servers even when one URL fronts
both. The choice is committed configuration in `.jcodebuddy/conf/webview.json`:

```json
{
  "port": 18882,
  "customServer": "http://127.0.0.1:9000"
}
```

- without `customServer`, the webview host serves `/app`, `/source`, `/lib`, `/reports` as static files;
- with `customServer`, it still starts its **own editor API port**, opens `/app/` from that port and
  proxies `/app` **and `/api/v1/metadata`** to the JS backend. `/source`, `/lib` and `/reports` remain
  local static namespaces; `/open`, `/health` and the edit routes stay local.

The JS project-automation backend owns `/app` and the metadata catalog/index/SSE transport. It may also
serve `/source`, `/lib` and `/reports` for a standalone editor (the `project-automation/metadata` host is
the reference), but a webview does not require it to duplicate those static namespaces.

The page is same-origin with both because the host is the URL the webview opens.

An authored UI can be an elaborate application over these namespaces without becoming a report root.
The exact `/app/?openFile=<project-relative>` URL, the `/source` read it triggers, and the reserved future
query keys are in [`webview-app-contract.md`](../webview/doc/webview-app-contract.md).
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

The default output is `<link-base>/.jcodebuddy/metadata/browser/`. The build also creates shared jsx6
assets under `<link-base>/.jcodebuddy/metadata/lib/jsx6/<hash>/` and an import map in `index.html`; jsx6 is
not re-bundled into `browser.js`. The pages are served under `/reports/<id>/`, the shared assets under
`/lib/`, and the default app under `/app/`.

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

`webviewd` is the static webview host and editor bridge. It serves `/source`, `/lib`, `/reports` and
`/app` from the project, but it does **not** implement the metadata catalog/index/SSE transport: that is
the JS project-automation backend's job. Run the JS server (or point `.jcodebuddy/conf/webview.json` at
it via `customServer`) when live metadata is needed. Without it, a page still renders from its static
files/model.

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

`model.json` is still written. A page opened as `file://`, or served without the metadata transport,
uses the static model. There is deliberately no polling loop: a reader refreshes the page when they want
the current metadata. The client sends `cache: 'no-store'`, and the host marks report HTML/JSON
`Cache-Control: no-store`, so a manual refresh cannot be answered from a stale cache.

## Verification

```sh
# entity-browser tests (includes shard/client/static fallback tests)
cd project-automation/entity-browser && bun test

# publisher + SSE + ETag
bun test project-automation/metadata/metadata.test.js

# webviewd static namespaces (metadata transport is not here)
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
