# DEC-010 Brainstorm: Proxy-backed entity and view bridge

This document explores a runtime bridge that materializes view interfaces from entity-backed data without requiring generated concrete classes for every view.

## 1. Problem statement

The project TODO calls for a proxy implementation that can read view properties from an entity source. The goal is to keep the default path lightweight and flexible while preserving the interface-first contract.

Key needs:
- quick adoption with low generation overhead
- compatibility with existing interface and metadata model
- predictable behavior for method-to-field mapping
- clear migration path to generated concrete implementations for hot paths

## 2. Scope and non-goals

In scope:
- dynamic proxy strategy for read-oriented view interfaces
- property method dispatch and metadata lookup
- fallback and diagnostics behavior
- performance envelope and when to switch strategies

Out of scope for this brainstorm:
- write/update mutation semantics (covered by DEC-012 draft)
- optional factory-module policy for proxy vs generated replacement (covered by DEC-013 draft)
- compile-time metadata generation details (already covered by DEC-004 and DEC-009)

## 3. Candidate architecture

## 3.1 Core runtime pieces

- `ViewProxyFactory` creates runtime proxy instances for a target view interface.
- `ViewInvocationHandler` maps method calls to field metadata entries.
- `EntityValueReader` abstraction reads values from the underlying entity source.
- `PropertyResolver` resolves view method to canonical property id.

Conceptual flow:
1. Call `ViewProxyFactory.create(PersonSummary.class, source)`.
2. Invocation handler intercepts `firstName()`.
3. Resolver maps `firstName` to a property key/ordinal.
4. Reader returns value from source.
5. Handler applies conversion if required and returns typed value.

## 3.2 Method mapping rules

Baseline mapping policy:
- Record-style accessors are canonical (`id()`, `firstName()`).
- `Object` methods are handled explicitly (`toString`, `hashCode`, `equals`).
- Default interface methods are optional and may be delegated where supported.

Error behavior options:
- strict mode: unknown property method throws descriptive exception
- lenient mode: unknown method returns null/default where legal

Recommendation:
- use strict mode by default to avoid silent data corruption

## 3.3 Source abstractions

Potential source backends:
- entity object with generated metadata
- array-backed row (`Object[]` + ordinal mapping)

Unified reader contract:
- read by property id
- optional typed read helper
- optional presence check for partial projections

## 4. Performance model

Expected strengths:
- avoids generating many classes for early-stage usage
- supports broad flexibility while contracts settle
- minimizes source churn

Expected costs:
- reflective or method-handle dispatch overhead per accessor
- potential boxing/casting overhead
- less JIT optimization versus concrete classes

Mitigations:
- cache method to property mapping at proxy construction
- prefer `MethodHandle` over raw reflection where feasible
- expose path to generated concrete adapter for hotspots

## 5. Developer ergonomics

Desired DX:
- one-line materialization from source to view
- diagnostics that include interface name, method name, and property key
- debuggable `toString` exposing mapped property set

Example API shape:

```java
PersonSummary view = viewProxyFactory.create(PersonSummary.class, entitySource);
```

## 6. Risks and failure modes

- hidden runtime errors if method mapping is too permissive
- mismatch between metadata and runtime source shape
- poor performance in tight loops if used indiscriminately
- default-method handling complexity in dynamic proxies

## 7. Alternatives

1. Generated concrete classes only
- Pros: best runtime performance, strongest type-specific optimization
- Cons: more generation complexity and churn

2. Reflection without dynamic proxy abstraction
- Pros: simpler initial implementation
- Cons: weak interface ergonomics and less encapsulation

3. Bytecode-generated runtime classes
- Pros: potentially near-concrete performance
- Cons: violates source-visible preference and increases debugging complexity

## 8. Open questions

- Should default interface methods be supported in first version?
- Is strict mode always default, or configurable per module?
- Should proxy layer support field-level converters directly or delegate externally?
- Which metric should trigger generated replacement recommendation (p95 latency, allocation rate, CPU)?

## 9. Suggested acceptance signals for eventual ADR

- deterministic method-to-property mapping rules are documented
- strict diagnostics for unresolved methods are implemented
- benchmark compares proxy and generated implementations on representative read workloads
- migration path to generated implementation is clear and testable
