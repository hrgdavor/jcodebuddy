# Unified plan — every open item in the repository, in one ordered schedule

**Status: 2026-10-01 — live.** This file is the **single schedule** for work that is already decided or
already planned but not done. It replaces the "which plan is still open?" archaeology across
`plans/`, `webview/`, `merge-java/`, `doc-hipster-entity/` and `.kilo/plans/`: each of those documents
keeps its own history, and every genuinely open item in them appears here exactly once, as a numbered,
gated step, in the order it should be done.

Work it top to bottom, one step per commit. Do not start a step before the previous step's gate is
green. Tick the box in [§ Progress](#progress) in the same commit that finishes the step.

---

## 1. How to work this file

- **One step, one commit.** The step's *Gate* is its definition of done: the command must pass and its
  output belongs in the commit message.
- **A step that turns out to be a decision, not code, still ends in a commit** — the decision written
  into the record it belongs to (an ADR, the roadmap tracker, or the plan it came from).
- **If a step needs an architecture decision, the ADR comes first** (the decision record is written and
  registered in [`decisions/README.md`](../doc-hipster-entity/architecture/decisions/README.md) before
  the code that depends on it). `doc/AGENTS.md` is the rule.
- **Split a step if it does not fit one commit.** Renumber nothing: add `4.2a`, `4.2b` and say why.
- **Mark a step blocked rather than skipping it**, with the concrete blocker in the progress table, and
  move to the next step that does not depend on it. Phases 1–4, 6 and 7 are independent of each other;
  within a phase the steps are ordered.

### Command vocabulary

| Name | Command | Notes |
| --- | --- | --- |
| **GATE** | `bun scripts/mvn-jdk25.js` | the recorded gate: the hipster-entity module set, `clean test`, incremental compilation off. Resolves JDK 25 itself. |
| **MODULE** | `bun scripts/mvn-jdk25.js -pl <modules> -am test` | any module set; add `install` instead of `test` when a consumer resolves it from the local repository |
| **LINKS** | `node scripts/check-repo-links.mjs` and `node webview/check-links.mjs` | every relative link in every Markdown file must resolve |
| **EXAMPLES** | `npm run check:examples` | the `@hrg/inject-examples` markers in `merge-java/docs/resolvers` and `materialization-levels.md` are not stale |
| **JMH** | `bun run scripts/run-jmh.js --include "<regex>"` | decision-grade results need the default profile (3 forks, 6×2 s warmup, 8×2 s measurement); never record a trimmed run as evidence |

**Sizes** are rough: **S** ≤ half a day, **M** 1–2 days, **L** 3+ days. **Who** is `agent` (doable in a
checkout with no human) or `human` (needs a person, a running IDE, or an external tool).

---

## 2. Rules a step must respect (they are not restated per step)

1. **Source-visible wiring** (root `AGENTS.md` § 1): generated connections are committed, navigable Java.
   No reflection-driven discovery, no `META-INF/services` registries doing the wiring.
2. **Cooperative codegen** (DEC-020/021/022/035): recognise previous output by shape, preserve user
   edits verbatim, opt back in by deleting the block. New whole-file emitters carry the two-line
   `@generated` header (DEC-035/DEC-021).
3. **Bun JavaScript only** for anything that runs a check: `.js`/`.mjs` with a `#!/usr/bin/env bun`
   header. Never `.ps1`, `.cmd`, `.bat`, `.sh`. Java build steps stay Maven/Gradle.
4. **OpenRewrite LST is the only Java source representation** (DEC-030): read via `SourceReader`, query
   via `TreeQueries`, positions from javac, and **splice text** to write — never reprint a tree.
5. **A `project-automation` module is strictly private** (§ 1.1): never installed, never depended on.
   Reusable pieces are promoted into a JCodeBuddy library instead.
6. **Generated `.java` stays under `src/main/java`**; `.jcodebuddy/` holds derived output and `conf/`
   is the only tracked subtree (DEC-026).
7. **Reports are rendered by Bun from the generator's JSON metadata**, never by a Java generator
   (DEC-027/028/029): one self-contained, framework-free HTML file, every link verified before it is
   written.

---

## 3. What this plan schedules

| Document | What it contributed here | Its own state after this plan |
| --- | --- | --- |
| [`plans/enumset-overlap-jmh-plan.md`](enumset-overlap-jmh-plan.md) | step 0.1 — land the finished work | closed |
| the stale-document pass of 2026-10-01 | step 0.2 — commit it | closed |
| [`doc-hipster-entity/architecture/decisions/DEC-021.md`](../doc-hipster-entity/architecture/decisions/DEC-021.md) § 6 | step 1.1 — `enabled: false` | note removed when the step lands |
| [`doc-hipster-entity/architecture/decisions/DEC-W008.md`](../doc/architecture/decisions-watch/DEC-W008.md), the `.kilo` metadata-server plan | steps 1.2–1.4 | closed into steps |
| the `.kilo` metadata-arena plan | steps 2.1–2.2 | closed into steps |
| the `.kilo` hipster-ioc-integration plan, [`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md) | steps 3.1–3.3 | closed into steps |
| [`merge-java/IMPLEMENTATION_PLAN.md`](../merge-java/IMPLEMENTATION_PLAN.md) | steps 4.1–4.4 | Phase 9 residue + Phase 13 closed |
| [`webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md) | steps 5.1–5.3 | Phase 6 and Q3/Q5 closed |
| [`webview/PLAN-eclipse-host.md`](../webview/PLAN-eclipse-host.md) | steps 5.4, 8.2 | Phase 5 + observations closed |
| [`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) | steps 6.1–6.5 | open rows closed |
| [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md), [`java-watch-agent/plan.md`](../java-watch-agent/plan.md), [`todo.hipster-entity.md`](../todo.hipster-entity.md), [`todo.java_watch2.md`](../todo.java_watch2.md), [`doc-hipster-entity/doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md), [`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) | steps 7.1–7.6, 8.1, 8.3 | "Future Refinement"/"Open questions" lists emptied |
| [`plans/rewrite-migration/`](rewrite-migration/README.md) | nothing — it is **complete** | stays in place as the historical record (step 9.2) |

---

## 4. Phase 0 — land what is already finished

### 0.1 — Commit the EEnumSet overlap JMH delivery
**Who:** agent · **Size:** S

The work is done and uncommitted: `plans/enumset-overlap-jmh-plan.md` says "implemented (2026-10-01)",
the parity gate is green, and the decision-grade numbers are written into
[`enumset-implementation-and-jmh.md`](../doc-hipster-entity/architecture/enumset-implementation-and-jmh.md).
Everything it names is on disk but nothing is committed: the plan, the workload summary, the fixtures,
the benchmark, the parity test, and the `scripts/run-jmh.js` extension are all untracked or modified.

**Do:** review the change set (`git status`), then commit it as one change — the benchmark, the shared
fixtures, the parity test, the runner extension, the plan, the new workload summary and the DEC-014 /
`enumset-implementation-and-jmh.md` updates, with the recorded numbers in the message.

**Gate:** `bun scripts/mvn-jdk25.js` green **and** `LINKS` green. Re-running the benchmark is optional
(the evidence is already recorded) — if you do re-run it, use the default profile and say so.

**Done when:** `git status` is clean for these paths and the plan's status line matches the commit.

### 0.2 — Commit the stale-document corrections
**Who:** agent · **Size:** S

The 2026-10-01 pass corrected status lines that contradicted the tree. It touched:
[`merge-java/IMPLEMENTATION_PLAN.md`](../merge-java/IMPLEMENTATION_PLAN.md) (the WS2 banner was stale),
[`merge-java/CHANGELOG.md`](../merge-java/CHANGELOG.md) (an appended correction, the entry itself
untouched),
[`webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md) (the status line, and a
`webview/kit/kit/…` path typo),
[`webview/PLAN-eclipse-host.md`](../webview/PLAN-eclipse-host.md) (Phase 5/Q2/Q8 open),
[`doc-hipster-entity/doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md) (status
banner; items 12–13 open),
[`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md) (backlog, nothing implemented),
[`java-watch-agent/plan.md`](../java-watch-agent/plan.md) (the two Phase 4 boxes),
[`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md) (two Future Refinement boxes),
[`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) (§ 8
unanswered),
[`todo.hipster-entity.md`](../todo.hipster-entity.md) and [`todo.java_watch2.md`](../todo.java_watch2.md)
(each item reconciled against the code: Wyhash and `text_extensions` are done, the LSP incremental item
is done),
[`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) (a pointer to this
plan), and
[`decisions/README.md`](../doc-hipster-entity/architecture/decisions/README.md) — **DEC-W006 – DEC-W009
were missing from the index entirely** and are now rows in the watch-subsystem table.

**Do:** commit all of it, with this plan, as one documentation change. No code changed.

**Gate:** `LINKS` green **and** `EXAMPLES` green.

**Done when:** committed; the corrections' commit message lists the documents and the one-line reason
each was wrong.

---

## 5. Phase 1 — close the gaps that already have a decision

### 1.1 — Honour `enabled: false` (the whole-file freeze, DEC-018 / DEC-021 § 6)
**Who:** agent · **Size:** M

[DEC-021 § 6](../doc-hipster-entity/architecture/decisions/DEC-021.md) recorded the gap in its own text:
the header **format** is implemented and emitted by every generator, `entityFieldEnum` and
`allowReorder` were decoded and honoured, and **`enabled` was not honoured — no code path read it**, so
`enabled: false` did not freeze a file and the next pass regenerated it.

**Done 2026-10-01.** What changed:

- `EnumConstantOrderChecker.HeaderConfig` gained an `enabled` component (default **true**), decoded from
  the header's JSON5. **A malformed header does not freeze the file** — the two fail-safe directions are
  deliberately opposite (still `marked`, so R1 keeps protecting the enum; still `enabled`, so one typo
  cannot silently stop generation).
- `CooperativeCodegen.isFrozen(Path)` / `isFrozen(J.CompilationUnit)` is the **single reader** of the
  knob, and `reconcileMembers` returns the previous text unchanged (plus one DEC-022 line,
  `kind=file_frozen`) before it consults `force`. **The freeze outranks `--force`**: force means "every
  member in this file belongs to the generator", and a file cannot be both; the way out is the freeze's
  own opt-out (`enabled:true`, or delete the line).
- **Two of the writers did not go through the reconciler, which this step's own text had assumed.**
  `ViewAdapterGenerator` writes `<View>RowAdapter.java` / `<View>Binder.java` directly and emits the
  DEC-021 header, so it now checks `isFrozen` (and skips the write, reporting `file_frozen`), taking the
  reporter through a new five-argument overload that the pass supplies. `ViewInterfaceGenerator` writes
  the developer's **own** view interface — no generated header, so `enabled` has nothing to freeze there —
  and is correctly untouched.
- `DivergenceReporter.KINDS` gained `file_frozen`, with its producer named in
  `ExampleDivergenceReportTest.KIND_PRODUCER` (that test fails on a kind nothing produces).
- New `CooperativeCodegenEnabledTest`: byte-for-byte freeze, the enabled path still regenerating *and*
  still preserving the developer's member, force not overriding the freeze while still regenerating an
  enabled file, the malformed-header direction, `isFrozen` for the four file states a pass meets, and an
  end-to-end pass over a tree with one frozen generated file (asserted byte-identical, reported, and
  still compiled).
- **DEC-021 was corrected rather than merely annotated**, because two of its claims were false:
  § 6's "aspirational" note is replaced by an implemented-status note; § 1/§ 2 and the worked examples now
  show DEC-035's `@generated file` first line instead of the `{@link …}` form it was written with; and the
  `blockMarker: "strict"` / `maxMethods` examples are removed with an explicit statement of the three keys
  this project actually reads (`enabled`, `entityFieldEnum`, `allowReorder`).
- `doc-hipster-entity/roadmap/README.md`'s DEC-018 row no longer says the freeze is "not yet honoured".

**Gate:** `GATE` green (8 modules), with `CooperativeCodegenEnabledTest` (7 tests) in the run.

**Done when:** ✅ done — see the commit for this step.

### 1.2 — Implement `MetadataProvider.parse` (the no-cache path, DEC-W008)
**Who:** agent · **Size:** M

**Done 2026-10-01.** DEC-W008's P0 half is implemented, and four points of the decision's text were
corrected in place (the amendment block at the top of DEC-W008 records all four):

- `MetadataProvider.parse(String relativePath, byte[] sourceBytes)` is on the interface. Its **default
  throws** `MetadataParseUnsupportedException` — DEC-W008 required a working default, which is not
  implementable in `metadata-server`: a default that parses needs the repository's one source reader
  (OpenRewrite's LST, DEC-030), which lives in `hipster-entity-tooling` and is not on this module's
  classpath. The decision's own boundary section had left the location open, so this is its "module
  dependency resolution" answer, recorded rather than assumed.
- The **reference implementation** is `project-automation`'s new `SourceMetadataParser`, called from
  `InMemoryMetadataCacheProvider.parse` — the override DEC-W008 names. Pure (nothing outside its two
  arguments is read or written), file-scoped facts only: the wayhash of the LF-normalised bytes via the
  tooling's `ContentHash` (so the CRLF/LF rule of DEC-029 § 4 is not re-implemented), the primary type
  chosen by Java's own file-name rule, its kind via `MetadataLocations.kindOf`, and its declared method
  names (sorted — declaration order is not part of the fact).
- **`SourceMetadata` does not exist**, so the entry carries the same `Map<String, Object>` payload cache
  entries already use; a parallel model would have made the interface's two halves disagree the day
  DEC-W007 lands.
- Additive surfaces: RPC `parseFile`, MCP `parse_file`. A provider with no parser gets a JSON-RPC error
  naming the provider (and the MCP tool an error result) rather than a silent `null`; the cache-backed
  methods are untouched, asserted in the same test.
- Tests: `SourceMetadataParserTest` (6) and two new `MetadataServerTest` cases (7 in that class).

**Still open from the decision:** the manual-mode CLI (`jcodebuddy metadata parse <file>`) — now step 7.7.

**Gate:** ✅ `MODULE` for `metadata-server,metadata-mcp-server,project-automation` green.

**Done when:** ✅ done — see the commit for this step.

### 1.3 — `WatchMetadataProvider`: the metadata server over the watch cache
**Who:** agent · **Size:** M

**Done 2026-10-01, with one deliberate deviation from this step's own text.**

What landed: `WatchMetadataProvider` in `metadata-server` — a snapshot view of a watch checksum cache built
from its three real facts (`WatchedFile(path, checksum, lastModified)`). It answers `get` by checksum (the
cache is keyed by path and the interface by hash, so identical content resolves to the first path in path
order while `listEntries` still lists every file), `listEntries` (path-ordered, so a cache with no order of
its own answers in a deterministic one), `hasChanged`, and `listClasses` — **empty**, deliberately, because
a checksum cache has never known a class name and paths that look like Java files would be a guess dressed
as a fact. `parse` is not overridden, so the interface's default answers with the named refusal. Tests:
`WatchMetadataProviderTest` (8), one of which drives a JSON-RPC round trip through the real transport.

**The deviation: the dependency this step asked for is not added, in either direction.** The step followed
the `.kilo` metadata-server plan ("promote `java-watch-agent` from test scope to compile scope"), which is
backwards twice over: it would make a **library** (`metadata-server`, which `metadata-mcp-server` and
`project-automation` both depend on) depend on an **application** that shades a jar and pulls OpenRewrite's
LST in through `jwa-builder`; and the reverse direction — `metadata-server` added to `java-watch-agent` —
would shade Fory into the watch daemon's fat jar for the sake of a three-line helper. So the **reusable
half is promoted here**, which is AGENTS.md § 1.1's rule doing its job, and the call site converts:

```java
WatchMetadataProvider.of(cache.getCache().entrySet().stream()
        .map(e -> new WatchMetadataProvider.WatchedFile(
                e.getValue().path(), e.getValue().checksum(), e.getValue().lastModified()))
        .toList())
```

**Left to the consumer, and not claimed as wired:** no production call site exists today, because nothing
yet runs a `MetadataServer` over a `MetadataCache` — the `.kilo` plan's own step 10 ("integration test
wiring `MetadataServer` with a real `MetadataCache`") never happened either. When such a call site appears,
the snippet above is the whole wiring, and that module's test is the place to prove it.

**Gate:** ✅ `MODULE` for `metadata-server` green — 15 tests (`MetadataServerTest` 7,
`WatchMetadataProviderTest` 8); `metadata-mcp-server` compiles unchanged.

### 1.4 — Test the MCP tool surface
**Who:** agent · **Size:** S

**Done 2026-10-01.** `metadata-mcp-server` has its first test directory, and all **six** advertised tools
are called rather than four: `get_entry`, `list_entries`, `get_metadata`, `has_changed`, `list_classes` and
`parse_file` (added by step 1.2, so leaving it untested would have made the newest tool the untested one).

- `MetadataMcpToolProviderTest` (8 tests) drives each handler directly and asserts the answer, including
  the two shapes that are easy to get wrong: a missing argument is an **error result**, not a thrown
  exception, and `parse_file` against a provider with no parser (DEC-W008's default) arrives as a tool
  error naming the provider rather than a broken session. The cache-backed tools are asserted still to work
  for that provider, which is what "additive" has to mean.
- A drift guard: `tools()` and `register()` are two hand-maintained lists of the same six names, so the
  test pins the names — a tool registered but not advertised (or the reverse) fails here.
- One small production change to make that possible: the six handlers are **package-private** rather than
  `private`, with a comment saying why. The wiring stays `register()` — one explicit `toolCall` per tool,
  each naming its handler method — so there is still no name-to-handler registry to keep in step; a test
  forced to assemble an `McpSyncServer` would have been testing the SDK instead of this class.

**Gate:** ✅ `MODULE` for `metadata-mcp-server` green — 8 tests.

---

## 6. Phase 2 — metadata-arena: make it tested and measured

### 2.1 — The unit tests the plan asked for
**Who:** agent · **Size:** M

**Done 2026-10-01 — and the tests found six real defects.** 37 tests in six classes (was 8 in one):

- `ArenaTest` (8) — both backends, through one set of assertions: sequential allocation, view round-trip,
  byte order, `reset`, growth, read-only refusal, idempotent `close`, and the growth/view caveat.
- `MemoryViewTest` (5) — typed and bulk access with array offsets, and the byte-level defaults.
- `CompactIndexFormatTest` (3) — the magic's exact eight bytes, the header budget, and the layout
  arithmetic. **The note this step inherited was wrong about the format**: the fixed region is
  `HEADER_SIZE + 16 * capacity` (keys *and* value pointers are 8 bytes per slot), not "12 × capacity".
- `IndexMmapRoundTripTest` (5) — values (the existing test asserted only size and capacity), the exact file
  size with no padding, bad magic, bad version, and the byte-order contract.
- `LongToLongsIndexLayoutTest` (8) — the sizing requirement, value-list growth, `totalSize()` accounting,
  the reserved key, the two constructor refusals, and both backends.
- The existing `LongToLongsIndexTest` (8) already covered six of the plan's seven intents, but two only
  nominally: its "capacity growth" case never actually grew anything (an oversized arena), and its mmap case
  never read a value back.

**The six defects, each fixed here and pinned by the test that found it:**

1. **`MemoryView.getBytes`/`putBytes` were wrong for any run longer than one word** —
   `offset + (long) i / 8` advanced the word base by one *byte* per byte instead of eight, so bytes ≥ 8 wrote
   into overlapping words and read back scrambled. Nothing noticed because the only caller
   (`IndexMmapWriter`'s tail) passes fewer than eight bytes.
2. **`FfmMemoryView` declared 8-byte alignment for a packed format**, so the FFM API rejected the index's
   data writes outright (`Target offset 132 is incompatible with alignment constraint 8`): `LongToLongsIndex`
   could not be built over an `FfmArena` at all, while the README claimed both backends work. The layouts
   now declare alignment 1.
3. **`FfmArenaImpl.view()` was sliced to `cursor`** — an empty view before the first allocation, which is the
   exact moment `LongToLongsIndex` writes its fixed region. It now covers the whole segment, like the
   `ByteBuffer` backend always did; the useful guard (refusing a write past the storage) is the segment's own
   bounds.
4. **`FfmArenaImpl.allocate` copied only the allocated prefix on growth**, losing anything written through a
   view without allocating — which is how the index writes its fixed region. `ByteBufferArenaImpl` copied its
   whole buffer; now both do.
5. **`FfmArenaImpl.close()` was not idempotent** (`java.lang.foreign.Arena.close()` throws "Already closed"),
   so `LongToLongsIndex.close()` followed by the caller's own `arena.close()` — the shape every test in the
   module uses — threw during teardown.
6. **`IndexMmapWriter` wrote the *arena's* byte order**, against the module's documented little-endian format,
   so a big-endian index produced a file the little-endian reader misread. It now writes little-endian and
   **refuses** a big-endian index rather than converting it: it transcribes 8-byte words, and the header's
   4-byte fields would be scrambled by a word-level copy, so the honest answer is a named failure.

Docs moved with the fixes: `Arena` and `MemoryView` now state their contracts (view extent, the growth
caveat, the byte packing, alignment), `LongToLongsIndex` gained the javadoc it never had (sizing requirement,
reserved key, rebuild-by-copy, `close` semantics), and the README's magic bytes, layout formula, test list and
byte-order rule were corrected.

**Gate:** ✅ `MODULE` for `metadata-arena` green — 37 tests, 0 failures.

### 2.2 — The JMH benchmarks
**Who:** agent · **Size:** S–M

**The decision this step asked for: yes, the numbers inform something** — which backend the metadata cache
should default to (the module ships two and nothing chooses between them), and whether a full rebuild
(DEC-W009's protocol) is cheap enough to run on every watcher batch.

**Done 2026-10-01.**

- `ArenaIndexJmhBenchmark` in `metadata-arena`: `getHot`, `getRandom`, `rebuild` (reset + re-insert, because
  that is how a caller performs it), `mmapLoad` (map the file written in setup, read every entry, unmap).
  Params: backend ∈ {`bytebuffer`, `ffm`} × entries ∈ {1 000, 100 000}. It declares no forks/iterations of
  its own, so the runner's profile governs.
- `metadata-arena/pom.xml` gained the `jmh` profile (`-proc:full`, without which the harness is never
  generated and the sources merely compile) and the same two surefire excludes `hipster-entity-core` has, so
  a `-Pjmh` build stays a build rather than running the benchmark as a test.
- `scripts/run-jmh.js`: `metadata-arena` added to the benchmark module list, to the classpath and to the
  generated-source collection, plus an `Arena` summary group. **The classpath file is now built from a named
  module** (`hipster-entity-test`) instead of "whichever module is last in reactor order" — adding an
  independent module is exactly what makes that ordering assumption fail silently, and the fix removes the
  need to reason about it. Two errors of mine on the way: `-Pjmh` was passed to a module that does not define
  the profile (Maven refuses, correctly), and the runner's own JDK check caught `JAVA_HOME` on 21 — both are
  the tooling working as designed.
- **The benchmark found a seventh defect, and a gap in the tests step 2.1 had just written.**
  `IndexMmapWriter` computed its byte-wise tail as "everything left in the file" rather than "a partial
  word", and the byte-level defaults transcribe a word most-significant-byte first — the reverse of memory
  order. Every file larger than one 64 KB chunk therefore landed on disk scrambled from 64 KB on, and a
  reader over it answered *no such key* rather than reporting corruption. `mmapLoad` at 100 000 entries
  (~5 MB file) threw; every unit-test file fitted in one chunk, which is why they passed. The writer now
  copies word by word and writes the ≤7-byte tail little-endian, and
  `IndexMmapRoundTripTest.aFileLargerThanOneWriterChunkRoundTripsToo` (capacity 8 192 → 131 KB fixed region)
  pins it. 38 arena tests green.
- **Evidence, and what is not evidence:** the smoke runs completed all 16 cases through the standard entry
  point (`bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"`), which is this step's gate.
  Their numbers are **not recorded** — 1 fork and 1×1 s, and the runner prints exactly that warning. They do
  raise one question worth a real run: `ByteBuffer` measured ~10× faster than FFM on `get` and `rebuild`
  (≈134 000 vs ≈13 000 ops/ms for a hot get), plausibly the alignment-1 var-handle access against an
  intrinsified `ByteBuffer`. That is a hypothesis, not a finding, and it is what step 2.3 exists to settle.

**Gate:** ✅ the benchmark compiles under `-Pjmh` and runs through the runner; `MODULE` for `metadata-arena`
green (38 tests).

### 2.3 — The decision-grade arena run, and the backend decision it settles
**Who:** agent, on a quiet machine · **Size:** S

Step 2.2 left one thing deliberately unrecorded: the smoke run's numbers are not evidence, and the module
still ships two backends with nothing choosing between them. This step is the run that produces the
evidence, and the decision that consumes it.

**Do:** on a machine that is not building anything else, run the default profile:

```
bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"
```

That is 3 forks with 6×2 s warmup and 8×2 s measurement per case — roughly half an hour, and the runner
warns if any of it is lowered. Then record the outcome where the decision lives: which backend
`metadata-server` should use for the index (and why), and whether a `rebuild` at the expected table size fits
the watcher batch the DEC-W009 rebuild protocol is meant to serve. Write it into DEC-W009's implementation
note, not into a README.

**Gate:** the numbers come from a default-profile run (the runner's own warning line is absent), and the
decision — including "either backend will do, and here is why that is the answer" — is written down.

**Done when:** no reader has to run the benchmark to learn which backend to use.

---

## 7. Phase 3 — hipster-ioc: fill the empty module

### 3.1 — The hipster-ioc ADR
**Who:** agent · **Size:** S

**Done 2026-10-01 — [DEC-036](../doc-hipster-entity/architecture/decisions/DEC-036.md), status `Trial`.**

- The number the `.kilo` plan asked for (`DEC-W008`) was already taken by the metadata no-cache decision, so
  the record is **DEC-036** in the main series — the watch series covers the watch subsystem, and this is a
  code-generation decision.
- It fixes the twelve things step 3.2 must not re-decide: `<Context>Impl` committed under the consuming
  module's `src/main/java` with DEC-035's file marker; the interface implemented directly (no proxy, no
  reflection, no name→bean registry, per DEC-019); creation order derived from `dependencies()` and each
  bean's type, with the same order driving `init*`; dependency-free beans in field initializers; `@Circular`
  as a settable field and an **unmarked cycle as a `circular_dependency_unmarked` diagnostic**; parent
  plumbing for `ChildContext<P>`; `impl` suppressing creation code; `Supplier`/`DynamicResource`/
  `StableValuePolyfill` as the **opt-in** lazy seam with eager creation as the default; region markers only
  above the design document's thresholds (fields > 5, exposed beans > 3, factory methods > 3) with DEC-020
  preservation and the `enabled:false` freeze from step 1.1; the graph as JSON under
  `.jcodebuddy/metadata/` and the page rendered by Bun (DEC-027/029); no `project-automation` dependency
  (§ 1.1) and no second Java parser (DEC-030); and the `<Context>` → `<Context>Impl` naming contract for
  DEC-022.
- Registered in [`decisions/README.md`](../doc-hipster-entity/architecture/decisions/README.md) with its
  Notes row, and cross-linked from
  [`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md) and
  [`architecture-and-design.md`](../hipster-ioc/doc/architecture-and-design.md) — the second says explicitly
  that DEC-036 is the decision and the design document is the intent behind it, so a later reader does not
  have to guess which wins.

**Gate:** ✅ `LINKS` green (252 files, 1459 links) and the record is listed in the index.

**Done when:** ✅ done — step 3.2 implements against this record.

### 3.2 — `CodeGenerator<GeneratedContext>` and dependency-graph computation
**Who:** agent · **Size:** L

[`hipster-ioc-tooling`](../hipster-ioc-tooling/pom.xml) is a reactor module whose POM says "Code generator
and dependency graph computation for hipster-ioc" and which contains **no `src/` at all** — it compiles
to an empty jar. A grep for `HipsterIocGenerator`, `GeneratedContext` or a dependency-graph service
finds nothing anywhere in the tree. The API surface it must target is the five types in
[`hipster-ioc-api`](../hipster-ioc-api/src/main/java/hr/hrg/hipster/ioc) (`HipsterContext`,
`ChildContext`, `Circular`, `DynamicResource`, `StableValuePolyfill`), and the hand-written context it
must eventually replace is [`hipster-ioc-test`](../hipster-ioc-test/src/test/java/hr/hrg/hipster/ioc/test)'s
`CtxMain`/`CtxMainModule`.

**Do:** implement the generator and the dependency-graph computation per step 3.1's decision. Generated
Java is committed under `src/main/java` of the consuming module and is IDE-navigable (§ 1). **Do not
declare `project-automation` as a dependency** — the POM's comment records that it was deliberately
removed, and § 1.1 forbids re-adding it; anything genuinely reusable goes into a library module.

**Gate:** `MODULE` for `hipster-ioc-tooling,hipster-ioc-test` green, with a test that generates a context
from the test module's beans and asserts the graph (dependencies, factories, exposed beans).

**Done when:** the module has sources that build, generate, and are asserted by tests.

### 3.3 — Make it runnable and documented
**Who:** agent · **Size:** M

A generator nothing can invoke is not a delivery. The ROADMAP's second half — the dependency metadata
and its navigation — needs a path a user can actually take.

**Do:** wire the generator into the documented entry point (the dev-time runner and/or a CLI), extend
[`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md) to say what landed and what did **not**
(the embedded HTTP server for graphs is a separate item — leave it explicitly open or drop it in the
same decision), and document the generated shape where a user reads it.

**Gate:** the new path runs from a documented command in a clean checkout, and `GATE` is green.

**Done when:** the ROADMAP's Phase 2 items are each either delivered or explicitly dropped with a reason.

---

## 8. Phase 4 — merge-java: Phase 9's residue and Phase 13

### 4.1 — Replace the hardcoded `WIDENING_CHAINS` table
**Who:** agent · **Size:** S–M

[`TypeChangeConflictResolver`](../merge-java/src/main/java/com/codebuddy/merge/TypeChangeConflictResolver.java)
still carries a hardcoded JDK name table, and its own plan says replacing it with real supertype
resolution "needs no new capability" — `ResolvedTypeReader` and `TypeContext` already exist and are
already required by `OverloadAddConflictResolver`.

**Do:** resolve the widening question through the type context, delete the table, and keep the
resolver's diagnostics identical for the cases the table used to answer.

**Gate:** `MODULE` for `merge-java` green; the existing resolver tests are the regression net, and the
commit message says which table entries became unreachable.

**Done when:** no JDK type-name list remains in the resolver.

### 4.2 — Phase 13, step 1: the read-only per-conflict review render
**Who:** agent · **Size:** M

Today a resolution is reported as a count. The user sees *that* something was resolved, never *what was
decided and why* — the strategy, the explanation, the fix paths not taken, the verification outcome, the
sticky decisions replayed.

**Do:** render exactly that per conflict — base / branch 1 / branch 2 beside the resolved code, with the
resolver's explanation and fix paths — as a Bun renderer over the JSON
[`MergeReportWriter`](../merge-java/scripts/merge-report/render.js) already writes, following DEC-027/029
(one self-contained framework-free HTML file, every link verified before it is written, output under the
module's `.jcodebuddy/`). Read-only first: reviewing what the tool did must not require trusting it.

**Gate:** the renderer's own test (`merge-java/scripts/*.test.js` is the existing pattern) plus
`MODULE` for `merge-java` green.

**Done when:** a resolution can be reviewed after the fact, with the reason attached.

### 4.3 — Phase 13, step 2: the action display, writing back to `BranchConflictStore`
**Who:** agent · **Size:** M

The manual cases already carry machine-readable fix paths (named options, a recommendation, a
justification, an impact). The review display becomes an action display: pick a fix path, edit the
proposed result, accept — and the decision flows back into `BranchConflictStore` so it replays like any
other sticky choice on the next update.

**Gate:** a test that a choice made through the interface is replayed on the next merge run, and
`MODULE` for `merge-java` green.

**Done when:** a decided conflict stops being decided twice.

### 4.4 — Phase 13, step 3: the LLM as proposer, never applier
**Who:** agent (needs a model endpoint) · **Size:** M

The same interface can hand a conflict to an LLM and show its analysis as **one more fix path** — subject
to the same verification gate and the same human decision. The boundary in
[`DESIGN_NEVER_AUTO_RESOLVED.md`](../merge-java/DESIGN_NEVER_AUTO_RESOLVED.md) holds unchanged.

**Gate:** a test that an LLM proposal which fails the verification gate is refused and cannot be applied
without an explicit human accept.

**Done when:** the proposal path exists and cannot bypass the gate.

---

## 9. Phase 5 — webview: close the suite

### 5.1 — Phase 6: headless parity as a build gate
**Who:** agent · **Size:** M

[`PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md) Phase 6 asks that "headless lacks nothing" be
a **test result** rather than a README claim. Today
[`smoke-test.mjs`](../webview/kit/examples/smoke-test.mjs) contains no reference to `webviewd`,
`applyEdit` or `undo`, so it drives none of the verbs.

**Do:** extend it (CDP, no dependencies) to drive **every** verb against a headless
[`webviewd`](../webview/core/webviewd) and assert the result, and add the JetBrains/VS Code capability
declarations to the same test so a host that declares a verb it does not implement fails the build.
Point the parity claim in [`webview/README.md`](../webview/README.md) at the test that proves it.

**Gate:** the extended test runs green, and `bun scripts/mvn-jdk25.js -pl webview/core/webviewd -am verify`
plus `node webview/check-links.mjs` are green.

**Done when:** the parity claim is a test result.

### 5.2 — Record the two open questions as decisions
**Who:** agent + maintainer · **Size:** S

Q2 ("which hosts are in scope for the write verbs") is answered by delivery — every host has them — so it
only needs the plan to say so. **Q3** (do the JetBrains and VS Code plugins become proxying adapters, or
keep their in-process implementations and share only the core?) and **Q5** (do the `vscode-jwa` /
`vscode-jswa` / `intellij-jwa` / `intellij-jswa` clients also move under `webview/`?) are still
unanswered, and each changes a boundary.

**Do:** put both to the maintainer, then record the answers where they bind: Q3 in the webview host API
document (or a DEC if it changes the boundary), Q5 in the plan and in
[`doc/architecture/module-map.md`](../doc/architecture/module-map.md) if the answer moves modules.
Update the plan's § 10 with the answers and drop the questions that are settled.

**Gate:** `LINKS` green; the plan's status line no longer lists Q3/Q5 as open.

**Done when:** no open question in the plan lacks an answer or a named owner.

### 5.3 — The ACP go/no-go spike (human)
**Who:** human (Zed 1.21, from a normal shell) · **Size:** S

Phase 5's spike "ends in a written go/no-go" and no such document exists. Run it as the plan describes
(`webviewd --acp` registered as a custom agent, `initialize` / `session/new` / `session/prompt`, one tool
calling the same command layer as `/api/v1`), then write the verdict — including "no, and here is why" —
into the plan's Phase 5 record.

**Gate:** the written verdict exists and the plan links to it.

**Done when:** ACP is either scheduled as its own plan or dropped with a reason.

### 5.4 — Eclipse Phase 5: the p2 update site (optional, gated on Q2)
**Who:** agent, after a human answers Q2 · **Size:** M

**Do not start this before Q2 is answered**: if a p2 update site is required from day one, this phase
moves ahead of the observations; if `dropins/` is acceptable, it stays optional and last. When it is
taken: a Tycho build of a feature + p2 update site (`category.xml`) as a **separate Maven profile**, so
the ordinary offline test loop keeps working and the packaging never becomes the thing that makes the
host buildable.

**Gate:** a clean Eclipse installs the host from the generated site with `dropins/` empty, and the Phase 1
navigation observation repeats against that install. `PLAN-eclipse-host.md`'s Phase 5 and Q2 record the
outcome either way.

**Done when:** either the site is delivered, or Q2 was answered "dropins" and the phase is closed as
declined.

---

## 10. Phase 6 — hipster-entity roadmap rows

Each of these is a row of
[`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) § 1 or a `Proposed`
row of its § 3. Close them in the tracker in the same commit.

### 6.1 — `FieldAnnotation` exposure in generated view enums
**Who:** agent · **Size:** M

The row is open and nothing named `FieldAnnotation` exists in the tree. Decide the shape first (a
generated enum constant carrying its annotations as metadata), because it changes generated output — a
DEC-022 naming-contract table entry comes with it.

**Gate:** `GATE` green, the generated example carries the annotations, and the tracker row says what
landed.

### 6.2 — Deep change tracking: the generator wiring (task 6.5) and a patch applier
**Who:** agent · **Size:** L

[DEC-024](../doc-hipster-entity/architecture/decisions/DEC-024.md)'s runtime half landed; the delivery
note says the **generator** wiring (task 6.5) and a patch applier are not part of it.

**Gate:** a generated view exposes the deep-tracking path end to end, the RFC-6902-like patch can be
applied, and `GATE` is green.

### 6.3 — Decide contract enforcement: advisory rules → hard failures
**Who:** agent + maintainer · **Size:** M

Two rows ask the same question: whether the marker/interface conventions become **enforceable** rather
than advisory. Today `--validate` reports and `--validate=STRICT` fails, and the example's build runs the
former. Decide per rule (the core entity contract; the view hierarchy naming rule), record the decision,
and make the example's build run whichever mode was chosen — a rule that is never fatal is a suggestion,
and saying so is the point.

**Gate:** the decision is recorded (DEC or tracker), and the example's build exercises the chosen mode.

### 6.4 — Type divergence analyzer + converter manifest generation
**Who:** agent · **Size:** L

Open, and unimplemented: no `TypeDivergence` type and no converter manifest exist. DEC-006 is the
governing decision (`Proposed`); this step starts by either accepting it or amending it, since it fixes
the diagnostics and the registry shape.

**Gate:** the analyzer reports divergence in DEC-022's format for the example module, the manifest is
generated and committed, and `GATE` is green.

### 6.5 — Projection + DTO marker pattern for SQL/NoSQL direct JSON output
**Who:** agent · **Size:** L

Open; DEC-003 and DEC-007 are both `Proposed` and both need their adapter shape and benchmark criteria
settled first.

**Gate:** the pattern is implemented on the example module, its DTO path is asserted by a test, and the
two decisions are updated to match what was built.

---

## 11. Phase 7 — cross-cutting leftovers

### 7.1 — jwa-sidecar: read the client's indentation
**Who:** agent · **Size:** S

The sidecar advertises incremental sync but never reads the client's formatting settings: a grep for
`tabSize` / `insertSpaces` / `formatting` across `webview/jwa-sidecar` finds nothing, so the generated
members use the indent the engine was constructed with
([`BuilderTransformationEngine(String indent)`](../jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java),
default 4 spaces).

**Do:** read `tabSize`/`insertSpaces` from the client (LSP `FormattingOptions` on the request, or
`workspace/didChangeConfiguration`) and pass the resulting indent step to the engine for every code
action. Same family as [`todo.java_watch2.md`](../todo.java_watch2.md)'s "custom indentation in
toolsets".

**Gate:** a test that a two-space client configuration produces two-space output; `MODULE` for
`jwa-sidecar` green.

### 7.2 — java-watch-agent: the remote-jump front-end
**Who:** agent · **Size:** S

The sidecar's `/jump` endpoint, its token/origin gate and its loopback bind all exist; the agent's web UI
never calls it (`java-watch-agent/src/main/resources/web/` has no `jump` and no `7979`).

**Do:** make the dashboard send the jump request with the token the sidecar requires, and show the
outcome (the sidecar reports a navigation *outcome*, not an assumed success, since 2026-09-25).

**Gate:** a test or a scripted end-to-end run against a live sidecar; the dashboard's own test stays
green.

### 7.3 — `View1Builder.merge(View2 other)`, and its proxy version
**Who:** agent · **Size:** M

[`todo.hipster-entity.md`](../todo.hipster-entity.md)'s open item: no `merge(` method exists in
`hipster-entity-core` or `hipster-entity-example`. `ViewMapperGenerator` is a different shape
(source→target conversion), and the build-time rule (a builder never returns a partially built instance)
has to be respected.

**Do:** generate the merge for fields with identical name **and** type, with the proxy variant, and a
diagnostic in DEC-022's format for a field that matches by name but not by type.

**Gate:** `GATE` green, with a test over a view pair that shares some fields and not others.

### 7.4 — The documentation front door
**Who:** agent · **Size:** S

[`doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md) items 12–13: the user guide
exists under [`doc-hipster-entity/user/`](../doc-hipster-entity/user/README.md) but the root
[`README.md`](../README.md) links none of it, and the architecture docs do not link back into it. A new
reader cannot reach `getting-started.md` or `why-hipster-entity.md` from the front page.

**Do:** add the front door (a short section that routes library users to the guide and library devs to
the architecture half), add the reverse cross-references, and update the plan's banner to say the two
items are closed.

**Gate:** `LINKS` green.

### 7.5 — java-watch-agent: the OpenRewrite-based tool prototype
**Who:** agent · **Size:** M

Phase 4's second box. Nothing under `java-watch-agent/` references OpenRewrite today. It must be built
the repository's way (DEC-030): read through `SourceReader`, query through `TreeQueries`, splice text —
not parse with a second parser and not reprint a tree.

**Gate:** one real tool (the plan's own example — a rename or a migration recipe) runs from the agent's
menu against a sample and is asserted by a test.

### 7.6 — Decide the three remaining `todo.java_watch2.md` items
**Who:** agent + maintainer · **Size:** S

Each is a decision, and silence is the only wrong outcome:

- **A configurable delay for delete events** — a debounce exists and is configurable
  ([`BatchedFileWatcher`](../java-watch-core/src/main/java/hr/hrg/watch2/core/BatchedFileWatcher.java),
  `ManagedFileWatcher`, `java-watch-scp`'s `watch_delay_ms`); a *delete-specific* delay does not. Decide
  whether one is wanted, and record why.
- **A memory-based LocalDB** — `local_db` today chooses *where the `.scpdb` file lives*, not whether the
  check database stays in memory. Decide, and record the measurement that justifies it.
- **The Zig port of the hashing logic** — there is no `*.zig` anywhere. Decide whether this is a real
  goal or a learning exercise that has served its purpose, and either schedule it or delete it.

**Gate:** each item in [`todo.java_watch2.md`](../todo.java_watch2.md) is either scheduled or struck
through with a reason; nothing stays silently unchecked.

### 7.7 — The manual-mode CLI for DEC-W008 (`jcodebuddy metadata parse <file>`)
**Who:** agent · **Size:** S

DEC-W008 requires a CLI entry point that calls `parse` directly and prints the resulting metadata, working
in a fresh checkout with no daemon, no cache folder and no prior `scan`. Step 1.2 built the method and the
RPC/MCP routes; the CLI is the piece the decision names and nothing implements — there is no `metadata`
command anywhere in the tree, which is why DEC-W008's amendment lists it as open.

**Do:** add the command where DEC-W008 says it belongs (`project-automation`, which already hosts
`MetadataAnalysisRunner` and the provider). It prints the entry as JSON on stdout and exits non-zero when
the file cannot be read. If it needs a launcher, that launcher is Bun JavaScript with JDK 25 pinned
(AGENTS.md § 2: the script is JavaScript, the build step is Maven) — never a `.cmd`/`.sh`.

**Gate:** the command runs against a file in a clean checkout with no `.jcodebuddy/` present, its output
round-trips as the same JSON the RPC returns, and `GATE` stays green.

**Done when:** DEC-W008's manual-mode paragraph is true and its status note drops the CLI from its
"not implemented" list.

---

## 12. Phase 8 — human-gated observations (no code; a checklist)

These are not "later"; they are the only deliverable that needs a person. Each ends in a dated,
version-named record, because a claim that is not observed is not a claim
([`doc/ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md)).

| # | What | Who | Record lands in |
| --- | --- | --- | --- |
| 8.1 | JetBrains § 8's five maintainer questions (vendor identity, the empty-allow-list default, one vs two settings services, dropping Kotlin, plan location) and acceptance criteria 2/5/7 in a running IDE | maintainer | [`plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) § 8, and `webview-jetbrains`' own docs |
| 8.2 | Eclipse 4.41 workbench observations — the caret landing, the unsaved buffer edit and the single `Ctrl+Z`, the dropins install layout, the two-live-hosts claim; then answer **Q2** (dropins vs p2) | maintainer | [`ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md) § 1a/§ 2a, then `PLAN-eclipse-host.md` |
| 8.3 | `java-watch-agent` Phase 4's lightweight IntelliJ/VS Code hooks (the existing `intellij-jwa`/`vscode-jwa` are sidecar clients, not these) | maintainer decides, agent implements | [`java-watch-agent/plan.md`](../java-watch-agent/plan.md) |
| 8.4 | The ACP spike's Zed run (see step 5.3) | maintainer | `PLAN-webview-suite.md` Phase 5 record |

---

## 13. Phase 9 — CLEANUP (the final step)

Do this **after** the steps above are done or explicitly closed, never before: a plan is deleted when its
content lives somewhere better, not when it is inconvenient.

### 9.1 — Coverage check before anything is removed
**Who:** agent · **Size:** S

Re-read every document in [§ 3](#3-what-this-plan-schedules) and confirm that each open item in it is
either a step here, done, or explicitly dropped. Anything not accounted for gets a step **before** the
deletions below.

**Gate:** the checklist in [§ 3](#3-what-this-plan-schedules) is annotated: every row either "closed into
step N" or "done".

### 9.2 — Archive the superseded plans
**Who:** agent · **Size:** S

Move, do not delete — the repository's convention is
[`archive/`](../archive) (it already holds the retired root POMs and `FORY-SERIALIZATION-ISSUES.md`):

1. `git mv doc/continuation-plan-legacy.md archive/plans/continuation-plan-legacy.md`
2. `git mv doc-hipster-entity/roadmap/plan-for-continuation.md archive/plans/hipster-entity-plan-for-continuation.md`
3. Rewrite every relative link in the moved files (they were written for their old depth) and every link
   **to** them; a moved file is a link change, never just a file change.
4. **Leave [`plans/rewrite-migration/`](rewrite-migration/README.md) where it is.** It is not a plan to
   clean up: its README is the delivery record, `scripts/rewrite-migration/verify-migration.js` and the
   honest-docs check read it, and it is linked from the tree. It already carries the "plan as written"
   banner (added 2026-10-01).
5. Same treatment for [`plans/inject-examples-feature-port.md`](inject-examples-feature-port.md) if the
   port report's historical value is not worth the front-page clutter — otherwise leave it with its
   status header.

**Gate:** `LINKS` green, and `git status` shows the moves as renames (not delete+add).

### 9.3 — Remove the local scratch
**Who:** agent · **Size:** S

These are not this repository's content and never were:

- **`.kilo/plans/*`** (six files, gitignored): their open items are now steps 1.2–1.4, 2.1–2.2 and
  3.1–3.3. Delete them — but only after step 9.1 confirms nothing is left behind, because once deleted
  they are gone from the working tree (git never had them).
- **`.kilo/worktrees/snapdragon-motorcycle`** — a stale Kilo worktree, clean, detached at `0c5f200`
  (eight commits behind `main`). Confirm it is clean, then `git worktree remove` it and `git worktree
  prune`; check `git worktree list` afterwards.
- **Root zero-byte files `explicit` and `hipster-entity`** — debris from a mangled command line
  (2026-09-20). Verify they are empty, then delete them.

**Gate:** `git status` clean of scratch; `git worktree list` shows only the main checkout.

### 9.4 — Retire the per-plan "open list" sections
**Who:** agent · **Size:** S

The lists that this plan absorbed must stop reading as live work:

- [`todo.hipster-entity.md`](../todo.hipster-entity.md) and [`todo.java_watch2.md`](../todo.java_watch2.md)
  — after steps 7.3 and 7.6, delete them, or leave each with a one-line pointer to this plan.
- [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md)'s "Future Refinement" and
  [`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) § 8
  — after steps 7.1 and 8.1, replace the lists with the outcome ("both delivered in commit X" /
  "answered 2026-XX-XX, recorded here").
- [`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) § 1 rows — tick the
  ones steps 6.1–6.5 closed; a tracker whose rows outlive their work is the same defect this plan exists
  to fix.

**Gate:** no document in the repository lists an open item that is not in this plan's table or in
`proto/`.

### 9.5 — The full sweep
**Who:** agent · **Size:** S

**Gate:** all of these green, in one run, after the moves:

```
bun scripts/mvn-jdk25.js          # GATE
node scripts/check-repo-links.mjs # LINKS
node webview/check-links.mjs      # LINKS
npm run check:examples            # EXAMPLES
```

### 9.6 — Close the books
**Who:** agent · **Size:** S

1. Tick every completed box in [§ Progress](#progress) and mark the plan's status line.
2. Update [`plans/README.md`](README.md) — it already says what lives in `plans/`; add the outcome line
   for this schedule (closed on <date> / still live) so the directory index and the plan agree.
3. If every step is closed or explicitly dropped: `git mv plans/unified-plan.md archive/plans/unified-plan.md`
   with a final status block recording the dates, and leave a pointer to it from `plans/README.md`. If any
   step is still open, the plan **stays** where it is; a schedule with open steps is a live document.
4. Remove the "scheduled in `plans/unified-plan.md`" pointers from the documents whose steps are now
   closed, so the cross-links do not outlive the schedule.

**Done when:** the repository has one place that says what is open, and it says "nothing".

---

## Progress

Legend: `[ ]` open · `[x]` done · `[~]` blocked (say why) · `[-]` dropped (say why)

| Step | What | Who | Size | State |
| --- | --- | --- | --- | --- |
| 0.1 | Commit the EEnumSet overlap JMH delivery | agent | S | `[x]` (landed as `ff0dc49`, with 0.2, by the maintainer) |
| 0.2 | Commit the stale-document corrections | agent | S | `[x]` (landed as `ff0dc49`) |
| 1.1 | Honour `enabled: false` (DEC-018 / DEC-021 § 6) | agent | M | `[x]` |
| 1.2 | `MetadataProvider.parse` (DEC-W008) | agent | M | `[x]` |
| 1.3 | `WatchMetadataProvider` over the watch cache | agent | M | `[x]` |
| 1.4 | Test the MCP tool surface | agent | S | `[x]` |
| 2.1 | metadata-arena unit tests | agent | M | `[x]` |
| 2.2 | metadata-arena JMH benchmarks (or close as not needed) | agent | S–M | `[x]` |
| 2.3 | Decision-grade arena run + the backend decision | agent | S | `[ ]` |
| 3.1 | The hipster-ioc ADR | agent | S | `[x]` |
| 3.2 | `CodeGenerator<GeneratedContext>` + dependency graph | agent | L | `[ ]` |
| 3.3 | Make the hipster-ioc generator runnable and documented | agent | M | `[ ]` |
| 4.1 | Replace `WIDENING_CHAINS` with supertype resolution | agent | S–M | `[ ]` |
| 4.2 | merge-java Phase 13 step 1 — review render | agent | M | `[ ]` |
| 4.3 | merge-java Phase 13 step 2 — action display + sticky decisions | agent | M | `[ ]` |
| 4.4 | merge-java Phase 13 step 3 — LLM proposer behind the gate | agent | M | `[ ]` |
| 5.1 | webview Phase 6 — headless parity as a build gate | agent | M | `[ ]` |
| 5.2 | Record the webview Q3/Q5 answers (Q2 by delivery) | agent + maintainer | S | `[ ]` |
| 5.3 | ACP go/no-go spike | human | S | `[ ]` |
| 5.4 | Eclipse Phase 5 — p2 update site (after Q2) | agent | M | `[ ]` |
| 6.1 | `FieldAnnotation` exposure in view enums | agent | M | `[ ]` |
| 6.2 | Deep tracking: generator wiring 6.5 + patch applier | agent | L | `[ ]` |
| 6.3 | Decide advisory → hard rule enforcement | agent + maintainer | M | `[ ]` |
| 6.4 | Type divergence analyzer + converter manifest (DEC-006) | agent | L | `[ ]` |
| 6.5 | Projection/DTO marker pattern (DEC-003/DEC-007) | agent | L | `[ ]` |
| 7.1 | jwa-sidecar reads the client's indentation | agent | S | `[ ]` |
| 7.2 | Agent web UI remote-jump front-end | agent | S | `[ ]` |
| 7.3 | `View1Builder.merge` + proxy merge | agent | M | `[ ]` |
| 7.4 | Documentation front door + cross-references | agent | S | `[ ]` |
| 7.5 | Agent OpenRewrite tool prototype | agent | M | `[ ]` |
| 7.6 | Decide the three `todo.java_watch2.md` remainders | agent + maintainer | S | `[ ]` |
| 7.7 | Manual-mode CLI for DEC-W008 (`metadata parse`) | agent | S | `[ ]` |
| 8.1 | JetBrains maintainer questions + IDE observations | human | — | `[ ]` |
| 8.2 | Eclipse observations, then Q2 | human | — | `[ ]` |
| 8.3 | Agent IDE hooks | human decides | — | `[ ]` |
| 8.4 | Zed ACP run | human | — | `[ ]` |
| 9.1 | Coverage check | agent | S | `[ ]` |
| 9.2 | Archive the superseded plans | agent | S | `[ ]` |
| 9.3 | Remove local scratch (`.kilo` plans, worktree, stray files) | agent | S | `[ ]` |
| 9.4 | Retire the per-plan open lists | agent | S | `[ ]` |
| 9.5 | Full sweep (gate + links + examples) | agent | S | `[ ]` |
| 9.6 | Close the books | agent | S | `[ ]` |
