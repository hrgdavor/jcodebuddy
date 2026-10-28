# `plans/`

Effort-sized plans and the repository's single schedule. Read the schedule first.

| File                                                                 | What it is |
| -------------------------------------------------------------------- | ---------- |
| [`unified-plan.md`](unified-plan.md)                                 | **The live schedule.** Every open item in the repository, one gated step per commit, ending with the plan-cleanup phase. If you are looking for "what is left to do", this is the file. |
| [`tbd.md`](tbd.md)                                                   | **Deferred work, deliberately not scheduled.** ACP (5.3, 8.4 and 8.5's ACP item) was moved here on 2026-10-28; pick a step up by copying it back to the live schedule. |
| [`rewrite-migration/`](rewrite-migration/README.md)                  | The **complete** JavaParser → OpenRewrite migration: the plan as it was written, the phase documents, and the verified delivery record. Historical; its README says what shipped. |
| [`enumset-overlap-jmh-plan.md`](enumset-overlap-jmh-plan.md)         | The `EEnumSet` service-overlap benchmark: mission, method matrix, parity guards, and where the evidence landed. |
| [`inject-examples-feature-port.md`](inject-examples-feature-port.md) | The port of the documentation-injection mechanism to the published `@hrg/inject-examples` CLI. Status header at the top: the port happened. |

**Conventions.** A plan states its own status in a banner at the top, with a date, and links to the record
that proves it. A plan whose work is done is kept as history or moved to [`../archive/`](../archive); a plan
whose work is open appears as steps in [`unified-plan.md`](unified-plan.md), and a step deliberately deferred
for later goes to [`tbd.md`](tbd.md). Anything an agent *runs* lives
in `scripts/` as Bun JavaScript — never here, and never as a shell script.

**Still live as of 2026-10-28 — slimmed on 2026-10-09, and 4 step ids are open here (2 more are in [`tbd.md`](tbd.md)).** The 91 finished step records now
live in [`../archive/plans/unified-plan-2026-10.md`](../archive/plans/unified-plan-2026-10.md), so
[`unified-plan.md`](unified-plan.md) answers one question — what is left — in ~700 lines instead of ~7,000.
**5.2 and 5.4 were closed on 2026-10-26** by the decisions their gates named — Q3 keeps the shared core, and Q2
accepts `dropins/` and declines Eclipse Phase 5 — after the slimming had counted them finished without a dated
record. **6.8, 6.9 and 6.10 were closed the same day, and 3.11c/3.11d followed**: the builders now implement their
view's `Write` so the patch applier recurses through child builders, the root family has a generated dispatcher, the
metadata JSON carries a field's constraints, the converter manifest is written by the pass and committed,
`bun scripts/ioc-gen.js --graph` regenerates and verifies the graph page, and the graph's bean types are FQNs.
What remains: **3.11 is closed, implementation and all** — its **design landed on 2026-10-10** as
[`DEC-049`](../doc-hipster-entity/architecture/decisions/DEC-049.md), the maintainer **accepted it the same day**,
and the two steps it scheduled were **done the same day**: **3.11a** (the graph page joins the class index and
navigates; `hipster-ioc-graph/src/locations.js` + `navigate.js`) and **3.11b** (the outside-the-browser entry
point, documented in `webview-host-api.md` § 4c and verified by `webview/tools/check-open-route.js`). What
DEC-049's two optional steps, **3.11c** (bean types as FQNs) and **3.11d** (the page build in the recorded pass),
were both closed on 2026-10-26. The rest here: five human observations on a real editor (8.1–8.5); 8.1's five
decisions were answered on 2026-10-28, 8.2 is blocked on an interested Eclipse user, and 8.5's only open item is the
ACP path. **ACP itself — 5.3 and 8.4 — was moved to [`tbd.md`](tbd.md) on 2026-10-28 for later**, and 8.5's ACP item
is recorded there too. The last agent remainder, **6.9** (the converter manifest), was closed on 2026-10-26.

Step 8.5 (Zed editor integration) was added the same day: Zed is the one editor in the suite that is neither
JetBrains nor VS Code, and the only one with a first-class agent protocol, so it gets its own survey and decision.

The plan therefore **stays here** rather than moving to [`../archive/`](../archive): step 9.6's own rule is that a schedule with open steps is a live document, applied to its own answer.
