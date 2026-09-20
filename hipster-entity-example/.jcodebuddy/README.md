# `.jcodebuddy/` — this module's JCodeBuddy output

This directory exists because **this module applies `project-automation`**. It is not a
repo-wide convention: a module that does not use JCodeBuddy has no `.jcodebuddy/`, and no
tool may create one on its behalf. The repository root gets a `.jcodebuddy/` only in the
rare case of a repo-wide generator.

Everything JCodeBuddy or a watch agent writes for this module lands here, split by
purpose so it is obvious what may be committed and what must not be:

    .jcodebuddy/
    ├── README.md      this file — tracked, because it is what tells the next reader what the tree means
    ├── context/       tracked   module-scoped specs and invariants for its generators
    ├── metadata/      derived   machine-written reports and caches
    │   ├── entity/    <Entity>.metadata.json, written by the hipster-entity generator
    │   ├── watch/     the watch agent's metadata.db plus its audit/ trail
    │   └── project/   whole-project metadata indexes (index.fury and friends)
    ├── reports/       tracked   human-read run records worth keeping
    └── agent-state/   ignored   scratch: run logs, probes, temp copies

## Track policy

`.jcodebuddy/.gitignore` is the authority; this prose only explains it.

| Subtree | Policy | Why |
|---|---|---|
| `context/`, `reports/` | **tracked** | the "why" and the evidence; small, text, reviewable |
| `metadata/` | **ignored** | machine-written and regenerable; a whole-tree commit here is churn. Opt a subtree back in with a `!` rule in `.jcodebuddy/.gitignore` when a project wants its metadata committed as a contract |
| `agent-state/` | **ignored** | scratch, never a deliverable |

## What must *not* move here

**Generated `.java` stays under `src/main/java`.** `AGENTS.md` §1 / DEC-019 require
generated wiring to be committed, IDE-navigable source; a dotted directory is
conventionally tool state and moving program source there would break
jump-to-definition and "find usages". This directory holds the *reports about*
generation, never the generated code that is part of the program.

## How the locations are decided

`EntityRegenerationWatcher.Config.defaultReportDir` walks up from the source root to the
nearest `.jcodebuddy` directory, falling back to the nearest `pom.xml` so a not-yet-
converted module still gets a predictable path, and to the system temp directory for a
source tree outside any project. `.jcodebuddy` wins when both markers are present,
because it can sit beside a submodule's sources while the nearest `pom.xml` may belong
to an aggregator.
