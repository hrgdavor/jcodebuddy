#!/usr/bin/env bun
/**
 * The join's own tests: plan step 3.11a and
 * [DEC-049](../../../doc-hipster-entity/architecture/decisions/DEC-049.md) decisions 2–4.
 *
 * They are separate from `graph.test.js` because the join is a step of its own — a name becomes a place, or
 * it is reported — and the assertions that matter are about the **real** class index in this checkout and the
 * **real** file it names, not about a fixture that agrees with the reader.
 *
 * Run: `bun run hipster-ioc/hipster-ioc-graph/src/locations.test.js`.
 */
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

import { INDEX_FILE, MODULE_DIR, REPO, readContexts, readLocations } from './build.js'
import { classRows, joinLocations, locationOf, projectPath } from './locations.js'
import { canNavigate, locationAttribute, locationOfElement, openLocation } from './navigate.js'

let passed = 0
let failed = 0

function check(name, fn) {
  try {
    fn()
    passed++
    console.log(`ok       ${name}`)
  } catch (failure) {
    failed++
    console.log(`FAIL     ${name}`)
    console.log(`         ${failure.message.split('\n').join('\n         ')}`)
  }
}

const INDEX = JSON.parse(readFileSync(INDEX_FILE, 'utf8'))
const PROJECT = REPO

/* ------------------------------------------------------------------ the path rule */

check('a joined path is the module directory plus the index row, forward-slashed', () => {
  assert.equal(projectPath('hipster-ioc/hipster-ioc-test', 'src/test/java/A.java'),
    'hipster-ioc/hipster-ioc-test/src/test/java/A.java')
  assert.equal(projectPath('', 'src/A.java'), 'src/A.java', 'a module at the project root adds nothing')
  assert.equal(projectPath('hipster-ioc/hipster-ioc-test', 'src\\test\\java\\A.java'),
    'hipster-ioc/hipster-ioc-test/src/test/java/A.java', 'a Windows separator from the index is normalised')
  assert.equal(projectPath('m', null), null, 'no file path is no location')
})

check('an index format this reader does not know is refused, not half-read', () => {
  assert.throws(() => classRows({ format: 2, classes: {} }), /format 2/)
  assert.throws(() => classRows({}), /format undefined/)
  assert.throws(() => classRows({ format: 1 }), /no classes object/)
  assert.deepEqual(classRows({ format: 1, classes: {} }), {})
})

/* ------------------------------------------------------------------ the real join */

check('the real class index resolves the example context and its implementation to real files', () => {
  const joined = readLocations(readContexts(), { project: PROJECT })
  assert.equal(joined.moduleDir, 'hipster-ioc/hipster-ioc-test')

  const context = joined.locations['hr.hrg.hipster.ioc.test.CtxMain']
  assert.ok(context, 'the context must resolve — it is a class the module compiles')
  assert.equal(context.path, 'hipster-ioc/hipster-ioc-test/src/test/java/hr/hrg/hipster/ioc/test/CtxMain.java')
  assert.equal(context.line, 7)

  const implementation = joined.locations['hr.hrg.hipster.ioc.test.CtxMainImpl']
  assert.equal(implementation.path.split('/').pop(), 'CtxMainImpl.java')
  assert.equal(implementation.line, 12)
})

check('the joined path names the line that carries the declaration, on disk', () => {
  const joined = readLocations(readContexts(), { project: PROJECT })
  for (const [fqn, at] of Object.entries(joined.locations)) {
    const file = join(PROJECT, at.path)
    assert.ok(existsSync(file), `${fqn} points at ${at.path}, which does not exist`)
    const lines = readFileSync(file, 'utf8').split(/\r?\n/)
    const declared = lines[at.line - 1] ?? ''
    assert.ok(declared.includes(fqn.split('.').pop()),
      `${fqn} points at line ${at.line} of ${at.path}, which is "${declared.trim()}"`)
  }
})

check('a simple type name is reported, never guessed (the ObjectMapper case)', () => {
  const joined = readLocations(readContexts(), { project: PROJECT })
  // The generator's `contexts.json` records a bean's type as the simple name written in the context's own
  // source, and the class index is keyed by FQN — so the bean is named and not navigable. DEC-049 decision 4:
  // the page reports it; the FQN belongs in the generator's projection, not in a guess here.
  assert.deepEqual(joined.locationless, ['ObjectMapper (bean mapper of hr.hrg.hipster.ioc.test.CtxMain)'])
  assert.equal(joined.locations.ObjectMapper, undefined, 'a simple name must not be matched by its tail')
})

check('the join writes the location onto the context and bean the model reads', () => {
  const joined = readLocations(readContexts(), { project: PROJECT })
  const context = joined.contexts.contexts[0]
  assert.equal(context.location.path.split('/').pop(), 'CtxMain.java')
  assert.equal(context.implementationLocation.line, 12)
  assert.equal(context.beans[0].location, null, 'the unresolved bean carries null rather than a guess')
})

check('a repeated unresolved name is one absence, with the first owner that asked', () => {
  // The synthetic index resolves `a.Ctx`/`b.Ctx` (a context is a class, so a real index would have it) and
  // deliberately does not know the bean type `Shared`: the assertion is about the *deduplication* of the
  // second report, which is what stops one unknown type from filling the page's report once per bean.
  const rows = {
    'a.Ctx': { path: 'src/a/Ctx.java', line: 3 },
    'b.Ctx': { path: 'src/b/Ctx.java', line: 4 },
  }
  const contexts = { contexts: [
    { context: 'a.Ctx', beans: [{ name: 'first', type: 'Shared' }] },
    { context: 'b.Ctx', beans: [{ name: 'second', type: 'Shared' }] },
  ] }
  const joined = joinLocations(contexts, rows)
  assert.deepEqual(joined.locationless, ['Shared (bean first of a.Ctx)'])
  assert.equal(joined.locations['a.Ctx'].path, 'src/a/Ctx.java')
})

check('a context that is not in the index is reported like any other name', () => {
  // A context is a class the module compiles, so this is the shape a hand-written test fixture has — and it is
  // reported rather than silently rendered without a target.
  const joined = joinLocations({ contexts: [{ context: 'a.Ctx' }] }, {})
  assert.deepEqual(joined.locationless, ['a.Ctx (context of a.Ctx)'])
  assert.deepEqual(joined.locations, {})
})

check('a context with no implementation reports nothing for it', () => {
  const rows = { 'a.Ctx': { path: 'src/a/Ctx.java', line: 3 } }
  const joined = joinLocations({ contexts: [{ context: 'a.Ctx' }] }, rows)
  assert.deepEqual(joined.locationless, [], 'an absent fact is not a missing one')
  assert.deepEqual(Object.keys(joined.locations), ['a.Ctx'])
})

check('the build refuses to join without knowing the project root', () => {
  // A module-relative path is refused by every host's jail as "not a usable path", so a build that cannot say
  // where the project is stops rather than writing links that cannot work (DEC-049 decision 2).
  assert.throws(() => readLocations(readContexts(), {}), /--project/)
})

check('a missing class index is a named failure, not an empty page', () => {
  assert.throws(() => readLocations(readContexts(), { project: PROJECT, indexPath: join(MODULE_DIR, 'no-such-index.json') }),
    /no class index/)
})

check('locationOf answers null for a name the index does not have', () => {
  const rows = { 'a.b.C': { path: 'src/C.java', line: 3 } }
  assert.deepEqual(locationOf(rows, 'm', 'a.b.C'), { path: 'm/src/C.java', line: 3 })
  assert.equal(locationOf(rows, 'm', 'a.b.Missing'), null)
  assert.equal(locationOf(rows, 'm', undefined), null)
})

/* ------------------------------------------------------------------ the page's call */

check('the page navigates only through the frozen window.openFile, and says whether it did', () => {
  const calls = []
  const scope = { openFile: (path, line, column) => calls.push([path, line, column]) }
  assert.equal(canNavigate(scope), true)
  assert.equal(openLocation({ path: 'm/src/C.java', line: 3 }, scope), true)
  assert.deepEqual(calls, [['m/src/C.java', 3, 1]], 'the third argument is the contract column, and 1 is its default')
  assert.equal(openLocation({ path: 'm/src/C.java', line: 0 }, scope), true)
  assert.deepEqual(calls[1], ['m/src/C.java', 1, 1], 'a line the join did not know is the contract default')
})

check('a page with no host is inert rather than broken (the file:// case)', () => {
  assert.equal(canNavigate({}), false)
  assert.equal(canNavigate(undefined), false)
  assert.equal(openLocation({ path: 'm/src/C.java', line: 3 }, {}), false)
  assert.equal(openLocation(null, { openFile() { throw new Error('must not be called') } }), false)
})

check('a location survives on an element, and a malformed one is null rather than a crash', () => {
  const attribute = locationAttribute({ path: 'm/src/C.java', line: 3 })
  assert.deepEqual(JSON.parse(attribute), { path: 'm/src/C.java', line: 3 })
  assert.equal(locationAttribute(null), null)
  assert.equal(locationAttribute({ line: 3 }), null, 'a location with no path is not a location')
  assert.deepEqual(locationOfElement({ getAttribute: () => attribute }), { path: 'm/src/C.java', line: 3 })
  assert.equal(locationOfElement({ getAttribute: () => 'not json' }), null)
  assert.equal(locationOfElement({ getAttribute: () => null }), null)
  assert.equal(locationOfElement(null), null)
})

console.log(`\n${passed} passed, ${failed} failed`)
process.exit(failed === 0 ? 0 : 1)
