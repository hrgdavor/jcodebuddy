# rewrite-migration — Phase 6 migration tooling

Tooling for **Phase 6: Migration of Existing Tooling**, the last phase of the
[JavaParser → OpenRewrite migration](../../plans/rewrite-migration/README.md).
Phase 6's job is to remove every remaining `com.github.javaparser` import from
the project's own sources (`AGENTS.md` §1: generated code and tooling wiring
must be committed, navigable Java).

The phase deliverables are prose under
[`doc/brainstorm/rewrite-migration/06-migration/`](../../doc/brainstorm/rewrite-migration/06-migration/):

| Deliverable | Role |
| --- | --- |
| `Checklist.md` | Generated: one section per file, from the live inventory + `curation.js` |
| `tracker.md` | **Hand-maintained**: the per-file status source of truth |
| `MIGRATION-GUIDE.md` | Hand-maintained: verified JavaParser → OpenRewrite mappings and the porting procedure |

This directory is the fourth half of that set: the scripts the plan calls for.
They are plain **Bun** JavaScript, matching the report-tooling convention already
used by [`scripts/entity-html/`](../entity-html/README.md) — no bundler, no
`node_modules`, no network at run time.

## The four plan scripts

The plan (`06-Migration-Checklist.md` § Deliverables) names four shell scripts.
On this checkout they are these four JavaScript entry points; the `.sh` name in
the plan is the only thing that changed.

| Plan name | Here | What it does |
| --- | --- | --- |
| `scan-remaining-javafiles.sh` | `scan-remaining-javafiles.js` | Finds every remaining JavaParser use, classifies it, prints a summary |
| `migrate-file.sh` | `migrate-file.js` | Pre-flight for one file: surface, plan steps, baseline hash, after-action check |
| `verify-migration.sh` | `verify-migration.js` | Gate: fails on remaining imports in non-exempt files; checks tracker ↔ disk agreement |
| `generate-migration-report.sh` | `generate-migration-report.js` | Writes the migration report in the format the plan specifies |

## Usage

```sh
bun run scripts/rewrite-migration/scan-remaining-javafiles.js
bun run scripts/rewrite-migration/migrate-file.js hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java
bun run scripts/rewrite-migration/verify-migration.js
bun run scripts/rewrite-migration/generate-migration-report.js --out /tmp/report.md
```

`--root <dir>` on any of them points the scan at another checkout (default: the
repository this directory lives in, found by walking up to the directory that
contains `plans/rewrite-migration`).

`migrate-file.js` writes a baseline snapshot before you edit and compares
afterwards, so it is the one script with durable state:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline <file>   # before editing
bun run scripts/rewrite-migration/migrate-file.js --after    <file>   # after editing
```

## Where "what to do to this file" lives

Two hand-maintained inputs, both real source:

- **`curation.js`** — the exception list and per-file migration knowledge:
  priority, risk, OpenRewrite classes involved, concrete notes, and the
  allowlist of files that legitimately keep a JavaParser reference.
- **`tracker.md`** — status per file (`- [x] status: complete`).

`curation.js` carries the reasoning; `tracker.md` carries the state. The
generated `Checklist.md` then holds both, and `verify-migration.js` fails when
`tracker.md` disagrees with what is actually on disk, so a status cannot drift
into a comfortable lie.

## Why the allowlist exists

Three groups of file legitimately keep a JavaParser mention, and none of them is
a migration target. `verify-migration.js` reports them as EXEMPT with the reason
rather than either failing the gate or silently dropping them from the count,
because "found nothing" and "found something I chose to ignore" are different
answers and must not be conflated.

- **Documentation scaffolding** under `doc/brainstorm/rewrite-migration/01-*`
  and `02-*`. These files *are* the migration's own notes, and the JavaParser
  types in them are what the notes are about.
- **Test fixtures that assert on JavaParser**, such as
  `hipster-entity-tooling/src/test/.../SourceReaderTest.java`, which asserts the
  configured language level *is* `JAVA_25`. That assertion is a regression guard
  for the parser contract; deleting the JavaParser reference would delete the
  guard.
- **Comment-only and fixture-string mentions** in tests that build source text
  mentioning JavaParser without using the API.

Each allowlist entry carries its `reason` and a `deferredTo` naming the phase or
condition that removes it, so the exemption is a recorded decision with an end
date rather than a permanent hole.

## Test

```sh
cd scripts && bun test rewrite-migration/rewrite-migration.test.js
```
