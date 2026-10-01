# hipster-ioc-tooling

The context generator for [hipster-ioc](../hipster-ioc/README.md), and the dependency graph it computes.
The decision it implements is [DEC-036](../doc-hipster-entity/architecture/decisions/DEC-036.md).

## What it does

Reads a `@HipsterContext` interface and writes `<Context>Impl` beside it, in the same package:

```
hipster-ioc-test/src/test/java/hr/hrg/hipster/ioc/test/
├── CtxMain.java          the context interface — the user's source of truth
├── CtxMainModule.java    package-private, holds `default ObjectMapper buildMapper()`
└── CtxMainImpl.java      generated: fields, creation in dependency order, accessors
```

The generated class **implements the interface directly** — no proxy, no reflective lookup, no
name-to-bean registry — so a reader with a stock IDE follows `ctx.mapper()` to the field that holds it
(DEC-019). Creation order comes from the factories' parameters, and each line names the factory that built
it, so the object graph is legible top to bottom. Beans are created eagerly; laziness is opt-in through
`Supplier`/`DynamicResource` with a factory, and a lazy bean *without* one is refused rather than guessed at.

The generated file carries DEC-035's file marker, follows DEC-020's cooperative rules (a member you add is
preserved verbatim), and honours `enabled:false` in its header as a whole-file freeze (DEC-021 § 6).

## Running it

```bash
bun scripts/ioc-gen.js                      # hipster-ioc-test/src/test/java
bun scripts/ioc-gen.js --root <dir>         # another source root
bun scripts/ioc-gen.js --indent "  "        # four spaces by default
bun scripts/ioc-gen.js --quiet              # print only the divergences
```

It is **not** a build step: nothing is bound to a Maven lifecycle phase, so `mvn test` only compiles the
committed generated source. The script pins JDK 25 and exports the reactor classpath; run it after changing
an interface, and commit the result.

Exit code is non-zero when a context was **refused**, so a check can depend on it.

## What it refuses, and why refusing is the answer

| Diagnostic | When | What it means |
| --- | --- | --- |
| `circular_dependency_unmarked` | the factories' parameters form a cycle and no parameter is `@Circular` | no creation order exists; the file is left untouched |
| `circular_dependency_marked_unsupported` | a parameter *is* marked `@Circular` | the two-phase form that would honour the mark needs a `Supplier` parameter, which the API cannot express yet (DEC-036 § 5) |
| `lazy_bean_needs_factory` | a `Supplier<X>`/`DynamicResource<X>` bean with no `build…` method | `new Supplier<X>()` is not valid Java, and inventing one would capture a bean nobody built |
| `context_implementation_present` | `@HipsterContext(impl = …)` names a class | an implementation already exists; the generator must not compete with it |
| `source_not_parsed` | the file cannot be read cleanly | nothing is generated — a half-read context is a half-wired one |

A factory parameter the context cannot provide is **not** an error: it becomes a constructor parameter of the
generated class, so the caller supplies it and no generated code ever passes a `null`.

## The dependency graph

Each pass writes `<module>/.jcodebuddy/metadata/hipster-ioc/contexts.json`: the contexts, their beans, how
each bean is created, and the parent type where there is one. It is derived, ignored output (DEC-026) and the
model a report renders from — a report is Bun JavaScript reading that JSON, never HTML emitted by this
generator (DEC-027/029).

## Naming contract (DEC-022)

| Source | Generated | Rename-sensitive? |
| --- | --- | --- |
| `@HipsterContext interface CtxMain` | `CtxMainImpl`, same package | **yes** — the class names the interface in its `{@link}` and implements it, so an IDE rename of the interface reaches the generated class |
| accessor `ObjectMapper mapper()` | field `mapper` + `@Override mapper()` | **yes** — the name is the interface's own |
| `default Widget buildWidget(Gadget gadget)` | the creation call `buildWidget(gadget)` | **yes** — the generator reads the method name on every pass, so a rename regenerates the call |
| `@HipsterContext(impl = ManualContext.class)` | nothing generated; the diagnostic names the class | **no** — a user-supplied reference, passed through as written |
| the generated file itself | `// @generated file hr.hrg.hipster.ioc.tooling.IocContextGenerator …` | **no** — the marker names the generator, which is stable |

## Boundaries

- **This is not a standalone, per-file generator — use the script, not the interface.** It implements the
  shared `CodeGenerator` SPI, whose contract is "offer me any file and I will answer about that file", but
  it needs a file *beside* the one it is given: the context's own compilation unit holds the interface,
  while the `default buildXxx(...)` factories live in the module interface in the **sibling**
  `<Supertype>.java`. It also writes `<module>/.jcodebuddy/metadata/hipster-ioc/contexts.json`, which
  describes the whole tree. Run it through `bun scripts/ioc-gen.js`, which walks a source root. The tier
  distinction is being made explicit in
  [the plan's step 7.8](../plans/unified-plan.md) and recorded in
  [DEC-036 § 11](../doc-hipster-entity/architecture/decisions/DEC-036.md); until then, a caller holding a
  list of generators must not hand this one an isolated file — and if a sibling module interface cannot be
  read, that is a missing neighbour to report, never an absence to infer.
- It does **not** depend on `project-automation`; another module must never depend on a project's private
  dev-time assistant, and this one implements the shared `CodeGenerator` SPI instead (AGENTS.md § 1.1).
- It reads Java through **OpenRewrite's LST** via `hipster-entity-tooling` (DEC-030); there is no second
  parser here.
- It generates into the consuming module's own sources, never into `.jcodebuddy/` (DEC-026).
