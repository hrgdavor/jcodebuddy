# Overlap at workload scale: 3000 users, tens of services each

## Summary

Checking a session's service set against **3000 users** with ~16 services each, on one thread:

| | EEnumSet | `HashSet` variant | difference |
| --- | --- | --- | --- |
| **Time per sweep** | **3.0 µs** | **1.41 ms** | **~469× slower** |
| **Garbage per sweep** | **~0 B** (below profiler resolution) | **2.21 MB** | **2.21 MB of garbage every sweep** |
| Garbage per user examined | 0 B | 736 B | — |
| GC collections per sweep | 0 | 0.018 (one every ~54 sweeps) | — |
| CPU at 100 sweeps/s | 0.03% of one core | 14% of one core | — |
| Garbage at 100 sweeps/s | 0 B | 221 MB/s — **~796 GB/hour** | — |

Across the service-count range measured (3 → 57 services per user) the `HashSet` variant takes
**255 µs → 2.81 ms** per sweep and creates **768 KB → 7.30 MB** of garbage per sweep, while EEnumSet
stays at **2.0–3.0 µs** and allocates nothing. The `HashSet` shape allocates for **every user it
examines**, matched or not; the bitset shape allocates for **every match** — and nothing when there are
none.

Sections 5 and 6 give the full tables this summary is taken from; §1 says which numbers are measured and
which are derived.

## What this document is

This document answers one concrete question — *what does it cost to check a session's service set
against 3000 users' service sets, by `HashSet` and by `EEnumSet`, in time and in garbage* — and then
summarises the detailed benchmark results behind the answer.

The raw evidence, the full 76-variant tables and the interpretation live in
[EnumSet implementation path and JMH evidence](enumset-implementation-and-jmh.md). Read that first for
what was measured; this document only **applies** those measurements to a workload and states every
step of the arithmetic.

## 1. Measured vs derived — read this before quoting a number

| Kind | What it is | Where it comes from |
| --- | --- | --- |
| **Measured** | Throughput of the whole-corpus loop, both implementations, at four set sizes | decision-grade JMH run (`filterAgents64`, `filterAgentsHashSet64`, agents = 1000) |
| **Measured** | Bytes allocated per loop, GC counts and GC time | `-prof gc` probe on the same two benchmarks, agents = 1000, densities 0.05 / 0.25 / 0.9 |
| **Derived** | Everything scaled to 3000 users, GC events per sweep, garbage per hour | arithmetic on the two rows above, shown inline |

Derived numbers are marked †. Every *time* number comes from the decision-grade run; the GC probe used a
short profile by design, because allocation per operation is structural, and its throughput is never
quoted here. No number in this document comes from a smoke run's throughput.

## 2. The loop, both ways

The `HashSet` shape is the one the JMH baseline actually measures — and it is the production shape
that motivated the work: per candidate user, materialise a boxed set and intersect it.

```java
// HashSet baseline — one boxed Set<Long> per user, per check
Set<Long> sessionIds = session.serviceIds();          // stored as Set<Long>
int matches = 0;
for (List<Long> userServiceIds : users) {             // 3000 users
    Set<Long> copy = new HashSet<>(userServiceIds);   // rebuild: ~43 B per service id
    copy.retainAll(sessionIds);                       // intersect
    if (!copy.isEmpty()) matches++;
}
```

```java
// EEnumSet — one long-wise AND per user, no allocation
EEnumSet<Service> session = sessionServices();        // one immutable bitset
int matches = 0;
for (EEnumSet<Service> userServices : users) {        // 3000 users
    if (session.hasAny(userServices)) matches++;      // one AND, early exit on first hit
}
```

The second loop reads a single `long` (width-64 universe) against another, so it allocates nothing,
boxes nothing, and touches no hash table. That difference is the whole of the result.

## 3. The scenario

| Property | Value |
| --- | --- |
| Users scanned per sweep | 3000 |
| Service-ID universe | 64 (the width-64 fixture) |
| Services per user | "tens" — modelled at 16 of 64, which is the measured `density = 0.25` |
| Session set | one set, fixed, same size |
| Threads | 1 |

The benchmark's `density` parameter *is* the number of services, because the fixture computes
`setSize = floor(density × 64)`:

| density | services per user |
| --- | --- |
| 0.05 | 3 |
| 0.25 | 16 ← "tens of services" |
| 0.5 | 32 |
| 0.9 | 57 |

## 4. Measured inputs

Throughput of one whole-corpus operation (one session set against 1000 user sets), decision-grade
profile — 3 forks, 6 × 2 s warmup, 8 × 2 s measurement, JDK 25.0.3, JMH 1.37, `-Xmx4g`:

| density | services | `filterAgents64` (ops/ms) | `filterAgentsHashSet64` (ops/ms) |
| --- | --- | --- | --- |
| 0.05 | 3 | 1,009 | 11.778 |
| 0.25 | 16 | 1,000 | 2.133 |
| 0.5 | 32 | 1,355 | 1.246 |
| 0.9 | 57 | 1,543 | 1.069 |

Allocation per whole-corpus operation, `-prof gc`, agents = 1000:

| density | services | EEnumSet bytes/op | HashSet bytes/op | HashSet GC counts per 1 s iteration (≈) | HashSet GC time |
| --- | --- | --- | --- | --- | --- |
| 0.05 | 3 | 0.005 | 256,000.567 | 23–25 | 22–23 ms |
| 0.25 | 16 | 0.006 | 736,003.357 | 12 | 16 ms |
| 0.9 | 57 | 0.005 | 2,432,006.388 | 21 | 21 ms |

The allocation figures are highly repeatable — a second run of the 0.05 row read 256,000.610 ± 0.208 B/op
against the 256,000.567 ± 0.035 quoted here, a difference of 0.00002%. The GC counts and times are not
that stable, and are marked approximate: they depend on when the heap happens to fill, so the 0.05 row
read 23–25 collections and 22–23 ms across two runs.

The EEnumSet figures (0.005–0.006 B per 1000-user sweep) are the profiler's resolution floor. The true
value is zero: the loop body is a bitwise AND. The pairwise probe in the evidence document reads
≈ 10⁻⁵ B/op for the same reason.

Per-user cost, obtained by dividing those two tables (†):

| density | services | EEnumSet ns per user | HashSet ns per user | HashSet bytes per user |
| --- | --- | --- | --- | --- |
| 0.05 | 3 | 0.99 | 84.9 | 256 |
| 0.25 | 16 | 1.00 | 469 | 736 |
| 0.5 | 32 | 0.74 | 803 | 1,376 |
| 0.9 | 57 | 0.65 | 936 | 2,432 |

Two things fall out of this table. EEnumSet's per-user cost is **flat and sub-nanosecond** regardless
of how many services a user has, because `hasAny` exits on the first matching segment. The `HashSet`
baseline's per-user cost **grows with the number of services** — 85 ns at 3 services to 936 ns at 57,
tracking its allocation almost exactly (≈ 43 bytes per service id, which is a boxed `Long` plus a hash
node plus table slot).

## 5. The 3000-user sweep: time

A 3000-user sweep is three of the measured 1000-user operations (†):

| density | services | EEnumSet per sweep | HashSet per sweep | ratio |
| --- | --- | --- | --- | --- |
| 0.05 | 3 | **2.97 µs** | **255 µs** | ~86× |
| 0.25 | 16 | **3.00 µs** | **1.41 ms** | ~469× |
| 0.5 | 32 | **2.21 µs** | **2.41 ms** | ~1,088× |
| 0.9 | 57 | **1.94 µs** | **2.81 ms** | ~1,444× |

Expressed as full sweeps per second on one core (†):

| density | services | EEnumSet sweeps/s | HashSet sweeps/s |
| --- | --- | --- | --- |
| 0.05 | 3 | ~336,000 | ~3,900 |
| 0.25 | 16 | ~333,000 | ~710 |
| 0.5 | 32 | ~452,000 | ~415 |
| 0.9 | 57 | ~514,000 | ~356 |

The characteristic case — 16 services per user, where a real service registry lives — is **3 µs against
1.4 ms** for the same 3000 users. A single core can run the EEnumSet sweep a third of a million times
per second; it can run the `HashSet` sweep about 700 times.

## 6. The 3000-user sweep: garbage

Bytes allocated per 3000-user sweep (†, from the measured per-operation figures × 3):

| density | services | EEnumSet | HashSet | HashSet per user examined |
| --- | --- | --- | --- | --- |
| 0.05 | 3 | ~0 B | 768 KB | 256 B |
| 0.25 | 16 | ~0 B | 2.21 MB | 736 B |
| 0.5 | 32 | ~0 B | 4.13 MB | 1,376 B |
| 0.9 | 57 | ~0 B | 7.30 MB | 2,432 B |

This is the sharpest difference in the whole comparison, and it is not a ratio that can be quoted
sensibly — one side is a few bytes of profiler noise, the other is megabytes per sweep. The `HashSet`
path allocates something for **every user it examines**, whether or not that user matches.

The probe also fixes the JVM's collection cadence, consistently across all three densities: the young
generation collects about once per **120–130 MB** allocated (`-Xmx4g`, default G1). That gives GC events
per sweep (†, approximate for the same reason as the counts above):

| density | services | HashSet bytes/sweep | GC events per sweep | sweeps per GC |
| --- | --- | --- | --- | --- |
| 0.05 | 3 | 768 KB | 0.006 | ~156 |
| 0.25 | 16 | 2.21 MB | 0.018 | ~54 |
| 0.9 | 57 | 7.30 MB | 0.061 | ~16 |

Each collection cost 0.9–1.3 ms of measured GC time in the probe (millisecond resolution, so treat it as
an order of magnitude), which puts GC at roughly **6–60 µs per sweep** — about **2%** on top of the
baseline's own CPU cost at every density measured (60.8 µs of GC against 2,806 µs of CPU at the dense
end, 24.5 against 1,406 at the representative one), and a large absolute garbage volume in a
long-running service.

### At a rate

A service that re-checks all 3000 users 100 times per second (a sweep every 10 ms), on one core (†):

| density | services | EEnumSet CPU | HashSet CPU | HashSet garbage | HashSet garbage per hour |
| --- | --- | --- | --- | --- | --- |
| 0.05 | 3 | 0.03% of a core | 2.5% of a core | 77 MB/s | ~276 GB |
| 0.25 | 16 | 0.03% of a core | 14% of a core | 221 MB/s | ~796 GB |
| 0.9 | 57 | 0.02% of a core | 28% of a core | 730 MB/s | ~2.6 TB |

At 10 sweeps per second the same table divides by ten: the `HashSet` path still churns **28–263 GB per
hour**, while the EEnumSet path churns none. That is the argument for this type in one line: the
`HashSet` shape pays per user examined, forever; the bitset shape pays per match, and pays nothing when
there are none.

## 7. What this model does not include

Three honest qualifications, all of which shrink the EEnumSet advantage rather than inflate it:

- **Building the sets is not in these numbers.** Both loops read a stored representation. Per user,
  the EEnumSet representation is one small object holding a `long` (~24 B) against a `Set<Long>` of ~16
  entries (~1 KB, estimated, not measured). So the EEnumSet advantage on *storage* is real but is a
  separate, unmeasured claim; the numbers above are the *check*.
- **Collecting matches allocates on both sides.** If the caller appends matching users to a list, that
  cost is identical for both paths and is proportional to matches, not to users scanned: ~60–110 KB per
  sweep for 3000 boxed matches, against the baseline's 2.21 MB for the same sweep. It does not change
  the conclusion, and it is the reason the two paths converge when *everything* matches.
- **A wider ID universe costs more per check.** The whole-corpus measurement exists only at width 64.
  The pairwise rows in the evidence document put width 96 at ~0.87× and width 256 at ~0.84× of width
  64's throughput on a hit path, and the `disjoint*` rows put a no-overlap width-256 scan at ~0.29× —
  i.e. roughly **3.4× slower** than width 64. Applied to the table above, a no-overlap session against a
  256-wide universe costs on the order of 10 µs per 3000-user sweep instead of 3 µs — still ~25× ahead
  of the `HashSet` baseline at the sparse end, and further ahead wherever overlap is common.

## 8. Summary of the detailed results

Everything below is the decision-grade run (3 forks, 6 × 2 s warmup, 8 × 2 s measurement, JDK 25.0.3,
JMH 1.37, `-Xmx4g`, 1 thread, 76 variants) — the full tables and error bars are in the
[evidence document](enumset-implementation-and-jmh.md), and the raw capture is
`target/jmh/overlap-full2.log`.

**Pairwise overlap (`hasAny`), ops/ms by density** — width 64 for the EEnumSet/`HashSet` pair, with the
width-96 `BitSet` and the raw-`long` reference alongside. EEnumSet is flat, `HashSet` collapses:

| density | 0.05 | 0.25 | 0.5 | 0.9 |
| --- | --- | --- | --- | --- |
| `overlapEEnumSet64` | 869,543 | 826,517 | 719,214 | 859,759 |
| `overlapHashSet64` | 13,853 | 3,156 | 1,845 | 1,164 |
| `overlapBitSet96` | 80,123 | 79,975 | 83,392 | 86,074 |
| `overlapRawLong64` | 1,752,893 | 1,762,388 | 1,760,516 | 1,779,034 |

**Widths (pairwise, density 0.5)** — segmentation costs little on a hit path: `EEnumSet64` 719,214,
`EEnumSet96` 623,629, `EEnumSet256` 604,264; builder variants track the immutable read path
(`Builder64` 804,305–836,466).

**Disjoint scan (the worst case)** — flat within ±3% across density and still far ahead of the
baseline: `disjointEEnumSet64` 1,093,059–1,124,064 against `disjointHashSet64` 1,939–2,144 (~512× at
density 0.5); width 256 falls to ~322,000, the price of scanning four segments instead of one.

**Headline ratios**

| Comparison | Ratio |
| --- | --- |
| `overlapEEnumSet64` vs `overlapHashSet64` @0.5 / @0.9 | ~390× / ~739× |
| `overlapEEnumSet256` vs `overlapHashSet256` @0.5 | ~1,246× |
| `disjointEEnumSet64` vs `disjointHashSet64` @0.5 | ~512× |
| whole-corpus (100 agents / 1000 agents) @0.9 | ~1,933× / ~1,444× |
| `overlapEEnumSet96` vs `overlapBitSet96` @0.5 | ~7.5× |
| `overlapRawLong64` vs `overlapEEnumSet64` @0.5 | ~2.5× (raw `long` is faster) |

**Allocation (pairwise probe, density 0.5)** — `overlapEEnumSet64` ≈ 10⁻⁵ B/op with ≈ 0 GC counts per
iteration; `overlapHashSet64` 1,376.004 B/op, 2,145.761 MB/s, 18 GC counts and 22 ms of GC per 1 s
iteration.

**Caveats carried from the evidence document.** The width-256 `density = 0.05` rows read about half
their neighbours and are flagged for re-measurement, not smoothed. `rawLong64` remains the honest upper
bound at ~2.5× the width-64 bitset. And the whole path requires a bounded, known service-ID universe
that can be assigned ordinals; the `HashSet`, `BitSet` and raw-`long` baselines are kept in the
benchmark so the comparison holds for a `long`-ID system too.

## 9. Reproducing

```bash
# decision-grade throughput for the whole-corpus loop
bun run scripts/run-jmh.js --include ".*EEnumSetOverlapJmhBenchmark.*" \
  -p density=0.05,0.25,0.5,0.9 -p agents=100,1000

# allocation and GC for the same loop (short profile on purpose, see below)
bun run scripts/run-jmh.js --include ".*EEnumSetOverlapJmhBenchmark.filterAgents.*" \
  -p density=0.05,0.25,0.9 -p agents=1000 \
  --forks 1 --warmup-iterations 3 --measurement-iterations 5 \
  --warmup-time 1s --measurement-time 1s -prof gc
```

Unrecognised flags are passed through to JMH, so `-prof gc` works from the runner. Because the second
command uses a short profile, the runner writes `target/jmh/results-smoke.json` rather than overwriting
the decision-grade `target/jmh/results.json`.

The allocation figures quoted above came from that probe, whose console capture is
`target/jmh/gc-filter-probe.log` (the pairwise allocation probe is `target/jmh/gc-probe.log`). The probe
ran a short profile deliberately: allocation per operation is structural and does not need forks or long
warmup to be trustworthy, whereas the throughput column of a short profile must never be quoted — which
is why every time number above comes from the decision-grade run instead.
