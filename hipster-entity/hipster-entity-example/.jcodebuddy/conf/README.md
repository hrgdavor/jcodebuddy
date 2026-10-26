# `.jcodebuddy/conf/`

**Tracked**, unlike every other `.jcodebuddy/` subtree: the files here must survive a clone because they are facts
about the project that a reviewer should see in a diff. `conf/` is re-included by the module's
[`.jcodebuddy/.gitignore`](../.gitignore).

## `converters.json` — the converter manifest

**Generated and committed.** It is written by `hipster-entity-tooling`'s pass
(`EntityMetadataGenerator.writeConverterManifest`) into this directory, from the `TypeDivergence` records the pass
collects while resolving `--mapper` requests. It is **machinery output**: do not hand-edit it; regenerate it with
`bun scripts/gen.js`.

- **Source of truth** — `TypeDivergence` (`hipster-entity-tooling`'s `meta`): one record per target field whose
  source field was resolved, carrying both outcomes — a pair that needs a converter and one that was considered and
  needed none. The manifest is therefore **coverage**, not only a list of failures.
- **Ordering is part of the format** — pairs are sorted by source type, then target type, then location. The same
  inputs must produce the same bytes, and `MapperConverterContractTest` asserts two passes are byte-identical and a
  changed pair changes the file.
- **Why `conf/` and not `metadata/`** — DEC-026 says `metadata/` is derived and ignored, while `conf/` is the one
  tracked subtree. This file is a project fact a reviewer must see, so it goes in the tracked half; the step's own
  record (6.9) settles that rather than inferring it.
- **Committed == generated** — `ExampleConverterManifestTest` copies the example's sources, runs the same
  `PersonSummary:PersonDto` mapper request `scripts/gen.js` makes, and compares the result with
  [`converters.json`](converters.json). A difference fails the gate.
- **Example mapping** — `PersonSummary → PersonDto` is the read-projection pair from
  `git`-tracked [`PersonSummaryToPersonDtoMapper`](../../src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummaryToPersonDtoMapper.java).
  It resolves six pairs, all convertible as they stand (`convertersRequired: 0`); a pair that needs a converter is
  visible as `"converterRequired": true` with its reason.
