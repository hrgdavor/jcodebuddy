# Open Questions Index

This index centralizes unresolved questions from brainstorm documents so early-phase decisions can be prioritized deliberately.

## Immediate (early-phase) decisions

These questions block architecture promotion or influence first implementation shape.

1. Proxy bridge baseline behavior (from DEC-010)
- Should default interface methods be supported in v1 of proxy invocation?
- Is strict mapping mode always default, or configurable?
- Should converter application happen inside proxy handler or in an external layer?

2. Builder contract shape (from DEC-011)
- Should merge methods be globally standardized or opt-in per view?
- Do we split builder contracts between read materialization and patch/write intent?
- Does builder interface expose metadata introspection, or keep that outside the contract?

3. Update tracking semantics (from DEC-012)
- Is default tracking model touched-only or dirty-by-compare?
- Is explicit null assignment always legal in patch mode?
- What is the canonical change-set surface: ordinals, names, or enum entries?

4. Optional per-view runtime selection module (from DEC-013)
- Is the optional factory-module boundary public API now or internal SPI first?
- How are per-view overrides declared and resolved with deterministic precedence?
- Is generated-load failure fallback warning by default, strict fail-fast, or policy-driven?

## Near-term (post-baseline) decisions

These do not block the first pass but should be resolved before scaling implementation breadth.

1. Source-visible generation workflow details (from DEC-009)
- Should skip-on-syntax-error default to on?
- What is the canonical generated block marker format?
- Which in-file augmentation placement policies are supported initially?

2. Annotation typing expansion (from typed-annotation-exposure)
- Which typed annotation strategy becomes baseline for v1?
- How are annotation-to-meta mappings declared and versioned?
- What fallback exists for unknown/custom annotation kinds?

3. Cross-view divergence validation strictness (from field-type-divergence)
- Which divergence cases are ERROR vs WARNING by default?
- Are scalar-vs-collection same-name mismatches always hard-fail?
- What is the default policy for primitive-vs-boxed divergence diagnostics?

## Suggested decision cadence

1. Resolve all Immediate questions before promoting DEC-010 to DEC-013 from brainstorm to architecture, while keeping DEC-013 explicitly optional rather than a prerequisite for the metadata baseline.
2. Resolve Near-term questions during Trial phase of DEC-009 to DEC-013.
3. Revisit unresolved Near-term items before marking related decisions Accepted.
