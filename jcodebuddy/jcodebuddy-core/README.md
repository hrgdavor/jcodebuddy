# jcodebuddy-core — the metadata engine

One model, one parse path, one index and one freshness contract for everything that needs to know what a Java
project *says*: codegen, code analysis, reports, the watch loop, and a future LSP sidecar. Every other module
in this reactor is a **consumer** or a **transport** — see [DEC-037](../../doc-hipster-entity/architecture/decisions/DEC-037.md),
which decided the engine, and [DEC-040](../../doc-hipster-entity/architecture/decisions/DEC-040.md), which
decides what the model is not allowed to lose.

## What is in here

| package          | what it answers                                                          |
| ---------------- | ------------------------------------------------------------------------ |
| `engine.source`  | the one parse path: `SourceReader` (DEC-030's representation, and the `readable()` verdict), `TreeQueries` (traversal, kinds, type text, line lookup) |
| `engine.meta`    | file-scoped facts — `SourceMetadata`, `TypeInfo` and friends, as written |
| `engine.index`   | the class index of DEC-029: `ClassRecord` per declaration — kind, modifiers, relations, annotations, **members**, and the file's checksum identity |
| `engine.query`   | `MetadataQuery` (relations, annotations, members, kind/modifier/package/path) and the generator-facing `TypeResolver` / `TypeDefinition` seam |
| `engine.codegen` | the generator SPI: `CodeGenerator`, `CodeContext`, `CodeContextImpl`     |
| `engine.fresh`   | the freshness contract — `SAFE` / `STALE` / `UNKNOWN` — so a consumer can tell whether an answer is safe to generate from |

The marker vocabulary that used to live here is **not** here: step 3.0l moved it to `jcodebuddy-generated`, so
that a tool wanting only "where does generated code stop" resolves neither a Java parser nor a JSON library.

## The rule a consumer relies on: metadata is richer than the runtime

**What the compiler removes, this model keeps** ([DEC-040](../../doc-hipster-entity/architecture/decisions/DEC-040.md),
the maintainer's direction of 2026-10-03):

> metadata should strictly avoid hiding any generics or relations that java might be hiding when compiling, we
> want to be better positioned than runtime code

Concretely, for anyone reading or extending this module:

- **Type arguments are reachable, not copied.** A member's type is recorded as written (`List<String>`), on a
  member, a parameter and a relation — and the *range* of that source is what the table carries, so a consumer
  slices the file for the written form instead of reading a second copy that can fall out of step. Erasure is why
  the arguments matter; staleness is why they are not stored twice.
- **A row points at the code.** A member carries its line and range, a relation its range: a navigation diagram,
  a review page or an IDE jump needs to *point*, and metadata that cannot point is metadata they cannot use
  (DEC-040 D6).
- **The name and the written form are two questions, and both must be answerable.** Matching wants
  `ChildContext`; fidelity wants `ChildContext<AppContext>`. The name is recorded and derived from the form the
  source wrote; the form itself is read from the file at the fact's range.
- **Resolution never replaces the name or the range.** `List` → `java.util.List` is a second fact, not a better first one,
  and resolution needs the imports and the whole index.
- **An omission is reported, never answered as "none".** A gap and a "no" must be distinguishable to a
  consumer: this is DEC-029's always-emitted rule and DEC-035's marker rule, applied to the model as a whole.
- **A consumer answers from the model, without parsing a file.** No file parse, no class loader, no classpath —
  that is what makes a generator a function of the model (steps 3.0d, 3.0e).

**Known debt, measured rather than implied:** the `relations` field still drops type arguments
(`TypeFacts.withoutTypeArguments`), so `X extends ChildContext<AppContext>` is recorded as `ChildContext`. Under
DEC-040 that is a defect and it is the first prerequisite of step 3.0e's remaining half; the plan records it.

## Tests and the gate

`bun scripts/mvn-jdk25.js` runs the recorded gate, and this module is in it — so a change here is verified by
the engine's own tests plus the six `hipster-entity` modules that consume the model. The engine's tests are the
place a *model contract* is pinned (a round trip, an always-emitted field, a refusal of an unknown kind), not
only a feature.
