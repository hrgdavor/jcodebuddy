import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'
import { report, reportSource, isSample, repoRoot } from '../.generated/report.js'
import {
  acceptAllResolved,
  buildDecisions,
  decisionKey,
  fileNameFor,
  fixPathOptions,
  codeForOption,
  isActionable,
  mergeAccepted,
  proposedCodeFor,
  rejectionFor,
  suggestionOf,
} from './decisions.js'
import { blockLabel, blockNote, blocksOf } from './blocks.js'
import { isServed, saveDecisions } from './save.js'

/**
 * The merge-java resolution review page (plan step 4.2), as a jsx6 app.
 *
 * It is a reader of the model `MergeReportWriter` writes: it parses no Java and decides nothing. Since step 4.3
 * it also collects what a reviewer *accepts* — but only as an export, because a `file://` page cannot write:
 * `DecisionRecorder` records the exported decisions, and the next merge replays them. Every value the page shows
 * comes from
 * `merge-report.json`, so a reviewer sees what was decided and why rather than a count.
 *
 * Two deliberate behaviours worth keeping:
 *  - **the data's origin is on the page** — the source path, and a `sample` badge when the build had
 *    no real report to inline, so a screenshot can never be mistaken for a run's evidence;
 *  - **a missing field is said out loud** — a report that does not carry the three sides yet renders
 *    a note pointing at 4.2 instead of silently showing one column.
 */

const percent = (part, whole) => (whole === 0 ? 0 : Math.round((part / whole) * 100))

/**
 * The decisions a reviewer has accepted so far (plan step 4.3).
 *
 * They are collected here and exported as one file rather than posted anywhere: this page is opened from
 * `file://`, which cannot write, and the maintainer's answer on 2026-10-03 was to keep that separation — the page
 * exports, `DecisionRecorder` records, and the next merge replays the decisions like any other sticky choice.
 */
const $accepted = signal([])

/**
 * The suggestions a reviewer has refused (plan step 4.16).
 *
 * A refusal is recorded rather than left as "not accepted", because the two are different facts: "not accepted
 * yet" is a page state, while "I read this and it is wrong" is a judgement the next merge must not ask for
 * again. Step 4.17 is what acts on it; recording it starts here.
 */
const $rejected = signal([])

/** Record a refusal, replacing any earlier refusal of the same conflict. */
function reject(filePath, resolution, conflict) {
  const entry = rejectionFor(filePath, resolution, conflict)
  const key = decisionKey(filePath, resolution, conflict)
  // Accepting and refusing are the same action on the same conflict, so refusing also retracts an acceptance:
  // a conflict cannot be both.
  $accepted($accepted().filter((existing) => decisionKey(existing.filePath, existing.resolution) !== key))
  $rejected([...$rejected().filter((existing) => `${existing.filePath}::${existing.signature}` !== key), entry])
}

/**
 * Deliver the payload by whichever transport this document has (plan step 4.13, deliverable 3): a POST when
 * the page is served, the browser download when it is a `file://` document. The rule and its reasons live in
 * `save.js`, which is testable without a DOM; this is only the call site, and it reports what happened because
 * a reviewer whose decisions reached nothing must be told rather than left believing they were recorded.
 */
async function deliverDecisions(payload, $outcome) {
  const result = await saveDecisions(payload, { fileName: fileNameFor(payload.branchName) })
  $outcome(result.detail)
  return result
}

/**
 * What a reviewer does with one resolution: pick a fix path (or keep the result), edit the code that will be
 * accepted, and accept it. Nothing here is applied to the repository — an accepted decision is data until
 * `DecisionRecorder` records it, which is what keeps the page unable to change a branch on its own.
 */
function Actions({ filePath, resolution, conflict }) {
  const options = fixPathOptions(resolution)
  const suggestion = suggestionOf(resolution)
  const $choice = signal('keep')
  // Prefilled from the PROPOSED result, which is the suggestion when there is one. Reading `resolvedCode` alone
  // was a real defect once step 4.15 moved the answers onto the channel: that field is deliberately empty on a
  // suggestion, so a reviewer was shown an empty editor and told to write code the tool had already worked out.
  const $code = signal(proposedCodeFor(resolution))

  const accept = () => {
    const chosen = $choice()
    const entry = {

      filePath,

      resolution,

      conflict,
      resolvedCode: $code(),
      explanation:
        chosen === 'keep'
          ? 'reviewer accepted the resolved result'
          : `reviewer chose ${chosen}`,
    }
    // Replacing by key rather than appending: a reviewer who changes their mind about one conflict must not end
    // up recording two decisions for it.
    const key = decisionKey(filePath, resolution, conflict)
    const others = $accepted().filter((existing) => decisionKey(existing.filePath, existing.resolution) !== key)
    $accepted([...others, entry])
  }
  const accepted = () =>
    $accepted().some(
      (existing) => decisionKey(existing.filePath, existing.resolution) === decisionKey(filePath, resolution, conflict),
    )

  return (
    <div class={`actions${accepted() ? ' accepted' : ''}`}>
      <div class="label">
        your decision{' '}
        {accepted() ? <span class="badge">accepted — export to apply</span> : null}
      </div>
      {suggestion ? (
        // The basis beside the answer, not only in the JSON. A suggestion whose provenance is invisible is one
        // that invites blind acceptance, and "the word-level comparison produced this" and "a model proposed
        // this" deserve different amounts of trust from the person reading them (SUGGESTIONS.md § 3).
        <div class={`suggestion${suggestion.failed ? ' failed' : ''}`}>
          <span class="badge">{suggestion.confidence}</span>
          <span class="provenance">proposed by {suggestion.provenance}</span>
          <span class="evidence">evidence: {suggestion.analysisLevel || 'not recorded'}</span>
          {suggestion.failed ? (
            <span class="verdict">
              verification failed: {suggestion.verificationDetail || 'no detail'}
            </span>
          ) : (
            <span class="verdict">verification: {suggestion.verification}</span>
          )}
        </div>
      ) : null}
      {suggestion ? (
        // Prefilled above; this is the editable copy the reviewer accepts as it stands or changes.
        <div class="label">the proposed result — accept it, edit it, or reject it</div>
      ) : null}
      {options.length ? (
        <select
          onchange={(event) => {
            $choice(event.target.value)
            // A fix path that carries code (a proposal) fills the editor: accepting it should be a decision,
            // not a transcription.
            const suggested = codeForOption(resolution, event.target.value)
            if (suggested) {
              $code(suggested)
            }
          }}
        >
          <option value="keep">keep the resolved result</option>
          {options.map(({ option, fixPath }) => (
            <option value={option}>
              {option}
              {fixPath.recommended === option ? ' (recommended)' : ''} — {fixPath.description}
            </option>
          ))}
        </select>
      ) : null}
      <textarea
        rows="3"
        placeholder="the code to accept — edit the resolved result if the fix path needs it"
        oninput={(event) => $code(event.target.value)}
      >
        {$code}
      </textarea>
      <div>
        <button onclick={accept}>Accept</button>
        {suggestion ? (
          // An explicit refusal, recorded rather than merely "not accepted": a suggestion that comes back after
          // being rejected is the channel making the tool worse, and the recorder suppresses it per
          // (signature, provenance) on the next run (SUGGESTIONS.md § 5 rule 4).
          <button class="reject" onclick={() => reject(filePath, resolution, conflict)}>
            Reject
          </button>
        ) : null}
        <span class="note">{options.length ? 'the choice is recorded with the code above' : ''}</span>
      </div>
    </div>
  )
}

function Chip({ label, value, tone }) {
  return (
    <div class="chip">
      <span class="k">{label}</span>
      <span class="v" style={tone ? `color:${tone}` : null}>
        {value}
      </span>
    </div>
  )
}

function Summary({ summary }) {
  const conflicts = summary.conflicts ?? 0
  return (
    <div>
      <h2>Summary</h2>
      <div class="chips">
        <Chip label="files" value={summary.files ?? 0} />
        <Chip label="clean" value={summary.cleanFiles ?? 0} />
        <Chip label="conflicts" value={conflicts} />
        <Chip label="auto" value={summary.autoResolutions ?? 0} tone="var(--auto)" />
        <Chip label="review" value={summary.reviewResolutions ?? 0} tone="var(--review)" />
        <Chip label="manual" value={summary.manualResolutions ?? 0} tone="var(--manual)" />
        <Chip label="replayed" value={summary.replayedDecisions ?? 0} />
        <Chip label="dry run" value={String(summary.dryRun ?? false)} />
        <Chip label="fully resolved" value={String(summary.fullyResolved ?? false)} />
      </div>
      <div class="note">
        {percent(summary.autoResolutions ?? 0, conflicts)}% of conflicts were resolved without asking;{' '}
        {percent((summary.reviewResolutions ?? 0) + (summary.manualResolutions ?? 0), conflicts)}% need a
        human.
      </div>
    </div>
  )
}

function Side({ label, text, resolved }) {
  return (
    <div class={resolved ? 'side resolved' : 'side'}>
      <div class="label">{label}</div>
      <pre>{text ?? '—'}</pre>
    </div>
  )
}

function Sides({ resolution }) {
  const sides = resolution.sides
  if (!sides) {
    return (
      <div class="note">
        This report carries only the resolved side. The three sides beside it are what step 4.2 is for, and they
        need `MergeReportWriter` to emit them from the `Conflict` it already holds.
      </div>
    )
  }
  return (
    <div class="sides">
      <Side label="base" text={sides.base} />
      <Side label="branch 1" text={sides.branch1} />
      <Side label="branch 2" text={sides.branch2} />
      <Side label="resolved" text={resolution.resolvedCode} resolved />
    </div>
  )
}

function FixPath({ fixPath }) {
  return (
    <div class="fixpath">
      <div class="desc">{fixPath.description}</div>
      {fixPath.options?.length ? (
        <ul class="options">
          {fixPath.options.map((option) => (
            <li>
              <code>{option}</code>
              {option === fixPath.recommended ? <span class="badge">recommended</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
      {fixPath.justification ? (
        <div class="kv">
          <b>why:</b> {fixPath.justification}
        </div>
      ) : null}
      {fixPath.impact ? (
        <div class="kv">
          <b>impact:</b> {fixPath.impact}
        </div>
      ) : null}
    </div>
  )
}

/**
 * What each analysis level actually read, in words a reviewer can act on.
 *
 * The report carries the level's name and the page owns the wording, because presenting it is the
 * renderer's job (DEC-027) — and because a bare `STRUCTURE` tells a reviewer nothing while "recognised
 * declarations" tells them why this resolution could outrank the one beside it.
 */
const EVIDENCE = {
  TEXT_LOCAL: "the block's own lines",
  // Step 4.11's level: the ported pass reads within the block, so it is still "the block's own lines" seen more
  // closely - and the page must not claim it read the file around the change.
  TEXT_INTRALINE: "the block's own lines, word by word",
  TEXT_FILE: 'the file around the change',
  STRUCTURE: 'recognised declarations',
  PLATFORM_TYPES: 'resolved platform types',
  PROJECT_TYPES: 'resolved project types'
}

/**
 * What kind of change a conflict is, in the page's own words (plan step 4.12).
 *
 * <p>Beside the domain type, never instead of it: "import addition, both sides inserted" is two facts a reviewer
 * needs, and the second says whether anything needs choosing at all.
 */
const SHAPE = {
  INSERTED: 'inserted',
  DELETED: 'deleted',
  MODIFIED: 'changed',
  CONFLICT: 'both sides changed it'
}

/**
 * How the comparison treated whitespace, in the page's words (plan step 4.10).
 *
 * <p>Only the lenient policies need a phrase: `DEFAULT` is what a reader assumes when nothing is said, so saying it
 * would add a line to every card and train the reader to skip the line that matters.
 */
const POLICY = {
  TRIM_WHITESPACES: 'ignoring line edges',
  IGNORE_WHITESPACES: 'ignoring whitespace'
}

function Resolution({ resolution, filePath, conflict }) {
  const region = resolution.region
  return (
    <div class={`card ${resolution.kind}`}>
      <div class="top">
        <span class="type">{resolution.type}</span>
        {/* The SHAPE beside the domain type (plan step 4.12): "import addition, both sides inserted" is two facts,
            and the second is what tells a reviewer whether anything needs choosing. The wording is the page's
            (DEC-027); the report carries the name. */}
        {conflict?.shape && conflict.shape !== 'UNKNOWN' ? (
          <span class="shape">{SHAPE[conflict.shape] ?? conflict.shape}</span>
        ) : null}
        <span class="badge">{resolution.kind}</span>
        <span class="tail">
          {resolution.strategy}
          {resolution.analysisLevel
            ? ` · evidence: ${EVIDENCE[resolution.analysisLevel] ?? resolution.analysisLevel}`
            : ''}
          {resolution.verification ? ` · verified: ${resolution.verification}` : ''}
          {/* The comparison policy, shown only when it was LENIENT (plan step 4.10). A decision reached while
              ignoring whitespace is a different answer from the strict one, so a reviewer reading it must know —
              and under the strict default there is nothing to say, which is why this is not on every card: a fact
              shown everywhere stops being read. The wording is the page's (DEC-027). */}
          {resolution.whitespacePolicy && resolution.whitespacePolicy !== 'DEFAULT'
            ? ` · compared ${POLICY[resolution.whitespacePolicy] ?? resolution.whitespacePolicy}`
            : ''}
          {region ? ` · lines ${region.startLine}–${region.endLine}` : ''}
          {resolution.sticky ? ' · sticky (a recorded decision)' : ''}
          {resolution.independentlyApplicable === false ? ' · not independently applicable' : ''}
        </span>
      </div>
      {resolution.explanation ? <div class="explanation">{resolution.explanation}</div> : null}
      {resolution.warnings?.length ? (
        <ul class="warnings">
          {resolution.warnings.map((warning) => (
            <li>{warning}</li>
          ))}
        </ul>
      ) : null}
      <Sides resolution={resolution} />
      {resolution.fixPaths?.length ? (
        <div>
          <h3 style="margin-top:12px">Fix paths</h3>
          {resolution.fixPaths.map((fixPath) => (
            <FixPath fixPath={fixPath} />
          ))}
        </div>
      ) : null}
      {isActionable(resolution) ? <Actions filePath={filePath} resolution={resolution} conflict={conflict} /> : null}
    </div>
  )
}


/**
 * One conflict block of a file, with the resolutions that answer it.
 *
 * A block, not a conflict, is the unit a reviewer works in: a block can disagree in more than one way at once, the
 * tool refuses to compose several conflicts of one block into a single answer, and an answer is written back to the
 * file per block. Reading the report flat is what let a reviewer accept everything and still find the block there,
 * with nothing saying why.
 */
function Block({ block, filePath }) {
  return (
    <div class={`block${block.severalConflicts ? ' several' : ''}${block.undecided ? ' needs-you' : ''}`}>
      <div class="blockhead">
        <span class="blocktitle">{blockLabel(block)}</span>
        <span class="blockstate">
          {block.entries.length === 1 ? '1 conflict' : `${block.entries.length} conflicts`} · {block.decided}{' '}
          decided
          {block.undecided ? ` · ${block.undecided} needs you` : ''}
        </span>
      </div>
      <div class="blocknote">{blockNote(block)}</div>
      {block.entries.map(({ resolution, conflict }) => (
        <Resolution resolution={resolution} filePath={filePath} conflict={conflict} />
      ))}
    </div>
  )
}

function FileSection({ file }) {
  const blocks = blocksOf(file)
  return (
    <div class="file">
      <div class="path">{file.filePath}</div>
      <div class="meta">
        branch {file.branchName} · {file.clean ? 'clean' : 'conflicts'} · {file.summary}
      </div>
      {blocks.length ? (
        blocks.map((block) => <Block block={block} filePath={file.filePath} />)
      ) : (
        <div class="note">No resolutions recorded for this file.</div>
      )}
      {file.conflicts?.length ? (
        <div class="conflicts">
          conflicts: {file.conflicts.map((conflict) => `${conflict.type} (${conflict.handling})`).join(', ')}
        </div>
      ) : null}
    </div>
  )
}

function App() {
  const files = report.files ?? []
  return (
    <div>
      <h1>
        merge-java resolution report{' '}
        {isSample ? <span class="badge sample">sample data</span> : <span class="badge">live report</span>}
      </h1>
      <div class="source">
        schema v{report.schemaVersion} · from <code>{reportSource}</code>
      </div>
      <ExportBar branchName={files[0]?.branchName ?? ''} />
      <Summary summary={report.summary ?? {}} />
      <h2>Files</h2>
      {files.length ? files.map((file) => <FileSection file={file} />) : <div class="note">No files in this report.</div>}
    </div>
  )
}

/**
 * The export half of the action display (plan step 4.3).
 *
 * A reviewer's accepted decisions stay in the page until they are exported: that is the whole reason this page
 * needs no host, and the reason an accepted decision cannot change a branch by itself. `DecisionRecorder` is what
 * turns the file this produces into stored decisions that the next merge replays.
 */
function ExportBar({ branchName }) {
  const count = $accepted().length
  // Static, and that is correct: what already has an answer does not change while the page is open.
  const resolvable = acceptAllResolved(report.files ?? []).length
  // Where the payload goes, and what happened to it. A served page posts it; a `file://` page downloads it,
  // because there is nothing to post to — and either way the reviewer is told which happened.
  const $outcome = signal('')
  const served = isServed()
  return (
    <div class="exportbar">
      <span>
        <b>{count}</b> decision(s) accepted
        {resolvable ? <> of {resolvable} already resolved</> : null}
      </span>
      <button onclick={() => $accepted(mergeAccepted($accepted(), acceptAllResolved(report.files ?? [])))}>
        Apply all resolved ({resolvable})
      </button>
      <button
        onclick={() => {
          $outcome('')
          void deliverDecisions(
            buildDecisions({
              branchName,
              accepted: $accepted(),
              rejected: $rejected(),
              repoPath: repoRoot,
            }),
            $outcome,
          )
        }}
        disabled={() => $accepted().length === 0}
      >
        {served ? 'Send decisions' : 'Download decisions.json'}
      </button>
      <button onclick={() => $accepted([])} disabled={() => $accepted().length === 0}>
        Clear
      </button>
      {served ? (
        <span class="note">
          the server records them with DecisionRecorder; nothing is applied to a branch by this page
        </span>
      ) : (
        <span class="note">
          then: bun run merge-java/scripts/merge-report/review-file.js &lt;your file&gt; --apply-decisions
          &lt;the downloaded json&gt; --branch {branchName || '<branch>'} — this page stays standalone: no host, no
          server, nothing to start
        </span>
      )}
      {$outcome ? <span class="export-outcome">{$outcome}</span> : null}
    </div>
  )
}

insert(document.getElementById('app'), <App />)
