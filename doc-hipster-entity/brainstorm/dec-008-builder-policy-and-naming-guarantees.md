# DEC-008 Brainstorm: Builder policy and naming guarantees

This document expands DEC-008 and defines expected builder behavior for projection-compatible materialization.

## Main requirement

Generated builders must support constructor or factory ingestion from an interface-compatible source view.

Example intent:

```java
new PersonSummaryBuilder(sourceView)
```

## Optional behavior

- Update-via-view methods such as `mergeFrom(...)` may be generated.
- Merge behavior must define overwrite and null policy if present.

## Naming guarantees

- Accessor names remain record-style.
- Builder mutator names map directly to logical property names.
- Generated method ordering and naming must be deterministic across runs.

## Why this matters

- Constructor-first ingestion is ergonomic and fast for full materialization.
- Naming consistency reduces adapter glue and cognitive overhead.
- Deterministic generation improves review quality and trust.

## Risks

- If builder logic includes heavy validation, constructor copy path can become slower than expected.

## Mitigation

- Keep constructor copy path lightweight.
- Reserve heavier checks for explicit validation stages where possible.
