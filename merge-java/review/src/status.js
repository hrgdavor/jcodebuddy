/**
 * The per-file state of a merge run, and the three numbers the file list shows (plan step 4.13, deliverable 4).
 *
 * <p>The list is what turns the page from "one file at a time" into a run: a reviewer resolves a file, moves to
 * the next, comes back to the first, and then exports once. That is why the state is computed from the report
 * plus the decisions accepted so far, and why it is a pure function — a number that only existed inside a
 * component could not be asserted, and the count is the metric § 10.3 asks for.</p>
 *
 * <h3>Three numbers, and why not one</h3>
 * <ul>
 *   <li><b>{@code decided}</b> — conflicts the tool answered automatically. Nobody needed to look.</li>
 *   <li><b>{@code accepted}</b> — conflicts a person accepted. Split into {@code accepted} (one at a time) and
 *       {@code acceptedInOneAction} (by the block action, which is the number § 10.3 names: *blocks accepted in
 *       one action*).</li>
 *   <li><b>{@code open}</b> — conflicts still needing somebody, **or another editor**. Named "open" rather than
 *       "conflicted" on purpose: a conflict is one way to be open and a missing answer is another, and calling
 *       both "conflicted" would say the page's own completeness is the merge's.</li>
 * </ul>
 */
import { blocksOf } from './blocks.js'
import { hasAnswer, isResolved } from './decisions.js'

/** Whether a decision entry was made by the reviewer in this session. */
function isDecidedNow(entry) {
  return entry?.decidedNow === true
}

/** Whether a decision came from the block action, as its explanation records. */
function isBlockAction(entry) {
  return typeof entry?.explanation === 'string' && entry.explanation.includes('whole block')
}

/**
 * The state of one file, given the decisions accepted so far.
 *
 * @param file      one entry of the report's `files`
 * @param accepted  the decisions the page holds (the same list the export is built from)
 */
export function fileStatus(file, accepted = []) {
  const blocks = blocksOf(file)
  const mine = accepted.filter((entry) => entry?.filePath === file?.filePath)

  let decided = 0
  let open = 0
  let answeredNotTaken = 0
  for (const block of blocks) {
    for (const entry of block.entries) {
      const taken = mine.some((decision) => decision.conflict === entry.conflict
        || (decision.conflict?.signature && decision.conflict.signature === entry.conflict?.signature))
      if (taken) {
        continue
      }
      if (isResolved(entry.resolution)) {
        decided++
      } else if (hasAnswer(entry)) {
        // An offer nobody has taken yet: it still needs a person, which is what `open` counts.
        answeredNotTaken++
        open++
      } else {
        open++
      }
    }
  }

  const acceptedOneByOne = mine.filter((entry) => isDecidedNow(entry) && !isBlockAction(entry)).length
  const acceptedInOneAction = mine.filter((entry) => isDecidedNow(entry) && isBlockAction(entry)).length
  const swept = mine.filter((entry) => !isDecidedNow(entry)).length

  const total = blocks.reduce((sum, block) => sum + block.entries.length, 0)
  return {
    filePath: file?.filePath ?? '',
    blocks: blocks.length,
    /** Blocks whose every conflict has an answer — what the block action can take. */
    blocksReady: blocks.filter((block) => block.entries.length > 0 && block.entries.every(hasAnswer)).length,
    total,
    decided,
    accepted: acceptedOneByOne,
    acceptedInOneAction,
    swept,
    answeredNotTaken,
    open,
    /** Whether the page has nothing left for a person in this file — never a claim about the merge. */
    finishedHere: open === 0,
  }
}

/** The same for every file of a run, plus the run's own totals. */
export function runStatus(files = [], accepted = []) {
  const perFile = files.map((file) => fileStatus(file, accepted))
  const sum = (key) => perFile.reduce((total, status) => total + status[key], 0)
  return {
    files: perFile,
    decided: sum('decided'),
    accepted: sum('accepted'),
    acceptedInOneAction: sum('acceptedInOneAction'),
    swept: sum('swept'),
    open: sum('open'),
  }
}
