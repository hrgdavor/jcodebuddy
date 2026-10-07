# hipster-ioc-tooling

The context generator for [hipster-ioc](../../hipster-ioc/README.md), and the dependency graph it computes.
The decision it implements is [DEC-036](../../doc-hipster-entity/architecture/decisions/DEC-036.md).

> **Status: prototype — the generated shape is not settled.** hipster-ioc is in its prototyping phase, and
> the point of this generator is to *find* that shape: DEC-036 is `Trial`, the output is expected to be
> rewritten, and the committed sample in `hipster-ioc-test` is a sample rather than a contract. The work
> that depends on the shape being decided is deliberately unscheduled and listed as `[TBD]` in
> [the plan's Phase 3 banner](../../plans/unified-plan.md) (steps 3.4–3.11), each naming the decision it
> waits on. What this prototype does **not** do: cross-context wiring from `dependencies()`, `init*`
> methods, region markers, and the `@Circular` two-phase form.
>
> **The wrong shape this prototype had is gone (2026-10-03, plan step 3.0e part two).** hipster-ioc is a
> **project-wide** generator — it needs the project's type relations (who extends whom, who implements what),
> which no single file contains — and **extracting metadata is not its job**. It used to keep a shortcut for the
> second half of that: it implemented the *file-scoped* `CodeGenerator` SPI and re-read the sibling module
> interface itself. It now consumes the model instead: a pass builds the class index
> ([DEC-037](../../doc-hipster-entity/architecture/decisions/DEC-037.md)'s engine in `jcodebuddy-core`) and the
> generator is handed rows and an index, so it parses no source at all. What it still reads is the file it
> **writes**, because cooperative codegen has to recognise its own previous output (DEC-020) — a different
> question from where the facts come from, and conflating the two was the category error.
>
> `bun scripts/ioc-gen.js` is still the supported entry point, and it now builds the index itself before
> generating, so the tree is read once per run rather than once per context.

## What it does

Reads a `@HipsterContext` row out of the class index and writes `<Context>Impl` beside the interface, in the same
package:

```
hipster-ioc/hipster-ioc-test/src/test/java/hr/hrg/hipster/ioc/test/
├── CtxMain.java          the context interface — the user's source of truth
└── CtxMainImpl.java      generated: fields, creation in dependency order, accessors
```

> **The hand-written `CtxMainModule.java` is gone (plan step 3.0e part two, and step 3.10 records it).** This
> listing used to show it between the interface and the generated class, which contradicted the note above: the
> module that held `default ObjectMapper buildMapper()` was the last hand-written wiring in the module, and the
> generator now emits the creation line instead. A reader following this tree would have looked for a file that is
> not there — which is what step 3.10 was scheduled to clear.

**The acceptance for that migration is reproducible in two commands**, and it is the check DEC-036 was waiting for:
`bun scripts/ioc-gen.js` must exit 0 with no divergence reported, and `git status` must then be **clean** — meaning
the committed `<Context>Impl` is byte-identical to what today's generator emits from the interface. Divergences are
reported rather than fatal (DEC-022), so the clean tree is the assertion, not the exit code.  Measured 2026-10-08:
exit 0, no divergence, clean tree, and the module compiles (`hipster-ioc-test` carries no tests of its own — it is a
test-support module — so the generator run *is* its evidence).

The generated class **implements the interface directly** — no proxy, no reflective lookup, no
name-to-bean registry — so a reader with a stock IDE follows `ctx.mapper()` to the field that holds it
(DEC-019). Creation order comes from the factories' parameters, and each line names the factory that built
it, so the object graph is legible top to bottom. Beans are created eagerly; laziness is opt-in through
`Supplier`/`DynamicResource` with a factory, and a lazy bean *without* one is refused rather than guessed at.

The generated file carries DEC-035's file marker, follows DEC-020's cooperative rules (a member you add is
preserved verbatim), and honours `enabled:false` in its header as a whole-file freeze (DEC-021 § 6).

## Running it

```bash
bun scripts/ioc-gen.js                      # hipster-ioc/hipster-ioc-test/src/test/java
bun scripts/ioc-gen.js --root <dir>         # another source root
bun scripts/ioc-gen.js --indent "  "        # four spaces by default
bun scripts/ioc-gen.js --quiet              # print only the divergences
```

It is **not** a build step: nothing is bound to a Maven lifecycle phase, so `mvn test` only compiles the
committed generated source. The script pins JDK 25 and exports the reactor classpath; run it after changing
an interface, and commit the result.

Exit code is non-zero when a context was **refused**, so a check can depend on it.

## What it refuses, and why refusing is the answer

| Diagnostic                               | When                                                                   | What it means                                                                              |
| ---------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `circular_dependency_unmarked`           | the factories' parameters form a cycle and no parameter is `@Circular` | no creation order exists; the file is left untouched                                       |
| `circular_dependency_marked_unsupported` | a parameter *is* marked `@Circular`                                    | the two-phase form that would honour the mark needs a `Supplier` parameter, which the API cannot express yet (DEC-036 § 5) |
| `lazy_bean_needs_factory`                | a `Supplier<X>`/`DynamicResource<X>` bean with no `build…` method      | `new Supplier<X>()` is not valid Java, and inventing one would capture a bean nobody built |
| `context_implementation_present`         | `@HipsterContext(impl = …)` names a class                              | an implementation already exists; the generator must not compete with it                   |
| `source_not_parsed`                      | the file cannot be read cleanly                                        | nothing is generated — a half-read context is a half-wired one                             |

A factory parameter the context cannot provide is **not** an error: it becomes a constructor parameter of the
generated class, so the caller supplies it and no generated code ever passes a `null`.

## The dependency graph

Each pass writes `<module>/.jcodebuddy/metadata/hipster-ioc/contexts.json`: the contexts, their beans, how
each bean is created, and the parent type where there is one. It is derived, ignored output (DEC-026) and the
model a report renders from — a report is Bun JavaScript reading that JSON, never HTML emitted by this
generator (DEC-027/029).

## Naming contract (DEC-022)

| Source                                        | Generated                                                             | Rename-sensitive?                                                                             |
| --------------------------------------------- | --------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `@HipsterContext interface CtxMain`           | `CtxMainImpl`, same package                                           | **yes** — the class names the interface in its `{@link}` and implements it, so an IDE rename of the interface reaches the generated class |
| accessor `ObjectMapper mapper()`              | field `mapper` + `@Override mapper()`                                 | **yes** — the name is the interface's own                                                     |
| `default Widget buildWidget(Gadget gadget)`   | the creation call `buildWidget(gadget)`                               | **yes** — the generator reads the method name on every pass, so a rename regenerates the call |
| `@HipsterContext(impl = ManualContext.class)` | nothing generated; the diagnostic names the class                     | **no** — a user-supplied reference, passed through as written                                 |
| the generated file itself                     | `// @generated file hr.hrg.hipster.ioc.tooling.IocContextGenerator …` | **no** — the marker names the generator, which is stable                                      |

## Boundaries

- **Kind: project-scoped.** It is a `ProjectGenerator` — metadata in, code out — and **not** a file-scoped
  `CodeGenerator`, which it stopped implementing in step 3.0e. The distinction is a type rather than a comment
  (`hr.hrg.jcodebuddy.engine.codegen`), so a caller holding a list of file-scoped generators cannot be handed this
  one at all: it is offered the project's **metadata** (the class index and the typed queries over it) and never a
  lone file. That is not a difference of appetite — the context's own compilation unit holds the interface, while the
  `default buildXxx(...)` factories live in the module interface, and "who extends whom" is in no single file — so a
  caller offering it one file would get *inferred absences* rather than errors. It also produces
  `<module>/.jcodebuddy/metadata/hipster-ioc/contexts.json`, which describes the whole tree. Run it through
  `bun scripts/ioc-gen.js` (the CLI half of DEC-036 § 11), let the dev-time pass drive it (`IocRegeneration` in `project-automation`, step 3.9), or let
  `IocRegenerationWatcher` run that pass on every save — the watch half of DEC-036 § 11 shares its loop breaker with
  the entity watcher (`WatchedRegeneration`), so regenerating on save cannot feed on its own output. A sibling
  module interface that cannot be read is a missing neighbour to **report**, never an absence to infer.
- It does **not** depend on `project-automation`; another module must never depend on a project's private
  dev-time assistant (AGENTS.md § 1.1). The pass drives the generator, never the other way round.
- It reads Java through **OpenRewrite's LST** via `hipster-entity-tooling` (DEC-030); there is no second
  parser here.
- It generates into the consuming module's own sources, never into `.jcodebuddy/` (DEC-026).
