#!/usr/bin/env bun
/**
 * The merge-java module's classpath, from Maven, with a cache that cannot go stale.
 *
 * `dependency:build-classpath` takes seconds and its answer only changes when the POM does, so both scripts here
 * cache it — but the first version of that cache keyed on nothing, and reused an answer from before Jackson was
 * added, which failed at run time with `NoClassDefFoundError: tools/jackson/databind/ObjectMapper`. A dependency
 * cache that outlives the dependency's declaration is worse than no cache: the error it produces points at the
 * consumer, not at the file that is out of date.
 *
 * So the cache is keyed by the POM's own sha256. Change the POM and the next run resolves again.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { run } from '../../../scripts/lib/toolchain.js'

/**
 * @returns {{ entries: string, fromCache: boolean }} the classpath entries, as Maven wrote them
 */
export function moduleClasspath({ moduleRoot, scratchDir, maven, env, cwd }) {
  const pom = join(moduleRoot, 'pom.xml')
  const cacheFile = join(scratchDir, 'classpath.txt')
  const stampFile = join(scratchDir, 'classpath.stamp')
  const stamp = createHash('sha256').update(readFileSync(pom)).digest('hex')

  const cached = existsSync(cacheFile) && existsSync(stampFile)
    && readFileSync(stampFile, 'utf8').trim() === stamp
  if (cached) {
    return { entries: readFileSync(cacheFile, 'utf8').trim(), fromCache: true }
  }

  const resolved = run(maven.command, [
    '-o', '-q', '-f', pom, 'dependency:build-classpath', `-Dmdep.outputFile=${cacheFile}`,
  ], { env, cwd, stdio: 'pipe' })
  if (resolved.status !== 0) {
    throw new Error(
      `could not resolve the module classpath (exit ${resolved.status})\n${resolved.stderr || resolved.stdout}`,
    )
  }
  writeFileSync(stampFile, `${stamp}\n`)
  return { entries: readFileSync(cacheFile, 'utf8').trim(), fromCache: false }
}
