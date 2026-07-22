# ADR Guide For Developers

This guide explains when to create or update an Architecture Decision Record (ADR) in this repository and how ADRs relate to the other documentation folders.

## What an ADR is

An ADR is a short record of an important technical decision.

Each ADR should answer:
- What problem or pressure existed?
- What decision was made?
- Why was this option chosen over alternatives?
- What consequences follow from the decision?

The goal is not paperwork. The goal is to preserve intent so later work does not have to reverse-engineer project direction from code or old discussions.

## How ADRs fit into this repository

- `doc/brainstorm` contains ideas, proposals, and design exploration.
- `doc/architecture` contains agreed or actively reviewed architectural direction.
- `doc/roadmap` tracks implementation progress, milestones, and changes in direction.

Use the folders this way:
1. Start in brainstorming when exploring an idea.
2. Promote the idea into an ADR when it becomes a serious candidate or accepted direction.
3. Track implementation work in the roadmap.

## When to write an ADR

Write or update an ADR when a decision:
- changes the entity model or view model
- changes generated code shape or metadata structure
- introduces a stable runtime contract or extension point
- affects performance strategy in a structural way
- changes module responsibilities or package boundaries
- replaces a previously accepted approach

Do not create ADRs for small editorial, naming, or formatting choices.

## ADR statuses used here

- `Proposed`: serious candidate under discussion
- `Trial`: direction is being exercised in design or implementation but is not yet stable
- `Accepted`: current intended direction
- `Superseded`: replaced by a later ADR
- `Rejected`: considered and intentionally not adopted

Use `Superseded` instead of deleting old decisions. Retaining history is one of the main reasons ADRs are useful.

## Normative language

Use RFC-style wording for implementation strength:
- `MUST`: non-negotiable invariant or requirement
- `SHOULD`: recommended default, deviation needs justification
- `MAY`: optional behavior or extension point

This helps keep accepted decisions precise even while the specification is still evolving.

## Decision lifecycle

Recommended lifecycle:
1. `Proposed` when the idea is documented and under active consideration.
2. `Trial` when implementation or design exploration has started and feedback is being gathered.
3. `Accepted` when the direction becomes the repository default.
4. `Superseded` or `Rejected` when the decision is replaced or intentionally abandoned.

Accepted decisions should ideally have acceptance criteria and roadmap traceability.

## Recommended workflow

1. Write the design idea in `doc/brainstorm`.
2. Create or update an ADR in `doc/architecture/DECISIONS.md`.
3. Link the ADR back to the relevant brainstorm and roadmap documents.
4. When the decision is implemented, update roadmap status rather than rewriting the ADR.
5. If the direction changes later, mark the old ADR as `Superseded` and add a new one.

## How detailed an ADR should be

Keep ADRs short and decisive.

Good ADRs are:
- explicit about tradeoffs
- specific about scope
- linked to related docs
- stable enough to guide implementation

Weak ADRs are vague statements like "we prefer X" without context, alternatives, or consequences.

## Suggested ADR template

```md
## DEC-XXX: Short title

- Status: Proposed | Trial | Accepted | Superseded | Rejected
- Date: YYYY-MM-DD
- Owners: team or person
- Related docs: links to brainstorm / roadmap / code
- Supersedes: DEC-... | -
- Superseded by: DEC-... | -

### Context
Why the decision is needed.

### Decision
What is being decided.

### Alternatives considered
- Option A
- Option B
- Option C

### Consequences
- Positive effects
- Negative effects
- Follow-up work

### Acceptance criteria
- Observable condition 1
- Observable condition 2
```

## Repository-specific ADR candidates

Common ADR topics for this project include:
- interface-first entity contracts
- generated metadata instead of runtime reflection
- field source semantics (`COLUMN`, `DERIVED`, `JOINED`)
- build-time converter validation for divergent field types
- projection-oriented read paths and direct JSON streaming
- module responsibility boundaries across `api`, `core`, `tooling`, and examples

## Rule of thumb

If a future contributor could reasonably ask "was this deliberate, and what were the alternatives?", that is a strong sign the topic deserves an ADR.
