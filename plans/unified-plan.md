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
   (DEC-027/028/029): every link verified before it is written. What the page is *built with* depends on
   what it is, per DEC-027's 2026-10-01 amendment — a page with **no UI or minimal UI** is one
   self-contained file of framework-free vanilla JavaScript, an **interactive or advanced page** is a
   **`jsx6`** page, and anything showing **relations or a diagram** uses **`jsx6`/`nodditor`** (rule 9).
8. **Generators come in two kinds, and the kind is declared.** A **file-scoped** generator reads only the
   file it is handed (`CodeContext.getFilePath()`) and its output is a function of that file: it may be
   offered to every file, one at a time, in any order, from a saved buffer with no tree around it. A
   **project-scoped** generator needs the project's *type relations* — who extends whom, who implements
   what, what is assignable — which no single file contains, so its input is the project's **metadata**
   (the engine's metadata, DEC-037 and steps 3.0a–3.0k) and never a lone `CodeContext`: it is run as its own pass and **never
   offered per-file** by a caller that believes it is asking about one file.

   Two clarifications that using this rule forced, because the earlier phrasing of it was wrong:

   - **The kinds are not two shades of one thing.** A generator that needs relations is not a file
     generator with a bigger appetite; it is a different kind of program. hipster-ioc cannot work on single
     files at all — which is why its prototype's `CodeGenerator` implementation is a category error that
     step 3.0e removes rather than re-labels;
   - **a generator's kind is what it reads; a pass's kind is what it writes.** The IoC generator returns
     text and `IocGeneration` writes the graph, so "needs another file *or* writes a tree artifact" would
     have called one generator two kinds depending on which half you looked at.

   The failure mode the rule exists for is specific: a generator that reads only its own file when it
   needed the project does not fail, it *infers an absence*. The hipster-ioc generator did exactly that in
   step 3.2 — it looked for the module interface inside the context's own compilation unit, found nothing,
   and reported no factories, producing an empty-wiring implementation rather than an error, because a file
   it never read looked like a file with nothing in it. So **metadata that cannot be resolved is reported,
   never inferred as absent**. The tree shows both kinds: the entity generator takes a source root and a
   package list and writes Java plus metadata JSON (project-scoped by construction, no per-file entry point
   to misuse), while `IocContextGenerator` wears the file-scoped SPI and reads whatever sits beside the
   context. Enforcement: steps 3.0a–3.0k (the engine, DEC-037) and 7.8.
9. **Any UI built uses `jsx6`, from a local checkout whose own `AGENTS.md` is the authority.** The
   instruction and its download directions are kept verbatim in
   [`AGENTS.md` § 2](../AGENTS.md) (the jsx6 bullet) — clone <https://github.com/hrgdavor/jsx6> into a
   temporary folder with git so it can be updated on demand to the latest, then read `AGENTS.md` from the
   jsx6 root and use jsx6 for UI. This plan does not restate jsx6's guidance; the checkout you actually
   read is what counts, and it is a **moving dependency** rather than a pinned version. The temporary
   folder is **not `target/`** — the recorded gate runs `clean test`, which would delete a checkout there
   on every run — and defaults to `<repo>/.jsx6/` (`JCODEBUDDY_JSX6_DIR` overrides). Enforcement: steps
   7.9–7.10.

   **How this meets rule 7 — a split, decided 2026-10-01 (DEC-027's amendment).** A page with **no UI or
   minimal UI** keeps the vanilla-JS rule; an **interactive or advanced page** — project-structure
   navigation, per-item review or accept workflows, state beyond a document, anything expected to be
   improved further as UI — **must** be `jsx6`; **relations and diagrams** use `jsx6`/`nodditor`. The
   no-bundler/no-`node_modules` clause does not bind the `jsx6` pages; **no network at view time** still
   does. The boundary is *further improvement*, not size: a page nobody intends to grow is the minimal
   case, and a page expected to acquire navigation or review state is an application from the start.

   **A capability either library lacks is reported, never worked around.** Minor gap → an improvement to
   be made in `jsx6` or `nodditor`, and the finding is worth more there than a workaround here; critical
   gap → the library cannot serve that specific output, so it is a decision about an additional library,
   taken with the evidence and recorded as its own decision rather than assumed. That is step 7.10's
   deliverable, not a note.
10. **The engine parses and analyses; a consumer projects.** `jcodebuddy-core` owns parsing and analysis in
   one place — the one source representation (DEC-030), the rich general model, the indexes with their
   relations, search and freshness — so that **no consumer duplicates the effort**. A consumer owns its
   **domain shape**: hipster-entity defines and serialises its own entity and relation data (the JSON a JS UI
   or a visual entity/relations page reads), because that is a *projection* of the engine's facts. What stays
   forbidden is a consumer parsing Java itself, running its own watch loop, or keeping its own type index
   because the engine's answer was inconvenient — and a fact a consumer needs three times is a signal to widen
   the engine's model rather than to derive it locally. The test for any change: **does it derive a fact from
   source (engine), or project a fact the engine already answers (consumer)?** DEC-037's "what a consumer
   owns" section is the decision this rule restates. Enforcement: steps 3.0f-3, 3.0g–3.0j, and every
   hipster-entity step that reads the engine.

---

## 3. What this plan schedules

| Document | What it contributed here | Its own state after this plan |
| --- | --- | --- |
| [`plans/enumset-overlap-jmh-plan.md`](enumset-overlap-jmh-plan.md) | step 0.1 — land the finished work | closed |
| the stale-document pass of 2026-10-01 | step 0.2 — commit it | closed |
| [`doc-hipster-entity/architecture/decisions/DEC-021.md`](../doc-hipster-entity/architecture/decisions/DEC-021.md) § 6 | step 1.1 — `enabled: false` | note removed when the step lands |
| [`doc-hipster-entity/architecture/decisions/DEC-W008.md`](../doc/architecture/decisions-watch/DEC-W008.md), the `.kilo` metadata-server plan | steps 1.2–1.4 | closed into steps |
| the `.kilo` metadata-arena plan | steps 2.1–2.2 | closed into steps |
| the `.kilo` hipster-ioc-integration plan, [`hipster-ioc/doc/ROADMAP.md`](../hipster-ioc/doc/ROADMAP.md), and [DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) | steps 3.0a–3.0k (the one metadata engine in `jcodebuddy-core`, then moving this generator onto it **as one consumer**); steps 3.1–3.3 as a **prototype**; steps 3.4–3.11 are `[TBD]` until the shape is decided | the prototype is delivered; Phase 3's banner says what "prototyping" means, why the rest waits, that hipster-ioc is project-wide and does not extract metadata, and that the engine comes before the consumers |
| [`merge-java/IMPLEMENTATION_PLAN.md`](../merge-java/IMPLEMENTATION_PLAN.md) | steps 4.1–4.4 | Phase 9 residue + Phase 13 closed |
| [`webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md) | steps 5.1–5.3 | Phase 6 and Q3/Q5 closed |
| [`webview/PLAN-eclipse-host.md`](../webview/PLAN-eclipse-host.md) | steps 5.4, 8.2 | Phase 5 + observations closed |
| [`doc-hipster-entity/roadmap/README.md`](../doc-hipster-entity/roadmap/README.md) | steps 6.1–6.5 | open rows closed |
| [`webview/jwa-sidecar/plan.md`](../webview/jwa-sidecar/plan.md), [`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md), [`todo.hipster-entity.md`](../todo.hipster-entity.md), [`todo.java_watch2.md`](../todo.java_watch2.md), [`doc-hipster-entity/doc-separation-plan.md`](../doc-hipster-entity/doc-separation-plan.md), [`webview/webview-jetbrains/plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) | steps 7.1–7.6, 8.1, 8.3 | "Future Refinement"/"Open questions" lists emptied |
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
[`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md) (the two Phase 4 boxes),
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
- `jcodebuddy/metadata-arena/pom.xml` gained the `jmh` profile (`-proc:full`, without which the harness is never
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

**Done 2026-10-01 — run at the default profile, and the decision is in DEC-W009's implementation note.**

- **The run**: `bun run scripts/run-jmh.js --include ".*ArenaIndexJmhBenchmark.*"` — 3 forks, 6 × 2 s warmup,
  8 × 2 s measurement, JMH 1.37, **22 min 29 s**, written to `target/jmh/results.json`. The profile is
  verified **from the JSON itself** (`forks: 3`, `warmupIterations: 6`, `warmupTime: 2 s`,
  `measurementIterations: 8`, `measurementTime: 2 s`) rather than from the absence of a console line, and
  `results-smoke.json` — the marker of a run that must not be recorded — does not exist.
- **Step 2.2's hypothesis is now a finding, and it is large.** `ByteBufferArena` is ~10× `FfmArena` on the
  hot path: `getHot` 121 774 vs 11 989 ops/ms at 1 000 entries and 127 584 vs 10 989 at 100 000; `getRandom`
  86 943 vs 10 722 and 50 281 vs 9 942; `rebuild` 92.8 vs 7.3 and 0.579 vs 0.071. The intervals do not come
  within a factor of four of meeting. The **cause** stays a hypothesis (no profiler was run) — what is
  established is direction and magnitude.
- **The one mode where they are equal is the cold start**: `mmapLoad` 171 µs vs 180 µs at 1 000 and 4.72 ms
  vs 4.06 ms at 100 000, intervals overlapping — consistent with a path dominated by mapping and page
  faults rather than by the accessor.
- **Decision 1 — the allocator is `ByteBufferArena`, with `FfmArena` kept for the two cases a `ByteBuffer`
  cannot serve**: size (int-indexed, 2 GiB per arena, which binds at roughly 30–40 million entries) and the
  platform's own direction. `sun.misc.Unsafe` is rejected outright and `Chronicle Bytes` is rejected as
  buying nothing — both named options answered rather than deferred.
- **Decision 2 — rebuild on the existing debounced batch, and incremental updates are not needed for
  latency.** The watcher's debounce is 300 ms by default (`HotSwapDaemon.DEFAULT_DEBOUNCE_MS`); a full
  rebuild is 10.8 µs at 1 000 entries and **1.73 ms at 100 000** (0.6 % of the budget), ~17 ms at a million
  by the linear 17.3 ns/entry figure. A batch's budget holds ≈ 17 million entries on ByteBuffer and ≈ 2.1
  million on FFM. The constraint is therefore not the rebuild but the **arena size**, which is why the note
  gives the sizing formula (`HEADER + 16 × capacity + 12 × entries + slack`, capacity the next power of two
  at or above twice the entries) with its per-size figures — that is the third follow-up answered too.
- **One irregularity, disclosed rather than smoothed over**: `getRandom/bytebuffer/1000` produced **5, 5 and
  6** measurement iterations per fork instead of 8/8/8 — uniform across its three forks, no error in the
  JSON, cause not established (the console output was not kept). Every other case is complete. It does not
  carry the decision: its 100 000-entry sibling is complete, and even its wider interval (±10 400 on
  86 943) leaves the gap to FFM's 10 722 intact.
- **Recorded in DEC-W009, not in a README**: the record's implementation note carries the full table, both
  decisions, the sizing rule and a "what this run does not establish" section; its three follow-ups are
  marked settled and a fourth was added (the unexplained FFM gap — anything that puts FFM on a default path
  owes a profile first). `jcodebuddy/metadata-arena/README.md` keeps its rule that numbers live where the decision is
  and now points at that note.

**Gate:** ✅ the numbers come from a default-profile run (the profile recorded inside `results.json`, no
`results-smoke.json`, 24 samples per case in 15 of 16) and the decision — backend, why, and the batch
answer — is written into DEC-W009's implementation note.

---

## 7. Phase 3 — hipster-ioc: **PROTOTYPING** — a project-wide generator that consumes metadata

> **hipster-ioc cannot work on single files.** It is a **project-wide** generator: what it needs is the
> project's *type relations* — who extends whom, who implements what, which types are assignable — and no
> single file contains that. Reading a file and following what it happens to reference is not a smaller
> version of the job; it is a different job that produces confident wrong answers when the reference points
> outside the file, which is exactly what the prototype did on its first implementation (it looked for the
> module interface in the context's own compilation unit, found nothing, and emitted wiring with no
> factories rather than an error — step 3.2).
>
> **Extracting metadata is not hipster-ioc's job.** hipster-ioc *consumes* metadata to produce IoC code.
> Extraction, caching and indexing belong to the metadata side, and since 2026-10-02 that side is **one
> engine in `jcodebuddy-core`** ([DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md)) rather
> than the four modules that hold the pieces today: [`metadata-server`](../jcodebuddy/metadata-server)'s providers and
> RPC/MCP surfaces, the **class index** (`hipster-entity-tooling`'s `ClassIndex`/`ClassRecord`, DEC-029) with
> per-file checksums (`ContentHash`), [`metadata-arena`](../jcodebuddy/metadata-arena) for index storage, and
> `java-watch-core` for the watch loop. What is missing is the **engine and its contract** — steps 3.0a–3.0k
> below, which come *before* any consumer work, because a consumer migrating onto a moving model moves twice.
>
> > **This phase is not a delivery, and its steps are not a contract.** hipster-ioc is still in
> > **prototyping**: the point of the work here is to *find the shape* of the generated code, and that shape
> > is not settled. [DEC-036](../doc-hipster-entity/architecture/decisions/DEC-036.md) is `Trial`, the
> > generator emits one shape today, and some of the decisions it records are clauses nothing implements
> > yet. The prototype's own `CodeGenerator` implementation — a **per-file SPI** — is a category error kept
> > only as a shortcut; step 3.0e is what removes it.
> >
> > So this phase follows different rules from every other one in this file:
> >
> > - a step here is **shape-defining** when it changes what the generator emits. Its output is a prototype —
> >   expected to be rewritten — and the committed generated file in `hipster-ioc-test` is a sample, not a
> >   contract;
> > - a step whose content **depends on the shape being settled** is marked **`[TBD]`**: deliberately
> >   unscheduled, with the decision it waits on named, rather than written as though the shape were known.
> >   A `[TBD]` step that quietly becomes "implement whatever the generator emits today" is the failure this
> >   phase can have, because it would freeze the prototype by accident — the shape would be decided by
> >   nobody, in code, with no record saying so;
> > - **the way out is a decision, not a date.** When the shape stops changing, DEC-036 moves from `Trial`
> >   to `Accepted` (or is superseded), and the steps below become ordinary steps with real gates. Until
> >   then a `[TBD]` row is a *known* item, not a forgotten one — an empty cell would be the latter.
>
> **Order in this phase:** 3.0a settles the engine decision's open points; 3.0b–3.0e are the metadata work
> this phase always needed (relations, freshness, one resolver, moving the prototype onto it); **3.0f–3.0k are
> the engine itself** ([DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md): one metadata engine
> in `jcodebuddy-core` as the backbone for codegen, analysis, reporting, the watch loop and an LSP sidecar);
> 3.1–3.3 are the prototype already delivered; 3.4–3.11 are `[TBD]`, and each one's "waits on" now names the
> metadata piece it needs where that is the real blocker.
>
> **The engine (DEC-037) reframes what "the metadata contract" means here.** It is no longer only the seam a
> generator reads: parsing, the model, the indexes (with relations), search and the watch loop that keeps them
> fresh and fires events are **one library in `jcodebuddy-core`**, and every other module is a consumer or a
> transport — `jcodebuddy-codegen-api` dissolves into it. So 3.0f–3.0k precede nothing in this phase but are
> the reason 3.0b–3.0e are worth doing once, in the right place: build the engine, then move consumers onto it
> rather than each consumer growing its own path. DEC-037 is `Proposed` until its three open points are
> settled by 3.0a.
>
> The step numbers `3.0a`–`3.0k` say *before 3.1* deliberately: this plan renumbers nothing, and these
> steps must land before the consumer ones to be worth anything.

### 3.0a — Settle the engine decision's open points (ADR first)
**Who:** agent + maintainer · **Size:** S–M

[DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) records the direction: one metadata engine
in `jcodebuddy-core` as the backbone for codegen, code analysis, code reporting, the watch loop and an LSP
sidecar; `jcodebuddy-codegen-api` dissolves into it; consumers never keep a private metadata path. It is
`Proposed` because three points are the maintainer's call and each changes code:

1. **What stays a leaf** — recommendation: the marker vocabulary and its parser (`GeneratedCodeMarkers`,
   `GeneratedCodeParser`, `GeneratedBlock`) move to a small leaf module, because the tools that read generated
   regions must not resolve OpenRewrite, which `jcodebuddy-core` gains the moment it is the engine. The
   alternative keeps the three types in the engine and gives that case the dependency the leaf exists to
   avoid.
2. **How far `metadata-server` is absorbed** — recommendation: keep it and re-point its providers at the
   engine, so DEC-W006–W009's serving shapes survive while their ownership of the model does not.
3. **What happens to `java-watch-core`'s watcher and checksums** — recommendation: it becomes the engine's
   freshness implementation (the engine owns the contract), because DEC-W006's cache and DEC-W009's rebuild
   protocol are exactly the freshness semantics the engine must publish.

**Do:** settle the three, flip DEC-037 from `Proposed` to `Accepted` (or amend it where the answer differs),
and update the records the answers change — [`module-map.md`](../doc/architecture/module-map.md) (which
today states `jcodebuddy-core`'s leaf property as its reason to exist, and the gate's own comment in
[`scripts/lib/gate.js`](../scripts/lib/gate.js) names it), DEC-029, and DEC-W006/W008/W009. Also record the
fact this step starts from, because it shapes every later step: **the recorded gate covers none of the
modules the migration touches** (`GATE_MODULES` is `jcodebuddy-core` plus the six `hipster-entity` modules),
so each migration step carries its own build/test evidence until 3.0k grows the gate.

**Done 2026-10-02 — every point settled, and the answer produced a second record.**

- The maintainer answered all of them: `jcodebuddy-core` holds the engine **and what consumers need**,
  `jcodebuddy-codegen-api` is merged into it "to simplify", the **markers move to a leaf**, and the watch side
  is `metadata-server` **renamed `jcodebuddy-meta`** with `java-watch*` staying **its own library** (the engine
  takes no watcher).
- **[DEC-037](../doc-hipster-entity/architecture/decisions/DEC-037.md) is `Accepted`** with both points
  answered, and the module layout they imply is **[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md)**
  (`Accepted`), which also absorbs the third thing the direction named: `jwa-builder`/`jwa-builder-api`.
- **The reconnaissance turned one of those into a finding worth the record**: there are **two
  `SourceSplicer` implementations** — `hipster-entity-tooling`'s and `jwa-builder`'s
  `hr.hrg.watch2.builder.SourceSplicer` (both package-private) — plus two read/position stacks, against
  DEC-030's "one representation, one splice path". So the `jwa-builder*` absorption is a **consolidation**,
  not a rename, and DEC-038 decision 5 makes one path survive.
- **Also recorded, because it shapes every step below**: `metadata-server`'s `groupId` is already
  `hr.hrg.jcodebuddy` while its package is `hr.hrg.watch2.server.metadata` (a half-rename waiting to happen),
  and the consumers of `jwa-builder*` — `java-watch-agent`, `project-automation`, `webview/jwa-sidecar` — are
  all **outside the recorded gate**.

**Gate:** ✅ DEC-037 is `Accepted` with every open point answered; DEC-038 carries the module layout; the
records that state the old shape are pointed at the new one
([`module-map.md`](../doc/architecture/module-map.md)'s "where this is going" note); `LINKS` green. (No code
gate: this step was a decision.)

**Done when:** 3.0f can start without a second decision — the engine's home, its leaf exceptions and its
consumers are all written down.

### 3.0f — The engine's skeleton in `jcodebuddy-core`, and the model it carries
**Who:** agent · **Size:** L

The generator-facing seam is **empty and too small**: `jcodebuddy-codegen-api`'s
[`TypeResolver`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/query/TypeResolver.java)
(`resolve(fqn)`) has no implementation but `EmptyTypeResolver`, and
[`TypeDefinition`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/query/TypeDefinition.java)
(qualified name, simple name, fields, field types) carries **no relations**, so it cannot answer the question
hipster-ioc actually has. That is a symptom: the model a consumer needs is spread across the tooling module
and two watch modules, and the SPI lives in a fifth. *(Those two links point where the types live today — the
engine's query seam; step 3.0i dissolved the module that held them and deleted it.)*

**Measured before starting (2026-10-02), so this step's real shape is visible:** `hipster-entity-tooling` is
**51 main sources** — 22 in the module's own package (the generators, the LST helpers, the divergence
reporter: the ones that need sorting into *engine* vs *consumer*), 12 in `meta/` (the representation and the
parse path: engine), 12 in `validation/` (the entity rules validators: consumer-side), 5 in `index/` (the
class index: engine) — plus **65 test sources**, and `jcodebuddy-codegen-api` is **5**, `jcodebuddy-core`
is **3 main + 3 test**. So most of this step is a **classification** ("is this the model, or is this a
generator reading it?"), and the move itself is small next to deciding what belongs where. Do not move a
package wholesale: `validation/` and the emitters stay consumers.

**The classification (2026-10-02), per file — the artefact this step starts with.** Read from the sources'
own summaries, not from their package names. It is deliberately not per package: `meta/` splits, and one
package-level guess in the measurement above was wrong (`meta/` is *not* all engine).

| Verdict | Files | Why |
| --- | --- | --- |
| **Engine → `jcodebuddy-core`** | `index/ClassIndex`, `index/ClassRecord`, `index/TypeFacts` | the class index: one row per type with kind, modifiers, enclosing, line (DEC-029). These are the engine's rows; 3.0b adds relations to them |
| **Engine** | `index/ContentHash`, `index/Wyhash64` | a file's content identity — the key the engine's invalidation is built on |
| **Engine** | `SourceReader` | the one place an existing source is read, through DEC-030's one representation |
| **Engine** | `TreeQueries` | the read-only queries over the parsed tree |
| **Engine** | `JavaSyntaxCheck` | the javac positions the LST cannot answer (DEC-030: positions come from javac) |
| **Engine** *(judgement call 1)* | `SourceSplicer` | the write half of the same one representation — see below, and see 3.0f-2's evidence |
| **Engine** | `meta/SourceMetadata` | the file-scoped metadata a parse produces (DEC-W008's shape) |
| **Engine** | `meta/SourceLocation` | "one place a field is, as a pass recorded it" — a position record, engine-shaped |
| **Consumer — corrected by the compiler** | `meta/InterfaceInfo` | **was classified engine; it is not.** It holds `Property` and `ViewAttributes`, so it is the entity model's view of an interface. 3.0f-2's build proved it; the engine set is 11 files |
| **Consumer — emitters** | `EntityMetadataGenerator` (the pass), `FieldBoilerplateGenerator`, `ValidationGenerator`, `ViewAdapterGenerator`, `ViewBuilderGenerator`, `ViewInterfaceGenerator`, `ViewMapperGenerator`, `ViewRecordGenerator`, `ViewTrackingBuilderGenerator` | they *read* the model and *write* Java; moving them would put entity codegen inside the engine |
| **Consumer — the entity model** | `meta/EntityMeta`, `meta/EntityFieldMeta`, `meta/ViewMeta`, `meta/ViewFieldMeta`, `meta/ArtifactMeta`, `meta/Property`, `meta/ViewAttributes`, `meta/FieldConstraint`, `meta/TrackableType`, `MetadataLocations` | views, entities, artifacts, constraints, tracking levels: **domain**, not engine. The measurement called `meta/` "the representation and the parse path" — true of the three engine rows above, wrong for these nine |
| **Consumer — generator behaviour** | `CooperativeCodegen`, `DivergenceReporter`, `GenLevelResolver`, `GeneratorPreflight`, `TypeLiterals`, `JcodebuddyDirectory`, `ViewAnnotationReader` | DEC-020 preservation, DEC-022 diagnostics, entity gen-levels, preflight, literal spelling, output plumbing, the `@View` reader |
| **Consumer — rules** | all 12 of `validation/` | the entity conventions and their CLIs |

**3.0f-2 landed on 2026-10-02** (its first attempt was reverted, and the compiler is why). The
twelve files were moved, the packages rewritten and every reference re-pointed (`scripts/extract-engine.js`,
kept and now correct); `jcodebuddy-core` then failed to compile with **five of the moved files reaching back
into classes that stay**. That is not a build accident — it is the dependency direction DEC-037 forbids, and
the evidence is better than the guess the classification made:

| Moved file | Reaches back to (consumer, stays) | What it means |
| --- | --- | --- |
| `meta/InterfaceInfo` | `Property`, `ViewAttributes` | **it is not engine at all**: it holds a view's properties and its `@View` attributes. The classification was wrong on this one; it belongs with the entity model that stays, and the engine set is **11 files**, not 12 |
| `index/TypeFacts` | `MetadataLocations` (an import *and* a use) | the index row reaches into the artifact-location model — the engine needs its own answer to "where is this declaration", or that helper moves in |
| `index/ClassIndex` | `EntityMetadataGenerator`, `JcodebuddyDirectory` (+ three more symbols) | the index reaches into the *pass* (a package filter?) and into the output-directory marker. `EntityMetadataGenerator` is unambiguously the consumer; `JcodebuddyDirectory` is arguably engine vocabulary, because the index **is** a file under `.jcodebuddy/index/` |
| `source/SourceReader` | `DivergenceReporter` | the reader reports diagnostics through the entity pass's reporter — the engine needs a diagnostic channel of its own, or the DEC-022 vocabulary needs to move with it |
| `source/SourceSplicer` | `ViewInterfaceGenerator` (a static member) | a write helper that needs an *emitter* to work. This is evidence against judgement call 1 below: either the member it needs is engine vocabulary and moves, or `SourceSplicer` is a consumer and 3.0n's survivor is `jwa-builder`'s copy |

**So 3.0f-2 has four small decisions before it can be a move**, and each has three shapes: **move it in** (it
is engine vocabulary), **invert it** (the engine defines a minimal contract — a diagnostic sink, a package
filter, a source-location query — and the consumer supplies it), or **drop the dependency** (the engine can
answer without it). Recommended, from the evidence above: `InterfaceInfo` back to the consumer set;
`JcodebuddyDirectory` **into the engine**; `EntityMetadataGenerator` **inverted** (filter as a parameter);
`DivergenceReporter` **inverted** behind a sink, with DEC-022's diagnostic vocabulary moving into the engine,
because "a missing answer is reported, never inferred as absent" is an engine rule; `TypeFacts`'s
`MetadataLocations` use **inverted or moved**; and `SourceSplicer`'s `ViewInterfaceGenerator` member
**moved into the engine if it is a marker**, otherwise judgement call 1 flips and the splice stays a consumer.
The tree was left green (the attempt reverted) rather than either dragging consumer classes into the engine or
committing a build that does not compile; `scripts/extract-engine.js` stays, with its two heuristic bugs fixed
(it now adds an import only for a file that **shared the class's old package**, skips a class the file declares
itself, and ignores comment lines).

**Outcome — all six executed as recommended, and two of them were not the shape the recommendation guessed.**
The move landed with `jcodebuddy-core` holding the engine (**12 files**: the five index files including their
new `TypeKinds`, the four `source/` helpers, `meta/SourceMetadata`, `meta/SourceLocation`, and
`JcodebuddyDirectory`), and the six fixes were:

| Decision as recommended | What it actually was |
| --- | --- |
| `InterfaceInfo` back to the consumers | as recommended — the compiler had already decided it |
| `JcodebuddyDirectory` into the engine | as recommended, and its `DIR` constant is **defined** in the engine now instead of read from the pass |
| `EntityMetadataGenerator` inverted | **not a filter**: the index used three shared *utilities* (`escapeJson`, `OBJECT_MAPPER`, `JCODEBUDDY_DIR`), so they became the engine's `MetadataJson` (one escaping rule, one mapper) and `JcodebuddyDirectory.DIR`, with the pass's own members delegating — one definition each |
| `DivergenceReporter` inverted behind a sink | as recommended: the engine defines `DiagnosticSink` (one method, DEC-022's six parameters), the reporter implements it and keeps owning the format |
| `TypeFacts`'s `MetadataLocations` use | **moved, not inverted**: `kindOf` became the engine's `TypeKinds`, because `MetadataLocations`' own javadoc said the method was public "only so `TypeFacts` can reuse it". The entity model delegates, so there is still one resolver |
| `SourceSplicer`'s member | **judgement call 1 stands**: the member was *data* (`EntryPoint`, two strings), not emission logic, so it moved into the engine and the emitter converts at its one call site — the emitter's API and its 11 test references did not move |

Two further consequences, recorded because they are the split's real cost: **(a)** helpers that were
package-private because they shared a package with their only callers became the engine's public surface
(`SourceSplicer`, `JavaSyntaxCheck` and its nested types — including two compact canonical constructors that
had to follow their records — `SourceReader.readFragmentUnit`/`reportUnparseable`,
`ClassIndex.moduleRelative`/`README_TEXT`, `Wyhash64`); **(b)** the engine's POM gains OpenRewrite and
Jackson 3 (`tools.jackson.core`), and `jcodebuddy-codegen-api` now depends on the **engine** instead of
`hipster-entity-tooling` — which is the cycle DEC-037's third fact described, gone.

Evidence: the recorded gate `BUILD SUCCESS` (`jcodebuddy-core` + the six `hipster-entity` modules, tooling
5:31 — its committed example regenerates byte-identically, which is 3.0f-4's evidence as well), a 20-module
build green for every module outside the gate before the visibility widening, and `LINKS` green.
`scripts/extract-engine.js` and `scripts/apply-engine-inversions.js` stay: each inversion is written there
with the reason it has the shape it has.

**3.0f-3 landed 2026-10-02 — the model's answer is a value, not a `null`.** The index had one way to answer
about a type, `ClassIndex.row(fqn)`, whose `null` means both *"this index has no such type"* and *"this type is
not something this index covers"* — a JDK type, another module's type, a type the pass has not reached. The
second case is the common one, and reading it as the first is what the prototype did in step 3.2. So:

- **`TypeAnswer`** (`engine.index`, a sealed interface): `Found(ClassRecord)` — a fact a consumer may act on —
  or `NotIndexed(fqn, cause)`, where the cause is **part of the value** and is phrased as what the index covers
  ("not a type in the index of module 'x'; it may be a JDK type, another module's type, or a type this module's
  sources do not declare — the engine is not saying it does not exist"), never as an absence.
- **`ClassIndex.answer(fqn)`** is the seam consumers should ask by; `row(fqn)` stays for the index's own use and
  is now documented as the shape *not* to ask in.
- **Members and relations are deliberately absent** from the model (3.0b, 3.0h), and the contract's point is
  that asking for them must also answer *cannot* rather than *none* — the same mistake in a new place. The
  type's javadoc says so, so the two steps that fill it know what to preserve.
- Evidence: `TypeAnswerTest` (4 tests: an indexed FQN answers with its row and a description naming file and
  line; a JDK FQN answers "cannot answer … not saying it does not exist"; `row()` still returns a bare `null`,
  pinned *as* the reason to prefer `answer`; and `type()` on a non-answer throws rather than handing back a
  null). Core: 53 tests, `BUILD SUCCESS`.

**The two stale-prose amendments 3.0f owed are done**: `DEC-009`'s "All three live in `hipster-entity-tooling`"
and `DEC-W008`'s "the tooling module is still where source bytes are turned into a `SourceMetadata` tree" both
now carry a dated note saying the tree is turned in the engine and the tooling consumes it.

**3.0f-4 is satisfied by the gate**: `ExampleRegenerationTest` regenerates the committed example
byte-identically, and the tooling's 65 test sources are green (`-pl :jcodebuddy-core,:hipster-entity-tooling -am test`,
`BUILD SUCCESS`). So **3.0f is complete** — 3.0f-1 the classification, 3.0f-2 the move and its six inversions,
3.0f-3 the answer contract, 3.0f-4 the pass unchanged. What it did *not* do, and what comes next: the model
still carries no members and no relations (3.0b), and no consumer has been rewired onto `answer()` yet — the
two places that should be are where the entity pass and `MetadataLocations` read the index, and they are
3.0b's or 3.0j's work rather than a silent follow-up here.

**3.0f-3's shape is set by rule 10 and DEC-037's "what a consumer owns" (clarified 2026-10-02)**: the model is
**general and rich** — the facts a *class* of consumers needs, not only what the entity pass wants today — and
it grows **no entity concept** (`@View`, views, artifacts, builder levels). hipster-entity keeps its own
entity/relation shape and serialises it for its UI and its visual documentation, because that is a projection
of the engine's facts; what it may not do is parse source or re-derive a fact the engine already answers. So
3.0f-3 is judged by two things: can a consumer extract what it needs **without touching source text**, and does
the model still contain nothing that is only one consumer's domain.

**Judgement calls, recorded rather than buried.** *(1) `SourceSplicer` goes to the engine.* DEC-030 names
the splice as the write half of the one representation, and DEC-037 lists parsing but not writing — so this
extends the record rather than following it. The reason to extend it: with the splice in the engine, "one
splice path" is structural and **3.0n** (absorb `jwa-builder*`) has an unambiguous replacement to point its
consumers at. The alternative — writing stays with generators, and `jwa-builder`'s copy becomes the survivor
in `jcodebuddy-builder` while the tooling's is deleted — is equally coherent and is recorded here so the
choice is visible rather than accidental. *(2) `meta/` splits*, above. *(3) `JcodebuddyDirectory` stays a
consumer* although "where output goes" sounds shared: it is the generator's output plumbing (DEC-026), and
the engine does not write files. *(4) What the engine gains*: OpenRewrite (`rewrite-core`, `rewrite-java`,
`rewrite-java-25`) and Jackson — exactly the dependency change DEC-037 predicted, and the reason the marker
leaf (3.0l) exists. *(5) `SourceMetadata` moving* is what lets `jcodebuddy-codegen-api`'s single-type
dependency on `hipster-entity-tooling` disappear at 3.0i — the cycle DEC-037's third fact described.

**Execution order for this step** (each lands with its own evidence, because nothing here is in the recorded
gate): **3.0f-2** `jcodebuddy-core` gains `index/` and the parse/position/write helpers plus the three
`meta/` engine files, packages re-imported, `hipster-entity-tooling` depending on core — behaviour unchanged;
**3.0f-3** the engine's *model* answers with one type (kind, modifiers, members, declaration file, checksum,
relations) and a *missing* answer stays distinguishable from an absent one; **3.0f-4** the entity pass
still regenerates the committed example byte-identically and the tooling's 65 test sources stay green. This
pass is **3.0f-1: the classification**, which moves no code.

**What 3.0f-2 will touch, measured (2026-10-02).** The engine types are imported by **five modules**, so the
move is cross-module even though it is small:

| Module | What it imports | Note |
| --- | --- | --- |
| `jcodebuddy-core` | — | gains the engine packages, and OpenRewrite + Jackson with them |
| `hipster-entity-tooling` | the engine types throughout (`validation/*`, `MetadataLocations`, `EntityMetadataGenerator`, `index/*`) | internal imports; it stays a consumer and keeps depending on core, which it already does |
| `project-automation` | `SourceReader`, `TreeQueries`, `index.ContentHash` (`SourceFacts`, `runner/SourceMetadataParser`), `meta.SourceMetadata` (`MetadataTypeResolver`), plus one test | outside the recorded gate → own build evidence |
| `jcodebuddy-codegen-api` | `meta.SourceMetadata` in `CodeContext` and `CodeContextImpl` | **this is the one-type dependency DEC-037's third fact describes**; after 3.0f-2 it points at core, and 3.0i dissolves the module |
| `hipster-ioc-tooling` | `SourceReader`, `TreeQueries` (`ContextReader`) | outside the gate → own build evidence |

Only `hipster-entity-tooling` is in the recorded gate, so 3.0f-2's evidence is the gate **plus** a targeted
build of `project-automation`, `jcodebuddy-codegen-api` and `hipster-ioc-tooling`.

**Do:** make `jcodebuddy-core` the engine's home and give it the model — one type with its kind, modifiers,
members, declaration file and checksum; relations expressed rather than implied; a *missing* answer reported,
never an inferred absence (the lesson from step 3.2 and `TypeChangeConflictResolver`'s `UNRESOLVED_WARNING`);
no file handles, no class loader, no second parser — the engine reads through DEC-030's one representation.
Move the representation and the parse path out of `hipster-entity-tooling` into it (and the marker
vocabulary out to the leaf 3.0a chose, if that is the answer), keep `hipster-entity-tooling` as a consumer,
and leave the entity pass working: this step moves code, it does not change what a generator emits.

**Gate:** the modules this touches are **outside the recorded gate** (DEC-037's second fact), so this step
publishes its own evidence: `MODULE` green for `jcodebuddy-core,jcodebuddy-core-leaf?,hipster-entity-tooling`
plus an explicit build of every module that referenced what moved, and the entity pass regenerating the
committed example byte-identically.

**Done when:** one module holds the model and the parse path, `TypeDefinition` (or its successor) can express
a relation, and nothing outside the engine parses Java for metadata.

### 3.0g — Freshness: the watch loop, its events and its invalidation
**Who:** agent · **Size:** L

A consumer that reads stale metadata is the failure this whole layer exists to prevent, and today freshness
lives in `java-watch-core` (the watcher, `ChecksumDatabase`, `ChangeSet`) and in `metadata-server`
(`WatchMetadataProvider`) while the model lives somewhere else entirely.

**Do:** the engine publishes the freshness contract — what a subscriber observes (**events**, with enough to
act: which file, which row, which relation, what kind of change), what is cached, and what invalidates it. A
row depends on the file that declares it **and on the types it names**, so an edit to `B` can stale `A`'s row
even though `A.java` was untouched; a rename, a delete and a new file each have a defined effect. Use 3.0a's
answer about `java-watch-core` (its watcher and checksums become the implementation beneath this contract, or
move in), and settle 3.0c with it rather than separately.

**Gate:** own evidence (outside the recorded gate) plus a test for the three transitions that matter: an edit
invalidates its own row; an edit that changes a relation invalidates the *dependent* rows; a deleted file
removes its row and the rows that named it (or marks them unresolved, as the contract says). And one test
that a subscriber actually receives an event — a freshness contract nobody can observe is a cache.

**Done when:** "is this metadata safe to read?" has a mechanical answer, and a consumer can subscribe instead
of watching files itself.

**Landed 2026-10-02 in the engine (`hr.hrg.jcodebuddy.engine.fresh`), and it is deliberately not a watcher.**

- **The division: the host watches, the engine says what it means.** DEC-038 settled that the engine takes no
  watcher, so nothing here polls a file or holds a watch service; `Freshness.report(kind, path)` is what the
  host calls after observing one, and it is the single entry point for all three transitions (a new file, an
  edit, a removal differ only by kind).
- **`FreshnessEvent(kind, path, rows, dependents, unresolved, cause)`** — and the second field is the whole
  point. A row depends on its file **and on the types it names**, so editing `Person.java` stales
  `Employee`'s row although `Employee.java` was never touched; that set is computed from 3.0b's relations
  (`ClassIndex.subtypesOf`), not by parsing anything. `kind` reuses `ClassIndex.ChangeKind` rather than
  introducing a second vocabulary for "what happened to a type".
- **The dependent search matches the FQN *and* the simple name**, because a relation is stored as written
  (`implements Person` contains no FQN) — and the fixture writes it that way on purpose, so a dependent match
  that only looked for `a.b.Person` would fail the test rather than pass silently.
- **`Freshness.stateOf(fqn)` answers SAFE / STALE / UNKNOWN**, with UNKNOWN kept apart from SAFE: a name the
  engine never saw is not a name it can vouch for, which is 3.0f-3's rule applied to freshness. A removal marks
  what named the gone type as **unresolved** rather than dropping the relation.
- **What is cached is the index and nothing else** — no parse trees, no source text between calls — so the
  invalidation unit is the row and a re-read of the file is always correct.
- **A file the index has no row for is still reported**, with a cause saying the index has no row for that path;
  silence would be indistinguishable from safety.
- **Evidence (own evidence, this is outside the recorded gate):** `FreshnessTest`, 6 tests — an edit stales its
  own row; an edit that touches a relation stales the **dependent** rows; a deleted file leaves what named it
  **unresolved**; a subscriber actually receives the event and a closed subscription stops; an unknown path is
  reported; and an unknown name is UNKNOWN rather than SAFE. `EngineHasNoWatcherTest`, 2 tests, makes DEC-038's
  rule mechanical: the engine's own POM declares no `watch` artifact and no engine source names
  `hr.hrg.watch2` or reaches for the JDK's `WatchService`. Core **67 tests `BUILD SUCCESS`**.
- **Additive, so nothing else was re-run**: this step adds a package and edits no existing engine type, so the
  tooling (and the gate path) cannot be affected — stated rather than claiming a run that did not happen.
- **What this step does not do:** it does not re-read a changed file (a pass does that), does not resolve
  relation names (3.0h), and does not decide *when* a pass runs (the host's policy). 3.0c's cache question is
  settled by the same answer: the row is the invalidation unit.

### 3.0h — Search: the queries every consumer asks
**Who:** agent · **Size:** M

Codegen, analysis, reporting and an LSP sidecar ask the same questions in different words, and today each
answers them its own way (`ClassIndex` lookups, sibling-file reads, classpath resolution in the merge tool).

**Do:** publish the engine's query surface over the model and the indexes: by fully qualified name; by kind
and modifier; by relation (supertype, subtype, implementor — in the direction the index stores and the
direction a consumer asks); by annotation; by member; by package. Say what a query returns when the metadata
is *unavailable* (beyond the sources the engine was given) and keep the distinction between "not yet indexed"
and "does not exist" visible, because collapsing them is what produces confident wrong answers.

**Gate:** own evidence, with tests per query against a fixture whose relations cross a module boundary (the
case a per-module index cannot answer), and a test that an unknown name is distinguishable from an
unindexed one.

**Done when:** a consumer can ask a relation question and get one answer, from one place.

**Landed 2026-10-02 as `hr.hrg.jcodebuddy.engine.query.MetadataQuery`, over a *set* of module indexes.**

- **Four of the six query families are answered from the model**: by FQN (`answer`, returning 3.0f-3's
  `TypeAnswer`), by kind, by modifier, by package, by path, and by relation in both directions
  (`extendersOf`, `implementorsOf`, `subtypesOf`, `supertypesOf`). Every answer carries `coverage()` — the
  modules searched — so "not found" reads as "not declared in these modules" and never as "does not exist";
  the two tests the gate asked for are `oneQuestionIsAnsweredAcrossModuleBoundaries` (the relation crosses a
  module boundary, which no per-module index can answer) and
  `aDeclaredTypeWithNoImplementorsIsAFactAndAnUnknownNameIsNot`.
- **3.0b's recorded gap is closed**: relation names are resolved against the indexed world — FQN first, then
  each package prefix of the declaring type (`a.b.Outer.Inner` included), then a simple name that exactly one
  indexed type has. Two candidates is not a tie to break but a question the engine cannot answer, and it is
  **reported** in `RelationAnswer.unresolvedNames()` instead of guessed. A relation naming something outside
  the modules (`java.io.Serializable`) is reported there too, so an answer's gaps are visible.
- **A correction the test forced, recorded because it is the useful part**: my first ambiguity fixture put the
  implementing class in the same package as one of the two `Person`s — where the *declaring package* settles
  the name exactly, so nothing was ambiguous and the engine was right to answer. A genuine ambiguity needs a
  package where neither candidate lives; the fixture now has `e.f.Orphan implements Person` for that, and
  keeps `c.d.Employee` and a full-FQN `g.h.Exact` to show exact resolution still winning.
- **Two families are not answerable from the model**, because a row carries no members and no annotations:
  `membersOf` and `annotationsOf` return **`NotCovered`** — question, reason and what to read instead — rather
  than an empty list, which would read as "this type has no such member". That gap is a DEC-029 format change
  and is scheduled as **3.0r** below, which is what the step's own Do asked for when it listed those two
  queries.
- **Evidence:** `MetadataQueryTest`, 6 tests (cross-module answer and coverage; declared-but-unimplemented vs
  declared-nowhere; an outside relation reported unresolved; ambiguity refused and reported; the kind, modifier,
  path and package queries over the whole project; and the two gaps reporting themselves). Core **73 tests
  `BUILD SUCCESS`**. Additive apart from one small addition to `TypeAnswer` (`fqn()`), which the answer types
  needed to report a question they could not answer.
- **What it does not do:** it does not grow the model (3.0r), does not cache query results (the index is the
  cache and `Freshness` says when it is stale), and does not search source *text* — a text search is a different
  question from "what is declared".

### 3.0i — Dissolve `jcodebuddy-codegen-api` into the engine
**Who:** agent · **Size:** M

DEC-037 decision 2: the five types split by what they are. `TypeResolver`/`TypeDefinition` are engine
metadata queries; `CodeGenerator`/`CodeContext`/`CodeContextImpl` are the generator SPI the engine publishes;
the `SourceMetadata` edge (the reason codegen-api depends on `hipster-entity-tooling` today, and the reason the
SPI cannot be implemented next to the index it reads) disappears with the move.

**Do:** move the SPI into the engine, delete the module, and update every consumer's POM and imports —
`project-automation` (the generator implementations and `MetadataTypeResolver`; `ActionToolAdapter`, the
watcher-side bridge, was already gone: step 3.0s removed it when the watcher kept its own `ActionTool` port),
`jcodebuddy-agent` (called `java-watch-agent` when this Do was written), and `hipster-ioc-tooling`. Update
[`module-map.md`](../doc/architecture/module-map.md) (which states the five-type leaf property) and the places
that cite codegen-api as the precedent for promoting a shared type out of `project-automation`
([`ProjectAutomationIsolationTest`](../hipster-entity/hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ProjectAutomationIsolationTest.java)
names it in a message). **`project-automation` stays private** — the SPI moving to the engine must not become
a reason for anyone to depend on a project's assistant.

**Gate:** own evidence: `MODULE` green for every module that moved to the new coordinates, the engine's own
tests green, and no `import hr.hrg.jcodebuddy.codegen.` left anywhere (a grep is the check that the move is
complete).

**Done when:** the SPI has one home, the module is gone, and nothing depends on a project's assistant to
implement a generator.

**Done 2026-10-03.** The module is gone, and the five types are the engine's: `TypeResolver`/`TypeDefinition`
in `hr.hrg.jcodebuddy.engine.query`, `CodeGenerator`/`CodeContext`/`CodeContextImpl` in
`hr.hrg.jcodebuddy.engine.codegen`. It left the root POM's `<modules>` and its managed `dependencyManagement`
entry, and [`scripts/dissolve-codegen-api.js`](../scripts/dissolve-codegen-api.js) did the move, the import
rewrite and the POM changes — so this is reproducible rather than a story about five files.

**The decision this step had to make that the record did not state: two packages, not one.** The script written
for the first attempt argued for keeping `hr.hrg.jcodebuddy.codegen`, because moving five files between modules
without changing an import is the cheapest possible migration. Two things beat that argument: the engine's own
convention is `hr.hrg.jcodebuddy.engine.<area>` (index, source, meta, fresh, query — and now codegen), and this
step's own gate is *no `import hr.hrg.jcodebuddy.codegen.` left anywhere*, which a surviving package cannot
satisfy and which would leave a module-shaped name inside a module that no longer exists. The churn was **two
consumer files**.

**What the Do named that had already changed, which is the part worth keeping.** Its consumer list was written
before 3.0s: it named `java-watch-agent` (renamed `jcodebuddy-agent` on 2026-10-03) and `ActionToolAdapter` as
things to update. `ActionToolAdapter` was deleted in 3.0s — nothing used it but its own test — so **the watcher
had no SPI dependency left to drop**: all that remained in its POM was a comment describing a dependency it no
longer declared, and rewriting that comment is the whole of the agent's part of this step. A finding is a
hypothesis with a date on it, and this one was two revisions old by the time the step ran.

**The consumer that was reaching the engine transitively.** `project-automation` imports `SourceReader`,
`TreeQueries`, `index.ContentHash` and `meta.SourceMetadata` while declaring no dependency on `jcodebuddy-core`
at all — it got them from `hipster-entity-tooling`. Its SPI dependency became an explicit `jcodebuddy-core` one
here, which is both the honest replacement and the fix for that reach. `hipster-ioc-tooling` already declared
the engine (for `GeneratedCodeMarkers`), so its SPI entry was **dropped rather than re-pointed**: a second
`<dependency>` on the same artifact is a POM smell, and Maven says so at every build.

**The gate caught something this step did not break, and the fix is not the move.** The first build failed in
`MigrationCompletenessTest` — *"the sweep is only evidence if it really walks the tree; found 243 files"*
against a floor of 250. `jcodebuddy-codegen-api` was never in that sweep, so the move could not have caused it;
the count was 243 before this step too. The cause was three names in the sweep's `MODULES` list that resolve to
no reactor module: `java-watch-agent` (renamed at 3.0s — **21 sources quietly left the sweep**, which is the
243) and `webview/jwa-sidecar` + `webview/eclipse/webview-eclipse` (written as *paths* in a list resolved by
*artifactId*, so 23 sources had never been swept at all). The count floor is what noticed, three steps late,
because the rename's own evidence was a targeted `-pl` build that never ran this test. Fixed here: the names
are artifactIds, the agent and the two webview modules are in the sweep (**287 sources**), and
`committedSources()` now asserts that *every* listed name resolves — so the next rename fails naming what
stopped resolving instead of reporting a bare count. The widened sweep passes unchanged, which is the honest
result: it was blind, not lenient.

**Evidence:** `bun scripts/mvn-jdk25.js -o -pl
:jcodebuddy-core,:hipster-ioc-tooling,:project-automation,:jcodebuddy-agent,:jcodebuddy-watch-tools -am clean
test` → **BUILD SUCCESS** (core 77 tests, `hipster-entity-tooling` 466, `project-automation` 92,
`hipster-ioc-tooling` 11, `metadata-server` 15, `metadata-mcp-server` 8); the recorded `GATE` →
**BUILD SUCCESS** (6:10) with `jcodebuddy-core` in it; `dissolve-codegen-api.js`'s own last check reports no
`hr.hrg.jcodebuddy.codegen` reference in any Java source; `LINKS` green.

**Docs in the same change:** `module-map.md` (the five-type leaf section replaced by where the SPI lives now;
`jcodebuddy-core` described as the engine it is — its "no dependencies at all" had been false since 3.0f — and
the tree and Layer tables brought to the reactor's actual shape, since the tree had not been re-derived since
the group move and `java-watch-agent` was still in the Layer 3 table), AGENTS.md § 1.1's precedent bullet,
DEC-037 decision 2 (dated execution note), DEC-W003 (amendment), DEC-036 (where the seam went), DEC-038 (both
halves done), `hipster-ioc/doc/ROADMAP.md`, `ProjectAutomationIsolationTest`'s failure message, and the two
places in this plan that still told a later step to put a type in the deleted module.

**Not this step:** nothing implements `TypeResolver` but `empty()` (3.0d), no consumer has moved onto the
engine's queries (3.0j), and the moved types are still the file-scoped seam they were. This step moved code and
deleted a module; it changed no behaviour.

### 3.0j — Move the remaining consumers onto the engine
**Who:** agent · **Size:** L

`metadata-server` (transport and providers), the dev-time passes (`project-automation`), the watch tools
(`java-watch-agent`), reporting (the Bun renderers that read the engine's JSON) and the future LSP sidecar are
all readers of the same facts; none of them may keep a path of its own.

**Do:** re-point each consumer at the engine, in the order 3.0a's answers allow, deleting the private paths as
they go (a consumer's own cache, its own sibling-file read, its own classpath resolution for types the engine
can answer). `metadata-server` keeps its transports and its serving shapes (DEC-W006–W009) and loses its
ownership of the model. Each consumer that moves gets its own commit, because a move that breaks it must be
visible as that move.

**Gate:** per consumer: its own build/test green plus the engine's contract test that it reads the engine
rather than a file (for the passes: the same regeneration output as before the move; for the server: the
existing RPC tests unchanged).

**Done when:** no consumer has a metadata path of its own — one model, one index, one freshness contract, many
readers.

### 3.0k — Grow the recorded gate to cover the engine's contract
**Who:** agent · **Size:** S

DEC-037's second fact: `GATE_MODULES` is `jcodebuddy-core` plus the six `hipster-entity` modules, so every
step above lands in modules **no gate run covers**. The gate's own comment says why being in it is a decision
rather than an accident, which is the reason this is a step and not a line of configuration.

**Do:** add the modules that now carry the engine's contract to
[`GATE_MODULES`](../scripts/lib/gate.js) — the engine's consumers as they finish migrating — and update
`GateContractTest`'s recorded list in the same change, since it asserts the constants are still what the notes
recorded. State the cost honestly: a larger gate is a slower gate, so add the modules that hold the
*contract*, not every module that happens to compile.

**Gate:** `GATE` green with the enlarged set, `GateContractTest` green, and the plan's gate line updated.

**Done when:** a later change that breaks the engine or a migrated consumer fails `bun scripts/mvn-jdk25.js`
rather than being discovered by hand.

### 3.0l — Extract the marker leaf out of `jcodebuddy-core`
**Who:** agent · **Size:** S
[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md) decision 1. `GeneratedCodeMarkers`,
`GeneratedCodeParser` and `GeneratedBlock` — three main types plus their test — move to a leaf of their own
(*proposed name: `jcodebuddy-generated`*), so the engine may gain OpenRewrite and Jackson while a tool that
only reads generated-region spans keeps resolving nothing heavy (DEC-035's consumers are not generators).

**Do:** create the leaf, move the three types, re-point the two dependents (`hipster-entity-tooling`,
`hipster-ioc-tooling`), add the leaf to the root POM and to `GATE_MODULES` (it is the module set that decides
what a gate run watches, and this is exactly the "being in the gate is a decision" case
[`gate.js`](../scripts/lib/gate.js) writes down), and update [`module-map.md`](../doc/architecture/module-map.md)
and DEC-035's "where the vocabulary lives" sentence if it names the module.

**Gate:** `GATE` green with the leaf in the set and `jcodebuddy-core` still green; `GateContractTest`'s
recorded list updated in the same change; `LINKS` green.

**Done when:** the engine's module can grow dependencies without giving them to the tools that only parse
markers.

### 3.0m — `metadata-server` becomes `jcodebuddy-meta`
**Who:** agent · **Size:** M
[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md) decisions 2 and 3. The module's `groupId`
is already `hr.hrg.jcodebuddy`, but its package is `hr.hrg.watch2.server.metadata` — so the rename is the
directory, the `artifactId`, the `<name>` **and the package** (`hr.hrg.jcodebuddy.meta.*`), with the MCP
sibling following (`jcodebuddy-meta-mcp`), and with `java-watch*` staying an independent library it **depends
on** rather than absorbs.

**Do:** rename in that order (package first, so the compiler finds every reference), update every consumer
(`metadata-mcp-server`, `webview/jwa-sidecar`, `java-watch-agent`, `project-automation` — the POMs and the
imports), and keep the serving shapes and transports unchanged (DEC-W006–W009 stay true; what changes is the
module's name and, later, that its providers read the engine rather than owning the model). Re-pointing the
providers at the engine is 3.0j's work, not this step's — this step must not change behaviour.

**Gate:** own evidence (the module is outside the recorded gate): every consumer of the renamed module builds
and its tests pass, no `import hr.hrg.watch2.server.metadata` remains anywhere, and the module's own tests are
green under the new coordinates.

**Done when:** the family's names say what the modules are, and nothing has changed but names.

### 3.0n — Absorb `jwa-builder*` and collapse the duplicate splice path
**Who:** agent · **Size:** L
[DEC-038](../doc-hipster-entity/architecture/decisions/DEC-038.md) decisions 4 and 5. `jwa-builder` and
`jwa-builder-api` are already modules here, named for the agent they were first written for
(`hr.hrg.watch2.builder[.api]`), and `jwa-builder` holds a **second** `SourceSplicer` (plus `LineLookup`)
against DEC-030's one splice path.

**Do:** rename them into the family (*proposed: `jcodebuddy-builder-api`* for `GenerateBuilder`, the
annotations; *proposed: `jcodebuddy-builder`* for `BuilderTransformationEngine`, `ClassMemberProcessor`,
`RecordBuilderProcessor`, and whichever of `LineLookup`/`SourceSplicer` 3.0f's classification leaves with the
generator), packages following (`hr.hrg.jcodebuddy.builder[.api]`), and **delete the duplicate splice path**:
one implementation survives and the other goes, rather than staying as a private helper the next generator
picks up by accident. Update the consumers (`java-watch-agent`, `project-automation`, `webview/jwa-sidecar`),
and give [`code.graph.md`](../doc_knowledge/code.graph.md) and DEC-030 the surviving home of the splice —
they name `SourceSplicer` today without saying which module it lives in, which is how there came to be two.

**Gate:** own evidence (all three consumers are outside the recorded gate): each builds and its tests pass;
one `SourceSplicer` remains in the tree, and a grep proves it; the record and the guide name its home.

**Done when:** the codegen modules are part of the family, and one splice path exists instead of two.

### 3.0o — Group the reactor's modules (DEC-039)
**Who:** agent · **Size:** M

**Done 2026-10-02 — the four groups, before the engine work, as directed.** [DEC-039](../doc-hipster-entity/architecture/decisions/DEC-039.md)
records it: `webview/` (already), `watch/` for `java-watch*`, `hipster-entity/` for `hipster-entity*`,
`jcodebuddy/` for `jcodebuddy*` **plus `metadata-*` and `jwa*`**; a module keeps its full name inside its
group; `java-watch*` stays its own library. "Others to be decided" is honoured — `hipster-ioc*`, `merge-java`
and `project-automation` are still at the root.

- **Group 1, `hipster-entity/`** (6 modules): gate `BUILD SUCCESS` (5:26 in tooling), links green.
- **Groups 2 and 3, `watch/` and `jcodebuddy/`** (12 modules): gate `BUILD SUCCESS` (5:59) **and** a targeted
  build of everything the gate does not cover — `BUILD SUCCESS` over 18 modules, including
  `project-automation` (92 tests), `java-watch-agent`, `metadata-server`, `metadata-mcp-server`,
  `metadata-arena`, `jwa-builder*` and `jcodebuddy-codegen-api` — because `GATE_MODULES` covers only
  `jcodebuddy-core` and the six `hipster-entity` modules until 3.0k. Links green.
- **Group 4, `hipster-ioc/`** (3 modules, added by the amendment to DEC-039 the same day): the group folder
  **is** the topic folder that already existed — `hipster-ioc/doc/`, its README and roadmap stay at the root
  and the modules go inside, which is the `webview/` shape. Evidence: a targeted build of
  `hipster-ioc-api,hipster-ioc-tooling,hipster-ioc-test` — `BUILD SUCCESS`, tooling's tests included (3.8 s) —
  and links green (the only docs change is `hipster-ioc/doc/ROADMAP.md`'s two module links).
- **Group 5, `webview/`** (5 directories, DEC-039's second amendment): `intellij-jwa`, `intellij-jswa`,
  `vscode-jwa`, `vscode-jswa` and `jswa-core` are **earlier attempts at the sidecar functionality** the suite
  provides today. None is a reactor module (Kotlin/Gradle plugins, VS Code extensions, a TypeScript library),
  so only their location changed; the three with a README now say at the top that they are an earlier attempt
  and not to build on them. Their duplication is audited by **3.0p** and resolved by **3.0q** below. Links
  green.
- **The `watch/` collision was real**: a module named `watch` already occupied that path (a small
  file-copying watcher app, no dependents). It was renamed into the family — directory
  `watch/java-watch-app`, artifactId `java-watch-app` — rather than left as a module sitting on its group
  folder.
- **Two layout assumptions had to die, and that is the lasting part.** `scripts/lib/gate.js` selects modules
  by `:artifactId` now (a bare directory name stops resolving once modules are nested), and
  `CompileHarness.findRepoRoot()` no longer guesses "the nearest `pom.xml` whose directory starts with
  `hipster-entity-`" — that returned the *module* directory the day the modules moved and left ~30 test
  classes looking for a classpath that cannot exist. Its replacement is `pom.xml` **and** `.git`, plus
  `CompileHarness.moduleDir(artifactId)`, which reads the root POM's `<module>` entries because that is the
  one record of where a module lives.
- **The move is scripted and the script is kept**: [`scripts/move-to-groups.js`](../scripts/move-to-groups.js)
  (`--dry-run`, `--group`, `--all`, `--fix-links`, `--fix-segments`), idempotent, repairing only the links it
  can prove and printing the rest. It also had to clean up after its own earlier non-idempotent run, which is
  why the doubling repair is in it and in the commit history rather than hidden.

**Gate:** ✅ both gates green, the 18-module build green, links green (256 files, 1555 links).

### 3.0p — Audit the five earlier sidecar attempts against today's webview
**Who:** agent · **Size:** M

`webview/intellij-jwa`, `webview/intellij-jswa`, `webview/vscode-jwa`, `webview/vscode-jswa` and
`webview/jswa-core` are **earlier attempts at the sidecar functionality** the current suite provides
(`webview/jwa-sidecar`, `webview/core/webviewd`, `webview/kit`, and the IDE hosts). They were moved under
`webview/` and three of them now say so at the top of their README, but **nothing has compared them**: an
earlier attempt can hold a capability the current one lacks — an IDE API it drove, a transport, a protocol
detail, a UI affordance, a known-bad interaction it documented — and it can equally be weight nobody should
carry.

**Do:** for each of the five, inventory what it actually does (entry points, what it talks to, what it
implements, whether it still builds) and compare it against the current implementation **capability by
capability**, naming the current counterpart or recording that there is none. Give every capability one of
three verdicts — *duplicated* (with the counterpart named), *unique and worth keeping* (with where it
belongs), or *dead* (superseded, with what replaced it) — and give every directory one of two: *has material
to merge* or *delete*. Read-only: this step merges nothing and deletes nothing, and it cites paths rather
than describing them.

**Gate:** the audit is written down with a verdict per capability and per directory, and `LINKS` green. No
build gate — this step reads.

**Done when:** 3.0q has a list it can act on: what to merge into which current implementation, and what to
delete with a reason.

### 3.0q — Merge what 3.0p found worth keeping, delete the rest
**Who:** agent · **Size:** M–L, decided by 3.0p

The second half of the direction: *"steps to merge them in new if there are functionalities worth
merging."* An earlier attempt that is not merged and not deleted is the worst of both — it compiles
sometimes, misleads a reader, and is nobody's responsibility.

**Do:** for each capability 3.0p marked *unique and worth keeping*, merge it into the current
implementation it belongs to (`webview/jwa-sidecar`, `webviewd`, `kit`, or the host that needs it) as an
ordinary change with a test, in its own commit; for each directory that is superseded, **delete it** —
git history is the archive, and a directory kept "just in case" is read by nobody. Record which
directory went, what replaced it, and which capability moved where, so the deletion is a fact rather than a
gap.

**Gate:** per merge, that module's own build and tests green; per deletion, no reference to it remains (a
grep is the check) and `LINKS` green; the record names what was merged and what was removed.

**Done when:** nothing under `webview/` is both an earlier attempt and unexamined — what survived is
merged, what did not is gone with a reason.

**Added 2026-10-02, asked for directly: annotation info in the core model.** `ClassRecord`/`TypeFacts` now carry
`List<TypeAnnotation>` — the annotation's name as written plus its arguments as source text, unevaluated — the
writer always emits the field, `DEC-029` gained the field row and a dated amendment, and
`MetadataQuery.annotationsOf`/`annotatedWith` answer for real. So the **annotation half of 3.0h's gap is closed
and 3.0r is members only**. Eight tests: four in `TypeAnnotationsTest` (extraction from real source with
arguments; `@View()`'s single `J.Empty` read as an empty parameter list rather than one empty argument; a round
trip with the field always emitted, including `"annotations": []`; and a table written before the field reading
as not-recorded) plus the rewritten `MetadataQueryTest` case (an annotation declared nowhere in these modules
still answers by name — a dependency annotation is the normal case, not a refusal; and `membersOf` still reports
its gap). Core **77 tests `BUILD SUCCESS`**.
### 3.0r — The index grows members
**Who:** agent · **Size:** M

3.0h listed six query families and could answer five. Members are the one it could not,
because a class index row records a declaration's kind, modifiers, file, checksum and relations — and nothing
about what the declaration contains or what annotates it. `MetadataQuery.membersOf`/`annotationsOf` report that
gap rather than answering "none", which is honest but not useful.

**Do:** add members to the row and to the writer, the way 3.0b added relations: a name and a
kind per member (field/method/nested type, with the signature's shape and never its body), and the annotation
type names per declaration — all as written, resolved at query time by the same rules 3.0h established. It is a
**DEC-029 format change**, so it carries its own amendment: the same always-emitted rule as relations (an absent
field is "not recorded", never "none"), the same refusal of an unknown kind, and a version bump only if the new
fields cannot be tolerated by the reader — which is what the `relations` field proved for an additive change.

**Gate:** own evidence: a round trip per new field; a member query and an annotation query answering from the
index alone; a declaration with none of either reading as a fact rather than as "not recorded"; and
`MetadataQueryTest`'s `NotCovered` case replaced by a real assertion, so the gap cannot silently reopen.

**Done when:** `membersOf` and `annotationsOf` answer from the model, and no consumer has to parse a file to ask
what a type contains or what annotates it.

### 3.0s — `java-watch*` is standalone: no Jackson, no OpenRewrite, nothing else from this workspace
**Who:** agent · **Size:** M

The maintainer's rule, given 2026-10-02 while 3.0i was being attempted:

> java-watch* must not know about jackson or openrewrite or anything else from this workspace

This is what 3.0i collided with, and the collision was real rather than formal: `java-watch-agent`'s
`ActionToolAdapter implements CodeGenerator<List<FileChange>>`, so **the SPI module existed to keep the watcher
compilable without `project-automation`** (AGENTS.md § 1.1 records exactly that promotion). Dissolving the SPI
into the engine would have made the watcher depend on OpenRewrite and Jackson in order to compile — so the
boundary had to be fixed first, and this step fixes it.

**Do:** make the check pass. It has been written and it measures **17 violations on 2026-10-02**, which is the
list this step works through:

| Module | Violation | The shape of the fix |
| --- | --- | --- |
| `java-watch-agent` | depends on `jcodebuddy-codegen-api`, and `ActionToolAdapter` (main) + `ToolSeamTest` (test) import `hr.hrg.jcodebuddy.codegen.*` | the watcher keeps **its own** port (`ActionTool` already is one); the adapter that bridges it to a JCodeBuddy SPI moves into a module that legitimately depends on both (`project-automation`, the project's own dev-time assistant) |
| `java-watch-agent` | depends on `jwa-builder-api` and `jwa-builder` | find what uses them — the JWA builder is absorbed into `jcodebuddy/` (DEC-038), so a use in the watcher is either a bridge to move out or a leftover to delete, and the record says which |
| `java-watch-agent` | Jackson: the dependency, plus imports in `AuditManager`, `CommandServer`, `WatchAgent` | the watcher writes its own audit JSON; either hand-rolled writing (it is a small, fixed document) or a JSON library the watcher chooses for itself — what it may not do is inherit this workspace's Jackson |
| `java-watch-run-sample` | depends on Jackson | a sample module: either its JSON use is removed or it depends on a library it declares itself, with the same rule |

**Progress 2026-10-03 — the SPI is gone from the watcher, 17 violations down to 13.** `ActionToolAdapter` was
**deleted, not moved**, and that is the honest reading the check made possible: nothing in `java-watch-agent`'s
main code used it — its only user was its own test — so relocating dead code into `project-automation` would
have preserved a bridge to nothing. The three adapter cases went with it and the seam tests the watcher
genuinely owns (`ActionTool`, `SimpleToolContext`) stay: 15 tests green, `java-watch-agent` `BUILD SUCCESS`.
The `jcodebuddy-codegen-api` dependency went with the class. **This is what unblocks 3.0i**: with the watcher no
longer implementing the SPI, dissolving the five types into the engine gives `java-watch*` nothing.

**Progress 2026-10-03, slice 2 — the sample is clean: 13 down to 8.** `java-watch-run-sample` no longer knows
Jackson: its demo was "Jackson picks up your changes after a hot reload", and the demo's real point is that
*editing a class changes the output without a restart* — a hand-written rendering shows that exactly as well,
so `DataProcessor`/`PersonData` lost the annotations, the mapper and the round trip, `SampleMain`'s section
names the demo rather than the library, and the POM dropped `jackson-databind`. The module builds
(`BUILD SUCCESS`) and the check says 8.

**The remaining 8 are all `java-watch-agent`, and they are three slices with three different shapes** (measured,
so the next pass does not re-derive it):

1. **`AuditManager` — one write, the easy one.** Its whole Jackson use is
   `MAPPER.writeValue(manifest.json, Map.of("files", entries))` where `entries` is a `List<FileEntry>`; a
   hand-rolled writer emits the identical shape in about twenty-five lines, and the keys must match
   `FileEntry`'s components exactly because a consumer of the audit session reads that file.
2. **`WatchAgent` (config) and `CommandServer` (HTTP) — a format and a protocol decision, not a mechanical
   one.** `WatchAgent` *reads* a config file back (`readValue(...)` into `AgentConfig`), so it needs a parser,
   not just a writer; `CommandServer` reads request bodies into a `Map` and writes responses as JSON, i.e. the
   Jackson dependency there is the **wire format between this server and its clients**. Hand-rolling a small
   reader for the flat shapes actually used is feasible; changing the format is a decision about clients. This
   is the slice to decide before writing code.
**Renamed the same day, on the maintainer''s direction** ("ok to be moved and renamed if it clearly is better
fit for jcodebuddy, then the rules about java-watch* do not aply to it"): the module is now
**`jcodebuddy/jcodebuddy-agent`** — directory, artifactId and `<name>` (`JCodeBuddy Agent`), with the parent's
managed entry and `jcodebuddy-watch-tools`' dependency updated, fifteen doc paths rewritten, and
`hr.hrg.watch2.agent` left as the package (the code''s identity; a package rename is its own change). Evidence:
`-pl :jcodebuddy-agent,:jcodebuddy-watch-tools -am clean test` `BUILD SUCCESS`, 15 tests in the agent.

**Resolved 2026-10-03 — the remaining 8 were the wrong module in the wrong group.** The maintainer's read:
*"java-watch-agent may be better suited to be part of jcodebuddy family."* It is, and that is a better answer
than moving tools: the agent is **JCodeBuddy's code-action server** — `AccessorGenerator`,
`ConstructorGenerator`, `BuilderGenerator`, `ContextualAnalyzer`, `RecordBuilderGenerator`, an HTTP command
server and a codegen-session audit — and it consumes `java-watch-core`, which is the standalone library that
must stay clean. So it moved to **`jcodebuddy/jcodebuddy-agent`** (artifact unchanged, package unchanged: the
group is a fact the build reads, and the package is the code's identity), the root reactor points at the new
path, and `bun scripts/check-watch-standalone.js` now says **`OK: java-watch* is standalone`** over the six
modules left in `watch/`.

That is the honest accounting of the whole step: **17 → 0**, but by three different means, and only one of them
was "fix the leak":

| Violations | How they went |
| --- | --- |
| 4 (SPI in the agent) | `ActionToolAdapter` **deleted** — nothing used it but its own test — which is what unblocked 3.0i |
| 5 (Jackson in the sample) | the demo rewritten without Jackson: its point was hot-reload, not the library |
| 8 (Jackson + `jwa-builder` in the agent) | **the module left the group**: it was JCodeBuddy's server sitting in the watcher's library group |

The check's scope is now stated in it and in AGENTS.md § 2: **the boundary is the `watch/` group**, so a future
module that needs a workspace artifact is moved to the group it belongs to rather than having its imports
rewritten. Recorded as a naming question rather than fixed here: the module keeps the name `java-watch-agent`
inside `jcodebuddy/`, which reads oddly for a JCodeBuddy server — renaming it (module directory, artifactId
and the group's docs, not the package) is its own small step if the name matters more than the churn.

**Progress 2026-10-03, slice 3 (started, not finished)** — the library exists; the `jwa-builder` boundary is a
*family*, not one class.** New module **`jcodebuddy/jcodebuddy-watch-tools`** (a small library the app may
depend on, per the maintainer's direction), depending on `java-watch-agent` — never the other way round — with
`RecordBuilderGenerator` as its first tool. `java-watch-agent` also lost four `Test*.java` files that were
manual-test scratch living in a library's `src/main/java`. Both modules build (`BUILD SUCCESS`).

**What the compiler then showed, which the recon had missed twice:** the agent's *other* tools —
`AccessorGenerator`, `ConstructorGenerator`, `BuilderGenerator` and `ContextualAnalyzer` — use
`ClassMemberProcessor`, which is also `jwa-builder`'s. So the jwa-builder dependency is not one bridge class
but the agent's **Java-codegen tool family**, and the honest fix is one of:

- **move the family** (`AccessorGenerator`, `ConstructorGenerator`, `BuilderGenerator`, `ContextualAnalyzer`,
  and anything else that reads Java) into `jcodebuddy-watch-tools`, leaving the watcher with its own port,
  the server, the audit and the non-Java tools. This is the coherent reading: *what to do about a Java file*
  is JCodeBuddy's work, and the watcher only knows a directory changed; or
- **invert a port**: the agent defines "read a Java member out of source" and the library implements it,
  which keeps the tools in the agent but means the watcher owns an interface only a JCodeBuddy module can
  implement.

The agent's POM was restored so the tree stays green, and the check is back to 8; this slice's value is the
library and the moved tool, not a lower count. The next pass picks one of the two options above and moves the
family in one go, because moving half of it is what the compiler rejected here.

3. **`jwa-builder*` — the bridge needs a *library* home, and that is a decision.** `WatchAgent` registers
   `new RecordBuilderGenerator()` (an `ActionTool` that drives `BuilderTransformationEngine`), and
   `TestDiscovery`/`TestStateSync` are `@GenerateBuilder` demos. The obvious home — `project-automation`,
   which already depends on `jwa-builder` — **is forbidden**: no other module may depend on a
   `project-automation` (AGENTS.md § 1.1), and the watcher's app would have to, to register the tool. So this
   is either a new small `jcodebuddy-*` library that the app may depend on, or the `record_builder` tool is
   dropped from the agent. Recorded as a decision to take, not a move to make.

**What remains (13, and the order to take them in):**

- `java-watch-agent` → `jwa-builder-api` and `jwa-builder` (2 dependency violations, plus
  `RecordBuilderGenerator` importing `BuilderTransformationEngine` and `TestDiscovery`/`TestStateSync`
  importing `GenerateBuilder`). These are *code actions* — "read a Java type out of source and complete it" —
  which is dev-time codegen, i.e. `project-automation`'s subject, and it already depends on `jwa-builder`; the
  slice is to move those generator classes (and their callers/registration) there and drop both dependencies.
- Jackson in the agent's own core: the dependency plus imports in `AuditManager` (7 uses), `WatchAgent` (2) and
  `CommandServer` (2). The watcher writes and reads its own small fixed documents, so this is hand-rolled
  writing (or a library the watcher chooses for itself) — never this workspace's Jackson.
- `java-watch-run-sample`: the dependency plus `DataProcessor`/`PersonData`/`SampleMain`, whose Jackson demo is
  the *point* of that module but cannot stay under `watch/`. Either the demo becomes self-contained or the
  sample module leaves the `watch/` group; the step's record should say which and why.

**Gate:** `bun scripts/check-watch-standalone.js` exits 0, plus each touched watch module's own build and tests
green, and `java-watch-agent` still passing whatever it tests about the seam it used to expose as
`CodeGenerator` (the port moves, its behaviour does not).

**Done when:** the watcher knows a directory changed and decides nothing about Java, and 3.0i can dissolve the
SPI into the engine without giving `java-watch*` anything.
### 3.0b — Class relations in the class index
**Who:** agent · **Size:** M

> **Under DEC-037 this is engine work.** The relations belong to the model the engine owns (3.0f moves it into
> `jcodebuddy-core`); until that move this step lands wherever the index lives then, and its gate is stated in
> terms of the index's own module so it stays true either way.

DEC-029's class index is real and tested: `classes.json`, one row per type, keyed by FQN, with the file's
path, its content checksum, the checksum instant, size, and the type's kind and modifiers
([`ClassRecord`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/ClassRecord.java),
[`TypeFacts`](../jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine/index/TypeFacts.java)).
It records **no relations**: nothing in a row says what a type extends or implements, and there is no
reverse index, so "what implements `CtxModule`" cannot be asked of it at all.

**Do:** add the relations to the index and to its writer: supertypes and implemented interfaces per type
(as resolved names, not as source text), and the **reverse** direction the consumers need (implementors,
subtypes) — with the storage decision from 3.0a in hand, and reusing the existing writer, format versioning
and validation rather than a second file. Keep the row's "one type, one row" property and keep relations
*derived* output: the index is regenerated, never hand-edited (DEC-026).

**Gate:** `GATE` green (the index lives in `hipster-entity-tooling`, so the recorded gate covers it), with
tests that a relation round-trips, that a relation to a type outside the module's sources is recorded as
the name it is (not dropped), and that an unresolvable supertype is visible as such rather than absent.

**Done when:** a consumer can answer "is A a subtype of B" and "who implements I" from the index alone,
without parsing a file.

**Landed 2026-10-02, in the engine (`jcodebuddy-core`), and the tests corrected the design twice.**

- **`TypeRelation(name, kind)`** (`engine.index`): one supertype, the name as written, and whether it came
  from an `extends` or an `implements` clause. `ClassRecord` and `TypeFacts` carry a `List<TypeRelation>`
  (`extends` first, then `implements`), `ClassRecord.sameTypeFacts` compares them — a type that starts
  implementing an interface must not look unchanged to a pass comparing only file content — and
  `ClassIndex.subtypesOf(name)` answers the reverse direction ("who implements I") from the table alone.
- **The writer always emits `relations`, even as `[]`.** `enclosing` and `generated` are omitted when they say
  nothing; this field is not, because *not recorded* must not read as *none* — a pre-3.0b table has no key at
  all, which is a gap rather than a fact. A test writes a table, takes the field back out, and reads it as
  empty to pin exactly that. An unknown `kind` makes the reader refuse the table, in the same spirit as an
  unknown format version.
- **Two corrections the tests forced, both recorded rather than smoothed over.** *(1)* `TreeQueries.supertypeNames`
  answers with the **last segment only** (`Serializable` for `java.io.Serializable`), so recording it threw the
  qualification away and made two different types one relation; the extraction now uses `supertypeTexts` and
  removes balanced `<…>` groups, so the name is the form written without its arguments — and balanced groups
  rather than a cut at the first `<`, because `Outer<T>.Inner` is two names and one argument. *(2)*
  `ClassIndex.write()` hashes every row's file and refuses a row "whose size and checksum describe a file
  nobody read", which the first version of the fixture tripped; the tests now write the sources they describe,
  which is better evidence anyway.
- **The interface trap is a test, not a comment.** `interface PersonSummary extends a.b.Person` must record
  one `EXTENDS` relation: the LST holds an interface's `extends` clause in `getImplements()` and its
  `getExtends()` is `null`, so a reader of `getExtends()` alone finds *no* supertype for the commonest
  declaration in this project (DEC-030's measured trap). A class keeps the two clauses apart in one list.
- **Evidence:** `TypeRelationsTest` (6 tests: round trip through write+read; an outside type recorded as
  written and honestly *not* a row; the interface trap; a class's two clauses; the reverse lookup, including
  that the match is the spelling used and that an unresolvable name finds no subtypes while staying visible as
  a name; and the pre-3.0b table reading as not-recorded). Core 59 tests `BUILD SUCCESS`.
- **DEC-029 amended** with the field, its three inner choices, the reverse lookup and the
  refuse-unknown-kind rule; and its two stale references to the tooling (`MetadataLocations.kindOf`,
  "vendored into the tooling") now name the engine.
- **What this step does not do:** the names are *not* resolved to FQNs, so a simple name is matched as
  written. That limit is in DEC-029, in `TypeRelation`'s javadoc and in `subtypesOf`'s, and closing it is
  search's work (3.0h) — a resolution helper here would be the confident-wrong-answer 3.0f-3 exists to
  prevent.

### 3.0c — The cache: what is cached, and what invalidates it
**Who:** agent · **Size:** M

> **Under DEC-037 this is the other half of 3.0g**, which is where it is implemented and where its answer is
> recorded: the engine publishes one freshness contract — change detection, invalidation, events — rather
> than a cache per module. Read this step's questions as the questions that contract must answer, and settle
> them once, in 3.0g, instead of here.

The checksum machinery exists (`ContentHash`/`Wyhash64`, the watch cache behind
`WatchMetadataProvider`, the step 1.3 work) and it answers "has *this file* changed". The relations from
3.0b introduce a second question the current cache cannot answer: a row for `A` depends on the file
declaring `A` **and on the types it names** — edit `B` so that `A extends B` stops resolving, and `A`'s row
is stale even though `A.java` was not touched.

**Do:** define what is cached (per-file facts, relations, the reverse index — and which of them are stored
or recomputed), the invalidation rules (checksum of the declaring file; a dependency edge for each named
relation; what happens on a rename, a delete and a new file), where the cache lives (`.jcodebuddy/`,
DEC-026) and in what format, and whether the arena-backed index (`LongToLongsIndex`, mmap) is the storage
for the relation half — which is what step 2.3's backend decision is meant to settle. Say explicitly what a
*partial* cache means: a stale index must be detectable, because a generator that reads a stale relation
and emits code is the failure this whole layer exists to prevent.

**Gate:** `GATE` green, with tests for the three transitions that matter: a file edit invalidates its own
row; an edit that changes a relation invalidates the *dependent* rows; and a deleted file removes its row
and the rows that named it (or marks them unresolved, whichever the decision says).

**Done when:** "is this index safe to generate from?" has a mechanical answer.

### 3.0d — One implementation of `TypeResolver` over the index
**Who:** agent · **Size:** M

> **Under DEC-037 this is the engine's own resolver**, because the SPI and the index end up in one module
> (3.0f/3.0i) — which is what makes an implementation possible at all: it cannot sit next to the index it
> reads while the SPI lives above it. If 3.0f lands first, this step is a thin layer over 3.0h's queries
> rather than a second implementation.

The seam from 3.0a needs at least one real implementation before any consumer can be written against it,
and the honest one is the metadata pass: index first, sources only when the index cannot answer.

**Do:** implement the contract from 3.0a over the index from 3.0b with the cache from 3.0c — including
`knownPackages()`, which exists on `TypeResolver` today and is exactly what a generator needs to choose a
name that will resolve. A missing type must answer `null` (the interface's present contract) and the caller
must fail loudly; do not soften that into an empty definition.

**Gate:** `MODULE` for the module hosting it green — and *one generator test that runs against the resolver
without being handed a file at all*, which is the property the whole phase is about.

**Done when:** a generator can be written as a function of the project model plus its own inputs, with no
file parsing of its own.

### 3.0e — Move hipster-ioc onto the metadata contract
**Who:** agent · **Size:** M · *(shape-defining)*

> **Under DEC-037 this is one consumer of 3.0j.** The generator becomes a reader of the engine like every
> other consumer, which is the point of moving the engine first: hipster-ioc's own step should not have to
> negotiate what the model is.

The prototype implements the per-file `CodeGenerator` SPI and reads the sibling module interface itself.
Both go: hipster-ioc becomes a project-wide generator handed the metadata (3.0a–3.0d) and a context to
emit code for, and its passes stop pretending to be file passes.

**Do:** replace the sibling lookup in `ContextReader` with metadata queries (the module interface, the
factories and the relations all come from the index); remove `IocContextGenerator`'s `CodeGenerator`
implementation rather than re-classifying it (a project-wide generator is not a per-file generator with a
label); keep `bun scripts/ioc-gen.js` as the entry point; update `hipster-ioc/hipster-ioc-tooling/README.md`, DEC-036
§ 11 and the ROADMAP's status block to say what the generator consumes and what it no longer does.

**Gate:** `MODULE` for `hipster-ioc-tooling,hipster-ioc-test` green; the committed example still
regenerates byte-identically; and a test proves the generator never reads a source file itself (the read
seam is the metadata layer's).

**Done when:** hipster-ioc parses nothing, and the per-file SPI has no project-wide implementer.

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

**Done 2026-10-01 — the empty module has sources, and they generate Java the JDK accepts.**

- **`ContextReader`** reads one `@HipsterContext` interface through the LST (`SourceReader`/`TreeQueries`,
  DEC-030), into a small model (`IocModel`): beans (abstract no-arg accessors), factories (`default
  build<Bean>(...)` on the module interface, with their parameters and `@Circular` marks), `dependencies()`,
  the `ChildContext<P>` parent type, `impl()`, and both files' imports.
- **`DependencyOrder`** derives creation order topologically from the factory parameters, preferring
  declaration order among ready beans so the output is deterministic, and **refuses** a context whose beans
  form a cycle — leaving the file untouched — with `circular_dependency_unmarked` or
  `circular_dependency_marked_unsupported`. A factory parameter the context cannot provide becomes a
  **constructor parameter** of the generated class, so no generated code ever passes a `null`.
- **`ContextSource`** renders `<Context>Impl`: the DEC-035 file marker, creation in the computed order with
  each line naming its factory, one accessor per bean returning its field, and `getParent`/`setParent` when
  the context implements `ChildContext`.
- **`IocContextGenerator`** is the shared `CodeGenerator` (since 3.0i the engine's SPI,
  `hr.hrg.jcodebuddy.engine.codegen`), with a cheap
  `isApplicable` (a substring test, not a parse) and a `generate` that **returns text**; the text goes
  through `CooperativeCodegen.reconcileMembers`, so step 1.1's freeze and DEC-020's preservation apply to
  what this generator writes. It also refuses a `Supplier`/`DynamicResource` bean with no factory
  (`lazy_bean_needs_factory`) and a context that names its own implementation
  (`context_implementation_present`).
- **`IocGeneration`** walks a source root, writes only when the text changed, and writes the dependency graph
  to the **module** root's `.jcodebuddy/metadata/hipster-ioc/contexts.json`.
- Four new divergence kinds, registered in `DivergenceReporter.KINDS` **and** in the entity tooling's
  producer map (`ExampleDivergenceReportTest` fails on a kind nothing produces, which is the guard working).
- `IocContextGeneratorTest` (11) asserts the model, the order, the constructor-parameter case, idempotence,
  the freeze, all four refusals, the parent accessors — and **compiles the generated tree with the JDK's own
  compiler**, which is the assertion a string-matching test cannot make.
- **Three claims of DEC-036 were wrong and are amended in the record, not worked around:** the module
  interface is a **sibling file** (`CtxMainModule.java`), not a type in the context's compilation unit —
  which is why the first implementation found no factories at all; `impl` is a **context-level** attribute,
  so it suppresses the whole context rather than one bean; and a cycle marked `@Circular` **cannot** be
  generated as a settable assignment without a `Supplier` parameter, so both cycle cases are refused with
  distinct diagnostics and the `Supplier` form is named as the follow-up.
- One trap worth recording for whoever runs this module's tests: `-pl hipster-ioc-tooling` **without `-am`**
  silently uses the *installed* `hipster-entity-tooling` jar, so a change made there (step 1.1's freeze, the
  new kinds) appears absent and two tests fail for the wrong reason. With `-am` everything is green — this
  is doc/AGENTS.md's "changing a library means reinstalling it", met from the other side.

**Gate:** ✅ `MODULE` for `hipster-ioc-tooling` green — 11 tests, with the generated tree compiled by the
JDK; the entity tooling's divergence tests (`ExampleDivergenceReportTest`, `DivergenceReporterTest`,
`DivergenceKindTest`, 20 tests) green with the four new kinds.

### 3.3 — Make it runnable and documented
**Who:** agent · **Size:** M

**Done 2026-10-01 — the generator has an entry point, a README, and a committed example.**

- **`IocTool`** is the Java entry point: `--root <dir>`, `--indent <text>`, `--quiet`, `--help`, and an exit
  code that is **non-zero when any context was refused** — a cycle, a lazy bean without a factory, a named
  implementation — so a check can depend on it, while report-only divergences exit 0.
- **`bun scripts/ioc-gen.js`** is the documented way to run it (AGENTS.md § 2: the script is Bun JavaScript,
  the build step is Maven). It pins JDK 25 through `scripts/lib/toolchain.js`, compiles the module and its
  reactor dependencies, exports the classpath with `dependency:build-classpath -am` — so it runs *this
  checkout's* classes rather than whatever jar is in `~/.m2` — and forwards the generator's arguments. Its
  header records why it is not `mvn exec:java`, the same measured reasons `scripts/gen.js` gives.
- **The committed example**: `hipster-ioc/hipster-ioc-test/src/test/java/…/CtxMainImpl.java` — the real `CtxMain`, its
  package-private `CtxMainModule` sibling, and `this.mapper = buildMapper();`. `hipster-ioc-test` **compiles**
  with it, which is DEC-036's headline acceptance criterion, met on the module the decision names rather than
  only on a fixture tree. Found while doing it: the generated file inherited the marker annotation's own
  import, which it never uses — the reader now drops it.
- **`hipster-ioc/hipster-ioc-tooling/README.md`** is the module's front door: what it does, the generated layout, how to
  run it, the five refusals and why refusing is the answer, the graph's location, the **naming-contract table
  DEC-036 § 12 asked for**, and the boundaries (§ 1.1, DEC-030, DEC-026).
- **The ROADMAP now distinguishes what landed from what did not**: the generator and the graph are real; the
  browsable report page (DEC-027/029), the editor-agnostic navigation bullet, the embedded light HTTP server
  and the `@Circular` two-phase form are named as *not built*, rather than left as an implication of "Phase 2
  started".
- The `-am` trap from step 3.2 is worth repeating for this step too: `bun scripts/mvn-jdk25.js` needs
  `-DskipTests=true` (the wrapper refuses a `-D` token without `=`, by design), and a module built with
  `-pl` alone uses installed siblings.

**Gate:** ✅ `bun scripts/ioc-gen.js` runs from a clean checkout and reports `1 context, 1 implementation,
0 refused`; `MODULE` for `hipster-ioc-test` compiles with the generated file; the recorded `GATE` is green
(8 modules, `hipster-entity-tooling` 6:20), as are `GeneratorGuardTest` and `GateContractTest` (14).

**Two details worth keeping, because each cost a wrong answer before it was found:**

- The new README's relative links were written with a `../../` prefix copied from files two levels deeper,
  and the **link check** caught it — the gate did not, because the gate is the Maven set and does not read
  Markdown. Which check owns which mistake is worth knowing: `GATE` green did not mean the docs resolved.
- The graph's destination needed a **module-level** ignore. The root `.gitignore`'s `.jcodebuddy/metadata/`
  contains a slash, so it is anchored to the repository root and does **not** match
  `hipster-ioc/hipster-ioc-test/.jcodebuddy/metadata/` — the graph showed up as untracked until the module got its own
  `.jcodebuddy/.gitignore` in the shape `hipster-entity-example`'s states.

---

Every step below is real work somebody will have to do, and none of it can be written as a normal step
yet: each one's *content* is a function of a shape still moving, so its gate would have to be invented and
then rewritten. They are listed with the decision each waits on, so that "not scheduled" is visible and
specific rather than looking like an oversight. Nobody should start one before its "schedulable when" line
is true — and if one is started anyway, the first thing it must do is write that decision down.

### 3.4 — The `@Circular` two-phase form
**Who:** agent · **Size:** unknown until the shape is decided

DEC-036 § 5 was amended when the generator was built: closing a cycle needs the dependency resolved
*after* construction (a `Supplier`-style parameter, or a setter the generator may call), and neither shape
is expressible through the API today. Both cycle cases are refused with a diagnostic in the meantime —
`circular_dependency_unmarked` and `circular_dependency_marked_unsupported` — which is safe and permanent
for the unmarked case.

**Waits on:** how a lazily-resolved dependency is spelled in a context interface (`Supplier<Bean>`? a new
marker? a setter convention?) **and** the metadata contract from 3.0a — whether a type is even known to be
part of a cycle is a question about the relations index, not about one file.
**Schedulable when:** that spelling is in DEC-036 and an `@Circular` cycle generates running code.

### 3.5 — `init*` methods in creation order
**Who:** agent · **Size:** S once the shape is known

DEC-036 § 3 says the creation order "drives the `init*` methods", and nothing emits any: the prototype
generates fields, a constructor and accessors. Whether `init*` means one method per bean, one method per
context, or a hook the user overrides is a shape decision, not an implementation detail — the wrong answer
puts generated code into the user's edit path.

**Waits on:** the shape of the initialisation seam, and whether it belongs in the context class at all.
**Schedulable when:** DEC-036 names the seam and the order contract it has to satisfy.

### 3.6 — Region markers above the thresholds
**Who:** agent · **Size:** S

DEC-036 § 9 requires region markers only above thresholds (fields > 5, exposed beans > 3, factory methods
> 3) so a large context can be read and partially hand-edited. The prototype emits none, because the
thresholds only matter once the *layout* is settled — a marker pair around a layout that then changes is
churn in every generated file.

**Waits on:** the generated layout (field grouping, where the constructor sits).
**Schedulable when:** the layout stops changing and DEC-035's `region begin/end` ids are named.

### 3.7 — Cross-context `dependencies()` and `ChildContext` parent assignment
**Who:** agent · **Size:** M

DEC-036 § 6 has `ChildContext<P>` generate parent plumbing and — "when a declared dependency is a
`ChildContext`" — the parent assignment where the child is created. The prototype generates the plumbing
(field, `getParent`, `setParent`) and no cross-context creation at all: a context's `dependencies()` are
recorded in the graph and never used to build anything.

**Waits on:** how a context names and receives another context (constructor parameter? a generated
factory? a `ChildContext` chain?), which is the same question as 3.4 for a different edge — plus the
metadata queries from 3.0a–3.0d, because "is this dependency a `ChildContext`" is a relation, and the
prototype answers it by reading whichever files happen to sit beside the context.
**Schedulable when:** the shape of context-to-context creation is decided, including who constructs the
parent.

### 3.8 — The dependency-graph report page
**Who:** agent · **Size:** M

The graph exists as JSON (`contexts.json`, DEC-026's location) and there is no page. DEC-027/029 say the
page is a Bun renderer over that JSON, but the JSON's *shape* is the generator's model — it changed within
step 3.2 and it can change again with any of 3.4–3.7, so a renderer built now would be rewritten with it.
**Its kind is settled even while its shape is not**: this is the page DEC-027's 2026-10-01 amendment exists
for — project structure shown for navigation, relations shown as a picture — so it is a **`jsx6`** page and
its diagram uses **`jsx6`/`nodditor`**, not a self-contained vanilla file. Read jsx6's own `AGENTS.md` from
the checkout (rule § 2.9, steps 7.9–7.10) before starting. **Its renderer does not live under the root
`scripts/`**: that subtree stays dependency-free for the vanilla renderers, so this page gets a home of its
own that may declare a dependency (DEC-027's 2026-10-02 note, and DEC-036 § 10 amended to match).

**Waits on:** the graph model being settled (it is DEC-036 § 10's shape, and it follows the generated
shape).
**Schedulable when:** no step above would add or rename a graph key.

### 3.9 — Driving the generator from the dev-time pass and watch mode
**Who:** agent · **Size:** M

Today the generator runs from `bun scripts/ioc-gen.js` over a source root. DEC-036 § 11 says it is "driven
by the dev-time pass and by a CLI"; the pass and watch halves do not exist. Step 7.8 (the generator tiers)
is the precondition that *is* scheduled, because the tier is a property of what the generator reads.

**Waits on:** the generator's interface being stable — which is 3.0d/3.0e (it consumes metadata rather
than files, so the pass hands it the project model) plus step 7.8 for the file-scoped kinds, plus the shape
decision, since a watch-mode pass regenerates on save and would otherwise rewrite a prototype's output
repeatedly.
**Schedulable when:** 7.8 has landed and the emitted shape is settled.

### 3.10 — Retire `hipster-ioc-test`'s hand-written context
**Who:** agent · **Size:** S–M

`hipster-ioc-test` still carries the hand-written `CtxMain`/`CtxMainModule` that the generated
`CtxMainImpl` was produced from — the whole point of the generator, per the `.kilo` integration plan, is
that a developer stops hand-writing the wiring. Doing it now would freeze the shape for a real consumer,
which is the opposite of prototyping.

**Waits on:** the shape being accepted (this is the step that would *make* it load-bearing) **and** 3.0a–3.0e
being real — a hand-written context retired in favour of a generator that still reads files by hand swaps
one hand-maintained thing for another.
**Schedulable when:** DEC-036 is `Accepted`, and the migration is the acceptance test for it.

### 3.11 — Editor-agnostic graph navigation, and the embedded host
**Who:** human decides · **Size:** unknown

The [ROADMAP](../hipster-ioc/doc/ROADMAP.md) lists "editor-agnostic context navigation" and an embedded
light HTTP server for the graph. Both were named as **not built** when this phase started, and neither has
a shape: the navigation depends on 3.8's page, and the host may not be wanted at all once that page can be
opened from the repository.

**Waits on:** 3.8, and a person deciding whether a host is worth having.
**Schedulable when:** it is either wanted (as a step with a gate) or dropped with a reason — `[-]`, which
is a real answer, not a deferral.

---

## 8. Phase 4 — merge-java: Phase 9's residue and Phase 13

### 4.1 — Replace the hardcoded `WIDENING_CHAINS` table
**Who:** agent · **Size:** S–M

**Done 2026-10-01 — the table is gone, and deleting it corrected a real defect.**

- **The JDK name list is deleted.** `PRIMITIVE_CHAINS` replaces `WIDENING_CHAINS` and holds only the
  JLS 5.1.2 widening primitive conversions, which are the language's rule and the one thing no class
  hierarchy expresses (`int` is not a subtype of `long`). Everything else is resolved:
  `ResolvedTypeReader.declaredType(...)` attributes each side's declaration against the type context,
  and `TypeUtils.isAssignableTo(wider, narrower)` answers the question — a semantic the existing
  expectations confirmed rather than one I assumed.
- **The reader grew one query, not a second parse path.** `parseInto(...)` was extracted so the method
  reading and the new declaration reading share one parser setup, one analysis context and one failure
  rule; `declaredType` returns empty for "no answer", and the caller treats that as escalate — never as
  "not assignable".
- **`requiresTypeContext()` means "a classpath is a hard requirement *for this resolver*", and this
  resolver declares `false`** — it degrades instead. This is a decision taken after reviewing the first
  cut, which had it declare `true`; the first cut's argument ("one resolver that answers differently
  depending on how it was built is worse than one that insists on being built properly") is not wrong,
  it is answered by *saying so* rather than by refusing to run. `OverloadAddConflictResolver` keeps
  `true`: comparing resolved parameter types has no weaker form, so a set containing it is still refused
  at construction — and its message now names both the diagnostic name and the **class**, because "remove
  that resolver by hand" is only actionable if the caller knows which class to remove.
- **The degraded mode is visible, not silent.** Without a context the resolver decides the JLS primitive
  conversions and the common JDK hierarchies from `JDK_SUPERTYPES` (best effort: `ArrayList → List →
  Collection → Iterable`, `HashMap → Map`, the wrapper types into `Number`/`Comparable`/`Object`), and
  every resolution it produces that way carries a warning. With a context, a declaration that cannot be
  resolved — a project type missing from the caller's classpath is `JavaType.Unknown`, **not** absent,
  which `ResolvedTypeReaderTest` now pins — escalates with its own warning *instead of* falling back to
  the table: matching simple names while a classpath is available would be the name-based guess
  resolution exists to replace. `ConflictResolution` grew `warnings` for this and `MergeReportWriter`
  writes them, so step 4.2's review page can show the basis beside the decision.
- **The fallback table is not the old table.** It is written to the invariant the old one broke: every
  chain holds only true relations, and a type with two unrelated supertypes gets two chains
  (`Integer → Number → Object` *and* `Integer → Comparable → Object`, because `Number` is not a
  `Comparable`). So the boxed-sibling defect stays fixed in both modes.
- `TypeContext`'s and `ConflictResolver`'s javadoc and `merge-java/README.md` were updated with it, and
  `merge-java/CHANGELOG.md` carries the correction as an **appended** entry — that file's own rule
  forbids editing the one written an hour earlier.
- **Follow-up delivered with it: `MergeFileTool --classpath`.** The single-file tool could not be told
  the project's classpath, so a conflict about the project's own types could only escalate. It can now
  (`--classpath <entries>`, path-separated, repeatable, jars or class directories, each checked to
  exist), added **to** the JVM classpath rather than replacing it — measured, not assumed: a parser given
  only the extra entries resolves neither the project's types nor `java.util`, so a replacing flag would
  make "resolve my types" mean "stop resolving everything else" (`TypeContext.withRuntimeClasspathAnd`,
  pinned by `ProjectClasspathResolutionTest`). The single-file variants and their classpath rule are
  tabulated in `docs/CONFLICT_FILE_TOOL.md`; none of them uses the degraded no-context mode, which stays
  what it was described as — a capability for a library caller and for future use.
- **A pre-existing veto came out of that work, and is now step 4.5.** With the classpath the type change
  *is* decided — the report shows `[TYPE_CHANGE/AUTO] 'Widget' is a widening of 'Gadget'` — and the block
  is still left, because detection emits the residual `STRUCTURAL_CHANGE` alongside the recognised
  conflict (deliberately: a residual that replaced the recognised ones once lost a mechanical import
  addition) and the tool's application rule requires exactly one resolution to claim the block. The
  classpath changes what the resolution says, which is what the fixture and the review render carry, not
  whether this tool writes the block. Pinned by a test so it cannot change unnoticed.
- **Two defects the table was hiding, both now pinned by tests.** (1) The boxed chain read the primitive
  lattice across the wrapper classes, so `widens("Long", "Integer")` was **true** — but javac rejects
  `Long x = anInteger`, and the two are siblings under `Number`; auto-adopting the `Long` declaration
  would have broken every reader assigning the result to an `Integer`. That pair now escalates.
  (2) `TreeSet` was listed against `AbstractSet`/`Set`/`Collection`/`Iterable`, so the interfaces it
  actually implements — `NavigableSet`, `SortedSet` — were absent, and a pair the resolver could have
  decided went to a human. Incompleteness was not neutral: it handed work back.
- **Generics changed meaning, deliberately.** The canonical token still drops type arguments (it is what
  the lattice compares), but resolution sees them, so `List<String>` → `Collection<String>` is a
  widening and `List<String>` → `Collection<Integer>` is **not** — a distinction the old string
  comparison could not make.
- **Docs updated with the code**: the resolver's page (its decision list, a new "What changed when the
  table was deleted" section, and the no-context example), the four live statements that called this
  table the remaining approximation (`merge-java/README.md`, `IMPLEMENTATION_PLAN.md` in two places,
  `IMPROVEMENTS_DELIVERED.md`), and an appended `CHANGELOG` entry — appended rather than edited, because
  that file's own rule says so.
- **The doc-injection gate caught the drift, not the Maven gate.** `ResolverDocsTest` failed with
  "rendered block is out of sync … re-run: npm run inject:examples" the moment the test regions changed,
  which is exactly what it exists for; `npm run inject:examples` re-rendered five blocks. Worth knowing
  which check owns which mistake, again: a green compile says nothing about a doc that quotes the code.

**Gate:** ✅ `MODULE` for `merge-java` green — **683 tests, 0 failures**, including the 68 in
`TypeChangeConflictResolverTest`, the resolver-docs suite and the whole three-way/verification set.
Which table entries became unreachable is in the commit message, as this step requires.

### 4.2 — Phase 13, step 1: the read-only per-conflict review render
**Who:** agent · **Size:** M

Today a resolution is reported as a count. The user sees *that* something was resolved, never *what was
decided and why* — the strategy, the explanation, the fix paths not taken, the verification outcome, the
sticky decisions replayed.

**Do:** render exactly that per conflict — base / branch 1 / branch 2 beside the resolved code, with the
resolver's explanation and fix paths — as a Bun renderer over the JSON
[`MergeReportWriter`](../merge-java/scripts/merge-report/render.js) already writes, following DEC-027/029
(every link verified before it is written, output under the module's `.jcodebuddy/`). **This is a `jsx6`
page, not a vanilla one**: DEC-027's 2026-10-01 amendment classifies it as interactive/advanced — three
branches beside a resolution, an explanation, fix paths, navigation between conflicts — and 4.3 turns it
into an action display that writes decisions back, which is state. The merge *summary* page stays vanilla.
Read jsx6's own `AGENTS.md` from the checkout (rule § 2.9, steps 7.9–7.10) before writing it. Read-only
first: reviewing what the tool did must not require trusting it.

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

### 4.5 — The residual structural conflict should not veto a block it only partly overlaps
**Who:** agent · **Size:** S–M

Found while adding `--classpath` (step 4.1's follow-up). Detection emits the residual
`STRUCTURAL_CHANGE` **alongside** the recognised conflicts — deliberately, because a residual that
*replaced* them once lost a mechanical import addition — and `MergeFileTool` applies a block only when
**exactly one** resolution claims it. So a block whose real change is one resolvable thing (a widened
declaration, an added import) can be left `LEFT_MANUAL` by a residual that describes the same lines
without adding anything. Measured, on a two-line block whose only change is a widening that *was*
decided:

```
block 1 (lines 4-8): LEFT_MANUAL TYPE_CHANGE - [TYPE_CHANGE/AUTO] 'Widget' is a widening of 'Gadget', …
  [STRUCTURAL_CHANGE/MANUAL] StructuralChange could not resolve STRUCTURAL_CHANGE automatically;
  a reviewer must choose. Multiple conflicts claim this block and at least one is manual.
```

That is a property of the decision rule, not of the classpath: the resolution is right and the block
still waits for a human.

**Do:** decide what "claims" should mean. The candidates, in the order I would weigh them:
(a) a resolution claims a block when its region **overlaps** the block's changed lines, and a residual
that is fully *subsumed* by a recognised resolution's region is dropped from the block's decision — the
residual is still emitted and still reported, it just stops vetoing;
(b) keep the veto but let the report say which conflict caused it, so a human is not left guessing;
(c) leave it and document it (what this plan did for now — see the limitation in
[`CONFLICT_FILE_TOOL.md`](../merge-java/docs/CONFLICT_FILE_TOOL.md)).

Note what must **not** change: a residual that describes lines *no* other conflict explains must keep
vetoing, or the module loses the exact case the alongside-emission exists for.

**Gate:** `MODULE` for `merge-java` green, with tests for both halves — a subsumed residual no longer
vetoes (the type-change block applies, and the applied code compiles), and a residual covering lines no
recognised conflict explains still does.

**Done when:** `LEFT_MANUAL` for a decided conflict is explained by something other than a structural
residual with nothing of its own to say.

---

## 9. Phase 5 — webview: close the suite

> **Every UI step in this phase builds on `jsx6`** (rule § 2.9, [`AGENTS.md` § 2](../AGENTS.md)): no UI is
> written against a remembered version of the library, and the checkout's own `AGENTS.md` is the guidance
> to follow. **Diagrams and relations use `jsx6`/`nodditor`** (DEC-027's 2026-10-01 amendment). Step 7.9
> sets the checkout up and step 7.10 records what the libraries can and cannot do — a capability they lack
> is **reported** there rather than worked around in a page. A webview step that finds the checkout missing
> should set it up rather than reach for another library.

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
([`BuilderTransformationEngine(String indent)`](../jcodebuddy/jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java),
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
never calls it (`jcodebuddy/jcodebuddy-agent/src/main/resources/web/` has no `jump` and no `7979`).

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

Phase 4's second box. Nothing under `jcodebuddy/jcodebuddy-agent/` references OpenRewrite today. It must be built
the repository's way (DEC-030): read through `SourceReader`, query through `TreeQueries`, splice text —
not parse with a second parser and not reprint a tree.

**Gate:** one real tool (the plan's own example — a rename or a migration recipe) runs from the agent's
menu against a sample and is asserted by a test.

### 7.6 — Decide the three remaining `todo.java_watch2.md` items
**Who:** agent + maintainer · **Size:** S

Each is a decision, and silence is the only wrong outcome:

- **A configurable delay for delete events** — a debounce exists and is configurable
  ([`BatchedFileWatcher`](../watch/java-watch-core/src/main/java/hr/hrg/watch2/core/BatchedFileWatcher.java),
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

### 7.8 — Two kinds of generator: file-scoped and project-scoped
**Who:** agent · **Size:** M

Rule § 2.8 states the distinction; nothing in the tree expresses it. One SPI — `CodeGenerator<T>` with
`isApplicable(CodeContext)` and `generate(CodeContext)` — describes a **file-scoped** generator, and its
javadoc says so ("a generator is therefore safe to offer to every file — the cost of asking is the
predicate"). The kinds are not two shades of the same thing, and the correction that made this step clearer
is worth stating: **a generator that needs the project's type relations is not a file generator with a
bigger appetite — it is a different kind of program.** hipster-ioc is the example: it cannot work on single
files at all, because "who extends whom" is not in any file, and its input is the project's **metadata**
(steps 3.0a–3.0e), not source text it parses itself.

Three facts about the current tree:

- `IocContextGenerator` implements the file-scoped SPI and is **not** a file generator: it reads the
  sibling `<Supertype>.java` beside the context, and the pass writes a whole-tree artifact
  (`contexts.json`). A caller holding a list of `CodeGenerator`s cannot know that, and the generator's own
  `isApplicable` (a substring test on the file's text) is deliberately cheap precisely *because* the SPI
  promises the file is all that matters. Steps 3.0e removes that implementation rather than re-labelling
  it — a project-wide generator should not be wearing a per-file interface at all.
- `ActionToolAdapter` is a **wrapper** whose kind is its delegate's: it forwards `isApplicable`/`generate`
  to an `ActionTool`. All five wrapped tools read only `context.getFilePath()`, so they are file-scoped
  today — but a kind that cannot be delegated cannot describe the one implementer that already exists as a
  wrapper.
- `EntityMetadataGenerator` is project-scoped by construction and therefore safe: its entry points take a
  source root and a package list and write generated Java plus `.jcodebuddy/metadata/entity/…`, so there is
  no per-file door to walk through.

**Do:** put the distinction in the engine's SPI as a type rather than a comment (`hr.hrg.jcodebuddy.engine.codegen`,
which absorbed `jcodebuddy-codegen-api` at step 3.0i). A project-scoped
generator's contract is **metadata in, code out** — it is handed the project model (3.0d's resolver) and
what to generate for, and it never receives a single-file `CodeContext` as its input. Concretely: a second
interface for the project-scoped kind, the delegable case stated as a contract (the one wrapper that existed,
`ActionToolAdapter`, was deleted in step 3.0s, so this is a shape to define rather than a class to fix), and
the caller side refusing to offer a project-scoped
generator per file. Say which kind each generator is on its README, and amend DEC-036 § 11 with the
classification and with the rule this whole thread has been circling: **a type the metadata cannot resolve
is reported, never inferred as absent** — the step 3.2 evidence is the reason.

Also refine rule § 2.8 in this step, because using it exposed a conflation: **a generator's kind is what it
reads; a pass's kind is what it writes.** The IoC generator returns text (no side effects) while
`IocGeneration` writes the graph, and a rule that says "needs another file *or* writes a tree artifact"
would call the same generator two kinds depending on which half you looked at.

**Gate:** `MODULE` for `jcodebuddy-core,jcodebuddy-agent` green, with tests that a project-scoped
generator is not offered per file, that a wrapper's kind follows its delegate, and that an unresolvable
type produces a diagnostic instead of an empty answer.

**Done when:** a caller holding a generator list can tell the kinds apart without reading the generator's
source, and no project-wide generator implements the file-scoped interface.

### 7.9 — Set up the `jsx6` checkout every UI must be built from
**Who:** agent · **Size:** S–M

Rule § 2.9 and [`AGENTS.md` § 2](../AGENTS.md) settle *that* any UI built here uses
[`jsx6`](https://github.com/hrgdavor/jsx6); nothing in the tree settles *how an agent gets it*, and the
instruction is explicit that it is a **local checkout updated on demand**, not a version in a lockfile.
Nothing exists yet: there is no `jsx6` directory, no ignore entry for one, and no record anywhere that a
UI author is supposed to read jsx6's own `AGENTS.md` before writing JSX.

**Do:**

1. **The checkout.** `git clone https://github.com/hrgdavor/jsx6` into the temporary folder the rule
   names — default `<repo>/.jsx6/`, overridable with `JCODEBUDDY_JSX6_DIR` — and add the ignore entry so a
   clone is never committed. Update with `git -C <dir> pull --ff-only`. **Not `target/`**: the recorded
   gate runs `clean test`, so a checkout there would be deleted by every gate run — and the whole point is
   a local copy that survives until it is deliberately updated.
2. **Read, then record.** `<dir>/AGENTS.md` first — it is a router, so follow it to what *using* the stack
   needs (in the version read on 2026-10-01 that is `docs/stack/README.md`: setup, signals, the JSX/DOM
   contract and the rules that fail silently) — and write down in this step's record the facts a UI author
   here needs: the package names a consumer imports, whether a build/transform step is required, how a
   page or webview loads it, and how the checkout's own gate is run. Cite the jsx6 commit that was read,
   because a moving dependency means "what I read" is part of the evidence.
3. **Inventory the UI surfaces**, so the rule has a subject: the webview clients and kit
   (`webview/kit`, `webview/webview-jetbrains`, `webview/webview-vscode`, `webview/webview-eclipse` if
   present, `webview/jwa-sidecar`) versus the **generated report pages** (the entity index and the merge
   review render).
4. **The scope question is already decided — record it, do not re-open it.** DEC-027's 2026-10-01
   amendment splits the rule: a page with **no UI or minimal UI** stays vanilla, an **interactive or
   advanced page** is `jsx6`, **relations and diagrams** are `jsx6`/`nodditor` (the entity reference page
   and the merge summary stay vanilla; the per-conflict review render in step 4.2 and the dependency graph
   in 3.8 are `jsx6` pages, the latter with nodditor). Read that amendment and the pages it classifies
   before touching a renderer; a page that changes class is a change to that amendment, not a quiet drift.
5. **Point the UI work at it.** Phase 5's banner already sends a webview step here; make sure
   `webview/PLAN-webview-suite.md`, `webview/kit/doc/page-authoring.md` (which carries a *no bundler* line
   of its own) and any UI-facing README name the checkout + jsx6's `AGENTS.md` rather than repeating
   either, and reconcile that page-authoring contract with what the checkout says a `jsx6` page needs — the
   view-time no-network property stays, the build shape is whatever the checkout documents.
6. **Do not put a `jsx6` page's build under `scripts/`, and do not look for a guard that enforces the
   library choice — there is none, by decision (2026-10-02).** `scripts/` is the vanilla renderers' home and
   stays dependency-free; a `jsx6` page's build (and wherever a dependency is declared) gets its own home —
   a package beside the page's renderer, or the module that owns the page — rather than an entry added to
   `scripts/`. Say which home in the record, because the next agent will otherwise try the obvious thing.

   **The choice itself is an instruction, not a check.** *Simple page → vanilla JavaScript; more complex
   page → `jsx6`, never React / Svelte / Solid / Vue / Preact / Lit or another framework of that kind* —
   stated in `AGENTS.md` § 1–§ 2 and DEC-027's amendment, and deliberately not enforced by the build: how
   complex a page is, is a judgement, and a strict guard over a judgement is the kind of check this
   repository does not want here (contrast step 7.8, where a generator's *kind* is a mechanical fact and
   therefore worth a type and a test). `HtmlRenderBoundaryTest` is untouched by this rule and keeps only
   what it always had about the page it was written for — the entity reference is framework-free and its
   renderer's subtree is dependency-free, which is that *minimal* page's own contract. DEC-036 § 10's "a
   JavaScript renderer under `scripts/`" is amended to match, since the graph page is a `jsx6` page.

   **Read the guard's actual scope before deciding it needs an exception — it does not.** Its framework
   check runs over an explicit five-file list (`RENDERER_CODE`, the `scripts/entity-html/*.js` sources), so
   a `jsx6` page elsewhere is simply not its subject; no exception mechanism is wanted, and adding one
   would weaken the one check that keeps the minimal page honest. **Recorded deviation:** an earlier cut of
   this change hardened that test for the new rule — it walked the `scripts/` subtree for dependencies, and
   asserted that `AGENTS.md` and DEC-027 carry both halves of the split. Both were **reverted (2026-10-02)**
   when the decision became *instruction, not guard*: the assertions were a strict check over a judgement
   call, which is what the maintainer did not want. The test file is back to exactly what it was before, so
   nothing in the gate fails over which library a page uses.

**Gate:** the checkout reproduces from the documented two commands on a clean machine; `<dir>/AGENTS.md`
was read and the consumption facts above are recorded with the commit read; the surfaces are listed **as a
record** with their class (vanilla / `jsx6` / `jsx6`+nodditor), and the page-authoring contract is
reconciled. `LINKS` green.

**Done when:** a future UI task can start from the written instruction — checkout, read jsx6's
`AGENTS.md`, know which surfaces are in scope and which class each page is — without re-deriving any of it,
and no UI is written against a remembered version of the library.

### 7.10 — What `jsx6` and `nodditor` can and cannot do for our pages
**Who:** agent · **Size:** M

Rule § 2.9 requires this the moment a page needs something the libraries do not have: **report it**, and
say which of the two answers it gets — a minor improvement that belongs in `jsx6`/`nodditor`, or a critical
gap that forces a decision about an additional library for that specific output. Nothing about the libraries
can be assumed from their names: this step is the assessment, and it needs 7.9's checkout and the page list
that 7.9 records (DEC-027's amendment: what each page must show).

**Do:** for each page class the repository actually has or has scheduled — the minimal vanilla page, the
interactive review page (4.2/4.3), the project-structure/navigation page (3.8), and the diagram/relations
layer (3.8, nodditor) — take the capability list from the page's own requirements and check it against what
the checkout documents and implements (read the code, not only the READMEs, and cite the commit read). Then
write the outcome down: **sufficient**, or a gap with a recommendation. A gap is *minor* when the library
can plausibly absorb it and the finding belongs upstream; it is *critical* when the specific output cannot
be produced with them at all, which is a decision about an additional library for that output — taken with
the evidence, and recorded as its own decision rather than assumed in a page.

**Gate:** a written assessment covering each page class, naming the jsx6/nodditor commit read, with every
gap classified minor/critical and the critical ones routed to a decision (not to a workaround).
`LINKS` green.

**Not in this step, by decision (2026-10-02): the page *class* is an instruction and is never enforced by a
guard.** How complex a page is, is a judgement, and a strict check over a judgement is exactly what the
maintainer does not want here — so the statement of which page is what lives in DEC-027's amendment,
`AGENTS.md` § 1–§ 2 and this plan, and **no test fails because an agent chose vanilla where `jsx6` would
have been better or the other way round**. The contrast with step 7.8 is deliberate: a generator's *kind*
is a mechanical fact (what it reads), so it earns a type and a test; a page's complexity is not. What
`HtmlRenderBoundaryTest` keeps is what it always had and only about the page it was written for — the
entity reference is framework-free, its renderer's subtree dependency-free, and its links verified. A page
changing class is a change to DEC-027's amendment, never a quiet drift.

**Done when:** the answer to "can we build this page with jsx6/nodditor?" is written down with evidence,
and a missing capability has an owner (the library, or a decision) instead of a silent workaround in a page.

---

## 12. Phase 8 — human-gated observations (no code; a checklist)

These are not "later"; they are the only deliverable that needs a person. Each ends in a dated,
version-named record, because a claim that is not observed is not a claim
([`doc/ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md)).

| # | What | Who | Record lands in |
| --- | --- | --- | --- |
| 8.1 | JetBrains § 8's five maintainer questions (vendor identity, the empty-allow-list default, one vs two settings services, dropping Kotlin, plan location) and acceptance criteria 2/5/7 in a running IDE | maintainer | [`plan.reimplement.md`](../webview/webview-jetbrains/plan.reimplement.md) § 8, and `webview-jetbrains`' own docs |
| 8.2 | Eclipse 4.41 workbench observations — the caret landing, the unsaved buffer edit and the single `Ctrl+Z`, the dropins install layout, the two-live-hosts claim; then answer **Q2** (dropins vs p2) | maintainer | [`ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md) § 1a/§ 2a, then `PLAN-eclipse-host.md` |
| 8.3 | `java-watch-agent` Phase 4's lightweight IntelliJ/VS Code hooks (the existing `intellij-jwa`/`vscode-jwa` are sidecar clients, not these) | maintainer decides, agent implements | [`jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md) |
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

Legend: `[ ]` open · `[x]` done · `[~]` blocked (say why) · `[-]` dropped (say why) · `[TBD]` **waits on
a decision that is not made** — deliberately unscheduled, with the decision named (see Phase 3's banner;
it is not the same as `[~]`, which waits on something outside the plan, nor as `[ ]`, which is ready to
start)

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
| 2.3 | Decision-grade arena run + the backend decision | agent | S | `[x]` |
| 3.0a | Settle the engine decision's open points (DEC-037, ADR first) | agent + maintainer | S–M | `[x]` — DEC-037 `Accepted`, DEC-038 created |
| 3.0b | Class relations (supertypes/interfaces + reverse) in the class index | agent | M | `[x]` — engine's `TypeRelation` + row `relations` (always emitted), `subtypesOf`; 6 tests; names stay as written, resolution is 3.0h |
| 3.0c | The cache: what is cached, and what invalidates it | agent | M | `[ ]` |
| 3.0d | One implementation of `TypeResolver` over the index | agent | M | `[ ]` |
| 3.0e | Move hipster-ioc onto the metadata contract (parses nothing) | agent | M | `[ ]` (shape-defining) |
| 3.0f | The engine's skeleton in `jcodebuddy-core`, and the model it carries (DEC-037) | agent | L | `[x]` — 3.0f-1 classification, 3.0f-2 move + six inversions, 3.0f-3 answer contract, 3.0f-4 pass unchanged; members and relations are 3.0b's |
| 3.0g | Freshness: the watch loop, its events and its invalidation | agent | L | `[x]` — `engine.fresh`: host reports, engine interprets; dependents from 3.0b relations; SAFE/STALE/UNKNOWN; 8 tests incl. DEC-038's "no watcher" made mechanical |
| 3.0h | Search: the queries every consumer asks | agent | M | `[x]` — `engine.query.MetadataQuery` over a set of indexes: FQN/kind/modifier/package/path + relations both ways, name resolution, `NotCovered` for members (3.0r); annotations answered, added 2026-10-02; 6 tests |
| 3.0i | Dissolve `jcodebuddy-codegen-api` into the engine | agent | M | `[x]` — five types split into `engine.query` + `engine.codegen`, module deleted, consumers re-pointed; also fixed the migration sweep's blind spots (a rename had silently dropped 21 sources) |
| 3.0j | Move the remaining consumers onto the engine | agent | L | `[ ]` |
| 3.0k | Grow the recorded gate to cover the engine's contract | agent | S | `[ ]` |
| 3.0l | Extract the marker leaf out of `jcodebuddy-core` (DEC-038) | agent | S | `[ ]` |
| 3.0m | `metadata-server` becomes `jcodebuddy-meta` (DEC-038) | agent | M | `[ ]` |
| 3.0n | Absorb `jwa-builder*` and collapse the duplicate splice path (DEC-038) | agent | L | `[ ]` |
| 3.0o | Group the reactor's modules: `watch/`, `hipster-entity/`, `jcodebuddy/`, `hipster-ioc/`, `webview/` (DEC-039) | agent | M | `[x]` — `merge-java`, `project-automation` and the doc trees wait on "others to be decided" |
| 3.0p | Audit the five earlier sidecar attempts against today's webview (DEC-039 amendment 2) | agent | M | `[ ]` |
| 3.0q | Merge what 3.0p found worth keeping, delete the rest | agent | M–L | ` [ ] ` (content decided by 3.0p) |
| 3.0r | The index grows members and annotations (DEC-029 format change) | agent | M | ` [ ] ` — what 3.0h's Do asked for and its model could not answer |
| 3.0s | `java-watch*` standalone: no Jackson, no OpenRewrite, nothing from this workspace | agent | M | `[x]` — 17 → 0: SPI deleted, sample rewritten, and the agent moved to `jcodebuddy/` and renamed `jcodebuddy-agent` (it was JCodeBuddy's server in the watcher's group) |
| 3.1 | The hipster-ioc ADR | agent | S | `[x]` (prototype: DEC-036 is `Trial`) |
| 3.2 | `CodeGenerator<GeneratedContext>` + dependency graph | agent | L | `[x]` (prototype: the emitted shape is provisional) |
| 3.3 | Make the hipster-ioc generator runnable and documented | agent | M | `[x]` (prototype) |
| 3.4 | The `@Circular` two-phase form | agent | ? | `[TBD]` — waits on how a lazily-resolved dependency is spelled |
| 3.5 | `init*` methods in creation order | agent | S | `[TBD]` — waits on the initialisation seam |
| 3.6 | Region markers above the thresholds | agent | S | `[TBD]` — waits on the generated layout |
| 3.7 | Cross-context `dependencies()` / `ChildContext` creation | agent | M | `[TBD]` — waits on context-to-context creation |
| 3.8 | The dependency-graph report page | agent | M | `[TBD]` — waits on the graph model being settled |
| 3.9 | Drive the generator from the dev-time pass and watch mode | agent | M | `[TBD]` — waits on 7.8 and the shape |
| 3.10 | Retire `hipster-ioc-test`'s hand-written context | agent | S–M | `[TBD]` — waits on DEC-036 being `Accepted` |
| 3.11 | Editor-agnostic graph navigation + embedded host | human | ? | `[TBD]` — waits on 3.8, or gets dropped with a reason |
| 4.1 | Replace `WIDENING_CHAINS` with supertype resolution | agent | S–M | `[x]` |
| 4.2 | merge-java Phase 13 step 1 — review render | agent | M | `[ ]` |
| 4.3 | merge-java Phase 13 step 2 — action display + sticky decisions | agent | M | `[ ]` |
| 4.4 | merge-java Phase 13 step 3 — LLM proposer behind the gate | agent | M | `[ ]` |
| 4.5 | Residual structural conflict should not veto a partly-overlapping block | agent | S–M | `[ ]` |
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
| 7.8 | Two kinds of generator: file-scoped and project-scoped | agent | M | `[ ]` |
| 7.9 | Set up the `jsx6` checkout every UI is built from (rule § 2.9) | agent | S–M | `[ ]` |
| 7.10 | What `jsx6` and `nodditor` can and cannot do for our pages (report gaps) | agent | M | `[ ]` |
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
