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

**Resolved since it was written down:** the `relations` field drops type arguments deliberately (a row keeps the
name a *match* wants, `TypeFacts.withoutTypeArguments`), and the written form is not lost with it — the relation
carries the **range** of its written form, so `ChildContext<AppContext>` is recovered by slicing the declaring file
at that range, verified against the row's own checksum (`SourceSlice`). A member carries its line and span, a type
its declaration range and an annotation its own, so every fact a consumer may need to *show* can point at the code
without the table holding a copy of it (DEC-040 D2 and D6, steps 3.0t).

## Two layers: base and extended (DEC-041)

The model has a named boundary at the file, and it is what makes per-file reuse possible:

- **Base metadata** is every fact derivable from **one source file's bytes alone** — path, checksum, size, the
  `generated` marker, type declarations, annotations and members as written, relations **unresolved**, and the
  file's import lines. No other file, no index, no classpath, no clock.
- **Extended metadata** is everything else — resolution, reverse indexes, cross-file answers, freshness verdicts,
  reports — and it must be a **pure function** of the base set.

DEC-041 requires each file's base set to be storable **with the hash it was computed from**, so a rebuild reuses
unchanged files instead of re-parsing them, and so a watch edit recomputes one file's worth of work. The class
index read above stays the addressing table consumers use, and becomes a **projection** of those entries. The
strictness is the point: a base entry for file *A* must be byte-identical whether file *B* exists, is edited or is
deleted — a per-file cache holding a cross-file fact is stale after an unrelated edit, and cannot say so.
Implementation is plan step 3.0u.

## Tests and the gate

`bun scripts/mvn-jdk25.js` runs the recorded gate, and this module is in it — so a change here is verified by
the engine's own tests plus the six `hipster-entity` modules that consume the model. The engine's tests are the
place a *model contract* is pinned (a round trip, an always-emitted field, a refusal of an unknown kind), not
only a feature.
