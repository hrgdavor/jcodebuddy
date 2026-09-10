# Annotation Processing vs Generated Metadata and Materialized Code

This document is a deeper rationale for two related project positions:
- why `hipster-entity` does not adopt Java annotation processing as its primary generation model
- why extracting metadata and materializing it as code is preferred over reflection-heavy runtime introspection

It complements:
- [DEC-004 generated metadata over reflection](dec-004-generated-metadata-over-reflection.md)
- [DEC-009 source-visible generation strategy](dec-009-source-visible-generation-strategy.md)

## 1. Executive summary

`hipster-entity` is metadata-first.
The project is designed to turn interface contracts into explicit metadata and optional generated code that other tools can consume predictably.

That leads to two choices:
- source analysis and source-visible generation are preferred over annotation processing
- generated metadata and direct materialized code paths are preferred over repeated runtime reflection

These choices are not only about developer taste.
They affect startup time, steady-state throughput, debuggability, build determinism, portability to constrained runtimes, and the ability to build higher-level tooling on top of a stable model.

## 2. Why annotation processing is not the primary model

## 2.1 Annotation processing is tied too tightly to compilation lifecycle

Annotation processors run inside the Java compilation pipeline.
That coupling is useful for certain narrow tasks, but it is a poor fit for a project that wants:
- source-aware incremental tooling
- editor-side generation workflows
- controlled behavior when sources are temporarily invalid
- explicit review of generated artifacts

In practice, annotation processing tends to collapse several concerns into one step:
- parse user code
- resolve symbols
- generate outputs
- compile outputs

When those phases fail together, the failure is harder to isolate.
A source-to-source generator can surface those phases separately and more clearly.

## 2.2 Annotation processing behaves inconsistently across environments

Real-world processor usage often differs across:
- IDE incremental compilers
- Maven and Gradle command-line builds
- CI environments
- mixed Java/Kotlin or mixed-source projects

Typical failure patterns:
- generated sources present in one environment but missing in another
- stale generated outputs surviving partial builds
- ordering issues between processors or generated source roots
- confusing behavior during refactors when symbols are unresolved temporarily

For a metadata-first project, that inconsistency is expensive because the metadata model is supposed to be the stable foundation for other tools.

## 2.3 Annotation processors encourage hidden generation

Processor outputs are often treated as build byproducts instead of first-class source artifacts.
That creates several problems:
- code review cannot easily inspect the generated shape
- debugging requires understanding processor internals rather than reading output code
- consumers cannot rely on generated artifacts being present in VCS
- downstream tooling cannot easily treat generated code as ordinary source unless the build has already run successfully

`hipster-entity` wants the opposite:
- generated artifacts should be visible
- generated artifacts should be diffable
- generated artifacts should be inspectable without stepping through compiler internals

## 2.4 Annotation processing is a poor fit for partial-broken-source workflows

During refactors, files are often temporarily syntactically or semantically broken.
A source-aware sidecar generator can respond conservatively:
- skip a broken file
- preserve prior generated outputs
- report the exact parse failure
- continue processing unaffected source units where safe

Annotation processing typically enters the pipeline only after compilation is already underway, which makes graceful partial behavior harder and more fragile.

## 2.5 Annotation processing is the wrong abstraction level for editor-first tooling

The project is not only generating helper code.
It is building a model that should support:
- metadata export
- code generation
- diagnostics
- validation
- adapter generation
- future editor workflows

That is broader than “emit some extra Java files during javac”.
A separate source-analysis pipeline is a better architectural center because it can be reused by many tools, not just the compiler.

## 2.6 Annotation processing gets more fragile more code is generated

This is where practical experience matters most.
In our own experiments with custom processors and Dagger-based graphs, fragility increased as generated surface area grew.

Observed pattern from custom processors:
- incremental mode frequently runs without full-project visibility, so cross-type metadata (especially collection and graph-level metadata) is incomplete in some rounds
- trying to compensate with processor-side caches introduces a second consistency problem (cache invalidation across partial recompiles)
- the more generated outputs depend on transitive type information, the easier it is for one stale edge to poison downstream generated code
- failure recovery often degrades into repeated clean builds rather than deterministic incremental fixes

Observed pattern from Dagger usage:
- generated components and binding graphs are sensitive to ordering and transitive visibility during edits
- during refactors, temporary symbol breakage tends to produce large error cascades that are hard to map back to one source change
- stale generated artifacts can survive just long enough to create misleading diagnostics, especially in IDE incremental loops
- multi-module graph updates can require broader rebuild scope than expected, reducing iteration speed

These problems are not unique to one codebase.
Across processor-heavy stacks, teams commonly report similar limitations:
- aggregating processors reduce incremental-build effectiveness because they need broader project knowledge
- isolating processors scale better incrementally, but many real generators are only partially isolating in practice
- mixed environments (IDE compiler, Maven/Gradle CLI, CI) can disagree on when generated sources are current
- round-based processing model makes partial-broken-source workflows awkward compared with sidecar source analysis
- diagnostics often appear at generated call sites rather than at the conceptual source-of-truth location

The key point is not that annotation processing never works.
The key point is that reliability cost rises with generation breadth and graph complexity.
For metadata-first architecture, those costs land directly on the most critical path: deterministic model generation.

That is why this project prefers:
- source analysis outside javac rounds
- explicit, source-visible generated artifacts
- deterministic regeneration semantics under partial edits
- failure modes that degrade locally (one file or one unit), not globally (whole processor graph)

External signals from ecosystem documentation:
- Gradle documents explicit limitations for incremental annotation processing, including strict processor constraints (`isolating` vs `aggregating`), conditions that disable incremental behavior, and cases that force broader recompilation.
- Gradle also documents that aggregating processors are always reprocessed and their generated outputs recompiled, which directly aligns with observed scaling fragility for graph-level generators.
- Kotlin kapt documentation flags operational caveats: build-cache reliability depends on processor behavior, IntelliJ's internal build does not support kapt, and Kotlin-source generation has round-model limitations (for example, no multiple rounds for generated Kotlin sources).
- Kotlin documentation explicitly recommends Kotlin Symbol Processing (KSP) for Kotlin projects, which is a practical signal that classic annotation-processing pipelines carry non-trivial overhead/constraints in modern Kotlin builds.
- Dagger documentation confirms strict compile-time graph validation and extensive generated-code surface (`Dagger*`, `*_Factory`, `*_MembersInjector`), which explains why refactor-phase failures can cascade when dependency graphs are incomplete or temporarily inconsistent.

Reference pages:
- https://docs.gradle.org/current/userguide/java_plugin.html
- https://kotlinlang.org/docs/kapt.html
- https://dagger.dev/dev-guide/

## 3. Why generated metadata and materialized code are preferred over reflection

## 3.1 Reflection recomputes structure that is already known from source

The project’s core inputs are source interfaces.
From those interfaces, the generator already knows:
- entity marker hierarchy
- property names
- property order
- generic type structure
- field origin semantics
- per-view exposure
- divergence across views
- collected annotations

Reconstructing that same graph again at runtime through reflection is duplicated work.
It adds runtime cost for information that could have been extracted once and materialized explicitly.

## 3.2 Reflection pushes cost to startup and hot paths

Reflection has two distinct costs:
- discovery cost: scan classes, methods, annotations, generics, inheritance
- invocation cost: dynamic access, lookup indirection, boxing/casting, weak optimizer visibility

For metadata-heavy systems, this often shows up as:
- longer startup and warmup
- more allocations during initialization
- extra caches and reflective adapters just to recover structural information
- weaker optimization opportunities for hot code paths

Generated metadata moves discovery cost out of runtime.
Generated code can also move invocation cost out of generic reflective machinery and into explicit direct access paths.

## 3.3 Generated metadata is easier for JIT and AOT toolchains to optimize

Reflection hides intent behind generic APIs.
Generated code makes intent concrete.

That matters for:
- JVM JIT inlining and branch simplification
- reduced metadata scanning at startup
- static analysis for native-image and closed-world runtimes
- lower reliance on reflection configuration and reachability hints

In other words, generating metadata and direct code paths gives the runtime fewer unknowns.
That is the same class of benefit people often chase through aggressive reflection-elimination strategies in native-image-oriented setups.

A practical way to say it:
- if the structure is already known from source, turning it into explicit code often captures a large share of the performance and startup benefit that teams otherwise try to recover later through specialized runtime optimization work
- that benefit is portable across ordinary JVM deployments as well, instead of being dependent on runtime-specific tuning or premium optimization paths

## 3.4 Reflection is especially costly for generic-rich metadata models

This project cares about more than simple getters.
It needs to model:
- transitive interface inheritance
- view-specific type divergence
- nested generic structures
- field origin semantics
- annotation payloads

Reflection can expose those pieces, but doing so repeatedly and robustly is cumbersome.
The result is usually a runtime metadata layer that:
- parses and normalizes reflective results into custom descriptors
- builds caches
- handles corner cases around generics and annotations
- still remains harder to inspect than generated metadata

That runtime normalization layer is effectively a generator that was moved into production startup.
The project prefers to do that work once, earlier, and visibly.

## 3.5 Generated code turns implicit contracts into explicit ones

Reflection-heavy systems often rely on conventions that are only validated at runtime.
Generated metadata and materialized code make those contracts concrete:
- a property enum exists or it does not
- a converter requirement is emitted or it is not
- a builder method exists or it does not
- a field is marked `DERIVED` or `JOINED` in explicit output

That improves:
- code review
- testability
- diffability
- backward-compatibility management
- downstream tool integration

## 4. Performance rationale in more concrete terms

## 4.1 Startup and warmup

Reflection-heavy designs often do some or all of the following at runtime:
- scan packages or classes
- resolve interface graphs
- inspect methods and annotations
- build metadata descriptors
- construct caches for repeated access

Generated metadata allows startup to skip most of that work.
Instead of discovering structure, the system loads already-materialized structure.

## 4.2 Allocation reduction

Reflection-based frameworks commonly allocate:
- method descriptor wrappers
- annotation wrappers
- generic signature helpers
- maps keyed by method names or field names
- adapter lambdas or accessors

Generated enums, metadata records, and direct helper code reduce that allocation pressure because the shape is already fixed.

## 4.3 Hot-path execution

When materialized code replaces generic reflective access, the runtime sees:
- direct method bodies
- explicit ordinals
- concrete switch cases
- static types in generated helper APIs

That makes it easier to inline, eliminate branches, and optimize repeated operations.
The effect is especially meaningful for repetitive mapping work such as:
- row-to-view adaptation
- field enumeration
- view-to-view copying
- validation dispatch
- serialization helpers

## 4.4 Native-image and constrained runtime friendliness

Reflection is one of the common friction points for native-image-style deployment and other constrained runtimes.
Reflection often requires:
- explicit configuration
- extra reachability metadata
- broader retention of types and members
- careful testing for missing reflective access

Generated metadata and explicit code reduce that pressure.
They provide a closed, visible description of what is needed.
This does not remove all native-image concerns, but it materially improves the situation because less behavior depends on runtime discovery.

## 4.5 Comparison to premium/runtime-specific optimization stories

Some ecosystems advertise performance improvements by hiding or reducing reflective work through specialized optimization layers.
The project’s position is that a large part of that benefit can be recovered earlier and more transparently by:
- extracting metadata at source-analysis time
- materializing that metadata as code and stable artifacts
- avoiding reflection in hot or repeated runtime paths

That makes the performance model:
- visible rather than magical
- portable rather than vendor-specific
- reviewable rather than opaque

## 4.6 What this can and cannot recover

Official GraalVM Native Image documentation makes several relevant points:
- dynamic JVM features such as reflection, dynamic proxies, resource discovery, and similar runtime lookups require reachability metadata in native-image builds
- those dynamic features carry extra startup and memory overhead because the runtime would otherwise need all classes/resources available and discoverable at runtime
- missing reflection metadata causes runtime failures such as `MissingReflectionRegistrationError`

That means the project can make a defensible claim in three parts.

### Part A: Yes, this approach can recover some of the same class of benefit

By extracting metadata ahead of time and generating explicit code paths, the project can reduce:
- reflection usage
- reachability-metadata burden
- startup-time structural discovery
- runtime cache-building for reflective access

Those are real benefits on both HotSpot and Native Image.
In Native Image specifically, they reduce friction around closed-world analysis and decrease the amount of dynamic behavior that must be described to the image builder.

So yes: there are benefits here that overlap with the kind of performance and startup improvements teams often seek from more advanced optimization stacks.

### Part B: No, this does not replace compiler-level optimization

This approach does **not** substitute for compiler-level features such as:
- profile-guided optimization
- higher optimization modes
- profile-inference-driven code generation

Those features operate at the compiler optimization level.
`hipster-entity` operates one layer above that by reducing dynamic runtime work and making structure explicit.

So the correct statement is:
- generated metadata and materialized code can reduce the need for reflection-heavy fallback machinery and improve startup/compatibility/perf characteristics
- but they do not replace compiler-level optimizations that depend on profile feedback or more aggressive backend optimization strategies

### Part C: Where the overlap is strongest

The overlap is strongest in these areas:

1. Native-image compatibility and configuration burden
- less reflection means fewer reachability metadata entries and fewer missing-registration failures

2. Startup and footprint
- less runtime discovery and less reflective adapter setup can improve startup behavior and reduce memory pressure

3. Predictability
- explicit generated structure is easier for static analysis to understand than convention-based runtime discovery

The overlap is weaker in these areas:

1. Peak throughput tuning
- profile-guided and other aggressive optimization modes can still outperform a reflection-light design when the compiler has better runtime information

2. Machine-specific code quality
- compiler flags like `-march=native` and machine-specific optimization pipelines remain orthogonal benefits

## 4.7 Recommended wording for the project

If the project wants to mention this explicitly without overstating it, a safe formulation is:

> By turning source-known structure into generated metadata and explicit code, `hipster-entity` reduces reflection, lowers Native Image metadata burden, and improves startup/runtime predictability. This can recover part of the same class of benefit teams often seek through more advanced optimization approaches, especially around startup, footprint, and closed-world compatibility. It does not replace compiler-level profile-driven or backend optimizations, but it reduces how much the runtime must discover dynamically.

## 4.8 Further gains boilerplate can target

There are additional gains that generated boilerplate can target beyond the baseline metadata-vs-reflection argument.
These do not replace compiler optimizations, but they can improve performance directly and make advanced optimization passes more effective.

### 4.8.1 Generate build-time-initialization-friendly metadata holders

Official Native Image documentation states that runtime class initialization hurts startup and can reduce performance substantially, while build-time initialization removes runtime checks for initialized classes.

Boilerplate opportunity:
- generate metadata holders as simple `final` classes with `static final` fields only
- avoid unsafe static initialization patterns in generated code
- separate pure metadata classes from runtime-bound helpers so the metadata classes are good candidates for build-time initialization

Why this helps:
- improves startup in Native Image
- gives the image builder more safe classes it can initialize at build time
- reduces runtime class-initialization checks

### 4.8.2 Replace dynamic proxies with generated direct implementations on hot paths

Dynamic proxies require metadata in Native Image and are less transparent to static analysis than direct classes.

Boilerplate opportunity:
- generate direct view adapters or direct builder implementations for hot views
- reserve proxy-based implementations for flexible or low-frequency paths

Why this helps:
- reduces dynamic proxy metadata/configuration burden
- creates more direct call graphs for GraalVM analysis
- gives compiler optimizations more straightforward code to inline and specialize

This is one of the clearest examples where generated boilerplate can improve the effectiveness of GraalVM analysis and optimization.

### 4.8.3 Emit constant, closed-world-friendly registration code instead of generic runtime discovery

GraalVM documentation explicitly notes that constant metadata in code is a preferred way to provide metadata because the builder can compute it at build time.

Boilerplate opportunity:
- generate constant registration code for reflective edge cases that cannot be removed entirely
- generate explicit lists of classes, methods, fields, proxies, and resources where required
- generate narrow, exact metadata rather than broad wildcard-style registration

Why this helps:
- reduces missing-registration failures
- can reduce image size compared with broad metadata declarations
- keeps more dynamic behavior analyzable and explicit

### 4.8.4 Generate serializers, mappers, and binders that avoid generic reflection-heavy frameworks

Boilerplate opportunity:
- generate JSON writers/readers for views
- generate DB row readers and binders
- generate validation code from collected annotation metadata
- generate message codecs and store adapters

Why this helps:
- removes reliance on reflective serializers or generic bean introspection
- reduces runtime adaptation layers and associated allocation/caching
- gives native-image fewer dynamic integration points to preserve

This is especially useful because many frameworks pay a reflection tax even before GraalVM-specific concerns enter the picture.

### 4.8.5 Generate primitive-specialized and ordinal-specialized code paths

Boilerplate opportunity:
- use property ordinals instead of name-based maps for repeated operations
- generate primitive-aware access paths where possible
- specialize common loops such as copy, compare, serialize, and validate

Why this helps:
- lowers allocation and boxing overhead
- gives the compiler simpler loops and branch structure
- increases the upside of aggressive optimization because the hot code is already explicit and stable

This is an important point: advanced compiler optimizations can often do *more* with direct, regular, generated code than with generic reflective machinery.

### 4.8.6 Generate code with stable control flow that benefits advanced optimization

Profile-guided and other aggressive optimization techniques work at the compiler level.
They are more valuable when the application exposes stable, analyzable hot paths.

Boilerplate opportunity:
- generate straight-line adapters instead of layered reflective dispatch
- minimize megamorphic call sites in generated hot-path code
- keep generated dispatch tables deterministic and shallow

Why this helps:
- does not reproduce compiler-level profiling, but gives advanced optimization passes better material to optimize
- can amplify the benefit of stronger optimization strategies on top of the baseline gains from reflection elimination

This is the strongest defensible statement about “recovering some of the same outcomes”: the project can shape the code so stronger optimizers have more profitable targets.

### 4.8.7 Generate resource/configuration loading patterns that can be moved to build time

Official documentation notes that loading configuration during image build can improve startup.

Boilerplate opportunity:
- separate static schema/config metadata from runtime mutable state
- generate configuration descriptors that can be loaded or frozen at build time
- avoid generated code that opens files, sockets, threads, or other unsafe objects in static initialization

Why this helps:
- increases the chance that metadata and support classes are build-time-initializable
- improves startup and keeps generated code native-image-friendly

## 4.9 Where generated boilerplate can recover similar outcomes

The following table is the safe way to frame the relationship.

| Project technique | Direct project benefit | Can recover similar outcome class | Why |
|------------------|------------------------|----------------------------------|-----|
| Reflection elimination | Yes | Yes | Reduces dynamic overhead and leaves cleaner hot paths for advanced optimization |
| Direct generated adapters instead of proxies | Yes | Yes | Better inlining and profiling opportunities |
| Build-time-initialization-friendly metadata holders | Yes | Yes | Better startup baseline; compiler has less runtime setup to preserve |
| Constant metadata registration in code | Yes | Partially | Improves compatibility and reduces metadata noise, but not a compiler optimization itself |
| Primitive/ordinal specialization | Yes | Yes | Gives stronger optimization passes more explicit hot loops to optimize |
| Compiler-level profiling | No | No | Compiler feature, not reproducible by this project |

## 4.10 Recommended project position

The strongest defensible project statement is:

> `hipster-entity` cannot replace compiler-level profile-driven or backend optimization features. However, by generating explicit, reflection-light, build-time-friendly boilerplate, it can improve baseline performance in any runtime and also create code shapes that allow GraalVM and similar toolchains to optimize more effectively.

## 5. Tooling rationale

## 5.1 Metadata is useful outside the JVM

Generated metadata can be consumed by:
- Java code generators
- documentation generators
- UI/schema tooling
- validation tooling
- database/query generators
- non-JVM tools that only need JSON or source-visible artifacts

Reflection cannot serve those consumers directly without embedding a JVM reflection phase somewhere in the pipeline.

## 5.2 Metadata enables a layered product model

The project is intentionally structured so that metadata is the foundation.
On top of that foundation, optional layers can be built:
- property enums
- builders
- proxies
- adapters
- mapper generation
- optional runtime modules such as factory strategies

This layered model is cleaner when metadata is first-class and explicit.
Reflection pushes too much intelligence into runtime libraries instead of making the structural model reusable.

## 6. Counterarguments and why they are not decisive here

## 6.1 “Reflection is simpler”

It is simpler only at the beginning.
Once the project needs deterministic metadata, generic-awareness, annotation payloads, divergence rules, and reusable outputs, reflection-based simplicity disappears.
The complexity returns as runtime caches, adapters, and defensive logic.

## 6.2 “Annotation processing is standard Java practice”

It is standard for some use cases.
That does not make it the best fit here.
The project needs editor-aware, source-visible, metadata-centered workflows that are broader than compiler-attached generation.

## 6.3 “Generated code increases repository size”

True, but this is a deliberate trade.
The project values:
- visibility
- debuggability
- explicit ownership
- deterministic outputs
- the ability to freeze or manually inspect artifacts during incidents

over minimizing source tree size at all costs.

## 6.4 “Modern JVMs optimize reflection reasonably well”

They do, but that misses the point.
The issue is not only the raw cost of reflective invocation.
The bigger issue is that reflection keeps structural information implicit and runtime-discovered.
The project benefits from making that structure explicit for tooling, portability, and deterministic generation even before runtime performance is considered.

## 7. Recommended project wording

When describing the rationale concisely in ADRs or README-style docs, the project can use wording like:

> `hipster-entity` is metadata-first. Source interfaces are analyzed once and turned into explicit metadata and optional generated code. This is preferred over annotation-processing-driven and reflection-heavy approaches because it improves reviewability, startup behavior, tooling portability, and optimization potential while keeping structural contracts explicit.

## 8. Practical benchmark directions

If the project wants to support these claims with measurements, benchmark at least the following:

1. Metadata discovery cost
- reflection scan of representative entity/view package
- loading equivalent generated metadata

2. Access path cost
- reflective field or method access
- proxy-backed access using generated metadata
- direct generated materialized access

3. Startup profile
- application startup with runtime discovery
- application startup with generated metadata only

4. Native-image/constrained-runtime friction
- amount of reflection configuration required
- startup and binary behavior with reflection-heavy vs generated approach

## 9. Summary

The project is not rejecting annotation processing or reflection because they are universally bad.
It is rejecting them as the primary architecture because they put too much structural work into the wrong phase.

For `hipster-entity`, the right center of gravity is:
- source interfaces as the source of truth
- generated metadata as the reusable structural model
- optional materialized code as the fast and explicit execution path
- reflection only as fallback, diagnostics aid, or compatibility layer
