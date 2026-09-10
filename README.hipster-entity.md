# java-hipster-entity

`hipster-entity` is an interface-first Java entity model focused on extracting strong structural metadata that can be reused across runtime helpers, code generation, and higher-level tooling.

The project favors explicit metadata and predictable contracts over reflection-heavy runtime discovery. Runtime helpers such as proxies, builders, and factories are layered components that can be adopted incrementally.

## Quick start

- [Why hipster-entity](doc-hipster-entity/user/why-hipster-entity.md)
- [Getting Started](doc-hipster-entity/user/getting-started.md)
- [Core Concepts](doc-hipster-entity/user/core-concepts.md)
- [Materialization Guide](doc-hipster-entity/user/materialization-guide.md)

## Documentation

- [User documentation](doc-hipster-entity/user/README.md)
- [Project docs index](doc-hipster-entity/README.md)
- [Architecture decisions](doc-hipster-entity/architecture/DECISIONS.md)
- [Roadmap](doc-hipster-entity/roadmap/README.md)

## For contributors

- Proxy-backed builders and views are the baseline implementation path.
- Change tracking is ordinal/bitmask based for compact and predictable behavior.
- Generated/materialized implementations are targeted optimizations for measured hotspots, not the default.

## Tooling runner with Bun

A small Bun script is available to build and execute the tooling jar from the repository root.

Examples:

- `bun ./scripts/run-tooling.js build --mvn "D:\\programs\\mvnd\\bin\\mvnd"`
- `bun ./scripts/run-tooling.js run --mvn "D:\\programs\\mvnd\\bin\\mvnd" hipster-entity-example/src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java hipster-entity-tooling/target/person-summary-tooling-output`

Common options:

- `--build` — when running, build the tooling jar before execution
- `--force-build` — force a rebuild even when the jar already exists
- `--java <path>` — explicit `java` executable
- `--mvn <path>` — explicit Maven executable or `mvnd` launcher

The `build` command is now separate from `run` so you can build the CLI once and run it repeatedly.

When Maven or Java are not on `PATH`, pass explicit paths. Example for your environment:

- `--mvn "D:\\programs\\mvnd\\bin\\mvnd.exe"`
- `--java "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe"`

## Performance benchmark summary (2026-04-03)

JMH suite `EntityJacksonJmhBenchmark.deserialize` (forks=3, warmup=6×2s, measure=8×2s) produced:

- `PojoThroughDefaultJackson`: 2,565 ops/ms (±152)
- `deserializeViewThroughPersonSummaryBoilerplateDeserializer`: 1,256 ops/ms (±78)
- `deserializeViewThroughEntityJackson`: 1,216 ops/ms (±15.8)
- `deserializeViewThroughEntityJacksonSwitch`: 1,190 ops/ms (±20.2)

This validates `EntityJacksonViewDeserializer` table-based ordinal dispatch and optimized `ObjectReader` path.  
These numbers are in `target/jmh/results.json`.

