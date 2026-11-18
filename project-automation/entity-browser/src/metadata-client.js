/**
 * The browser half of the served-metadata contract.
 *
 * <p>The build publishes `manifest.json`, a small `index.json`, and immutable shard blobs. This client
 * loads the index plus all shards, then subscribes to the metadata SSE endpoint. A change event names
 * only the shards that changed, so a large type map is never transferred again. If there is no
 * manifest (an old static page directory), it falls back to the single `model.json` and does not
 * subscribe.</p>
 *
 * <p>There is deliberately no polling loop. A host without a metadata transport is refreshed by the
 * reader; every fetch is `no-store`, and the host marks manifest/index/page responses no-store so a
 * manual refresh cannot be served from a stale cache.</p>
 */

async function getJson(url) {
  const response = await fetch(url, { cache: 'no-store' })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${url}`)
  }
  return await response.json()
}

function shardUrl(model, hash) {
  return new URL(`./blobs/${hash}.json`, document.baseURI).toString()
}

async function loadShard(model, hash) {
  return await getJson(shardUrl(model, hash))
}

function mergeShard(model, shardKeys, index, shard, collection) {
  const current = shardKeys.get(index) ?? new Set()
  const next = new Set(Object.keys(shard))
  const values = model[collection]
  for (const key of current) {
    if (!next.has(key)) {
      delete values[key]
    }
  }
  for (const [key, value] of Object.entries(shard)) {
    values[key] = value
  }
  shardKeys.set(index, next)
}

async function loadFromIndex(manifest, index, collection) {
  const model = { ...index, [collection]: {} }
  const shards = await Promise.all((index.shards ?? []).map((hash) => loadShard(model, hash)))
  const shardKeys = new Map()
  shards.forEach((shard, index) => mergeShard(model, shardKeys, index, shard, collection))
  model.report = manifest.report
  model.generation = manifest.generation
  return { model, shardKeys, live: typeof EventSource !== 'undefined' }
}

/**
 * @param {{onChange?: (model: object) => void, collection?: string}} [options]
 * @returns {Promise<{model: object, live: boolean, close: () => void}>}
 */
export async function loadMetadata({ onChange, collection = 'types' } = {}) {
  let manifest
  try {
    manifest = await getJson('./manifest.json')
  } catch {
    return { model: await getJson('./model.json'), live: false, close() {} }
  }
  let index = await getJson('./index.json')
  let { model, shardKeys, live } = await loadFromIndex(manifest, index, collection)
  let closed = false
  let source = null
  let currentGeneration = manifest.generation

  const apply = (next) => {
    if (closed) {
      return
    }
    model = next
    onChange?.(next)
  }

  if (live) {
    source = new EventSource(`/api/v1/metadata/events?report=${encodeURIComponent(manifest.report)}`)
    source.addEventListener('metadata', async (event) => {
      let change
      try {
        change = JSON.parse(event.data)
      } catch {
        return
      }
      if (!change || change.generation === currentGeneration) {
        return
      }
      try {
        if (change.indexChanged) {
          index = await getJson('./index.json')
          model = { ...index, [collection]: model[collection] }
        }
        const changed = Array.isArray(change.changedShards) ? change.changedShards : []
        for (const shardIndex of changed) {
          const hash = index.shards?.[shardIndex]
          if (hash) {
            mergeShard(model, shardKeys, shardIndex, await loadShard(model, hash), collection)
          }
        }
        model.report = change.report
        model.generation = change.generation
        currentGeneration = change.generation
        apply(model)
      } catch {
        // Keep the current model; EventSource will reconnect, and a later event can recover.
      }
    })
  }

  return {
    model,
    live,
    close() {
      closed = true
      source?.close()
    },
  }
}
