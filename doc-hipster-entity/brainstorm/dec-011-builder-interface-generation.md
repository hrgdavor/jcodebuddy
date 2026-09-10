# DEC-011 Brainstorm: Automatic builder interface generation

This document expands the backlog item for automatic creation of builder interfaces and aligns it with existing naming and constructor-ingestion policy.

## 1. Problem statement

The project has decided builder behavior and naming constraints in DEC-008, but it still lacks a dedicated design for generated builder interfaces as stable contracts.

The objective is to generate builder interfaces that:
- preserve record-style naming consistency
- remain compatible with projection-only materialization
- allow multiple implementation strategies (proxy or generated concrete)

## 2. Scope and relationship to existing decisions

In scope:
- builder interface shape and naming rules
- required builder methods and optional capabilities
- compatibility contract across implementations

Related decisions:
- DEC-001: interface-first and record-style accessor naming
- DEC-008: builder policy and constructor ingestion preference
- DEC-013 draft: optional runtime module for implementation-mode selection

Out of scope:
- storage model for update tracking internals (DEC-012 draft)
- optional runtime factory/selection policy details (DEC-013 draft)

## 3. Proposed builder interface model

## 3.1 Base pattern

For each view interface `PersonSummary`, generate a builder interface:

```java
public interface PersonSummaryBuilder {
    PersonSummaryBuilder id(Long value);
    PersonSummaryBuilder firstName(String value);
    PersonSummaryBuilder lastName(String value);

    PersonSummary build();
}
```

## 3.2 Required methods

Required:
- one mutator per logical property
- `build()` terminal method
- source-ingestion factory or constructor equivalent in implementation contract

Optional:
- `mergeFrom(ViewType source)`
- `clear(Property)` or `clearAll()`
- patch helpers such as non-null merge variants

## 3.3 Source-ingestion compatibility

Even if Java interfaces cannot define constructors, generated builder ecosystem must guarantee source ingestion by one of:
- static factory: `PersonSummaryBuilders.from(source)`
- implementation constructor: `new PersonSummaryBuilderImpl(source)`

Recommendation:
- standardize on factory for API-level stability
- allow constructor detail in implementation classes

## 4. Naming and determinism rules

- mutator names match logical property names exactly
- deterministic method ordering across generation runs
- avoid JavaBean setter prefixes unless explicitly configured
- no renaming based on storage backend details

## 5. Implementation strategies

1. Proxy-backed implementation
- fast to bootstrap
- lower codegen footprint
- slower in hot paths

2. Generated concrete class
- best throughput and optimizer friendliness
- larger generated source surface
- stronger static analyzability

3. Hybrid
- default proxy, generated class for selected views
- requires strict compatibility test suite

## 6. Contract testing requirements

To guarantee interchangeable implementations:
- same observed behavior for all mutators
- same merge and overwrite semantics
- same null handling behavior
- same build output for same input sequence

Recommended test pattern:
- shared TCK-style suite executed against every implementation mode

## 7. Risks

- interface drift if generated contract and implementation features diverge
- hidden semantics in optional methods like `mergeFrom`
- inconsistent behavior when switching implementation mode

## 8. Open questions

- Should builder interface include metadata introspection methods?
- Should merge methods be standardized globally or opt-in per view?
- Do we need separate interfaces for read model materialization and write patching?

## 9. Suggested acceptance signals for eventual ADR

- one canonical generated interface pattern is documented
- source-ingestion guarantee is formalized at API level
- compatibility suite proves proxy and generated implementations are behaviorally equivalent
- optional methods are explicitly gated and semantically specified
