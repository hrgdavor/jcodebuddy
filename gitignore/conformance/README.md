# Conformance vectors

> **Status: hand-authored, not yet runner-verified.** The 34 cases below were written from `doc/rules.md`, not
> generated from the implementation. The Zig runner that reads this file is the next step in the plan, and it may
> well correct a case — in which case the correction and the reasoning are the valuable part of the commit.


`gitignore-vectors.json` is the contract a port is measured against. The Zig implementation is the **basis**, not the
authority: a case here was written by hand from the rules in [`../doc/rules.md`](../doc/rules.md), and a port that
disagrees is either wrong or has found something this project should learn.

## Shape

```json
{
  "name": "last matching pattern wins",
  "ruleFiles": [{ "dir": "", "rules": "*.log\n!keep.log\n" }],
  "path": "keep.log",
  "isDir": false,
  "expect": "include"
}
```

- `ruleFiles` — one entry per `.gitignore`, with the directory it governs (`""` is the root). A file governs names
  **below** its directory; a deeper file beats a shallower one.
- `path` — the name to decide, relative to the root, `/`-separated. Not a filesystem path: no case contains a real
  tree, so a port needs no checkout of any repository.
- `isDir` — whether the name is a directory. Directory-only rules (`build/`) turn on it, and an excluded directory is
  never entered, so its contents are excluded because their ancestor is.
- `expect` — `include`, `exclude`, or `no-opinion`. **The third value is the one ports get wrong**: when no rule file
  has an opinion the answer is *no opinion*, not "include", because a caller (an indexer, a walker) may have a default
  of its own.

## How a port runs them

The runner is deliberately language-neutral:

```text
# given a case, materialise ruleFiles under a temporary root, then ask the matcher
for each case:
    write each ruleFile to <root>/<dir>/.gitignore
    verdict = matcher.decide(<root>, case.path, case.isDir)
    assert verdict == case.expect, failing with the case name
```

A port that resolves chained files differently should still pass: the vectors state precedence as *behaviour*
(a deeper file wins), not as an algorithm.

## Adding a case

1. Find the rule it pins in `doc/rules.md`; if the rule is not there, the document is incomplete and that is the first
   change.
2. Add the smallest case that fails when the rule is broken, and name it in the vocabulary of the rule.
3. Run the Zig implementation. If it disagrees, decide which is wrong **before** changing the vector — a vector that
   follows an implementation teaches nothing.
4. If the case came from git's own tests or from a real checkout, note the source in the commit message; cases carry
   git's licence when they come from git, and generated cases may carry names but never file contents.

## What is not covered yet

- Case sensitivity per platform: the matcher is case-sensitive everywhere, which is git's behaviour on Linux and its
  default on Windows only for tracked paths; a case would need to say which platform it assumes.
- `.git/info/exclude` and a global `core.excludesFile`: deliberately not read (see the README), so no cases.
- `.gitignore` files that git itself treats as unreadable (binary content, NUL bytes): a robustness question rather
  than a rule, and the plan's corpus research is where it belongs.
