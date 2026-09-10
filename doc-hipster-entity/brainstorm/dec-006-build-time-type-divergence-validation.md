# DEC-006 Brainstorm: Build-time type divergence validation

This document expands DEC-006 with converter governance and validation flow.

## Motivation

Type mismatches across views are expected in real systems, but runtime discovery of missing converters is too late.

## Validation flow

1. Collect per-field per-view types from metadata.
2. Detect divergent type pairs by mapping direction.
3. Resolve converter registry coverage.
4. Emit deterministic diagnostics before runtime.

## Severity policy

- Error: required converter missing.
- Warning: converter exists but path is not reachable.
- Info: converter resolution complete.

## Converter contract principles

- Explicit and testable registration.
- Generic-aware conversion when container element types differ.
- Primitive boxing/unboxing treated separately from semantic conversion.

## Risks

- Too-strict validation can block progress in migration phases.

## Mitigation

- Support scoped suppressions with expiration and review policy.
