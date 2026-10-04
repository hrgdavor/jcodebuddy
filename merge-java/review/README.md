# merge-java review page

The Phase 13 review display: a **jsx6** page that renders the report `MergeReportWriter` writes, so a
reviewer sees *what was decided and why* — the strategy, the verification outcome, the branches' own code
beside the result, the fix paths not taken, and the sticky decisions replayed — instead of a count.

It replaced the vanilla `scripts/merge-report/render.js` (deleted 2026-10-03). The reason is a rule rather
than a preference: DEC-027's 2026-10-01 amendment sends a page with **per-item review workflows** to
`jsx6`, and keeps vanilla for a page whose whole job is to render and hide a few blocks. This page is the
former, so it is `jsx6`, and it lives here — a package of its own — because a `jsx6` page is *built* and
therefore may declare a build dependency, while the root `scripts/` subtree stays dependency-free for the
vanilla renderers (AGENTS.md § 1).

## What it does

- Reads one `merge-report.json` and renders: a summary bar, then per file its conflicts and, per
  resolution, **base / branch 1 / branch 2 / resolved** side by side with the resolver's explanation,
  warnings, strategy, verification, sticky flag and fix paths.
- Emits **one self-contained HTML file**. Two reasons, and the second was found by measurement: the page
  opens from `file://` (double-click) and inside the JetBrains JCEF webview, and an ES module loaded with
  `<script type="module" src=…>` is **CORS-blocked on `file://`** (null origin), which renders a blank page
  that looks perfectly fine on disk. So the bundle is inlined, and the map reference is stripped with it.
- Says where its data came from. The header prints the report's path, and the badge reads `sample data`
  when the build had no real report to inline — a screenshot can never be mistaken for a run's evidence.

## Use it

```sh
bun install                                     # once: esbuild only (the jsx6 libs are aliased, never installed)

# a real report, from the merge engine's own API:
bun run ../scripts/merge-report/sample-report.js

cd merge-java/review
bun run src_build/build.js --report ../../.tmp/merge-review/merge-report.json
bun run src_build/screenshot.js --height 3000   # headless Chrome + a pixel measurement
```

`build.js` defaults to `<merge-java>/.jcodebuddy/metadata/merge-report.json` (where a real run writes it)
and falls back to the committed `sample-report.json`; naming a report that does not exist is **misuse and
exits 2** rather than silently rendering the sample. `screenshot.js` fails when the page is blank, which is
how the `file://` CORS failure above was caught rather than shipped.

## Where the jsx6 checkout comes from

`<repo>/.jsx6` — cloned from <https://github.com/hrgdavor/jsx6> and updated on demand
(`git -C ../../.jsx6 pull --ff-only`); `JCODEBUDDY_JSX6_DIR` overrides it (AGENTS.md § 2). It is gitignored,
and `src_build/esbDef.js` **aliases** `@jsx6/*` to its `libs/*/index.js` instead of installing anything: a
consumer outside the jsx6 workspace cannot `file:`-link packages that depend on each other with
`workspace:*`, and aliasing gives what the rule asks for — no `node_modules` for the stack, always the
source `git pull` last put there.

## Actions: a decision, exported and recorded

Since step 4.3 each resolution also offers a decision: pick a fix path (or keep the result), edit the code to
accept, and **Accept**. Accepted decisions are listed in the export bar at the top, and
`Download decisions.json` hands them to the browser as a file.

The page does not write them anywhere, and that is the design rather than a gap: it is opened from `file://`,
which cannot write, so the maintainer chose a separation on 2026-10-03 — the page **exports**, a command
**records**, and the next merge **replays**. It also keeps the page free of a host and of any dependency beyond
the stack itself.

```sh
# from the repository root, with the branch directory (NOT its parent - see below):
java -cp <merge-java classpath> com.codebuddy.merge.DecisionRecorder \
    --decisions ~/Downloads/decisions-feature-payments.json \
    --history .jcodebuddy/merge-history/feature-payments \
    --branch feature-payments
```

`DecisionRecorder` records each decision as a sticky replay — the same thing "remember this choice" means
everywhere else in the module — so the next update answers that conflict with it instead of re-litigating it.
It **verifies the signature** the page displayed against the one it computes from the payload's own sides, and
refuses a mismatch: the failure it prevents is a decision landing on the wrong conflict.

**The one thing to get right:** `--history` is the **branch's** directory, the same path
`MergeConflictResolver` is given, because decisions live in `<historyRoot>/decisions/`. Passing its parent
writes successfully and is never replayed, so the command prints the absolute directory it used.

Still not here: recording *into* a served page. The exported format is the contract, so a webview host could
drive the same command later without changing it.
