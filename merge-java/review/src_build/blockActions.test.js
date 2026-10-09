#!/usr/bin/env bun
/**
 * The block-level accept action (plan step 4.13): one action takes a block **only when every conflict in it has an
 * answer**, and otherwise refuses and names what blocks it.
 *
 * Two things this file exists to keep apart, because merging them would be the easy mistake:
 *
 * - `isBulkAcceptable` is the page-wide sweep's rule — *what may be taken without being shown* — and stays
 *   `AUTO`-only (`SUGGESTIONS.md` § 5);
 * - `blockClaim`/`acceptBlock` are the block action's rule — *what a confirmed, displayed set may take* — which
 *   includes a suggestion, because the reviewer sees the set and confirms it.
 *
 * Both are asserted here, so neither can quietly become the other.
 *
 * Run from `merge-java/review`: `bun test`.
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { blocksOf } from '../src/blocks.js'
import { acceptBlock, blockClaim, buildDecisions, hasAnswer, isBulkAcceptable } from '../src/decisions.js'

/** A conflict as the report writes one. */
const conflict = (type, startLine, endLine, text = 'sides') => ({
  type,
  signature: `${type.toLowerCase()}-${startLine}`,
  region: { startLine, endLine },
  sides: { base: text, branch1: text, branch2: text },
})

/** A resolution the tool answered outright. */
const answered = (type, startLine, endLine, code = 'resolved\n') => ({
  ...conflict(type, startLine, endLine),
  kind: 'AUTO',
  resolvedCode: code,
})

/** A resolution with an **offer**: the code lives in `suggestion.code` and `resolvedCode` stays empty. */
const offered = (type, startLine, endLine, code = 'offered\n') => ({
  ...conflict(type, startLine, endLine),
  kind: 'SUGGESTION',
  resolvedCode: '',
  suggestion: {
    code,
    explanation: 'combined both edits',
    provenance: 'MethodBodyChangeConflictResolver',
    confidence: 'PLAUSIBLE',
    analysisLevel: 'TEXT_INTRALINE',
    verification: 'PASSED',
  },
})

/** A resolution with nothing: the tool refused and left markers. */
const unanswered = (type, startLine, endLine) => ({
  ...conflict(type, startLine, endLine),
  kind: 'MANUAL',
  resolvedCode: '',
})

/** A block as `blocksOf` shapes one: entries pairing each resolution with the conflict it answers. */
const blockOf = (...resolutions) => ({
  key: '10-20',
  region: { startLine: 10, endLine: 20 },
  entries: resolutions.map((resolution, index) => ({ resolution, conflict: conflict('X', 10, 20) })),
})

test('a block whose every conflict has an answer is accepted, one decision per conflict', () => {
  const block = blockOf(answered('COMMENT_ADD', 10, 20), offered('METHOD_BODY_CHANGE', 10, 20))

  const accepted = acceptBlock(block, 'A.java')

  assert.equal(accepted.length, 2, 'one decision per conflict, so the whole claim is recorded')
  assert.equal(accepted[0].filePath, 'A.java')
  assert.equal(accepted[0].resolvedCode, 'resolved\n', "the engine's own answer is what gets recorded")
  assert.equal(accepted[1].resolvedCode, 'offered\n', "the suggestion's code is the answer, not resolvedCode")
  assert.ok(accepted.every((entry) => entry.conflict), 'each decision carries the conflict it answers')
  for (const entry of accepted) {
    assert.match(entry.explanation, /whole block/, 'a block acceptance is distinguishable in the record')
  }
})

test('a block with one unanswered conflict is refused, and the refusal names it', () => {
  const block = blockOf(
    answered('COMMENT_ADD', 10, 20),
    unanswered('STRUCTURAL_CHANGE', 30, 40),
  )

  const claim = blockClaim(block, 'A.java')
  assert.deepEqual(claim.accepted, [], 'nothing is taken when the block cannot be taken')
  assert.equal(claim.blocked.length, 1, 'exactly the conflict that blocks it')
  assert.match(claim.blocked[0], /STRUCTURAL_CHANGE/, 'the refusal says which type')
  assert.match(claim.blocked[0], /30–40/, 'and where it is')

  assert.throws(
    () => acceptBlock(block, 'A.java'),
    /cannot be accepted in one action: 1 of 2 conflict\(s\) have no answer/,
    'the action throws rather than silently accepting nothing',
  )
})

test('a block where EVERY conflict is unanswered is refused, not reported as done', () => {
  const block = blockOf(unanswered('STRUCTURAL_CHANGE', 10, 20), unanswered('API_INCOMPATIBILITY', 20, 30))

  const claim = blockClaim(block, 'A.java')
  assert.equal(claim.blocked.length, 2)
  assert.deepEqual(claim.accepted, [])
})

test('the block action takes a suggestion, and the sweep still refuses one', () => {
  const suggested = offered('METHOD_BODY_CHANGE', 1, 5)

  // The same resolution, judged by the two rules. This pair is the whole reason the rules are separate.
  assert.equal(hasAnswer({ resolution: suggested }), true, 'the block action sees an answer to offer')
  assert.equal(acceptBlock(blockOf(suggested), 'A.java').length, 1, 'and takes it, once confirmed')
  assert.equal(
    isBulkAcceptable(suggested),
    false,
    'the page-wide sweep must still refuse it: nobody was shown that code (SUGGESTIONS.md § 5)',
  )
})

test('an unanswered resolution has no answer by either rule', () => {
  const entry = { resolution: unanswered('STRUCTURAL_CHANGE', 1, 5) }
  assert.equal(hasAnswer(entry), false)
  assert.equal(isBulkAcceptable(entry.resolution), false)
  assert.equal(blockClaim(blockOf(entry.resolution), 'A.java').blocked.length, 1)
})

test('the engine manual marker counts as no answer, not as code', () => {
  const marker = {
    ...conflict('MANUAL', 1, 5),
    kind: 'MANUAL',
    resolvedCode: '<<< MERGE-JAVA: MANUAL RESOLUTION REQUIRED >>>',
  }
  assert.equal(hasAnswer({ resolution: marker }), false, 'a marker is the absence of an answer')
})

test('the accepted decisions survive the export as one entry per conflict', () => {
  const block = blockOf(answered('COMMENT_ADD', 1, 5), offered('METHOD_BODY_CHANGE', 1, 5))

  const document = buildDecisions({
    branchName: 'feature/payments',
    accepted: acceptBlock(block, 'src/A.java'),
  })

  assert.equal(document.decisions.length, 2, 'what the CLI applies is one decision per conflict')
  assert.ok(document.decisions.every((decision) => decision.filePath === 'src/A.java'))
  assert.equal(document.decisions[1].resolvedCode, 'offered\n', 'the suggestion arrived as concrete code')
  assert.ok(
    document.decisions.every((decision) => decision.signature),
    'and each carries the key the recorder verifies',
  )
})

test('a block built by blocksOf is accepted when the report answered all of it', () => {
  // The same rule through the real grouping, so the test cannot pass on a hand-built shape alone.
  const file = {
    filePath: 'src/A.java',
    conflicts: [conflict('COMMENT_ADD', 1, 5), conflict('METHOD_BODY_CHANGE', 1, 5)],
    resolutions: [answered('COMMENT_ADD', 1, 5), offered('METHOD_BODY_CHANGE', 1, 5)],
  }

  const blocks = blocksOf(file)
  assert.equal(blocks.length, 1, 'both resolutions share one block')

  const accepted = acceptBlock(blocks[0], file.filePath)
  assert.equal(accepted.length, 2)
  assert.equal(accepted[0].conflict.signature, 'comment_add-1', 'paired with the conflict it answers')
})

test('a block built by blocksOf is refused when the report left part of it open', () => {
  const file = {
    filePath: 'src/A.java',
    conflicts: [conflict('COMMENT_ADD', 1, 5), conflict('STRUCTURAL_CHANGE', 1, 5)],
    resolutions: [answered('COMMENT_ADD', 1, 5), unanswered('STRUCTURAL_CHANGE', 1, 5)],
  }

  const blocks = blocksOf(file)
  const claim = blockClaim(blocks[0], file.filePath)

  assert.deepEqual(claim.accepted, [])
  assert.equal(claim.blocked.length, 1)
  assert.match(claim.blocked[0], /STRUCTURAL_CHANGE/)
})
