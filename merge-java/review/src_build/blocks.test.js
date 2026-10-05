#!/usr/bin/env bun
/**
 * The page reads per BLOCK, and these are the rules of that reading (plan step 4.2's follow-up).
 *
 * The shape under test is the real one from the temporary test environment (`test-env.js
 * import-add-both`): ONE block of the file, lines 9-21, that disagrees in two ways at once —
 * a `COMMENT_ADD` and a `STRUCTURAL_CHANGE`. The tool refuses to compose several conflicts of one
 * block into a single answer, so the page has to say so rather than leave a reviewer accepting
 * everything and wondering why the block is still there.
 *
 * Run from `merge-java/review`: `bun test`.
 */
import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { blockKey, blockLabel, blockNote, blocksByFile, blocksOf } from '../src/blocks.js'
import { MANUAL_MARKER } from '../src/decisions.js'

/** The block of the sample repository, as the report carries it after the region is stamped. */
const block = { startLine: 9, endLine: 21 }

const twoConflictFile = {
  filePath: 'PaymentProcessor.java',
  branchName: 'feature',
  conflicts: [
    { type: 'COMMENT_ADD', signature: 'comment_add-1c2f', region: block, handling: 'AUTO' },
    { type: 'STRUCTURAL_CHANGE', signature: 'structural_change-1c2f', region: block, handling: 'MANUAL' },
  ],
  resolutions: [
    {
      type: 'COMMENT_ADD',
      kind: 'DEFERRED',
      strategy: 'STICKY_REPLAY',
      resolvedCode: 'the composed comment merge',
      region: block,
    },
    { type: 'STRUCTURAL_CHANGE', kind: 'MANUAL', resolvedCode: MANUAL_MARKER, region: block },
  ],
}

test('conflicts sharing a block are one group, and the group says it needs a person', () => {
  const blocks = blocksOf(twoConflictFile)

  assert.equal(blocks.length, 1, 'both conflicts are in the same block, so there is one group')
  assert.equal(blocks[0].entries.length, 2)
  assert.equal(blocks[0].decided, 1, 'the replayed decision is an answer')
  assert.equal(blocks[0].undecided, 1, "the engine's manual marker is not")
  assert.ok(blocks[0].severalConflicts)
  assert.match(blockNote(blocks[0]), /will not compose several conflicts/)
  assert.equal(blockLabel(blocks[0]), 'lines 9–21')
  // The pairing is the point: a decision is keyed on the CONFLICT, so the entry must carry it.
  assert.equal(blocks[0].entries[0].conflict.signature, 'comment_add-1c2f')
  assert.equal(blocks[0].entries[1].conflict.signature, 'structural_change-1c2f')
})

test('blocks at different lines are different groups', () => {
  const blocks = blocksOf({
    conflicts: [
      { type: 'IMPORT_ADD', signature: 's1', region: { startLine: 3, endLine: 7 } },
      { type: 'METHOD_BODY_CHANGE', signature: 's2', region: { startLine: 40, endLine: 44 } },
    ],
    resolutions: [
      { type: 'IMPORT_ADD', kind: 'AUTO', resolvedCode: 'import a;', region: { startLine: 3, endLine: 7 } },
      { type: 'METHOD_BODY_CHANGE', kind: 'REVIEW', resolvedCode: 'void x() {}', region: { startLine: 40, endLine: 44 } },
    ],
  })

  assert.equal(blocks.length, 2)
  assert.equal(blocks[0].severalConflicts, false)
  assert.equal(blocks[0].undecided, 0)
  assert.equal(blocks[1].decided, 1)
  assert.equal(blockLabel(blocks[1]), 'lines 40–44')
})

test('a resolution the report cannot locate is still a group, and says so', () => {
  const blocks = blocksOf({
    conflicts: [{ type: 'IMPORT_ADD', signature: 's1' }],
    resolutions: [{ type: 'IMPORT_ADD', kind: 'AUTO', resolvedCode: 'import a;' }],
  })

  assert.equal(blocks.length, 1)
  assert.equal(blocks[0].key, 'unlocated')
  assert.equal(blockKey(null), 'unlocated')
  assert.match(blockLabel(blocks[0]), /does not say where/)
  assert.equal(blocks[0].undecided, 0)
})

test('an undecided single conflict asks for a decision; a decided one does not', () => {
  const undecided = blocksOf({
    conflicts: [{ type: 'VARIABLE_RENAME', signature: 's', region: { startLine: 2, endLine: 2 } }],
    resolutions: [
      { type: 'VARIABLE_RENAME', kind: 'MANUAL', resolvedCode: MANUAL_MARKER, region: { startLine: 2, endLine: 2 } },
    ],
  })[0]
  assert.equal(undecided.undecided, 1)
  assert.equal(undecided.severalConflicts, false)
  assert.match(blockNote(undecided), /No answer yet/)
  assert.equal(blockLabel(undecided), 'line 2', 'one line reads as a line, not a range')

  const decided = blocksOf({
    conflicts: [{ type: 'IMPORT_ADD', signature: 's', region: { startLine: 2, endLine: 5 } }],
    resolutions: [
      { type: 'IMPORT_ADD', kind: 'AUTO', resolvedCode: 'import a;', region: { startLine: 2, endLine: 5 } },
    ],
  })[0]
  assert.match(blockNote(decided), /Resolved/)
})

test('every file of a report keeps its own blocks', () => {
  const grouped = blocksByFile([twoConflictFile, { filePath: 'Other.java', branchName: 'feature', conflicts: [], resolutions: [] }])

  assert.equal(grouped.length, 2)
  assert.equal(grouped[0].filePath, 'PaymentProcessor.java')
  assert.equal(grouped[0].blocks.length, 1)
  assert.equal(grouped[1].filePath, 'Other.java')
  assert.equal(grouped[1].blocks.length, 0, 'a clean file has no blocks to read')
})
