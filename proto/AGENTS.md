# AGENTS.md — rules for the `proto/` driver projects

> **Scope: the `proto/` area of the JCodeBuddy checkout.** This file is read by an AI coding agent (or a
> human) who is about to work *here*. The rules for working on **JCodeBuddy itself** are the root
> [`AGENTS.md`](../AGENTS.md); the rules for one driver project are that project's **own** `AGENTS.md`,
> inside its own repository — the section [Where each file applies](#where-each-file-applies) below says
> which one wins.
>
> This file is one of only two files tracked under `proto/`. Everything else here belongs to a driver
> project and lives in that project's own git.

## What is in here

`proto/` holds **driver projects**: real projects whose build, codegen and IDE experience are exercised
while JCodeBuddy changes. They are not examples and not fixtures. `proto/README.md` is the canonical
explanation of the area — what belongs here, the tracking policy, and how to add a project. **Read it
before adding anything.**

The one sentence that governs every rule below:

> **The driver projects are consumers of JCodeBuddy, not parts of it.**

## Where each file applies

Three files, nearest wins:

| File | Applies to | Contains |
| ---- | ---------- | -------- |
| [`AGENTS.md`](../AGENTS.md) (repository root) | everything in this checkout, including `proto/` | the two rules that bind unconditionally — § 1 source-visible, IDE-navigable wiring, and § 2's Bun-JavaScript rule — plus pointers |
| **this file** (`proto/AGENTS.md`) | the `proto/` area as a whole | what a driver project is, what it must never do, and which root rules do *not* bind it |
| `proto/<project>/AGENTS.md` | **one** driver project | that project's own conventions, build commands and layout. It lives in that project's own repository and is **not** tracked here |

**Precedence, and how a conflict is resolved.** The nearer file wins for anything it states. Where the
nearer file is silent, the next one out applies. Where two files genuinely conflict, the conflict is a
bug in the documentation and must be reported rather than resolved silently — and no driver project's
rule may suspend § 1 of the root file (see [Rules that do not bend](#rules-that-do-not-bend)).

## What a driver project must never do

- **Never join this reactor.** It declares its own coordinates, parent and version, never
  `jcodebuddy-parent` as its parent, and it is never added to the root POM's `<modules>`. No command in
  the root [`README`](../README.md)'s table builds, tests or generates anything under `proto/`.
- **Never `git add -f` it.** A forced add copies another repository's sources into this one's history,
  permanently. `proto/*` already ignores it; do not defeat that.
- **Never make `proto/` a submodule.** The projects are independent checkouts, not gitlinks.
- **Never let it block a JCodeBuddy change.** A driver project is a consumer: if it is broken, that is
  information about JCodeBuddy, not a reason the gate cannot pass. The reverse is the point of the area.
- **Never put generated `.java` under its `.jcodebuddy/`.** It stays under that project's
  `src/main/java` (DEC-026). This one is guarded: `GeneratorGuardTest` walks this repository three levels
  deep and fails the build if any `.jcodebuddy/` it finds holds a `.java` file — which covers
  `proto/<project>/.jcodebuddy/`, but **not** a project nested deeper, which must keep the rule itself.
- **Nothing goes here unless it is meant to become its own repository.** Scratch code, a fixture, or a
  reproduction that is not a project belongs in the owning module's `src/test` or in `target/` scratch
  space. The test is whether it has (or will have) its own remote and history.

## Rules that do NOT bind a driver project

The root file's rules were written for JCodeBuddy's own tree. These are **JCodeBuddy-only**, and applying
them to a driver project would be wrong rather than extra-cautious:

| Root rule | Why it does not apply here |
| --------- | -------------------------- |
| "Modular multi-module Maven layout … a `project-automation` module orchestrates dev-time codegen" (root § 2) | A driver project is one project with **one** `project-automation` module, which is dev-time only and never installed or shared (DEC-031, DEC-W003). It is not the JCodeBuddy reactor's layout and must not copy it. |
| "`project-automation` … never a transitive dependency of any runtime module" (root § 2) | It **does** bind a driver project — this row is listed only because the rule is easy to mistake for JCodeBuddy's own module graph. The project's runtime modules must not depend on its `project-automation`; the project's `project-automation` must not be installed as an artifact for others. |
| Anything about `hipster-entity`, `metadata-arena`, `java-watch-*`, `webview/`, or the other JCodeBuddy modules (root § 2) | Those are *this* repository's modules. A driver project depends on JCodeBuddy as a consumer — a released artifact or a local install — and has no obligation to mirror the producer's internals. |
| `GeneratorGuardTest`'s three-level walk (root § 1, § 2) | The guard covers `proto/<project>/` at the depth it walks. A project nested deeper is outside it and must keep the DEC-026 rule on its own — the guard's silence is not permission. |
| The root `plans/`, `doc/`, `doc_knowledge/` structure | JCodeBuddy's documentation layout. A driver project keeps its own; the business-logic project keeps its design documents beside its code, which is a deliberate choice it made for itself. |

## Rules that DO bind a driver project

- **§ 1 — source-visible, IDE-navigable wiring.** A driver project is where that rule is tested against a
  real IDE, not an exemption from it. Its generated code is committed Java, under its own
  `src/main/java`, navigable with a stock IDE. See [Rules that do not bend](#rules-that-do-not-bend).
- **§ 2's Bun-JavaScript rule.** Anything an agent writes to *run* or *check* something is a `.js` file
  run with `bun`, and a `.cmd`/`.bat`/`.sh`/`.ps1` helper is not acceptable as the only way to run a
  check. This binds every project, including a driver project, for the reason the rule gives: a check
  that only runs in one shell on one operating system is invisible wiring for the workflow.
- **Derived output goes in that project's own `.jcodebuddy/`**, with its tracked/ignored split as DEC-026
  and DEC-032 describe. Generated `.java` is the exception and stays under `src/main/java`.
- **Documentation about a driver project refers to this repository by *name*** — DEC-019,
  `project-automation`, `hipster-entity` — never by a relative link. This checkout is not part of that
  project and its layout is not that project's to track.

## Rules that do not bend

Three are absolute, and a nearer `AGENTS.md` may **not** suspend them. If a driver project's own file
appears to conflict with one, that is a documentation bug to report:

1. **Source-visible, IDE-navigable wiring** (root § 1, DEC-019). A developer with only the committed
   sources and a stock IDE must be able to follow the program flow from entry point to leaf. No
   reflection-driven discovery, no proxy that hides its target, no `Map<String, Method>` dispatch.
2. **A driver project stays a consumer.** It is never part of this reactor and never depends on
   `project-automation` as a published artifact.
3. **Never `git add -f` anything under `proto/`.** History is not reversible.

## Working here: the expectation about JCodeBuddy changing

**Until JCodeBuddy reaches a major release, the expected response to a driver project that cannot be
implemented properly is to change JCodeBuddy**, not to work around it in the project. The projects here
are deliberately ahead of the library: they are how a missing API, a wrong default, or an unimplementable
convention is found.

So when you hit a wall in a driver project, the question is not "how do I make this work here" but:

1. Is the gap in JCodeBuddy — a missing generator, an annotation that cannot express this, a convention
   that does not survive a real build? **Then the change belongs in JCodeBuddy**, and the driver project
   is updated alongside it in the same change.
2. Is the gap in the driver project — its own domain, its own layout, its own dependencies? Then it is
   that project's business, and its own `AGENTS.md` decides.
3. Is it unclear? Say so and ask, rather than committing a workaround. A workaround hides the finding,
   and the finding is the deliverable.

No compatibility promise is made before the major release: APIs, generated output shape and metadata
formats may change without a deprecation cycle, and updating the affected driver project is part of the
change rather than follow-up work.
