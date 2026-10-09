#!/usr/bin/env bun
/**
 * The multi-file flow's numbers (plan step 4.13, deliverable 4): per-file `decided` / `accepted` / `open`, and
 * the run's totals for the file list a reviewer navigates by.
 *
 * The assertion that matters most is the one about **navigation**: accepting in one file and then computing the
 * status of another must not lose the first file's decision, because that is the flow the maintainer asked for —
 * resolve a file, move on, come back — and a reviewer who loses work by clicking a filename will not use the
 * page twice.
 *
 * Run from `merge-java/review`: `bun test`.
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { fileStatus, runStatus } from '../src/status.js'
import { acceptBlock, acceptAllResolved } from '../src/decisions.js'
import { blocksOf } from '../src/blocks.js'

const conflict = (type, startLine, endLine) => ({
  type,
  signature: `${type.toLowerCase()}-${startLine}`,
  region: { startLine, endLine },
  sides: { base: 'b', branch1: 'x', branch2: 'y' },
})

const answered = (type, startLine, endLine, code = 'fixed\n') => ({
  ...conflict(type, startLine, endLine),
  kind: 'AUTO',
  resolvedCode: code,
})

const offered = (type, startLine, endLine, code = 'offered\n') => ({
  ...conflict(type, startLine, endLine),
  kind: 'SUGGESTION',
  resolvedCode: '',
  suggestion: { code, explanation: 'combined', provenance: 'r', confidence: 'PLAUSIBLE', verification: 'PASSED' },
})

const unanswered = (type, startLine, endLine) => ({
  ...conflict(type, startLine, endLine),
  kind: 'MANUAL',
  resolvedCode: '',
})

/** A report file: `conflicts` and `resolutions` are index-parallel. */
const file = (filePath, ...resolutions) => ({
  filePath,
  branchName: 'feature',
  conflicts: resolutions.map((resolution) => conflict(resolution.type, resolution.region.startLine,
    resolution.region.endLine)),
  resolutions,
})

/**
 * A file with **two conflicts in ONE block**: `blocksOf` groups by region, so a single shared region is what
 * makes a block of several conflicts — which is the case the all-or-nothing rule is about.
 */
const A = file('src/A.java', answered('COMMENT_ADD', 1, 20), unanswered('STRUCTURAL_CHANGE', 1, 20))
const B = file('src/B.java', offered('METHOD_BODY_CHANGE', 1, 5))
/** A file whose one block is entirely answered, so it is ready for the block action. */
const C = file('src/C.java', answered('COMMENT_ADD', 1, 5))

test('a file reports its three numbers from the report alone', () => {
  const status = fileStatus(A, [])

  assert.equal(status.total, 2)
  assert.equal(status.decided, 1, 'the automatic answer is decided without anybody')
  assert.equal(status.open, 1, 'and the unanswered conflict is open')
  assert.equal(status.accepted, 0, 'nobody has accepted anything yet')
  assert.equal(status.finishedHere, false)
})

test('an offered answer counts as OPEN until somebody takes it', () => {
  // The distinction the page must not blur: the tool having an answer is not the reviewer having accepted it.
  const before = fileStatus(B, [])
  assert.equal(before.open, 1)
  assert.equal(before.answeredNotTaken, 1)
  assert.equal(before.accepted, 0)

  const after = fileStatus(B, acceptBlock(blocksOf(B)[0], 'src/B.java'))
  assert.equal(after.open, 0, 'once taken, nothing is left for a person here')
  assert.equal(after.acceptedInOneAction, 1, 'and it counts as the block action, which is the metric')
  assert.equal(after.accepted, 0, 'not as a one-at-a-time acceptance')
  assert.equal(after.finishedHere, true)
})

test('an accepted decision in ONE file does not change another file, and is not lost by selecting it', () => {
  // The navigation property: `accepted` is the whole session's list, so computing B's status after accepting in
  // B — and again after looking at A — must be the same answer.
  const accepted = acceptBlock(blocksOf(B)[0], 'src/B.java')

  const bAgain = fileStatus(B, accepted)
  assert.equal(bAgain.acceptedInOneAction, 1, 'coming back to B, the decision is still there')

  const aWhileDeciding = fileStatus(A, accepted)
  assert.equal(aWhileDeciding.accepted, 0, "A shows none of B's decisions")
  assert.equal(aWhileDeciding.open, 1, "and A's own open conflict is still open")

  const bThird = fileStatus(B, accepted)
  assert.deepEqual(bThird, bAgain, 'the status is a pure function of the report and the decisions')
})

test('the block action and the sweep are counted apart', () => {
  const sweep = acceptAllResolved([A])

  const status = fileStatus(A, sweep)
  assert.equal(status.swept, 1, 'the sweep recorded that automatic answer')
  assert.equal(status.accepted, 0, 'but nobody decided it, so it is not an acceptance')
  assert.equal(status.acceptedInOneAction, 0)
  assert.equal(status.open, 1, 'and the unanswered conflict stays open')
})

test('a one-at-a-time acceptance counts as accepted, not as the block action', () => {
  const entry = {
    filePath: 'src/B.java',
    resolution: B.resolutions[0],
    conflict: B.conflicts[0],
    resolvedCode: 'mine\n',
    decidedNow: true,
    explanation: 'reviewer chose the suggestion',
  }

  const status = fileStatus(B, [entry])
  assert.equal(status.accepted, 1, 'the metric for one-at-a-time is its own number')
  assert.equal(status.acceptedInOneAction, 0, 'and the § 10.3 number is not inflated by it')
  assert.equal(status.open, 0)
})

test('"ready" needs EVERY conflict in the block answered, which is what the block action requires', () => {
  // B holds an OFFER: the tool has an answer, so the block is ready for the block action — but nobody has taken
  // it, so the file is not finished. "Ready to take in one action" and "nothing left for a person" are two
  // different numbers, and the list shows both.
  const b = fileStatus(B, [])
  assert.equal(b.blocksReady, 1, 'B has one block and every conflict in it has an answer')
  assert.equal(b.finishedHere, false, 'an untaken offer is still work for a person')
  assert.equal(b.answeredNotTaken, 1)

  // Once taken, the same file is finished here — and the action that took it is the block action, which is the
  // number § 10.3 asks for.
  const taken = fileStatus(B, acceptBlock(blocksOf(B)[0], 'src/B.java'))
  assert.equal(taken.finishedHere, true)
  assert.equal(taken.acceptedInOneAction, 1)

  // C's single block is entirely answered, so it is ready — the number the list shows as "you can take this in
  // one action".
  assert.equal(fileStatus(C, []).blocksReady, 1)

  // A's block holds TWO conflicts, and the second has no answer at all — so the block is NOT ready and the block
  // action would refuse it. Getting this wrong would offer a button that always refuses, which is the failure the
  // all-or-nothing rule exists to prevent.
  const a = fileStatus(A, [])
  assert.equal(a.blocks, 1, 'A\'s two conflicts share one region, so they are one block')
  assert.equal(a.blocksReady, 0, "A's block has an unanswered conflict, so it is not ready")
  assert.equal(a.finishedHere, false)
})

test('the run totals add the files up, for the list a reviewer navigates by', () => {
  const before = runStatus([A, B], [])
  assert.equal(before.files.length, 2)
  assert.equal(before.decided, 1)
  assert.equal(before.open, 2)
  assert.equal(before.acceptedInOneAction, 0)

  const after = runStatus([A, B], acceptBlock(blocksOf(B)[0], 'src/B.java'))
  assert.equal(after.acceptedInOneAction, 1, "B's block action shows in the run's total")
  assert.equal(after.open, 1, 'and the run has one thing left for a person')
})

test('a file with no blocks is empty rather than an error', () => {
  const status = fileStatus({ filePath: 'src/C.java', conflicts: [], resolutions: [] }, [])
  assert.equal(status.total, 0)
  assert.equal(status.blocks, 0)
  assert.equal(status.open, 0)
  assert.equal(status.finishedHere, true, 'nothing to do is finished, and it says so without claiming a merge')
})
