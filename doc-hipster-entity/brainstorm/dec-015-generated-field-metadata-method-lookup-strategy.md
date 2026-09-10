# DEC-015 Brainstorm: Generated field metadata method lookup strategy

This document explores method-name to field resolution for generic array-backed view proxies.
The goal is to keep dispatch predictable and fast without per-view handwritten switch logic.

## 1. Problem statement

Generic proxy invocation handlers receive method names at runtime and must map each call to a field ordinal.
The mapping is on the accessor hot path, so overhead compounds quickly.

Runtime initialization work (sorting at class load) is avoidable because metadata generators already know all field names.

## 2. Goals

- Move lookup preparation to generation time, not runtime class initialization.
- Keep generated code simple and deterministic.
- Preserve compatibility with record-style accessor naming (`id()`, `firstName()`).
- Provide an upgrade path for tighter lookup when field counts become large.

## 3. Non-goals

- Build a global runtime registry for all entities.
- Introduce reflection-heavy or mutable lookup structures.
- Optimize `Object` methods (`toString`, `hashCode`, `equals`) in this decision.

## 4. Baseline strategy

Generate direct `switch(methodName)` mapping code that returns target field enum constant.

The switch is generated at build time and contains one case per accessor name.
No runtime sorting and no per-view handwritten proxy mapping are needed.

### Why this baseline

- No runtime sort at class load.
- Compact runtime data footprint (no extra lookup arrays required).
- Source-visible mapping is easy to inspect and debug.
- Often highly optimized by JDK string-switch bytecode lowering.
- Works well for typical view field counts.

## 5. Exploration: first-character bucket prefilter

### Idea

Add generated side metadata:

- sorted unique first characters: `char[] firstChars`
- aligned range starts: `int[] rangeStart`
- aligned range ends (exclusive): `int[] rangeEnd`

Algorithm:

1. Read `c = methodName.charAt(0)`.
2. Binary search `firstChars` to find bucket index.
3. If not found, return no match.
4. Search only `sortedMethodNames[rangeStart[i]..rangeEnd[i])`.
5. Confirm full string match and return aligned field.

### Expected behavior

- If names are spread across many starting letters, range width often becomes 1-2 entries.
- If many names share the same first letter (for example common prefixes), benefit shrinks.
- Additional arrays improve narrowing but increase generated footprint and branch complexity.

### Cost model

- Baseline: one binary search over $n$ strings.
- Prefilter path: one binary search over unique first chars $u$ plus one bounded search over bucket size $k$.
- Effective complexity: $O(\log u + \log k)$ where $u \le n$.

Practical impact depends on distribution, string lengths, and branch behavior on the target JDK/CPU.

## 6. Decision (brainstorm recommendation)

- Adopt generated string switch as the default method mapping strategy.
- Do not perform sorting at runtime class initialization.
- Keep sorted-array + binary search as fallback/reference strategy.
- Treat first-character bucketing as an optional generated specialization, enabled only when benchmark evidence shows stable benefit for target workloads.

## 7. Final decision

Generated string switch is the only supported runtime lookup implementation for field-name resolution in array-backed view field enums.

- Each field enum provides a `forMethodName(String)` method implemented with a `switch(methodName)` block.
- Runtime sorting, fallback binary search, and alternative strategies are not required, to keep generated code minimal and predictable.
- Optional experimental paths such as first-char prefilter or perfect hash may be explored in separate experimental branches, but they are not part of baseline generator behavior.

## 8. Field metadata iteration alternatives

To keep view serialization/deserialization as lean as possible, we evaluated alternatives for exposing field metadata in `ViewMeta`:

1. enum-backed access (chosen):
   - `fieldType().getEnumConstants()` + `fieldNameAt(i)/fieldTypeAt(i)`.
   - no additional copy or cache object per call.
   - best for JIT and zero-alloc hot loops.
2. explicit arrays in meta:
   - `String[] fieldNames()` and `Class<?>[] fieldTypes()` (cached in metadata).
   - low overhead if stored immutably; but all callers may copy for safety.
   - adds extra fields in metadata object.
3. callback iterator:
   - `forEachField((ordinal, f)->...)` with lambda.
   - clean, but incurs lambda overhead at call site and may hinder inlining.

## 9. Practical choice

- Implemented `ViewMeta.fieldCount()`, `fieldNameAt(int)`, `fieldTypeAt(int)` backed by `fieldType().getEnumConstants()`.
- Used in `EntityJacksonMapper` loops with index-based access.
- Keeps generated and runtime code minimal and consistent with array-backed ordinal semantics.

## 8. Benchmark plan

Measure generated string switch plus experimental alternatives in a dedicated benchmark class (see section 10). - This is to ensure switch remains optimal as workload and JDKs evolve.

1. Direct generated string switch (baseline)
2. First-char bucket prefilter + bounded binary search (experimental)
3. Sorted string array + binary search (experimental)
4. Two-char prefilter + bounded binary search (experimental)
5. Perfect-hash variant (experimental)


Measure generated lookup variants across multiple field counts and naming distributions:

1. Sorted string array + binary search (baseline)
2. Direct generated string switch (baseline candidate)
3. First-char bucket prefilter + bounded binary search
4. Two-char prefilter + bounded binary search
5. Perfect-hash variant (if implemented)

Dataset dimensions:

- field counts: 8, 16, 32, 64, 128
- distributions: uniform first letters vs clustered prefixes (`firstName`, `familyName`, `fullName`, ...)
- lookup mix: 90% hit / 10% miss and 50% hit / 50% miss

Decision gate for enabling bucketing by default should require consistent gain with low variance in both hit and miss paths.

Also track:

- generated bytecode size deltas
- class init time deltas
- branch-miss and allocation counters when available

## 10. Implemented benchmark

JMH benchmark implementation:

- `hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/MethodLookupJmhBenchmark.java`

Covered variants:

1. Generated string switch lookup
2. Sorted array + `Arrays.binarySearch`
3. First-char prefilter (`char[]` ranges) + bounded binary search

Covered paths:

- hit lookup and miss lookup
- small field set (6 names)
- larger field set (24 names)

## 11. How to run

From repository root:

```bash
bun run scripts/run-jmh.js --include .*MethodLookupJmhBenchmark.* --forks 1 --warmup-iterations 4 --measurement-iterations 6 --warmup-time 1s --measurement-time 1s
```

Result file:

- `target/jmh/results.json`

For higher-confidence runs (slower but more stable), use stronger JIT stabilization:

```bash
bun run scripts/run-jmh.js --include .*MethodLookupJmhBenchmark.* --forks 3 --warmup-iterations 6 --measurement-iterations 8 --warmup-time 2s --measurement-time 2s
```

## 12. Results (2026-04-01, JDK 21, Windows)

Run profile used for numbers below:

- forks: 1
- warmup: 4 x 1s
- measurement: 6 x 1s
- mode: throughput (`ops/ms`)

### Raw scores

| Benchmark            | Score (ops/ms) | Error (99.9%) |
| -------------------- | -------------- | ------------- |
| `switchSmallHit`     | 1,363,587.957  | ±329,733.735  |
| `switchSmallMiss`    | 1,258,310.174  | ±207,365.485  |
| `binarySmallHit`     | 434,424.359    | ±107,436.822  |
| `binarySmallMiss`    | 93,235.227     | ±16,598.445   |
| `firstCharSmallHit`  | 247,209.295    | ±21,302.821   |
| `firstCharSmallMiss` | 104,475.892    | ±16,861.985   |
| `switchLargeHit`     | 379,350.900    | ±95,529.970   |
| `switchLargeMiss`    | 419,465.255    | ±82,310.300   |
| `binaryLargeHit`     | 72,081.197     | ±8,749.759    |
| `binaryLargeMiss`    | 75,395.573     | ±14,906.941   |
| `firstCharLargeHit`  | 88,462.597     | ±14,215.996   |
| `firstCharLargeMiss` | 61,571.837     | ±7,801.410    |

### Relative speedup vs sorted-array binary search

| Scenario   | Switch vs Binary | First-char vs Binary |
| ---------- | ---------------- | -------------------- |
| Small hit  | `3.19x`          | `0.54x`              |
| Small miss | `11.27x`         | `1.20x`              |
| Large hit  | `5.32x`          | `1.12x`              |
| Large miss | `5.26x`          | `0.79x`              |
- First-char prefilter is mixed: it helps some cases but loses in others, confirming it should remain optional and benchmark-gated.
- These numbers were collected with a short single-fork profile; use the higher-confidence command above before changing global defaults for all generated code.

## 13. Related decisions

- [DEC-004](dec-004-generated-metadata-over-reflection.md): generated metadata as primary runtime input
- [DEC-009](dec-009-source-visible-generation-strategy.md): generated source as inspectable contract
- [DEC-010](dec-010-proxy-backed-view-bridge.md): proxy dispatch and method mapping context
- [DEC-014](dec-014-enumset-concrete-dispatch-strategy.md): hot-path dispatch specialization precedent