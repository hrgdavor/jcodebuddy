/**
 * Reading the review report per BLOCK, not per conflict.
 *
 * A git conflict block can disagree in more than one way at once — the `import-add-both` fixture's block holds both
 * a `COMMENT_ADD` and a `STRUCTURAL_CHANGE` — and `MergeFileTool` refuses to compose several conflicts into one
 * block answer. That refusal is right, but it used to be invisible: the page listed the resolutions flat, so a
 * reviewer could accept everything, export, apply, and find the block still there with nothing telling them why.
 *
 * So the page groups the resolutions of one file by the block they came from, and each group says what is missing.
 * Kept out of the component because this is a rule about the data, and rules about data belong where they can be
 * tested without a DOM.
 */
import { isResolved, proposedCodeFor, suggestionOf } from './decisions.js'

/** A block's identity: its lines in the file, or one group for resolutions the report could not locate. */
export function blockKey(region) {
  return region ? `${region.startLine}-${region.endLine}` : 'unlocated'
}

/**
 * The blocks of one file, each with its resolutions (paired to the conflict each answers) and what it still needs.
 *
 * The pairs matter: the report keeps `conflicts` and `resolutions` as index-parallel arrays, so a resolution is
 * tied to its conflict by position. Grouping by region and then re-pairing by position inside the group keeps that
 * link, which is what keys a decision on the conflict rather than on the resolution.
 */
export function blocksOf(file) {
  const conflicts = file?.conflicts ?? []
  const resolutions = file?.resolutions ?? []
  const groups = new Map()

  resolutions.forEach((resolution, index) => {
    const conflict = conflicts[index]
    const region = resolution.region ?? conflict?.region ?? null
    const key = blockKey(region)
    if (!groups.has(key)) {
      groups.set(key, { key, region, entries: [] })
    }
    groups.get(key).entries.push({ resolution, conflict })
  })

  return [...groups.values()].map((group) => {
    // "Has an answer", which is deliberately not the same question as "may a bulk accept take it" (that one is
    // `isBulkAcceptable`, and it is AUTO-only): the page's count describes what is on screen, while the guard
    // describes what may be accepted without a person reading it.
    const decided = group.entries.filter((entry) => isResolved(entry.resolution)).length
    return {
      ...group,
      decided,
      undecided: group.entries.length - decided,
      /**
       * More than one conflict in one block: the tool will not compose them into a single answer, so this block is
       * finished in an editor even when every one of its conflicts has been decided.
       */
      severalConflicts: group.entries.length > 1,
    }
  })
}

/**
 * A block's offered answer, or `null` when none of its resolutions carries one (plan step 4.16).
 *
 * <p>The page shows this as the **proposed result**, prefilled in the editor with its provenance and the
 * verifier's verdict beside it — so a reviewer reads what the tool worked out and what it rests on, then accepts,
 * edits or refuses it. Nothing here applies anything: the code is a proposal until a decision file says
 * otherwise.
 *
 * <p>Shaped for display rather than passed through raw, because the page needs the three facts that make a
 * suggestion judgeable — who produced it, how strong its basis was, and what verification said — and a renderer
 * that had to pick them out of a nested object would be the place they got dropped.
 */
export function suggestionFor(block) {
  for (const entry of block?.entries ?? []) {
    const suggestion = suggestionOf(entry.resolution)
    if (suggestion) {
      return {
        code: proposedCodeFor(entry.resolution),
        explanation: suggestion.explanation ?? '',
        provenance: suggestion.provenance ?? 'unknown',
        confidence: suggestion.confidence ?? 'PLAUSIBLE',
        analysisLevel: suggestion.analysisLevel ?? '',
        verification: suggestion.verification ?? 'NOT_RUN',
        verificationDetail: suggestion.verificationDetail ?? '',
        /** A failed verification labels the suggestion; it never hides it (SUGGESTIONS.md § 5 rule 2). */
        failed: suggestion.verification === 'FAILED',
      }
    }
  }
  return null
}

/** Every block of every file in a report. */
export function blocksByFile(files) {
  return (files ?? []).map((file) => ({
    filePath: file.filePath,
    branchName: file.branchName,
    blocks: blocksOf(file),
  }))
}

/**
 * What to tell a reviewer about one block: the sentence the flat list could not say.
 */
export function blockNote(block) {
  if (block.severalConflicts) {
    return (
      `${block.entries.length} conflicts share this block, and the tool will not compose several conflicts ` +
      'into one answer — decide them all, then finish this block in your editor.'
    )
  }
  if (block.undecided > 0) {
    // An offered answer is not "no answer yet", and saying so would be the page telling a reviewer to write code
    // the tool has already worked out (plan step 4.16).
    return suggestionFor(block)
      ? 'The tool worked out an answer — read it with its provenance, then accept it, edit it, or reject it.'
      : 'No answer yet: pick a fix path, or write the code you want, and accept it.'
  }
  return 'Resolved — what you accepted is what gets applied.'
}

/** A short label for a block, for the page's own heading. */
export function blockLabel(block) {
  if (!block.region) {
    return 'block (the report does not say where)'
  }
  const { startLine, endLine } = block.region
  return startLine === endLine ? `line ${startLine}` : `lines ${startLine}–${endLine}`
}
