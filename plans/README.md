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

**Still live as of 2026-10-08 — 74 of its 92 steps are done, and the 18 that are not wait on people rather than on the schedule:** the maintainer's decisions (5.2, 5.4, 6.1, 6.3), the phases that follow them (5.3, 6.2, 6.4, 6.5), the human observation steps (8.1–8.4), the three `[TBD]` steps left in phase 3 (3.8, 3.9 — the `jsx6`/nodditor pages, which wait on a checkout — and 3.11, a decision deliberately not taken yet), one in-progress step with a named remainder (4.19) and the final cache validation (**9.8**, the build-cache validation the maintainer asked to keep for the very end). Everything else — the engine, the migration, the JetBrains port, the suggestion channel, the documentation split and the webview suite — is closed.

The plan therefore **stays here** rather than moving to [`../archive/`](../archive): step 9.6's own rule is that a schedule with open steps is a live document, applied to its own answer.
