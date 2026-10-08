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

**Still live as of 2026-10-08 — 78 of its 93 steps are done.** After the maintainer's four answers on 2026-10-08 (5.2's Q3: keep the plugins' in-process implementations; Q2: `dropins/` is acceptable, so the p2 site stays optional; 6.1's shape: annotations as metadata on the generated enum constant; 6.3: the core contract strict, the naming rule advisory) the remaining steps are **work rather than decisions**: the implementations that follow those answers (6.1, 6.2, 6.4, 6.5), four human observations on a real IDE (8.1–8.4), the `jsx6`/nodditor pages (3.8, 3.9 — they need the checkout), 3.11 (a decision deliberately not taken) and step 4.19's one named remainder. The plan stays where it is: a schedule with open steps is a live document (step 9.6's own rule).

The plan therefore **stays here** rather than moving to [`../archive/`](../archive): step 9.6's own rule is that a schedule with open steps is a live document, applied to its own answer.
