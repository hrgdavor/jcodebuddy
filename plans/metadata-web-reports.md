# Serving metadata reports live

> **Status: M1–M5 complete — 2026-11-17.** The standalone/Bun + webviewd vertical slice, ETag reads,
> targeted SSE, live entity-browser page and shared jsx6 asset are in place. The IOC-graph second
> consumer (M6) remains.
>
> **Scope.** JCodeBuddy's generated HTML reports become **served, interactive metadata views**:
> the report shell is built once, the metadata model is fetched at runtime, and a recompile that
> changes metadata pushes an SSE event so the open page updates **without regenerating the page**.
> The first consumer is `project-automation/entity-browser`; `hipster-ioc`'s graph is the second.
>
> **Not in scope.** A general-purpose metadata query language, editing metadata from the browser,
> WebSockets, or a second visual design. This is the serving/reload contract and the build split that
> makes it useful.
>
> **Accepted decisions (2026-11-17).**
>
> 1. **Build pipelines are Bun JavaScript.** Report builds write the catalog, manifest, index, blobs
>    and shared assets into the proper metadata folder. The **web server may be implemented twice**
>    (Bun standalone and Java `webviewd`); the wire contract and conformance fixtures are shared, not
>    the server source. A host is selected at run time.
> 2. **Large metadata is split into identifiable, content-addressed blobs.** A shard key can be a
>    type/FQN, a record id, or a deterministic hash bucket. SSE carries changed/removed keys or shard
>    ids; a page fetches only those blobs, never the whole model.
> 3. **jsx6 is a content-addressed asset under the serving root** (`assets/jsx6/<hash>/...`), external
>    to report bundles. Pages reuse it and only rebuild the page-specific code.
> 4. **The first delivery is M1–M5** for `entity-browser`; the IOC graph follows as the second
>    consumer.
>
> **Implementation progress (2026-11-17).** The M1–M5 vertical slice is in place:
> `project-automation/metadata/publish.js` writes catalog/manifest/index/blobs atomically,
> `server.js` serves the standalone read API and targeted SSE, `webviewd` implements the same read
> API/SSE contract with catalog-path resolution, the entity-browser page loads index+shards and
> live-updates changed shards (`metadata-client.test.js` covers the partial update), and jsx6 is built
> once under `assets/jsx6/<hash>/` with an import map. `ETag`/`304` is implemented for the metadata
> catalog/index in both hosts. The headless live-page gate is in
> [`scripts/check-metadata-live-page.js`](../scripts/check-metadata-live-page.js). The user guide is
> [`doc/metadata-report-hosting.md`](../doc/metadata-report-hosting.md). The IOC graph remains an acceptable
> static report; migrating it is optional follow-up.

## 1. The decision this plan implements

User-facing hosting, update, fallback and verification instructions are in
[`doc/metadata-report-hosting.md`](../doc/metadata-report-hosting.md).

A metadata report has two independent lifecycles:

| piece                                             | changes when                                | delivery                                |
| ------------------------------------------------- | ------------------------------------------- | --------------------------------------- |
| page shell and bundle (`index.html`, `report.js`) | the page source or its libraries change     | static asset, cached by content hash    |
| metadata model (`model.json`, catalog, manifest)  | a generator/recompile pass changes metadata | fetched at runtime; live-updated by SSE |

The **page never has to be regenerated to show new metadata**. A rebuild writes a new model revision;
the server announces that revision; the page fetches it and re-renders while preserving its UI state.

The metadata host is a **read-only reader** of report metadata; the build is the publisher. Two host
implementations are allowed (Bun standalone and Java `webviewd`) because they are selected at run time,
but they share the same on-disk contract and event shape.

1. **`webviewd`** (and the JetBrains WebView Explorer bridge) is the normal host. It already owns
   page serving, project jailing, tokens, `/api/v1/events` SSE and the fs-meta dependency.
2. **`entity-browser/serve.js`** is the standalone development fallback. It serves a page directory
   with Bun alone; it gains the same read-only metadata routes and a polling-backed SSE fallback.

This keeps the existing rule that a page is usable without a webview: a standalone page directory is
still runnable, and a static `model.json` remains the fallback when no live server is available.

## 2. Current baseline, and the gaps

The entity browser is the first vertical slice and has the right shape to build on:

- [`project-automation/entity-browser/src_build/build.js`](../project-automation/entity-browser/src_build/build.js)
  renders `model.json` from `.jcodebuddy/index/classes.json` and bundles the jsx6 page.
- [`project-automation/entity-browser/src/index.jsx`](../project-automation/entity-browser/src/index.jsx)
  already fetches `./model.json` at runtime, not at build time.
- [`project-automation/entity-browser/src_build/serve.js`](../project-automation/entity-browser/src_build/serve.js)
  is a loopback static server with a path jail.
- `webviewd` already serves pages under `/page/` and streams fs-meta changes under `/api/v1/events`.
- The jsx6 build aliases the local `.jsx6` checkout and **bundles all of jsx6 into each page**;
  every page build therefore re-bundles the libraries.

Gaps:

1. No metadata catalog or report manifest; a client can only fetch one hard-coded `model.json`.
2. No model revision identity (`ETag`/generation) and no atomic publish contract.
3. No metadata SSE event; the existing file-change stream says “a path changed”, not “this report’s
   model is now revision X”.
4. No page-side live-update runtime; reloading the model currently means a browser refresh.
5. jsx6 is bundled per page, so a library change rebuilds every page.
6. The standalone server has no metadata API and no live-update path.

## 3. Target architecture

```text
generator / recompile pass
        │
        │ writes model.json + manifest.json atomically
        ▼
<project>/.jcodebuddy/metadata/
  catalog.json
  entity-browser/
    manifest.json          ← report id, schema, generation, model hash
    model.json             ← replaceable metadata revision
    index.html             ← static page shell
    report.js              ← page-specific bundle (jsx6 externalized)
        │
        │ fs-meta sees the manifest/model change (or standalone serves polls)
        ▼
metadata host (webviewd / standalone serve.js)
  GET /api/v1/metadata/catalog
  GET /api/v1/metadata/events        ← SSE
  GET /api/v1/metadata/<report>/model
  GET /page/...                      ← existing page serving
        │
        ▼
browser page
  fetch catalog/model → render
  EventSource → on metadata event fetch new model → re-render, keep UI state
```

The metadata host is **read-only** for reports. It never writes `.jcodebuddy/metadata`; generators
own writes, and an atomic write is the publication boundary.

## 4. Contracts

### 4.1 Layout and atomic publication

A build writes into a temporary sibling and renames:

```text
.jcodebuddy/metadata/<report>/manifest.json.tmp   → manifest.json
.jcodebuddy/metadata/<report>/model.json.tmp      → model.json
```

`manifest.json` is small and written **after** the model; a reader that sees a new manifest can trust
the model it names. Minimum fields:

```json
{
  "schemaVersion": 1,
  "report": "entity-browser",
  "title": "Entity browser",
  "generation": "sha256:<model-hash>",
  "model": "model.json",
  "entry": "index.html",
  "builtAt": "2026-11-17T20:00:00Z"
}
```

`generation` is content-derived, not a wall clock. Two builds with identical metadata produce the
same generation and should not emit a change event.

`catalog.json` lists available reports and their entry/model URLs. It is derived and ignored under
DEC-026; it is never a committed artifact.

### 4.2 HTTP read API

Under the host’s existing origin/token rules:

| route                                    | meaning                                        |
| ---------------------------------------- | ---------------------------------------------- |
| `GET /api/v1/metadata/catalog`           | the report catalog                             |
| `GET /api/v1/metadata/<report>/index`    | the small index (shard hashes and fixed data)  |
| `GET /page/...`                          | the page, `index.json` and immutable blobs     |
| `GET /api/v1/metadata/events`            | metadata SSE stream                            |

- A conditional `If-None-Match` returns `304`; the page keeps its current model.
- Unknown reports are `404`; a malformed/traversal path is `400`/`404`, never a filesystem read.
- The host serves only paths inside the project and only catalogued reports.
- No report writes are exposed. The existing `/api/v1/applyEdit` write routes remain unrelated.

### 4.3 SSE contract

```json
{
  "report": "entity-browser",
  "generation": "sha256:9f2c...",
  "changedShards": [7, 19],
  "indexChanged": false
}
```

`changedShards` are indexes into `index.shards`; a page fetches only those blobs. `indexChanged` asks
it to reload the small index for stats/title changes. A large unchanged map is never transferred.

- One event per **coalesced metadata publication**, not one per file write. A short server-side
  debounce (proposed 75–150 ms) turns a multi-file generator write into one event.
- `id` is the generation; a reconnect sends `Last-Event-ID`, and the server replays the latest event
  if the client missed it. Generation equality means no fetch is necessary.
- If the stream cannot attach (no fs-meta daemon), the route answers `503` with the same actionable
  setup text as `/api/v1/events`; the page falls back to polling the manifest.
- Keep `GET /api/v1/events` unchanged for ordinary file changes. A separate metadata stream avoids
  making every page interpret project-wide paths.

### 4.4 Page runtime

A small shared client (proposed `metadata-client.js`) provides:

- initial `manifest` → `index` → shard load;
- `EventSource` subscription with reconnect and `Last-Event-ID`;
- conditional fetches and abort of stale fetches;
- a `state` update callback so a page keeps search text, selected record, scroll position and
  drill-down state;
- an explicit `stale`/`error` status instead of silently showing an old model.

The entity browser adopts this client first. The page remains a normal jsx6 page; it does not gain a
second rendering path.

### 4.5 Shared jsx6 library

The build must stop putting jsx6 into every page bundle:

1. Build jsx6 once into a content-addressed asset, e.g.
   `.jcodebuddy/metadata/browser/assets/jsx6/<hash>/jsx6.js` (plus the JSX runtime).
2. The report build marks `@jsx6/jsx6` and `@jsx6/jsx-runtime` as external and writes an import map
   into `index.html`, for example:
   ```html
   <script type="importmap">
     {"imports":{"@jsx6/jsx6":"./assets/jsx6/<hash>/jsx6.js",
                 "@jsx6/jsx-runtime":"./assets/jsx6/<hash>/jsx-runtime.js"}}
   </script>
   ```
3. The host serves the assets with immutable cache headers. A second report reuses the same hash.
4. A jsx6 source change rebuilds only the shared asset and invalidates by hash; a report change
   rebuilds only that report. The build reports which of the two it did.

If import maps are not available in the target browser, the fallback is a tiny loader that resolves
the same hashed module URLs; the plan does not add a second bundler.

## 5. Implementation phases

Each phase is a gated delivery, not a checklist of ideas. **Sizes are estimates.**

### M0 — Decisions and inventory (S)

Settle the open decisions in § 7 and write the metadata schema/version. Inventory the reports that
will use the contract (entity-browser first, IOC graph second, entity reference page later).

**Gate:** the plan’s open decisions have accepted answers; no code.

### M1 — Publish a catalog and atomic model (M)

- Add a small metadata publish helper used by the entity-browser build (and later other reports):
  write model temp → model; manifest temp → manifest; catalog temp → catalog.
- Add `generation` (content hash), `schemaVersion`, and report identity.
- Keep the existing static `model.json` path working for the standalone fallback.

**Gate:** a test builds twice with unchanged metadata and proves the generation and model bytes are
identical; an interrupted temp file is ignored; a changed model produces a new generation.

### M2 — Read API in the host (M)

- Add catalog/manifest/model routes to `webviewd`, reusing `PageServer`’s project jail and the
  existing token/origin rules.
- Add `ETag`/`If-None-Match` and `304`.
- Add the same read routes to `serve.js` (the standalone subset).

**Gate:** Bun tests start a host, fetch catalog/model, assert `304` on the matching `ETag`, and assert
traversal/unknown-report refusal. The same assertions run against `serve.js`.

### M3 — Metadata SSE and publisher (M)

- Add a metadata stream that watches `.jcodebuddy/metadata/**/manifest.json` through fs-meta (or the
  host’s existing file-change stream filtered to that subtree), coalesces writes, and publishes
  generation events.
- Add `Last-Event-ID` replay and a bounded recent-event ring.
- Add the standalone polling fallback (manifest `ETag`, no fs-meta required).

**Gate:** a test writes a new model atomically, receives exactly one event with the new generation,
reconnects with `Last-Event-ID` and receives the missed event; unchanged bytes produce no event; no
daemon produces `503` in `webviewd` and polling in `serve.js`.

### M4 — Page-side live updates (M)

- Add the shared metadata client and adopt it in entity-browser.
- Preserve selection/search and show a non-blocking “metadata updated” transition.
- Keep the existing static fetch as a no-SSE fallback.

**Gate:** a headless browser test (the existing CDP smoke-test style) opens the page, changes the
model on disk, observes new content without a full page reload, and asserts the selected record is
preserved. A stale-generation fetch is discarded.

### M5 — Shared jsx6 build (M)

- Build the hashed jsx6 asset once; externalize it from report bundles; write the import map.
- Reuse the asset when the hash is unchanged; rebuild it only when its inputs change.
- Report both decisions in the build output and the metadata manifest.

**Gate:** two page builds with an unchanged jsx6 hash do not rebuild jsx6; changing one page source
rebuilds only that page; changing jsx6 rebuilds the shared asset once. The built page still loads with
no `node_modules` and only same-origin assets.

### M6 — Rollout and fallback (M)

- Make entity-browser the default live consumer.
- **The IOC graph may remain a static `file://` page.** Dynamic is preferred, but a generated report that
  is already self-contained is acceptable (maintainer, 2026-11-18); migrating it is optional follow-up, not
  a gate for the entity-browser delivery.
- If migrated, reuse the same catalog, SSE client and shared jsx6 asset rather than inventing a second
  runtime.
- Document the standalone `serve.js` path and the `file://` limitation honestly.
- Leave old static reports working; migration is additive.

**Gate:** entity-browser has a catalog entry and updates live; a page with no SSE still renders the last
static model; the docs name which host serves which route. The IOC graph's status is stated, not implied.

### M7 — Verification and cleanup (S)

- Integrate the tests into the Maven/Bun lanes used by the report tools.
- Add a no-rebuild assertion: changing only metadata does not touch `index.html`/`report.js` mtimes.
- Remove duplicate per-page jsx6 bundling once both consumers use the shared asset.

**Gate:** the recorded gate is green for touched modules; the no-rebuild assertion is part of the
test suite; the plan’s status header records what shipped.

## 6. Acceptance criteria

The direction is complete when all of these are true:

1. `GET /api/v1/metadata/catalog` lists at least entity-browser and the IOC graph.
2. A metadata-only change produces a new generation and one SSE event, and **no page rebuild**.
3. An open page updates its model without a full browser reload and keeps its UI state.
4. A conditional fetch returns `304` for an unchanged generation.
5. A reconnect with `Last-Event-ID` does not lose the latest publication.
6. `webviewd` remains token/project-jailed; no metadata write route exists.
7. `serve.js` can serve the same page and model without fs-meta; it uses polling when SSE cannot attach.
8. jsx6 is built once per hash and reused across reports; page builds do not bundle it again.
9. Existing static page directories still work as a fallback.
10. Tests cover catalog, model `ETag`, SSE coalescing/replay, page live update, and shared-library reuse.

## 7. Open decisions

These are the decisions M0 must settle; the plan does not pretend they are already made.

| id   | question                                               | proposed answer                                                                            |
| ---- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| M-D1 | Which host owns the metadata API?                      | `webviewd` is canonical; `serve.js` implements the read-only subset                        |
| M-D2 | Is the metadata stream separate from `/api/v1/events`? | Yes, a separate `/api/v1/metadata/events`; file changes stay file changes                  |
| M-D3 | Is a token required for metadata reads?                | Same rule as page serving; no broader CORS or anonymous reads                              |
| M-D4 | How is generation defined?                             | Content hash of the model + schema/report identity; never a clock                          |
| M-D5 | import map or loader for jsx6?                         | Prefer import map; keep a loader fallback if the supported browsers need it                |
| M-D6 | Does the page ship a bundled jsx6 fallback?            | Not in the normal served path; the standalone fallback may carry a pinned copy if required |
| M-D7 | Who publishes the catalog?                             | The report build; the host only reads and validates it                                     |

## 8. Risks and limits

- **fs-meta latency and debounce.** The event is only as fast as the daemon’s batch; the page must
  tolerate a short delay and must not assume an event per file.
- **Atomicity across three files.** Model before manifest before catalog is the publication order;
  readers must tolerate a new model with an old manifest during the tiny gap and never render the
  reverse.
- **Browser import-map support.** If the supported browser lacks it, the loader fallback must be
  tested in the same headless lane.
- **Cache invalidation.** Content-addressed assets are immutable; `index.html` itself must not be
  cached aggressively, or a changed import map will not be seen.
- **Two hosts must not drift.** The catalog/model/event rules live in one shared implementation
  where possible; `serve.js` is a subset, not a second semantic.
- **Security.** Metadata can name source paths. The same project jail and token rule applies; a
  report must never cause a read outside the project.
- **No live update without a host.** `file://` and a static server without SSE still show the model
  at load; the docs must say so rather than implying live updates everywhere.

## 9. Files and owners

| area                   | current file / proposed owner                                                                       |
| ---------------------- | --------------------------------------------------------------------------------------------------- |
| first consumer         | [`project-automation/entity-browser`](../project-automation/entity-browser/README.md)               |
| static fallback server | [`src_build/serve.js`](../project-automation/entity-browser/src_build/serve.js)                     |
| report build           | [`src_build/build.js`](../project-automation/entity-browser/src_build/build.js)                     |
| page runtime           | [`src/index.jsx`](../project-automation/entity-browser/src/index.jsx), new shared `metadata-client` |
| host API/SSE           | `webview/core/webviewd`, sharing `PageServer` and the existing fs-meta client                       |
| page serving           | existing `PageServer` / `/page/` routes                                                             |
| jsx6 checkout          | `.jsx6/` or `JCODEBUDDY_JSX6_DIR` (AGENTS.md § 2)                                                   |

## 10. Definition of done

The plan closes only when a second report consumes the same catalog, SSE client and shared jsx6
asset, and a metadata-only change is proven to update an open page without a page rebuild. A
single-report prototype that still bundles jsx6 per page is a milestone, not completion.

## 11. Registration

This is a live plan once M0 starts. Until then it is a draft: add its gated steps to
[`unified-plan.md`](unified-plan.md) when the first decision is accepted, and keep the status banner
above dated.
