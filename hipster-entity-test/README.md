# hipster-entity-test

**Integration fixtures and tests for `hipster-entity`.**

This module exists to hold the tests that need more than one module at
once: a real `ViewMeta`, a real array-backed view, and a real Jackson
mapper in the same test. Those cannot live next to the code they cover —
[`hipster-entity-jackson`](../hipster-entity-jackson/README.md) has no
test dependency on `hipster-entity-core`'s fixtures, and
`hipster-entity-core` has no Jackson dependency at all — so the
integration surface is gathered here.

It is a test module, not a runtime module: nothing that ships should
depend on it.

## What lives here

### The `person` fixture (`src/main/java`)

A small, complete view built by hand so every module can test against
the same shape:

| Type | Role |
|---|---|
| `PersonEntity` | the marker: `extends EntityBase<Long>` |
| `PersonSummary` | the view: `firstName`, `lastName`, plus a `DERIVED` `age` and a `JOINED` `departmentName`, and a `metadata` field |
| `PersonSummary_` | the field enum implementing `FieldDef`, with `javaType()`, `forName`, `NAME_MAPPER` and `META` |
| `TrackedPersonSummary` | a fixture view that adds the write surface (`ViewWriter`) and the tracking surface (`ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>>`) to `PersonSummary`, so one view type can be handed to `ArrayBackedViewProxyFactory.createUpdatable` and driven through both contracts |

The fixture is **hand-written, not generated**: this module deliberately
does not depend on
[`hipster-entity-tooling`](../hipster-entity-tooling/README.md). The
generated-materialization case is covered inside the tooling module,
where the generator is local (see below).

`TrackedPersonSummary` is a *test-only* convenience. A real project gets
the same surface by putting `Write` and the tracking contract on the view
itself and letting the generator emit the classes.

### Tests (`src/test/java`)

| Test | Covers |
|---|---|
| `jackson/EntityJacksonMapperTest` | JSON round trips: serialize and deserialize the `PersonSummary` view, the generated view helpers, registration into a stock `ObjectMapper` via `EntityJacksonMapper.registerModule`, and deserialization through the boilerplate deserializer |
| `jackson/EntityJacksonChangeSerializerTest` | the change-set patch: one-field patch from the proxy path and from the builder path, the assertion that **both paths produce the same patch** (the exit gate), the current-values-only merge-patch shape (there is no `includePrevious` mode), and the S4 presence distinction — an unchanged field is **absent**, a changed `null` is an explicit `null`, a no-op write produces no patch, and `clearChanges()` empties it |
| `person/ViewChangeTrackingStateSharingTest` | the S5 state-sharing acceptance test, run against **both** materializations through the *same* fixture view type: the array-backed proxy and a hand-written tracking holder standing in for the generated `<View>BuilderTracking`. The assertion that catches a `changesBuilder()` that returns a copy is the one that matters |
| `jackson/EntityJacksonJmhBenchmark`, `PersonSummaryFileBenchmark*` | JMH benchmarks and the file-scan runner; the numbers are discussed in the jackson module's README |
| `jackson/PersonSummaryConcreteImpl`, `PersonSummaryBoilerplateDeserializer`, `PersonSummaryGeneratedDeserializer` | benchmark-only stand-ins for a concrete implementation and for generated deserializers |

`PersonSummaryConcreteImpl` and the benchmark deserializers are
**benchmark scaffolding**, not a supported API: they exist to measure the
proxy-versus-concrete gap, and the tooling module is what actually emits
production deserializers and adapters.

## Module boundaries

**This module must never be a dependency of
`hipster-entity-tooling`.** The reason is the same one that keeps a
runtime app module off the dev-time tooling, stated in `AGENTS.md` § 2
and [`README.md`](../README.md): the dev-time generator and the runtime
app modules are separate layers, and a dependency from the tooling module
onto the integration fixtures would invert the layering and create a
dependency cycle through `hipster-entity-core`.

Concretely:

- `hipster-entity-tooling` compiles against `hipster-entity-api` only;
  its `hipster-entity-core` dependency is `test`-scoped.
- `hipster-entity-test` depends on `hipster-entity-api` and
  `hipster-entity-core` at compile scope, and on
  `hipster-entity-jackson` at test scope.
- A test that must both **run the generator** and **compile/run its
  output** therefore cannot live here. It lives in
  `hipster-entity-tooling` (`GeneratedTrackingBuilderContractTest`),
  which compiles the emitted source into a temporary directory, loads it
  in a child class loader, and drives it there.

## Running the tests

The recorded command is the JDK-25 wrapper, which includes this module:

```bat
scripts\mvn-jdk25.cmd
```

That expands to
`mvn -o -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example -am test`.

## See also

- [`hipster-entity-core/README.md`](../hipster-entity-core/README.md) — the
  ordinal-array runtime and the S1/S4/S5/D5 contracts these tests assert.
- [`hipster-entity-jackson/README.md`](../hipster-entity-jackson/README.md) —
  the JSON integration and its benchmark results.
- [`hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md) —
  where the generated-materialization test lives, and why.
- [Jackson setup](../doc-hipster-entity/user/patterns/jackson-setup.md) —
  the user-facing JSON guide.
