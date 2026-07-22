# DEC-013 Brainstorm: Factory strategy for proxy and generated implementation selection

This document expands the backlog item proposing an optional factory module that allows proxy variants to be replaced by generated implementations on a per-view basis for performance-critical paths.

This strategy is explicitly not a hard requirement of the `hipster-entity` model.
The core value of the project is interface-first metadata that enables tooling and higher-level systems.
Projects may consume that metadata directly and skip the factory module entirely.

## 1. Problem statement

The platform is intentionally hybrid:
- proxy implementations are fast to ship and flexible
- generated concrete implementations can be faster in hot paths
- selection should be configurable per view, not only as a global runtime mode
- the factory layer itself should remain optional and separable from the metadata core

Without a factory contract, switching strategies risks API drift and runtime inconsistency.

## 2. Goals

- one stable API for adopters that choose to use the optional runtime factory module
- explicit and predictable selection policy
- safe fallback behavior
- measurable criteria for when generated implementations should be used
- per-view override support with deterministic precedence
- preserve the ability for projects to bypass the factory layer and use metadata directly

## 2.1 Non-goal

- The factory strategy is not required for metadata generation, metadata consumption, or basic use of interface contracts.
- A project may build its own runtime composition model on top of generated metadata and never use the factory module.

## 3. Factory architecture options

## 3.1 Central service factory

Single entrypoint example:

```java
public interface EntityRuntimeFactory {
    <V> V createView(Class<V> viewType, Object source);
    <B> B createBuilder(Class<B> builderType);
    <B, S> B createBuilderFrom(Class<B> builderType, S source);
}
```

Pros:
- one abstraction point
- easy policy centralization
- can be packaged as a separate optional module

Cons:
- broad interface can grow too large over time
- inappropriate as a hard dependency for projects that only need metadata/tooling outputs

## 3.2 Specialized factories

Separate concerns:
- `ViewFactory`
- `BuilderFactory`
- `MapperFactory`

Pros:
- cleaner boundaries
- simpler per-factory contracts

Cons:
- more composition and wiring burden

## 3.3 Registry with pluggable providers

Policy resolved through provider chain:
- generated provider first
- proxy provider fallback

Pros:
- extensible by module
- test-friendly replacement

Cons:
- provider ordering bugs can be subtle

## 4. Selection policy candidates

1. Static configuration
- module-level flag chooses proxy or generated mode

1.1 Per-view static override
- view-level rule can override module default for targeted hot paths

2. Capability-based
- use generated implementation for a specific view when present, else proxy fallback for that view

3. Performance-profile guided
- default proxy in dev/test, generated in production profile

4. Heuristic runtime switching
- collect metrics and switch mode dynamically
- likely too complex for initial phase

Recommendation:
- start with capability-based plus explicit per-view override in an optional runtime module layered above the metadata core

## 5. Compatibility contract

Non-negotiable invariants:
- same method-level behavior across modes
- same exception and diagnostics policy
- same naming and metadata interpretation
- same merge/update semantics for builders
- identical behavior when the same view is executed under proxy or generated mode

Verification approach:
- run shared behavior test suite for each mode
- include golden test vectors for read, write, and merge flows

## 6. Failure and fallback policy

Required behavior when generated class load fails for a specific view:
- diagnostic event with implementation id and reason
- deterministic fallback to proxy implementation for that same view if allowed
- fail-fast mode option for strict environments

Suggested logging fields:
- target interface
- target view id/name
- selected provider
- fallback reason
- mode override source (config, default, explicit)

## 7. Performance governance

Do not switch based on intuition alone. Define measurable thresholds:
- p95 latency under representative workload
- allocation rate per operation
- CPU utilization under sustained load

Recommended process:
- benchmark proxy baseline first
- benchmark generated variant
- switch default only when benefit clears threshold and complexity budget

## 8. Operational concerns

- module boundary between metadata core and optional factory runtime
- classpath and packaging for generated artifacts
- deterministic provider discovery order
- cache lifecycle for created handlers/instances
- test environment parity with production mode

## 9. Open questions

- Should the optional factory module expose a public API or remain an internal SPI initially?
- How are per-view overrides expressed?
- Should fallback be silent, warned, or prohibited by default?
- How do we expose selected implementation mode for diagnostics/testing?

## 10. Suggested acceptance signals for eventual ADR

- optional factory module boundary is stable and documented
- explicit selection policy and override precedence are defined
- fallback and fail-fast behavior is deterministic and tested
- benchmark evidence supports when to prefer generated over proxy when the optional runtime layer is adopted
