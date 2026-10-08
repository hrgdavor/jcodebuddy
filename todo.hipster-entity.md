> **Status 2026-10-08 — closed, and this file is no longer a work list.** Every item is done; the schedule it fed is
> [`plans/unified-plan.md`](plans/unified-plan.md), and steps 7.3 and 7.4 closed the last two. It is kept rather than
> deleted so the links to it keep resolving and the history stays readable — but nothing here is open, and a new item
> belongs in the plan, not in this file.

## Mapper utility functionalities:

- [x] `View1Builder.merge(View2 other)` — merge fields with identical name and type
      — **done** in plan step 7.3: `ViewMergeGenerator` plus the `--merge <host>:<partner>` request, with a DEC-022
      diagnostic for a name-only match.
- [x] The proxy version of that merge — **done in the same step**: the tracking builder carries the same
      `merge(ViewN other)`, decided through the same `ViewMergeGenerator`, so the two merge the same fields.

## separation/layering of docs for lib users vs lib devs

- [x] Separate end-user documentation (focusing on use cases) from implementation details, as well as some of the
      conventions users need not initially know deeply about. — **delivered**, and the two items that plan left open
      (the root README front door and the reverse cross-references into the user guide) were closed by **step 7.4**.
