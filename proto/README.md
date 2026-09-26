# `proto/` — driver projects for JCodeBuddy development

This directory holds **real projects that drive JCodeBuddy while JCodeBuddy is under heavy
development**. They are not examples and not fixtures: each one is a working codebase whose
build, codegen and IDE experience are the thing being exercised, so a change that looks fine in
a unit test but breaks a real build shows up here rather than after release.

The one sentence that explains why this directory exists:

> **JCodeBuddy is under heavy development and is driven through real projects — and those
> projects belong to themselves, not to this repository.**

Consequences, in order of how often they matter:

- **`proto/` is gitignored by this repository.** The only file here that is committed to
  JCodeBuddy's own git is this `README.md`. See [Tracking policy](#tracking-policy).
- **Every project under `proto/` is its own git repository.** It has its own history, its own
  remote, its own `.gitignore`, and its own release cadence. JCodeBuddy's history deliberately
  does not contain it — neither as a submodule, nor as a vendored copy, nor as a gitlink.
- **The projects are the integration test bed.** A JCodeBuddy change (a generator, an entity
  pass, a webview host, a watch agent) is driven against a project here before it is believed.
- **Nothing here is a deliverable of this repository.** Deleting the whole directory must leave
  JCodeBuddy buildable and releasable; if it does not, something in the repository wrongly
  depends on it.

## What belongs here

| Belongs here                                                       | Does not belong here                            |
| ------------------------------------------------------------------ | ----------------------------------------------- |
| A real project that installs/uses JCodeBuddy and is driven by it.   | Scratch code that is not a project of its own.  |
| A project with its own remote (or a planned one).                   | Anything that should be committed to JCodeBuddy. |
| A reproduction case too large or too messy for a test.              | Shared test fixtures — those belong in the repo. |

A project that is *not* intended to become its own repository does not belong under `proto/`;
put it in the owning module's `src/test` or in `target/` scratch space instead.

## Tracking policy

`proto/` follows the same shape as the rest of the repository's ignored subtrees: **the
directory is excluded, its explanation is committed.** The root [`.gitignore`](../.gitignore)
carries the two lines that do it:

```gitignore
proto/*
!proto/README.md
```

The rule is `proto/*` rather than `proto/` on purpose. Git never re-includes a file whose parent
directory is excluded, so excluding the directory itself would silently drop this README as well.
With `proto/*` the directory stays visible to git and the single exception applies.

The practical result:

    proto/
    ├── README.md   TRACKED    this file — the only thing JCodeBuddy's git knows about here
    ├── <project>/  UNTRACKED  a project with its own git repository and its own remote
    └── <project>/  UNTRACKED  …

Two rules follow from that, and both are easy to get wrong:

- **Do not run `git add -f` on anything under `proto/`.** A forced add of a whole project copies
  another repository's sources into JCodeBuddy's history, where they stay forever. If a file here
  really must be committed to JCodeBuddy, it belongs somewhere else in the repository.
- **Do not make `proto/` a submodule.** The projects are independent checkouts at whatever
  revision their own branch is on; a gitlink would either pin nothing useful or pin a commit
  nobody intended to publish.

## Projects

| Project | What it drives | State |
| ------- | -------------- | ----- |
| [`business-logic/`](business-logic/README.md) | The business-logic concept: pure-function steps, the `ProcessingUnit`, the three operation types and the generated dispatchers/loop guards. Its design documents moved out of this repository's `doc/` tree to live with the code. | Docs only so far — no `pom.xml` yet; own git repository initialized |

## Adding a project

1. Create the project under `proto/<project-name>/` and give it its **own** git repository —
   either clone its remote there, or `git init` in place and add the remote when it exists:

   ```sh
   mkdir -p proto/<project-name>
   cd proto/<project-name>
   git init
   git remote add origin <its-own-remote-url>
   ```

   Nothing else is needed to hide it from JCodeBuddy's git: `proto/*` already ignores it. Verify
   with `git status --porcelain` at the repository root, which must stay silent about it.

2. Wire the project to JCodeBuddy **the way any external consumer would** — a released library
   dependency, or a local install of the artifacts under development. Do not reach into
   JCodeBuddy's `pom.xml` reactor from a proto project, and **do not add the project to the
   reactor's `<modules>` list**: a driver project is a consumer, and a consumer that participates
   in the producer's build cannot tell you what an outside consumer would experience. In
   particular a driver project declares its own coordinates, parent and version — never
   `jcodebuddy-parent` as its parent POM.

3. If the project applies `project-automation`, it gets its own `.jcodebuddy/` with its own
   `.gitignore`, committed to **that project's** git — the same split described in
   [`hipster-entity-example/.jcodebuddy/README.md`](../hipster-entity-example/.jcodebuddy/README.md)
   and DEC-026. Generated `.java` still goes under that project's `src/main/java`, never under
   `.jcodebuddy/`.

   This one is enforced, not just asked for: `GeneratorGuardTest` walks this repository three
   levels deep and fails the build if **any** `.jcodebuddy/` tree it finds holds a `.java` file —
   and `proto/<project>/.jcodebuddy/` is two levels down, so it is inside that walk. It is not
   only the generator's own pass that must obey it: a hand copy, a second writer or a stale tool
   in a driver project fails JCodeBuddy's test suite the same way, and because such a file is
   gitignored here, the failure is the only thing that would have told you.

4. Add a row to the Projects table above so the next reader knows what is being driven and in what
   state.

## Moving a project in from elsewhere

When a project is copied into `proto/` from a path that is already inside another git working
tree, the copy can arrive with a nested `.git` directory that makes it look like part of the
outer repository. Check for it (`ls -a` or `git -C proto/<project> rev-parse --show-toplevel`)
and decide deliberately: either it is that project's own repository — in which case its
`.git` is the point, and it must have its own remote — or it is not, in which case remove it
before writing history that claims otherwise.

## Relation to the rest of the repository

- The first project here, [`business-logic/`](business-logic/README.md), carries the design that
  this directory is largely meant to exercise. Its documents — previously at
  `doc/brainstorm/business_logic/` in this repository — now live at
  `proto/business-logic/doc/brainstorm/business_logic/README.md`, inside that project's own git,
  because the concept and the library implementing it are one deliverable. The JCodeBuddy side of
  the integration, `40_engineering/10_jcodebuddy_integration.md`, is in that same tree.
- Those documents refer to this repository's decisions and modules **by name** (DEC-019,
  `project-automation`, `hipster-entity`, …) rather than by relative link, because this checkout is
  not part of that project and its layout is not the project's to track.
- What a proto project gets from JCodeBuddy must still satisfy the project-wide rule that wiring
  is **source-visible and IDE-navigable** in the *consuming* project's committed sources
  (DEC-019, [`AGENTS.md`](../AGENTS.md) §1). A driver project is the place that rule is tested
  against a real IDE, not an exemption from it.
- Ignored-output conventions for whatever a proto project generates are the same as everywhere
  else: derived output under that project's `.jcodebuddy/`, committed `.java` under its
  `src/main/java` (DEC-026).
