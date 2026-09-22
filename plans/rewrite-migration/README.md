# OpenRewrite Migration Project

## Overview

This directory contains the plan documents for migrating JCodeBuddy from JavaParser to OpenRewrite's core
module. **The migration is complete, code and documentation both.** `javaparser-core` is declared by no
module, all 34 queue files are ported, the reactor runs 1326 tests green, and `bun run
scripts/rewrite-migration/verify-migration.js` reports `RESULT: PASS` over nine checks — the ninth,
`docs-honest`, fails when a live document tells a reader to use the library the code removed.

## Directory Structure

```
plans/rewrite-migration/
├── README.md                     # This file
├── REWRITE-MIGRATION-PLAN.md     # The original plan, as written before the work started
├── PLAN-SUMMARY.md               # Phase status verified against the tree - read this first
├── 01-Foundation.md              # Phase 1: dependencies and the API compatibility sketch
├── 02-Core-AST-Utilities.md      # Phase 2: traversal/printer/utility sketches
├── 03-Code-Generation.md         # Phase 3: generator port design
├── 04-Validation.md              # Phase 4: validation rule port design
├── 05-Automation.md              # Phase 5: the automation engine (delivered)
├── 06-Migration-Checklist.md     # Phase 6: the file-by-file port (delivered)
├── 07-Testing-Validation.md      # Phase 7: tests, benchmarks, reports (delivered)
└── 08-Documentation.md           # Phase 8: making the documentation agree with the tree (delivered)
```

Each phase document keeps the plan as it was written and carries a **§ Status** section at the end
recording what was actually delivered and every place the two disagree. Read that section before using
anything above it as a description of the code - several deliverables name classes that never existed
outside the plan, and two of Phase 7's compared against a library the migration removed.

Phase *artifacts* (sketches, checklists, trackers, reports) live outside this directory, under
`doc/brainstorm/rewrite-migration/`:

```
doc/brainstorm/rewrite-migration/
├── 01-foundation/   (5 files)   POM fragment, API compatibility sketches
├── 02-utilities/    (10 files)  AstPrinter/NodeTraversal/SourceManipulation/TypeUtils sketches and tests
├── 03-codegen/      (2 files)   generator design notes
├── 05-automation/   (10 files)  the ten engine sketches that Phase 5 reviewed and rewrote
├── 06-migration/    (5 files)   Checklist, tracker, MIGRATION-GUIDE, MIGRATION-CAVEATS, FOLLOWUP-06
└── 07-testing/      (6 files)   TEST-REPORT.md, gate-run.txt, benchmarks/
```

Phase 4 has no artifact directory: its deliverables were the ported validation rules themselves, which
live in `hipster-entity-tooling`.

## Quick Start

1. Read `PLAN-SUMMARY.md` § *Phase status* for what is delivered and what the evidence is.
2. For the phase you care about, read its § *Status* first, then the plan above it.
3. To verify the migration yourself:
   ```
   cmd /c "scripts\mvn-jdk25.cmd -o -Dmaven.compiler.useIncrementalCompilation=false clean test"
   bun run scripts/rewrite-migration/verify-migration.js
   ```
4. To read what the documentation now says about source handling, start at
   `doc_knowledge/code.graph.md`; the decision behind it is DEC-030.

## Getting Help

- OpenRewrite documentation: https://docs.openrewrite.org/
- OpenRewrite repository: https://github.com/openrewrite/rewrite
- What the port actually had to work around: `doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md`
  and `MIGRATION-GUIDE.md` - both are records of the migration and name the old library deliberately.

## Architecture Compliance

All migrations must comply with JCodeBuddy architecture decisions:

- DEC-019: Source-visible wiring (no reflection-driven discovery)
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header format
- DEC-022: Refactor-sensitive naming contracts
- DEC-026: JCodeBuddy output in the module's `.jcodebuddy/`
- DEC-027/028/029: reports rendered by Bun from generator JSON; locations; class index by FQN
- DEC-030: OpenRewrite's LST is the source representation (reading, positions, querying, splicing)

## Status

**Current Phase**: none — Phases 1–8 are delivered.

**Delivered**: Phases 1–7 (2026-09-22). Phase 6 completed the port; Phase 7 added 95 tests, found and
fixed three production defects in the position-lookup half, and produced the generated
`doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md`.

**Delivered in Phase 8** (2026-09-22): the documentation agrees with the tree and the agreement is
checked. `AGENTS.md` § 2 states the OpenRewrite rule; `doc_knowledge/code.graph.md` is the source-handling
guide; the builder guides were consolidated and the AI-transcript one retired; 20 DEC/brainstorm/record
notes were appended; `DEC-030` records the representation; and `docs-honest` fails the gate when a live
document names the removed library without a recorded reason. Delivery record:
`08-Documentation.md` § *Status*.

**Next Action**: none. A future phase should not be opened for this migration; the two things it leaves
are named as follow-ups rather than as work — moving `docs-honest` out of the migration gate if that
tooling is ever retired, and the DEC-031 generated-artifact index pointer DEC-029 reserved.

---

*For questions or issues, contact the migration team or review the architecture decisions in
`doc-hipster-entity/architecture/decisions/`.*
