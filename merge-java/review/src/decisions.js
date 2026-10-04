/**
 * The decision payload the review page exports (plan step 4.3).
 *
 * Kept out of the component on purpose: this is the *contract* with `DecisionRecorder`, the Java command that
 * records what a reviewer accepted, so it must be testable without a DOM and readable by someone who is not
 * looking at JSX. The keys are the ones the recorder reads, and it verifies `signature` against the conflict it
 * computes from `type`/`branch1`/`branch2` — the one check that keeps a decision from landing on the wrong
 * conflict.
 */

/** The export format's version; the recorder refuses anything else rather than misreading it. */
export const SCHEMA_VERSION = 1

/** The file name a reviewer will see, so two branches' exports do not overwrite each other. */
export function fileNameFor(branchName) {
  const safe = String(branchName || 'branch').replace(/[^A-Za-z0-9._-]+/g, '-')
  return `decisions-${safe}.json`
}

/**
 * One accepted decision, in the shape the recorder reads.
 *
 * @param filePath   the repository-relative file the conflict is in
 * @param resolution the resolution from the report (its `signature`, `type` and `sides` are used)
 * @param choice     `{ resolvedCode, explanation }` - what the reviewer accepted, including any edit they made
 */
export function decisionFor(filePath, resolution, choice, conflict) {
  // The CONFLICT's own facts win when the report carries them, and that is the whole point of them: a
  // resolution that replayed a recorded decision holds THAT decision's sides and signature, so keying a
  // decision on the resolution can name a signature the incoming conflict does not have. Observed on a real
  // mid-merge repository: two decision files for one block, and the replay kept using the first.
  const sides = conflict?.sides ?? resolution.sides ?? {}
  return {
    signature: conflict?.signature ?? resolution.signature ?? '',
    type: resolution.type,
    filePath,
    // A resolution carries no description of its own (the conflict does, and the report keeps the two lists
    // apart), so this is empty and the recorder defaults it. It is not part of the decision's key.
    description: conflict?.description ?? '',
    base: sides.base ?? '',
    branch1: sides.branch1 ?? '',
    branch2: sides.branch2 ?? '',
    resolvedCode: choice.resolvedCode ?? '',
    explanation: choice.explanation ?? '',
  }
}

/** The whole document: what `DecisionRecorder --decisions` is pointed at. */
export function buildDecisions({ branchName, accepted }) {
  return {
    schemaVersion: SCHEMA_VERSION,
    branchName: branchName || '',
    decisions: (accepted ?? []).map(({ filePath, resolution, conflict, resolvedCode, explanation }) =>
      decisionFor(filePath, resolution, { resolvedCode, explanation }, conflict),
    ),
  }
}

export function toJson(payload) {
  return `${JSON.stringify(payload, null, 2)}\n`
}

/**
 * The fix-path options a resolution offers, flattened, each carrying the fix path it came from so the label can
 * say what choosing it means.
 */
export function fixPathOptions(resolution) {
  return (resolution.fixPaths ?? []).flatMap((fixPath) =>
    (fixPath.options ?? []).map((option) => ({ option, fixPath })),
  )
}

/** Whether a resolution is one a reviewer can act on: anything not already applied automatically, or anything
 * that offers fix paths to choose between. */
export function isActionable(resolution) {
  return resolution.kind !== 'AUTO' || fixPathOptions(resolution).length > 0
}

/** The text the engine writes where it refuses to decide, so a page never mistakes it for an answer. */
export const MANUAL_MARKER = '<<< MERGE-JAVA: MANUAL RESOLUTION REQUIRED >>>'

/**
 * Whether a resolution already HAS an answer: something "apply all resolved" may accept on the reviewer's behalf.
 *
 * <p>An empty result is not an answer, and neither is the engine's own manual marker - it is the absence of one.
 * Automatic resolutions with a real result are, which is where this differs from {@link isActionable}: actionable
 * is about what a reviewer may decide, this is about what is already decided.</p>
 */
export function isResolved(resolution) {
  const code = resolution.resolvedCode ?? ''
  return code.trim().length > 0 && !code.includes(MANUAL_MARKER)
}

/** One decision's identity: a file and a conflict's key, so accepting twice replaces rather than doubles. */
/**
 * One decision's identity: a file and a conflict's key, so accepting twice replaces rather than doubles.
 *
 * <p>The conflict's signature wins over the resolution's, so a replayed resolution and the raw conflict it
 * answers are the SAME decision - one entry, recorded under the key the conflict actually has.</p>
 */
export function decisionKey(filePath, resolution, conflict) {
  return `${filePath ?? ''}::${conflict?.signature ?? resolution?.signature ?? resolution?.type ?? ''}`
}

/**
 * Every resolution in a report that already has an answer, as entries ready to accept.
 *
 * <p>This is what the "Apply all resolved" button adds, and it is deliberately the *narrow* reading: blocks the
 * engine refused, and blocks it left for a human, are not accepted silently. A reviewer can still accept those one
 * at a time, with an edit.</p>
 */
export function acceptAllResolved(files) {
  const accepted = []
  for (const file of files ?? []) {
    const conflicts = file.conflicts ?? []
    // Index-parallel with resolutions, which is how a resolution is tied to the conflict it answers.
    ;(file.resolutions ?? []).forEach((resolution, index) => {
      if (!isResolved(resolution)) {
        return
      }
      accepted.push({
        filePath: file.filePath,
        resolution,
        conflict: conflicts[index],
        resolvedCode: resolution.resolvedCode ?? '',
        explanation: `accepted the ${resolution.kind} resolution as resolved`,
      })
    })
  }
  return accepted
}

/**
 * Add decisions that are not already decided, keeping what the reviewer decided themselves.
 *
 * <p>"Apply all resolved" is a bulk convenience, so it must never overwrite a choice somebody made by hand for
 * that same conflict - the hand-made one is the more specific statement, and silently replacing it would change
 * what gets applied to the file.</p>
 */
export function mergeAccepted(existing, incoming) {
  const decided = new Set(
    (existing ?? []).map((entry) => decisionKey(entry.filePath, entry.resolution, entry.conflict)),
  )
  const added = (incoming ?? []).filter(
    (entry) => !decided.has(decisionKey(entry.filePath, entry.resolution, entry.conflict)),
  )
  return [...(existing ?? []), ...added]
}
