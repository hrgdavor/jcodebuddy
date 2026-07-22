# DEC-007 Brainstorm: Balancing projection performance and ergonomics

This document expands DEC-007 and clarifies tradeoffs between raw speed and developer usability.

## Goal

Deliver low-allocation projection reads without creating a fragile, low-level API surface.

## Performance targets

- Minimize per-row allocations.
- Support streaming JSON output.
- Avoid unnecessary intermediate containers.

## Ergonomics targets

- Keep generated APIs discoverable and predictable.
- Preserve strong typing where needed.
- Offer graceful fallback to materialized values.

## Candidate API layers

1. Lowest level: row/document cursor adapters.
2. Middle level: generated projection binders.
3. High level: optional materialized wrappers/builders.

## Decision pressure

If only the lowest level exists, adoption suffers. If only high-level materialization exists, performance goals are diluted.

## Recommended shape

Support layered APIs with constructor-first materialization as the main ergonomic path and optional low-level streaming for hot paths.
