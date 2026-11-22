#!/usr/bin/env bun
// Guard the Java-source rule: production code parses Java through OpenRewrite, never JavaParser.
//
// The migration history is allowed to mention JavaParser; production sources and POMs are not. This
// check is deliberately narrow: an import of `com.github.javaparser` or a POM coordinate for it. It
// does not try to judge text emission — DEC-030 requires SourceSplicer rather than reprinting a tree.
import { readFileSync } from 'node:fs'
import { listFiles } from './lib/file-walk/index.js'
import { reportDuration, stopwatch } from './lib/timing.js'

const elapsed = stopwatch()
const root = process.cwd()
const violations = []
let checked = 0

function productionPath(path) {
  const normalized = path.replaceAll('\\', '/')
  if (normalized.startsWith('doc/') || normalized.startsWith('plans/') || normalized.startsWith('archive/')) {
    return false
  }
  return normalized.endsWith('.java') || normalized.endsWith('/pom.xml') || normalized === 'pom.xml'
}

listFiles('.', {
  visit(path) {
    if (!productionPath(path)) return
    checked++
    const text = readFileSync(path, 'utf8')
    const lines = text.split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      if (/^\s*import\s+com\.github\.javaparser\b/.test(line)) {
        violations.push(`${path}:${i + 1}: JavaParser import`)
      }
      if (path.endsWith('pom.xml') && /<groupId>\s*com\.github\.javaparser\s*<\/groupId>/.test(line)) {
        violations.push(`${path}:${i + 1}: JavaParser dependency`)
      }
    }
  },
})

if (violations.length > 0) {
  console.error('JavaParser is banned from production Java; parse through OpenRewrite SourceReader/TreeQueries.')
  for (const violation of violations) console.error(`  ${violation}`)
  console.error(reportDuration(elapsed(), { what: 'JavaParser check failed', files: checked }))
  process.exit(1)
}

console.log('No JavaParser imports or dependencies in production Java.')
console.log(reportDuration(elapsed(), { what: 'JavaParser check passed', files: checked }))
