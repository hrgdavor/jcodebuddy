
> **Status 2026-10-01 — two items, one open and one delivered.** Kept as the list it is; the live
> schedule is [`plans/unified-plan.md`](plans/unified-plan.md).

## Mapper utility functionalities:

- [ ] `View1Builder.merge(View2 other)` — merge fields with identical name and type
  - Proxy version of such merge

  **Open, and nothing has started it:** no `merge(` method exists in `hipster-entity/hipster-entity-core/src/main/java`
  or `hipster-entity/hipster-entity-example/src/main/java`. The related mapper work that *did* land is
  `ViewMapperGenerator` (a statically dispatched `static <Target> map(<Source>)`), which is a
  different shape — source→target conversion, not a builder-to-builder merge. Scheduled as step 7.3 of
  [`plans/unified-plan.md`](plans/unified-plan.md).

## separation/layering of docs for lib users vs lib devs

- [x] Separate end-user documentation (focusing on use cases) from implementation details, as well as
  some of the conventions users need not initially know deeply about.

  **Delivered** by [`doc-hipster-entity/doc-separation-plan.md`](doc-hipster-entity/doc-separation-plan.md):
  the user guide is [`doc-hipster-entity/user/`](doc-hipster-entity/user/README.md), the implementation
  half is [`doc-hipster-entity/architecture/`](doc-hipster-entity/architecture/README.md). Two items of
  that plan are still open (the root README front door and the reverse cross-references) and are its
  own banner's business, not this list's.
