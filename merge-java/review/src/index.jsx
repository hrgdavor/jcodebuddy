import { insert } from '@jsx6/jsx6'
import { report, reportSource, isSample } from '../.generated/report.js'

/**
 * The merge-java resolution review page (plan step 4.2), as a jsx6 app.
 *
 * It is a reader of the model `MergeReportWriter` writes and nothing else: it parses no Java, decides
 * nothing, and writes nothing (4.3 is the step that adds actions). Every value it shows comes from
 * `merge-report.json`, so a reviewer sees what was decided and why rather than a count.
 *
 * Two deliberate behaviours worth keeping:
 *  - **the data's origin is on the page** — the source path, and a `sample` badge when the build had
 *    no real report to inline, so a screenshot can never be mistaken for a run's evidence;
 *  - **a missing field is said out loud** — a report that does not carry the three sides yet renders
 *    a note pointing at 4.2 instead of silently showing one column.
 */

const percent = (part, whole) => (whole === 0 ? 0 : Math.round((part / whole) * 100))

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

function Resolution({ resolution }) {
  const region = resolution.region
  return (
    <div class={`card ${resolution.kind}`}>
      <div class="top">
        <span class="type">{resolution.type}</span>
        <span class="badge">{resolution.kind}</span>
        <span class="tail">
          {resolution.strategy}
          {resolution.verification ? ` · verified: ${resolution.verification}` : ''}
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
    </div>
  )
}

function FileSection({ file }) {
  return (
    <div class="file">
      <div class="path">{file.filePath}</div>
      <div class="meta">
        branch {file.branchName} · {file.clean ? 'clean' : 'conflicts'} · {file.summary}
      </div>
      {file.resolutions?.length ? (
        file.resolutions.map((resolution) => <Resolution resolution={resolution} />)
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
      <Summary summary={report.summary ?? {}} />
      <h2>Files</h2>
      {files.length ? files.map((file) => <FileSection file={file} />) : <div class="note">No files in this report.</div>}
    </div>
  )
}

insert(document.getElementById('app'), <App />)
