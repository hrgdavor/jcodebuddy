# Roadmap / Todo

> **Status 2026-10-01 — Phase 2's first half is a PROTOTYPE, and the rest is TBD.**
>
> What exists: Phase 1's catalog is [`ioc-problems-catalog.md`](ioc-problems-catalog.md), and the
> usage simulation is hand-written today — [`hipster-ioc-test/`](../../hipster-ioc-test) holds
> `CtxMain`/`CtxMainModule` plus the composable-entity playground, over the five API types in
> [`hipster-ioc-api/`](../../hipster-ioc-api) (`HipsterContext`, `ChildContext`, `Circular`,
> `DynamicResource`, `StableValuePolyfill`).
>
> **Phase 2's first half is real but still prototyping:** [`hipster-ioc-tooling/`](../../hipster-ioc-tooling/README.md)
> is no longer an empty jar — it generates `CtxMainImpl` from `CtxMain` (committed Java, DEC-035's header,
> creation order derived from the `buildMapper()` factory, accessors that return fields), and the committed
> example in `hipster-ioc-test` compiles. It also computes the dependency graph as JSON under the module's
> `.jcodebuddy/metadata/hipster-ioc/contexts.json`, and runs through `bun scripts/ioc-gen.js`.
> [DEC-036](../../doc-hipster-entity/architecture/decisions/DEC-036.md) is `Trial`: it **proposes** the
> generated shape, and that shape is expected to change.
>
> **Everything that depends on the shape therefore waits**, and the plan is where that is tracked: Phase 3's
> banner in [`plans/unified-plan.md`](../../plans/unified-plan.md) marks this module as prototyping and
> lists the shape-dependent work as `[TBD]` — steps 3.4–3.11, each naming the decision it waits on. "TBD"
> there means deliberately unscheduled, not forgotten: the bullets in this roadmap that need a settled
> shape (the graph presentation, the host, the navigation) are among them.
>
> **What this module is, and what it is not — settled, and not a matter of prototyping.** hipster-ioc is a
> **project-wide** generator: it needs the project's type relations (who extends whom, who implements
> what, what is assignable) to decide how beans are wired, and no single file contains them — so it cannot
> work file by file, and it does not. **Extracting metadata is not its job:** it *consumes* the project's
> metadata to produce IoC code, while the metadata layer (the class index with its checksums, the cache,
> the arena-backed index) reads and indexes sources. The seam for that already exists in
> `jcodebuddy-codegen-api` — `TypeResolver`/`TypeDefinition` — and is empty: `TypeDefinition` carries a
> type's fields and **no relations**, and nothing but `EmptyTypeResolver` implements `TypeResolver`. The
> plan schedules the contract for it as steps **3.0a–3.0d**, and moving this generator onto it as
> **3.0e**. The prototype's file-scoped `CodeGenerator` implementation is a shortcut that 3.0e removes.
>
> **What the prototype does not do, stated rather than discovered:** no cross-context wiring (a context's
> `dependencies()` are recorded in the graph and never used to build anything), no `init*` methods, no
> region markers, and no `@Circular` two-phase form — a cycle is refused with a diagnostic rather than
> wired (DEC-036 § 5). The browsable page rendered by Bun (DEC-027/029) and the embedded light HTTP server
> do not exist; the module's README says which entry points do.
>
> Tracked as steps 3.1–3.3 of [`plans/unified-plan.md`](../../plans/unified-plan.md), with the
> shape-dependent remainder as steps 3.4–3.11 (`[TBD]`). The record those steps needed is
> **[DEC-036](../../doc-hipster-entity/architecture/decisions/DEC-036.md)** — the `.kilo` plan for this
> work asked for `DEC-W008`, a number the metadata no-cache decision had already taken. Read it as a
> **proposal** (`Trial`), not as a contract: it is what the prototype implements, and the plan's Phase 3
> banner says which parts are still expected to change. `region markers only above the thresholds` is in
> the record as intent and is **not** implemented — see the list above.

## Phase 1: Define the Problem Space & Refine Boilerplate
To be able to define good, readable boilerplate, we first need to explicitly catalog the "problems" that existing IOC frameworks (like Dagger, Spring, Guice) attempt to solve.

1. **Catalog IOC Use Cases:** Create a comprehensive catalog of the specific problems and patterns solved by heavy IOC frameworks. This is the first step toward finding patterns for light, readable boilerplate code that the LSP sidecar can augment and maintain as a project grows or refactors.
2. **Build Sample Applications (Simulate Usage):** Construct a collection of sample applications to simulate usage. 
   - Start with really small, basic use-cases where IOC begins to become useful.
   - Progressively build on these with more complex, real-world examples.
   *Without simulating actual usage, the generated boilerplate cannot be properly refined.*

## Phase 2: Feature Backlog

### Dependency Discovery & Visual Graphs
- **Generate Dependency Metadata:** The LSP sidecar will compute and continuously maintain a structured JSON representation of the dependency graph during development.
- **Embedded Light HTTP Server:** The LSP sidecar will embed a light HTTP server to serve interactive HTML dependency graphs directly to the browser.
- **Editor-Agnostic Context Navigation:** Because the sidecar serves the visual presentation locally, it allows for editor-agnostic integration. Users can navigate the generated HTML visualizations and click directly back into specific lines of Java code in their preferred IDE.

### Secondary Backlog
- list contexts
  - dependencies
  - exposed beans
  - expanded beans
  - factories
  - initializers
  - AutoCloseable beans
- dependencies that are exposed and those used in factories must be instance fields
- produce a markdown that is clickable and explains each module where you can click each class if you need more details
- can be used to produce a dependency graph
- maybe some nice HTML interface to explore dependencies
- make sure generated context does not call methods in methods that return a bean, to guarantee singleton as expected
  - if return value from an expanded dependency or a custom build method changes (like maybe config) we store a snapshot
  - it may be limiting, but is potential source of freaky bugs. dealing with mutable values should be done outside hipster-ioc
- make sure any dependency used for factories are placed in fields, and not just a local var in constructor
- explore if it would be a good practice to extract factory methods from context into a separate interface (this could be enforced)
- explore enforcing some rules that are deemed a good practice (with ability to  disable them via config or annotation if annoying to user)

dependency graph generation exploration ideas
- group by context, 
- mark dependencies for factory method separately from additional parameters.
