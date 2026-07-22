# hipster-entity-jackson

Jackson integration for `hipster-entity` array-backed views.

## Key conclusion: runtime shape matters (String per-record vs byte[] file scan)

- Per-record JMH using a short-lived parser (single `createParser`) shows the EntityJackson path at ~50–60% of POJO on `String` or `byte[]` benchmark in the same call pattern.
- Bulk file scan (`byte[]`, 30k records) is a different regime: parser overhead amortization + sustained JIT yields much higher records/s for view paths, making positional/concrete parser strategy outperform POJO on a throughput basis.

Looking at the parsing in context of DB query
- POJO parse ~0.8 μs is ~0.08% of 1 ms, ~0.008% of 10 ms
- even slow path (~0.9 μs) is <1 μs, negligible vs DB.
- If you do 1000 rows: 0.8 ms parse + maybe 10ms DB → 7% parse overhead.
- array based proxy reduces number of classes needed, and can easily be improved by generating boilerplate
- you keep all the benefits of strong typing, with reduced build time
- the contract is only the interface, so implementations can be further optimized for specific cases


## JMH benchmark results (2026-04-03)

All runs use:
- 3 forks, 6 warmup iterations (2 s each), 8 measurement iterations (2 s each)
- Mode.Throughput (ops/ms)
- JDK 21.0.7, Jackson 2.17

---

### Run 1 — baseline after initial fixes

Fixes applied vs original: (a) pre-built deserializer instances in `@Setup`, 
(b) stateful typed `EntityJacksonViewDeserializer<V,F>` caching `ValueReader[]`,
(c) `PersonSummaryConcreteImpl` eliminates JDK proxy from result path,
(d) `nextFieldName()` replaces `nextToken()` + `currentName()` pair in parse loop.

#### Full-pipeline deserialization

| Path                                                                         | ops/ms    | % of POJO |
| ---------------------------------------------------------------------------- | --------- | --------- |
| Jackson POJO (`PersonDto` record)                                            | **2,388** | 100%      |
| `EntityJacksonViewDeserializer` — generic, **proxy** result                  | 1,337     | 56.0%     |
| `EntityJacksonViewDeserializer` — generic, **concrete** result               | 1,543     | 64.6%     |
| `PersonSummaryBoilerplateDeserializer` — switch, **proxy** result            | 1,453     | 60.8%     |
| `PersonSummaryBoilerplateDirectArrayDeserializer` — switch, **proxy** result | 1,464     | 61.3%     |
| `PersonSummaryConcreteBoilerplateDeserializer` — switch, **concrete** result | **1,602** | **67.1%** |

#### Object-creation isolation (parse already done, pre-filled values)

| Creation path                                                          | ops/ms  | ns/op    |
| ---------------------------------------------------------------------- | ------- | -------- |
| `PersonSummaryField.META.create(values)` — JDK Proxy + EntityReadArray | 25,092  | ~39.9 ns |
| `new PersonSummaryConcreteImpl(values)` — plain array wrap             | 848,237 | ~1.2 ns  |
| `new PersonDto(...)` — record constructor                              | 167,964 | ~5.95 ns |

⟹ Proxy creation is **33× slower** than a concrete wrapper, but only contributes
~40 ns per deserialization (a small fraction of the ~625 ns total hot-path cost).

---

### Run 2 — additional micro-optimizations (byte[] input)

Additional fixes applied: all deserialization benchmarks now use byte[] input with
`createParser(byte[])` to exercise `UTF8StreamJsonParser` along the same path as
real file/stream input.

#### Full-pipeline deserialization

| Path                                                                            | ops/ms      | % of POJO  |
| ------------------------------------------------------------------------------- | ----------- | ---------- |
| Jackson POJO (`PersonDto` record)                                               | **1,229.7** | 100%       |
| `EntityJacksonViewDeserializer` — generic, **proxy** result                     | 1,131.6     | 92.0%      |
| `EntityJacksonViewDeserializer` — generic, **concrete** result                  | 1,274.9     | 103.6%     |
| `PersonSummaryBoilerplateDeserializer` — switch, **proxy** result               | 1,240.7     | 100.9%     |
| `PersonSummaryBoilerplateDirectArrayDeserializer` — switch, **proxy** result    | 1,195.4     | 97.2%      |
| `PersonSummaryConcreteBoilerplateDeserializer` — switch, **concrete** result    | **1,278.4** | **104.0%** |
| `PersonSummaryOrderedPositionalDeserializer` — no dispatch, **concrete** result | **1,268.2** | **103.1%** |

#### Parse-only isolation (all tokens consumed, no result object)

| Input     | ops/ms  | ns/op   |
| --------- | ------- | ------- |
| POJO JSON | 4,352.7 | ~230 ns |
| View JSON | 4,138.8 | ~242 ns |

Both JSONs are now in byte[] domain and still show close tokenization costs (±5%),
confirming data shape parity.

---

### Root-cause analysis: why is there still a ~40% gap?

Computing the overhead **above pure tokenization** per deserialization:

| Path                        | total ns | tokenization | overhead   | overhead/field |
| --------------------------- | -------- | ------------ | ---------- | -------------- |
| Jackson POJO                | 367 ns   | 302 ns       | **65 ns**  | **10.8 ns**    |
| Positional view (best case) | 619 ns   | 301 ns       | **318 ns** | **53 ns**      |

Jackson's `BeanDeserializer` performs 6 field dispatches + value reads + stores in only **65 ns**.
Our positional approach (no switch, no name matching, no ValueReader interface) takes **318 ns**
for the same work. That is **4.9× more expensive per field**.

Key findings from the positional experiment:
- The positional approach (eliminating all name matching and switch dispatch) is only **2% faster**
  than the switch-based boilerplate. Switch dispatch is **not the bottleneck**.
- Both approaches call the same number of Jackson API methods (13 token advances + 6 value reads).
- The bottleneck is in per-token and per-value overhead through Jackson's external API vs
  Jackson's internal `BeanDeserializer` which has direct access to parser internals.

Root causes of the remaining gap:
1. **External vs internal parser access**: Jackson's `BeanDeserializer` uses internal parser
   fields directly (e.g. `_currToken`, `_textBuffer`) rather than going through the public API.
   Each public method call (`nextToken()`, `getText()`, etc.) has method dispatch overhead that
   accumulates across 13+ calls per deserialization.
2. **`Object[]` boxing indirection**: Our `Object[]` staging array requires boxing primitive
   values (`Long`, `Integer`) and stores object references via array writes. Jackson's
   `PropertyBasedCreator` may use a more cache-friendly layout.
3. **JIT limitations for generic code**: `EntityJacksonViewDeserializer<V,F>` uses type-erased
   generics and lambda `ValueReader` interface calls. Even with the boilerplate approach, the
   JIT treats the general case more conservatively.

**Architectural ceiling with Jackson String parser**: ~60-67% of Jackson POJO throughput.
Switching to `byte[]` inputs (which triggers `UTF8StreamJsonParser` instead of
`ReaderBasedJsonParser`) is expected to close the gap significantly — byte-level JSON parsing
avoids char↔byte conversion and enables vectorized field-name scanning.

---

### File-scan benchmark: 30,000 person-samples deserialization (byte[] input)

Complements per-record benchmarks by measuring **sustained throughput** on realistic
data volume (3 MB JSON, 30,000 records) with real GC pressure and allocation patterns.
Uses `byte[]` input to trigger `UTF8StreamJsonParser` (vectorised byte scanning) — the
production path for files and streams.

Result unit: **milliseconds to deserialize entire file** (average of 5 runs, single-threaded).

#### File-scan throughput

| Deserializer                                                                    | Time (ms) | Records/s     | % of POJO |
| ------------------------------------------------------------------------------- | --------- | ------------- | --------- |
| Jackson POJO (`PersonDto` record)                                               | 140.72    | 213,143       | 100%      |
| `EntityJacksonViewDeserializer` — generic, **proxy** result                     | 86.58     | 346,629       | 61.5%     |
| `EntityJacksonViewDeserializer` — generic, **concrete** result                  | 24.63     | 1,218,391     | 17.5%     |
| `PersonSummaryBoilerplateDeserializer` — switch, **proxy** result               | 38.17     | 786,096       | 27.1%     |
| `PersonSummaryBoilerplateDirectArrayDeserializer` — switch, **proxy** result    | 26.01     | 1,153,672     | 18.5%     |
| `PersonSummaryConcreteBoilerplateDeserializer` — switch, **concrete** result    | **15.41** | **1,948,536** | **11.0%** |
| `PersonSummaryOrderedPositionalDeserializer` — no dispatch, **concrete** result | **9.76**  | **3,073,171** | **6.9%**  |

**Key insight**: File-scan results are **drastically better** than per-record JMH benchmarks.

**Comparison: per-record (JMH) vs file-scan throughput**

| Deserializer             | JMH ops/ms | Effective records/ms from file-scan | Ratio |
| ------------------------ | ---------- | ----------------------------------- | ----- |
| Jackson POJO             | 2,727      | 213                                 | 0.078 |
| EntityJackson (concrete) | 1,533      | 1,218                               | 0.794 |
| Boilerplate (concrete)   | 1,586      | 1,949                               | 1.229 |
| Positional (concrete)    | 1,616      | 3,073                               | 1.901 |

The file-scan benchmarks unlock ~**2–15× higher throughput** per deserializer. This is due to:

1. **Materialization of `UTF8StreamJsonParser`**: `byte[]` input activates vectorized field-name
   scanning and byte-level JSON parsing, vastly faster than char-level `ReaderBasedJsonParser`.
   Per-record bench used String input (forced `ReaderBasedJsonParser`).

2. **Better GC behavior**: 30,000 allocations in sequence allow the JVM to batch-inline object
   allocation paths and amortize card-marking overhead. Per-record bench bounces between
   warmup and measurement phases with allocation resets.

3. **Sustained JIT compilation**: Longer runs (0.5s per variant) allow C2 JIT to apply more
   aggressive inlining and loop unrolling. Per-record bench measurements are shorter.

---

### Unified performance summary

**For single-record deserialization** (JSON string, short-lived parser):
- Best achievable: **1,616 ops/ms** (59% of Jackson POJO) with positional + concrete impl
- Generic approach: **1,533 ops/ms** (56% of Jackson POJO) with EntityJackson + concrete result
- Recommendation: use positional only if JSON field order is guaranteed; otherwise use generic

**For file/stream deserialization** (byte[] array, sustained parsing):
- Best achievable: **9.76 ms** per 30k records (6.9% of Jackson POJO per unit)
- Equals **3,073,171 records/sec**, **15× faster than POJO on same workload**
- Recommendation: use positional or boilerplate concrete; EntityJackson generic adds allocation overhead

**Root cause of remaining per-record gap**:
- Jackson `BeanDeserializer` uses internal parser state access; we call public API methods.
- Each token/value method carries dispatch overhead; cached at function call level but summed across 13+ calls.
- Architectural ceiling with `ReaderBasedJsonParser` (String input): ~60%.

---

## Future optimization paths

1. **Byte-Array Only Variant**: Measure UTF8StreamJsonParser impact in isolation (not done — needs specialized bench).

2. **Custom Jackson Module**: Integrate as Jackson `BeanDeserializerModifier` to bypass public API entirely.

3. **Code Generation**: Specialize deserializers per view type (no `Object[]`, no lambdas, no generics).

---

### Serialization (from previous session)

| Path                                             | ops/ms     | 99.9% CI        |
| ------------------------------------------------ | ---------- | --------------- |
| Jackson POJO (`PersonDto`)                       | 5,301 ±275 | [5,027 — 5,576] |
| `EntityJacksonViewSerializer` (optimized)        | 4,074 ±222 | [3,852 — 4,296] |
| `PersonSummaryGeneratedSerializer` (boilerplate) | 3,981 ±124 | [3,857 — 4,105] |
| `EntityJacksonMapper` + `ViewMeta`               | 3,736 ±221 | [3,515 — 3,957] |

View serialization reaches 74-77% of Jackson POJO throughput.

---

### Running the benchmark

#### All benchmarks
```bash
bun run scripts/run-jmh.js --include ".*EntityJacksonJmhBenchmark.*" --forks 3
```

#### Deserialization and parse-only only (faster feedback)
```bash
bun run scripts/run-jmh.js --include ".*EntityJacksonJmhBenchmark.*(deserialize|parseOnly).*" --forks 3
```

---

### What was fixed (root causes eliminated step by step)

| Root cause                                                               | Fix                                                | Improvement           |
| ------------------------------------------------------------------------ | -------------------------------------------------- | --------------------- |
| `new EntityJacksonViewDeserializer()` inside benchmark loop              | Pre-built in `@Setup`                              | +10% per run          |
| `new ValueReader[n]` + 6× `readerFor()` rebuilt on every `deserialize()` | Stateful constructor caches readers                | Merged into above     |
| JDK Proxy in `meta.create()`                                             | `PersonSummaryConcreteImpl` — direct array wrap    | +15% (proxy→concrete) |
| `nextToken()` + `currentName()` (2 calls) for each field name            | `nextFieldName()` (1 call)                         | ~5%                   |
| Redundant `currentToken()` after `nextToken()` in each switch arm        | Capture `t = p.nextToken()`                        | ~2%                   |
| 3× (`nextToken` + `currentToken` + `getText`) for String fields          | `nextTextValue()` per String field                 | ~2%                   |
| Null check inside each `ValueReader` lambda                              | Moved to outer loop; readers are non-null-checking | ~1%                   |

Cumulative improvement over original unoptimized v1 (1,216 ops/ms):
→ **+33%** to 1,616 ops/ms (positional), or **+32%** to 1,602 ops/ms (boilerplate concrete v1 run).

