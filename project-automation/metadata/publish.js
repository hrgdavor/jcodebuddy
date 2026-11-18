#!/usr/bin/env bun
/**
 * Shared report publisher for served metadata.
 *
 * <p>A report is split into an index plus immutable content-addressed blobs. The index is the only
 * document the page must replace when non-list metadata changes; a large map such as entity types is
 * sharded by a stable key hash, so a changed type invalidates one shard and SSE can name that shard.
 * The build writes model/index/manifest/catalog with temp-file renames; a reader never sees a partial
 * document.</p>
 *
 * <p>This module contains no HTTP server. `serve.js` and the Java `webviewd` adapter both read the same
 * on-disk contract; that keeps one build pipeline and allows the serving process to be chosen at run
 * time.</p>
 */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'

export const SCHEMA_VERSION = 1

/** A small deterministic string hash; FNV-1a is enough for choosing a shard. */
export function shardOf(key, count) {
  let hash = 0x811c9dc5
  for (let index = 0; index < key.length; index += 1) {
    hash ^= key.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash % count
}

export function contentHash(value) {
  return createHash('sha256').update(String(value)).digest('hex')
}

export function json(value) {
  return `${JSON.stringify(value, null, 2)}\n`
}

function writeAtomic(file, text) {
  mkdirSync(dirname(file), { recursive: true })
  const temp = `${file}.tmp`
  writeFileSync(temp, text, 'utf8')
  renameSync(temp, file)
}

function writeBlob(reportDir, text) {
  const hash = contentHash(text)
  const file = join(reportDir, 'blobs', `${hash}.json`)
  if (!existsSync(file)) {
    writeAtomic(file, text)
  }
  return hash
}

/**
 * Publish one report.
 *
 * @param {{
 *   metadataRoot: string,
 *   report: string,
 *   title: string,
 *   model: object,
 *   pageDir: string,
 *   reportDir?: string,
 *   pageEntry?: string,
 *   shardCount?: number,
 *   assets?: string[],
 *   collection?: string,
 *   collectionKey?: string,
 * }} input
 */
export function publishReport({
  metadataRoot,
  report,
  title,
  model,
  pageDir,
  reportDir = join(metadataRoot, report),
  pageEntry = 'index.html',
  shardCount: requestedShardCount,
  assets = [],
  collection = 'types',
  collectionKey = 'id',
}) {
  mkdirSync(join(reportDir, 'blobs'), { recursive: true })

  const source = model[collection]
  let entries
  let order = []
  if (Array.isArray(source)) {
    entries = {}
    for (let index = 0; index < source.length; index += 1) {
      const item = source[index]
      const key = String(item?.[collectionKey] ?? item?.id ?? item?.context ?? index)
      entries[key] = item
      order.push(key)
    }
  } else {
    entries = source && typeof source === 'object' ? source : {}
    order = Object.keys(entries)
  }
  // Keep shards small enough that a three-item change names three buckets, not a thousand entries, without
  // creating a file per tiny report. The cap keeps the index and blob count bounded.
  const shardCount = requestedShardCount
    ?? Math.min(512, Math.max(64, 2 ** Math.ceil(Math.log2(Math.max(1, order.length / 8)))))
  const shards = Array.from({ length: shardCount }, () => ({}))
  for (const [key, value] of Object.entries(entries)) {
    shards[shardOf(key, shardCount)][key] = value
  }
  const shardRefs = shards.map((shard) => writeBlob(reportDir, json(shard)))

  const { [collection]: _values, ...rest } = model
  const index = {
    schemaVersion: SCHEMA_VERSION,
    report,
    title,
    ...rest,
    collection,
    collectionOrder: order,
    shardCount,
    shards: shardRefs,
    generation: '',
  }
  index.generation = contentHash(JSON.stringify({ ...index, generation: '' }))
  const indexText = json(index)
  writeAtomic(join(reportDir, 'index.json'), indexText)
  // Keep the single-file model for static/back-compatible readers. The live page uses the index.
  writeAtomic(join(reportDir, 'model.json'), json(model))

  const manifest = {
    schemaVersion: SCHEMA_VERSION,
    report,
    title,
    generation: index.generation,
    index: 'index.json',
    entry: pageEntry,
    page: relative(metadataRoot, join(pageDir, pageEntry)).split('\\').join('/'),
    assets,
    builtAt: new Date().toISOString(),
  }
  writeAtomic(join(reportDir, 'manifest.json'), json(manifest))

  const catalogFile = join(metadataRoot, 'catalog.json')
  let catalog = { schemaVersion: SCHEMA_VERSION, reports: [] }
  if (existsSync(catalogFile)) {
    try {
      const previous = JSON.parse(readFileSync(catalogFile, 'utf8'))
      if (previous && Array.isArray(previous.reports)) {
        catalog = { ...previous, reports: [...previous.reports] }
      }
    } catch {
      // A corrupt catalog is replaced; the per-report manifests are the source of truth.
    }
  }
  const entry = {
    report,
    title,
    manifest: `${relative(metadataRoot, reportDir).split('\\').join('/')}/manifest.json`,
    index: `${relative(metadataRoot, reportDir).split('\\').join('/')}/index.json`,
    model: `${relative(metadataRoot, reportDir).split('\\').join('/')}/model.json`,
    page: manifest.page,
    entry: pageEntry,
    generation: index.generation,
  }
  catalog.reports = catalog.reports.filter((item) => item.report !== report)
  catalog.reports.push(entry)
  catalog.reports.sort((left, right) => left.report.localeCompare(right.report))
  writeAtomic(catalogFile, json(catalog))

  return { reportDir, index, manifest, catalog }
}
