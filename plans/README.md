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
