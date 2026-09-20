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
    │   ├── entity/    <Entity>.metadata.json and generation.json, written by the hipster-entity generator
    │   ├── watch/     the watch agent's metadata.db plus its audit/ trail
    │   └── project/   whole-project metadata indexes (index.fury and friends)
    ├── index/         derived   the module's class index: classes.json (FQN → row) that the documents above
    │                            reference, plus the mtimes.json pre-filter sidecar
    ├── reports/       tracked   human-read run records worth keeping
    └── agent-state/   ignored   scratch: run logs, probes, temp copies

## Track policy

`.jcodebuddy/.gitignore` is the authority; this prose only explains it.

| Subtree | Policy | Why |
|---|---|---|
| `context/`, `reports/` | **tracked** | the "why" and the evidence; small, text, reviewable |
| `metadata/` | **ignored** | machine-written and regenerable; a whole-tree commit here is churn. Opt a subtree back in with a `!` rule in `.jcodebuddy/.gitignore` when a project wants its metadata committed as a contract |
| `index/` | **ignored** | derived like `metadata/`, and **coupled to it**: a metadata document names source files by fully qualified type names that resolve only in `index/classes.json`, so an opt-in for the metadata subtree must carry the index too. The `mtimes.json` sidecar beside the table is machine-local and never a correctness input |
| `agent-state/` | **ignored** | scratch, never a deliverable |

The opt-in that makes a subtree tracked is written in `.jcodebuddy/.gitignore`. Because the
metadata and the index are coupled, the two snippets travel **together** — opting the documents in
without the table would commit documents whose every file reference is dangling:

```gitignore
!metadata/entity/
!metadata/entity/**

!index/
!index/**
```

Every ignored subfolder keeps a tracked `README.md` (`!metadata/**/README.md`, `!index/**/README.md`),
so the taxonomy survives in git even when the contents do not. `index/README.md` is tracked and
human-owned: a generation pass creates it when it is absent and **never overwrites** it.

## What must *not* move here

**Generated `.java` stays under `src/main/java`.** `AGENTS.md` §1 / DEC-019 require
generated wiring to be committed, IDE-navigable source; a dotted directory is
conventionally tool state and moving program source there would break
jump-to-definition and "find usages". This directory holds the *reports about*
generation, never the generated code that is part of the program.

**And a report records a path, never a copy of a file.** The module's class index
(`index/classes.json`) names the source file a type lives in (module-relative, `src/main/java/…`)
and the metadata documents name that type by its fully qualified name, which resolves there — that
is what a consumer needs to open it — but neither the metadata, the index nor the HTML page quotes a
Java file's text. An older tooling
did drop thirteen generated `.java` files into `metadata/entity/hr/…` (a pre-`--java-out` run; see
`codebuddy.md` § 6.1); they have been deleted, `GeneratorGuardTest` asserts that no `.jcodebuddy/`
tree holds a `.java` and that a pass leaves only JSON in its report directory, and `.gitignore`
below keeps a stray `.java` ignored even if this subtree is ever opted in as a committed contract.

## How the locations are decided

`EntityRegenerationWatcher.Config.defaultReportDir` walks up from the source root to the
nearest `.jcodebuddy` directory, falling back to the nearest `pom.xml` so a not-yet-
converted module still gets a predictable path, and to the system temp directory for a
source tree outside any project. `.jcodebuddy` wins when both markers are present,
because it can sit beside a submodule's sources while the nearest `pom.xml` may belong
to an aggregator.
