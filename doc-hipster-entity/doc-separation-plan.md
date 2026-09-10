# Documentation Separation Plan

## Problem

~85% of current documentation targets library developers (architecture decisions, implementation internals, naming conventions, JMH benchmarks). End-user documentation (`doc/user/`) is essentially a placeholder. A new user browsing the docs is immediately confronted with internal design rationale, ordinal dispatch strategies, and ADR lifecycle rules — none of which help them evaluate or adopt the library.

The goal: **make benefits easy to spot on first read, and let users explore deeper when hooked on the concept.**

## Guiding Principles

| Principle                                          | Rationale                                                                                               |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| **Benefits first, mechanics later**                | A user should understand *what they gain* before learning *how it works internally*                     |
| **Progressive disclosure**                         | Layer 1: pitch + quick start. Layer 2: patterns & recipes. Layer 3: internals for contributors          |
| **Separate concerns by audience**                  | User docs answer "how do I use this?"; architecture docs answer "why is it built this way?"             |
| **No duplication, only linking**                   | Internal docs already exist and are well-structured — user docs should reference them, not copy content |
| **Conventions are discoverable, not prerequisite** | Naming rules, ADR guides, and enum casing rationale should be reachable but not required reading        |

---

## Current State

### What exists

| Location                           | Content                                                                                                                                | Audience      |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `README.md` (root)                 | Project vision, tooling CLI, JMH results                                                                                               | Lib-dev heavy |
| `doc/architecture/`                | 330-line entity design guide, naming conventions, materialization levels, field-type divergence, enumset internals, ADR guide, 17 ADRs | Lib-dev       |
| `doc/brainstorm/`                  | 13 exploratory proposals, annotation typing options, metadata generator design                                                         | Lib-dev       |
| `doc/roadmap/`                     | Milestones, decision traceability, phase tracker                                                                                       | Maintainers   |
| `doc/user/`                        | Placeholder README + field-lookup-guide (actually implementation-focused)                                                              | Placeholder   |
| `hipster-entity-api/README.md`     | API contracts, proxy behavior, interface patterns                                                                                      | Mixed         |
| `hipster-entity-jackson/README.md` | 400 lines of JMH benchmarks + root-cause perf analysis                                                                                 | Lib-dev       |
| `hipster-entity-example/doc/`      | Payment method polymorphic example                                                                                                     | Mixed         |

### Key gaps

1. **No getting-started guide** — nothing explains how to add the dependency and define a first entity
2. **No "why use this" page** — benefits are scattered across architecture docs and the root README
3. **Materialization levels** are the most user-relevant architecture doc but are buried alongside low-level concerns
4. **field-lookup-guide.md** is in `doc/user/` but targets deserializer authors, not library consumers
5. **Root README** jumps straight to JMH benchmarks — useful for credibility but not for onboarding
6. **Module READMEs** mix API usage patterns with internal implementation notes

---

## Proposed Structure

### Three-audience model

```
doc/
├── user/                          ← Library consumers (NEW — primary focus of this plan)
│   ├── README.md                  ← User docs landing page + reading order
│   ├── why-hipster-entity.md      ← Benefits & value proposition
│   ├── getting-started.md         ← Installation, first entity, first view
│   ├── core-concepts.md           ← Interface-first model, views, field enums (user perspective)
│   ├── materialization-guide.md   ← Adoption levels: start minimal, grow as needed
│   ├── patterns/
│   │   ├── crud-views.md          ← Summary/Details/Update view layering
│   │   ├── polymorphic-views.md   ← Payment method example walkthrough
│   │   ├── jackson-setup.md       ← JSON serialization quick setup
│   │   └── builder-usage.md       ← Using builders and change tracking
│   └── faq.md                     ← Common questions, troubleshooting
│
├── architecture/                  ← Library developers & contributors (EXISTS — minor reorg)
│   ├── ... (keep as-is)
│
├── brainstorm/                    ← Design exploration (EXISTS — no change)
│   ├── ... (keep as-is)
│
└── roadmap/                       ← Delivery tracking (EXISTS — no change)
    ├── ... (keep as-is)
```

### Root README rewrite plan

The root `README.md` should be restructured to serve as the front door:

```
Current flow:   Vision → Implementation direction → JMH numbers
Proposed flow:  One-liner pitch → Key benefits list → Quick example
                → Link to getting-started → Link to architecture (for contributors)
                → Performance highlights (brief, linked to full data)
```

---

## Detailed Plan by Deliverable

### 1. `doc/user/why-hipster-entity.md` — Value Proposition

**Purpose:** Answer "why should I care?" in under 2 minutes of reading.

**Content outline:**
- Problem: Java entity boilerplate (DTOs, builders, mappers) is repetitive and error-prone
- Solution: Define entities as interfaces → get metadata, views, serialization, and change tracking
- Key benefits (bullet list):
  - Start from a record or interface — no framework lock-in
  - Generated field enums give compile-time schema
  - Views are projections of one entity — define once, slice many ways
  - Incremental adoption: start with just metadata, add proxies/builders when needed
  - Jackson integration with measured performance (link to benchmarks)
- A single before/after code example (plain Java record vs hipster-entity interface + generated metadata)
- "Where to go next" links

**Source material:** Extract and rewrite from `doc/architecture/README.md` (opening sections), root `README.md`, and `materialization-levels.md` (MINIMAL/RECORD levels).

### 2. `doc/user/getting-started.md` — First 15 Minutes

**Purpose:** Get a working entity with generated metadata from zero.

**Content outline:**
- Maven dependency snippet (hipster-entity-api)
- Define a simple entity interface with `@View`
- Run the tooling to generate metadata (`bun ./scripts/run-tooling.js` — simplified)
- Inspect the generated `Person_` enum
- Use `ViewMeta` to access field metadata at runtime
- Optional: add Jackson module, deserialize JSON into a view
- "What's next" links to core-concepts and patterns

**Source material:** `README.md` CLI section, `materialization-levels.md` MINIMAL section, example module code.

### 3. `doc/user/core-concepts.md` — Mental Model for Users

**Purpose:** Build intuition for the interface-first model without diving into implementation details.

**Content outline:**
- Entities are interfaces (not classes, not annotations)
- Views are slices of an entity (Summary, Details, Update)
- Field enums (`Person_`) are the compile-time schema
- `EntityBase` vs `Identifiable` — when you need identity
- Field sources: COLUMN / DERIVED / JOINED (user-level explanation: "where does this data come from?")
- Materialization levels as an adoption ladder (overview only, link to full guide)
- Explicit non-goals for this page: no ADR references, no proxy internals, no naming convention rules

**Source material:** `doc/architecture/README.md` (rewritten for users), `hipster-entity-api/README.md` (pattern sections), DEC-001 and DEC-017 (conclusions only).

### 4. `doc/user/materialization-guide.md` — Adoption Levels

**Purpose:** Show users they can start simple and add power incrementally.

**Content outline:**
- Level 0: Just an interface + `@View` → get `Person_` enum and `ViewMeta`
- Level 1: Interface + record → immutable data class with full metadata
- Level 2: Add write interface → proxy-backed updates
- Level 3: Add builder → fluent construction with change tracking
- Each level: code example, what you gain, when you need it
- Decision table: "If you need X, go to level Y"

**Source material:** `doc/architecture/materialization-levels.md` — restructured from implementation perspective to user perspective. Current doc explains *what the system generates*; new doc explains *what the user gets*.

### 5. `doc/user/patterns/` — Recipe-Style Guides

Each pattern doc follows a consistent template:

```
## Goal
## When to use
## Code example
## What happens under the hood (brief, links to architecture)
## See also
```

| Pattern file           | Source material                                                       |
| ---------------------- | --------------------------------------------------------------------- |
| `crud-views.md`        | `architecture/README.md` CRUD layering section                        |
| `polymorphic-views.md` | `hipster-entity-example/doc/README.md` payment method example         |
| `jackson-setup.md`     | `hipster-entity-jackson/README.md` integration parts (not benchmarks) |
| `builder-usage.md`     | DEC-008, DEC-012 conclusions (user-facing subset)                     |

### 6. `doc/user/faq.md` — Quick Answers

**Content outline (initial questions):**
- Do I have to use all the modules?
- Can I start with a record and upgrade later?
- What's the difference between `EntityBase` and `Identifiable`?
- Why do field enum constants use lowerCamelCase?
- How does change tracking work? (brief answer + link to DEC-012)
- What performance can I expect? (brief + link to JMH data)

### 7. Root `README.md` — Restructure

| Section                  | Change                                            |
| ------------------------ | ------------------------------------------------- |
| Opening paragraph        | Keep, make slightly more benefit-oriented         |
| Implementation direction | Move to "For Contributors" section at bottom      |
| Documentation links      | Reorder: User docs first, then architecture       |
| Tooling runner           | Keep, but move below "Getting Started" link       |
| JMH benchmark summary    | Condense to 2-line summary + link to full results |

### 8. Relocate `field-lookup-guide.md`

Move `doc/user/field-lookup-guide.md` → `doc/architecture/field-lookup-guide.md`

This doc targets deserializer/serializer implementers, not end users. It belongs with the other implementation guides.

---

## Content Migration Rules

| Rule                    | Detail                                                                                                                      |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| **Don't duplicate**     | User docs summarize concepts and link to architecture docs for full detail                                                  |
| **Don't delete**        | All existing architecture/brainstorm docs stay in place                                                                     |
| **Rewrite, don't copy** | When extracting from architecture docs, rewrite for a user audience (remove ADR references, explain benefits not tradeoffs) |
| **Link forward**        | Each user doc should have a "Deeper dive" section linking to relevant architecture docs for curious users                   |
| **Link backward**       | Architecture docs can add a "User-facing guide" cross-reference where applicable                                            |

---

## Convention Visibility Strategy

Some conventions are important but should not be prerequisite reading:

| Convention                | Current location                                 | User visibility                                                                                |
| ------------------------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| Naming conventions        | `architecture/naming-conventions.md`             | Mentioned in FAQ ("why lowerCamelCase enums?") with link                                       |
| ADR lifecycle             | `architecture/ADR-GUIDE.md`                      | Not referenced in user docs at all                                                             |
| Field-type divergence     | `architecture/field-type-divergence.md`          | Brief mention in core-concepts ("views can have different types for the same field") with link |
| EnumSet internals         | `architecture/enumset-implementation-and-jmh.md` | Not referenced in user docs; linked from builder-usage pattern if user wants perf details      |
| Materialization internals | `architecture/materialization-levels.md`         | User-facing rewrite in `materialization-guide.md`; original stays for contributors             |

---

## Suggested Execution Order

| #   | Task                                             | Effort | Dependencies                                       |
| --- | ------------------------------------------------ | ------ | -------------------------------------------------- |
| 1   | Relocate `field-lookup-guide.md` to architecture | Small  | None                                               |
| 2   | Write `why-hipster-entity.md`                    | Medium | None                                               |
| 3   | Write `getting-started.md`                       | Medium | Verify example module compiles cleanly             |
| 4   | Write `core-concepts.md`                         | Medium | `why-hipster-entity.md` for consistent terminology |
| 5   | Write `materialization-guide.md`                 | Medium | `core-concepts.md` for level references            |
| 6   | Write `patterns/crud-views.md`                   | Small  | `core-concepts.md`                                 |
| 7   | Write `patterns/polymorphic-views.md`            | Small  | Example module docs                                |
| 8   | Write `patterns/jackson-setup.md`                | Small  | `getting-started.md`                               |
| 9   | Write `patterns/builder-usage.md`                | Small  | `materialization-guide.md`                         |
| 10  | Write `faq.md`                                   | Small  | All user docs drafted                              |
| 11  | Update `doc/user/README.md` with reading order   | Small  | All user docs exist                                |
| 12  | Restructure root `README.md`                     | Small  | User docs landing page ready                       |
| 13  | Add cross-reference links in architecture docs   | Small  | User docs finalized                                |

---

## Expected Outcome

**Before:** A new user lands on the README, sees JMH benchmarks and "proxy-backed builders," and has to read architecture docs to understand what the library does for them.

**After:** A new user reads a benefits page, follows a getting-started guide, understands the adoption ladder, and finds recipe-style patterns — all without needing to know about ADRs, ordinal dispatch, or concrete EnumSet specialization. Those details remain accessible one click away for users who want to go deeper.
