# file-walk — walking a repository the way git sees it

Every script that reads "all the files in the repository" needs the same thing, and every script that improvises gets
it wrong the same way: a hand-written skip list that misses a directory somebody added later. The symptom is not a
wrong answer, it is a slow one — `scripts/check-repo-links.mjs` walked a checked-out VS Code and every `node_modules`
before this existed, and the reason was invisible in its own source: its list skipped `node_modules` and `build`, and
said nothing about `.vscode-test`, because nobody had thought about it since it appeared.

So the rule lives here, once, for every JavaScript utility in this ecosystem.

```js
import { listFiles, isIgnored } from '../lib/file-walk/index.js';

// Every Markdown file git would call ours.
const markdown = listFiles('.', { extensions: ['.md'] });

// Or without collecting, when the caller is a formatter walking a tree it will edit.
listFiles('.', { extensions: ['.md'], visit: (path) => format(path) });

// One path, when a caller already has one.
if (isIgnored('.', somePath)) { /* not ours */ }
```

## What it promises

- **`.gitignore` is the definition of "not ours".** Read from each directory as the walk enters it, so a nested file
  overrides its parent and a later line overrides an earlier one — git's precedence, not an approximation of it.
- **An ignored directory is not entered.** That is not a shortcut: git cannot re-include a file whose parent directory
  is excluded, so not looking inside is precisely git's rule — and it is where the speed comes from.
- **Symlinks are not followed.** A link out of the tree is how a walk escapes it, and following one would also make
  `node_modules` cost whatever it points at.
- **No `git` process, no dependency.** A walker that shells out to `git ls-files` cannot help a script running in an
  exported tarball, and it makes a tool's behaviour depend on an environment rather than on a file. This package
  declares no dependencies, like everything under `scripts/` (DEC-027).
- **Extensions are filtered during the walk**, not after it. For a link checker that is the difference between reading
  25,000 files and reading a few hundred.

## What it deliberately does not do

`.git/info/exclude`, `core.excludesFile` and `.gitattributes` are not consulted. Those are a person's or a machine's
opinion about a checkout rather than a property of the sources, and a build that behaves differently per machine is
exactly what this repository writes rules about.

Unreadable directories are skipped rather than fatal: a walk is a scan, not a proof.

## The Java side, recorded and not built

The watcher needs the same functionality on the Java side, and it is **deliberately not implemented yet** — the
maintainer's direction was "make that as statement to be done also for java in watcher if needed at some point. do not
implement java gitignore ATM". [DEC-044](../../../doc-hipster-entity/architecture/decisions/DEC-044.md) records that
intent, and what would have to be true before it is worth doing: a second implementation of `.gitignore` semantics is a
second place for them to be subtly wrong, so it should be written when something actually needs it, and against these
tests as the shared statement of the rule.
