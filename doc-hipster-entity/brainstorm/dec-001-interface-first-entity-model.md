# DEC-001 Brainstorm: Interface-first entity model

This document captures the early rationale and tradeoffs behind DEC-001.

## Problem framing

The project needs entity contracts that are:
- stable for generation tooling
- composable across multiple views
- independent from mutable JavaBean conventions

## Core proposal

Use interfaces as canonical entity/view contracts, with a minimal marker/root interface and layered read/write/detail views.

## Why this was attractive

- Strong compile-time shape for metadata generation.
- Easy composition via interface inheritance.
- Clear separation between contract and materialization strategy.
- Better fit for projection-driven query/use-case modeling.

## Alternatives considered

1. Concrete POJO/JPA-style entity classes as canonical model.
2. Record-first concrete types for all entity shapes.
3. Hybrid with class model for persistence and interface model for APIs.

## Tradeoffs

Pros:
- Flexible view hierarchy.
- Source-level clarity for code generation.
- Reduced coupling to reflection-heavy patterns.

Cons:
- Requires well-defined materialization/update patterns.
- Can introduce many interfaces if naming conventions are weak.
- Needs strict generator semantics for inheritance and duplicates.

## Follow-up topics linked later

- DEC-008 for builder policy and naming guarantees.
- DEC-004 for generated metadata over reflection.
- DEC-003 for projection-first read architecture.
