# `.jcodebuddy/` — this module's JCodeBuddy output

This directory exists because **this module applies `project-automation`**. It is not a
repo-wide convention: a module that does not use JCodeBuddy has no `.jcodebuddy/`, and no
tool may create one on its behalf. The repository root gets a `.jcodebuddy/` only in the
rare case of a repo-wide generator.

Everything JCodeBuddy or a watch agent writes for this module lands here. The **palette of
subfolders is open** — projects are still inventing them — and the track policy does not
depend on knowing the palette: only `conf/` is tracked by default, and every other subfolder
is derived and ignored until a project opts it in.

    .jcodebuddy/
    ├── README.md      tracked   this file — what tells the next reader what the tree means
    ├── .gitignore     tracked   the authority for the split below
    ├── conf/          TRACKED   reserved: what must survive a clone and a commit (shared settings,
    │                            naming contracts, invariants a tool must honour). A worked example is
    │                            `conf/webview.json` — `{ "port": 18882 }`, the port this project asks
    │                            its page hosts to prefer
    ├── context/       opt-in    module-scoped specs and invariants for its generators
    ├── metadata/      derived   machine-written reports and caches
    │   ├── entity/    <Entity>.metadata.json and generation.json, written by the hipster-entity generator
    │   ├── watch/     the watch agent's metadata.db plus its audit/ trail
    │   └── project/   whole-project metadata indexes (index.fury and friends)
    ├── index/         derived   the module's class index: classes.json (FQN → row) that the documents above
    │                            reference, plus the mtimes.json pre-filter sidecar
    ├── cache/         derived   the conventional name for a tool's generic cache; `metadata/` and `index/`
    │                            are the specific ones this module uses
    ├── reports/       opt-in    human-read run records worth keeping
    ├── agent-state/   derived   scratch: run logs, probes, temp copies
    └── webview/       derived   a running page host's published state: host.json (the port it bound, the
                                 editor serving it, the project), the token, and a page's undo journal

`conf/` is the one reserved subfolder: **if it must be preserved across a commit, it belongs in
`conf/`**, and `.gitignore` keeps it and nothing else. Everything else is machine-written and
machine-local, so the default is closed and a project opens it deliberately.

`webview/` appears only while something serves this project over HTTP — see DEC-032 and DEC-033.
It is the *host's* state, not this module's output, and it is per project rather than per module:
the port identifies the directory a host was pointed at. Nothing may depend on it existing, and the
token in it is the one a caller must present for the state-changing routes.

**Host state is not configuration, which is why it is not in `conf/`.** A port, a pid, a token and
a page's undo journal are facts about one running process: meaningless in a clone, stale the moment
it exits, and different for two hosts on one machine. `conf/` holds what a project wants committed
and shared. A port *preference* ("ask for 18882 first") may be configuration; the *reservation*
never is.

A **user-home** `.jcodebuddy/` (`~/.jcodebuddy/`) is a different scope with a different job: it holds
that person's global defaults and configuration and nothing else — no reports, no caches, no project
secrets, and **no port**: a port names a socket for one served directory, so it is stated per project in
`conf/` and pinned per checkout in `webview/`, never machine-wide. A build must neither read nor create the
user-home directory. See DEC-032.

## Track policy

`.jcodebuddy/.gitignore` is the authority; this prose only explains it.

**Default: everything under `.jcodebuddy/` is ignored** — a tool's output is derived, and a
project that has not asked for it in git should not get a diff for it. Two top-level files and
one subfolder are the exceptions:

| Subtree | Policy | Why |
|---|---|---|
| `conf/` | **tracked, reserved** | the things that must survive a clone and a commit: shared settings, a naming contract, an invariant a tool must honour. This is the *only* subtree whose purpose is to be committed — `conf/webview.json` is the worked example |
| `README.md`, `.gitignore` (top level) | **tracked** | they are what explains the tree and encodes the split; without them the directory cannot document itself |
| every other subfolder — `metadata/`, `index/`, `cache/`, `reports/`, `context/`, `agent-state/`, `webview/`, and whatever comes next | **ignored by default** | derived, regenerable, machine-local, or scratch. A project opts in the subtree it wants, and only that subtree |

This module opts in two things, and both shapes are instructive:

```gitignore
# The explanation of every subtree, and no content:
!metadata/
!metadata/README.md
!metadata/*/
!metadata/*/README.md

# A hand-written spec that is project material rather than tool output:
!context/
!context/**
```

Two rules of git decide the shape of every opt-in, and both have been observed as real mistakes:

- **A directory must be re-included before anything inside it.** Git never re-includes a file whose
  parent directory is excluded, so `!metadata/entity/README.md` alone does nothing while `metadata/`
  is ignored.
- **An opt-in must stay narrow enough not to carry generated `.java`.** A `.java` under `.jcodebuddy/`
  is always a mistake (DEC-026 § 5), and `GeneratorGuardTest` asserts that no `.jcodebuddy/` tree in
  this repository holds one.

Two consequences are worth stating because they are easy to get wrong:

- **`index/` is coupled to `metadata/`.** A metadata document names source types by fully qualified
  name, and those resolve only in `index/classes.json`; committing the documents without the index
  commits a tree of dangling references. A project that opts `metadata/` in opts `index/` in with it,
  and a project that ignores both is consistent.
- **A subfolder's `README.md` is created when absent and never overwritten.** The generation pass
  writes `index/README.md` only if it is missing: the file is human-owned (DEC-020's rule for a
  file a person may edit), so rewriting it on every pass would silently revert an edit.

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
