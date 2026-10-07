# What `jsx6` and `nodditor` can and cannot do for this repository's pages

> **Step 7.10 of [`plans/unified-plan.md`](../plans/unified-plan.md)**, and the discharge of the reporting duty in
> [`AGENTS.md` § 2](../AGENTS.md): *when `jsx6` or `nodditor` cannot do something a page needs, report it* — as a
> minor improvement that belongs upstream, or as a critical gap that forces a decision about another library for that
> specific output.

**The commit read:** `a584e7a` (`a584e7add16aba3cee4be78ad56ab6ce5b906621`, 2026-10-02, *"document usage"*), the
checkout at `.jsx6/` that step 7.9 set up (gitignored; update with `git -C .jsx6 pull --ff-only`). **What I read:**
`AGENTS.md`, `docs/stack/README.md`, `docs/stack/agent-rules.md` in the checkout, plus the **source** of the libraries
each page class needs — because a capability claimed only in a README is a capability nobody has run. Where a finding
rests on a README alone, it says so.

**The page classes** are the ones step 7.9's inventory and DEC-027's 2026-10-01 amendment name. Whether a page is
"simple" or "advanced" is a judgement, and per the maintainer's 2026-10-02 decision **no guard enforces it**; this
document is the evidence for the judgement, not a check.

---

## 1. The minimal vanilla page — the entity reference, the merge summary

**Requirements.** Render a document, show/hide a few blocks, links and anchors. No state beyond a document, no
per-item workflow.

**Verdict: not applicable, by decision rather than by gap.** DEC-027's amendment keeps this class framework-free: it is
a self-contained file with no bundler, no `node_modules`, no CDN and no network at view time, and
`HtmlRenderBoundaryTest` holds that contract for the entity reference page. `jsx6` is therefore **not required here**,
and nothing in this assessment asks it to be — the class is listed because the step names it, and a reader should be
able to see that its exclusion was checked rather than overlooked.

## 2. The interactive review page — the per-conflict review render (steps 4.2/4.3)

**Requirements, from the page as built** (`merge-java/review/`): one card per conflict; accept / edit / reject per
conflict; a bulk accept that deliberately skips suggestions; the proposed result prefilled for editing; provenance,
confidence, evidence level, verification verdict and warnings shown beside each answer; the comparison policy shown
when it was lenient; an export of the reviewer's decisions as a file.

**What the page uses today**, from its own imports: `insert` from `@jsx6/jsx6` and `signal` from `@jsx6/signal` —
the base stack the checkout's `docs/stack/README.md` treats as the whole instruction set. The page is one
self-contained bundle built outside `scripts/`, per step 7.9's finding that a `file://` page cannot load ES modules
or `file:`-linked workspace packages.

**Verdict: sufficient**, with three named caveats.

- **Signals and per-node binding cover the whole interaction model.** `signal` is a function, there is no compiler
  pass and no virtual DOM; an update writes the node it was bound to. The page's state (accepted / edited / rejected
  per conflict, plus the editor's text) is exactly that shape, and it needs no more.
- **A code editor is available and unused.** `libs/editor-monaco/` wraps Monaco, worker files included
  (`libs/editor-monaco/editor.worker.js` re-exports `monaco-editor/esm/vs/editor/editor.worker`). The page edits a
  suggestion in a plain field, which is **sufficient**; Monaco is the route if syntax highlighting or a diff view is
  wanted later. That is a page improvement, not a gap: nothing the page must do is blocked.
- **`libs/virtual-scroll/` cannot render variable-height rows, and the review page must not use it as it stands.**
  This is the one finding here that is a real limitation rather than a preference, and it is a limitation of the
  *implementation*, not of its documentation: `libs/virtual-scroll/src/virtual-scroll.js` declares
  `@property {number} itemHeight - The fixed height of each row in pixels`, places row *i* at
  `i * itemHeight + offsetTop`, and computes the visible window by dividing by it (lines 4, 9, 37, 74, 77). The
  page's cards are **not** uniform — an explanation, a code block, warnings and fix paths differ in height per
  conflict. **Classification: minor** (`virtual-scroll` is not needed at merge-report sizes, so no page change is
  forced today; the library would need variable-height support — measurement or estimated heights — to be usable for
  such a list). If a merge report ever grows past what a plain list can render, this becomes the thing to fix, and it
  belongs upstream in `libs/virtual-scroll`, not in a page-specific workaround.

## 3. The project-structure / navigation page — the dependency page (step 3.8)

**Requirements, from the step that schedules it:** navigate a project's structure, list and filter the entries,
select one, collapse and expand groups, and keep the location linkable.

**Verdict: sufficient, on a prospective reading.** The page does not exist yet, so its capability list is the step's
description rather than code, and this verdict is a prediction rather than a measurement — said plainly because the
difference matters to whoever builds it. What supports it: the base stack for rendering and state, and
`libs/url-util/` for linkable locations (the checkout's `docs/stack/README.md` and `docs/stack/agent-rules.md` are
where the details are, and the page author must read them before writing JSX — this assessment does not restate
them). No gap identified; a gap found while building it is reported here rather than worked around.

## 4. The diagram / relations layer — `nodditor` (step 3.8)

**Requirements:** nodes with labels, typed edges, pan and zoom, selection, click-through to the node's own
documentation, and legible line widths at every zoom level.

**Verdict: sufficient, and this class is where the checkout's implementation has the most depth behind it.**

- `apps/nodditor/README.md` describes the app as a node-graph editor whose "blocks are JSX6 components", placed on "a
  zoomable/pannable canvas", with connector discovery, line drawing, "selection, drag-connect, keyboard editing and
  undo/redo" — and the source bears it out: `apps/nodditor/src/NodeEditor.jsx`, `lineLayer.js`,
  `canvasLineLayer.js`, `LineInteraction.js`, `ConnectLine.js`, `makeLineConnector.js`, plus
  `EditableTitle.js` and `moveMenu.js` for per-node editing.
- **The line-width question is already answered in code, and the answer is a policy we can adopt rather than
  rediscover.** `apps/nodditor/src/canvasLineLayer.js` (lines 37, 46, 109-112) documents that the canvas layer
  scales with zoom exactly like the SVG layer's `stroke-width`, that widths are `css * devicePixelRatio`, and how the
  viewport re-derives both — which is the same ground the checkout's own `AGENTS.md` covers when it reports its
  measured `vector-effect: non-scaling-stroke` experiment. A diagram page here should take that policy from
  `nodditor` rather than re-derive it.
- `libs/line-render/` is the rendering layer beneath it: `index.js` exports `LineRenderer`, the shaders
  (`BLIT_SHADER`, `LINE_SHADER`), `makeConnector`, `edgeToPath`, and the shape helpers (`lineEdge`, `circleEdges`,
  `polygonEdges`). So the two outputs a relations page needs — connectors and shapes — have both a WebGL renderer
  and a canvas layer to choose from.
- **One build consideration, not a gap:** `apps/nodditor` ships **raw source** (`index.js`, `src/`) plus generated
  type declarations. A consumer page therefore bundles source it does not own, exactly as the review page already
  bundles `jsx6`'s source — which step 7.9's aliasing approach covers, and which is the opposite of pinning a
  published version.

---

## What would change a verdict

- **A page needing variable-height virtualisation** turns finding 2's minor into a required upstream change, or into
  the decision the rule describes about another library for that output.
- **A diagram needing more than nodes, edges, pan/zoom, selection and undo** — routing, grouping, live layout — is
  the next question for `nodditor`, and it is answered by reading `apps/nodditor/src/` at the commit then current,
  not by this document.
- **A page that needs a second framework's ecosystem** (component libraries, form tooling) is a decision, not a gap
  in these libraries; DEC-027's amendment and `AGENTS.md` § 2.9 both route it to a recorded decision.

**No critical gap is claimed by this assessment, so no decision about an additional library is requested.** The one
substantive limitation found is `virtual-scroll`'s fixed row height, classified **minor** with the reason (the class
of page that would hit it does not need virtualisation at its current size).

## Operating notes worth carrying, from the checkout's own `AGENTS.md`

Three of these cost this repository time already, and one cost it again while writing this:

- **Headless Chrome needs unrestricted file access.** Under confined sandbox modes it cannot start (crashpad and
  Chromium IPC need named pipes), so *"the page is blank"* can be a sandbox fact rather than a rendering bug — and
  the checkout's screenshot technique (`chrome --headless=new … --screenshot`, PNG decoded with `node:zlib`, with
  calibration bars of known width) is what catches a blank page instead of shipping it.
- **`command 2>&1 | Select-String …` fakes a non-zero exit in PowerShell.** This repository's gate runs hit that
  repeatedly; check `$LASTEXITCODE` or redirect to a file.
- **Do not rewrite text files through a PowerShell redirect.** A redirect re-encodes (UTF-16 with a BOM under Windows
  PowerShell), and the symptom is a `SyntaxError` on line 1 of a file that is fine — the same trap `AGENTS.md` § 2
  records for this repository, found in the checkout independently.
