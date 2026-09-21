# merge-java `.jcodebuddy/` directory

This module declares its own `.jcodebuddy/` because it both consumes and produces
JCodeBuddy metadata (DEC-026). Two very different things live here, and they have
opposite git policies.

## Structure

```
.jcodebuddy/
├── README.md
├── metadata/
│   └── schema.json          # derived/ignorable: schema for a recorded decision
└── merge-history/
    └── <branch-name>/
        └── decisions/
            ├── import_add-<hash>.json
            ├── variable_rename-<hash>.json
            └── ...
```

## metadata/ — derived, ignored

`schema.json` describes the shape of a recorded decision. This subtree is derived
output and is **not** checked in.

## merge-history/ — a contract, checked in

This is the branch's memory of how it resolved recurring conflicts, and it is the
whole point of the module: it is what turns the fifth rebase of a long-lived
branch into a no-op. It is therefore **checked in**, one directory per branch.

```
.jcodebuddy/merge-history/feature-payments/decisions/variable_rename-3f9a1c2b.json
```

One file per decision, named `<conflict-type>-<content-hash>.json`. The hash comes
from the normalised shape of the disagreement, so:

- reformatting or reindenting the code does **not** invalidate a decision;
- a genuinely different disagreement never matches, so a stale decision can never
  be applied to code it was not made for.

A decision is only ever written when it is *replayable*: additive conflicts and
recorded preferences. A one-off manual resolution and a review-level judgement
call are deliberately **not** persisted.

The location is configurable per call
(`MergeConflictResolver.Builder.setHistoryPath`), and
`MergeUtil.resolve(filePath, base, ours, theirs)` uses an in-memory branch so it
never touches this tree.

## Git policy

```gitignore
# Derived metadata is not checked in.
.jcodebuddy/metadata/*

# Branch decision history IS checked in: it is the module's contract.
# (no ignore rule for .jcodebuddy/merge-history/)
```
