# DEC-031: Project automations are living code, never dynamically loaded artifacts

- Status: Accepted
- Date: 2026-09-26
- Owners: project
- Related docs: [DEC-019 — Source-Visible, IDE-Navigable Wiring](DEC-019.md), [DEC-020 — Cooperative codegen](DEC-020.md), [DEC-021 — Generator class-file header](DEC-021.md), [DEC-022 — Refactor-sensitivity and divergence reporting](DEC-022.md), [DEC-026 — `.jcodebuddy/` per module](DEC-026.md), [`AGENTS.md`](../../../AGENTS.md) § 1 and § 2, [`webview/jwa-sidecar/README.md`](../../../webview/jwa-sidecar/README.md), [`webview/jwa-sidecar/modules.md`](../../../webview/jwa-sidecar/modules.md), [`webview/PLAN-webview-suite.md`](../../../webview/PLAN-webview-suite.md)
- Supersedes: the `jwa-sidecar.txt` addon-file mechanism described in [`webview/jwa-sidecar/README.md`](../../../webview/jwa-sidecar/README.md) (documented, never implemented — no code in any language ever read that file), and the "addon host" framing of the sidecar
- Superseded by: -

## Context

The sidecar's README described a project-level `jwa-sidecar.txt`: one Maven GAV or local path per line, resolved
at startup and loaded into the running sidecar. `modules.md` recorded it as **done**. Neither was true: a search
of the entire history for that file name finds only the documents that describe it, because the builder is a
compile-time dependency of the sidecar (`jwa-builder` and `jwa-builder-api` in its `pom.xml`) and
`JwaTextDocumentService` calls it directly.

That left a choice, and it was put to the maintainer as one: implement the mechanism (a classloader over the
listed GAVs and paths, plus an SPI describing what an addon contributes — which does not exist either), or
withdraw the claim. The answer was neither, and it reframes the problem:

> classloader complexity should be moved towards the `project-automation` concept. Instead of adding classloader
> complexity, make the sidecar and other project automations as living code, that for start may be just a
> starting stub that is generated for a project based on some initial requirements, or user copies project
> automation example from other projects (own, or online).

This repository already works that way for its own automations. `hipster-entity-example` and
`project-automation` are ordinary modules: hand-written code, generated code committed beside it, no registry,
no scanning, no loader — and `scripts/gen.js` runs the generator against them by exporting the reactor classpath
with `dependency:build-classpath` and starting a JVM with `java -cp`. The `jwa-sidecar.txt` mechanism would have
added a second, invisible way of getting code into the same process.

## Decision

1. **An automation is a module in the project it automates.** It lives under that project's source tree
   (`src/main/java`, DEC-026), is compiled by that project's own build, and is reviewable in the same diff as
   everything else. "Addon" stops being a word for an artifact and becomes a word for code.
2. **Nothing is loaded dynamically.** No classloader over GAVs or paths, no `META-INF/services`, no annotation
   scanning, no reflective instantiation. A host that needs a project's automations gets them **on its classpath
   when it is launched** — the mechanism the generator pass already uses — or the automation runs as part of that
   project's build.
3. **The wiring is generated into the project and committed.** If a host must know which automations exist, an
   explicit registration class is generated into the automation module (a direct construction or call per
   automation), so a stock IDE can follow entry point → automation by jump-to-definition (DEC-019). Generated
   files carry the DEC-021 header and follow DEC-020's cooperative rules: the user edits them freely, deleting a
   block is how regeneration is requested, and a pass reports divergence (DEC-022) rather than overwriting
   silently.
4. **Bootstrap is generation or copying, not installation.** A new project's automation module is either
   **generated as a starting stub** from a few initial requirements (which markers, which languages, which
   automation kinds), or **copied from an example** — another of the user's projects, or one published online —
   and then edited. Both produce ordinary source, which is the point: the starting point is readable and
   changeable, and there is no artifact to resolve.
5. **`jwa-sidecar.txt` is withdrawn.** Not implemented, and not to be implemented. The sidecar stops describing
   itself as an addon host and becomes what it is: an LSP transport whose behaviour comes from the project's
   automation module, present on its classpath.

## Consequences

- **Positive.** There is one way code enters the process, and it is the visible one. Automations are debuggable
  and refactorable with a stock IDE (DEC-019 § 3's test), appear in code review, and version with the project
  they belong to. It also removes a real security surface: a file in a repository that makes a running process
  load and execute arbitrary code, resolved from a path a repository can change.
- **Positive.** Reuse keeps working, by the two routes the maintainer named — generate a stub, or copy an example
  — both of which produce code the user owns rather than a dependency they cannot see into.
- **Negative, and accepted.** You cannot drop a prebuilt jar into a project and have the sidecar pick it up; a
  project without an automation module must be given one (generated or copied) before it has automations. That is
  the cost of the visibility rule, and it is the same cost DEC-019 already accepted everywhere else.
- **Negative, and mitigated.** A copied example drifts from its origin. The mitigation is the one this repository
  already built for generated code: DEC-022's divergence report names what differs, and DEC-021's header makes it
  clear which parts a generator owns.
- **Follow-up work this creates** (none of it is done by this decision): a stub generator that asks the initial
  requirements and emits a compiling automation module (POM, an example automation, the generated registration);
  a documented example to copy; and the sidecar's launch path that puts a project's automation module on its
  classpath.
