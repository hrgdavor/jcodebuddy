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
export function decisionFor(filePath, resolution, choice) {
  const sides = resolution.sides ?? {}
  return {
    signature: resolution.signature ?? '',
    type: resolution.type,
    filePath,
    // A resolution carries no description of its own (the conflict does, and the report keeps the two lists
    // apart), so this is empty and the recorder defaults it. It is not part of the decision's key.
    description: '',
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
    decisions: (accepted ?? []).map(({ filePath, resolution, resolvedCode, explanation }) =>
      decisionFor(filePath, resolution, { resolvedCode, explanation })),
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
