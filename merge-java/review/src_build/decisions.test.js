#!/usr/bin/env bun
/**
 * The decision payload the page exports, tested without a DOM (plan step 4.3).
 *
 * This is one half of the page <-> recorder contract: these are the keys `DecisionRecorder` reads and verifies
 * (`signature` against the conflict it builds from `type`/`branch1`/`branch2`). The other half lives in
 * `DecisionRecorderTest`, which records a payload of exactly this shape and asserts the next merge replays it.
 *
 * Run from `merge-java/review`: `bun test`.
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import {
  MANUAL_MARKER,
  SCHEMA_VERSION,
  acceptAllResolved,
  buildDecisions,
  decisionKey,
  fileNameFor,
  codeForOption,
  fixPathOptions,
  isActionable,
  isResolved,
  mergeAccepted,
  toJson,
} from '../src/decisions.js'

/** A resolution shaped the way the report writes one, with the fix paths 4.3 acts on. */
const resolution = {
  type: 'API_INCOMPATIBILITY',
  kind: 'MANUAL',
  signature: 'api_incompatibility-abc123',
  resolvedCode: 'void process(CharSequence id) {\n}',
  sides: { base: 'base code', branch1: 'branch 1 code', branch2: 'branch 2 code' },
  fixPaths: [
    { description: 'keep both overloads', options: ['process(String)'], recommended: 'process(String)' },
    { description: 'rename one', options: ['processById(String)', 'process(CharSequence)'], recommended: 'processById(String)' },
  ],
}

test('the payload carries every key the recorder reads', () => {
  const document = buildDecisions({
    branchName: 'feature-payments',
    accepted: [
      {
        filePath: 'src/main/java/com/example/demo/OrderService.java',
        resolution,
        resolvedCode: 'void process(CharSequence id) {\n}',
        explanation: 'reviewer chose process(CharSequence)',
      },
    ],
  })

  assert.equal(document.schemaVersion, SCHEMA_VERSION)
  assert.equal(document.branchName, 'feature-payments')
  assert.equal(document.decisions.length, 1)

  const decision = document.decisions[0]
  for (const key of [
    'signature',
    'type',
    'filePath',
    'base',
    'branch1',
    'branch2',
    'resolvedCode',
    'explanation',
  ]) {
    assert.ok(key in decision, `the recorder reads ${key}`)
  }
  // The sides are what the recorder hashes to verify the page's key, so they must be the report's own text.
  assert.equal(decision.signature, 'api_incompatibility-abc123')
  assert.equal(decision.base, 'base code')
  assert.equal(decision.branch1, 'branch 1 code')
  assert.equal(decision.branch2, 'branch 2 code')
  assert.equal(decision.resolvedCode, 'void process(CharSequence id) {\n}')
  assert.equal(decision.explanation, 'reviewer chose process(CharSequence)')
  assert.equal(decision.type, 'API_INCOMPATIBILITY')
})

test('the export is JSON the recorder can parse, and its name identifies the branch', () => {
  const document = buildDecisions({ branchName: 'feature/payments', accepted: [] })

  assert.equal(JSON.parse(toJson(document)).branchName, 'feature/payments')
  assert.equal(JSON.parse(toJson(document)).decisions.length, 0)
  assert.equal(fileNameFor('feature/payments'), 'decisions-feature-payments.json')
  assert.equal(fileNameFor(''), 'decisions-branch.json')
})

test('a resolution with no sides still produces a payload, with empty sides', () => {
  const decision = buildDecisions({
    branchName: 'b',
    accepted: [{ filePath: 'A.java', resolution: { type: 'IMPORT_ADD', kind: 'AUTO' }, resolvedCode: '', explanation: '' }],
  }).decisions[0]

  assert.equal(decision.base, '')
  assert.equal(decision.branch1, '')
  assert.equal(decision.branch2, '')
})

test('what a reviewer can act on', () => {
  assert.ok(isActionable({ kind: 'MANUAL' }), 'a manual resolution is the point of the action display')
  assert.ok(isActionable({ kind: 'REVIEW' }))
  assert.ok(
    isActionable({ kind: 'AUTO', fixPaths: [{ options: ['keep'] }] }),
    'an automatic resolution offering fix paths can still be decided against',
  )
  assert.ok(!isActionable({ kind: 'AUTO' }), 'and one that offers nothing is not a decision to make')
  assert.equal(fixPathOptions(resolution).length, 3, 'every option of every fix path is offered')
  assert.equal(fixPathOptions(resolution)[0].fixPath.description, 'keep both overloads')
})

test('what "Apply all resolved" accepts, and what it refuses to decide for you', () => {
  assert.ok(isResolved({ resolvedCode: 'void process() {}' }))
  assert.ok(!isResolved({ resolvedCode: '' }), 'an empty result is not an answer')
  assert.ok(!isResolved({ resolvedCode: '   \n ' }), 'nor is whitespace')
  assert.ok(!isResolved({ resolvedCode: MANUAL_MARKER }), 'the engine refusing is not an answer')
  assert.ok(!isResolved({}), 'nor is a resolution with no result at all')

  const accepted = acceptAllResolved([
    {
      filePath: 'src/main/java/com/example/demo/OrderService.java',
      resolutions: [
        { type: 'IMPORT_ADD', kind: 'AUTO', signature: 's1', resolvedCode: 'import java.util.List;' },
        { type: 'STRUCTURAL_CHANGE', kind: 'MANUAL', signature: 's2', resolvedCode: MANUAL_MARKER },
        { type: 'API_INCOMPATIBILITY', kind: 'MANUAL', signature: 's3', resolvedCode: '' },
      ],
    },
  ])

  assert.equal(accepted.length, 1, 'only the resolution that HAS an answer')
  assert.equal(accepted[0].resolvedCode, 'import java.util.List;')
  assert.match(accepted[0].explanation, /AUTO/)
})

test('a decision made by hand survives "Apply all resolved"', () => {
  const mine = {
    filePath: 'A.java',
    resolution: { type: 'IMPORT_ADD', kind: 'AUTO', signature: 's1', resolvedCode: 'import a;' },
    resolvedCode: 'import a; // edited by hand',
    explanation: 'reviewer chose the recommended fix path',
  }
  const bulk = acceptAllResolved([
    {
      filePath: 'A.java',
      resolutions: [
        { type: 'IMPORT_ADD', kind: 'AUTO', signature: 's1', resolvedCode: 'import a;' },
        { type: 'COMMENT_ADD', kind: 'AUTO', signature: 's2', resolvedCode: '// b' },
      ],
    },
  ])

  const merged = mergeAccepted([mine], bulk)

  assert.equal(merged.length, 2, 'the bulk action adds, it does not replace')
  assert.equal(
    merged.find((entry) => entry.resolution.signature === 's1').resolvedCode,
    'import a; // edited by hand',
    'the more specific, hand-made decision must win',
  )
  assert.equal(merged.find((entry) => entry.resolution.signature === 's2').resolvedCode, '// b')
  const keys = merged.map((entry) => decisionKey(entry.filePath, entry.resolution))
  assert.equal(new Set(keys).size, keys.length, 'one decision per conflict, never two')
})

test('a decision is keyed on the CONFLICT, not on a replayed resolution', () => {
  // The replay that fooled the real run: the resolution holds the recorded decision's sides, while the conflict
  // holds its own. Keying on the resolution named a signature the incoming conflict did not have, so two decision
  // files existed for one block and the replay kept using the older one.
  const conflict = {
    type: 'COMMENT_ADD',
    description: 'Both branches added comments',
    signature: 'comment_add-1c2f',
    sides: { base: 'base', branch1: 'branch one', branch2: 'branch two' },
  }
  const replayed = {
    type: 'COMMENT_ADD',
    kind: 'DEFERRED',
    strategy: 'STICKY_REPLAY',
    signature: 'comment_add-6e34',
    resolvedCode: 'the recorded answer',
    sides: { base: 'base', branch1: 'the recorded answer', branch2: 'the recorded answer' },
  }

  const decision = buildDecisions({
    branchName: 'feature',
    accepted: [
      {
        filePath: 'PaymentProcessor.java',
        resolution: replayed,
        conflict,
        resolvedCode: 'branch one',
        explanation: 'reviewer took branch one',
      },
    ],
  }).decisions[0]

  assert.equal(decision.signature, 'comment_add-1c2f', "the key the store holds decisions under is the conflict's")
  assert.equal(decision.branch1, 'branch one', "and the sides the recorder hashes are the conflict's too")
  assert.equal(decision.branch2, 'branch two')
  assert.equal(decision.description, 'Both branches added comments')
  assert.equal(decision.resolvedCode, 'branch one', "the accepted code is still the reviewer's choice")

  // A replayed resolution and the raw conflict it answers are ONE decision, not two.
  assert.equal(
    decisionKey('PaymentProcessor.java', replayed, conflict),
    decisionKey('PaymentProcessor.java', { signature: 'comment_add-1c2f' }, conflict),
  )
  // Without the conflict it falls back to the resolution - exactly the different key that caused the bug.
  assert.notEqual(
    decisionKey('PaymentProcessor.java', replayed, conflict),
    decisionKey('PaymentProcessor.java', replayed),
  )
})

test('acceptAllResolved pairs each resolution with its conflict', () => {
  const accepted = acceptAllResolved([
    {
      filePath: 'A.java',
      conflicts: [
        { type: 'IMPORT_ADD', signature: 'import_add-aaaa', sides: { base: 'b', branch1: 'one', branch2: 'two' } },
        {
          type: 'STRUCTURAL_CHANGE',
          signature: 'structural_change-bbbb',
          sides: { base: 'b', branch1: 'x', branch2: 'y' },
        },
      ],
      resolutions: [
        { type: 'IMPORT_ADD', kind: 'AUTO', signature: 'import_add-aaaa', resolvedCode: 'import a;' },
        {
          type: 'STRUCTURAL_CHANGE',
          kind: 'MANUAL',
          signature: 'structural_change-bbbb',
          resolvedCode: MANUAL_MARKER,
        },
      ],
    },
  ])

  assert.equal(accepted.length, 1, 'only the resolution with an answer')
  assert.equal(accepted[0].conflict.signature, 'import_add-aaaa', 'paired with the conflict it answers')
  assert.equal(accepted[0].conflict.sides.branch1, 'one')
})

test('a fix path that carries code prefills the choice that applies it', () => {
  // How a proposal reaches the editor (plan step 4.4): the fix path carries the code, so accepting it is a
  // decision rather than a transcription. The fix paths that only describe a direction carry none.
  const resolution = {
    fixPaths: [
      { description: 'Take branch 1', options: ['apply branch 1'] },
      {
        description: 'Use the proposed answer',
        options: ['Accept the proposed answer'],
        suggestedCode: 'void renamed() {\n}',
      },
    ],
  }

  assert.equal(codeForOption(resolution, 'Accept the proposed answer'), 'void renamed() {\n}')
  assert.equal(codeForOption(resolution, 'apply branch 1'), '')
  assert.equal(codeForOption(resolution, 'not an option'), '')
  assert.equal(codeForOption({}, 'anything'), '')
})
