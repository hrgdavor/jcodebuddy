# `plans/`

Effort-sized plans and the repository's single schedule. Read the schedule first.

| File                                                                 | What it is |
| -------------------------------------------------------------------- | ---------- |
| [`unified-plan.md`](unified-plan.md)                                 | **The live schedule.** Every open item in the repository, one gated step per commit, ending with the plan-cleanup phase. If you are looking for "what is left to do", this is the file. |
| [`rewrite-migration/`](rewrite-migration/README.md)                  | The **complete** JavaParser → OpenRewrite migration: the plan as it was written, the phase documents, and the verified delivery record. Historical; its README says what shipped. |
| [`enumset-overlap-jmh-plan.md`](enumset-overlap-jmh-plan.md)         | The `EEnumSet` service-overlap benchmark: mission, method matrix, parity guards, and where the evidence landed. |
| [`inject-examples-feature-port.md`](inject-examples-feature-port.md) | The port of the documentation-injection mechanism to the published `@hrg/inject-examples` CLI. Status header at the top: the port happened. |

**Conventions.** A plan states its own status in a banner at the top, with a date, and links to the record
that proves it. A plan whose work is done is kept as history or moved to [`../archive/`](../archive); a plan
whose work is open appears as steps in [`unified-plan.md`](unified-plan.md). Anything an agent *runs* lives
in `scripts/` as Bun JavaScript — never here, and never as a shell script.

**Still live as of 2026-10-10 — slimmed on 2026-10-09, and 11 step ids are open.** The 91 finished step records now
live in [`../archive/plans/unified-plan-2026-10.md`](../archive/plans/unified-plan-2026-10.md), so
[`unified-plan.md`](unified-plan.md) answers one question — what is left — in ~700 lines instead of ~7,000.
What remains: **3.11 is closed, implementation and all** — its **design landed on 2026-10-10** as
[`DEC-049`](../doc-hipster-entity/architecture/decisions/DEC-049.md), the maintainer **accepted it the same day**,
and the two steps it scheduled were **done the same day**: **3.11a** (the graph page joins the class index and
navigates; `hipster-ioc-graph/src/locations.js` + `navigate.js`) and **3.11b** (the outside-the-browser entry
point, documented in `webview-host-api.md` § 4c and verified by `webview/tools/check-open-route.js`). What
DEC-049 leaves open is its two **optional** steps, **3.11c** (the generator emitting a bean FQN, only if beans
should be clickable) and **3.11d** (wiring the page build into the recorded pass). The rest: an ACP spike (5.3, a
person); five human observations on a real editor (8.1–8.5); and three steps added on 2026-10-09 from the 6.1–6.5
refinement — **6.8** (nested, collection and polymorphic patch application), **6.9** (the converter manifest) and
**6.10** (the source → metadata JSON pass), which are the remainders steps 6.1, 6.2 and 6.4 left behind while
being closed as steps.

Step 8.5 (Zed editor integration) was added the same day: Zed is the one editor in the suite that is neither
JetBrains nor VS Code, and the only one with a first-class agent protocol, so it gets its own survey and decision.

The plan therefore **stays here** rather than moving to [`../archive/`](../archive): step 9.6's own rule is that a schedule with open steps is a live document, applied to its own answer.
