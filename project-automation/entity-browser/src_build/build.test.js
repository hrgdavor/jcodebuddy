import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { main } from './build.js'

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = resolve(PACKAGE_ROOT, '..', '..')
const ready = existsSync(join(REPO_ROOT, '.jsx6', 'libs'))
  && existsSync(join(PACKAGE_ROOT, 'node_modules', 'esbuild'))

function writeFixture(root, options = {}) {
  const packageName = options.packageName ?? 'demo'
  const recordName = options.recordName ?? 'OrderState'
  const enumName = options.enumName ?? 'OrderStatus'
  const packagePath = packageName.split('.').join('/')
  const relativeDir = `src/main/java/${packagePath}`
  const indexDir = join(root, '.jcodebuddy', 'index')
  const sourceDir = join(root, relativeDir)
  mkdirSync(indexDir, { recursive: true })
  mkdirSync(sourceDir, { recursive: true })
  writeFileSync(join(sourceDir, `${recordName}.java`), `package ${packageName};\n\n`
    + `public record ${recordName}(Long id, ${enumName} status) {\n`
    + '}\n', 'utf8')
  writeFileSync(join(sourceDir, `${enumName}.java`), `package ${packageName};\n\n`
    + `public enum ${enumName} { NEW, PAID, SHIPPED }\n`, 'utf8')
  writeFileSync(join(indexDir, 'classes.json'), JSON.stringify({
    format: 1,
    module: options.module ?? 'demo',
    sourceRoot: 'src/main/java',
    classes: {
      [`${packageName}.${recordName}`]: {
        path: `${relativeDir}/${recordName}.java`,
        kind: 'record',
        line: 3,
        modifiers: ['public'],
        members: [
          {
            name: 'id', kind: 'record-component', type: 'Long', line: 3, parameters: [],
            modifiers: [], annotations: [], hasInitializer: false, throws: [], hasBody: false,
          },
          {
            name: 'status', kind: 'record-component', type: enumName, line: 3, parameters: [],
            modifiers: [], annotations: [], hasInitializer: false, throws: [], hasBody: false,
          },
        ],
        relations: [],
        annotations: [],
      },
      [`${packageName}.${enumName}`]: {
        path: `${relativeDir}/${enumName}.java`,
        kind: 'enum',
        line: 3,
        modifiers: ['public'],
        members: ['NEW', 'PAID', 'SHIPPED'].map((name) => ({
          name, kind: 'enum-constant', type: '', line: 3, parameters: [],
          modifiers: [], annotations: [], hasInitializer: false, throws: [], hasBody: false,
        })),
        relations: [],
        annotations: [],
      },
    },
  }), 'utf8')
  writeFileSync(join(indexDir, 'imports.json'), JSON.stringify({
    format: 1,
    of: 'classes.json',
    imports: {
      [`${relativeDir}/${recordName}.java`]: ['import java.lang.Long;'],
      [`${relativeDir}/${enumName}.java`]: [],
    },
  }), 'utf8')
  return join(indexDir, 'classes.json')
}

test('a second page build reuses the content-addressed jsx6 asset', () => {
  if (!ready) {
    console.log('[entity-browser.test] skipped: needs .jsx6/libs and `bun install` in entity-browser/')
    return
  }
  const scratch = join(REPO_ROOT, '.tmp')
  mkdirSync(scratch, { recursive: true })
  const root = mkdtempSync(join(scratch, 'entity-browser-jsx6-'))
  try {
    const indexFile = writeFixture(root)
    const outDir = join(root, '.jcodebuddy', 'metadata', 'browser')
    assert.equal(main(['--index', indexFile, '--out', outDir, '--quiet']), 0)
    const assetRoot = join(dirname(outDir), 'lib', 'jsx6')
    const first = readdirSync(assetRoot)[0]
    const firstMtime = statSync(join(assetRoot, first, 'jsx6.js')).mtimeMs
    assert.equal(main(['--index', indexFile, '--out', outDir, '--quiet']), 0)
    assert.equal(statSync(join(assetRoot, first, 'jsx6.js')).mtimeMs, firstMtime,
      'jsx6 is not rebuilt when its content hash is unchanged')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the build writes a served page directory with the record and its inline enum values', () => {
  if (!ready) {
    console.log('[entity-browser.test] skipped: needs .jsx6/libs and `bun install` in entity-browser/')
    return
  }
  const scratch = join(REPO_ROOT, '.tmp')
  mkdirSync(scratch, { recursive: true })
  const root = mkdtempSync(join(scratch, 'entity-browser-build-'))
  try {
    const indexFile = writeFixture(root)
    const outDir = join(root, '.jcodebuddy', 'metadata', 'browser')
    const page = join(outDir, 'index.html')
    const code = main(['--index', indexFile, '--out', outDir, '--quiet'])
    assert.equal(code, 0)
    assert.ok(existsSync(page), 'the page shell is written')
    assert.ok(existsSync(join(outDir, 'browser.js')), 'the bundle is a sibling file')
    assert.ok(existsSync(join(outDir, 'model.json')), 'the model is a sibling JSON file')
    assert.ok(existsSync(join(outDir, 'serve.js')), 'the standalone server travels with the page')
    assert.ok(existsSync(join(outDir, 'README.txt')), 'the output says how to run it')
    const html = readFileSync(page, 'utf8')
    assert.ok(html.includes('<script type="module" src="./browser.js"></script>'),
      'the page loads its bundle over HTTP, not inline')
    const model = JSON.parse(readFileSync(join(outDir, 'model.json'), 'utf8'))
    assert.ok(model.types['demo.OrderState'], 'the record reaches the page model')
    assert.deepEqual(model.types['demo.OrderStatus'].enumConstants.map((constant) => constant.name),
      ['NEW', 'PAID', 'SHIPPED'])
    assert.equal(model.types['demo.OrderState'].components[1].enum.inline, true,
      'the small enum is marked for the component line')
    assert.equal(model.linkBase, '../../../', 'the page knows the link base it resolves paths against')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('two modules share one link base and every path keeps its module prefix', () => {
  if (!ready) {
    console.log('[entity-browser.test] skipped: needs .jsx6/libs and `bun install` in entity-browser/')
    return
  }
  const scratch = join(REPO_ROOT, '.tmp')
  mkdirSync(scratch, { recursive: true })
  const root = mkdtempSync(join(scratch, 'entity-browser-multi-'))
  try {
    const moduleA = join(root, 'a')
    const moduleB = join(root, 'b')
    const indexA = writeFixture(moduleA, { module: 'a', packageName: 'a.demo', recordName: 'A', enumName: 'AStatus' })
    const indexB = writeFixture(moduleB, { module: 'b', packageName: 'b.demo', recordName: 'B', enumName: 'BStatus' })
    const outDir = join(root, '.jcodebuddy', 'metadata', 'browser')
    const code = main([
      '--index', indexA,
      '--index', indexB,
      '--link-base', root,
      '--out', outDir,
      '--quiet',
    ])
    assert.equal(code, 0)
    const model = JSON.parse(readFileSync(join(outDir, 'model.json'), 'utf8'))
    assert.equal(model.types['a.demo.A'].path, 'a/src/main/java/a/demo/A.java',
      'module A keeps its prefix relative to the shared link base')
    assert.equal(model.types['b.demo.B'].path, 'b/src/main/java/b/demo/B.java',
      'module B keeps its prefix relative to the shared link base')
    assert.equal(model.linkBase, '../../../',
      'the page resolves the shared root from its own location')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
