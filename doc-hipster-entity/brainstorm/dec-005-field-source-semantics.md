# DEC-005 Brainstorm: Field-source semantics as generator input

This document expands DEC-005 and details expected behavior for `COLUMN`, `DERIVED`, and `JOINED` fields.

## Problem

Without first-class source semantics, generators can produce incorrect write paths and confusing mapper behavior.

## Semantic model

- `COLUMN`: persisted value, eligible for read/write mapping.
- `DERIVED`: computed value, read-only by default.
- `JOINED`: relation-origin value, read-focused and non-local for persistence.

## Write-path policy

- `COLUMN` may map to writes.
- `DERIVED` should be skipped unless explicit custom policy exists.
- `JOINED` should not generate direct writes to local table updates.

## Diagnostics

- Error when write views expose non-writable field kinds without explicit strategy.
- Warning when mapping logic is generated but likely dead in write-only contexts.

## Open points

- Should field-kind defaults be strict or permissive?
- How should custom derived persistence hooks be modeled?
