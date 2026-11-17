# hipster-sql-tooling

Dev-time source generator for `hipster-sql` entities.

**Status: first vertical slice, 2026-11-17.** The module is wired into the reactor and can discover
`@HipsterEntity` interfaces, build the first entity/property model, emit `<Entity>Visitor` and
`<Entity>Immutable` with the new LF text writer, and write per-entity metadata + a tracked ordinal
ledger. The remaining artifacts, the full metadata schema (locations/options) and the option surface
are the next phases; see [`plans/hipster-sql-processor-port.md`](../../plans/hipster-sql-processor-port.md).

The legacy annotation processor lives in the external `java-hipster-sql-1` checkout and is the
reference/oracle. This module does not modify it or the `hipster-sql` runtime.

## What is implemented

- `EntityDiscovery` — reads a source root through `SourceReader`/`TreeQueries`, finds top-level
  `@HipsterEntity` interfaces, applies the legacy getter rules (`get`, `is`/`has` for boolean),
  honours `@HipsterColumn(skip/keepRest)`, and records unreadable/nested sources as divergences.
- `EntityModel` / `PropertyModel` — package, simple name, FQN, table, source path, imports, accessors,
  accessor annotations and the `@Id` primary.
- `JavaSourceWriter` — LF by default, four-space indent, explicit `blank(n)`, one final newline, no
  trailing whitespace.
- `ImportCollector` — imports the simple names the emitted type text needs; no wildcard imports.
- `VisitorEmitter` — `<Entity>Visitor` with one `visit(...)` over non-transient, non-`keepRest`
  accessors.
- `ImmutableEmitter` — `<Entity>Immutable` with fields, empty/all-args/copy constructors, getters and
  both `getValue` forms; `--gen-json` adds `@JsonCreator`/`@JsonProperty` and the direct Jackson
  `serialize` implementation (`keepRest` maps/object nodes merge into the object); a `@Id` property
  drives `equals`/`hashCode`; accessor annotations are copied and initial values are emitted.
- `UpdateEmitter` / `BuilderEmitter` — `<Entity>Update` with change-set bits and setters, Jackson
  serialization under `--gen-json`, and the fluent `<Entity>Builder` that builds an Immutable. They
  are opt-in (`--gen-update`/`--gen-builder`).
- `MetaEmitter` / `AllEntitiesEmitter` — `<Entity>Meta` (columns, ordinals, impl classes,
  `fromResultSet`/`visitResult`, Jackson mapper with `--gen-json`, Mongo `encode`/`decode` with
  `--gen-mongo`, `ColumnMeta` annotations with `--gen-annotations`) and the package registry, opt-in
  with `--gen-meta`.
- `EntityMetadataWriter` — per-entity `*.metadata.json` (identity, source hash, table, primary,
  options, locations, supertypes/inputs, fields with ordinals/annotations, artifacts) +
  `manifest.json`; no source text.
- `OrdinalLedger` — append-only `<Entity>.ledger.json`, bootstrap/append/refused removal, explicit
  `--allow-reorder` renumbering, atomic write.
- `HipsterSqlGenerator` — CLI with `--source-root`, `--java-out`, `--metadata-out`, `--ledger-out`,
  `--check`, `--allow-reorder`, `--gen-update`, `--gen-builder`, `--gen-meta`, `--gen-json`,
  `--gen-mongo`, `--gen-annotations`, `--classpath`, `--packages`, `--version`.

## Run

Build and test:

```bash
JAVA_HOME=/path/to/jdk-25 mvn -o -f hipster-sql/hipster-sql-tooling/pom.xml test
```

Run the slice against the fixtures (export the classpath with Maven's
`dependency:build-classpath`, then use `target/classes:<that classpath>`):

```bash
java -cp "hipster-sql/hipster-sql-tooling/target/classes:$(cat hipster-sql/hipster-sql-tooling/target/cp.txt)" \
  hr.hrg.hipster.sql.tooling.HipsterSqlGenerator \
  --source-root hipster-sql/hipster-sql-tooling/src/test/resources/fixtures/basic/src \
  --java-out .tmp/hipster-sql-visitors
```

`--check` compares instead of writing and exits `1` on a difference.

## Scope boundary

- The generated code targets Java 8, because `hipster-sql` is a Java 8 runtime.
- The tooling itself is Java 25, like the rest of JCodeBuddy.
- `--metadata-out`/`--ledger-out` are implemented, including metadata options and member locations.
- A `@HipsterColumn(skip = true)` accessor is kept out of the ordinal/columns/Visitor, but
  `Immutable`/`Update` still implement its getter and metadata records it under
  `skippedAccessors`.
