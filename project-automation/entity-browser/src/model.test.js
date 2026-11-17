import { test } from 'bun:test'
import assert from 'node:assert/strict'
import {
  INLINE_ENUM_LIMIT,
  buildBrowserModel,
  filterTypes,
  parseImports,
  selectedInitial,
} from './model.js'

function enumRow(path, constants) {
  return {
    path,
    kind: 'enum',
    line: 1,
    modifiers: ['public'],
    members: constants.map((name, index) => ({
      name,
      kind: 'enum-constant',
      type: '',
      line: 3 + index,
      parameters: [],
      modifiers: [],
      annotations: [],
      hasInitializer: false,
      throws: [],
      hasBody: false,
    })),
    relations: [],
    annotations: [],
  }
}

function recordRow(path, components, relations = []) {
  return {
    path,
    kind: 'record',
    line: 3,
    modifiers: ['public'],
    members: components.map(([name, type], index) => ({
      name,
      kind: 'record-component',
      type,
      line: 3 + index,
      parameters: [],
      modifiers: [],
      annotations: [],
      hasInitializer: false,
      throws: [],
      hasBody: false,
    })),
    relations,
    annotations: [],
  }
}

function indexOf(moduleName, classes) {
  return {
    id: `${moduleName}/.jcodebuddy/index/classes.json`,
    module: moduleName,
    sourceRoot: 'src/main/java',
    directory: `${moduleName}/.jcodebuddy/index`,
    data: { format: 1 },
    classes,
  }
}

function fixture() {
  return buildBrowserModel({
    title: 'demo',
    indexes: [
      indexOf('demo', [
        {
          fqn: 'demo.OrderState',
          path: 'src/main/java/demo/OrderState.java',
          row: recordRow(
            'src/main/java/demo/OrderState.java',
            [
              ['id', 'java.lang.Long'],
              ['status', 'OrderStatus'],
              ['lines', 'List<LineItem>'],
            ],
            [{ name: 'OrderSummary', kind: 'implements' }],
          ),
          imports: ['import java.util.List;', 'import java.math.BigDecimal;'],
        },
        {
          fqn: 'demo.OrderStatus',
          path: 'src/main/java/demo/OrderStatus.java',
          row: enumRow('src/main/java/demo/OrderStatus.java', ['NEW', 'PAID', 'SHIPPED']),
          imports: [],
        },
        {
          fqn: 'demo.LineItem',
          path: 'src/main/java/demo/LineItem.java',
          row: recordRow('src/main/java/demo/LineItem.java', [
            ['sku', 'String'],
            ['quantity', 'int'],
          ]),
          imports: [],
        },
        {
          fqn: 'demo.OrderSummary',
          path: 'src/main/java/demo/OrderSummary.java',
          row: {
            path: 'src/main/java/demo/OrderSummary.java',
            kind: 'interface',
            line: 3,
            modifiers: ['public'],
            members: [
              {
                name: 'total',
                kind: 'method',
                type: 'BigDecimal',
                line: 4,
                parameters: [],
                modifiers: [],
                annotations: [],
                hasInitializer: false,
                throws: [],
                hasBody: false,
              },
            ],
            relations: [],
            annotations: [],
          },
          imports: ['import java.math.BigDecimal;'],
        },
      ]),
    ],
  })
}

test('records are the first view, with components in header order', () => {
  const model = fixture()
  assert.deepEqual(model.records, ['demo.LineItem', 'demo.OrderState'])
  const state = model.types['demo.OrderState']
  assert.equal(state.kind, 'record')
  assert.deepEqual(state.components.map((component) => component.name), ['id', 'status', 'lines'])
  assert.deepEqual(state.components.map((component) => component.type.text), [
    'java.lang.Long', 'OrderStatus', 'List<LineItem>',
  ])
  assert.equal(state.components[1].type.primary, 'demo.OrderStatus')
  assert.equal(state.components[2].type.primary, 'demo.LineItem',
    'a type inside a generic argument is still a dependency')
})

test('a small enum is answered on the component line, not by a drill-down', () => {
  const state = fixture().types['demo.OrderState']
  const status = state.components[1]
  assert.ok(status.enum, 'the component carries its enum facts')
  assert.equal(status.enum.fqn, 'demo.OrderStatus')
  assert.deepEqual(status.enum.constants, ['NEW', 'PAID', 'SHIPPED'])
  assert.equal(status.enum.inline, true)
  assert.equal(status.enum.more, 0)

  const model = buildBrowserModel({
    indexes: [indexOf('demo', [
      {
        fqn: 'demo.Big',
        path: 'src/main/java/demo/Big.java',
        row: recordRow('src/main/java/demo/Big.java', [['status', 'BigStatus']]),
        imports: [],
      },
      {
        fqn: 'demo.BigStatus',
        path: 'src/main/java/demo/BigStatus.java',
        row: enumRow('src/main/java/demo/BigStatus.java',
          Array.from({ length: INLINE_ENUM_LIMIT + 1 }, (_, index) => `V${index}`)),
        imports: [],
      },
    ])],
  })
  const big = model.types['demo.Big'].components[0].enum
  assert.equal(big.inline, false, 'a larger enum keeps the drill-down instead of flooding the row')
  assert.equal(big.more, 1)
  assert.equal(big.constants.length, INLINE_ENUM_LIMIT + 1)
})

test('dependencies are resolved through the package and the imports, and stay local when possible', () => {
  const state = fixture().types['demo.OrderState']
  assert.deepEqual(state.dependencies.map((dependency) => dependency.fqn).sort(), [
    'demo.LineItem', 'demo.OrderStatus', 'demo.OrderSummary',
  ])
  assert.ok(state.externalDependencies.includes('java.util.List'),
    'an imported JDK type is a real dependency, reported as external')
  assert.ok(state.externalDependencies.includes('java.lang.Long'))
  assert.ok(!state.externalDependencies.includes('LineItem'),
    'a local type is not also listed as external')
})

test('a record depends on a local enum, and the dependency carries the enum values inline', () => {
  const model = buildBrowserModel({
    indexes: [indexOf('demo', [
      {
        fqn: 'demo.Job',
        path: 'src/main/java/demo/Job.java',
        row: recordRow('src/main/java/demo/Job.java', [['status', 'JobStatus']]),
        imports: [],
      },
      {
        fqn: 'demo.JobStatus',
        path: 'src/main/java/demo/JobStatus.java',
        row: enumRow('src/main/java/demo/JobStatus.java', ['QUEUED', 'RUNNING', 'DONE']),
        imports: [],
      },
    ])],
  })
  const dependency = model.types['demo.Job'].dependencies.find((item) => item.fqn === 'demo.JobStatus')
  assert.deepEqual(dependency.enum.constants, ['QUEUED', 'RUNNING', 'DONE'])
  assert.equal(dependency.enum.inline, true)
})

test('search reaches components, enum values and local dependencies', () => {
  const model = fixture()
  assert.deepEqual(filterTypes(model, 'PAID').map((row) => row.fqn), ['demo.OrderState'])
  assert.deepEqual(filterTypes(model, 'status').map((row) => row.fqn), ['demo.OrderState'])
  assert.deepEqual(filterTypes(model, 'lines').map((row) => row.fqn), ['demo.OrderState'])
  assert.deepEqual(filterTypes(model, 'sku').map((row) => row.fqn), ['demo.LineItem'])
  assert.deepEqual(filterTypes(model, 'nothing-here'), [])
  assert.equal(filterTypes(model, 'OrderStatus', 'all').length, 2,
    'all-types scope finds the enum by name as well as the record that references it')
})

test('a deep link may name any indexed type, while a missing one falls back to the first record', () => {
  const model = fixture()
  assert.equal(selectedInitial(model, 'demo.OrderStatus'), 'demo.OrderStatus')
  assert.equal(selectedInitial(model, 'demo.Missing'), 'demo.LineItem')
})

test('an ambiguous simple name is reported and left unlinked rather than guessed', () => {
  const model = buildBrowserModel({
    indexes: [indexOf('demo', [
      {
        fqn: 'demo.jobs.Job',
        path: 'src/main/java/demo/jobs/Job.java',
        row: recordRow('src/main/java/demo/jobs/Job.java', [['status', 'Status']]),
        imports: [],
      },
      {
        fqn: 'demo.a.Status',
        path: 'src/main/java/demo/a/Status.java',
        row: enumRow('src/main/java/demo/a/Status.java', ['ON']),
        imports: [],
      },
      {
        fqn: 'demo.b.Status',
        path: 'src/main/java/demo/b/Status.java',
        row: enumRow('src/main/java/demo/b/Status.java', ['OFF']),
        imports: [],
      },
    ])],
  })
  const job = model.types['demo.jobs.Job']
  assert.equal(job.components[0].type.primary, null, 'two candidates is not a resolution')
  assert.deepEqual(job.dependencies, [])
  assert.ok(model.diagnostics.some((item) => item.kind === 'browser_ambiguous_type'))
})

test('imports: explicit type imports resolve, static imports are ignored, wildcards are tried', () => {
  const parsed = parseImports([
    'import java.util.List;',
    'import static java.util.Collections.emptyList;',
    'import demo.orders.*;',
  ])
  assert.equal(parsed.explicit.get('List'), 'java.util.List')
  assert.equal(parsed.explicit.has('emptyList'), false, 'a static import names a member, not a type')
  assert.deepEqual(parsed.wildcards, ['demo.orders'])
})

test('the model is deterministic for the same input', () => {
  const a = JSON.stringify(fixture())
  const b = JSON.stringify(fixture())
  assert.equal(a, b)
})
