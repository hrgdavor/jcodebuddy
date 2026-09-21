# What "base" means here

A **newly explicit** part of the model. Until now the code used Git's merge base and
said "base" without distinguishing it from the other reference points, which is
wrong for the workflow this module targets.

---

## The three reference points

For a long-lived branch (`dev`) that periodically takes changes from its upstream
(`main`):

| Name in this module | What it is | Git equivalent |
|---|---|---|
| **`lastSynced`** | `main` exactly as it stood the **last time `main` was merged into `dev`** | the upstream commit recorded on `dev` at the last sync |
| `ours` | `dev` as it is now | `dev` tip |
| `theirs` | `main` as it is now | `main` tip |
| `mergeBase` | the best common ancestor of `ours` and `theirs` | `git merge-base dev main` |

`lastSynced` and `mergeBase` are **not the same commit**, and conflating them is the
mistake this document exists to prevent.

### `mergeBase` is about content

It is what a three-way merge needs in order to know, line by line, what each side
changed. Git computes it; nothing has to be recorded.

### `lastSynced` is about intent

It is a **recorded marker**: the upstream commit that was current the last time this
branch was brought up to date. It answers "what has `main` done since I last looked",
which is a question about this branch's history, not about the shape of the commit
graph.

---

## Why they diverge

```
main:    A --- B --- C --- D --- E --- F
                  \           \
dev:               G --- H --- M1 --- I --- J
```

`dev` was synced at `M1`, which merged `main` as far as `C`.

- `mergeBase(dev, main)` = **`C`** — the merge exists, so the common ancestor is `C`.
- `lastSynced` = **`C`** — same commit here, because `M1`'s merge brought in exactly `C`.

Now `main` advances and `dev` is synced a second time:

```
main:    A --- B --- C --- D --- E --- F --- G
                  \           \             \
dev:               G --- H --- M1 --- I --- J --- M2
```

`M2` merges `main` as far as `F`.

- `mergeBase(dev, main)` = **`F`**
- `lastSynced` = **`F`**

They still agree. The important case is the one *between* syncs, which is when the
module actually runs:

```
main:    A --- B --- C --- D --- E --- F --- G
                  \           \
dev:               G --- H --- M1 --- I --- J          <- dev is out of date
```

- `mergeBase(dev, main)` = **`C`**
- `lastSynced` = **`C`**

Agreement again. **So when is `lastSynced` different from `mergeBase`?**

When the branch has *not* merged `main` but has recorded a sync marker anyway — for
example when a previous sync resolved conflicts on a copy of `main`'s content, or
when a sync was recorded and then the branch was rebased. It also differs whenever
an explicit marker is kept independently of the commit graph, which is exactly what
makes it useful: it survives a rebase, and a merge base does not.

That last point is the practical argument. **`merge-base` is derived, and a rebase
changes it. `lastSynced` is recorded, and a rebase does not.**

---

## What the module actually needs

Both, for different jobs:

1. **Content comparison** — the line-by-line "what did each side change" work — needs
   `mergeBase`, because that is what makes a three-way comparison meaningful. Git
   gives it; the module reads it.

2. **Recognising what is new** — deciding whether a divergence is *fresh* from `main`
   since the branch last looked, rather than something the branch already resolved —
   needs `lastSynced`.

The second is what makes the branch-local decision history work. A recorded decision
is replayed when the conflict signature matches; if the branch had no idea what it
had already seen, it could not tell "this conflict is back" from "this is a different
conflict that happens to look similar".

---

## The rule the code follows

- The three inputs to a resolver are **`lastSynced`, `ours`, `theirs`**.
- `lastSynced` is used wherever the module asks *"what did each side change?"*
- `mergeBase` is used only to *establish* a `lastSynced` when none has been recorded
  yet — the first sync of a branch. After that the recorded value wins.
- **Never** substitute `mergeBase` for `lastSynced` in a change computation. Using
  an older base makes `theirs` look as though it re-added everything the branch
  already merged, and `ours` look as though it deleted it — producing conflicts that
  do not exist, which is the exact failure this module exists to remove.

### Naming

The field is called `lastSynced` in the model. It reaches a resolver as
`Conflict.getBaseCode()`, where "base" means *the last-synced state of the upstream*,
not Git's merge base. Code and documentation should say **last-synced** when the
distinction matters; "base" is retained in the API only because renaming it
everywhere is churn that would obscure the meaning rather than clarify it.

---

## Consequences for the fixtures

The fixture directories are named `base/`, `ours/`, `theirs/` for readability, and
`base/` holds the **last-synced upstream content**. When adding a fixture, that is
what to put there: the upstream file as it stood at the last sync, not the current
upstream file and not a synthetic common ancestor.

`import-add-remove-same` is the case that shows the difference matters: with the
last-synced content as base, `theirs` is seen to remove `java.util.Set` while `ours`
kept it — a removal decision. Against a *stale* base, `ours` would appear to have
added `Set`, and the module would report an addition instead of a removal.
