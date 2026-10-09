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

**Still live as of 2026-10-09 — 87 of its 96 steps are done, and 9 are not.** The remaining steps are **work,
one reported library gap and one deliberate non-decision**: the `jsx6` graph page (3.8 — built and verified
in its navigation half, with its diagram blocked on a `nodditor` custom-element limitation that is recorded
in the step rather than worked around), a resolver split out of 6.6 (6.7), one blocked parity item (4.13), an
ACP spike (5.3), four human observations on a real IDE (8.1–8.4), and 3.11, a decision deliberately not
taken. The plan stays where it is: a schedule with open steps is a live document (step 9.6's own rule).

The plan therefore **stays here** rather than moving to [`../archive/`](../archive): step 9.6's own rule is that a schedule with open steps is a live document, applied to its own answer.
