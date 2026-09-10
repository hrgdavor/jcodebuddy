# DEC-002 Brainstorm: Documentation structure separation

This document captures the rationale behind separating brainstorming, architecture, and roadmap documentation.

## Problem framing

When exploratory notes, accepted architecture, and implementation progress are mixed in one flat location, teams lose context and decision status becomes unclear.

## Core proposal

Split documentation under `doc/` into:
- `brainstorm` for exploration and alternatives
- `architecture` for agreed direction and ADRs
- `roadmap` for execution status and sequencing

## Why this was attractive

- Faster onboarding: readers know where to find intent vs status.
- Better decision hygiene: proposed vs accepted is explicit.
- Cleaner change history and reviews for documentation updates.

## Alternatives considered

1. Single monolithic documentation folder with section headers.
2. ADR-only approach without separate brainstorm files.
3. Issue tracker only for planning and no structured docs split.

## Tradeoffs

Pros:
- Reduces ambiguity across design lifecycle stages.
- Encourages promotion flow: idea -> decision -> implementation.
- Scales better as the number of topics grows.

Cons:
- Requires cross-link maintenance.
- Some duplication risk between architecture and roadmap.
- Needs lightweight conventions to avoid fragmentation.

## Guardrails

- Every DEC entry should have related brainstorm/roadmap links where relevant.
- TOCs should be updated whenever new doc artifacts are added.
- Move stale brainstorm decisions into superseded notes when direction changes.
