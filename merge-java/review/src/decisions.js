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

/**
 * The whole document: what `DecisionRecorder --decisions` is pointed at.
 *
 * <p>{@code rejected} is a refusal, recorded rather than merely "not accepted". The difference matters on the
 * second run: a suggestion a reviewer refused and which comes back is the channel making the tool worse, and
 * the suppression that fixes it keys on `(signature, provenance)` — a rejection of a text-comparison answer is
 * not a rejection of a future structural one (SUGGESTIONS.md § 5 rule 4). This step records the refusal; the
 * recorder is what acts on it.
 */
export function buildDecisions({ branchName, accepted, rejected }) {
  return {
    schemaVersion: SCHEMA_VERSION,
    branchName: branchName || '',
    decisions: (accepted ?? []).map(({ filePath, resolution, conflict, resolvedCode, explanation }) =>
      decisionFor(filePath, resolution, { resolvedCode, explanation }, conflict),
    ),
    rejected: (rejected ?? []).map(({ filePath, resolution, conflict }) =>
      rejectionFor(filePath, resolution, conflict),
    ),
  }
}

/**
 * One refusal, in the shape the recorder reads: the conflict it is about and **who** produced the answer, so
 * suppression can be per provenance.
 */
export function rejectionFor(filePath, resolution, conflict) {
  const suggestion = suggestionOf(resolution)
  return {
    signature: conflict?.signature ?? resolution?.signature ?? '',
    type: resolution?.type,
    filePath,
    // The provenance is what makes the refusal specific: refusing "the word-level comparison" must not refuse a
    // structural answer for the same conflict later.
    provenance: suggestion?.provenance ?? resolution?.kind ?? 'unknown',
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

/**
 * The code that choosing an option would apply, when the fix path carrying it has one.
 *
 * <p>A proposal arrives exactly this way (plan step 4.4): the fix path says what the gate thought of it and
 * carries the code, so a reviewer accepting one does not have to copy it out of a justification.</p>
 */
export function codeForOption(resolution, option) {
  const carrier = (resolution.fixPaths ?? []).find((fixPath) => (fixPath.options ?? []).includes(option))
  return carrier?.suggestedCode || ''
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
 * The suggestion a resolution carries, or `null` (plan step 4.16).
 *
 * <p>A suggestion is an answer the tool worked out and offers: it is never applied on its own, and it carries
 * the provenance and the verifier's verdict that a person needs in order to judge it. Kept here beside the other
 * resolution readers so the page reads one vocabulary rather than reaching into the JSON itself.
 */
export function suggestionOf(resolution) {
  const suggestion = resolution?.suggestion
  return suggestion && typeof suggestion === 'object' ? suggestion : null
}

/**
 * The code a reviewer should see in the editor for a resolution: the suggestion's answer when there is one, else
 * the resolution's own result.
 *
 * <p>The suggestion's code is the proposed result, which is why it wins here — and why a resolution carrying one
 * has an <em>empty</em> `resolvedCode`, so the text exists in exactly one place.
 */
export function proposedCodeFor(resolution) {
  const suggestion = suggestionOf(resolution)
  if (suggestion) {
    return suggestion.code ?? ''
  }
  return resolution?.resolvedCode ?? ''
}

/**
 * Whether "apply all resolved" may accept a resolution on the reviewer's behalf.
 *
 * <p><b>Automatic resolutions only.</b> A reviewer accepting a suggestion is making a judgement about code they
 * have read, and no bulk action can stand in for that; a `REVIEW` is the tool asking for a nod rather than
 * answering, so it is not swept up either.
 *
 * <p>The rule is on the KIND rather than on whether a field happens to hold text, and the distinction is not
 * academic: the previous version accepted anything whose `resolvedCode` was non-empty, which quietly included
 * review resolutions and contradicted this file's own documentation. A guard that holds because a field is empty
 * is not a guard — it holds until somebody fills the field.
 */
export function isBulkAcceptable(resolution) {
  return resolution?.kind === 'AUTO' && isResolved(resolution)
}

/**
 * Every resolution in a report that already has an answer, as entries ready to accept.
 *
 * <p>This is what the "Apply all resolved" button adds, and it is deliberately the *narrow* reading: blocks the
 * engine refused, blocks it left for a human, and every suggestion are not accepted silently. A reviewer can
 * still accept those one at a time, with an edit.</p>
 */
export function acceptAllResolved(files) {
  const accepted = []
  for (const file of files ?? []) {
    const conflicts = file.conflicts ?? []
    // Index-parallel with resolutions, which is how a resolution is tied to the conflict it answers.
    ;(file.resolutions ?? []).forEach((resolution, index) => {
      if (!isBulkAcceptable(resolution)) {
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
