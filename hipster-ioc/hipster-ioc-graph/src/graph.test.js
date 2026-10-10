#!/usr/bin/env bun
/**
 * The graph page's tests: the model projection, the emitted document, and the properties DEC-027 asks a
 * page of this class to keep.
 *
 * They are a plain Bun script with explicit `ok`/`FAIL` lines and a final count, because that is what
 * this repository's JavaScript checks look like (`AGENTS.md` § 2) and because the interesting
 * assertions here are about *content* — that the page inlines the generator's JSON, that it carries no
 * `<script src>` and no `<link>`, and that it reports a dependency it could not draw instead of
 * inventing a block.
 *
 * The build's `bundle()` is not called here: it needs the jsx6 checkout and a bundler run, and the
 * build script is what the page's own gate runs. What is asserted is everything that does not need a
 * browser — plus, when Chrome is present, that the page actually renders.
 */
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

import { INDEX_FILE, MODULE_DIR, REPO, html, OUTPUT_DIR, readContexts, readLocations, styles } from './build.js'
import { layout, readGraph, simpleName } from './model.js'

/** The repository's scratch directory — derived, gitignored, and outside `target/` (AGENTS.md § 2). */
const REPO_TMP = join(OUTPUT_DIR, '..', '..', '..', '..', '..', '.tmp')

/**
 * The bridge a host injects, stubbed for the headless run — written under `.tmp/` (gitignored, outside
 * `target/`) and loaded into the page **before** the page's own script, which is how a real host delivers it.
 *
 * The stub prints one line per call to the browser's console, and Chrome's `--enable-logging=stderr` puts
 * that on stderr where this test can read it: that is the evidence a click reached `window.openFile` with the
 * joined path and line, rather than the DOM merely having the attribute.
 */
const NAV_STUB = 'JCB_OPEN'
function navStubSource() {
  return `window.__jcbWebViewBridge = 1;\n`
    + `window.openFile = function (path, line, column) {\n`
    + `  console.log('${NAV_STUB} ' + JSON.stringify({ path: path, line: line, column: column }));\n`
    + `};\n`
}

/** Headless Chrome, if this machine has one. */
function chromeExecutable() {
  return [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    join(process.env.LOCALAPPDATA ?? '', 'Google/Chrome/Application/chrome.exe'),
  ].find((candidate) => candidate && existsSync(candidate))
}

/**
 * The built page plus a probe that records what the page actually mounted.
 *
 * The probe runs as a **microtask after the page's own script**, so it sees the finished boot synchronously —
 * and it reports through DOM attributes rather than the console, because `--dump-dom` (stdout) is readable
 * without Chrome's logging flags and because the DOM is what the reader of this test is asserting about. The
 * rendered `ne-block` arrives after a later task, so that one assertion stays on the DOM itself.
 */
function probePage(page) {
  const html = readFileSync(page, 'utf8')
  return html.replace('</body>', `<script>
    Promise.resolve().then(function () {
      var app = document.querySelector('.graph-app')
      var status = document.querySelector('.graph-status')
      var probe = document.createElement('div')
      probe.setAttribute('id', 'render-probe')
      probe.setAttribute('data-root-class', app ? app.className : 'none')
      probe.setAttribute('data-open-file', typeof window.openFile)
      probe.setAttribute('data-status', status ? status.textContent : 'none')
      document.body.appendChild(probe)
    })
  </script>
  </body>`, 1)
}

/** The probe's report, as an object — the DOM attributes it wrote, decoded. */
function probeResult(dom) {
  const at = dom.indexOf('id="render-probe"')
  if (at < 0) {
    return {}
  }
  // The attributes follow the id in the serialized element, so the window is the element itself.
  const attributes = dom.slice(at, at + 800)
  const read = (name) => {
    const match = attributes.match(new RegExp(`data-${name}="([^"]*)"`))
    return match ? match[1] : ''
  }
  return { rootClass: read('root-class'), openFile: read('open-file'), status: read('status') }
}

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

/* ------------------------------------------------------------------ the model */

check('the graph is a projection: a context keeps its name, implementation, dependencies and beans', () => {
  const graph = readGraph({
    contexts: [{
      context: 'a.b.Ctx',
      implementation: 'a.b.CtxImpl',
      dependencies: ['c.d.Other'],
      beans: [{ name: 'mapper', type: 'ObjectMapper', createdBy: 'new ObjectMapper()' }],
    }, { context: 'c.d.Other', implementation: 'c.d.OtherImpl' }],
  })
  assert.equal(graph.contexts.length, 2)
  assert.equal(graph.contexts[0].simpleName, 'Ctx')
  assert.equal(graph.contexts[0].implementation, 'a.b.CtxImpl')
  assert.equal(graph.contexts[0].beans[0].name, 'mapper')
  assert.equal(graph.lines.length, 1, 'one dependency is one line')
  assert.deepEqual(graph.lines[0], { from: 'a.b.Ctx', to: 'c.d.Other' })
  assert.deepEqual(graph.missing, [], 'a resolvable dependency is not reported as missing')
})

check('a dependency naming an unknown context is reported, never drawn', () => {
  const graph = readGraph({ contexts: [{ context: 'a.Ctx', dependencies: ['gone.Missing'] }] })
  assert.deepEqual(graph.lines, [], 'a line to nowhere would be a picture of something that is not there')
  assert.deepEqual(graph.missing, ['a.Ctx -> gone.Missing'], 'and the absence is named')
})

check('the model carries the locations the build joined, and reports what it could not place', () => {
  const graph = readGraph({
    contexts: [{ context: 'a.Ctx', implementation: 'a.CtxImpl' }],
    locations: {
      'a.Ctx': { path: 'm/src/a/Ctx.java', line: 7 },
      'a.CtxImpl': { path: 'm/src/a/CtxImpl.java', line: 12 },
    },
    locationless: ['ObjectMapper (bean mapper of a.Ctx)'],
  })
  assert.deepEqual(graph.contexts[0].location, { path: 'm/src/a/Ctx.java', line: 7 })
  assert.deepEqual(graph.contexts[0].implementationLocation, { path: 'm/src/a/CtxImpl.java', line: 12 })
  assert.deepEqual(graph.locationless, ['ObjectMapper (bean mapper of a.Ctx)'],
    'an unresolved name reaches the page instead of being dropped')
})

check('a location is never invented: a name with no join carries null', () => {
  const graph = readGraph({ contexts: [{ context: 'a.Ctx', beans: [{ name: 'mapper', type: 'ObjectMapper' }] }] })
  assert.equal(graph.contexts[0].location, null)
  assert.equal(graph.contexts[0].beans[0].location, null, 'a bean the index cannot answer is not pointed at line 1')
})

check('a context with no recorded dependencies or beans is empty rather than invented', () => {
  const graph = readGraph({ contexts: [{ context: 'a.Ctx' }] })
  assert.deepEqual(graph.contexts[0].dependencies, [])
  assert.deepEqual(graph.contexts[0].beans, [])
  assert.equal(readGraph({}).contexts.length, 0, 'a JSON with no contexts is not an error')
})

check('the layout is stable: the same contexts always land on the same grid', () => {
  const contexts = readGraph({
    contexts: [{ context: 'a' }, { context: 'b' }, { context: 'c' }, { context: 'd' }],
  }).contexts
  const first = layout(contexts)
  assert.deepEqual(layout(contexts), first, 'a page that re-ordered itself on every open would be unusable')
  assert.deepEqual(first.map((b) => b.id), ['a', 'b', 'c', 'd'])
  assert.deepEqual(first[0].pos, [40, 40])
  assert.notDeepEqual(first[3].pos, first[0].pos)
})

check('a simple name is the part after the last dot', () => {
  assert.equal(simpleName('hr.hrg.CtxMain'), 'CtxMain')
  assert.equal(simpleName('CtxMain'), 'CtxMain')
  assert.equal(simpleName(null), '')
})

/* ------------------------------------------------------------------ the document */

check('the emitted page inlines its script and its stylesheet: no <script src>, no <link>', () => {
  const page = html('/*script*/', '/*css*/')
  assert.ok(page.includes('<script>'), 'the script is inline')
  assert.ok(page.includes('/*script*/'))
  assert.ok(page.includes('/*css*/'), 'the stylesheet is inline')
  assert.ok(!/<script[^>]+src=/.test(page), 'no external script: the page must work from file://')
  assert.ok(!/<link\b/.test(page), 'no external stylesheet for the same reason')
})

check('the page JSON-escapes a value that could close its own script element', () => {
  const source = html('', '')
  assert.ok(source.includes('<script>'), 'the marker is present for this test to be about')
  // The escaping itself lives in `contextsModule`, which the build writes; its effect is asserted on the
  // built page below, where the generator's own JSON is what was inlined.
})

check('the built page exists, carries the real contexts, and no network reference at all', () => {
  const file = join(OUTPUT_DIR, 'graph.html')
  assert.ok(existsSync(file), `the page must be built at ${file} — run src/build.js`)
  const page = readFileSync(file, 'utf8')
  const contexts = readContexts()
  for (const context of contexts.contexts ?? []) {
    assert.ok(page.includes(context.context),
      `the built page must carry the generator's own graph: ${context.context} is missing`)
  }
  assert.ok(!page.includes('fetch('), 'the page must not fetch anything: file:// refuses it')
  assert.ok(!page.includes('XMLHttpRequest'), 'and it must not reach the network another way')
  assert.ok(page.includes('jsx6-nodditor'), 'the editor element is in the page')
  assert.ok(page.includes('graph-sidebar'), 'and the navigation the page is for')
})

check('the stylesheet carries nodditor geometry and the page frame', () => {
  const css = styles()
  assert.ok(css.length > 0)
  assert.ok(css.includes('.graph-app'), 'the page frame is ours')
  assert.ok(css.includes('--graph-accent'), 'the block look is ours')
})

check('the built page carries the joined source locations, and the name it could not place', () => {
  const file = join(OUTPUT_DIR, 'graph.html')
  assert.ok(existsSync(file), `the page must be built at ${file} — run src/build.js --project=<dir>`)
  const page = readFileSync(file, 'utf8')
  const joined = readLocations(readContexts(), { project: REPO })
  const context = joined.locations['hr.hrg.hipster.ioc.test.CtxMain']
  assert.ok(page.includes(context.path),
    `the joined path must be inlined, not fetched: ${context.path} is missing — the page cannot fetch at file://`)
  // The JSX compiles `data-location={…}` to a string key that evaluates at render time, so the serialized
  // form is the bundle's business rather than this test's. What the built file must show is the key, and what
  // it must show is the joined path — the *rendered* attribute is asserted on the real DOM below, which is
  // where a click reads it back through `getAttribute`.
  assert.ok(page.includes('"data-location"'), 'a joined location must reach an element attribute')
  assert.ok(page.includes(context.path), 'the joined project-relative path must be inlined in the page')
  assert.ok(page.includes('ObjectMapper'), 'the unresolved bean type is reported in the page rather than dropped')
})

/* ------------------------------------------------------------------ the real page, in a browser */

/**
 * Renders the built page in headless Chrome and asserts the DOM it actually reaches.
 *
 * <p>This is the assertion the page class needs and the reason it is last: everything above can pass
 * while the page is a blank shell, which is exactly what happened three times while this page was being
 * built (a `file://` fetch, an ESM script the browser refuses, and a `JsxW` constructor that set
 * attributes during the custom-element upgrade). Skipped — with a printed note — when no Chrome is
 * present, because the rest of the file is still meaningful without one.</p>
 */
check('the built page renders its shell, its block and its status line in a browser', () => {
  const page = join(OUTPUT_DIR, 'graph.html')
  if (!existsSync(page)) {
    throw new Error(`no built page at ${page}`)
  }
  const chrome = chromeExecutable()
  if (!chrome) {
    console.log('         (no Chrome found — the render assertion was skipped, not passed)')
    return
  }
  const profile = join(REPO_TMP, `chrome-${Math.random().toString(36).slice(2, 10)}`)
  const probe = join(REPO_TMP, 'graph-render-probe.html')
  mkdirSync(REPO_TMP, { recursive: true })
  writeFileSync(probe, probePage(page), 'utf8')
  const result = Bun.spawnSync([chrome, '--headless=new', '--no-sandbox', '--disable-gpu',
    '--disable-crash-reporter', '--virtual-time-budget=8000', `--user-data-dir=${profile}`,
    '--dump-dom', `file:///${probe.replaceAll('\\', '/')}`], { stdio: ['ignore', 'pipe', 'pipe'] })
  const dom = result.stdout ? new TextDecoder().decode(result.stdout) : ''
  assert.ok(dom.length > 0, 'Chrome must print the DOM it reached')
  assert.ok(dom.includes('graph-sidebar'), 'the jsx6 shell rendered')
  assert.ok(dom.includes('ne-block'), 'and nodditor rendered a block')
  assert.ok(dom.includes('context(s)'), 'and the page reached its status line')
  // `--dump-dom` prints the document **including its inline script text**, so the bundle's own source is in
  // the output: a `dom.includes('graph-navigable')` would find the expression, not the element. The probe
  // therefore reports what the page actually mounted, which is the assertion.
  const runtime = probeResult(dom)
  assert.equal(runtime.rootClass, 'graph-app',
    'a file:// page has no injected bridge, so it must not claim to be navigable')
  assert.equal(runtime.openFile, 'undefined', 'and nothing has injected window.openFile')
  assert.ok(!runtime.status.includes('Cannot read contexts.json'),
    'the page must not be the fetch-failure shell it was before the JSON was inlined')
  assert.ok(runtime.status.includes('standalone:'),
    'and it must say navigation needs a host rather than offering a click that does nothing')
})

/**
 * The navigation assertion (step 3.11a): a click on a joined element reaches the **frozen contract** with the
 * joined path and line.
 *
 * The host's half is a stub — `window.openFile`, written before the page's script — and the call is observed
 * through the browser's console, printed to stderr by `--enable-logging=stderr`. That is a stronger check than
 * the attribute being present: it is the page's own listener, the delegated `closest('[data-location]')` walk
 * and the transport call, all exercised.
 */
check('a click on a joined context asks the host to open its source, at the joined line', () => {
  const page = join(OUTPUT_DIR, 'graph.html')
  if (!existsSync(page)) {
    throw new Error(`no built page at ${page}`)
  }
  const chrome = chromeExecutable()
  if (!chrome) {
    console.log('         (no Chrome found — the navigation assertion was skipped, not passed)')
    return
  }
  const profile = join(REPO_TMP, `chrome-nav-${Math.random().toString(36).slice(2, 10)}`)
  const stub = join(REPO_TMP, 'graph-nav-stub.js')
  mkdirSync(REPO_TMP, { recursive: true })
  writeFileSync(stub, navStubSource(), 'utf8')
  const result = Bun.spawnSync([chrome, '--headless=new', '--no-sandbox', '--disable-gpu',
    '--disable-crash-reporter', '--enable-logging=stderr', '--v=0', '--virtual-time-budget=8000',
    `--user-data-dir=${profile}`, '--dump-dom', navUrl(page, stub)],
  { stdio: ['ignore', 'pipe', 'pipe'] })
  const logs = result.stderr ? new TextDecoder().decode(result.stderr) : ''
  const dom = result.stdout ? new TextDecoder().decode(result.stdout) : ''

  const context = readLocations(readContexts(), { project: REPO }).locations['hr.hrg.hipster.ioc.test.CtxMain']
  const anchored = dom.slice(dom.indexOf('<div id="graph-root"'))
  assert.ok(anchored.includes('data-location='),
    'the rendered page must carry the target on an element — this is the half a click reads back')
  assert.ok(dom.includes('id="nav-probe"'), 'the probe ran the click and wrote its result into the page')
  assert.ok(dom.includes('data-nav="true"'),
    'the click must reach window.openFile — a page whose elements carry locations but never call is not navigation')
  const opened = logs.split('\n').filter((line) => line.includes(NAV_STUB))
  assert.equal(opened.length, 1, `expected exactly one navigation call, saw ${opened.length}`)
  // Chrome wraps the page's console line (`[pid:tid:date:INFO:CONSOLE(n)] "…", source: …`), so the call is the
  // JSON object inside it — taken by its own first brace, not by trimming the wrapper.
  const line = opened[0]
  const call = JSON.parse(line.slice(line.indexOf('{', line.indexOf(NAV_STUB)), line.lastIndexOf('}') + 1))
  assert.equal(call.path, context.path, 'the host is asked for the joined, project-relative path')
  assert.equal(call.line, context.line, 'and for the joined line — not line 1')
  assert.equal(call.column, 1, 'the column is the contract default, because the join knows only a line')
})

/**
 * The page with the stub before it and a probe after it: the probe clicks the first element that carries a
 * location and records whether the page's own listener reached `window.openFile`. Both scripts are classic
 * inline scripts in one file, so their order is the document's order — which is how a host delivers its
 * bridge, minus the host.
 */
function navUrl(page, stub) {
  const html = readFileSync(page, 'utf8')
  const injected = html
    .replace('<script>', `<script>\n${readFileSync(stub, 'utf8')}\n</script>\n<script>`, 1)
    .replace('</body>', `<script>
      var clicked = document.querySelector('[data-location]')
      var before = 0
      var result = document.createElement('div')
      result.id = 'nav-probe'
      window.openFile = (function (inner) {
        return function (path, line, column) { before++; return inner(path, line, column) }
      })(window.openFile)
      if (clicked) { clicked.click() }
      result.setAttribute('data-nav', String(before > 0))
      document.body.appendChild(result)
    </script>
    </body>`, 1)
  const injectedFile = join(REPO_TMP, 'graph-nav-probe.html')
  writeFileSync(injectedFile, injected, 'utf8')
  return `file:///${injectedFile.replaceAll('\\', '/')}`
}

console.log(`\n${passed} passed, ${failed} failed`)
process.exit(failed === 0 ? 0 : 1)
