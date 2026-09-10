# DEC-003 Brainstorm: Projection-oriented read path

This document expands DEC-003 with practical architecture options and implementation constraints.

## Goal

Treat projection-first reads as a first-class architectural path for SQL and NoSQL access.

## Core use cases

- Read-heavy APIs that do not require full domain entity materialization.
- Query-side shape optimization where response contracts differ from write models.
- Fast path rendering into JSON where allocation pressure must stay low.

## Design options

1. Interface projection plus runtime proxy.
2. Interface projection plus generated adapter.
3. Record-like materialized view generated from projection metadata.

## Recommended direction

- Keep interface projection as canonical read contract.
- Prefer generated adapters for stable performance and deterministic behavior.
- Keep optional materialization layer for consumers that need concrete immutable values.

## Risks

- Over-fragmenting view definitions.
- Type divergence complexity across views.
- Ambiguous null/missing-field semantics.

## Guardrails

- Stable property ordering.
- Deterministic mapping rules.
- Clear optional/missing semantics.
- Build-time diagnostics for incompatible mappings.
